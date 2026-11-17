package app.shiguang.core.model.collab.relay

import app.shiguang.core.model.collab.AccountSession
import app.shiguang.core.model.collab.BundleProposalService
import app.shiguang.core.model.collab.CollabBundleRepository
import app.shiguang.core.model.collab.FriendshipTicket
import app.shiguang.core.model.collab.IdentityRecord
import app.shiguang.core.model.collab.IdentityStore
import app.shiguang.core.model.collab.KeyStore
import app.shiguang.core.model.collab.OutboxDrainer
import app.shiguang.core.model.collab.OutboxRepository
import app.shiguang.core.model.collab.PendingProposalReconciler
import app.shiguang.core.model.SqlStore
import app.shiguang.core.model.collab.signFriendshipTicket
import app.shiguang.core.model.collab.BundleKeySource

/** 从本地身份记录还原 Ed25519 身份私钥 raw 种子（lib/collab/relay_session.dart Kotlin 等价）。
 *
 * IdentityRecord 存的是 Ed25519 raw 种子（32B）——原样开封返回（restore 后公钥
 * 与原公钥一致，由测试钉死这一前提；签名链一律 seed 携带，Conscrypt 边界规避）。 */
fun restoreIdentityKeySeed(keyStore: KeyStore, record: IdentityRecord): ByteArray {
    return kotlinx.coroutines.runBlocking {
        keyStore.ensureReady() // Dart restoreIdentityKeyPair 对位：先备钥再开封
        keyStore.open(record.sealedIdentityPrivateKey)
    }
}

/** 从本地身份记录还原 X25519 SPK 私钥 seed（**被邀端解开 K_bundle 信封**用）。
 *
 * 与 [restoreIdentityKey] 同法：私钥字节即种子。没有它，被邀端收到邀约也只能看着
 * 密文——解密用的是「发起人 EK 公钥 × 我这把 SPK 私钥」的 DH。
 *
 * 存量身份记录未封存 SPK 私钥（可空字段）时抛 [IllegalStateException]：这是**数据
 * 缺口**不是运行时故障，调用方按半盲降级处理（重新注册可恢复）。 */
fun restoreSignedPreKeySeed(keyStore: KeyStore, record: IdentityRecord): ByteArray {
    val sealed = record.sealedSignedPreKeyPrivate
        ?: throw IllegalStateException("本机身份未封存 SPK 私钥（存量记录）：解不开被邀的 K_bundle")
    return kotlinx.coroutines.runBlocking {
        keyStore.ensureReady() // Dart restoreSignedPreKeyPair 对位：先备钥再开封
        keyStore.open(sealed)
    }
}

/**
 * 中继会话：token 取自 [AccountSession]（登录态单一来源），业务面挂好友域与成员
 * 公钥。本地发起 bundle 只缺三样东西真机才能给：①我是谁（本地身份）；②能邀谁
 * （双向 accepted 名单）；③信封怎么封（每位成员的 X25519 广播公钥）——本类把
 * 三样合装，让 UI 只管表单，不管密码学与网络。
 *
 * ## 门禁语义（2026-10-11 拍板：登录态判定）
 *
 * 会话可用性只剩两个判定：**账号已登录**（token 由 AccountSession 颁发）与
 * **本机有协同身份**（IdentityRecord，注册时落账）。二者缺一即返回 null / 抛错，
 * UI 转登录或注册引导——宁可空态，不可伪造。
 */
class RelaySession(
    private val client: okhttp3.OkHttpClient,
    val baseUrl: String,
    private val identityStore: IdentityStore,
    private val account: AccountSession,
) {
    /** 当前有效的 access token（过期续期由账号会话托管）。 */
    suspend fun accessToken(): String = account.ensureAccessToken()

    /** 双向 accepted 好友（可邀名单）。 */
    suspend fun acceptedFriends(): List<FriendView> =
        fetchAcceptedFriends(client, baseUrl, accessToken())

    /** 某成员的 X25519 广播公钥（封 K_bundle 信封用）。 */
    suspend fun memberSpk(userId: String): ByteArray =
        fetchPeerKeys(client, baseUrl, accessToken(), userId).signedPrekeyPub

    /** 某成员 Ed25519 身份公钥（安全码比对用）。 */
    suspend fun peerIdentityPublicKey(userId: String): ByteArray =
        fetchPeerKeys(client, baseUrl, accessToken(), userId).identityKeyPub

    /** 拉全部好友（含 pending / blocked，UI 自行区分状态）。 */
    suspend fun listFriends(): List<FriendView> = fetchFriends(client, baseUrl, accessToken())

    /** 签发自己的名片 Ticket（Ed25519 私钥签名；无本地身份即抛）。
     *  encKeyPub 取本机签名预密钥公钥（X25519），随证带出供 B 端 ECIES 自报家门。 */
    suspend fun issueTicket(): FriendshipTicket {
        val rec = identityStore.load()
            ?: throw IllegalStateException("本机尚未注册协同身份（§1.5），无法签发名片")
        val keySeed = restoreIdentityKeySeed(account.keyStore, rec)
        return signFriendshipTicket(
            identityKeySeed = keySeed,
            issuerUserId = rec.userId,
            encKeyPub = rec.signedPreKeyPub,
        )
    }

    /** 提交扫到的对方名片，发起好友邀请（服务端验签 + 过期 + nonce 一次性）。 */
    suspend fun submitInvite(ticket: FriendshipTicket): String =
        submitFriendInvite(client, baseUrl, accessToken(), ticket)

    /** 按用户名搜索对方并发起好友邀请（账号体系搜索路径，§3.5/3.6）。 */
    suspend fun requestFriendByUsername(username: String, encryptedIntro: String = ""): String {
        val target = lookupUser(client, baseUrl, accessToken(), username)
        return submitFriendRequest(client, baseUrl, accessToken(), toUserId = target.userId, encryptedIntro = encryptedIntro)
    }

    /** 被邀方处置一条邀请（accept=true → accepted；false → blocked）。
     *  服务端强制 inviteId 的被邀方必须是本机身份——非自己的邀请直接 403/404，安全。 */
    suspend fun acceptInvite(inviteId: String, accept: Boolean): String =
        acceptFriendInvite(client, baseUrl, accessToken(), inviteId, accept)

    /** 解除关系（删好友/撤回申请/解除拉黑三合一，双向命中；此后可重新申请）。
     *  无关系 → 404 INVITE_NOT_FOUND。 */
    suspend fun removeFriend(userId: String): String =
        removeFriend(client, baseUrl, accessToken(), userId)

    /** 主动拉黑（无需既有关系；pending/accepted 一并转 blocked）。
     *  配额 ≤500，超限 400 EXCEED_BLOCKED_LIST。 */
    suspend fun blockFriend(userId: String): String =
        blockFriend(client, baseUrl, accessToken(), userId)

    /** 我的 Ed25519 身份公钥原始字节（安全码比对用）。
     *  seed → 公钥 raw：BC Ed25519PrivateKeyParameters.generatePublicKey（JDK 私钥不带公钥分量）。 */
    suspend fun myIdentityPublicKey(): ByteArray {
        val rec = identityStore.load() ?: throw IllegalStateException("本机尚未注册协同身份（§1.5）")
        val seed = account.keyStore.open(rec.sealedIdentityPrivateKey)
        return org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters(seed).generatePublicKey().encoded
    }

    /** 本机协同身份 id（好友 invite_id 拼装用）。 */
    suspend fun myUserId(): String? = identityStore.load()?.userId

    /** 处置一条「对方发来的」好友邀请（accept=true → accepted；false → blocked）。
     *
     *  invite_id 按约定拼成 {peer}:{me}——服务端强制被邀方必须是本机身份，故发起方
     *  点这个只会拿到 404（安全），UI 据此提示「等待对方确认」而非误判。 */
    suspend fun acceptInviteFromPeer(peerUserId: String, accept: Boolean): String {
        val me = myUserId() ?: throw IllegalStateException("本机尚未注册协同身份（§1.5）")
        return acceptFriendInvite(client, baseUrl, accessToken(), "$peerUserId:$me", accept)
    }
}

/** 本地发起一整套协作者（UI 只捏表单）。 */
class BundleComposeKit(
    val proposer: BundleProposalService,
    val drainer: OutboxDrainer,
    /** 可邀名单；为空即尚无 accepted 好友（服务端 PROPOSE 也认双向 accepted）。 */
    val loadFriends: suspend () -> List<FriendView>,
) {
    /** 立即尝试弹出：在线发出（含认祖归宗）；离线则由发件箱原样接管，联网补发。 */
    suspend fun drainNow() = drainer.drain()
}

/**
 * 真机装配：本地身份 + 本地库 + baseUrl → [BundleComposeKit]。
 *
 * **返回 null = 本机未注册协同身份**（「不是现在」）：UI 转注册引导，不做「先发了
 * 再说」的降级。登录态由 UI 先行判定（未登录给登录引导）。
 * （Dart 的 SecureIdentityStore/dotenv 兜底属平台装配面，:app 侧注入，本层收显式参。）
 */
suspend fun buildBundleComposeKit(
    db: SqlStore,
    account: AccountSession,
    identityStore: IdentityStore,
    baseUrl: String,
    client: okhttp3.OkHttpClient = relayHttpClient(),
): BundleComposeKit? {
    val rec = identityStore.load() ?: return null
    val session = RelaySession(client, baseUrl, identityStore, account)
    val keys = BundleKeySource(db, account.keyStore)
    val outbox = OutboxRepository(db)
    val reconciler = PendingProposalReconciler(
        bundles = CollabBundleRepository(db),
        keys = keys,
        outbox = outbox,
    )
    val transport = HttpOutboxTransport(
        client = client,
        baseUrl = session.baseUrl,
        accessToken = { session.accessToken() },
        // KeyRing 取不到钥（半盲）必须抛而非给空钥：由 drainer 归一化为 Offline
        // 保留重试，补拉 K_bundle 后再发——绝不拿假密钥发密文（成员永远解不开）。
        bundleKey = { bundleId, keyVer ->
            val key = keys(bundleId, keyVer)
            if (key == null || key.size != 32) {
                throw IllegalStateException("K_bundle 取不到：半盲，等补拉后再发")
            }
            key
        },
    )
    return BundleComposeKit(
        proposer = BundleProposalService(
            keys = keys,
            outbox = outbox,
            memberSpk = { uid -> session.memberSpk(uid) },
        ),
        drainer = OutboxDrainer(outbox, transport) { entry, ack -> reconciler(entry, ack) },
        loadFriends = { session.acceptedFriends() },
    )
}

/** 真机装配：本地身份 + 本地库 + baseUrl → [RelaySession]（好友域用）。
 *  返回 null = 本机未注册协同身份（与发起套件同口径），UI 转引导，不降级。 */
suspend fun buildFriendsSession(
    account: AccountSession,
    identityStore: IdentityStore,
    baseUrl: String,
    client: okhttp3.OkHttpClient = relayHttpClient(),
): RelaySession? {
    identityStore.load() ?: return null
    return RelaySession(client, baseUrl, identityStore, account)
}
