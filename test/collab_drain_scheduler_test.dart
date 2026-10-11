import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:sqflite_common_ffi/sqflite_ffi.dart';

import 'package:shiguang/collab/connectivity.dart';
import 'package:shiguang/collab/outbox.dart';
import 'package:shiguang/data/db.dart';

/// 脚本化连通性监听：手动驱动 online/offline 跃迁。
class FakeConnectivityWatcher implements ConnectivityWatcher {
  final StreamController<bool> _c = StreamController<bool>.broadcast();
  bool _online = false;

  @override
  Stream<bool> get onConnectivityChanged => _c.stream;

  @override
  Future<bool> get isOnline => Future.value(_online);

  void emit(bool online) {
    _online = online;
    _c.add(online);
  }

  void dispose() => _c.close();
}

/// 假传输：所有条目回 Accepted，记录发送次数。
class FakeTransport implements OutboxTransport {
  int sends = 0;
  @override
  Future<SendOutcome> send(OutboxEntry entry) async {
    sends++;
    return const SendAccepted();
  }
}

/// 入站事件轮询等待。
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

/// CollabDrainScheduler 单测：Drainer 接网络监听常驻。
void main() {
  sqfliteFfiInit();
  databaseFactory = databaseFactoryFfi;
  Db.overridePath(inMemoryDatabasePath);

  late Database db;
  late OutboxRepository outbox;
  late FakeTransport transport;
  late FakeConnectivityWatcher watcher;
  late CollabDrainScheduler scheduler;

  setUp(() async {
    db = await openDatabase(
      inMemoryDatabasePath,
      version: 5,
      onCreate: (d, _) => Db.createAll(d),
    );
    outbox = OutboxRepository(db, nowMs: () => 1);
    transport = FakeTransport();
    watcher = FakeConnectivityWatcher();
    scheduler = CollabDrainScheduler(OutboxDrainer(outbox, transport), watcher);
  });

  tearDown(() async {
    await scheduler.dispose();
    watcher.dispose();
    await db.close();
  });

  test('启动时常离线：不主动 drain', () async {
    await scheduler.start();
    expect(transport.sends, 0);
  });

  test('断→通跃迁：自动 drain 清空发件箱', () async {
    await outbox.enqueue(
        bundleId: 'b1', actionType: 'ACCEPT', payload: {'x': 1});
    expect((await outbox.pending()).length, 1);

    await scheduler.start();
    watcher.emit(true); // 网络恢复
    await _waitUntil(() async => transport.sends >= 1);
    await _waitUntil(() async => (await outbox.pending()).isEmpty);
    expect(transport.sends, 1);
  });

  test('trigger 手动触发：reports 流出战报', () async {
    await outbox.enqueue(
        bundleId: 'b1', actionType: 'ACCEPT', payload: {'x': 1});
    final reports = <DrainReport>[];
    scheduler.reports.listen(reports.add);

    final report = await scheduler.trigger();
    expect(report.sent, 1);
    expect(report.stoppedByNetwork, isFalse);
    await _waitUntil(() async => reports.isNotEmpty);
    expect(reports.last.sent, 1);
  });

  test('常驻：连续两次跃迁各自触发一轮 drain', () async {
    await scheduler.start();
    await outbox.enqueue(
        bundleId: 'b1', actionType: 'ACCEPT', payload: {'a': 1});
    watcher.emit(true);
    await _waitUntil(() async => transport.sends >= 1);
    final first = transport.sends;

    await outbox.enqueue(
        bundleId: 'b1', actionType: 'ACCEPT', payload: {'b': 2});
    watcher.emit(false);
    watcher.emit(true); // 再次恢复
    await _waitUntil(() async => transport.sends >= first + 1);
    expect(transport.sends, first + 1);
  });
}
