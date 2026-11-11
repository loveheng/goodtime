@Timeout(Duration(minutes: 3)) // 注册 429 退避重试预算 90s，需超 flutter_test 默认 30s
library;

import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:path/path.dart' as p;
import 'package:sqflite_common_ffi/sqflite_ffi.dart';

import 'package:shiguang/collab/crypto/bundle_key_source.dart';
import 'package:shiguang/collab/crypto/key_store.dart';
import 'package:shiguang/collab/crypto/secret_store.dart';
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
import 'package:shiguang/collab/replay.dart' show signalFromPayload;
import 'package:shiguang/collab/sealing.dart';
import 'package:shiguang/data/collab_repository.dart';
import 'package:shiguang/data/db.dart';

/// 真 relay **完整业务场景闭环**测试（Phase 1 服务端真进程，非 relay_mock）。
///
/// 场景：两台「设备」（各自独立 ffi SQLite 库 + 身份 + 登录态 + WSS）走完
/// 注册 → 建联 → 发起 bundle（K_bundle + 成员信封 + 拓扑）→ 被邀端信封入户 →
/// 接受 → 敲定 → 双端 FSM 一致 + 换机冷启动回放一致 的全生命周期。
///
/// 目标服务：REST 基址 `http://127.0.0.1:8080`（客户端自拼 `/v1` 前缀）
/// + WSS `ws://127.0.0.1:8080/ws`（shiguang-relay relayd 真进程）。
/// 服务不在场时整组 skip，不阻塞 CI。
///
/// 约束：单 IP 注册限流 ≤3/min——全场只在 setUpAll 注册 2 个用户；整场重跑
/// 需间隔 1 分钟以上（限流窗口），撞 429 即等窗口重试。

const _base = 'http://127.0.0.1:8080';
const _wsUri = 'ws://127.0.0.1:8080/ws';

/// 真 relay 的 POST 幂等中间件要求 `X-Client-Msg-Id` + `X-Client-Ts`
/// （relay_mock 未复刻该中间件）——统一经 [_dio] 构造。
Dio _dio() {
  final dio = Dio();
  var n = 0;
  dio.interceptors.add(InterceptorsWrapper(
    onRequest: (options, handler) {
      options.headers['X-Client-Msg-Id'] =
          'scn-${DateTime.now().microsecondsSinceEpoch}-${n++}';
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
      throw TimeoutException('条件未在 ${timeout.inSeconds}s 内满足${reason == null ? '' : '：$reason'}');
    }
    await Future.delayed(const Duration(milliseconds: 50));
  }
}

/// 一台「设备」：独立 ffi SQLite 库 + 身份 + 登录态 + WSS + 单消费管道。
class _Device {
  final String userId;
  final String username;
  final IdentityMaterial material;
  final TokenPair tokens;
  final Database db;
  final CollabBundleRepository repo;
  final KeyStore vault;
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
    required this.vault,
    required this.keys,
    required this.outbox,
    required this.pipeline,
    required this.wss,
    required this.dio,
  });

  /// 落库一条事件（引擎 _onApplied 的测试内联版）。
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
    final envelopes = await fetchBundleKeyEnvelopes(
        dio, _base, tokens.access, bundleId);
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

  /// Wake Pull 补拉（全量分页直到无更多）。
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

  /// 出站弹出一轮（Drainer 的测试内联版）：逐条 pending → 真传输 → 确认清箱。
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

/// 设备装配：ffi 内存外临时库 + 全套本地协同栈 + 真 WSS/管道焊接。
Future<_Device> _mountDevice({
  required String username,
  required IdentityMaterial material,
  required TokenPair tokens,
}) async {
  final tmp = await Directory.systemTemp.createTemp('live_scenario');
  final db = await openDatabase(
    p.join(tmp.path, 't.db'),
    version: 7,
    onCreate: (d, _) => Db.createAll(d),
  );
  final vault = KeyStore(MemorySecretStore());
  await vault.ensureReady();
  final repo = CollabBundleRepository(db);
  final keys = BundleKeySource(db, vault);
  final outbox = OutboxRepository(db);
  final pipeline = SingleConsumerPipeline();

  final device = _Device(
    userId: (await fetchMe(_dio(), _base, tokens.access)).userId,
    username: username,
    material: material,
    tokens: tokens,
    db: db,
    repo: repo,
    vault: vault,
    keys: keys,
    outbox: outbox,
    pipeline: pipeline,
    wss: RelayWssClient(
      wsUri: Uri.parse(_wsUri),
      getToken: () async => tokens.access,
      keepaliveInterval: const Duration(hours: 1),
    ),
    dio: _dio(),
  );

  // 焊接回路（真 relay 契约）：WSS 广播是**零知识唤醒**（不带 cipher，ref
  // hub.go Publish 只发 bundle/seq/action/sender），密文一律经 /sync Wake Pull
  // 补齐 → 落库；某包首事件 → 被邀端信封入户（半盲降级不拖垮落库）。
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
        // 入户失败按半盲处理，骨架照常推进（引擎同纪律）
      }
    }
  });
  device.wss.connect();
  return device;
}

/// 从已落库事件回放 FSM 状态（密文用 K_bundle 解开 → 信号 → reduce）。
Future<CollabBundleState> _replayFsm(_Device device, String bundleId) async {
  final events = await device.repo.loadEvents(bundleId);
  var state = CollabBundleState.empty(bundleId);
  for (final e in events) {
    if (e.cipher.isEmpty) continue; // WSS 唤醒帧不含密文，落库经 Wake Pull 补齐
    final key = await device.keys(bundleId, e.keyVer);
    if (key == null) continue; // 半盲：解不开的事件跳过（骨架照常推进）
    final payload = jsonDecode(utf8.decode(
        await openPayload(key, SealedPayload.decode(utf8.decode(e.cipher)))))
        as Map<String, dynamic>;
    final signal = signalFromPayload(
      _actionOf(e.actionType),
      payload,
      senderId: e.senderId,
      seqId: e.seqId,
    );
    state = reduceEvent(state, signal);
  }
  return state;
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

/// 单 IP 注册限流 ≤3/min：撞 429 退避等窗口（全量并行跑时多文件同时注册会超配额，
/// 等待而非红）。限流按尝试计数且对齐分钟边界（unix/60），退避对齐到下一窗口
/// 边界加抖动，20s 固定步进可能三连落同窗全废。
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
  }) aliceReg, bobReg;

  setUpAll(() async {
    up = await _relayUp();
    if (!up) return;
    dio = _dio();
    // 单 IP 注册限流 ≤3/min：全场只注册 2 个用户。
    final a = await _registerRetry(_uniq('scn_a'), dio);
    final b = await _registerRetry(_uniq('scn_b'), dio);
    aliceReg = a;
    bobReg = b;
    // 建联：搜索路径申请 → 审批（bundle 成员 ACL 的前置）
    final inviteId = await submitFriendRequest(
        dio, _base, aliceReg.tokens.access,
        toUserId: bobReg.userId);
    await acceptFriendInvite(dio, _base, bobReg.tokens.access, inviteId, true);
  });

  test('完整场景：发起 → 信封入户 → 接受 → 敲定 → 双端一致 + 换机冷启动回放',
      () async {
    if (!up) return;

    // ---- 两台设备上电 ----
    final alice = await _mountDevice(
        username: 'alice', material: aliceReg.material, tokens: aliceReg.tokens);
    final bob = await _mountDevice(
        username: 'bob', material: bobReg.material, tokens: bobReg.tokens);
    addTearDown(alice.dispose);
    addTearDown(bob.dispose);
    await Future.delayed(const Duration(seconds: 2)); // WSS AUTH 握手窗口

    // ---- ① alice 发起 directive 包（1 步，bob 必到）----
    final nowMs = DateTime.now().millisecondsSinceEpoch;
    final draft = BundleDraft(
      localId: 'local-1',
      kind: ProposalKind.directive,
      inviteeUids: [bob.userId],
      steps: [
        ProposalStepDraft(
          stepId: 's1',
          title: '周五聚餐',
          requiredUids: [bob.userId],
          startsAtMs: nowMs + 3600 * 1000,
          endsAtMs: nowMs + 2 * 3600 * 1000,
        ),
      ],
    );
    final spk = await fetchPeerKeys(dio, _base, alice.tokens.access, bob.userId);
    final service = BundleProposalService(
      keys: alice.keys,
      outbox: alice.outbox,
      memberSpk: (uid) async => spk.signedPrekeyPub,
      newBundleKey: () => List<int>.generate(32, (_) => 7),
    );
    await service.propose(meId: alice.userId, draft: draft);

    // ---- ② 发起端真出站：服务端建包发号 → 认祖归宗 ----
    final transport = HttpOutboxTransport(
      dio: alice.dio,
      baseUrl: _base,
      accessToken: () async => alice.tokens.access,
      bundleKey: (b, v) async => (await alice.keys(b, v))!,
    );
    final acks = await _drainCreated(alice, transport);
    expect(acks.length, 1, reason: 'PROPOSE 应走建包分支拿到服务端发号');
    final bundleId = acks.single.bundleId;
    expect(acks.single.seqId, 1);
    await PendingProposalReconciler(
      bundles: alice.repo,
      keys: alice.keys,
      outbox: alice.outbox,
    )(acks.single.entry, acks.single.ack);

    // 发起端补拉自己的 PROPOSE（中继不回发者 → 本地缺 seq1，Wake Pull 补齐）
    await alice.pullAll(bundleId);
    await _waitUntil(
        () => alice.repo
            .loadEvents(bundleId)
            .then((l) => l.isNotEmpty));

    // ---- ③ bob 收到 PROPOSE（WSS 实时或补拉）→ 信封入户 → FSM proposed ----
    await _waitUntil(() => bob.repo.loadEvents(bundleId).then((l) => l.isNotEmpty),
        timeout: const Duration(seconds: 20));
    if ((await bob.repo.loadEvents(bundleId)).isEmpty) {
      await bob.pullAll(bundleId);
    }
    await _waitUntil(
        () => bob.keys(bundleId, 1).then((k) => k != null),
        reason: 'bob 应经信封入户拿到 K_bundle');
    final bobState = await _replayFsm(bob, bundleId);
    expect(bobState.status, BundleFsmStatus.proposed,
        reason: 'bob 回放 PROPOSE 后应处于 proposed');
    expect(bobState.steps.keys, contains('s1'));

    // ---- ④ bob 接受 s1 → 真出站 → 补拉自己的 ACCEPT ----
    await bob.outbox.enqueue(
      bundleId: bundleId,
      actionType: 'ACCEPT',
      payload: {'step_id': 's1', 'decision': 'OK'},
    );
    final bobTransport = HttpOutboxTransport(
      dio: bob.dio,
      baseUrl: _base,
      accessToken: () async => bob.tokens.access,
      bundleKey: (b, v) async => (await bob.keys(b, v))!,
    );
    await bob.drain(bobTransport);
    await bob.pullAll(bundleId);
    await _waitUntil(
        () => bob.repo.loadEvents(bundleId).then((l) => l.length >= 2));
    final bobFinal = await _replayFsm(bob, bundleId);

    // ---- ⑤ alice 收到 bob 的 ACCEPT（WSS 广播）→ FSM 敲定 ----
    await _waitUntil(
        () => alice.repo.loadEvents(bundleId).then((l) => l.length >= 2),
        timeout: const Duration(seconds: 20));
    final aliceFinal = await _replayFsm(alice, bundleId);

    // ---- ⑥ 双端一致：状态/步骤/事件集 ----
    expect(aliceFinal.status, BundleFsmStatus.confirmed,
        reason: 'directive 必到成员接受后发起端应敲定，实际 ${aliceFinal.status}');
    expect(bobFinal.status, BundleFsmStatus.confirmed,
        reason: '双端状态应一致，实际 ${bobFinal.status}');
    expect(aliceFinal.steps['s1']!.status, bobFinal.steps['s1']!.status);
    final aliceSeqs =
        (await alice.repo.loadEvents(bundleId)).map((e) => e.seqId).toSet();
    final bobSeqs =
        (await bob.repo.loadEvents(bundleId)).map((e) => e.seqId).toSet();
    expect(aliceSeqs, bobSeqs);
    expect(aliceSeqs, {1, 2});

    // ---- ⑦ 换机冷启动：全新库经 /sync 全量回放，终态一致 ----
    final lateDb = await openDatabase(
      p.join((await Directory.systemTemp.createTemp('latecomer')).path, 't.db'),
      version: 7,
      onCreate: (d, _) => Db.createAll(d),
    );
    addTearDown(lateDb.close);
    final lateRepo = CollabBundleRepository(lateDb);
    final lateVault = KeyStore(MemorySecretStore());
    await lateVault.ensureReady();
    final lateKeys = BundleKeySource(lateDb, lateVault);
    final page = await fetchSyncPage(dio, _base, bob.tokens.access, bundleId, 0);
    expect(page.events.map((e) => e.seqId).toSet(), {1, 2});
    for (final e in page.events) {
      await lateRepo.recordEvent(
        bundleId: bundleId,
        seqId: e.seqId,
        actionType: e.actionType,
        senderId: e.senderId,
        cipher: e.cipher,
        keyVer: 1,
        clientMsgId: 'late:${e.seqId}',
        serverAtMs: e.serverAtSec * 1000,
      );
    }
    // 换机端从 vault 信封重取 K_bundle（bob 同一身份，服务端信封仍在）
    final lateDevice = _Device(
      userId: bob.userId,
      username: bob.username,
      material: bob.material,
      tokens: bob.tokens,
      db: lateDb,
      repo: lateRepo,
      vault: lateVault,
      keys: lateKeys,
      outbox: OutboxRepository(lateDb),
      pipeline: SingleConsumerPipeline(),
      wss: RelayWssClient(
          wsUri: Uri.parse(_wsUri),
          getToken: () async => bob.tokens.access),
      dio: dio,
    );
    await lateDevice.admitKeyEnvelopes(bundleId);
    final lateState = await _replayFsm(lateDevice, bundleId);
    expect(lateState.status, BundleFsmStatus.confirmed,
        reason: '换机冷启动全量回放应与在网端终态一致，实际 ${lateState.status}');
  });
}

/// ActionType 枚举名 → 枚举值（生成物 valueOf 收 int，名字映射自建）。
final Map<String, ActionType> _actionByName = {
  for (final v in ActionType.values) v.name: v,
};
ActionType _actionOf(String name) => _actionByName[name]!;

/// drain 里挑出建包回执（SendProposed）。
class _CreatedAck {
  final OutboxEntry entry;
  final CreatedBundle ack;
  _CreatedAck(this.entry, this.ack);
  String get bundleId => ack.bundleId;
  int get seqId => ack.seqId;
}

Future<List<_CreatedAck>> _drainCreated(
    _Device device, HttpOutboxTransport transport) async {
  final pending = await device.outbox.pending();
  final acks = <_CreatedAck>[];
  for (final entry in pending) {
    final outcome = await transport.send(entry);
    if (outcome is SendProposed) {
      await device.outbox.remove(entry.id);
      acks.add(_CreatedAck(
        entry,
        CreatedBundle(
          entryId: entry.id,
          localBundleId: entry.bundleId,
          bundleId: outcome.bundleId,
          seqId: outcome.seqId,
        ),
      ));
    } else if (outcome is SendAccepted) {
      await device.outbox.remove(entry.id);
    } else {
      throw StateError('出站未受理：$outcome');
    }
  }
  return acks;
}
