import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shiguang/collab/account.dart';
import 'package:shiguang/collab/crypto/key_store.dart';
import 'package:shiguang/collab/crypto/secret_store.dart';
import 'package:shiguang/collab/identity_store.dart';
import 'package:shiguang/collab/recovery_vault.dart';
import 'package:shiguang/collab/relay/auth_api.dart';
import 'package:shiguang/collab/relay/relay_mock.dart';


void main() {
  group('RecoveryVaultCodec（v3 拍板：金库包裹钥=登录密码派生）', () {
    final codec = RecoveryVaultCodec();
    final msk = Uint8List.fromList(List.generate(32, (i) => i));
    final idPriv = Uint8List.fromList(List.generate(32, (i) => i + 32));
    final spkPriv = Uint8List.fromList(List.generate(32, (i) => i + 64));

    test('封库→开库 roundtrip：三段私钥原样取回', () async {
      final vault = await codec.sealVault(
        password: 'correct-horse-battery',
        msk: msk,
        identityPriv: idPriv,
        spkPriv: spkPriv,
      );
      final keys = await codec.openVault(
        password: 'correct-horse-battery',
        envelope: vault,
      );
      expect(keys.msk, msk);
      expect(keys.identityPriv, idPriv);
      expect(keys.spkPriv, spkPriv);
    });

    test('信封含 KDF 参数与 salt（端侧自解析，服务端纯盲）', () async {
      final vault = await codec.sealVault(
          password: 'pw-123456', msk: msk, identityPriv: idPriv, spkPriv: spkPriv);
      final s = String.fromCharCodes(vault);
      expect(s, contains('argon2id'));
      expect(s, contains('salt'));
      expect(s, contains('mem_kib'));
    });

    test('错误密码 → RecoveryVaultException（绝不静默半盲）', () async {
      final vault = await codec.sealVault(
          password: 'right-password', msk: msk, identityPriv: idPriv, spkPriv: spkPriv);
      await expectLater(
        codec.openVault(password: 'wrong-password', envelope: vault),
        throwsA(isA<RecoveryVaultException>()),
      );
    });

    test('坏信封/错版本 → RecoveryVaultException', () async {
      await expectLater(
        codec.openVault(
            password: 'x', envelope: Uint8List.fromList('not-json'.codeUnits)),
        throwsA(isA<RecoveryVaultException>()),
      );
      final bad = Uint8List.fromList('{"v":99}'.codeUnits);
      await expectLater(
        codec.openVault(password: 'x', envelope: bad),
        throwsA(isA<RecoveryVaultException>()),
      );
    });

    test('独立 salt：同一密码两次封库密文不同', () async {
      final a = await codec.sealVault(
          password: 'same-pw', msk: msk, identityPriv: idPriv, spkPriv: spkPriv);
      final b = await codec.sealVault(
          password: 'same-pw', msk: msk, identityPriv: idPriv, spkPriv: spkPriv);
      expect(a, isNot(b));
    });
  });

  group('AccountSession 换机恢复 + 改密重封', () {
    test('注册后 /me 可取回金库；restoreIdentityFromVault 用密码恢复身份', () async {
      final relay = RelayMockServer();
      await relay.start();
      addTearDown(relay.close);
      final secrets = MemorySecretStore();
      final s = _session(relay, secrets);
      await s.register(username: 'iris', password: 'password-iris');

      // 服务端已有金库（注册时上传）
      final profile = await s.me();
      expect(profile.recoveryVault, isNotNull);

      // 模拟换机：全新空 session/空安全区/空 IdentityStore
      final secrets2 = MemorySecretStore();
      final fresh = _session(relay, secrets2);
      await fresh.login(username: 'iris', password: 'password-iris');
      final restored = await fresh.restoreIdentityFromVault(
          password: 'password-iris', username: 'iris');
      expect(restored, isTrue);
      // 恢复出的 MSK 与原机一致
      expect(fresh.keyStore.mskBytes, s.keyStore.mskBytes);
      // 身份记录已落本机
      final rec = await fresh.identityStore.load();
      expect(rec, isNotNull);
      expect(rec!.userId, profile.userId);
    });

    test('restoreIdentityFromVault 密码错误抛 RecoveryVaultException；无金库返回 false', () async {
      final relay = RelayMockServer();
      await relay.start();
      addTearDown(relay.close);
      final s = _session(relay, MemorySecretStore());
      await s.register(username: 'jack', password: 'password-jack');

      final fresh = _session(relay, MemorySecretStore());
      await fresh.login(username: 'jack', password: 'password-jack');
      await expectLater(
        fresh.restoreIdentityFromVault(password: 'wrong-pw', username: 'jack'),
        throwsA(isA<RecoveryVaultException>()),
      );

      // 无金库账号（PATCH 清空后）→ false
      await s.updateMe(recoveryVault: []);
      final bare = _session(relay, MemorySecretStore());
      await bare.login(username: 'jack', password: 'password-jack');
      expect(
          await bare.restoreIdentityFromVault(
              password: 'password-jack', username: 'jack'),
          isFalse);
    });

    test('改密即重封：改密后用新密码在换机侧恢复成功，旧密码失败', () async {
      final relay = RelayMockServer();
      await relay.start();
      addTearDown(relay.close);
      final s = _session(relay, MemorySecretStore());
      await s.register(username: 'kate', password: 'password-old');

      // 重新登录拿金库到内存（注册流程已带上；直接改密走重封路径）
      final fresh = _session(relay, MemorySecretStore());
      await fresh.login(username: 'kate', password: 'password-old');
      final me1 = await fresh.me();
      expect(me1.recoveryVault, isNotNull);

      // 改密（会话被踢）→ 新密码重登 → 恢复身份成功；旧密码解不开
      await fresh.changePassword(
          oldPassword: 'password-old', newPassword: 'password-new');
      expect(fresh.isLoggedIn, isFalse);

      final migrated = _session(relay, MemorySecretStore());
      await migrated.login(username: 'kate', password: 'password-new');
      await expectLater(
        migrated.restoreIdentityFromVault(
            password: 'password-old', username: 'kate'),
        throwsA(isA<RecoveryVaultException>()),
        reason: '旧密码派生的 KEK 解不开新密码重封的金库',
      );
      final ok = await migrated.restoreIdentityFromVault(
          password: 'password-new', username: 'kate');
      expect(ok, isTrue);
      expect(migrated.keyStore.mskBytes, s.keyStore.mskBytes);
    });
  });
}

AccountSession _session(RelayMockServer relay, MemorySecretStore secrets) {
  return AccountSession(
    dio: Dio(),
    baseUrl: relay.baseUrl,
    keyStore: KeyStore(secrets),
    identityStore: MemoryIdentityStore(),
    secrets: secrets,
  );
}
