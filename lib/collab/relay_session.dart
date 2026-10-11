/// 中继登录态会话 + 本地发起链路的真机装配（Phase 4.3 发起切片）。
///
/// 本地发起 bundle 只缺三样东西真机才能给：①我是谁（本地身份）；②能邀谁
/// （`GET /v1/friends` 双向 accepted 名单）；③信封怎么封（每位成员的 X25519
/// 广播公钥）。本文件把这三样合装成一份 [BundleComposeKit]，让 UI 只管表单，
/// 不管密码学与网络。
///
/// ## 门禁语义（2026-10-11 拍板：登录态判定）
///
/// 旧版在此处处检查「vault 已解锁」；账号体系（Plan A）落地后 MSK 由
/// [KeyStore] 端侧自动托管（[KeyStore.ensureReady] 幂等就绪），口令解锁层退役。
/// 会话可用性只剩两个判定：**账号已登录**（token 由 [AccountSession] 颁发）
/// 与 **本机有协同身份**（IdentityRecord，注册时落账）。二者缺一即返回 null
/// / 抛错，UI 转登录或注册引导——宁可空态，不可伪造。
library;

import 'package:cryptography/cryptography.dart';
import 'package:dio/dio.dart';
import 'package:sqflite/sqflite.dart';

import 'account.dart';
import 'crypto/bundle_key_source.dart';
import 'crypto/key_store.dart';
import '../data/collab_repository.dart';
import 'identity_store.dart';
import 'outbox.dart';
import 'propose.dart';
import 'friends_ticket.dart';
import 'relay/auth_api.dart' show lookupUser;
import 'relay/peers_api.dart';
import 'relay/friends_api.dart' as friends;
import 'relay/action_api.dart';
import 'relay/client.dart';
import 'relay_config.dart';

/// 从本地身份记录还原 Ed25519 身份私钥。
///
/// [IdentityRecord] 存的是 `identityKey.extractPrivateKeyBytes()`——Ed25519 的
/// 私钥字节即密钥种子，故可用 `newKeyPairFromSeed` 原样还原（
/// `test/collab_relay_session_test.dart` 用「还原后公钥与原公钥一致」钉死这一前提）。
Future<SimpleKeyPair> restoreIdentityKeyPair({
  required KeyStore keyStore,
  required IdentityRecord record,
}) async {
  await keyStore.ensureReady();
  final priv = await keyStore.open(record.sealedIdentityPrivateKey);
  return Ed25519().newKeyPairFromSeed(priv);
}

/// 从本地身份记录还原 X25519 SPK 密钥对（**被邀端解开 K_bundle 信封**用）。
///
/// 与 [restoreIdentityKeyPair] 同法：私钥字节即种子。没有它，被邀端收到邀约也
/// 只能看着密文——解密用的是「发起人 EK 公钥 × 我这把 SPK 私钥」的 DH。
///
/// 存量身份记录未封存 SPK 私钥（可空字段）时抛 [StateError]：这是**数据缺口**
/// 不是运行时故障，调用方按半盲降级处理（重新注册可恢复）。
Future<SimpleKeyPair> restoreSignedPreKeyPair({
  required KeyStore keyStore,
  required IdentityRecord record,
}) async {
  final sealed = record.sealedSignedPreKeyPrivate;
  if (sealed == null) {
    throw StateError('本机身份未封存 SPK 私钥（存量记录）：解不开被邀的 K_bundle');
  }
  await keyStore.ensureReady();
  final priv = await keyStore.open(sealed);
  return X25519().newKeyPairFromSeed(priv);
}

/// 中继会话：token 取自 [AccountSession]（登录态单一来源），业务面挂好友域
/// 与成员公钥。
class RelaySession {
  final Dio dio;
  final String baseUrl;
  final IdentityStore identityStore;
  final AccountSession account;

  RelaySession({
    required this.dio,
    required this.baseUrl,
    required this.identityStore,
    required this.account,
  });

  /// 当前有效的 access token（过期续期由账号会话托管）。
  Future<String> accessToken() => account.ensureAccessToken();

  /// 双向 accepted 好友（可邀名单）。
  Future<List<FriendView>> acceptedFriends() async =>
      fetchAcceptedFriends(dio, baseUrl, await accessToken());

  /// 某成员的 X25519 广播公钥（封 K_bundle 信封用）。
  Future<List<int>> memberSpk(String userId) async =>
      (await fetchPeerKeys(dio, baseUrl, await accessToken(), userId))
          .signedPrekeyPub;

  /// 某成员 Ed25519 身份公钥（安全码比对用）。
  Future<List<int>> peerIdentityPublicKey(String userId) async =>
      (await fetchPeerKeys(dio, baseUrl, await accessToken(), userId))
          .identityKeyPub;

  /// 拉全部好友（含 pending / blocked，UI 自行区分状态）。
  Future<List<FriendView>> listFriends() async =>
      fetchFriends(dio, baseUrl, await accessToken());

  /// 签发自己的名片 Ticket（Ed25519 私钥签名；无本地身份即抛）。
  ///
  /// [encKeyPub] 取本机签名预密钥公钥（X25519），随证带出供 B 端 ECIES 自报家门。
  Future<FriendshipTicket> issueTicket() async {
    final rec = await identityStore.load();
    if (rec == null) {
      throw StateError('本机尚未注册协同身份（§1.5），无法签发名片');
    }
    final keyPair =
        await restoreIdentityKeyPair(keyStore: account.keyStore, record: rec);
    return signFriendshipTicket(
      identityKeyPair: keyPair,
      issuerUserId: rec.userId,
      encKeyPub: rec.signedPreKeyPub,
    );
  }

  /// 提交扫到的对方名片，发起好友邀请（服务端验签 + 过期 + nonce 一次性）。
  Future<String> submitInvite(FriendshipTicket ticket) async =>
      friends.submitFriendInvite(dio, baseUrl, await accessToken(), ticket);

  /// 按用户名搜索对方并发起好友邀请（账号体系搜索路径，§3.5/3.6）。
  Future<String> requestFriendByUsername(String username,
      {String encryptedIntro = ''}) async {
    final target =
        await lookupUser(dio, baseUrl, await accessToken(), username);
    return friends.submitFriendRequest(dio, baseUrl, await accessToken(),
        toUserId: target.userId, encryptedIntro: encryptedIntro);
  }

  /// 被邀方处置一条邀请（accept=true → accepted；false → blocked）。
  ///
  /// 服务端强制 [inviteId] 的被邀方必须是本机身份——非自己的邀请直接 403/404，安全。
  Future<String> acceptInvite(String inviteId, bool accept) async =>
      friends.acceptFriendInvite(dio, baseUrl, await accessToken(), inviteId, accept);

  /// 解除关系（删好友/撤回申请/解除拉黑三合一，双向命中；此后可重新申请）。
  /// 无关系 → 404 `INVITE_NOT_FOUND`（归一 [RelayFriendsException]）。
  Future<String> removeFriend(String userId) async =>
      friends.removeFriend(dio, baseUrl, await accessToken(), userId);

  /// 主动拉黑（无需既有关系；pending/accepted 一并转 blocked）。
  /// 配额 ≤500，超限 400 `EXCEED_BLOCKED_LIST`。
  Future<String> blockFriend(String userId) async =>
      friends.blockFriend(dio, baseUrl, await accessToken(), userId);

  /// 我的 Ed25519 身份公钥原始字节（安全码比对用）。
  Future<List<int>> myIdentityPublicKey() async {
    final rec = await identityStore.load();
    if (rec == null) throw StateError('本机尚未注册协同身份（§1.5）');
    final keyPair =
        await restoreIdentityKeyPair(keyStore: account.keyStore, record: rec);
    return identityPublicKeyBytes(keyPair);
  }

  /// 本机协同身份 id（好友 invite_id 拼装用）。
  Future<String?> myUserId() async => (await identityStore.load())?.userId;

  /// 处置一条「对方发来的」好友邀请（accept=true → accepted；false → blocked）。
  ///
  /// invite_id 按约定拼成 `{peer}:{me}`——服务端强制被邀方必须是本机身份，故发起方
  /// 点这个只会拿到 404（安全），UI 据此提示「等待对方确认」而非误判。
  Future<String> acceptInviteFromPeer(String peerUserId, bool accept) async {
    final me = await myUserId();
    if (me == null) throw StateError('本机尚未注册协同身份（§1.5）');
    return friends.acceptFriendInvite(
        dio, baseUrl, await accessToken(), '$peerUserId:$me', accept);
  }
}

/// 本地发起一整套协作者（UI 只捏表单）。
class BundleComposeKit {
  final BundleProposalService proposer;
  final OutboxDrainer drainer;

  /// 可邀名单；为空即尚无 accepted 好友（服务端 PROPOSE 也认双向 accepted）。
  final Future<List<FriendView>> Function() loadFriends;

  const BundleComposeKit({
    required this.proposer,
    required this.drainer,
    required this.loadFriends,
  });

  /// 立即尝试弹出：在线发出（含认祖归宗）；离线则由发件箱原样接管，联网补发。
  Future<DrainReport> drainNow() => drainer.drain();
}

/// 真机装配：本地身份 + 本地库 + [RelayConfig] → [BundleComposeKit]。
///
/// **返回 null = 本机未注册协同身份**（「不是现在」）：UI 转注册引导，不做
/// 「先发了再说」的降级。登录态由 UI 先行判定（未登录给登录引导）。
Future<BundleComposeKit?> buildBundleComposeKit(
  Database db, {
  required AccountSession account,
  KeyStore? keyStore,
  IdentityStore? identityStore,
  Dio? dio,
  String? baseUrl,
}) async {
  final store = identityStore ?? SecureIdentityStore();
  final rec = await store.load();
  if (rec == null) return null;
  final ks = keyStore ?? account.keyStore;

  final session = RelaySession(
    dio: dio ?? relayHttpClient(),
    baseUrl: baseUrl ?? RelayConfig.fromEnvironment().baseUrl,
    identityStore: store,
    account: account,
  );
  final keys = BundleKeySource(db, ks);
  final outbox = OutboxRepository(db);
  final reconciler = PendingProposalReconciler(
    bundles: CollabBundleRepository(db),
    keys: keys,
    outbox: outbox,
  );
  final transport = HttpOutboxTransport(
    dio: session.dio,
    baseUrl: session.baseUrl,
    accessToken: session.accessToken,
    // KeyRing 取不到钥（半盲）必须抛而非给空钥：由 drainer 归一化为 Offline
    // 保留重试，补拉 K_bundle 后再发——绝不拿假密钥发密文（成员永远解不开）。
    bundleKey: (bundleId, keyVer) async {
      final key = await keys(bundleId, keyVer);
      if (key == null || key.length != 32) {
        throw StateError('K_bundle 取不到：半盲，等补拉后再发');
      }
      return key;
    },
  );
  return BundleComposeKit(
    proposer: BundleProposalService(
      keys: keys,
      outbox: outbox,
      memberSpk: session.memberSpk,
    ),
    drainer: OutboxDrainer(outbox, transport,
        onBundleCreated: reconciler.call),
    loadFriends: session.acceptedFriends,
  );
}

/// 真机装配：本地身份 + 本地库 + [RelayConfig] → [RelaySession]（好友域用）。
///
/// 返回 null = 本机未注册协同身份（与发起套件同口径），UI 转引导，不做
/// 「先发了再说」的降级。
Future<RelaySession?> buildFriendsSession(
  Database db, {
  required AccountSession account,
  KeyStore? keyStore,
  IdentityStore? identityStore,
  Dio? dio,
  String? baseUrl,
}) async {
  final store = identityStore ?? SecureIdentityStore();
  final rec = await store.load();
  if (rec == null) return null;
  return RelaySession(
    dio: dio ?? relayHttpClient(),
    baseUrl: baseUrl ?? RelayConfig.fromEnvironment().baseUrl,
    identityStore: store,
    account: account,
  );
}
