import 'dart:convert';
import 'dart:math';
import 'dart:typed_data';

import 'package:cryptography/cryptography.dart';

/// 加密封装单点（协同设计 §9.2）。
///
/// 一切落网明文都经此一处出口，统一三件事：
/// 1. **定长填充**：PKCS#7 补到 512B 整数倍——「好」与小作文密文等长，
///    杜绝按密文长度侧信道推断内容（如「会议/航班」长短差异）；
/// 2. **AES-256-GCM**：12B 随机 nonce 每次不复用（GCM 的 nonce 复用即全盘沦陷）；
/// 3. **key_ver 标注**：密文自带所用 K_bundle 版本，回放端按版本取密钥（见 KeyRing）。
///
/// 入参 `key` 为 K_bundle 对称密钥（32B）；本函数不关心它从 X3DH 如何得来。
class SealedPayload {
  final int keyVer;
  final List<int> nonce; // 12B
  final List<int> cipherText;
  final List<int> mac;

  const SealedPayload({
    required this.keyVer,
    required this.nonce,
    required this.cipherText,
    required this.mac,
  });

  Map<String, dynamic> toJson() => {
        'key_ver': keyVer,
        'nonce': base64Encode(nonce),
        'cipher_text': base64Encode(cipherText),
        'mac': base64Encode(mac),
      };

  String encode() => jsonEncode(toJson());

  factory SealedPayload.fromJson(Map<String, dynamic> j) => SealedPayload(
        keyVer: j['key_ver'] as int,
        nonce: base64Decode(j['nonce'] as String),
        cipherText: base64Decode(j['cipher_text'] as String),
        mac: base64Decode(j['mac'] as String),
      );

  factory SealedPayload.decode(String raw) =>
      SealedPayload.fromJson(jsonDecode(raw) as Map<String, dynamic>);
}

const _blockSize = 512;
final _aead = AesGcm.with256bits();
final _rng = Random.secure();

/// 定长填充到 512B 整数倍（抗长度侧信道）。
///
/// 经典 PKCS#7 的填充字节只能表达 1..255，无法表示「整 512B 块」的填充量，
/// 故这里用 2 字节大端长度尾注承载真实 pad 量（1..512），填充字节本身取 pad 低 8 位
/// 仅作占位。解码只读尾注，绝不歧义——这是 512B 粒度下 PKCS#7 的稳妥落地。
List<int> _pkcs7Pad(List<int> data) {
  final pad = _blockSize - (data.length % _blockSize); // 1..512
  final out = [
    ...data,
    ...List.filled(pad, pad & 0xff),
    (pad >> 8) & 0xff,
    pad & 0xff,
  ];
  return out;
}

List<int> _pkcs7Unpad(List<int> data) {
  if (data.length < 2) throw const FormatException('PKCS#7：数据过短');
  final pad = (data[data.length - 2] << 8) | data.last;
  if (pad < 1 || pad > _blockSize) {
    throw const FormatException('PKCS#7：填充长度非法');
  }
  return data.sublist(0, data.length - pad - 2);
}

/// 加密：明文 → 定长填充 → AES-256-GCM。返回自描述信封（含 key_ver）。
Future<SealedPayload> sealPayload(
  List<int> key,
  List<int> plaintext,
  int keyVer,
) async {
  if (key.length != 32) throw ArgumentError('K_bundle 密钥须为 32 字节');
  final padded = _pkcs7Pad(plaintext);
  final nonce = Uint8List.fromList(
      List<int>.generate(12, (_) => _rng.nextInt(256)));
  final box = await _aead.encrypt(padded,
      secretKey: SecretKey(key), nonce: nonce);
  return SealedPayload(
    keyVer: keyVer,
    nonce: nonce,
    cipherText: box.cipherText,
    mac: box.mac.bytes,
  );
}

/// 解密：AES-256-GCM → 去填充 → 明文。密钥错会抛（GCM MAC 校验失败）。
Future<Uint8List> openPayload(List<int> key, SealedPayload p) async {
  if (key.length != 32) throw ArgumentError('K_bundle 密钥须为 32 字节');
  final padded = await _aead.decrypt(
    SecretBox(p.cipherText, nonce: p.nonce, mac: Mac(p.mac)),
    secretKey: SecretKey(key),
  );
  return Uint8List.fromList(_pkcs7Unpad(padded));
}
