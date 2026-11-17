package app.shiguang.core.model.collab

import app.shiguang.core.model.collab.relay.MeProfile
import app.shiguang.core.model.collab.relay.RelayAuthException
import app.shiguang.core.model.collab.relay.TokenPair
import app.shiguang.core.model.collab.relay.changePassword
import app.shiguang.core.model.collab.relay.fetchMe
import app.shiguang.core.model.collab.relay.login
import app.shiguang.core.model.collab.relay.logoutRemote
import app.shiguang.core.model.collab.relay.patchMe
import app.shiguang.core.model.collab.relay.refresh
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** 账号合法性与服务端同口径（register.go：小写字母/数字/下划线 3~32；密码 ≥8）。
 *  UI 表单校验与本类守卫共用这一份，不许两处漂移。 */
fun isValidUsername(u: String): Boolean = Regex("^[a-z0-9_]{3,32}$").matches(u)
fun isValidPassword(p: String): Boolean = p.length >= 8

enum class AccountStatus {
    /** 尚未探测（未读安全区，不预判断）。 */
    UNKNOWN,

    /** 未登录：协同功能引导去登录。 */
    LOGGED_OUT,

    /** 已登录：凭据在内存/安全区，中继会话可用。 */
    LOGGED_IN,
}

/**
 * 登录态机（账号体系 Plan A 的端侧唯一状态入口；lib/collab/account.dart Kotlin 等价）。
 *
 * 三态：未探测（[restore] 前，不预读安全区——初始化热路径不碰平台插件）→
 * 未登录 → 已登录（凭据驻内存 + 安全区，冷启动 [restore] 复原）。
 *
 * ## 门禁语义（2026-10-11 拍板）
 *
 * 协同功能的可用判定从「vault 已解锁（MSK 在内存）」改为「本类已登录」。
 * E2EE 密钥材料由 [KeyStore] 端侧自动托管，与登录互不牵扯；登录态只决定
 * **与中继的会话**（token 颁发/续期/好友域/协同动作）。登出仅清会话凭据，
 * 不动本地密钥与协同数据。
 *
 * ## Token 存放红线（契约 §5：access 只存内存）
 *
 * access_token **只驻内存**，进程被杀即随行；refresh_token 落设备安全区，
 * 换进程靠它换新。refresh 是**强制轮换**：每次换新后旧票立即作废（重用 →
 * 401 TOKEN_REVOKED），故换新成功后必须第一时间把新票写回安全区。
 * 崩溃一致性取舍：轮换与落盘之间进程被杀 → 本地旧票已被服务端拉黑 → 下次
 * restore 换新得 401 → 降级未登录重新登录。安全优先于会话存续，可接受。
 *
 * ## 踢会话语义（契约 §3.4：改密即全端下线）
 *
 * 改密成功后服务端 Bump 用户令牌纪元，该账号全部既有 token 即时失效——本类随即
 * 清空本地 token 与凭据，转回未登录。refresh 遇 401（轮换重用/登出/纪元落后）同理。
 *
 * Dart ChangeNotifier → Kotlin 只读 StateFlow（状态单一流出纪律，K5/K8 同则）。
 */
class AccountSession(
    private val client: okhttp3.OkHttpClient,
    private val baseUrl: String,
    val keyStore: KeyStore,
    val identityStore: IdentityStore,
    /** 凭据持久化通道（生产=SecureSecretStore；测试注入内存实现；null=不持久化）。 */
    private val secrets: SecretStore? = null,
    private val nowSec: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    companion object {
        private const val SESSION_KEY = "collab.account.session"
    }

    private val sessionScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default,
    )
    private val statusFlow = MutableStateFlow(AccountStatus.UNKNOWN)
    val status: StateFlow<AccountStatus> get() = statusFlow.asStateFlow()

    private var usernameValue: String? = null
    private var userIdValue: String? = null
    private var access: String? = null // 只驻内存（§5 红线）
    private var refreshToken: String? = null // 内存 + 安全区
    private var accessExpiresAt: Long? = null // unix 秒；仅内存

    /** 最近一次见到的服务端金库信封（/me 或换机恢复取回）：改密重封的原料。
     *  仅驻内存；null=本账号无金库（改密走纯改密兼容路径）。 */
    private var lastVaultEnvelope: ByteArray? = null

    val isLoggedIn: Boolean get() = statusFlow.value == AccountStatus.LOGGED_IN

    /** 当前账号的用户名（未登录为 null）。服务端归一小写存储。 */
    val username: String? get() = usernameValue

    /** 当前账号的 user_id（服务端 UUID）。换机冷启动可能暂缺（下次登录补齐）。 */
    val userId: String? get() = userIdValue

    /** 登录态探测未完成前的门禁等待（restore 只在冷启动跑一次；调用方先 await 再判）。 */
    suspend fun ensureRestored() {
        if (statusFlow.value != AccountStatus.UNKNOWN) return
        restore()
    }

    /** 冷启动恢复登录态（只读安全区，不触网）。access 不落盘：restore 后首次
     *  [ensureAccessToken] 用 refresh 换新。 */
    suspend fun restore() {
        val store = secrets
        if (store == null) {
            statusFlow.value = AccountStatus.LOGGED_OUT
            return
        }
        val raw = try {
            store.read(SESSION_KEY)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (raw == null) {
            statusFlow.value = AccountStatus.LOGGED_OUT
            return
        }
        try {
            val j = Json.parseToJsonElement(raw).jsonObject
            usernameValue = strField(j, "username")
            userIdValue = strField(j, "user_id")
            refreshToken = strField(j, "refresh_token")
            statusFlow.value = if (refreshToken.isNullOrEmpty()) {
                AccountStatus.LOGGED_OUT
            } else {
                AccountStatus.LOGGED_IN
            }
        } catch (_: Exception) {
            statusFlow.value = AccountStatus.LOGGED_OUT
        }
    }

    private fun strField(j: kotlinx.serialization.json.JsonObject, k: String): String? =
        (j[k] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content

    private fun requireValidCredentials(username: String, password: String): String {
        val uname = username.trim().lowercase()
        if (!isValidUsername(uname)) {
            throw IllegalArgumentException("用户名需为 3~32 位小写字母、数字或下划线")
        }
        if (!isValidPassword(password)) {
            throw IllegalArgumentException("密码至少 8 位")
        }
        return uname
    }

    /** 注册并自动登录：本地生成身份材料随注册上传（对用户无感），成功后即持票。 */
    suspend fun register(username: String, password: String, pushToken: String? = null): RegistrationResult {
        val uname = requireValidCredentials(username, password)
        val result = registerAccount(
            keyStore = keyStore,
            identityStore = identityStore,
            client = client,
            baseUrl = baseUrl,
            username = uname,
            password = password,
            pushToken = pushToken,
        )
        // 注册响应不带 Token：用刚注册的凭据立即登录补齐会话。
        establishSession(uname, password, preferUserId = result.userId)
        return result
    }

    /** 密码登录（已有账号；换机重装后的入口）。 */
    suspend fun login(username: String, password: String) {
        val uname = requireValidCredentials(username, password)
        establishSession(uname, password)
    }

    /** 退出登录：服务端拉黑双 token（尽力而为，离线不阻断）+ 本地清凭据。
     *  本地密钥与协同数据不动。 */
    suspend fun logout() {
        val a = access
        if (a != null) {
            // [DEGRADE] 尽力而为：离线时服务端拉黑不了，access 到期自然失效——
            // 契约语义（登出幂等、永远成功），非猜测性吞异常。
            logoutRemote(client, baseUrl, a, refreshToken = refreshToken)
        }
        terminateSession()
    }

    /** 修改密码（Bearer）：成功后服务端 Bump 纪元、全端踢会话——本地随即清空 token
     *  与凭据，转回未登录（UI 回登录表单）。
     *
     *  v3 拍板（改密即原子重封）：改密前先用旧密码解出金库，再用新密码重封，
     *  new_recovery_vault 与新哈希同包提交（服务端单事务落库）。本地无金库则缺省
     *  不重封，纯改密兼容。 */
    suspend fun changePassword(oldPassword: String, newPassword: String) {
        if (!isValidPassword(newPassword)) {
            throw IllegalArgumentException("密码至少 8 位")
        }
        var resealed: ByteArray? = null
        val vault = lastVaultEnvelope
        if (vault != null) {
            // 解包旧钥 → 新钥重封：两步全在内存，失败即中止改密（不产生死金库）。
            // 重封必须用**金库里解出的原身份密钥**（msk）——本机 MSK 可能是新设备
            // 新生成的，用它会偷换身份（金库语义=换机后还原服务端身份）。
            val keys = RecoveryVaultCodec().openVault(password = oldPassword, envelope = vault)
            resealed = RecoveryVaultCodec().sealVault(
                password = newPassword,
                msk = keys.msk,
                identityPriv = keys.identityPriv,
                spkPriv = keys.spkPriv,
            )
        }
        changePassword(
            client = client,
            baseUrl = baseUrl,
            accessToken = ensureAccessToken(),
            oldPassword = oldPassword,
            newPassword = newPassword,
            newRecoveryVault = resealed,
        )
        // 服务端已踢全部会话：本地 token/凭据全灭，等重新登录。
        terminateSession()
    }

    /**
     * 换机恢复（v3 拍板）：登录成功后本机无协同身份时调用——GET /v1/users/me 取
     * 金库 → 登录密码派生 KEK 解出 MSK+身份私钥+SPK 私钥 → 落本机安全区与
     * [IdentityStore]，免密无感就绪。
     *
     * 密码错误/金库损坏抛 [RecoveryVaultException]：UI 必须明示「重新生成身份」
     * 流程并预告后果，**禁止静默半盲**。返回 true=恢复成功；false=服务端无金库。
     */
    suspend fun restoreIdentityFromVault(password: String, username: String): Boolean {
        val profile = me()
        val vault = profile.recoveryVault
        if (vault == null || vault.isEmpty()) return false
        val keys = RecoveryVaultCodec().openVault(password = password, envelope = vault)
        // 密钥落安全区（MSK）+ 身份记录落 IdentityStore（两段私钥仍以 MSK 封存）。
        keyStore.importMsk(keys.msk)
        val sealedId = keyStore.seal(keys.identityPriv)
        val sealedSpk = keyStore.seal(keys.spkPriv)
        identityStore.save(
            IdentityRecord(
                userId = profile.userId,
                sealedIdentityPrivateKey = sealedId,
                signedPreKeyPub = ByteArray(0),
                opkRemaining = 0,
                sealedSignedPreKeyPrivate = sealedSpk,
            ),
        )
        lastVaultEnvelope = vault
        return true
    }

    /** 当前有效的 access token：内存缓存未到期直接用；无票/到期（留 60s 余量）用
     *  refresh 轮换换新并立即落盘。未登录即抛。 */
    suspend fun ensureAccessToken(): String {
        if (!isLoggedIn || refreshToken == null) {
            throw IllegalStateException("未登录：协同功能需要先登录账号")
        }
        val cached = access
        val exp = accessExpiresAt
        if (cached != null && exp != null && exp - 60 > nowSec()) {
            return cached
        }
        try {
            val pair = refresh(client, baseUrl, refreshToken!!)
            access = pair.access
            accessExpiresAt = pair.accessExpiresAt
            refreshToken = pair.refresh
            // 轮换纪律：旧 refresh 已被服务端拉黑，新票必须立即落盘（进程被杀
            // 也不得退回旧票——重用会被判 TOKEN_REVOKED）。
            persistRefresh()
            return pair.access
        } catch (e: RelayAuthException) {
            if (e.statusCode == 401) {
                // 轮换重用（TOKEN_REVOKED）/改密踢会话（TOKEN_STALE）/refresh 过期：
                // 会话终结，等用户重新登录。
                terminateSession()
            }
            throw e
        }
    }

    /** 自账户读回（GET /v1/users/me）：换机恢复链路的取回通道。取回的金库信封缓存
     *  到内存，供改密重封使用。 */
    suspend fun me(): MeProfile {
        val profile = fetchMe(client, baseUrl, ensureAccessToken())
        val vault = profile.recoveryVault
        if (vault != null && vault.isNotEmpty()) lastVaultEnvelope = vault
        return profile
    }

    /** 自账户更新（PATCH /v1/users/me）：字段可选独立生效；pushToken 传空串=清空。 */
    suspend fun updateMe(
        encryptedProfile: ByteArray? = null,
        recoveryVault: ByteArray? = null,
        pushToken: String? = null,
    ) = patchMe(
        client = client,
        baseUrl = baseUrl,
        accessToken = ensureAccessToken(),
        encryptedProfile = encryptedProfile,
        recoveryVault = recoveryVault,
        pushToken = pushToken,
    )

    /** 登录共用尾段：密码换票 → 定位 user_id → 持久化 refresh → 广播登录态。
     *  user_id 优先取本地协同身份记录；没有（换机重装）则用 /me 补齐，失败留空。 */
    private suspend fun establishSession(uname: String, password: String, preferUserId: String? = null) {
        val pair = login(client, baseUrl, uname, password)
        access = pair.access
        accessExpiresAt = pair.accessExpiresAt
        refreshToken = pair.refresh
        usernameValue = uname
        var uid = preferUserId ?: identityStore.load()?.userId
        if (uid == null || uid.isEmpty()) {
            uid = try {
                fetchMe(client, baseUrl, pair.access).userId
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                "" // 离线等场景：登录成功但 user_id 暂缺，不阻断
            }
        }
        if (uid.isNotEmpty()) userIdValue = uid
        persistRefresh()
        statusFlow.value = AccountStatus.LOGGED_IN
    }

    /** 把「最新 refresh + 账号标识」写回安全区（access 绝不落盘）。 */
    private suspend fun persistRefresh() {
        val store = secrets ?: return
        val payload = buildJsonObject {
            if (usernameValue != null) put("username", usernameValue)
            if (userIdValue != null) put("user_id", userIdValue)
            if (refreshToken != null) put("refresh_token", refreshToken)
        }
        store.write(SESSION_KEY, payload.toString())
    }

    /** 本地会话终结（内存凭据全清 + 安全区凭据删除）→ 未登录。
     *  不触网：远端拉黑由调用方决定是否需要。 */
    private fun terminateSession() {
        access = null
        accessExpiresAt = null
        refreshToken = null
        usernameValue = null
        userIdValue = null
        val store = secrets
        if (store != null) {
            // 异步删不阻塞状态广播：删除失败至多多留一份死票（下次 restore 换新
            // 401 仍会降级），不反过来拖住 UI。
            sessionScope.launch {
                try {
                    store.delete(SESSION_KEY)
                } catch (_: Exception) {
                }
            }
        }
        statusFlow.value = AccountStatus.LOGGED_OUT
    }

    // ---- 测试注入口（Dart @visibleForTesting 镜像；不落安全区） ----------------

    /** 绕过网络直接置为已登录。票据未带过期时刻（accessExpiresAt==0）时视为刚签发
     *  （now+2h），避免被判过期。 */
    fun debugEstablishSession(username: String, userId: String, tokens: TokenPair) {
        usernameValue = username
        userIdValue = userId
        access = tokens.access
        refreshToken = tokens.refresh
        accessExpiresAt = if (tokens.accessExpiresAt == 0L) nowSec() + 7200 else tokens.accessExpiresAt
        statusFlow.value = AccountStatus.LOGGED_IN
    }

    /** 把缓存的 access 标记为已过期（强制下次取票走 refresh），refresh token 保持真票。 */
    fun debugExpireAccessToken() {
        if (access == null) return
        accessExpiresAt = 1
    }
}
