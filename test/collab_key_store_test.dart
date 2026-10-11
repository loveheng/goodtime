import 'dart:typed_data';

import 'package:cryptography/cryptography.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:shiguang/collab/crypto/key_store.dart';
import 'package:shiguang/collab/crypto/secret_store.dart';

// 密钥域单测（2026-10-11 改版）：MSK 端侧自动托管——口令 KEK 层退役，
// 门禁统一为登录态，KeyStore 只剩「就绪 + seal/open」两面。

KeyStore _store([SecretStore? s]) => KeyStore(s ?? MemorySecretStore());

void main() {
  group('MSK 自动托管', () {
    test('ensureReady 生成 MSK 并落安全区；同 store 二次装配读回同一把', () async {
      final mem = MemorySecretStore();
      final ks1 = _store(mem);
      expect(ks1.isUnlocked, isFalse);
      await ks1.ensureReady();
      expect(ks1.isUnlocked, isTrue);
      expect(ks1.mskBytes, hasLength(32));

      // 模拟换进程：同一安全区内容、全新内存态
      final ks2 = _store(mem);
      expect(ks2.isUnlocked, isFalse);
      await ks2.ensureReady();
      expect(ks2.mskBytes, ks1.mskBytes, reason: '同一安全区=同一把 MSK');
    });

    test('ensureReady 幂等：重复调用不换钥', () async {
      final ks = _store();
      await ks.ensureReady();
      final msk = ks.mskBytes;
      await ks.ensureReady();
      await ks.ensureReady();
      expect(ks.mskBytes, msk);
    });

    test('并发 ensureReady 合并为一次：两边拿到同一把钥', () async {
      final ks = _store();
      await Future.wait([ks.ensureReady(), ks.ensureReady()]);
      expect(ks.mskBytes, hasLength(32));
    });

    test('不同安全区各自生成独立 MSK（互不相通）', () async {
      final a = _store();
      await a.ensureReady();
      final b = _store();
      await b.ensureReady();
      expect(a.mskBytes, isNot(b.mskBytes));
    });
  });

  group('seal/open', () {
    test('往返：密封后解封还原原文', () async {
      final ks = _store();
      await ks.ensureReady();
      final plain = Uint8List.fromList(List.generate(64, (i) => i));
      final sealed = await ks.seal(plain);
      final opened = await ks.open(sealed);
      expect(opened, plain);
    });

    test('未就绪（未 ensureReady）即 seal/open → StateError，绝不静默造钥', () async {
      final ks = _store();
      expect(() => ks.seal(Uint8List.fromList([1])),
          throwsA(isA<StateError>()));
      expect(() => ks.open(Uint8List.fromList([1])),
          throwsA(isA<StateError>()));
      // 就绪后恢复正常
      await ks.ensureReady();
      final sealed = await ks.seal(Uint8List.fromList([1, 2, 3]));
      expect(await ks.open(sealed), [1, 2, 3]);
    });

    test('别的 MSK 解不开（GCM MAC 校验失败抛出）', () async {
      final a = _store();
      await a.ensureReady();
      final b = _store();
      await b.ensureReady();
      final sealed = await a.seal(Uint8List.fromList([9, 9, 9]));
      expect(() => b.open(sealed),
          throwsA(isA<SecretBoxAuthenticationError>()));
    });
  });
}
