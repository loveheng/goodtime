import 'package:flutter_test/flutter_test.dart';
import 'package:sqflite_common_ffi/sqflite_ffi.dart';

import 'package:shiguang/data/collab_repository.dart';
import 'package:shiguang/data/db.dart';

/// CollabBundleRepository 单测：WakePuller 游标接 collab 表的水位与事件落库。
void main() {
  sqfliteFfiInit();
  databaseFactory = databaseFactoryFfi;
  Db.overridePath(inMemoryDatabasePath);

  late Database db;
  late CollabBundleRepository repo;

  setUp(() async {
    db = await openDatabase(
      inMemoryDatabasePath,
      version: 5,
      onCreate: (d, _) => Db.createAll(d),
    );
    var clock = 0;
    repo = CollabBundleRepository(db, nowMs: () => clock++);
  });

  tearDown(() async => await db.close());

  test('maxSeqId：无包头无事件时为 0', () async {
    expect(await repo.maxSeqId('b-x'), 0);
  });

  test('ensureBundle：INSERT OR IGNORE，缺省 PROPOSED / max_seq_id=0', () async {
    await repo.ensureBundle(
      bundleId: 'b1',
      proposalType: 'DIRECTIVE',
      initiatorId: 'u1',
      participants: ['u1', 'u2'],
      expiresAtMs: 9,
    );
    final h = await repo.bundleHeader('b1');
    expect(h, isNotNull);
    expect(h!.status, 'PROPOSED');
    expect(h.maxSeqId, 0);
    expect(h.participants, ['u1', 'u2']);
    // 重复 ensure 不覆盖（IGNORE）
    await repo.ensureBundle(
      bundleId: 'b1',
      proposalType: 'CONSENSUS',
      initiatorId: 'other',
      participants: ['other'],
      expiresAtMs: 0,
    );
    final h2 = await repo.bundleHeader('b1');
    expect(h2!.initiatorId, 'u1'); // 未被覆盖
  });

  test('recordEvent：落库 + 水位 GREATEST 单调推进', () async {
    await repo.recordEvent(
      bundleId: 'b1',
      seqId: 3,
      actionType: 'PROPOSE',
      senderId: 'u1',
      cipher: [1, 2, 3],
      keyVer: 1,
      clientMsgId: 'm3',
    );
    await repo.recordEvent(
      bundleId: 'b1',
      seqId: 7,
      actionType: 'ACCEPT',
      senderId: 'u2',
      cipher: [4, 5],
      keyVer: 1,
      clientMsgId: 'm7',
    );
    expect(await repo.maxSeqId('b1'), 7);
    // 乱序低 seq 不回退水位
    await repo.recordEvent(
      bundleId: 'b1',
      seqId: 5,
      actionType: 'ACCEPT',
      senderId: 'u2',
      cipher: [9],
      keyVer: 1,
      clientMsgId: 'm5',
    );
    expect(await repo.maxSeqId('b1'), 7);
  });

  test('recordEvent：重复 (bundle,seq) 被 UNIQUE 忽略，不炸库', () async {
    await repo.recordEvent(
      bundleId: 'b1',
      seqId: 2,
      actionType: 'PROPOSE',
      senderId: 'u1',
      cipher: [1],
      keyVer: 1,
      clientMsgId: 'm2',
    );
    await repo.recordEvent(
      bundleId: 'b1',
      seqId: 2, // 重复 seq
      actionType: 'ACCEPT',
      senderId: 'u2',
      cipher: [2],
      keyVer: 1,
      clientMsgId: 'm2-dup',
    );
    final events = await repo.loadEvents('b1');
    expect(events.length, 1); // 仍只一条
    expect(events.first.actionType, 'PROPOSE'); // 首条保留
  });

  test('recordEvent：先落事件后 ensure 也安全（包头行自动建）', () async {
    await repo.recordEvent(
      bundleId: 'b2',
      seqId: 4,
      actionType: 'PROPOSE',
      senderId: 'u9',
      cipher: [7],
      keyVer: 1,
      clientMsgId: 'm4',
    );
    expect(await repo.maxSeqId('b2'), 4); // 无包头行时回退到 events MAX
    final h = await repo.bundleHeader('b2');
    expect(h, isNotNull);
    expect(h!.maxSeqId, 4);
    expect(h.initiatorId, 'u9');
  });

  test('loadEvents：按 seq 升序回放源', () async {
    for (final s in [3, 1, 2]) {
      await repo.recordEvent(
        bundleId: 'b1',
        seqId: s,
        actionType: 'ACCEPT',
        senderId: 'u1',
        cipher: [s],
        keyVer: 1,
        clientMsgId: 'm$s',
      );
    }
    final events = await repo.loadEvents('b1');
    expect(events.map((e) => e.seqId).toList(), [1, 2, 3]);
  });

  test('ensureStep / listSteps：步骤落库与拓扑序', () async {
    await repo.ensureBundle(
      bundleId: 'b1',
      proposalType: 'CONSENSUS',
      initiatorId: 'u1',
      participants: ['u1', 'u2', 'u3'],
      expiresAtMs: 9,
    );
    await repo.ensureStep(
      bundleId: 'b1',
      stepId: 's2',
      requiredUids: ['u1', 'u2'],
      threshold: 2,
      sort: 2,
      votes: {'u2': 'OK'},
    );
    await repo.ensureStep(
      bundleId: 'b1',
      stepId: 's1',
      requiredUids: ['u1', 'u2', 'u3'],
      sort: 1,
    );
    final steps = await repo.listSteps('b1');
    expect(steps.map((s) => s.id).toList(), ['s1', 's2']); // 按 sort 升序
    expect(steps.last.threshold, 2);
    expect(steps.last.votes, {'u2': 'OK'});
    expect(steps.first.requiredUids, ['u1', 'u2', 'u3']);
  });

  test('listBundles：active 倒序，status 可收窄', () async {
    await repo.ensureBundle(
      bundleId: 'b-old',
      proposalType: 'DIRECTIVE',
      initiatorId: 'u1',
      participants: ['u1', 'u2'],
      expiresAtMs: 9,
    );
    await repo.ensureBundle(
      bundleId: 'b-new',
      proposalType: 'CONSENSUS',
      initiatorId: 'u1',
      participants: ['u1', 'u2', 'u3'],
      expiresAtMs: 9,
    );
    await repo.updateStatus('b-new', 'CONFIRMED');
    final all = await repo.listBundles();
    expect(all.map((b) => b.id).toList(), ['b-new', 'b-old']); // 倒序
    final confirmed = await repo.listBundles(status: 'CONFIRMED');
    expect(confirmed.map((b) => b.id).toList(), ['b-new']);
    final archived = await repo.listBundles(lifecycle: 'archived');
    expect(archived, isEmpty);
  });
}
