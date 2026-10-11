import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';

import 'crypto/key_store.dart';
import 'crypto/secret_store.dart';
import 'identity_store.dart';
import 'recovery_vault.dart';
import 'registration.dart';
import 'relay/auth_api.dart' as auth;

/// 账号合法性与服务端同口径（`register.go`：小写字母/数字/下划线 3~32；密码 ≥8）。
/// UI 表单校验与本类守卫共用这一份，不许两处漂移。
bool isValidUsername(String u) => RegExp(r'^[a-z0-9_]{3,32}$').hasMatch(u);
bool isValidPassword(String p) => p.length >= 8;

/// 登录态机（账号体系 Plan A 的端侧唯一状态入口）。
///
/// 三态：未探测（[restore] 前，不预读安全区——初始化热路径不碰平台插件）→
/// 未登录 → 已登录（凭据驻内存 + 安全区，冷启动 [restore] 复原）。
///
/// ## 门禁语义（2026-10-11 拍板）
///
/// 协同功能的可用判定从「vault 已解锁（MSK 在内存）」改为「本类已登录」。
/// E2EE 密钥材料由 [KeyStore] 端侧自动托管，与登录互不牵扯；登录态只决定
/// **与中继的会话**（token 颁发/续期/好友域/协同动作）。登出仅清会话凭据，
/// 不动本地密钥与协同数据。
///
/// ## Token 存放红线（契约 §5：access 只存内存）
///
/// `access_token` **只驻内存**，进程被杀即随行；`refresh_token` 落设备安全区，
/// 换进程靠它换新。refresh 是**强制轮换**：每次换新后旧票立即作废（重用 →
/// 401 `TOKEN_REVOKED`），故换新成功后必须第一时间把新票写回安全区。
/// 崩溃一致性取舍：轮换与落盘之间进程被杀 → 本地旧票已被服务端拉黑 → 下次
/// restore 换新得 401 → 降级未登录重新登录。安全优先于会话存续，可接受。
///
/// ## 踢会话语义（契约 §3.4：改密即全端下线）
///
/// 改密成功后服务端 Bump 用户令牌纪元，该账号全部既有 token 即时失效
/// （后续请求 401 `TOKEN_STALE`）——本类随即清空本地 token 与凭据，转回
/// 未登录等用户重新登录。refresh 遇 401（轮换重用/登出/纪元落后）同理降级。
class AccountSession extends ChangeNotifier {
  AccountSession({
    required this.dio,
    required this.baseUrl,
    required this.keyStore,
    required this.identityStore,
    this.secrets,
  });

  final Dio dio;
  final String baseUrl;
  final KeyStore keyStore;
  final IdentityStore identityStore;

  /// 凭据持久化通道（生产=SecureSecretStore；测试注入内存实现；null=不持久化）。
  final SecretStore? secrets;

  static const _sessionKey = 'collab.account.session';

  AccountStatus _status = AccountStatus.unknown;
  String? _username;
  String? _userId;
  String? _access; // 只驻内存（§5 红线）
  String? _refreshToken; // 内存 + 安全区
  int? _accessExpiresAt; // unix 秒；仅内存

  /// 最近一次见到的服务端金库信封（`/me` 或换机恢复取回）：改密重封的原料。
  /// 仅驻内存；null=本账号无金库（改密走纯改密兼容路径）。
  List<int>? _lastVaultEnvelope;

  AccountStatus get status => _status;
  bool get isLoggedIn => _status == AccountStatus.loggedIn;

  /// 登录态探测未完成前的门禁等待。
  ///
  /// restore 只在冷启动 main 里跑一次；若门禁（好友/发起入口）在 restore
  /// 完成前现查 isLoggedIn，会把 unknown 误判成未登录（真机上安全区读有
  /// 平台插件延迟，时序并不保证）。调用方 await 本方法后再判 isLoggedIn。
  Future<void> ensureRestored() async {
    if (_status != AccountStatus.unknown) return;
    await restore();
  }

  /// 当前账号的用户名（未登录为 null）。服务端归一小写存储。
  String? get username => _username;

  /// 当前账号的 user_id（服务端 UUID）。换机冷启动本机无身份记录且 /me 不可达
  /// 时可能暂缺（下次登录补齐）。
  String? get userId => _userId;

  /// 冷启动恢复登录态（只读安全区，不触网）。access 不落盘：restore 后首次
  /// [ensureAccessToken] 用 refresh 换新。
  Future<void> restore() async {
    final store = secrets;
    if (store == null) {
      _status = AccountStatus.loggedOut;
      notifyListeners();
      return;
    }
    final raw = await store.read(_sessionKey);
    if (raw == null) {
      _status = AccountStatus.loggedOut;
      notifyListeners();
      return;
    }
    try {
      final j = jsonDecode(raw) as Map<String, dynamic>;
      _username = j['username'] as String?;
      _userId = j['user_id'] as String?;
      _refreshToken = j['refresh_token'] as String?;
      if (_refreshToken == null || _refreshToken!.isEmpty) {
        _status = AccountStatus.loggedOut;
      } else {
        _status = AccountStatus.loggedIn;
      }
    } on FormatException {
      _status = AccountStatus.loggedOut;
    }
    notifyListeners();
  }

  /// 注册并自动登录：本地生成身份材料随注册上传（对用户无感），成功后即持票。
  ///
  /// 用户名在端侧归一小写（与服务端同口径）；非法即抛 [ArgumentError]。
  Future<RegistrationResult> register({
    required String username,
    required String password,
    String? pushToken,
  }) async {
    final uname = username.trim().toLowerCase();
    if (!isValidUsername(uname)) {
      throw ArgumentError('用户名需为 3~32 位小写字母、数字或下划线');
    }
    if (!isValidPassword(password)) {
      throw ArgumentError('密码至少 8 位');
    }
    final result = await registerAccount(
      keyStore: keyStore,
      identityStore: identityStore,
      dio: dio,
      baseUrl: baseUrl,
      username: uname,
      password: password,
      pushToken: pushToken,
    );
    // 注册响应不带 Token：用刚注册的凭据立即登录补齐会话。
    await _establishSession(uname, password, preferUserId: result.userId);
    return result;
  }

  /// 密码登录（已有账号；换机重装后的入口）。
  Future<void> login({
    required String username,
    required String password,
  }) async {
    final uname = username.trim().toLowerCase();
    if (!isValidUsername(uname)) {
      throw ArgumentError('用户名需为 3~32 位小写字母、数字或下划线');
    }
    if (!isValidPassword(password)) {
      throw ArgumentError('密码至少 8 位');
    }
    await _establishSession(uname, password);
  }

  /// 退出登录：服务端拉黑双 token（尽力而为，离线不阻断）+ 本地清凭据。
  /// 本地密钥与协同数据不动。
  Future<void> logout() async {
    final access = _access;
    final refresh = _refreshToken;
    if (access != null) {
      // [DEGRADE] 尽力而为：离线时服务端拉黑不了，access 到期自然失效——
      // 契约语义（登出幂等、永远成功），非猜测性吞异常。
      await auth.logoutRemote(dio, baseUrl, access, refreshToken: refresh);
    }
    _terminateSession();
  }

  /// 修改密码（Bearer）：成功后服务端 Bump 纪元、全端踢会话——本地随即清空
  /// token 与凭据，转回未登录（UI 回登录表单）。
  ///
  /// v3 拍板（改密即原子重封）：改密前先用旧密码解出金库，再用新密码重封，
  /// `new_recovery_vault` 与新哈希同包提交（服务端单事务落库）。本地无金库
  /// （如注册后从未走 v3 的存量账号）则缺省不重封，纯改密兼容。
  Future<void> changePassword({
    required String oldPassword,
    required String newPassword,
  }) async {
    if (!isValidPassword(newPassword)) {
      throw ArgumentError('密码至少 8 位');
    }
    List<int>? resealed;
    final vault = _lastVaultEnvelope;
    if (vault != null) {
      // 解包旧钥 → 新钥重封：两步全在内存，失败即中止改密（不产生死金库）。
      // 重封必须用**金库里解出的原身份密钥**（keys.msk）——本机 MSK 可能是
      // 新设备新生成的，用它会偷换身份（金库语义=换机后还原服务端身份）。
      final keys = await RecoveryVaultCodec()
          .openVault(password: oldPassword, envelope: Uint8List.fromList(vault));
      resealed = await RecoveryVaultCodec().sealVault(
        password: newPassword,
        msk: keys.msk,
        identityPriv: keys.identityPriv,
        spkPriv: keys.spkPriv,
      );
    }
    await auth.changePassword(dio, baseUrl, await ensureAccessToken(),
        oldPassword: oldPassword,
        newPassword: newPassword,
        newRecoveryVault: resealed);
    // 服务端已踢全部会话：本地 token/凭据全灭，等重新登录。
    _terminateSession();
  }

  /// 换机恢复（v3 拍板）：登录成功后本机无协同身份时调用——
  /// `GET /v1/users/me` 取金库 → 登录密码派生 KEK 解出 MSK+身份私钥+SPK 私钥
  /// → 落本机安全区与 [IdentityStore]，免密无感就绪。
  ///
  /// 密码错误/金库损坏抛 [RecoveryVaultException]：UI 必须明示「重新生成身份」
  /// 流程并预告后果（safety number 全变/好友重新建联/K_bundle 全废），
  /// **禁止静默半盲**。返回 true=恢复成功；false=服务端无金库（新账号，无需恢复）。
  Future<bool> restoreIdentityFromVault({
    required String password,
    required String username,
  }) async {
    final profile = await me();
    final vault = profile.recoveryVault;
    if (vault == null || vault.isEmpty) return false;
    final keys = await RecoveryVaultCodec()
        .openVault(password: password, envelope: Uint8List.fromList(vault));
    // 密钥落安全区（MSK）+ 身份记录落 IdentityStore（两段私钥仍以 MSK 封存）。
    await keyStore.importMsk(keys.msk);
    final sealedId = await keyStore.seal(keys.identityPriv);
    final sealedSpk = await keyStore.seal(keys.spkPriv);
    await identityStore.save(IdentityRecord(
      userId: profile.userId,
      sealedIdentityPrivateKey: sealedId,
      signedPreKeyPub: const [],
      opkRemaining: 0,
      sealedSignedPreKeyPrivate: sealedSpk,
    ));
    _lastVaultEnvelope = vault;
    return true;
  }

  /// 当前有效的 access token：内存缓存未到期直接用；无票/到期（留 60s 余量）
  /// 用 refresh 轮换换新并立即落盘。未登录即抛。
  Future<String> ensureAccessToken() async {
    if (!isLoggedIn || _refreshToken == null) {
      throw StateError('未登录：协同功能需要先登录账号');
    }
    final cached = _access;
    final exp = _accessExpiresAt;
    if (cached != null && exp != null && exp - 60 > _nowSec()) {
      return cached;
    }
    try {
      final pair =
          await auth.refresh(dio, baseUrl, _refreshToken!);
      _access = pair.access;
      _accessExpiresAt = pair.accessExpiresAt;
      _refreshToken = pair.refresh;
      // 轮换纪律：旧 refresh 已被服务端拉黑，新票必须立即落盘（进程被杀
      // 也不得退回旧票——重用会被判 TOKEN_REVOKED）。
      await _persistRefresh();
      return pair.access;
    } on auth.RelayAuthException catch (e) {
      if (e.statusCode == 401) {
        // 轮换重用（TOKEN_REVOKED）/改密踢会话（TOKEN_STALE）/refresh 过期：
        // 会话终结，等用户重新登录。
        _terminateSession();
      }
      rethrow;
    }
  }

  /// 自账户读回（`GET /v1/users/me`）：换机恢复链路的取回通道（recovery_vault）。
  /// 取回的金库信封缓存到内存（[_lastVaultEnvelope]），供改密重封使用。
  Future<auth.MeProfile> me() async {
    final profile = await auth.fetchMe(dio, baseUrl, await ensureAccessToken());
    final vault = profile.recoveryVault;
    if (vault != null && vault.isNotEmpty) _lastVaultEnvelope = vault;
    return profile;
  }

  /// 自账户更新（`PATCH /v1/users/me`）：字段可选独立生效；`pushToken` 传空串
  /// = 清空（推送令牌换发时调用，否则 Wake Push 失效）。
  Future<void> updateMe({
    List<int>? encryptedProfile,
    List<int>? recoveryVault,
    String? pushToken,
  }) async =>
      auth.patchMe(dio, baseUrl, await ensureAccessToken(),
          encryptedProfile: encryptedProfile,
          recoveryVault: recoveryVault,
          pushToken: pushToken);

  /// 登录共用尾段：密码换票 → 定位 user_id → 持久化 refresh → 广播登录态。
  ///
  /// user_id 优先取本地协同身份记录（注册过的设备）；没有（换机重装）则用
  /// `GET /v1/users/me` 补齐（登录态自身的语义通道），失败留空（不阻断登录）。
  Future<void> _establishSession(
    String uname,
    String password, {
    String? preferUserId,
  }) async {
    final pair = await auth.login(dio, baseUrl, uname, password);
    _access = pair.access;
    _accessExpiresAt = pair.accessExpiresAt;
    _refreshToken = pair.refresh;
    _username = uname;
    var uid = preferUserId ?? (await identityStore.load())?.userId;
    if (uid == null || uid.isEmpty) {
      try {
        uid = (await auth.fetchMe(dio, baseUrl, pair.access)).userId;
      } catch (_) {
        uid = ''; // 离线等场景：登录成功但 user_id 暂缺，不阻断
      }
    }
    if (uid.isNotEmpty) _userId = uid;
    await _persistRefresh();
    _status = AccountStatus.loggedIn;
    notifyListeners();
  }

  /// 把「最新 refresh + 账号标识」写回安全区（access 绝不落盘）。
  Future<void> _persistRefresh() async {
    final store = secrets;
    if (store == null) return;
    await store.write(
      _sessionKey,
      jsonEncode({
        'username': _username,
        'user_id': _userId,
        'refresh_token': _refreshToken,
      }),
    );
  }

  /// 本地会话终结（内存凭据全清 + 安全区凭据删除）→ 未登录。
  /// 不触网：远端拉黑由调用方决定是否需要（logout 需要，踢会话由服务端已做）。
  void _terminateSession() {
    _access = null;
    _accessExpiresAt = null;
    _refreshToken = null;
    _username = null;
    _userId = null;
    final store = secrets;
    if (store != null) {
      // 同步删不阻塞状态广播：删除失败至多多留一份死票（下次 restore 换新
      // 401 仍会降级），不反过来拖住 UI。
      store.delete(_sessionKey).catchError((_) {});
    }
    _status = AccountStatus.loggedOut;
    notifyListeners();
  }

  int _nowSec() => DateTime.now().millisecondsSinceEpoch ~/ 1000;

  /// 测试注入口：绕过网络直接置为已登录（仅测试可见；不落安全区）。
  /// 票据未带过期时刻（accessExpiresAt==0）时视为刚签发（now+2h），避免被判过期。
  @visibleForTesting
  void debugEstablishSession({
    required String username,
    required String userId,
    required auth.TokenPair tokens,
  }) {
    _username = username;
    _userId = userId;
    _access = tokens.access;
    _refreshToken = tokens.refresh;
    _accessExpiresAt = tokens.accessExpiresAt == 0
        ? _nowSec() + 7200
        : tokens.accessExpiresAt;
    _status = AccountStatus.loggedIn;
    notifyListeners();
  }

  /// 测试注入口：把缓存的 access 标记为已过期（强制下次取票走 refresh），
  /// refresh token 保持真票。
  @visibleForTesting
  void debugExpireAccessToken() {
    if (_access == null) return;
    _accessExpiresAt = 1;
    notifyListeners();
  }
}

enum AccountStatus {
  /// 尚未探测（未读安全区，不预判断）。
  unknown,

  /// 未登录：协同功能引导去登录。
  loggedOut,

  /// 已登录：凭据在内存/安全区，中继会话可用。
  loggedIn,
}
