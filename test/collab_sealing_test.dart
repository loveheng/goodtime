import 'dart:convert';

import 'package:cryptography/cryptography.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:shiguang/collab/sealing.dart';

void main() {
  final key = List<int>.filled(32, 7); // 32B 测试常量密钥

  test('加密封装单点：seal → open 往返一致', () async {
    final p = await sealPayload(key, utf8.encode('今天下午三点的评审会'), 3);
    expect(p.keyVer, 3);
    final out = await openPayload(key, p);
    expect(utf8.decode(out), '今天下午三点的评审会');
  });

  test('「好」与小作文密文等长（512B 定长填充，抗长度侧信道）', () async {
    final good = await sealPayload(key, utf8.encode('好'), 1);
    final essay = await sealPayload(
        key,
        utf8.encode('去机场接人，航班 CA1234，行李两件，晚上八点回程。'.padRight(180, 'x')),
        1);
    // 明文长度差异被 512B 粒度吸收，密文等长（GCM tag 不计入 cipherText，单独随 SealedPayload 走）
    expect(good.cipherText.length, essay.cipherText.length);
    // 明文补到 512B 整数倍 + 2B 填充尾注，故密文长度 ≡ 514 mod 512
    expect(good.cipherText.length % 512, 2);
  });

  test('密文自描述：encode/decode 不丢 key_ver 与字段', () async {
    final p = await sealPayload(key, utf8.encode('payload'), 7);
    final restored = SealedPayload.decode(p.encode());
    expect(restored.keyVer, 7);
    final out = await openPayload(key, restored);
    expect(utf8.decode(out), 'payload');
  });

  test('错误密钥解密失败（GCM MAC 校验）', () async {
    final p = await sealPayload(key, utf8.encode('secret'), 1);
    final wrong = List<int>.from(key)..[0] ^= 0xff; // 翻转一位
    expect(() => openPayload(wrong, p), throwsA(isA<SecretBoxAuthenticationError>()));
  });

  test('密钥长度非 32B 直接拒绝', () {
    expect(() => sealPayload([1, 2, 3], [1], 1), throwsA(isA<ArgumentError>()));
  });
}
