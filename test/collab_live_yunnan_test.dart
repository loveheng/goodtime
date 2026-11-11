@Timeout(Duration(minutes: 5)) // 4 用户注册限流退避（3/min，需跨两个窗口）需超默认 30s
library;

import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:cryptography/cryptography.dart';
import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:path/path.dart' as p;
import 'package:sqflite_common_ffi/sqflite_ffi.dart';

import 'package:shiguang/collab/crypto/bundle_key_source.dart';
import 'package:shiguang/collab/crypto/key_store.dart';
import 'package:shiguang/collab/crypto/secret_store.dart';
import 'package:shiguang/collab/freemask.dart';
import 'package:shiguang/collab/fsm.dart';
import 'package:shiguang/collab/identity.dart';
import 'package:shiguang/collab/outbox.dart';
import 'package:shiguang/collab/pipeline.dart';
import 'package:shiguang/collab/pow.dart';
import 'package:shiguang/collab/propose.dart';
import 'package:shiguang/collab/proto/relay_protocol.pb.dart';
import 'package:shiguang/collab/relay/action_api.dart';
import 'package:shiguang/collab/relay/auth_api.dart';
import 'package:shiguang/collab/relay/friends_api.dart';
import 'package:shiguang/collab/relay/keys_api.dart';
import 'package:shiguang/collab/relay/peers_api.dart';
import 'package:shiguang/collab/relay/sync_api.dart';
import 'package:shiguang/collab/relay/wss_client.dart';
import 'package:shiguang/collab/replay.dart' show replayBundle, StepSlot;
import 'package:shiguang/collab/sealing.dart';
import 'package:shiguang/data/collab_repository.dart';
import 'package:shiguang/data/db.dart';

/// 真 relay **多用户长行程全功能场景**：四人云南七日游。
///
/// 覆盖面（端侧数据/协议层，无 UI）：
/// ① 注册/登录/建联（4 用户，含 3 条好友边 + 限流退避）；
/// ② Directive 七步行程（逐成员信封 + 步骤级 required 门限）→ 全员接受 → 敲定；
/// ③ 发起人独占 PATCH_STEP_TIME 敲定后改期 → 全端一致；
/// ④ Consensus 选出发时段（3 候选 + quorum=3）→ PREFER/OK 混合投票 → winningSlot 固化；
/// ⑤ FreeMask 忙闲探测：QUERY 下发 → 成员本地量化掩码 → 单向加密回执 → 发起人解开核验；
/// ⑥ 四端事件集/FSM 终态逐项一致 + 换机冷启动回放一致。
///
/// 目标服务：REST `http://127.0.0.1:8080`（客户端自拼 `/v1`）+ WSS `ws://127.0.0.1:8080/ws`。
/// 服务不在场整组 skip。单 IP 注册限流 3/min——4 用户靠退避重试跨窗口。

const _base = 'http://127.0.0.1:8080';
const _wsUri = 'ws://127.0.0.1:8080/ws';

Dio _dio() {
  final dio = Dio();
  var n = 0;
  dio.interceptors.add(InterceptorsWrapper(
    onRequest: (options, handler) {
      options.headers['X-Client-Msg-Id'] =
          'yn-${DateTime.now().microsecondsSinceEpoch}-${n++}';
      options.headers['X-Client-Ts'] =
          (DateTime.now().millisecondsSinceEpoch ~/ 1000).toString();
      handler.next(options);
    },
  ));
  return dio;
}

Future<bool> _relayUp() async {
  try {
    final dio = Dio(BaseOptions(
        connectTimeout: const Duration(seconds: 2),
        receiveTimeout: const Duration(seconds: 2)));
    final resp = await dio.get('$_base/v1/healthz');
    return resp.statusCode == 200 &&
        (resp.data is Map && resp.data['ok'] == true);
  } catch (_) {
    return false;
  }
}

Future<void> _waitUntil(Future<bool> Function() cond,
    {Duration timeout = const Duration(seconds: 15), String? reason}) async {
  final end = DateTime.now().add(timeout);
  while (!(await cond())) {
    if (DateTime.now().isAfter(end)) {
      throw TimeoutException(
          '条件未在 ${timeout.inSeconds}s 内满足${reason == null ? '' : '：$reason'}');
    }
    await Future.delayed(const Duration(milliseconds: 50));
  }
}

final Map<String, ActionType> _actionByName = {
  for (final v in ActionType.values) v.name: v,
};
ActionType _actionOf(String name) => _actionByName[name]!;

/// 一台「设备」：独立 ffi SQLite 库 + 身份 + 登录态 + WSS + 单消费管道。
class _Device {
  final String userId;
  final String username;
  final IdentityMaterial material;
  final TokenPair tokens;
  final Database db;
  final CollabBundleRepository repo;
  final BundleKeySource keys;
  final OutboxRepository outbox;
  final SingleConsumerPipeline pipeline;
  final RelayWssClient wss;
  final Dio dio;

  _Device({
    required this.userId,
    required this.username,
    required this.material,
    required this.tokens,
    required this.db,
    required this.repo,
    required this.keys,
    required this.outbox,
    required this.pipeline,
    required this.wss,
    required this.dio,
  });

  Future<void> record(CollabInboundEvent e) => repo.recordEvent(
        bundleId: e.bundleId,
        seqId: e.seqId,
        actionType: e.action.name,
        senderId: e.senderHint ?? '',
        cipher: e.cipher ?? const [],
        keyVer: e.keyVer ?? 1,
        clientMsgId: e.clientMsgId ?? '${e.bundleId}:${e.seqId}',
        topologyRef: e.topologyRef,
        serverAtMs: e.serverAtMs,
      );

  /// 被邀端信封入户：拉本成员信封 → 自身 SPK 打开 → K_bundle 落 vault。
  Future<int> admitKeyEnvelopes(String bundleId) async {
    final envelopes =
        await fetchBundleKeyEnvelopes(dio, _base, tokens.access, bundleId);
    var admitted = 0;
    for (final v in envelopes) {
      final key = await openBundleKeyEnvelope(
        envelope: {
          'wrapped_key': v.wrappedKey,
          'key_ver': v.keyVer,
          'recipient_user_id': userId,
        },
        mySignedPreKey: material.signedPreKey,
      );
      await keys.persist(bundleId, v.keyVer, key);
      admitted++;
    }
    return admitted;
  }

  /// Wake Pull 补拉（分页直到无更多）。
  Future<int> pullAll(String bundleId) async {
    var since = await repo.maxSeqId(bundleId);
    var applied = 0;
    while (true) {
      final page =
          await fetchSyncPage(dio, _base, tokens.access, bundleId, since);
      for (final e in page.events) {
        pipeline.pushSyncBatch([
          CollabInboundEvent(
            bundleId: bundleId,
            seqId: e.seqId,
            action: _actionOf(e.actionType),
            senderHint: e.senderId.isEmpty ? null : e.senderId,
            cipher: e.cipher,
            keyVer: 1,
            source: 'http',
          ),
        ]);
        applied++;
      }
      if (!page.hasMore || page.events.isEmpty) break;
      since = page.events.last.seqId;
    }
    return applied;
  }

  /// 出站弹出一轮（Drainer 内联版）。
  Future<int> drain(HttpOutboxTransport transport) async {
    final pending = await outbox.pending();
    var sent = 0;
    for (final entry in pending) {
      final outcome = await transport.send(entry);
      if (outcome is SendAccepted || outcome is SendProposed) {
        await outbox.remove(entry.id);
        sent++;
      } else {
        throw StateError('出站被拒：$outcome（entry=${entry.actionType}）');
      }
    }
    return sent;
  }

  Future<void> dispose() async {
    wss.dispose();
    await db.close();
  }
}

/// 设备装配（真 relay 契约焊接：WSS 零知识唤醒 → Wake Pull 补密文）。
Future<_Device> _mountDevice({
  required IdentityMaterial material,
  required TokenPair tokens,
}) async {
  final tmp = await Directory.systemTemp.createTemp('yunnan_dev');
  final db = await openDatabase(
    p.join(tmp.path, 't.db'),
    version: 7,
    onCreate: (d, _) => Db.createAll(d),
  );
  final vault = KeyStore(MemorySecretStore());
  await vault.ensureReady();
  final repo = CollabBundleRepository(db);
  final keys = BundleKeySource(db, vault);
  final pipeline = SingleConsumerPipeline();

  final device = _Device(
    userId: (await fetchMe(_dio(), _base, tokens.access)).userId,
    username: '',
    material: material,
    tokens: tokens,
    db: db,
    repo: repo,
    keys: keys,
    outbox: OutboxRepository(db),
    pipeline: pipeline,
    wss: RelayWssClient(
      wsUri: Uri.parse(_wsUri),
      getToken: () async => tokens.access,
      keepaliveInterval: const Duration(hours: 1),
    ),
    dio: _dio(),
  );

  final seen = <String>{};
  device.wss.frames.listen((f) async {
    final env = f.schedule;
    if (env.cipher.isEmpty) {
      try {
        await device.pullAll(env.bundleId);
      } catch (_) {} // 补拉失败下次唤醒再试
      return;
    }
    device.pipeline
        .pushWss(CollabInboundEvent.fromServerFrame(f, source: 'wss'));
  });
  device.pipeline.applied.listen((e) async {
    await device.record(e);
    if (seen.add(e.bundleId)) {
      try {
        await device.admitKeyEnvelopes(e.bundleId);
      } catch (_) {
        // 入户失败按半盲处理（引擎同纪律）
      }
    }
  });
  device.wss.connect();
  return device;
}

/// 从已落库事件回放 FSM 状态——走生产入口 [replayBundle]：初态 proposalType 由
/// PROPOSE 载荷自判（手写回放缺这步会把 Consensus 误派发到 directive 分支）。
Future<CollabBundleState> _replayFsm(_Device device, String bundleId) async {
  final events = await device.repo.loadEvents(bundleId);
  return replayBundle(events, device.keys.call, bundleId: bundleId);
}

/// 载荷级步骤时间槽观察（生产 replayBundle 顺手抓取，与 B 页时间槽数据源同口径）。
Future<Map<String, StepSlot>> _observedSlots(
    _Device device, String bundleId) async {
  final slots = <String, StepSlot>{};
  await replayBundle(await device.repo.loadEvents(bundleId), device.keys.call,
      bundleId: bundleId, observedSlots: slots);
  return slots;
}

Future<({String userId, IdentityMaterial material, TokenPair tokens})>
    _register(String username, Dio dio) async {
  final material = await generateIdentity(opkCount: 12);
  final password = 'password-$username';
  final ch = await fetchChallenge(dio, _base);
  final reg = await registerUser(
      dio,
      _base,
      buildRegisterBody(
        username: username,
        password: password,
        identityKeyPub: await material.identityPublicKey,
        signedPrekeyPub: await material.signedPreKeyPublic,
        signedPrekeySig: await material.signedPreKeySignature(),
        nonce: ch.nonce,
        powSolution: solvePoW(ch.powChallenge, ch.powDifficulty),
        oneTimePreKeys: material.oneTimePreKeys,
      ));
  final tokens = await login(dio, _base, username, password);
  return (userId: reg.userId, material: material, tokens: tokens);
}

/// 注册 429 退避：relay 限流是「固定窗口按尝试计数」（对齐分钟边界 unix/60，
/// 429 也 INCR 白烧配额）——退避须对齐到下一窗口边界加抖动，20s 固定步进可能
/// 三连落同窗全废（全量并行跑时多文件同时注册挤 3/min 配额即复现）。
Future<({String userId, IdentityMaterial material, TokenPair tokens})>
    _registerRetry(String username, Dio dio) async {
  var attempt = 0;
  while (true) {
    try {
      return await _register(username, dio);
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

String _uniq(String prefix) =>
    '$prefix${DateTime.now().millisecondsSinceEpoch.toRadixString(36)}';

void main() {
  sqfliteFfiInit();
  databaseFactory = databaseFactoryFfi;

  late bool up;
  late Dio dio;
  late ({
    String userId,
    IdentityMaterial material,
    TokenPair tokens,
  }) initiator, m1, m2, m3;

  setUpAll(() async {
    up = await _relayUp();
    if (!up) return;
    dio = _dio();
    // 4 用户注册（限流 3/min，退避跨窗口）+ 发起人↔各同伴好友边。
    initiator = await _registerRetry(_uniq('yn_go'), dio);
    m1 = await _registerRetry(_uniq('yn_m1'), dio);
    m2 = await _registerRetry(_uniq('yn_m2'), dio);
    m3 = await _registerRetry(_uniq('yn_m3'), dio);
    for (final member in [m1, m2, m3]) {
      final inviteId = await submitFriendRequest(
          dio, _base, initiator.tokens.access,
          toUserId: member.userId);
      await acceptFriendInvite(
          dio, _base, member.tokens.access, inviteId, true);
    }
  });

  test('四人云南七日游：行程包 → 改期 → 选时段投票 → 忙闲探测 → 四端一致',
      () async {
    if (!up) return;

    // ---- ① 四台设备上电 ----
    final d0 = await _mountDevice(
        material: initiator.material, tokens: initiator.tokens);
    final d1 =
        await _mountDevice(material: m1.material, tokens: m1.tokens);
    final d2 =
        await _mountDevice(material: m2.material, tokens: m2.tokens);
    final d3 =
        await _mountDevice(material: m3.material, tokens: m3.tokens);
    addTearDown(() async {
      for (final d in [d0, d1, d2, d3]) {
        await d.dispose();
      }
    });
    await Future.delayed(const Duration(seconds: 3)); // WSS AUTH 窗口

    final members = [d1, d2, d3];

    // ---- ② 发起人建 Directive「云南七日游」：7 步，required 轮换 ----
    final day0 = DateTime(2026, 11, 2, 8).millisecondsSinceEpoch; // D1 08:00
    int day(int n) => day0 + n * 24 * 3600 * 1000;
    final steps = <ProposalStepDraft>[
      for (var i = 0; i < 7; i++)
        ProposalStepDraft(
          stepId: 'd${i + 1}',
          title: const [
            'D1 昆明集合·翠湖夜市',
            'D2 石林一日',
            'D3 大理·洱海环湖',
            'D4 苍山步道',
            'D5 丽江古城',
            'D6 玉龙雪山',
            'D7 返程散团',
          ][i],
          requiredUids: [
            [d1.userId],
            [d2.userId],
            [d3.userId],
            [d1.userId, d2.userId],
            [d2.userId],
            [d3.userId],
            [d1.userId],
          ][i],
          startsAtMs: day(i),
          endsAtMs: day(i) + 12 * 3600 * 1000,
        ),
    ];
    final tripDraft = BundleDraft(
      localId: 'trip-local',
      kind: ProposalKind.directive,
      inviteeUids: [d1.userId, d2.userId, d3.userId],
      steps: steps,
    );
    // 逐成员预取 SPK 公钥（信封必须按目标成员各封各的）
    final spkByUid = <String, List<int>>{
      for (final reg in [m1, m2, m3])
        reg.userId: (await fetchPeerKeys(dio, _base, d0.tokens.access, reg.userId))
            .signedPrekeyPub,
    };
    final tripService = BundleProposalService(
      keys: d0.keys,
      outbox: d0.outbox,
      memberSpk: (uid) async => spkByUid[uid]!,
      newBundleKey: () => List<int>.generate(32, (_) => 11),
    );
    await tripService.propose(meId: d0.userId, draft: tripDraft);

    final tripTransport = HttpOutboxTransport(
      dio: d0.dio,
      baseUrl: _base,
      accessToken: () async => d0.tokens.access,
      bundleKey: (b, v) async => (await d0.keys(b, v))!,
    );
    // 出站建包 + 认祖归宗
    final pending0 = await d0.outbox.pending();
    expect(pending0.length, 1);
    final outcome = await tripTransport.send(pending0.single);
    expect(outcome, isA<SendProposed>());
    final created = outcome as SendProposed;
    final tripId = created.bundleId;
    expect(created.seqId, 1);
    await d0.outbox.remove(pending0.single.id);
    await PendingProposalReconciler(
      bundles: d0.repo,
      keys: d0.keys,
      outbox: d0.outbox,
    )(
      pending0.single,
      CreatedBundle(
        entryId: pending0.single.id,
        localBundleId: pending0.single.bundleId,
        bundleId: tripId,
        seqId: created.seqId,
      ),
    );
    await d0.pullAll(tripId);

    // ---- ③ 各成员收到 PROPOSE → 信封入户 → FSM proposed（7 步在位）----
    for (final m in members) {
      await _waitUntil(
          () => m.repo.loadEvents(tripId).then((l) => l.isNotEmpty),
          timeout: const Duration(seconds: 20),
          reason: '成员应收到行程包 PROPOSE');
      if ((await m.repo.loadEvents(tripId)).isEmpty) {
        await m.pullAll(tripId);
      }
      await _waitUntil(() => m.keys(tripId, 1).then((k) => k != null),
          reason: '成员应信封入户拿到 K_bundle');
      final st = await _replayFsm(m, tripId);
      expect(st.status, BundleFsmStatus.proposed);
      expect(st.steps.keys.toSet(), {for (var i = 1; i <= 7; i++) 'd$i'});
    }

    // ---- ④ 成员接受各自 required 步骤 → 出站 → 补拉自身 ----
    final memberTransport = <_Device, HttpOutboxTransport>{
      for (final m in members)
        m: HttpOutboxTransport(
          dio: m.dio,
          baseUrl: _base,
          accessToken: () async => m.tokens.access,
          bundleKey: (b, v) async => (await m.keys(b, v))!,
        ),
    };
    // required 映射：d1→m1 d2→m2 d3→m3 d4→m1+m2 d5→m2 d6→m3 d7→m1
    final accepts = <_Device, List<String>>{
      d1: ['d1', 'd4', 'd7'],
      d2: ['d2', 'd4', 'd5'],
      d3: ['d3', 'd6'],
    };
    for (final m in members) {
      for (final stepId in accepts[m]!) {
        await m.outbox.enqueue(
          bundleId: tripId,
          actionType: 'ACCEPT',
          payload: {'step_id': stepId, 'decision': 'OK'},
        );
      }
      await m.drain(memberTransport[m]!);
      await m.pullAll(tripId);
    }

    // ---- ⑤ 发起端收齐 ACCEPT → 敲定 ----
    final expectedSeq = 1 + 8; // PROPOSE + 8 条 ACCEPT
    await _waitUntil(
        () => d0.repo.loadEvents(tripId).then((l) => l.length >= expectedSeq),
        timeout: const Duration(seconds: 20),
        reason: '发起端应收齐 8 条 ACCEPT');
    await d0.pullAll(tripId);
    final d0Trip = await _replayFsm(d0, tripId);
    expect(d0Trip.status, BundleFsmStatus.confirmed,
        reason: '全部 required 接受后行程应敲定，实际 ${d0Trip.status}');

    // ---- ⑥ 发起人独占 PATCH_STEP_TIME：D3 洱海环湖改到下午 ----
    final d3NewStart = day(2) + 4 * 3600 * 1000;
    final d3NewEnd = day(2) + 9 * 3600 * 1000;
    await d0.outbox.enqueue(
      bundleId: tripId,
      actionType: 'PATCH_STEP_TIME',
      payload: {
        'step_id': 'd3',
        'starts_at_ms': d3NewStart,
        'ends_at_ms': d3NewEnd,
      },
    );
    await d0.drain(tripTransport);
    await d0.pullAll(tripId);
    final patchedSeq = expectedSeq + 1;
    for (final m in members) {
      await _waitUntil(
          () => m.repo.loadEvents(tripId).then((l) => l.length >= patchedSeq),
          timeout: const Duration(seconds: 20),
          reason: '成员应收齐 PATCH_STEP_TIME');
      final st = await _replayFsm(m, tripId);
      expect(st.status, BundleFsmStatus.confirmed,
          reason: '敲定后改期 200 保态');
      final slots = await _observedSlots(m, tripId);
      expect(slots['d3']!.startsAtMs, d3NewStart,
          reason: '改期应在全端生效');
      expect(slots['d3']!.endsAtMs, d3NewEnd);
    }

    // ---- ⑦ Consensus「D5 出发时段」：3 候选 + quorum=3，混合投票 ----
    final slotDraft = BundleDraft(
      localId: 'slot-local',
      kind: ProposalKind.consensus,
      inviteeUids: [d1.userId, d2.userId, d3.userId],
      quorum: 3,
      steps: const [
        ProposalStepDraft(stepId: 'slot-0800'),
        ProposalStepDraft(stepId: 'slot-1000'),
        ProposalStepDraft(stepId: 'slot-1400'),
      ],
    );
    final slotService = BundleProposalService(
      keys: d0.keys,
      outbox: d0.outbox,
      memberSpk: (uid) async => spkByUid[uid]!,
      newBundleKey: () => List<int>.generate(32, (_) => 22),
    );
    await slotService.propose(meId: d0.userId, draft: slotDraft);
    final slotPending = await d0.outbox.pending();
    final slotOutcome = await tripTransport.send(slotPending.single);
    expect(slotOutcome, isA<SendProposed>());
    final slotCreated = slotOutcome as SendProposed;
    final slotId = slotCreated.bundleId;
    await d0.outbox.remove(slotPending.single.id);
    await PendingProposalReconciler(
      bundles: d0.repo,
      keys: d0.keys,
      outbox: d0.outbox,
    )(
      slotPending.single,
      CreatedBundle(
        entryId: slotPending.single.id,
        localBundleId: slotPending.single.bundleId,
        bundleId: slotId,
        seqId: slotCreated.seqId,
      ),
    );
    await d0.pullAll(slotId);

    // 成员信封入户 → 各投 slot-1000（1 张 PREFER + 2 张 OK，赞成票都计）
    for (final m in members) {
      await _waitUntil(() => m.repo.loadEvents(slotId).then((l) => l.isNotEmpty),
          timeout: const Duration(seconds: 20),
          reason: '成员应收到选时段包 PROPOSE');
      if ((await m.repo.loadEvents(slotId)).isEmpty) {
        await m.pullAll(slotId);
      }
      await _waitUntil(() => m.keys(slotId, 1).then((k) => k != null),
          reason: '成员应拿到选时段包 K_bundle');
    }
    final votes = <_Device, String>{
      d1: 'PREFER',
      d2: 'OK',
      d3: 'OK',
    };
    for (final m in members) {
      await m.outbox.enqueue(
        bundleId: slotId,
        actionType: 'VOTE',
        payload: {'slot_id': 'slot-1000', 'preference': votes[m]},
      );
      await m.drain(memberTransport[m]!);
      await m.pullAll(slotId);
    }

    // 全端：quorum=3 集齐 → winningSlot 固化 slot-1000，状态 confirmed
    final expectedSlotSeq = 1 + 3; // PROPOSE + 3 条 VOTE
    for (final d in [d0, ...members]) {
      await _waitUntil(
          () => d.repo.loadEvents(slotId).then((l) => l.length >= expectedSlotSeq),
          timeout: const Duration(seconds: 20),
          reason: '各端应收齐 3 条 VOTE');
      await d.pullAll(slotId);
      final st = await _replayFsm(d, slotId);
      expect(st.status, BundleFsmStatus.confirmed,
          reason: 'quorum=3 集齐后选时段包应敲定');
      expect(st.winningSlot, 'slot-1000');
      expect(st.candidateSlots, containsAll(['slot-0800', 'slot-1400']));
    }

    // ---- ⑧ FreeMask 忙闲探测：QUERY 下发 → 成员量化掩码 → 单向加密回执 ----
    // 探测 D5 白天 8 桶（4h）；每名成员忙闲不同，断言发起人解出的掩码与预期一致。
    final fmStart = day(4);
    final fmEnd = day(4) + 4 * 3600 * 1000; // 8 个 30min 桶
    // 发起人 SPK 公钥（成员经 prekey 端点取，单向加密的目标）
    final d0SpkPub =
        await fetchPeerKeys(dio, _base, d0.tokens.access, d0.userId);
    final d0Spk = SimplePublicKey(d0SpkPub.signedPrekeyPub,
        type: KeyPairType.x25519);
    // 发起人发 QUERY（K_bundle 封装，各成员解出目标窗口）
    await d0.outbox.enqueue(
      bundleId: tripId,
      actionType: 'FREEMASK_QUERY',
      payload: {'target_start_ms': fmStart, 'target_end_ms': fmEnd},
    );
    await d0.drain(tripTransport);
    await d0.pullAll(tripId);

    final expectedMask = <_Device, String>{};
    final busySpec = <_Device, List<(int, int)>>{
      d1: [(fmStart + 30 * 60 * 1000, fmStart + 60 * 60 * 1000)], // 桶 1 忙
      d2: [], // 全空
      d3: [(fmStart + 2 * 3600 * 1000, fmStart + 5 * 3600 * 1000)], // 桶 4..7 忙
    };
    for (final m in members) {
      // 等成员看到 QUERY（不参与 FSM，直接查事件流）
      await _waitUntil(
          () => m.repo.loadEvents(tripId).then((l) => l.any((e) =>
              e.actionType == 'FREEMASK_QUERY' && e.cipher.isNotEmpty)),
          timeout: const Duration(seconds: 20),
          reason: '成员应收到 FREEMASK_QUERY');
      final mask =
          quantizeFreeMask(targetStartMs: fmStart, targetEndMs: fmEnd, busy: busySpec[m]!);
      expectedMask[m] = mask;
      final sealedEnvelope = await sealFreeMaskForInitiator(
        signal: FreeMaskSignal(
            targetStartMs: fmStart, targetEndMs: fmEnd, mask: mask),
        senderEphemeral: await X25519().newKeyPair(),
        initiatorSpkPub: d0Spk,
      );
      await m.outbox.enqueue(
        bundleId: tripId,
        actionType: 'FREEMASK_RESPONSE',
        payload: {'envelope': sealedEnvelope},
      );
      await m.drain(memberTransport[m]!);
    }
    await d0.pullAll(tripId);

    // 发起人解回执：K_bundle 解 payload → openFreeMaskFromMember 解掩码
    final responses = (await d0.repo.loadEvents(tripId))
        .where((e) => e.actionType == 'FREEMASK_RESPONSE')
        .toList();
    expect(responses.length, 3, reason: '发起人应收齐 3 份忙闲回执');
    for (final e in responses) {
      final key = await d0.keys(tripId, e.keyVer);
      final payload = jsonDecode(utf8.decode(
          await openPayload(key!, SealedPayload.decode(utf8.decode(e.cipher)))))
          as Map<String, dynamic>;
      final signal = await openFreeMaskFromMember(
        sealed: payload['envelope'] as Map<String, Object?>,
        initiatorSpk: d0.material.signedPreKey,
      );
      expect(signal.targetStartMs, fmStart);
      // 与该成员预期掩码逐桶核对（按掩码内容匹配成员）
      final owner = expectedMask.entries
          .firstWhere((kv) => kv.value == signal.mask)
          .key;
      expect(signal.mask, expectedMask[owner]);
    }
    // 三份掩码应两两可区分（忙闲设计保证）
    expect(expectedMask.values.toSet().length, 3);

    // ---- ⑨ 四端终态一致 ----
    for (final bundle in [tripId, slotId]) {
      final seqSets = <Set<int>>[];
      final statuses = <BundleFsmStatus>[];
      for (final d in [d0, ...members]) {
        await d.pullAll(bundle);
        seqSets.add((await d.repo.loadEvents(bundle))
            .map((e) => e.seqId)
            .toSet());
        statuses.add((await _replayFsm(d, bundle)).status);
      }
      for (var i = 1; i < seqSets.length; i++) {
        expect(seqSets[i], seqSets.first,
            reason: 'bundle $bundle 各端事件集应一致');
        expect(statuses[i], statuses.first, reason: 'bundle $bundle 各端终态应一致');
      }
    }

    // ---- ⑩ 换机冷启动：全新库（m1 身份）全量回放两包，终态一致 ----
    final lateDb = await openDatabase(
      p.join((await Directory.systemTemp.createTemp('yn_late')).path, 't.db'),
      version: 7,
      onCreate: (d, _) => Db.createAll(d),
    );
    addTearDown(lateDb.close);
    final lateRepo = CollabBundleRepository(lateDb);
    final lateVault = KeyStore(MemorySecretStore());
    await lateVault.ensureReady();
    final lateKeys = BundleKeySource(lateDb, lateVault);
    for (final bundle in [tripId, slotId]) {
      final page =
          await fetchSyncPage(dio, _base, m1.tokens.access, bundle, 0);
      for (final e in page.events) {
        await lateRepo.recordEvent(
          bundleId: bundle,
          seqId: e.seqId,
          actionType: e.actionType,
          senderId: e.senderId,
          cipher: e.cipher,
          keyVer: 1,
          clientMsgId: 'late:${e.seqId}',
          serverAtMs: e.serverAtSec * 1000,
        );
      }
      final envelopes =
          await fetchBundleKeyEnvelopes(dio, _base, m1.tokens.access, bundle);
      for (final v in envelopes) {
        final key = await openBundleKeyEnvelope(
          envelope: {
            'wrapped_key': v.wrappedKey,
            'key_ver': v.keyVer,
            'recipient_user_id': m1.userId,
          },
          mySignedPreKey: m1.material.signedPreKey,
        );
        await lateKeys.persist(bundle, v.keyVer, key);
      }
    }
    final lateDevice = _Device(
      userId: m1.userId,
      username: '',
      material: m1.material,
      tokens: m1.tokens,
      db: lateDb,
      repo: lateRepo,
      keys: lateKeys,
      outbox: OutboxRepository(lateDb),
      pipeline: SingleConsumerPipeline(),
      wss: RelayWssClient(
          wsUri: Uri.parse(_wsUri), getToken: () async => m1.tokens.access),
      dio: dio,
    );
    expect(
        (await _replayFsm(lateDevice, tripId)).status,
        BundleFsmStatus.confirmed,
        reason: '换机冷启动行程包回放应 confirmed');
    final lateSlot = await _replayFsm(lateDevice, slotId);
    expect(lateSlot.status, BundleFsmStatus.confirmed);
    expect(lateSlot.winningSlot, 'slot-1000');
    expect((await lateRepo.loadEvents(tripId)).length,
        (await d0.repo.loadEvents(tripId)).length,
        reason: '换机端与在网端事件数应一致');
  });
}
