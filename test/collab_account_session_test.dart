import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:shiguang/collab/account.dart';
import 'package:shiguang/collab/crypto/key_store.dart';
import 'package:shiguang/collab/crypto/secret_store.dart';
import 'package:shiguang/collab/identity_store.dart';
import 'package:shiguang/collab/relay/auth_api.dart';
import 'package:shiguang/collab/relay/relay_mock.dart';

// 账号会话（登录态单一来源）：注册即登录、凭据持久化跨进程、access 只存内存、
// refresh 强制轮换、改密踢会话、登出走服务端。全部打在进程内 mock relay 上。

AccountSession _session(RelayMockServer relay, MemorySecretStore secrets,
        {IdentityStore? identityStore}) =>
    AccountSession(
      dio: Dio(),
      baseUrl: relay.baseUrl,
      keyStore: KeyStore(MemorySecretStore()),
      identityStore: identityStore ?? MemoryIdentityStore(),
      secrets: secrets,
    );

Future<String?> _blobRefresh(MemorySecretStore secrets) async {
  final raw = await secrets.read('collab.account.session');
  if (raw == null) return null;
  return (jsonDecode(raw) as Map<String, dynamic>)['refresh_token'] as String?;
}

void main() {
  late RelayMockServer relay;

  setUp(() async {
    relay = RelayMockServer();
    await relay.start();
  });

  tearDown(() => relay.close());

  test('注册即登录：状态 loggedIn、username/user_id 入账、协同身份落账', () async {
    final secrets = MemorySecretStore();
    final identities = MemoryIdentityStore();
    final s = _session(relay, secrets, identityStore: identities);

    expect(s.status, AccountStatus.unknown);
    await s.register(username: 'Alice', password: 'password-1');
    expect(s.isLoggedIn, isTrue);
    expect(s.username, 'alice', reason: '端侧归一小写，与服务端同口径');
    expect(s.userId, startsWith('u-'));
    expect((await identities.load())?.userId, s.userId,
        reason: '注册生成的协同身份与账号绑定同一 user_id');
  });

  test('凭据持久化红线：安全区只存 refresh_token，access 绝不落盘（§5）', () async {
    final secrets = MemorySecretStore();
    final s = _session(relay, secrets);
    await s.register(username: 'bob', password: 'password-1');
    await s.ensureAccessToken();

    final raw = await secrets.read('collab.account.session');
    final blob = jsonDecode(raw!) as Map<String, dynamic>;
    expect(blob.containsKey('access_token'), isFalse,
        reason: 'access_token 只存内存（契约红线），进程被杀即随行');
    expect(blob['refresh_token'], isNotEmpty);
  });

  test('换进程 restore：无 access 也 loggedIn，首次取票走 refresh 轮换', () async {
    final secrets = MemorySecretStore();
    final s = _session(relay, secrets);
    await s.register(username: 'bob', password: 'password-1');
    final refreshV1 = await _blobRefresh(secrets);

    // 模拟换进程：全新内存态（access 已随进程消失），只剩安全区里的 refresh
    final s2 = _session(relay, secrets);
    await s2.restore();
    expect(s2.isLoggedIn, isTrue);
    expect(s2.username, 'bob');

    final access = await s2.ensureAccessToken();
    expect(access, isNotEmpty);
    // 轮换：新票已落盘，旧 refresh 已被服务端拉黑
    final refreshV2 = await _blobRefresh(secrets);
    expect(refreshV2, isNot(refreshV1));
  });

  test('restore：无凭据 → loggedOut；坏数据不崩溃', () async {
    final secrets = MemorySecretStore();
    final s = _session(relay, secrets);
    await s.restore();
    expect(s.status, AccountStatus.loggedOut);

    await secrets.write('collab.account.session', 'not-json{{');
    final s2 = _session(relay, secrets);
    await s2.restore();
    expect(s2.status, AccountStatus.loggedOut);
  });

  test('登录失败：统一 401 口径，状态保持 loggedOut；随后正确凭据可登录',
      () async {
    final secrets = MemorySecretStore();
    final s = _session(relay, secrets);
    await s.register(username: 'carol', password: 'password-1');
    await s.logout();

    await expectLater(
      s.login(username: 'carol', password: 'wrong-pass'),
      throwsA(isA<RelayAuthException>()),
    );
    expect(s.isLoggedIn, isFalse);

    await s.login(username: 'carol', password: 'password-1');
    expect(s.isLoggedIn, isTrue);
  });

  test('login-only（换机重装）：本机无身份记录，user_id 由 /v1/users/me 补齐',
      () async {
    final secrets = MemorySecretStore();
    final s = _session(relay, secrets);
    await s.register(username: 'dave', password: 'password-1');
    final expectedUserId = s.userId;
    await s.logout();

    // 另一台「设备」：同账号登录，本机没有协同身份记录
    final s2 = _session(relay, MemorySecretStore());
    await s2.login(username: 'dave', password: 'password-1');
    expect(s2.isLoggedIn, isTrue);
    expect(s2.userId, expectedUserId, reason: 'user_id 来自自账户读回（/me）');
  });

  test('ensureAccessToken：未到期用缓存；过期自动 refresh 轮换换新', () async {
    final secrets = MemorySecretStore();
    final s = _session(relay, secrets);
    await s.register(username: 'erin', password: 'password-1');
    final fresh = await s.ensureAccessToken();
    expect(fresh, isNotEmpty);

    // 把缓存的 access 标记为过期（refresh 仍是真票）：下一次取票必须走 refresh
    s.debugExpireAccessToken();
    final renewed = await s.ensureAccessToken();
    expect(renewed, isNot(fresh), reason: '轮换签发了新票');
  });

  test('refresh 轮换重用（旧票再换）→ 401 TOKEN_REVOKED，降级未登录', () async {
    final secrets = MemorySecretStore();
    final s = _session(relay, secrets);
    await s.register(username: 'frank', password: 'password-1');
    final refreshV1 = await _blobRefresh(secrets);

    s.debugExpireAccessToken();
    await s.ensureAccessToken(); // 轮换：v1 已被服务端拉黑

    // 盗号场景：拿旧 refresh 重放
    await secrets.write(
      'collab.account.session',
      jsonEncode({'username': 'frank', 'user_id': s.userId, 'refresh_token': refreshV1}),
    );
    final attacker = _session(relay, secrets);
    await attacker.restore();
    await expectLater(
      attacker.ensureAccessToken(),
      throwsA(isA<RelayAuthException>()),
    );
    expect(attacker.isLoggedIn, isFalse, reason: 'TOKEN_REVOKED：会话终结');
  });

  test('改密即踢会话：成功后本地转 loggedOut，旧票全灭；新密码可重新登录', () async {
    final secrets = MemorySecretStore();
    final s = _session(relay, secrets);
    await s.register(username: 'grace', password: 'password-1');
    final oldAccess = await s.ensureAccessToken();

    await s.changePassword(oldPassword: 'password-1', newPassword: 'password-2');
    expect(s.isLoggedIn, isFalse, reason: '服务端已踢全部会话，本地同步终结');
    expect(await _blobRefresh(secrets), isNull, reason: '本地凭据已清空');

    // 旧 access 已被纪元踢掉：打 /me 应 401
    await expectLater(
      fetchMe(Dio(), relay.baseUrl, oldAccess),
      throwsA(isA<RelayAuthException>()),
    );

    await s.login(username: 'grace', password: 'password-2');
    expect(s.isLoggedIn, isTrue);
  });

  test('登出走服务端：双 token 拉黑（旧 access 打受保护接口 401）+ 本地清凭据', () async {
    final secrets = MemorySecretStore();
    final s = _session(relay, secrets);
    await s.register(username: 'henry', password: 'password-1');
    final access = await s.ensureAccessToken();

    await s.logout();
    expect(s.isLoggedIn, isFalse);
    expect(await _blobRefresh(secrets), isNull);

    await expectLater(
      fetchMe(Dio(), relay.baseUrl, access),
      throwsA(isA<RelayAuthException>()),
      reason: '登出已把 access jti 拉黑',
    );
    // 幂等：重复登出不抛
    await s.logout();
  });

  test('端侧守卫：非法用户名/弱密码在触网前即拒（ArgumentError）', () async {
    final s = _session(relay, MemorySecretStore());
    expect(() => s.register(username: 'AB', password: 'password-1'),
        throwsA(isA<ArgumentError>()));
    expect(() => s.register(username: 'ok_name', password: 'short'),
        throwsA(isA<ArgumentError>()));
  });
}
