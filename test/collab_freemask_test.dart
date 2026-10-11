import 'package:cryptography/cryptography.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:shiguang/collab/freemask.dart';

/// Phase 3.4 FreeMask 验收（collab-implementation §3.4）：
/// §3.6 三约束逐条——靶向量化桶 / 按位与重叠区 / 单向加密只有发起人能解。
void main() {
  const hour = 3600 * 1000;
  final day0 = DateTime(2026, 10, 24, 9).millisecondsSinceEpoch;

  group('约束②：30min 量化桶', () {
    test('空闲区间 → 全 1；被占桶 → 0；半开区间不污染邻桶', () {
      // 09:00–11:00 = 4 桶；busy [10:00, 11:00) 占第 2、3 桶
      final mask = quantizeFreeMask(
        targetStartMs: day0,
        targetEndMs: day0 + 2 * hour,
        busy: [(day0 + 1 * hour, day0 + 2 * hour)],
      );
      expect(mask, '1100');
    });

    test('busy 起点落在桶边界上：[10:00,10:30) 只污染一个桶', () {
      final mask = quantizeFreeMask(
        targetStartMs: day0,
        targetEndMs: day0 + 2 * hour,
        busy: [(day0 + hour, day0 + hour + 1)], // 只钉住 1ms，也污染整桶
      );
      // 桶序：b0[9:00,9:30) b1[9:30,10:00) b2[10:00,10:30) b3[10:30,11:00)
      // busy [10:00,10:00.001) 落在 b2 → 仅 b2 被污染：'1101'
      // （前两版期望 '1011'/'1010' 均为掩码串下标算术手误，机器是对的）
      expect(mask, '1101');
    });

    test('区间非对齐也安全：尾桶截断（向上取整）', () {
      // 90 分钟 = 3 个整桶 + 0？90/30=3 整除；改 100 分钟 = 4 桶（尾桶 10min）
      final mask = quantizeFreeMask(
        targetStartMs: day0,
        targetEndMs: day0 + 100 * 60 * 1000,
        busy: const [],
      );
      expect(mask, '1111');
      expect(mask.length, 4);
    });

    test('靶向约束①：空区间直接拒绝（API 形状上不存在整周探测）', () {
      expect(
        () => quantizeFreeMask(targetStartMs: day0, targetEndMs: day0, busy: const []),
        throwsArgumentError,
      );
    });
  });

  group('约束③：发起人端按位与', () {
    test('全员共振条：仅全员都有空的桶亮 1', () {
      expect(freeMaskBitwiseAnd(['11010', '11110', '01111']), '01010');
    });

    test('单份掩码 = 原样；空列表拒绝；长度不齐拒绝', () {
      expect(freeMaskBitwiseAnd(['1010']), '1010');
      expect(() => freeMaskBitwiseAnd(const []), throwsArgumentError);
      expect(() => freeMaskBitwiseAnd(['101', '1010']), throwsArgumentError);
    });
  });

  group('单向加密：只有发起人能解（X25519 ECIES）', () {
    test('成员封 → 发起人解：DH 对称往返无损', () async {
      final initiatorSpk = await X25519().newKeyPair();
      final memberEk = await X25519().newKeyPair();
      final signal = FreeMaskSignal(
        targetStartMs: day0,
        targetEndMs: day0 + 9 * hour,
        mask: '110011010',
      );

      final sealed = await sealFreeMaskForInitiator(
        signal: signal,
        senderEphemeral: memberEk,
        initiatorSpkPub: await initiatorSpk.extractPublicKey(),
      );
      final opened = await openFreeMaskFromMember(
        sealed: sealed,
        initiatorSpk: initiatorSpk,
      );

      expect(opened.mask, signal.mask);
      expect(opened.targetStartMs, signal.targetStartMs);
      expect(opened.targetEndMs, signal.targetEndMs);
      // 信封四件套齐且 base64
      expect(sealed.keys, containsAll(['ek_pub', 'nonce', 'cipher_text', 'mac']));
    });

    test('非发起人（错误 SPK 私钥）解不开：GCM MAC 校验失败', () async {
      final initiatorSpk = await X25519().newKeyPair();
      final attacker = await X25519().newKeyPair();
      final memberEk = await X25519().newKeyPair();
      final sealed = await sealFreeMaskForInitiator(
        signal: const FreeMaskSignal(
            targetStartMs: 0, targetEndMs: hour, mask: '11'),
        senderEphemeral: memberEk,
        initiatorSpkPub: await initiatorSpk.extractPublicKey(),
      );
      // 异步断言必须 expectLater 接住：expect(() => asyncFn(), throwsA) 不 await
      // Future，解密失败会漏成未捕获异步错误（与 collab_key_store_test 同款反模式）。
      await expectLater(
        openFreeMaskFromMember(sealed: sealed, initiatorSpk: attacker),
        throwsA(anything),
      );
    });

    test('密文篡改即拒（认证加密）', () async {
      final initiatorSpk = await X25519().newKeyPair();
      final memberEk = await X25519().newKeyPair();
      final sealed = await sealFreeMaskForInitiator(
        signal: const FreeMaskSignal(
            targetStartMs: 0, targetEndMs: hour, mask: '10'),
        senderEphemeral: memberEk,
        initiatorSpkPub: await initiatorSpk.extractPublicKey(),
      );
      final tampered = Map.of(sealed);
      tampered['cipher_text'] = 'AAAA${sealed['cipher_text']}';
      await expectLater(
        openFreeMaskFromMember(sealed: tampered, initiatorSpk: initiatorSpk),
        throwsA(anything),
      );
    });
  });
}
