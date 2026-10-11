import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:sqflite_common_ffi/sqflite_ffi.dart';

import 'package:dio/dio.dart';
import 'package:shiguang/collab/connectivity.dart';
import 'package:shiguang/collab/engine.dart';
import 'package:shiguang/collab/outbox.dart';
import 'package:shiguang/collab/pipeline.dart';
import 'package:shiguang/collab/proto/relay_protocol.pb.dart' show ActionType;
import 'package:shiguang/collab/relay/sync_api.dart' show SyncPage, SyncEvent;

import 'package:shiguang/collab/relay_config.dart' show RelayConfig;
import 'package:shiguang/collab/wake_puller.dart' show WakePuller, WakePayload;
import 'package:shiguang/data/collab_repository.dart';
import 'package:shiguang/data/db.dart';

/// 脚本化连通性监听（复用模式）。
class FakeConnectivityWatcher implements ConnectivityWatcher {
  final StreamController<bool> _c = StreamController<bool>.broadcast();
  bool _online = false;
  @override
  Stream<bool> get onConnectivityChanged => _c.stream;
  @override
  Future<bool> get isOnline => Future.value(_online);
  void emit(bool v) => _c.add(_online = v);
  void dispose() => _c.close();
}

class FakeTransport implements OutboxTransport {
  int sends = 0;
  @override
  Future<SendOutcome> send(OutboxEntry entry) async {
    sends++;
    return const SendAccepted();
  }
}

/// 脚本化 /sync 拉页：服务器有 seq 1..10；返回 since+1 直至到顶。
SyncPage _scriptedPull(String bundleId, int since) {
  if (since >= 10) {
    return SyncPage(
        bundleId: bundleId, events: const [], latestSeq: 10, hasMore: false);
  }
  final seq = since + 1;
  return SyncPage(
    bundleId: bundleId,
    events: [
      SyncEvent(
        seqId: seq,
        actionType: 'ACCEPT',
        senderId: 'u1',
        cipher: [seq],
        topologyRef: '',
        serverAtSec: 1,
      ),
    ],
    latestSeq: 10,
    hasMore: true,
  );
}

Future<void> _waitUntil(Future<bool> Function() cond,
    {Duration timeout = const Duration(seconds: 5)}) async {
  final end = DateTime.now().add(timeout);
  while (!await cond()) {
    if (DateTime.now().isAfter(end)) {
      throw TimeoutException('条件未在 ${timeout.inSeconds}s 内满足');
    }
    await Future.delayed(const Duration(milliseconds: 5));
  }
}

/// CollabEngine 编排收口集成单测：三件套收口 + RELAY_BASE_URL 串联。
void main() {
  sqfliteFfiInit();
  databaseFactory = databaseFactoryFfi;
  Db.overridePath(inMemoryDatabasePath);

  late Database db;
  late CollabBundleRepository repo;
  late SingleConsumerPipeline pipeline;
  late List<int> pullSinceCalls;
  late WakePuller wakePuller;
  late FakeConnectivityWatcher watcher;
  late FakeTransport transport;
  late CollabEngine engine;

  setUp(() async {
    db = await openDatabase(
      inMemoryDatabasePath,
      version: 5,
      onCreate: (d, _) => Db.createAll(d),
    );
    repo = CollabBundleRepository(db, nowMs: () => 1);
    pipeline = SingleConsumerPipeline();
    pullSinceCalls = [];
    wakePuller = WakePuller(
      pipeline: pipeline,
      pull: (b, since) {
        pullSinceCalls.add(since);
        return Future.value(_scriptedPull(b, since));
      },
      sinceSeqOf: repo.maxSeqId, // 游标接 collab 表
    );
    watcher = FakeConnectivityWatcher();
    transport = FakeTransport();
    final drainer = OutboxDrainer(
        OutboxRepository(db, nowMs: () => 1), transport);
    engine = CollabEngine(
      pipeline: pipeline,
      repo: repo,
      wakePuller: wakePuller,
      drainScheduler: CollabDrainScheduler(drainer, watcher),
    );
  });

  tearDown(() async {
    await engine.dispose();
    watcher.dispose();
    await db.close();
  });

  test('收口①：pipeline.applied → 落库推进 max_seq_id（WakePuller 游标接 collab 表）',
      () async {
    engine.start();
    // 模拟实时流收到 seq 1..5
    for (var i = 1; i <= 5; i++) {
      pipeline.pushWss(CollabInboundEvent(
        bundleId: 'b1',
        seqId: i,
        action: ActionType.ACCEPT,
        senderHint: 'u1',
        cipher: [i],
        clientMsgId: 'm$i',
      ));
    }
    await _waitUntil(() async => (await repo.maxSeqId('b1')) >= 5);
    expect(await repo.maxSeqId('b1'), 5);

    // 落库事件可被回放源读取
    final events = await repo.loadEvents('b1');
    expect(events.map((e) => e.seqId).toList(), [1, 2, 3, 4, 5]);
  });

  test('收口①续：Wake Pull 从本地水位续拉，不重复拖全量', () async {
    engine.start();
    for (var i = 1; i <= 5; i++) {
      pipeline.pushWss(CollabInboundEvent(
        bundleId: 'b1',
        seqId: i,
        action: ActionType.ACCEPT,
        senderHint: 'u1',
        cipher: [i],
        clientMsgId: 'm$i',
      ));
    }
    await _waitUntil(() async => (await repo.maxSeqId('b1')) >= 5);

    pullSinceCalls.clear();
    // 无 hint → 用 sinceSeqOf（本地水位 5）续拉到 10
    final reached = await engine.pullBundle('b1');
    await _waitUntil(() async => pullSinceCalls.isNotEmpty);
    expect(pullSinceCalls.first, 5); // 从本地水位续拉
    expect(reached, 10);
  });

  test('收口②：常驻发件箱——离线条目经 drainNow 清空', () async {
    engine.start();
    await OutboxRepository(db, nowMs: () => 1).enqueue(
        bundleId: 'b1', actionType: 'ACCEPT', payload: {'x': 1});
    final report = await engine.drainNow();
    expect(report.sent, 1);
    expect(transport.sends, 1);
  });

  test('收口③：wireCollabSystem 以 RELAY_BASE_URL 焊接三路传输（override 避网）',
      () async {
    const config = RelayConfig(baseUrl: 'https://relay.test:8443');
    Future<String> token() async => 'tok';
    final e = wireCollabSystem(
      config: config,
      db: db,
      dio: _noopDio(),
      tokenProvider: token,
      bundleKey: (_, _) async => List.filled(32, 1),
      connectivity: FakeConnectivityWatcher(),
      pullOverride: (b, since) => Future.value(_scriptedPull(b, since)),
      wssOverride: null, // 不接真 WSS
      transportOverride: FakeTransport(),
    );
    e.start();
    // 推事件 → 落库（证明引擎回路焊接成功，config 已贯穿构造）
    // 注意：wireCollabSystem 内部自建 pipeline，必须推到 e.pipeline 而非测试变量
    e.pipeline.pushWss(CollabInboundEvent(
      bundleId: 'b2',
      seqId: 1,
      action: ActionType.PROPOSE,
      senderHint: 'u1',
      cipher: [1],
      clientMsgId: 'm1',
    ));
    await _waitUntil(() async => (await repo.maxSeqId('b2')) >= 1);
    expect(await repo.maxSeqId('b2'), 1);
    await e.dispose();
  });

  test('onWake：静默唤醒载荷驱动 Wake Pull', () async {
    engine.start();
    final reached = await engine.onWake(const WakePayload(
      pushType: 'schedule',
      bundleId: 'b1',
      latestSeqId: 10,
    ));
    await _waitUntil(() async => pullSinceCalls.isNotEmpty);
    // 无本地水位（b1 未落过）→ 从 0 起拉到 10
    expect(pullSinceCalls.first, 0);
    expect(reached, 10);
  });
}

/// 占位 Dio（仅满足签名；测试已全部 override 传输/WSS，不会真正发请求）。
Dio _noopDio() => Dio(BaseOptions());
