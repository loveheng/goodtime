import 'package:cryptography/cryptography.dart';

/// 最小 X3DH 风格会话密钥协商（协同设计 §3 / Phase 2.7 双机对测）。
///
/// 坐标系铁律：X3DH 的 DH 运算只用 **X25519 加密钥**。本端已注册的 `signedPreKey`
/// （SPK，见 `identity.dart`）即 X25519，对端通过 `/v1/users/{id}/prekeys` 广播的 SPK
/// 公钥亦 X25519——二者做 DH 即得共享密钥，喂给 `sealing.sealPayload` 做 AES-256-GCM。
///
/// 说明：完整 X3DH 还含「发起方临时钥 EK + 对端一次性预密钥 OPK」的多 DH 组合与前向保密，
/// 此处取核心的 **SPK↔SPK 单 DH** 作为 Phase 2.7 的会话种子（足以让「A 发 B 解」真实成立）；
/// EK/OPK 增强留会话层后续 Phase。因 X25519 DH 对称，`agreeSessionKey(A私,B公)` 与
/// `agreeSessionKey(B私,A公)` 得到**同一把 32B 密钥**，故双机可直接对称加解密。
Future<List<int>> agreeSessionKey({
  required SimpleKeyPair mySpk,
  required SimplePublicKey peerSpkPub,
}) async {
  if (peerSpkPub.type != KeyPairType.x25519) {
    throw ArgumentError('对端 SPK 公钥必须是 X25519（坐标系铁律）');
  }
  final sk = await X25519().sharedSecretKey(
    keyPair: mySpk,
    remotePublicKey: peerSpkPub,
  );
  return sk.extractBytes();
}
