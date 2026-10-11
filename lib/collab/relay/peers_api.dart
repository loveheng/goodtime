/// 好友域与成员公钥 REST 客户端（relay `friends.go` / `register.go` 冻结契约）。
///
/// **本地发起 bundle 的两张前置名单**（§1.7 准入严管）：
/// 1. 谁能被邀——`GET /v1/friends` 且 `status=accepted`：服务端 PROPOSE 时逐个校验
///    「发起人与被邀人双向 accepted」，未 accepted 一律 403 打回，端侧提前过滤省一次
///    往返（服务端仍然复检，端侧过滤只是 UX，不是安全边界）；
/// 2. 信封怎么封——`GET /v1/users/{id}/prekey` 取被邀人的 X25519 SPK 公钥，发起人
///    据此用「一次性 EK × 成员 SPK」把 K_bundle 单独封给每一人（§5.3，与
///    `freemask.dart` 的单向加密同一套 shape）。
///
/// 注意 REST 体是**原生 JSON**（snake_case 键 + base64 值），非 protobuf。
library;

import 'dart:convert';

import 'package:dio/dio.dart';

/// 好友条目（`GET /v1/friends` 单行投影）。
///
/// `encryptedIntro`（自报家门）只在「我是被邀方 + 仍 pending」时才有值——A 侧拿
/// 不到自己也解不开的字节（`friends.go` 就是这样回包的），这里是忠实映射。
class FriendView {
  final String userId;
  final String status; // accepted / pending / blocked
  final int createdAtSec;
  final List<int>? encryptedIntro;

  const FriendView({
    required this.userId,
    required this.status,
    this.createdAtSec = 0,
    this.encryptedIntro,
  });

  bool get isAccepted => status == 'accepted';

  factory FriendView.fromJson(Map<String, dynamic> j) => FriendView(
        userId: j['user_id'] as String,
        status: j['status'] as String,
        createdAtSec: (j['created_at'] as int?) ?? 0,
        encryptedIntro: j['encrypted_intro'] == null
            ? null
            : base64Decode(j['encrypted_intro'] as String),
      );
}

/// 拉全部好友（Bearer 鉴权）。不去 status——UI 需区分 pending/blocked。
Future<List<FriendView>> fetchFriends(
  Dio dio,
  String baseUrl,
  String accessToken,
) async {
  final resp = await dio.get(
    '$baseUrl/v1/friends',
    options: Options(
      headers: {'Authorization': 'Bearer $accessToken'},
      validateStatus: (s) => s == 200,
    ),
  );
  if (resp.statusCode != 200) {
    throw RelayPeersException(resp.statusCode ?? 0, resp.data);
  }
  final j = resp.data as Map<String, dynamic>;
  return (j['friends'] as List? ?? const [])
      .map((e) => FriendView.fromJson(e as Map<String, dynamic>))
      .toList();
}

/// 只回落已通过双向确认的好友（本地发起 bundle 的候选成员池）。
Future<List<FriendView>> fetchAcceptedFriends(
  Dio dio,
  String baseUrl,
  String accessToken,
) async =>
    (await fetchFriends(dio, baseUrl, accessToken))
        .where((f) => f.isAccepted)
        .toList();

/// 成员公钥包（`GET /v1/users/{id}/prekey`）。
class PeerIdentityBundle {
  final String userId;
  final List<int> identityKeyPub; // Ed25519 raw 32B（验签用）
  final List<int> signedPrekeyPub; // X25519 raw 32B（X3DH DH 用）
  final List<int> signedPrekeySig;
  final int remainingOpk;
  final bool opkLow; // OPK_EMPTY_WARNING：降级 3-DH 提示

  const PeerIdentityBundle({
    required this.userId,
    required this.identityKeyPub,
    required this.signedPrekeyPub,
    required this.signedPrekeySig,
    this.remainingOpk = 0,
    this.opkLow = false,
  });

  factory PeerIdentityBundle.fromJson(String userId, Map<String, dynamic> j) =>
      PeerIdentityBundle(
        userId: userId,
        identityKeyPub: base64Decode(j['identity_key_pub'] as String? ?? ''),
        signedPrekeyPub: base64Decode(j['signed_prekey_pub'] as String? ?? ''),
        signedPrekeySig: base64Decode(j['signed_prekey_sig'] as String? ?? ''),
        remainingOpk: (j['remaining_opk'] as int?) ?? 0,
        opkLow: j['warning'] == 'OPK_EMPTY_WARNING',
      );
}

/// 取成员的 X25519 广播公钥——本地发起时封 K_bundle 信封的锚点。
///
/// 坐标系铁律（§3）：DH 只走 `signedPrekeyPub`；`identityKeyPub` 是 Ed25519，
/// 拿它做 X25519 会直接炸。
Future<PeerIdentityBundle> fetchPeerKeys(
  Dio dio,
  String baseUrl,
  String accessToken,
  String userId,
) async {
  final encoded = Uri.encodeComponent(userId);
  final resp = await dio.get(
    '$baseUrl/v1/users/$encoded/prekey',
    options: Options(
      headers: {'Authorization': 'Bearer $accessToken'},
      validateStatus: (s) => s == 200,
    ),
  );
  if (resp.statusCode != 200) {
    throw RelayPeersException(resp.statusCode ?? 0, resp.data);
  }
  return PeerIdentityBundle.fromJson(
      userId, resp.data as Map<String, dynamic>);
}

class RelayPeersException implements Exception {
  final int statusCode;
  final Object? detail;
  RelayPeersException(this.statusCode, [this.detail]);
  @override
  String toString() => 'RelayPeersException($statusCode): $detail';
}
