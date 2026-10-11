import 'dart:typed_data';

import 'package:cryptography/cryptography.dart';

/// 身份密钥材料（协同设计 §1.5 / §5.3）。
///
/// 坐标系严格分离（设计稿 §3 铁律）：
/// - **签名钥** `identityKey` = Ed25519（身份所有权证明 PoP、OPK/SPK 签名）；
/// - **加密钥** `signedPreKey` / OPK = X25519（X3DH Diffie-Hellman）。
/// 二者绝不分用——跨坐标系直用必炸，故在此一次性生成并冻结类型。
class IdentityMaterial {
  final SimpleKeyPair identityKey; // Ed25519
  final SimpleKeyPair signedPreKey; // X25519（SPK）
  final List<PreKey> oneTimePreKeys; // X25519 首批 10~20

  const IdentityMaterial(
    this.identityKey,
    this.signedPreKey,
    this.oneTimePreKeys,
  );

  /// Ed25519 身份公钥（raw 32B）。
  Future<Uint8List> get identityPublicKey =>
      identityKey.extractPublicKey().then((p) => Uint8List.fromList(p.bytes));

  /// X25519 SPK 公钥（raw 32B）。
  Future<Uint8List> get signedPreKeyPublic =>
      signedPreKey.extractPublicKey().then((p) => Uint8List.fromList(p.bytes));

  /// 服务端要的 `signed_prekey_sig`：用身份私钥对 SPK 公钥做 Ed25519 签名。
  Future<Uint8List> signedPreKeySignature() async => Uint8List.fromList((await Ed25519()
          .sign(await signedPreKeyPublic, keyPair: identityKey))
      .bytes);
}

/// 一次性预密钥（OPK）：公钥 + 身份钥签名（服务端兜底验签用）。
class PreKey {
  final String keyId;
  final List<int> pub; // X25519 raw 32B
  final List<int> signature; // Ed25519 sig over pub，由身份私钥签署

  const PreKey(this.keyId, this.pub, this.signature);
}

/// 生成一整套身份材料：Ed25519 身份钥 + X25519 SPK + N 个 OPK（各带身份钥签名）。
Future<IdentityMaterial> generateIdentity({int opkCount = 20}) async {
  if (opkCount < 10 || opkCount > 20) {
    throw ArgumentError('OPK 首批数量必须 ∈ [10, 20]（防刷号瘦身，§1.5）');
  }
  final ed = Ed25519();
  final x = X25519();
  final identityKey = await ed.newKeyPair();
  final signedPreKey = await x.newKeyPair();
  final idPub = (await identityKey.extractPublicKey()).bytes;

  final opks = <PreKey>[];
  for (var i = 0; i < opkCount; i++) {
    final kp = await x.newKeyPair();
    final pub = (await kp.extractPublicKey()).bytes;
    final sig = (await ed.sign(pub, keyPair: identityKey)).bytes;
    opks.add(PreKey('opk-$i', pub, sig));
  }
  // 引用以抑制未使用告警（签名覆盖凭证已在上面用掉）。
  assert(idPub.length == 32);
  return IdentityMaterial(identityKey, signedPreKey, opks);
}
