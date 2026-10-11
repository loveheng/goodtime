import 'dart:io';

import 'package:path/path.dart' as p;
import 'package:sqflite_common_ffi/sqflite_ffi.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:shiguang/data/db.dart';
import 'package:shiguang/collab/outbox.dart';

/// Phase 3.3 本地 Outbox 验收（collab-implementation §3.3）：
/// 弱网单测——**杀进程不丢操作**（file DB close→reopen→pending 原样），
/// 外加 §11.1 三段式语义逐条：入队即落盘 / 按序弹出 / 确认即删 /
/// 409 清箱引导 / 网络失败保序停止。

/// 脚本化传输：按序吐出预设结局，并记录被发送的条目顺序。
class ScriptedTransport implements OutboxTransport {
  final List<SendOutcome> script;
  final List<OutboxEntry> seen = [];
  int cursor = 0;
  ScriptedTransport(this.script);

  @override
  Future<SendOutcome> send(OutboxEntry e) async {
    seen.add(e);
    if (cursor >= script.length) {
      throw StateError('script exhausted at ${e.id}');
    }
    return script[cursor++];
  }
}

/// 裸异常传输：模拟传输层抛非 SendOutcome 异常（坏网络）。
class ThrowingTransport implements OutboxTransport {
  @override
  Future<SendOutcome> send(OutboxEntry e) async => throw Exception('net down');
}

void main() {
  sqfliteFfiInit();
  databaseFactory = databaseFactoryFfi;

  late Directory tmp;
  late String dbPath;

  setUp(() async {
    tmp = await Directory.systemTemp.createTemp('outbox_test');
    dbPath = p.join(tmp.path, 't.db');
  });

  tearDown(() async {
    await tmp.delete(recursive: true);
  });

  Future<Database> openV5(String path) => openDatabase(
        path,
        version: 5,
        onCreate: (db, version) => Db.createAll(db),
      );

  test('入队即落盘：PENDING_SEND 先于一切反馈，(created_at, id) 全序', () async {
    final db = await openV5(dbPath);
    var seq = 0;
    final repo = OutboxRepository(db,
        nowMs: () => 1000, newId: () => 'ob-${seq++}'); // 冻结时钟→靠 id tie-break

    // 故意乱序语义入队：id 依次 0、1、2
    final first = await repo.enqueue(
        bundleId: 'b1',
        actionType: 'ACCEPT',
        payload: {'step_id': 's3', 'pref': 'ok'});
    final second = await repo.enqueue(
        bundleId: 'b1',
        actionType: 'REJECT',
        payload: {'step_id': 's1', 'reason': 'time_conflict'},
        keyVer: 2);
    await repo.enqueue(
        bundleId: 'b1', actionType: 'ACCEPT', payload: {'step_id': 's2'});

    expect(first.id, 'ob-0');
    expect(second.id, 'ob-1');
    expect(second.keyVer, 2);

    final pending = await repo.pending();
    expect(pending.map((e) => e.id).toList(), ['ob-0', 'ob-1', 'ob-2']);
    // payload JSON 往返无损（明文本地域）
    expect(pending[0].payload, {'step_id': 's3', 'pref': 'ok'});
    expect(pending[1].actionType, 'REJECT');
    expect(pending[1].attempts, 0);
    await db.close();
  });

  test('验收线：杀进程不丢操作——close→reopen 后 pending 原样', () async {
    final db = await openV5(dbPath);
    var seq = 100;
    final repo =
        OutboxRepository(db, nowMs: () => 1000, newId: () => 'ob-${seq++}');
    await repo.enqueue(
        bundleId: 'b1', actionType: 'ACCEPT', payload: {'step_id': 's1'});
    await repo.enqueue(
        bundleId: 'b1',
        actionType: 'REJECT',
        payload: {'step_id': 's2', 'reason': 'role_unwilling'});
    await repo.enqueue(
        bundleId: 'b1',
        actionType: 'VOTE',
        payload: {'slot_id': 'fri18', 'pref': 'prefer'});
    await db.close(); // 杀进程

    final db2 = await openV5(dbPath); // 冷启动重开
    var seq2 = 200;
    final repo2 =
        OutboxRepository(db2, nowMs: () => 1000, newId: () => 'ob-${seq2++}');
    final pending = await repo2.pending();
    expect(pending, hasLength(3));
    expect(pending[0].actionType, 'ACCEPT');
    expect(pending[1].payload, {'step_id': 's2', 'reason': 'role_unwilling'});
    expect(pending[2].payload, {'slot_id': 'fri18', 'pref': 'prefer'});
    expect(pending.every((e) => e.attempts == 0), isTrue);
    await db2.close();
  });

  test('drain 全成功：按 (created_at, id) 全序发送，确认即删', () async {
    final db = await openV5(dbPath);
    var seq = 0;
    final repo = OutboxRepository(db,
        nowMs: () => 1000, newId: () => 'ob-${seq++}');
    for (final s in ['s1', 's2', 's3']) {
      await repo.enqueue(
          bundleId: 'b1', actionType: 'ACCEPT', payload: {'step_id': s});
    }
    final t = ScriptedTransport(
        [const SendAccepted(), const SendAccepted(), const SendAccepted()]);
    final report = await OutboxDrainer(repo, t).drain();

    expect(report.sent, 3);
    expect(report.conflicts, isEmpty);
    expect(report.stoppedByNetwork, isFalse);
    // 发送序 = 弹出全序
    expect(t.seen.map((e) => e.payload['step_id']), ['s1', 's2', 's3']);
    expect(await repo.pending(), isEmpty); // 确认即删
    await db.close();
  });

  test('弱网：Offline 非终态——保留 + attempts+1，后续条目保序不弹，恢复后补发',
      () async {
    final db = await openV5(dbPath);
    var seq = 0;
    final repo = OutboxRepository(db,
        nowMs: () => 1000, newId: () => 'ob-${seq++}');
    for (final s in ['s1', 's2', 's3']) {
      await repo.enqueue(
          bundleId: 'b1', actionType: 'ACCEPT', payload: {'step_id': s});
    }
    // 第 2 条摸网失败（非终态）→ 第 3 条因果依赖前序，绝不乱序发出
    final t1 = ScriptedTransport(
        [const SendAccepted(), SendOffline(Exception('timeout'))]);
    final r1 = await OutboxDrainer(repo, t1).drain();

    expect(r1.sent, 1);
    expect(r1.stoppedByNetwork, isTrue);
    expect(t1.seen, hasLength(2)); // 第 3 条未尝试
    final pending = await repo.pending();
    expect(pending.map((e) => e.payload['step_id']), ['s2', 's3']);
    expect(pending[0].attempts, 1); // 离线重试计数
    expect(pending[1].attempts, 0); // 原样未动

    // 弱网恢复：重 drain 补发剩余
    final t2 = ScriptedTransport([const SendAccepted(), const SendAccepted()]);
    final r2 = await OutboxDrainer(repo, t2).drain();
    expect(r2.sent, 2);
    expect(r2.stoppedByNetwork, isFalse);
    expect(await repo.pending(), isEmpty);
    await db.close();
  });

  test('409 清箱引导：确定性打回终态清条 + conflicts 上抛，后续条目继续弹', () async {
    final db = await openV5(dbPath);
    var seq = 0;
    final repo = OutboxRepository(db,
        nowMs: () => 1000, newId: () => 'ob-${seq++}');
    await repo.enqueue(
        bundleId: 'b1', actionType: 'ACCEPT', payload: {'step_id': 's1'});
    await repo.enqueue(
        bundleId: 'b1', actionType: 'PATCH_TIME', payload: {'step_id': 's2'});

    final t = ScriptedTransport(
        [const SendRejected('STALE_REVISION'), const SendAccepted()]);
    final report = await OutboxDrainer(repo, t).drain();

    expect(report.sent, 1); // 第 2 条不因第 1 条被打回而阻塞
    expect(report.stoppedByNetwork, isFalse);
    expect(report.conflicts, hasLength(1));
    expect(report.conflicts.first.errorCode, 'STALE_REVISION');
    expect(report.conflicts.first.actionType, 'ACCEPT');
    expect(await repo.pending(), isEmpty); // 两条均已出箱
    await db.close();
  });

  test('传输层裸异常归一化为 Offline：坏网络绝不炸弹出循环', () async {
    final db = await openV5(dbPath);
    final repo = OutboxRepository(db, nowMs: () => 1000);
    await repo.enqueue(
        bundleId: 'b1', actionType: 'ACCEPT', payload: {'step_id': 's1'});

    final report = await OutboxDrainer(repo, ThrowingTransport()).drain();
    expect(report.stoppedByNetwork, isTrue);
    final pending = await repo.pending();
    expect(pending, hasLength(1));
    expect(pending.single.attempts, 1);
    await db.close();
  });
}
