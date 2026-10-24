import 'dart:async';
import 'dart:convert';

import 'package:cryptography/cryptography.dart';
import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:shiguang/collab/crypto/session.dart';
import 'package:shiguang/collab/identity.dart';
import 'package:shiguang/collab/pipeline.dart';
import 'package:shiguang/collab/pow.dart';
import 'package:shiguang/collab/proto/relay_protocol.pb.dart';
import 'package:shiguang/collab/relay/auth_api.dart';
import 'package:shiguang/collab/relay/relay_mock.dart';
import 'package:shiguang/collab/relay/wss_client.dart';
import 'package:shiguang/collab/sealing.dart';

/// 轮询等待条件成立，超时抛 [TimeoutException]。
Future<void> _waitUntil(bool Function() cond,
    {Duration timeout = const Duration(seconds: 10)}) async {
  final end = DateTime.now().add(timeout);
  while (!cond()) {
    if (DateTime.now().isAfter(end)) {
      throw TimeoutException('条件未在 ${timeout.inSeconds}s 内满足');
    }
    await Future.delayed(const Duration(milliseconds: 15));
  }
}

/// 一台「设备」：独立身份 + 登录态 + 可选 WSS + 单消费管道。
///
/// 与 test/collab_e2e_test.dart 的 _Device 同构，但支持 [connect]=false 的
/// 离线形态（错过广播、事后经 /sync 追平的场景用）。
///
/// 观测面口径：mock 中继对 bundle 内事件广播给「除发送者外」的全部连接
/// （ref hub.go 不回发者），因此**混合发送者**场景下任一设备的管道永远缺
/// 自己所发事件的 seq、`pipeline.applied` 会停在缺口——投递/顺序类断言一律
/// 在 `client.frames`（WSS 原始帧，即广播实收面）上做；`applied` 只用于
/// 「单发送者」bundle 的连续应用断言与单消费不变量。
class _Device {
  final String userId;
  final String username;
  final IdentityMaterial material;
  final TokenPair tokens;
  final RelayWssClient? client;
  final SingleConsumerPipeline pipeline;
  final Dio dio;
  final String baseUrl;

  _Device({
    required this.userId,
    required this.username,
    required this.material,
    required this.tokens,
    required this.client,
    required this.pipeline,
    required this.dio,
    required this.baseUrl,
  });

  /// 注册 + 登录；[connect]=true 时连 WSS 并入管道。
  static Future<_Device> bootstrap(String username, String baseUrl, Dio dio,
      {bool connect = true}) async {
    final material = await generateIdentity(opkCount: 12);
    final password = 'password-$username';

    final ch = await fetchChallenge(dio, baseUrl);
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
    final reg = await registerUser(dio, baseUrl, body);
    final tokens = await login(dio, baseUrl, username, password);

    final pipeline = SingleConsumerPipeline();
    RelayWssClient? client;
    if (connect) {
      client = RelayWssClient(
        wsUri: Uri.parse('${baseUrl.replaceFirst('http', 'ws')}/ws'),
        getToken: () async => tokens.access,
        keepaliveInterval: const Duration(hours: 1),
      );
      pipeline.attachWss(client);
      client.connect();
    }

    return _Device(
      userId: reg.userId,
      username: username,
      material: material,
      tokens: tokens,
      client: client,
      pipeline: pipeline,
      dio: dio,
      baseUrl: baseUrl,
    );
  }

  /// 取对端 SPK 公钥（X3DH 预密钥包）。
  Future<SimplePublicKey> fetchPeerSpk(String peerId) async {
    final resp = await dio.get('$baseUrl/v1/users/prekeys?user_id=$peerId');
    final pub = base64Decode(resp.data['signed_prekey_pub'] as String);
    return SimplePublicKey(pub, type: KeyPairType.x25519);
  }

  /// 用与 [peer]（收件人）协商的对称 DH 密钥密封明文，经 WSS 发布到 [bundleId]。
  Future<void> publish(String bundleId, List<int> plaintext, _Device peer,
      {ActionType action = ActionType.PROPOSE, String? clientMsgId}) async {
    final key = await agreeSessionKey(
      mySpk: material.signedPreKey,
      peerSpkPub: await fetchPeerSpk(peer.userId),
    );
    final sealed = await sealPayload(key, plaintext, 1);
    final cf = ClientFrame()
      ..schedule = (Envelope()
        ..action = action
        ..bundleId = bundleId
        ..cipher = utf8.encode(sealed.encode())
        ..keyVer = 1
        ..clientMsgId = clientMsgId ?? '');
    client!.send(cf);
  }

  /// 解开一条针对本设备的密文事件。
  Future<List<int>> open(CollabInboundEvent e, _Device sender) async {
    final key = await agreeSessionKey(
      mySpk: material.signedPreKey,
      peerSpkPub: await fetchPeerSpk(sender.userId),
    );
    return openPayload(
        key, SealedPayload.decode(utf8.decode(e.cipher!)));
  }

  void dispose() => client?.dispose();
}

/// 订阅 [device] 的 WSS 原始帧（广播实收面），收集到 [sink]。
void _tapFrames(_Device device, List<ServerFrame> sink) {
  device.client!.frames.listen(sink.add);
}

/// 订阅 [device] 管道 applied 流，收集到 [sink]。
void _tapApplied(_Device device, List<CollabInboundEvent> sink) {
  device.pipeline.applied.listen(sink.add);
}

void main() {
  // ---- 小规模场景（2–4 端）：完整协商闭环 ----

  test('小规模 4 端：发起 → 逐端定向解密 → 3 票回执 → 全局 seq 唯一一致', () async {
    final relay = RelayMockServer();
    await relay.start();
    final dio = Dio();
    final initiator = await _Device.bootstrap('initiator', relay.baseUrl, dio);
    final members = [
      await _Device.bootstrap('m01', relay.baseUrl, dio),
      await _Device.bootstrap('m02', relay.baseUrl, dio),
      await _Device.bootstrap('m03', relay.baseUrl, dio),
    ];
    await _waitUntil(() => relay.authedUsers.toSet().containsAll(
        {initiator.userId, ...members.map((m) => m.userId)}));

    final frames = {
      for (final d in [initiator, ...members]) d.userId: <ServerFrame>[],
    };
    for (final d in [initiator, ...members]) {
      _tapFrames(d, frames[d.userId]!);
    }

    // ① 发起人向 3 名成员各自密封 PROPOSE（同一 bundle，逐条单独密封）
    for (final m in members) {
      await initiator.publish(
          'b-quorum', utf8.encode('propose→${m.username}:周五聚餐@18:00'), m);
    }
    // ② 定向加密：每名成员收到全部 3 条，但**恰好一条**能被自己解开——
    //    别人信封打开必失败（MAC），这本身就是密封定向性的断言。
    for (final m in members) {
      await _waitUntil(() =>
          frames[m.userId]!
              .where((f) =>
                  f.schedule.bundleId == 'b-quorum' &&
                  f.schedule.action == ActionType.PROPOSE)
              .length >=
          3);
      final mine = frames[m.userId]!
          .where((f) =>
              f.schedule.bundleId == 'b-quorum' &&
              f.schedule.action == ActionType.PROPOSE)
          .map((f) => CollabInboundEvent.fromServerFrame(f))
          .toList();
      var opened = 0;
      List<int> plaintext = [];
      for (final e in mine) {
        try {
          plaintext = await m.open(e, initiator);
          opened++;
        } on SecretBoxAuthenticationError {
          // 别人的信封，本就打不开
        }
      }
      expect(opened, 1, reason: '${m.username} 应恰好解开属于自己的 1 条信封');
      expect(utf8.decode(plaintext), 'propose→${m.username}:周五聚餐@18:00');
    }

    // ③ 三名成员各回 ACCEPT（各自向发起人密封）
    for (var i = 0; i < members.length; i++) {
      await members[i].publish(
          'b-quorum', utf8.encode('accept:m0${i + 1}'), initiator,
          action: ActionType.ACCEPT, clientMsgId: 'vote-m0${i + 1}');
    }

    // ④ 发起人收齐 3 票，发送者恰为 3 名成员
    await _waitUntil(() =>
        frames[initiator.userId]!
            .where((f) => f.schedule.action == ActionType.ACCEPT)
            .length >=
        3);
    final voters = frames[initiator.userId]!
        .where((f) => f.schedule.action == ActionType.ACCEPT)
        .map((f) => f.schedule.senderHint)
        .toSet();
    expect(voters.length, 3);
    expect(voters, containsAll(members.map((m) => m.userId)));

    // ⑤ 全局 seq 一致性：同 bundle 内 1..6 连续无重，每条 seq 全局唯一 sender
    final seqSender = <int, String>{};
    for (final d in [initiator, ...members]) {
      for (final f in frames[d.userId]!) {
        if (f.schedule.bundleId != 'b-quorum') continue;
        final seq = f.seqId.toInt();
        final prev = seqSender[seq];
        expect(prev, anyOf(isNull, f.schedule.senderHint),
            reason: 'seq $seq 出现了两个不同 sender（$prev vs ${f.schedule.senderHint}）');
        seqSender[seq] = f.schedule.senderHint;
      }
    }
    expect(seqSender.keys.toSet(), {1, 2, 3, 4, 5, 6});

    for (final d in [initiator, ...members]) {
      d.dispose();
    }
    await relay.close();
  });

  // ---- 大规模场景（16 端 = 成员上限口径 §10.4）----

  test('大规模 16 端并发发文风暴：广播实收面 seq 一致无重无缺、管道连续前缀、单消费恒 1',
      () async {
    final relay = RelayMockServer();
    await relay.start();
    final dio = Dio();
    const n = 16;
    final devices = <_Device>[];
    for (var i = 0; i < n; i++) {
      devices.add(await _Device.bootstrap('u${i.toString().padLeft(2, '0')}',
          relay.baseUrl, dio));
    }
    await _waitUntil(() => relay.authedUsers.length >= n);

    final frames = {for (final d in devices) d.userId: <ServerFrame>[]};
    final applied = {for (final d in devices) d.userId: <CollabInboundEvent>[]};
    for (final d in devices) {
      _tapFrames(d, frames[d.userId]!);
      _tapApplied(d, applied[d.userId]!);
    }

    // 16 端同时向同一 bundle 各发 1 条（并发风暴，无人工错峰）
    await Future.wait([
      for (var i = 0; i < n; i++)
        devices[i].publish('b-storm', utf8.encode('storm-$i'),
            devices[(i + 1) % n],
            action: ActionType.UPDATE_FACT, clientMsgId: 'storm-$i'),
    ]);

    // 全端实收 n-1 条（发送者被中继排除）
    await _waitUntil(
        () => frames.values
            .every((fs) => fs.where((f) => f.schedule.bundleId == 'b-storm').length >= n - 1),
        timeout: const Duration(seconds: 20));

    // 每端实收 seq 集 = 全集减去自己那条（先由「缺失者」反推 own seq）
    final globalSeqs = <int>{};
    final seqSender = <int, String>{};
    for (final d in devices) {
      for (final f in frames[d.userId]!) {
        if (f.schedule.bundleId != 'b-storm') continue;
        final seq = f.seqId.toInt();
        globalSeqs.add(seq);
        final prev = seqSender[seq];
        expect(prev, anyOf(isNull, f.schedule.senderHint),
            reason: 'seq $seq 全局出现两个 sender');
        seqSender[seq] = f.schedule.senderHint;
      }
    }
    expect(seqSender.length, n, reason: '服务端对 n 条并发发文应发号 n 个不同 seq');
    expect(globalSeqs, {for (var i = 1; i <= n; i++) i});
    expect(seqSender.values.toSet().length, n, reason: 'n 个 sender 各占 1 个 seq');

    for (final d in devices) {
      final got = frames[d.userId]!
          .where((f) => f.schedule.bundleId == 'b-storm')
          .map((f) => f.seqId.toInt())
          .toSet();
      final own = seqSender.entries
          .firstWhere((e) => e.value == d.userId)
          .key;
      expect(got, globalSeqs.difference({own}),
          reason: '${d.username} 实收 seq 集应恰为全集减自己的 seq $own');
    }

    // 管道层：applied 为从 1 起的连续前缀（缺自己 seq 即停，绝不乱序/跳号/重复），
    // 且 seq 最大的发送者（无人再缺它的下游号）能应用满 n-1 条。
    final ownSeqOf = {
      for (final d in devices)
        d.userId: seqSender.entries.firstWhere((e) => e.value == d.userId).key,
    };
    for (final d in devices) {
      final seenSeqs = frames[d.userId]!
          .where((f) => f.schedule.bundleId == 'b-storm')
          .map((f) => f.seqId.toInt())
          .toList();
      // frames 层已证无重（集合大小 = 条数）
      expect(seenSeqs.toSet().length, seenSeqs.length,
          reason: '${d.username} 实收帧出现重复 seq');
      // own seq = n 的设备不缺任何下游号，应连续应用满 n-1 条
      if (ownSeqOf[d.userId] == n) {
        await _waitUntil(() => applied[d.userId]!.length >= n - 1,
            timeout: const Duration(seconds: 5));
        expect(applied[d.userId]!.map((e) => e.seqId).toList(),
            [for (var i = 1; i <= n - 1; i++) i]);
      }
      // 应用面为全局 seq 的连续前缀（缺号即停，不乱序不跳号）
      final gotApplied = applied[d.userId]!.map((e) => e.seqId).toList();
      expect(gotApplied, [for (var i = 1; i <= gotApplied.length; i++) i],
          reason: '${d.username} 管道应用面不是从 1 起的连续前缀');
      expect(d.pipeline.peakConcurrent, 1,
          reason: '${d.username} 管道出现并发消费');
    }

    for (final d in devices) {
      d.dispose();
    }
    await relay.close();
  });

  test('大规模：离线端错过广播，事后经 /sync 追平且与实时端应用结果一致（单发送者口径）',
      () async {
    final relay = RelayMockServer();
    await relay.start();
    final dio = Dio();
    final sender = await _Device.bootstrap('sender01', relay.baseUrl, dio);
    final online = await _Device.bootstrap('online01', relay.baseUrl, dio);
    final offline = await _Device.bootstrap('latecomer', relay.baseUrl, dio,
        connect: false);
    await _waitUntil(() =>
        relay.authedUsers.toSet().containsAll({sender.userId, online.userId}));

    // 实时端订阅 applied（在线收端，无自发事件 → 连续应用不受缺口影响）
    final onlineApplied = <CollabInboundEvent>[];
    _tapApplied(online, onlineApplied);
    final offlineApplied = <CollabInboundEvent>[];
    _tapApplied(offline, offlineApplied);

    // 单发送者连发 10 条（混合两种 action）
    for (var i = 1; i <= 10; i++) {
      await sender.publish('b-catchup', utf8.encode('evt-$i'), online,
          action: i.isEven ? ActionType.VOTE : ActionType.UPDATE_FACT,
          clientMsgId: 'evt-$i');
    }

    // 实时端管道连续应用 1..10
    await _waitUntil(() => onlineApplied.length >= 10);
    expect(onlineApplied.map((e) => e.seqId).toList(), [for (var i = 1; i <= 10; i++) i]);
    expect(onlineApplied.map((e) => e.action).toSet(),
        {ActionType.UPDATE_FACT, ActionType.VOTE});

    // 离线端经 HTTP /sync 全量拉取灌入同一管道（Wake Pull 口径），追平结果一致
    final syncResp =
        await dio.get('${relay.baseUrl}/v1/sync?bundle_id=b-catchup&after=0');
    final frames = (syncResp.data['events'] as List)
        .map((e) => ServerFrame.fromBuffer(base64Decode(e as String)))
        .toList();
    expect(frames.map((f) => f.seqId.toInt()).toList(),
        [for (var i = 1; i <= 10; i++) i]);
    offline.pipeline.pushSyncBatch(frames
        .map((f) => CollabInboundEvent.fromServerFrame(f, source: 'http'))
        .toList());
    await _waitUntil(() => offlineApplied.length >= 10);
    expect(offlineApplied.map((e) => e.seqId).toList(),
        onlineApplied.map((e) => e.seqId).toList(),
        reason: '离线追平的应用顺序应与实时端完全一致');
    expect(offlineApplied.map((e) => e.action).toList(),
        onlineApplied.map((e) => e.action).toList());

    // 双路同涌去重：同一批再灌一遍（模拟 WSS 迟到 + HTTP 重拉），不重复应用
    offline.pipeline.pushSyncBatch(frames
        .map((f) => CollabInboundEvent.fromServerFrame(f, source: 'wss'))
        .toList());
    await Future.delayed(const Duration(milliseconds: 200));
    expect(offlineApplied.length, 10, reason: '重复批次不得产生重复应用');

    sender.dispose();
    online.dispose();
    await relay.close();
  });
}
