/// 成员密钥信封的 REST 客户端（relay `sync.go` 冻结契约，§5.3 K_bundle 分发）。
///
/// 只有**本成员自己的那一份**会回包（`MemberKeyEnvelopes` 按 caller 过滤），
/// 服务端纯粹当字节盲存：它不知道里面包的是 K_bundle，也解不开。
library;

import 'package:dio/dio.dart';

/// 一条成员信封（服务端只认 key_ver + 字节，语义全在端侧）。
class KeyEnvelopeView {
  final int keyVer;
  final String wrappedKey; // base64（内容由发起端自描述：{ek_pub, sealed}）
  final int createdAtSec;

  const KeyEnvelopeView({
    required this.keyVer,
    required this.wrappedKey,
    this.createdAtSec = 0,
  });

  factory KeyEnvelopeView.fromJson(Map<String, dynamic> j) => KeyEnvelopeView(
        keyVer: (j['key_ver'] as int?) ?? 1,
        wrappedKey: j['wrapped_key'] as String? ?? '',
        createdAtSec: (j['created_at'] as int?) ?? 0,
      );

  /// 喂给 `propose.openBundleKeyEnvelope` 的形状（键名与发起端封包一致）。
  Map<String, Object?> toEnvelope() => {
        'wrapped_key': wrappedKey,
        'key_ver': keyVer,
      };
}

/// `GET /v1/bundles/{id}/key-envelopes`：拉我在这个包里的全部密钥信封。
///
/// 非成员 403（服务端 ACL），空列表也是合法回包（发起人自己就没有信封——它
/// 直接持有明文 K_bundle）。
Future<List<KeyEnvelopeView>> fetchBundleKeyEnvelopes(
  Dio dio,
  String baseUrl,
  String accessToken,
  String bundleId,
) async {
  final encoded = Uri.encodeComponent(bundleId);
  final resp = await dio.get(
    '$baseUrl/v1/bundles/$encoded/key-envelopes',
    options: Options(
      headers: {'Authorization': 'Bearer $accessToken'},
      validateStatus: (s) => s == 200,
    ),
  );
  if (resp.statusCode != 200) {
    throw RelayKeysException(resp.statusCode ?? 0, resp.data);
  }
  final j = resp.data as Map<String, dynamic>;
  return (j['envelopes'] as List? ?? const [])
      .map((e) => KeyEnvelopeView.fromJson(e as Map<String, dynamic>))
      .toList();
}

class RelayKeysException implements Exception {
  final int statusCode;
  final Object? detail;
  RelayKeysException(this.statusCode, [this.detail]);
  @override
  String toString() => 'RelayKeysException($statusCode): $detail';
}
