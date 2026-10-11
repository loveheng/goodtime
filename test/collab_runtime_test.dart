import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sqflite_common_ffi/sqflite_ffi.dart';

import 'package:shiguang/collab/account.dart';
import 'package:shiguang/collab/crypto/key_store.dart';
import 'package:shiguang/collab/crypto/secret_store.dart';
import 'package:shiguang/collab/identity_store.dart';
import 'package:shiguang/collab/relay/auth_api.dart';
import 'package:shiguang/collab/runtime.dart';
import 'package:shiguang/data/db.dart';

// 起停前置（2026-10-11 改版）：门禁=登录态（调用方判），start 只看「本机有
// 协同身份」。MSK 由 KeyStore 端侧自动托管，不再是前置。

AccountSession _account() => AccountSession(
      dio: Dio(),
      baseUrl: 'http://127.0.0.1:1',
      keyStore: KeyStore(MemorySecretStore()),
      identityStore: MemoryIdentityStore(),
    )..debugEstablishSession(
        username: 'alice',
        userId: 'u-a',
        tokens: const TokenPair(access: 'tok', refresh: 'r'),
      );

void main() {
  sqfliteFfiInit();
  databaseFactory = databaseFactoryFfi;

  late Database db;

  setUp(() async {
    db = await openDatabase(
      inMemoryDatabasePath,
      version: 7,
      onCreate: (d, _) => Db.createAll(d),
    );
  });

  tearDown(() async => await db.close());

  test('无协同身份 → start 返回 false（不触网、不崩；登录态不再是前置）', () async {
    final rt = CollabRuntime();
    final ok = await rt.start(
      db: db,
      keyStore: KeyStore(MemorySecretStore()),
      account: _account(),
      identityStore: MemoryIdentityStore(), // 空：模拟未注册
    );
    expect(ok, isFalse);
    expect(rt.running, isFalse);
    await rt.stop(); // 收尾不抛
  });

  test('重复 start 幂等：无身份时两次都 false，不重复建连', () async {
    final rt = CollabRuntime();
    final acct = _account();
    final a = await rt.start(
      db: db,
      keyStore: KeyStore(MemorySecretStore()),
      account: acct,
      identityStore: MemoryIdentityStore(),
    );
    final b = await rt.start(
      db: db,
      keyStore: KeyStore(MemorySecretStore()),
      account: acct,
      identityStore: MemoryIdentityStore(),
    );
    expect(a, isFalse);
    expect(b, isFalse);
    expect(rt.running, isFalse);
  });

  test('从未起过就 stop → 安全幂等不抛', () async {
    final rt = CollabRuntime();
    expect(rt.running, isFalse);
    await rt.stop();
    await rt.stop(); // 重复停也安全
    expect(rt.running, isFalse);
  });
}
