/// 好友名片凭证（§1.6 Friendship Ticket）+ 安全码（Safety Number）。
///
/// ## Friendship Ticket
/// 接收方 B 端侧签发的一次性名片（二维码）：`Ticket_B = {account_id_B, nonce,
/// expires_at, signature_B}`。A 扫码携完整 Ticket 提交 `POST /v1/friends/invite`，
/// 服务端验签（B 的 Ed25519 身份公钥）+ 验未过期 + nonce 一次性才写 `friendships(pending)`。
/// 防撞库骚扰——禁止拿纯 account_id 全局发好友申请。
///
/// QR 串里就是本对象的 JSON（字段名对齐 relay `ticketReq`），扫码方原样转发即可。
///
/// ## Safety Number
/// 好友建立后，端侧计算双方身份公钥哈希指纹，展示为 6 组 5 位数字安全码——用户可在线下
/// /微信核对，提供防「恶意中继置换公钥」的 MITM 校验入口（中继不可信，端侧可验证）。
library;

import 'dart:convert';
import 'dart:math';
import 'dart:typed_data';

import 'package:crypto/crypto.dart' as crypto;
import 'package:cryptography/cryptography.dart';

/// Ticket 规范化签名消息（与 relay `auth.TicketMessage` 逐字节一致，否则跨端验签必败）。
///
/// 覆盖字段：`shiguang.relay.v1/friendship-ticket|<issuer>|<subject>|<nonce>|<expires_at>`。
/// `subject_user_id` 可空（任意扫码者），此时该段为空串。
String ticketMessage({
  required String issuer,
  required String subject,
  required String nonce,
  required int expiresAt,
}) =>
    'shiguang.relay.v1/friendship-ticket|$issuer|$subject|$nonce|$expiresAt';

/// 好友名片凭证（QR 的载荷，字段名对齐 relay `ticketReq` 原生 JSON）。
class FriendshipTicket {
  final String issuerUserId;
  final String subjectUserId; // 被指定可扫码者，可空（任意扫码者）
  final String nonce;
  final int expiresAt; // unix 秒
  final List<int> signature; // Ed25519 签 issuer 私钥
  final List<int> encKeyPub; // 签发人 X25519 加密公钥（ECIES 自报家门用，服务端不解析）

  const FriendshipTicket({
    required this.issuerUserId,
    required this.subjectUserId,
    required this.nonce,
    required this.expiresAt,
    required this.signature,
    required this.encKeyPub,
  });

  Map<String, dynamic> toJson() => {
        'issuer_user_id': issuerUserId,
        'subject_user_id': subjectUserId,
        'nonce': nonce,
        'expires_at': expiresAt,
        'signature': base64Encode(signature),
        'enc_key_pub': base64Encode(encKeyPub),
      };

  factory FriendshipTicket.fromJson(Map<String, dynamic> j) => FriendshipTicket(
        issuerUserId: j['issuer_user_id'] as String,
        subjectUserId: j['subject_user_id'] as String? ?? '',
        nonce: j['nonce'] as String,
        expiresAt: j['expires_at'] as int,
        signature: base64Decode(j['signature'] as String),
        encKeyPub: base64Decode(j['enc_key_pub'] as String? ?? ''),
      );

  /// 直接喂 qr_flutter 的二维码串。
  String toQrString() => jsonEncode(toJson());

  factory FriendshipTicket.fromQrString(String s) =>
      FriendshipTicket.fromJson(jsonDecode(s) as Map<String, dynamic>);
}

String _randomNonce() {
  final bytes = List<int>.generate(16, (_) => Random.secure().nextInt(256));
  return base64UrlEncode(bytes);
}

/// 用本地身份私钥签发自己的名片 Ticket（Ed25519 签名）。
///
/// 与「半盲不建联」铁律一致：私钥由 MSK 封存，vault 未解锁 / 无身份记录即抛 StateError，
/// 绝不拿假证凑数。调用方负责先把钥匙还原成 [SimpleKeyPair]（见 `relay_session` 的
/// `restoreIdentityKeyPair`），本函数保持纯密码学、零身份存储依赖、可单测。
Future<FriendshipTicket> signFriendshipTicket({
  required SimpleKeyPair identityKeyPair,
  required String issuerUserId,
  required List<int> encKeyPub,
  int ttlSeconds = 300,
}) async {
  final nonce = _randomNonce();
  final expiresAt =
      (DateTime.now().millisecondsSinceEpoch ~/ 1000) + ttlSeconds;
  final msg = ticketMessage(
    issuer: issuerUserId,
    subject: '',
    nonce: nonce,
    expiresAt: expiresAt,
  );
  final sig = await Ed25519().sign(utf8.encode(msg), keyPair: identityKeyPair);
  return FriendshipTicket(
    issuerUserId: issuerUserId,
    subjectUserId: '',
    nonce: nonce,
    expiresAt: expiresAt,
    signature: sig.bytes,
    encKeyPub: encKeyPub,
  );
}

/// Ed25519 身份公钥原始字节（安全码比对用）。
Future<List<int>> identityPublicKeyBytes(SimpleKeyPair identityKeyPair) async =>
    (await identityKeyPair.extractPublicKey()).bytes;

int _compareBytes(List<int> a, List<int> b) {
  final n = a.length < b.length ? a.length : b.length;
  for (var i = 0; i < n; i++) {
    if (a[i] != b[i]) return a[i] < b[i] ? -1 : 1;
  }
  return a.length.compareTo(b.length);
}

/// 安全码（Safety Number，§1.6）：双方身份公钥哈希指纹，6 组 5 位数字。
///
/// 取双方 Ed25519 身份公钥，按字节序排序后拼接做 SHA-256，逐组派生 5 位十进制数
/// （00000–99999）。排序保证双端算出同一串；加组索引避免各组撞车。
List<String> safetyNumberDigits(List<int> myIkPub, List<int> peerIkPub) {
  final a = Uint8List.fromList(myIkPub);
  final b = Uint8List.fromList(peerIkPub);
  final ordered = _compareBytes(a, b) <= 0 ? [a, b] : [b, a];
  final concat = Uint8List(a.length + b.length)
    ..setRange(0, a.length, ordered[0])
    ..setRange(a.length, a.length + b.length, ordered[1]);
  final out = <String>[];
  for (var i = 0; i < 6; i++) {
    final block = <int>[...concat, i];
    final hash = crypto.sha256.convert(Uint8List.fromList(block)).bytes;
    final n = (hash[0] << 16) | (hash[1] << 8) | hash[2];
    out.add((n % 100000).toString().padLeft(5, '0'));
  }
  return out;
}
