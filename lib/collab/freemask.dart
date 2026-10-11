/// FreeMask 传输协议（协同设计 §3.6「空白探测分流」评审三约束，端侧实现）。
///
/// 作息泄漏防线，定稿三约束逐条落地：
/// 1. **靶向范围**：探测只允许针对具体提议候选区间（如 10月24日 09:00–18:00），
///    函数签名强制收 `targetStart/targetEnd`——API 形状上就不可能发起整周探测
///    （整周空区可反推几点睡觉几点上班）；
/// 2. **粗粒度量化桶**：区间按 30 分钟切片生成二进制掩码串（`01110001`，
///    1=有空）；成员本地用生活日程 + 已有团队日程（**仅 HARD_TENTATIVE 强占位**，
///    SOFT_CANDIDATE 弱占位仍算可用——防「可用时间虚假枯竭」）计算；
/// 3. **单向加密**：掩码经临时信箱透传，只有发起人能解——用成员一次性 X25519
///    临时钥 `EK` 与发起人 SPK 公钥做 DH 得对称密钥 AES-256-GCM 加密；发起人端
///    用自身 SPK 私钥 × EK 公钥解（X25519 DH 对称，与 `crypto/session.dart`
///    的 X3DH 会话协商同构）。发起人端内存中按位与算全员重叠区。
library;

import 'dart:convert';
import 'dart:math';
import 'dart:typed_data';

import 'package:cryptography/cryptography.dart';

/// 30 分钟量化桶宽（§3.6 定稿常量）。
const int kFreeMaskBucketMs = 30 * 60 * 1000;

/// 掩码信号（解密后的明文形态）。
class FreeMaskSignal {
  final int targetStartMs;
  final int targetEndMs;
  final String mask; // '01110001'，1=有空；长度 = 桶数

  const FreeMaskSignal({
    required this.targetStartMs,
    required this.targetEndMs,
    required this.mask,
  });

  Map<String, Object?> toJson() => {
        'target_start': targetStartMs,
        'target_end': targetEndMs,
        'mask': mask,
      };

  factory FreeMaskSignal.fromJson(Map<String, Object?> j) => FreeMaskSignal(
        targetStartMs: j['target_start'] as int,
        targetEndMs: j['target_end'] as int,
        mask: j['mask'] as String,
      );
}

/// 成员本地算 FreeMask（§3.6）：目标区间切 30min 桶，被任一 busy 区间污染的
/// 桶记 0（占用），其余 1（有空）。**半开区间语义**：busy [10:00,11:00) 不污染
/// [09:30,10:00) 桶——判据 `busyStart < bucketEnd && bucketStart < busyEnd`。
/// 输入只放 HARD_TENTATIVE 与实体日程；SOFT_CANDIDATE 弱占位由调用方排除。
String quantizeFreeMask({
  required int targetStartMs,
  required int targetEndMs,
  required List<(int, int)> busy,
}) {
  if (targetEndMs <= targetStartMs) {
    throw ArgumentError('靶向区间必须 start < end（§3.6 约束①）');
  }
  final buckets =
      ((targetEndMs - targetStartMs + kFreeMaskBucketMs - 1) ~/ kFreeMaskBucketMs);
  final out = StringBuffer();
  for (var i = 0; i < buckets; i++) {
    final bucketStart = targetStartMs + i * kFreeMaskBucketMs;
    final bucketEnd = bucketStart + kFreeMaskBucketMs;
    final occupied = busy.any((iv) => iv.$1 < bucketEnd && bucketStart < iv.$2);
    out.write(occupied ? '0' : '1');
  }
  return out.toString();
}

/// 发起人端全员重叠区：按位与（BITWISE AND，§3.6 约束③）——「全员共振条」数据源。
/// 掩码必须同区间同长（靶向探测统一区间，长度不齐即调用方 bug，防御性抛错）。
String freeMaskBitwiseAnd(List<String> masks) {
  if (masks.isEmpty) throw ArgumentError('至少需要一份掩码');
  final len = masks.first.length;
  if (masks.any((m) => m.length != len)) {
    throw ArgumentError('掩码长度不一致：靶向区间必须统一');
  }
  final out = StringBuffer();
  for (var i = 0; i < len; i++) {
    final allFree = masks.every((m) => m[i] == '1');
    out.write(allFree ? '1' : '0');
  }
  return out.toString();
}

// ---- 单向加密（约束③：只有发起人能解） --------------------------------------

final _aesGcm = AesGcm.with256bits();
final _x25519 = X25519();
final _rng = Random.secure();

/// 成员端封掩码：一次性 EK × 发起人 SPK 公钥做 DH → AES-256-GCM。
/// 返回信封 map（ek_pub/nonce/cipher_text/mac 均 base64），经临时信箱透传。
Future<Map<String, Object?>> sealFreeMaskForInitiator({
  required FreeMaskSignal signal,
  required SimpleKeyPair senderEphemeral,
  required SimplePublicKey initiatorSpkPub,
}) async {
  final shared = await _x25519.sharedSecretKey(
    keyPair: senderEphemeral,
    remotePublicKey: initiatorSpkPub,
  );
  final key = await shared.extractBytes();
  final nonce = Uint8List.fromList(
      List<int>.generate(12, (_) => _rng.nextInt(256)));
  final plaintext = utf8.encode(jsonEncode(signal.toJson()));
  final box = await _aesGcm.encrypt(plaintext,
      secretKey: SecretKey(key), nonce: nonce);
  final ekPub = await senderEphemeral.extractPublicKey();
  return {
    'ek_pub': base64Encode(ekPub.bytes),
    'nonce': base64Encode(nonce),
    'cipher_text': base64Encode(box.cipherText),
    'mac': base64Encode(box.mac.bytes),
  };
}

/// 发起人端开掩码：自身 SPK 私钥 × 成员 EK 公钥（DH 对称 → 同一把密钥）。
/// 密钥不对（非本包发起人/篡改）→ GCM MAC 校验失败抛出。
Future<FreeMaskSignal> openFreeMaskFromMember({
  required Map<String, Object?> sealed,
  required SimpleKeyPair initiatorSpk,
}) async {
  final ekPub = SimplePublicKey(
    base64Decode(sealed['ek_pub'] as String),
    type: KeyPairType.x25519,
  );
  final shared = await _x25519.sharedSecretKey(
    keyPair: initiatorSpk,
    remotePublicKey: ekPub,
  );
  final key = await shared.extractBytes();
  final padded = await _aesGcm.decrypt(
    SecretBox(
      base64Decode(sealed['cipher_text'] as String),
      nonce: base64Decode(sealed['nonce'] as String),
      mac: Mac(base64Decode(sealed['mac'] as String)),
    ),
    secretKey: SecretKey(key),
  );
  return FreeMaskSignal.fromJson(
      jsonDecode(utf8.decode(padded)) as Map<String, Object?>);
}
