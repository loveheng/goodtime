import 'dart:convert';

import 'package:dio/dio.dart';

import '../identity.dart' show PreKey;

/// 账号域 REST 契约（relay `internal/httpapi/register.go` / `router.go`，账号体系 Plan A）。
///
/// 用户只见「用户名 + 密码」；E2EE 密钥材料由端侧自动生成随注册上传，服务端纯盲存。
/// 注意：REST 体是**原生 JSON**（snake_case 键 + base64 值），并非 protobuf。
class TokenPair {
  final String access;
  final String refresh;

  /// access 过期时刻（unix 秒；对齐 relay `auth.TokenPair.access_expires_at`）。
  final int accessExpiresAt;

  const TokenPair({
    required this.access,
    required this.refresh,
    this.accessExpiresAt = 0,
  });
}

class Challenge {
  final String nonce;
  final String powChallenge;
  final int powDifficulty;
  final int expiresAt;

  const Challenge({
    required this.nonce,
    required this.powChallenge,
    required this.powDifficulty,
    required this.expiresAt,
  });

  factory Challenge.fromJson(Map<String, dynamic> j) => Challenge(
        nonce: j['nonce'] as String,
        powChallenge: j['pow_challenge'] as String,
        powDifficulty: j['pow_difficulty'] as int,
        expiresAt: j['expires_at'] as int,
      );
}

class RegisterResponse {
  final String userId;
  final String username;
  final int opkCount;

  const RegisterResponse({
    required this.userId,
    required this.username,
    required this.opkCount,
  });

  factory RegisterResponse.fromJson(Map<String, dynamic> j) => RegisterResponse(
        userId: j['user_id'] as String,
        username: (j['username'] as String?) ?? '',
        opkCount: j['opk_count'] as int,
      );
}

/// `GET /v1/users/lookup?username=` 的回包（加好友搜索路径的用户身份面）。
class UserLookup {
  final String userId;
  final String username;
  final List<int> identityKeyPub;
  final List<int> signedPrekeyPub;
  final List<int> signedPrekeySig;

  const UserLookup({
    required this.userId,
    required this.username,
    required this.identityKeyPub,
    required this.signedPrekeyPub,
    required this.signedPrekeySig,
  });
}

/// 构造 `POST /v1/users` 的 JSON 体。密钥字段按服务端要求 base64；
/// `user_id` 由服务端发号（UUID），客户端不再自报。
Map<String, dynamic> buildRegisterBody({
  required String username,
  required String password,
  required List<int> identityKeyPub,
  required List<int> signedPrekeyPub,
  required List<int> signedPrekeySig,
  required String nonce,
  required String powSolution,
  required List<PreKey> oneTimePreKeys,
  String? pushToken,
  List<int>? encryptedProfile,
  List<int>? recoveryVault,
}) =>
    {
      'username': username,
      'password': password,
      'identity_key_pub': base64Encode(identityKeyPub),
      'signed_prekey_pub': base64Encode(signedPrekeyPub),
      'signed_prekey_sig': base64Encode(signedPrekeySig),
      'push_token': pushToken ?? '',
      'encrypted_profile':
          encryptedProfile == null ? '' : base64Encode(encryptedProfile),
      'recovery_vault':
          recoveryVault == null ? '' : base64Encode(recoveryVault),
      'nonce': nonce,
      'pow_solution': powSolution,
      'one_time_prekeys': oneTimePreKeys
          .map((k) => {
                'key_id': k.keyId,
                'pub': base64Encode(k.pub),
                'signature': base64Encode(k.signature),
              })
          .toList(),
    };

/// `POST /v1/auth/challenge`：申领一次性 nonce + PoW 题面（注册用；登录不再需要）。
Future<Challenge> fetchChallenge(Dio dio, String baseUrl) async {
  final resp = await dio.post(
    '$baseUrl/v1/auth/challenge',
    data: jsonEncode(<String, dynamic>{}),
    options: Options(contentType: 'application/json'),
  );
  return Challenge.fromJson(resp.data as Map<String, dynamic>);
}

/// 服务端统一错误体的 `error_code`（`{error_code, message, detail}`）；
/// 非标准体返回 null。UI 据此给可读文案（防枚举口径见 apierr 字典）。
String? errorCodeOf(Object? detail) {
  if (detail is Map && detail['error_code'] is String) {
    return detail['error_code'] as String;
  }
  return null;
}

class RelayAuthException implements Exception {
  final int statusCode;
  final Object? detail;
  RelayAuthException(this.statusCode, [this.detail]);
  @override
  String toString() => 'RelayAuthException($statusCode): $detail';
}

/// `POST /v1/users`：提交注册（username + password + E2EE 公钥材料 + PoW）。
/// 期待 201；其余状态抛 [RelayAuthException]。
Future<RegisterResponse> registerUser(
  Dio dio,
  String baseUrl,
  Map<String, dynamic> body,
) async {
  try {
    final resp = await dio.post(
      '$baseUrl/v1/users',
      data: jsonEncode(body),
      options: Options(
        contentType: 'application/json',
        validateStatus: (s) => s == 201,
      ),
    );
    return RegisterResponse.fromJson(resp.data as Map<String, dynamic>);
  } on DioException catch (e) {
    if (e.response != null) {
      throw RelayAuthException(e.response!.statusCode ?? 0, e.response!.data);
    }
    rethrow;
  }
}

/// `POST /v1/auth/login`：纯密码登录（`{username, password}` → 双 Token）。
///
/// 用户不存在与密码错误同一 401 口径（`USER_OR_PASSWORD_INVALID`，防账号枚举）；
/// 客户端 UI 也不得区分。
Future<TokenPair> login(
  Dio dio,
  String baseUrl,
  String username,
  String password,
) async {
  try {
    final resp = await dio.post(
      '$baseUrl/v1/auth/login',
      data: jsonEncode({'username': username, 'password': password}),
      options: Options(
        contentType: 'application/json',
        validateStatus: (s) => s == 200,
      ),
    );
    final j = resp.data as Map<String, dynamic>;
    return TokenPair(
      access: j['access_token'] as String,
      refresh: j['refresh_token'] as String,
      accessExpiresAt: (j['access_expires_at'] as num?)?.toInt() ?? 0,
    );
  } on DioException catch (e) {
    if (e.response != null) {
      throw RelayAuthException(e.response!.statusCode ?? 0, e.response!.data);
    }
    rethrow;
  }
}

/// `POST /v1/auth/refresh`：用 refresh token 续期双 Token。
Future<TokenPair> refresh(
  Dio dio,
  String baseUrl,
  String refreshToken,
) async {
  try {
    final resp = await dio.post(
      '$baseUrl/v1/auth/refresh',
      data: jsonEncode({'refresh_token': refreshToken}),
      options: Options(
        contentType: 'application/json',
        validateStatus: (s) => s == 200,
      ),
    );
    final j = resp.data as Map<String, dynamic>;
    return TokenPair(
      access: j['access_token'] as String,
      refresh: j['refresh_token'] as String,
      accessExpiresAt: (j['access_expires_at'] as num?)?.toInt() ?? 0,
    );
  } on DioException catch (e) {
    if (e.response != null) {
      throw RelayAuthException(e.response!.statusCode ?? 0, e.response!.data);
    }
    rethrow;
  }
}

/// `POST /v1/auth/change-password`（Bearer）：只换密码哈希，不影响 E2EE 密钥与
/// 历史消息（账号体系 Plan A：密码仅鉴权）。
Future<void> changePassword(
  Dio dio,
  String baseUrl,
  String accessToken, {
  required String oldPassword,
  required String newPassword,
  List<int>? newRecoveryVault,
}) async {
  try {
    await dio.post(
      '$baseUrl/v1/auth/change-password',
      data: jsonEncode({
        'old_password': oldPassword,
        'new_password': newPassword,
        // v3 拍板：金库包裹钥=登录密码派生，改密即原子重封（缺省=不改金库，
        // 服务端单事务落库，缺省字段仅换哈希兼容无金库账号）。
        if (newRecoveryVault != null)
          'new_recovery_vault': base64Encode(newRecoveryVault),
      }),
      options: Options(
        contentType: 'application/json',
        headers: {'Authorization': 'Bearer $accessToken'},
        validateStatus: (s) => s == 200,
      ),
    );
  } on DioException catch (e) {
    if (e.response != null) {
      throw RelayAuthException(e.response!.statusCode ?? 0, e.response!.data);
    }
    rethrow;
  }
}

/// `GET /v1/users/lookup?username=`（Bearer）：用户名搜索身份（加好友搜索路径）。
/// 查无此人 → 404 `USER_NOT_FOUND`（抛 [RelayAuthException]）。
Future<UserLookup> lookupUser(
  Dio dio,
  String baseUrl,
  String accessToken,
  String username,
) async {
  try {
    final resp = await dio.get(
      '$baseUrl/v1/users/lookup',
      queryParameters: {'username': username},
      options: Options(
        headers: {'Authorization': 'Bearer $accessToken'},
        validateStatus: (s) => s == 200,
      ),
    );
    final j = resp.data as Map<String, dynamic>;
    return UserLookup(
      userId: j['user_id'] as String,
      username: j['username'] as String,
      identityKeyPub: base64Decode(j['identity_key_pub'] as String),
      signedPrekeyPub: base64Decode(j['signed_prekey_pub'] as String),
      signedPrekeySig: base64Decode(j['signed_prekey_sig'] as String),
    );
  } on DioException catch (e) {
    if (e.response != null) {
      throw RelayAuthException(e.response!.statusCode ?? 0, e.response!.data);
    }
    rethrow;
  }
}

/// 服务端统一错误体的 `detail.retry_after_sec`（登录防爆破：账号锁定期等待窗口）。
int? retryAfterSecOf(Object? detail) {
  if (detail is Map && detail['detail'] is Map) {
    final inner = detail['detail'] as Map;
    final v = inner['retry_after_sec'];
    if (v is num) return v.toInt();
  }
  return null;
}

/// `GET /v1/users/me`（Bearer）：自账户读回——换机恢复链路的取回通道。
/// `encrypted_profile`/`recovery_vault` 未设置时字段缺省（null）。
class MeProfile {
  final String userId;
  final String username;
  final List<int>? encryptedProfile;
  final List<int>? recoveryVault;
  final int createdAt;

  const MeProfile({
    required this.userId,
    required this.username,
    this.encryptedProfile,
    this.recoveryVault,
    required this.createdAt,
  });
}

Future<MeProfile> fetchMe(Dio dio, String baseUrl, String accessToken) async {
  try {
    final resp = await dio.get(
      '$baseUrl/v1/users/me',
      options: Options(
        headers: {'Authorization': 'Bearer $accessToken'},
        validateStatus: (s) => s == 200,
      ),
    );
    final j = resp.data as Map<String, dynamic>;
    return MeProfile(
      userId: j['user_id'] as String,
      username: j['username'] as String,
      encryptedProfile: j['encrypted_profile'] == null
          ? null
          : base64Decode(j['encrypted_profile'] as String),
      recoveryVault: j['recovery_vault'] == null
          ? null
          : base64Decode(j['recovery_vault'] as String),
      createdAt: (j['created_at'] as num?)?.toInt() ?? 0,
    );
  } on DioException catch (e) {
    if (e.response != null) {
      throw RelayAuthException(e.response!.statusCode ?? 0, e.response!.data);
    }
    rethrow;
  }
}

/// `PATCH /v1/users/me`（Bearer）：自账户更新。三字段**可选独立生效**——
/// 传 null = 不动；`pushToken` 传空串 = 清空（推送令牌换发时调用）。
/// `encryptedProfile`/`recoveryVault` 传 base64 密文字节，present 即整体替换。
Future<void> patchMe(
  Dio dio,
  String baseUrl,
  String accessToken, {
  List<int>? encryptedProfile,
  List<int>? recoveryVault,
  String? pushToken,
}) async {
  if (encryptedProfile == null && recoveryVault == null && pushToken == null) {
    throw ArgumentError('patchMe：至少给一个字段（服务端拒绝空更新）');
  }
  final body = <String, dynamic>{
    if (encryptedProfile != null)
      'encrypted_profile': base64Encode(encryptedProfile),
    if (recoveryVault != null) 'recovery_vault': base64Encode(recoveryVault),
    'push_token': ?pushToken,
  };
  try {
    await dio.patch(
      '$baseUrl/v1/users/me',
      data: jsonEncode(body),
      options: Options(
        contentType: 'application/json',
        headers: {'Authorization': 'Bearer $accessToken'},
        validateStatus: (s) => s == 200,
      ),
    );
  } on DioException catch (e) {
    if (e.response != null) {
      throw RelayAuthException(e.response!.statusCode ?? 0, e.response!.data);
    }
    rethrow;
  }
}

/// `POST /v1/auth/logout`（Bearer）：主动登出——当前 access 与随附 refresh 双 jti
/// 拉黑至各自自然到期，重复登出幂等无害。服务端永远 200（非法 refresh 静默忽略）。
Future<void> logoutRemote(
  Dio dio,
  String baseUrl,
  String accessToken, {
  String? refreshToken,
}) async {
  try {
    await dio.post(
      '$baseUrl/v1/auth/logout',
      data: jsonEncode({'refresh_token': ?refreshToken}),
      options: Options(
        contentType: 'application/json',
        headers: {'Authorization': 'Bearer $accessToken'},
        validateStatus: (s) => s == 200,
      ),
    );
  } on DioException {
    // 登出必须本地可完成：网络失败不阻断（服务端拉黑尽力而为；access 到期自然失效）
  }
}

/// `POST /v1/users/me/prekeys`：低存量时后台补仓（≤50/批，§1.5 心跳监听用）。
Future<Map<String, dynamic>> refillPreKeys(
  Dio dio,
  String baseUrl,
  String accessToken,
  List<PreKey> oneTimePreKeys,
) async {
  final body = {
    'one_time_prekeys': oneTimePreKeys
        .map((k) => {
              'key_id': k.keyId,
              'pub': base64Encode(k.pub),
              'signature': base64Encode(k.signature),
            })
        .toList(),
  };
  try {
    final resp = await dio.post(
      '$baseUrl/v1/users/me/prekeys',
      data: jsonEncode(body),
      options: Options(
        contentType: 'application/json',
        headers: {'Authorization': 'Bearer $accessToken'},
        validateStatus: (s) => s == 200,
      ),
    );
    return resp.data as Map<String, dynamic>;
  } on DioException catch (e) {
    if (e.response != null) {
      throw RelayAuthException(e.response!.statusCode ?? 0, e.response!.data);
    }
    rethrow;
  }
}
