import 'package:crypto/crypto.dart';
import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:shiguang/collab/identity.dart';
import 'package:shiguang/collab/relay/auth_api.dart';
import 'package:shiguang/collab/relay/friends_api.dart';
import 'package:shiguang/collab/relay/relay_mock.dart';

// 账号域 REST 契约客户端（Plan A）：登录/续期/改密/lookup/搜索加好友，
// 全部打在忠实复刻 relay 契约的进程内 mock 上（零外网）。

void main() {
  late RelayMockServer relay;
  late Dio dio;
  late String access;
  late String bobUserId;

  setUp(() async {
    relay = RelayMockServer();
    await relay.start();
    dio = Dio();
    // 预置一个已注册用户 bob
    final ch = await fetchChallenge(dio, relay.baseUrl);
    await registerUser(
      dio,
      relay.baseUrl,
      buildRegisterBody(
        username: 'bob',
        password: 'password-bob',
        identityKeyPub: List<int>.filled(32, 1),
        signedPrekeyPub: List<int>.filled(32, 2),
        signedPrekeySig: List<int>.filled(64, 3),
        nonce: ch.nonce,
        powSolution: _solve(ch.powChallenge, ch.powDifficulty),
        oneTimePreKeys: _opks(10),
      ),
    );
    final pair = await login(dio, relay.baseUrl, 'bob', 'password-bob');
    access = pair.access;
    bobUserId = (await lookupUser(dio, relay.baseUrl, access, 'bob')).userId;
  });

  tearDown(() => relay.close());

  test('登录：正确凭据换双 Token（带 access 过期时刻）', () async {
    final pair = await login(dio, relay.baseUrl, 'bob', 'password-bob');
    expect(pair.access, isNotEmpty);
    expect(pair.refresh, isNotEmpty);
    expect(pair.accessExpiresAt,
        greaterThan(DateTime.now().millisecondsSinceEpoch ~/ 1000));
  });

  test('登录：用户不存在与密码错误同一 401 口径（防枚举，UI 不得区分）', () async {
    final wrongPw =
        await _codeOf(() => login(dio, relay.baseUrl, 'bob', 'wrong-pass-1'));
    expect(wrongPw, 'USER_OR_PASSWORD_INVALID');
    final noUser =
        await _codeOf(() => login(dio, relay.baseUrl, 'nobody', 'password-x'));
    expect(noUser, 'USER_OR_PASSWORD_INVALID',
        reason: '同一 error_code，绝不泄露账号是否存在');
  });

  test('refresh：旧 refresh token 换新双 Token', () async {
    final pair = await login(dio, relay.baseUrl, 'bob', 'password-bob');
    final next = await refresh(dio, relay.baseUrl, pair.refresh);
    expect(next.access, isNot(pair.access));
    expect(next.accessExpiresAt, greaterThan(0));
  });

  test('change-password：旧密码错 401；改完新密码可登录', () async {
    await expectLater(
      changePassword(dio, relay.baseUrl, access,
          oldPassword: 'wrong-old-pass', newPassword: 'password-new'),
      throwsA(isA<RelayAuthException>()),
    );
    await changePassword(dio, relay.baseUrl, access,
        oldPassword: 'password-bob', newPassword: 'password-new');
    final pair = await login(dio, relay.baseUrl, 'bob', 'password-new');
    expect(pair.access, isNotEmpty);
  });

  test('lookup：按用户名取身份（含公钥材料）；查无此人 404', () async {
    final u = await lookupUser(dio, relay.baseUrl, access, 'bob');
    expect(u.userId, bobUserId);
    expect(u.username, 'bob');
    expect(u.identityKeyPub, hasLength(32));
    expect(u.signedPrekeyPub, hasLength(32));

    await expectLater(
      lookupUser(dio, relay.baseUrl, access, 'ghost'),
      throwsA(isA<RelayAuthException>()),
    );
  });

  test('搜索加好友：friends/request 201 落 pending（invite_id=发起:接收）', () async {
    final ch = await fetchChallenge(dio, relay.baseUrl);
    await registerUser(
      dio,
      relay.baseUrl,
      buildRegisterBody(
        username: 'carol',
        password: 'password-c',
        identityKeyPub: List<int>.filled(32, 4),
        signedPrekeyPub: List<int>.filled(32, 5),
        signedPrekeySig: List<int>.filled(64, 6),
        nonce: ch.nonce,
        powSolution: _solve(ch.powChallenge, ch.powDifficulty),
        oneTimePreKeys: _opks(10),
      ),
    );
    final carolId =
        (await lookupUser(dio, relay.baseUrl, access, 'carol')).userId;

    final inviteId = await submitFriendRequest(dio, relay.baseUrl, access,
        toUserId: carolId);
    expect(inviteId, '$bobUserId:$carolId');
  });

  test('errorCodeOf：从服务端错误体提取 error_code，非标准体返回 null', () {
    expect(errorCodeOf({'error_code': 'RATE_LIMITED'}), 'RATE_LIMITED');
    expect(errorCodeOf('plain text'), isNull);
    expect(errorCodeOf(null), isNull);
  });

  test('retryAfterSecOf：从错误体 detail 提取锁定等待窗口', () {
    expect(
      retryAfterSecOf({
        'error_code': 'LOGIN_THROTTLED',
        'detail': {'retry_after_sec': 900},
      }),
      900,
    );
    expect(retryAfterSecOf({'error_code': 'LOGIN_THROTTLED'}), isNull);
  });

  test('refresh 强制轮换：换新后旧 refresh 重用 → 401 TOKEN_REVOKED', () async {
    final pair = await login(dio, relay.baseUrl, 'bob', 'password-bob');
    final next = await refresh(dio, relay.baseUrl, pair.refresh);
    expect(next.access, isNot(pair.access));

    final reused = await _codeOf(() => refresh(dio, relay.baseUrl, pair.refresh));
    expect(reused, 'TOKEN_REVOKED');
  });

  test('登录防爆破：同账号失败 ≥5 次锁 15min（正确密码也 429 + retry_after_sec）',
      () async {
    String? last;
    for (var i = 0; i < 5; i++) {
      last = await _codeOf(
          () => login(dio, relay.baseUrl, 'bob', 'wrong-pass-$i'));
    }
    expect(last, 'USER_OR_PASSWORD_INVALID');

    final locked = await _codeOf(
        () => login(dio, relay.baseUrl, 'bob', 'password-bob'));
    expect(locked, 'LOGIN_THROTTLED',
        reason: '锁定期内正确密码也 429（防爆破硬约束）');
  });

  test('logout：access 拉黑（后续 /me 401）；重复登出幂等无害', () async {
    final pair = await login(dio, relay.baseUrl, 'bob', 'password-bob');
    await logoutRemote(dio, relay.baseUrl, pair.access,
        refreshToken: pair.refresh);

    final denied = await _codeOf(() => fetchMe(dio, relay.baseUrl, pair.access));
    expect(denied, 'UNAUTHORIZED');
    // 被拉黑的 refresh 也不得换新
    final revoked =
        await _codeOf(() => refresh(dio, relay.baseUrl, pair.refresh));
    expect(revoked, 'TOKEN_REVOKED');

    // 幂等：重复登出仍 200
    await logoutRemote(dio, relay.baseUrl, pair.access,
        refreshToken: pair.refresh);
  });

  test('自账户：GET /me 读回身份；PATCH 三字段独立生效 + 空串清空 push_token',
      () async {
    final me = await fetchMe(dio, relay.baseUrl, access);
    expect(me.userId, bobUserId);
    expect(me.username, 'bob');
    expect(me.encryptedProfile, isNull, reason: '未设置时字段缺省');
    // v3 拍板：注册即上传密码派生 KEK 封存的恢复金库（不再是 null）
    expect(me.recoveryVault, isNotNull);

    final profile = List<int>.generate(5, (i) => i + 1);
    await patchMe(dio, relay.baseUrl, access, encryptedProfile: profile);
    expect((await fetchMe(dio, relay.baseUrl, access)).encryptedProfile,
        profile);

    final vault = List<int>.generate(9, (i) => i * 2);
    await patchMe(dio, relay.baseUrl, access, recoveryVault: vault);
    final afterVault = await fetchMe(dio, relay.baseUrl, access);
    expect(afterVault.recoveryVault, vault);
    expect(afterVault.encryptedProfile, profile,
        reason: 'PATCH 缺省字段不动（独立生效）');

    // push_token present 空串 = 清空；其余字段不动
    await patchMe(dio, relay.baseUrl, access, pushToken: 'fcm-token-1');
    await patchMe(dio, relay.baseUrl, access, pushToken: '');
  });

  test('好友关系管理：解除（三合一）→ 可重新申请；拉黑后申请同 409 口径', () async {
    // 预置 carol，bob→carol 建 pending
    final ch = await fetchChallenge(dio, relay.baseUrl);
    await registerUser(
      dio,
      relay.baseUrl,
      buildRegisterBody(
        username: 'carol',
        password: 'password-c',
        identityKeyPub: List<int>.filled(32, 4),
        signedPrekeyPub: List<int>.filled(32, 5),
        signedPrekeySig: List<int>.filled(64, 6),
        nonce: ch.nonce,
        powSolution: _solve(ch.powChallenge, ch.powDifficulty),
        oneTimePreKeys: _opks(10),
      ),
    );
    final carolId =
        (await lookupUser(dio, relay.baseUrl, access, 'carol')).userId;

    // 重复申请 → 409 RELATION_EXISTS（同一口径，不区分 pending/好友/被拉黑）
    await submitFriendRequest(dio, relay.baseUrl, access, toUserId: carolId);
    final dup = await _codeOf(
        () => submitFriendRequest(dio, relay.baseUrl, access, toUserId: carolId));
    expect(dup, 'RELATION_EXISTS');

    // 撤回申请（解除三合一）→ removed；此后可重新申请
    expect(
        await removeFriend(dio, relay.baseUrl, access, carolId), 'removed');
    await submitFriendRequest(dio, relay.baseUrl, access, toUserId: carolId);

    // bob 拉黑 carol（无需既有关系；pending 一并转 blocked）→ blocked
    expect(await blockFriend(dio, relay.baseUrl, access, carolId), 'blocked');
    // 无关系的人解除 → 404 INVITE_NOT_FOUND
    final ghost = await _codeOf(
        () => removeFriend(dio, relay.baseUrl, access, 'u-ghost'));
    expect(ghost, 'INVITE_NOT_FOUND');
  });
}

List<PreKey> _opks(int n) => List.generate(
      n,
      (i) => PreKey(
        'opk-$i',
        List<int>.filled(32, i + 1),
        List<int>.filled(64, 7),
      ),
    );

/// 与 lib/collab/pow.dart 同口径：SHA256(challenge + solution) 的 hex 前导零。
String _solve(String challenge, int difficulty) {
  for (var i = 0;; i++) {
    final sol = '$i-$challenge';
    final digest = sha256.convert(challenge.codeUnits + sol.codeUnits).toString();
    if (digest.startsWith('0' * difficulty)) return sol;
  }
}

/// 跑一个预期失败的请求，返回服务端 error_code（auth/friends 两族异常统一提取）。
Future<String> _codeOf(Future<void> Function() run) async {
  try {
    await run();
  } on RelayAuthException catch (e) {
    return errorCodeOf(e.detail) ?? '';
  } on RelayFriendsException catch (e) {
    return errorCodeOf(e.detail) ?? '';
  }
  fail('期望抛 RelayAuthException / RelayFriendsException');
}
