@Timeout(Duration(minutes: 3)) // 注册 429 退避重试预算 90s，需超 flutter_test 默认 30s
library;

import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:shiguang/collab/identity.dart';
import 'package:shiguang/collab/pow.dart';
import 'package:shiguang/collab/relay/auth_api.dart';
import 'package:shiguang/collab/relay/friends_api.dart';
import 'package:shiguang/collab/relay/peers_api.dart';
import 'package:shiguang/collab/relay/wss_client.dart';

/// 真 relay 联调场景测试（Phase 1 服务端真进程，非 relay_mock）。
///
/// 目标服务：REST 基址 `http://127.0.0.1:8080`（客户端自拼 `/v1` 前缀）
/// + WSS `ws://127.0.0.1:8080/ws`（shiguang-relay relayd 真进程）。服务不在时整组 skip，不阻塞 CI。
///
/// 约束：单 IP 注册限流 ≤3/min——本文件整场只注册 2 个用户，用户名带
/// 毫秒时间戳防重跑撞 409。

/// 真 relay 的幂等中间件要求每个 POST 带 `X-Client-Msg-Id`（relay_mock 未复刻
/// 该中间件）——统一经 [_dio] 构造，每请求自动生成唯一 id。
Dio _dio() {
  final dio = Dio();
  var n = 0;
  dio.interceptors.add(InterceptorsWrapper(
    onRequest: (options, handler) {
      options.headers['X-Client-Msg-Id'] =
          'live-${DateTime.now().microsecondsSinceEpoch}-${n++}';
      options.headers['X-Client-Ts'] =
          (DateTime.now().millisecondsSinceEpoch ~/ 1000).toString();
      handler.next(options);
    },
  ));
  return dio;
}

const _base = 'http://127.0.0.1:8080';

/// 真 relay 在场探针；不在场时 skip 整个测试组。
Future<bool> _relayUp() async {
  try {
    final dio = Dio(BaseOptions(connectTimeout: const Duration(seconds: 2),
        receiveTimeout: const Duration(seconds: 2)));
    final resp = await dio.get('$_base/v1/healthz');
    return resp.statusCode == 200 &&
        (resp.data is Map && resp.data['ok'] == true);
  } catch (_) {
    return false;
  }
}

/// 注册一台「设备」：独立身份 + 登录态。
Future<({String userId, IdentityMaterial material, TokenPair tokens})>
    _bootstrap(String username, Dio dio) async {
  final material = await generateIdentity(opkCount: 12);
  final password = 'password-$username';

  final ch = await fetchChallenge(dio, _base);
  final body = buildRegisterBody(
    username: username,
    password: password,
    identityKeyPub: await material.identityPublicKey,
    signedPrekeyPub: await material.signedPreKeyPublic,
    signedPrekeySig: await material.signedPreKeySignature(),
    nonce: ch.nonce,
    powSolution: solvePoW(ch.powChallenge, ch.powDifficulty),
    oneTimePreKeys: material.oneTimePreKeys,
  );
  final reg = await registerUser(dio, _base, body);
  final tokens = await login(dio, _base, username, password);
  return (userId: reg.userId, material: material, tokens: tokens);
}

String _uniq(String prefix) =>
    '$prefix${DateTime.now().millisecondsSinceEpoch.toRadixString(36)}';

/// 单 IP 注册限流 ≤3/min：撞 429 退避等窗口（全量并行跑时多文件同时注册会超配额，
/// 等待而非红）。限流按尝试计数且对齐分钟边界（unix/60），退避对齐到下一窗口
/// 边界加抖动，20s 固定步进可能三连落同窗全废。
Future<({String userId, IdentityMaterial material, TokenPair tokens})>
    _bootstrapRetry(String username, Dio dio) async {
  var attempt = 0;
  while (true) {
    try {
      return await _bootstrap(username, dio);
    } on RelayAuthException catch (e) {
      if (!e.toString().contains('RATE_LIMITED') || attempt >= 8) rethrow;
      attempt++;
      final now = DateTime.now();
      await Future.delayed(Duration(
          seconds: 60 - now.second + 2,
          milliseconds: now.millisecondsSinceEpoch % 4000));
    }
  }
}

void main() {
  late bool up;
  late Dio dio;
  late ({
    String userId,
    String username,
    IdentityMaterial material,
    TokenPair tokens,
  }) alice, bob;

  setUpAll(() async {
    up = await _relayUp();
    if (!up) return;
    dio = _dio();
    // 单 IP 注册限流 ≤3/min：全场只在 setUpAll 注册 2 个共享用户，各场景复用。
    final aName = _uniq('live_a');
    final a = await _bootstrapRetry(aName, dio);
    alice = (
      userId: a.userId,
      username: aName,
      material: a.material,
      tokens: a.tokens,
    );
    final bName = _uniq('live_b');
    final b = await _bootstrapRetry(bName, dio);
    bob = (
      userId: b.userId,
      username: bName,
      material: b.material,
      tokens: b.tokens,
    );
  });

  test('真 relay 场景：登录/换票 → 资料面 → 防御性拒绝', () async {
    if (!up) return;

    // 资料面：/users/me 回发号 id 与用户名
    final me = await fetchMe(dio, _base, alice.tokens.access);
    expect(me.userId, alice.userId);
    expect(me.username, alice.username);

    // refresh 换票：新 access 可用
    final fresh = await refresh(dio, _base, alice.tokens.refresh);
    expect(fresh.access, isNotEmpty);
    final me2 = await fetchMe(dio, _base, fresh.access);
    expect(me2.userId, alice.userId);

    // 防御面：弱密码 400（<8 位）——走完整注册链但被服务端口径拒绝
    final dupCh = await fetchChallenge(dio, _base);
    final dupBody = buildRegisterBody(
      username: _uniq('weak'),
      password: 'short',
      identityKeyPub: await bob.material.identityPublicKey,
      signedPrekeyPub: await bob.material.signedPreKeyPublic,
      signedPrekeySig: await bob.material.signedPreKeySignature(),
      nonce: dupCh.nonce,
      powSolution: solvePoW(dupCh.powChallenge, dupCh.powDifficulty),
      oneTimePreKeys: bob.material.oneTimePreKeys,
    );
    // 防御面断言走重试：这些调用本身也是注册请求，撞限流窗口（429）时
    // 退避等窗口，落到被测的真实错误码而非 429。
    Future<RelayAuthException> registerExpectError(
        Map<String, dynamic> body) async {
      var attempt = 0;
      while (true) {
        try {
          await registerUser(dio, _base, body);
          throw StateError('注册意外成功：$body');
        } on RelayAuthException catch (e) {
          if (!e.toString().contains('RATE_LIMITED') || attempt >= 8) return e;
          attempt++;
          final now = DateTime.now();
          await Future.delayed(Duration(
              seconds: 60 - now.second + 2,
              milliseconds: now.millisecondsSinceEpoch % 4000));
        }
      }
    }

    // 弱密码 400（<8 位）
    final weakErr = await registerExpectError(dupBody);
    expect(weakErr.toString(), contains('PASSWORD'), reason: '弱密码应被 400 口径拒绝，实际 $weakErr');
    // 无效 challenge nonce：复用已消费 nonce → 401 CHALLENGE_INVALID
    final staleCh = await fetchChallenge(dio, _base);
    final staleBody = Map<String, dynamic>.from(dupBody)
      ..['password'] = 'password-valid-pw'
      ..['nonce'] = staleCh.nonce;
    final staleErr = await registerExpectError(staleBody);
    expect(staleErr, isA<RelayAuthException>(), reason: '无效 nonce 应被 401 口径拒绝，实际 $staleErr');
  });

  test('真 relay 场景：lookup 搜索 → prekey 公钥包 → OPK 原子弹出', () async {
    if (!up) return;

    // 搜索路径：按用户名 lookup 拿对方 user_id
    final lookup =
        await lookupUser(dio, _base, alice.tokens.access, bob.username);
    expect(lookup.userId, bob.userId);

    // prekey 公钥包：身份钥 32B + SPK 32B + OPK 存量计数
    final bundle1 =
        await fetchPeerKeys(dio, _base, alice.tokens.access, bob.userId);
    expect(bundle1.identityKeyPub.length, 32);
    expect(bundle1.signedPrekeyPub.length, 32);
    final remaining1 = bundle1.remainingOpk;

    // 再拉一次：OPK 原子弹出 → 存量严格递减（幂等弹出语义）
    final bundle2 =
        await fetchPeerKeys(dio, _base, alice.tokens.access, bob.userId);
    expect(bundle2.remainingOpk, lessThan(remaining1),
        reason: 'OPK 应每拉一次原子弹出一个');
  });

  test('真 relay 场景：好友搜索路径 申请 → 审批 → 双向 accepted → 解除三合一',
      () async {
    if (!up) return;

    // A 按 user_id 直发申请（搜索路径第二跳；intro 留空服务端也收）
    final inviteId = await submitFriendRequest(dio, _base, alice.tokens.access,
        toUserId: bob.userId, encryptedIntro: '');

    // B 侧 pending 面单：回包带对方身份
    final pending = await fetchFriends(dio, _base, bob.tokens.access);
    expect(pending.map((f) => f.userId), contains(alice.userId));
    expect(pending.firstWhere((f) => f.userId == alice.userId).isAccepted,
        isFalse);

    // A 侧此刻也应看到自己发出的 pending
    final aView = await fetchFriends(dio, _base, alice.tokens.access);
    expect(aView.map((f) => f.userId), contains(bob.userId));

    // B 审批通过
    await acceptFriendInvite(dio, _base, bob.tokens.access, inviteId, true);

    // 双向 accepted
    final aFriends =
        await fetchAcceptedFriends(dio, _base, alice.tokens.access);
    final bFriends =
        await fetchAcceptedFriends(dio, _base, bob.tokens.access);
    expect(aFriends.map((f) => f.userId), contains(bob.userId));
    expect(bFriends.map((f) => f.userId), contains(alice.userId));

    // 解除三合一：删好友双向回陌生
    await removeFriend(dio, _base, alice.tokens.access, bob.userId);
    final aAfter = await fetchFriends(dio, _base, alice.tokens.access);
    expect(aAfter.map((f) => f.userId), isNot(contains(bob.userId)));
  });

  test('真 relay 场景：WSS 首帧 JSON AUTH 入场；非法 token 被掐', () async {
    if (!up) return;

    // 合法 token：客户端完成 AUTH 后进入 open 态且不被掐
    final ok = RelayWssClient(
      wsUri: Uri.parse('ws://127.0.0.1:8080/ws'),
      getToken: () async => alice.tokens.access,
      keepaliveInterval: const Duration(hours: 1),
    );
    final opened = Completer<void>();
    final sub = ok.state.listen((s) {
      if (s == WssConnectionState.open && !opened.isCompleted) {
        opened.complete();
      }
    });
    ok.connect();
    await opened.future.timeout(const Duration(seconds: 6),
        onTimeout: () => throw TimeoutException('合法 token 应完成 AUTH 进入 open'));
    // open 后再观察 2s：服务端不应关闭连接（表现为状态反复回 connecting）
    var fellBack = false;
    final sub2 = ok.state.listen((s) {
      if (s == WssConnectionState.connecting ||
          s == WssConnectionState.reconnecting) {
        fellBack = true;
      }
    });
    await Future.delayed(const Duration(seconds: 2));
    expect(fellBack, isFalse, reason: '合法 token 不应被服务端掐线进重连');
    await sub.cancel();
    await sub2.cancel();
    ok.dispose();

    // 非法 token：裸 WebSocket 直测服务端——AUTH 首帧后应立即被关
    // （hub.go：非法 token 立即 close；3s 未 AUTH 也掐）
    final ws = await WebSocket.connect('ws://127.0.0.1:8080/ws');
    ws.add(jsonEncode({'action': 'AUTH', 'token': 'bogus-token'}));
    final closed = Completer<void>();
    ws.listen((_) {}, onDone: () => closed.complete());
    await closed.future.timeout(const Duration(seconds: 6),
        onTimeout: () =>
            throw TimeoutException('非法 token 应在 AUTH 窗口内被服务端掐线'));
  });
}
