import 'dart:convert';
import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:sqflite_common_ffi/sqflite_ffi.dart';

import 'package:shiguang/collab/materialize.dart' show materializeBundle;
import 'package:shiguang/collab/sealing.dart' show sealPayload;
import 'package:shiguang/data/collab_repository.dart';
import 'package:shiguang/data/db.dart';

const _k = <int>[
  1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16,
  17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32,
];

void main() {
  sqfliteFfiInit();
  databaseFactory = databaseFactoryFfi;

  late Database db;
  late CollabBundleRepository repo;

  setUp(() async {
    db = await openDatabase(
      inMemoryDatabasePath,
      version: 7,
      onCreate: (d, _) => Db.createAll(d),
    );
    var clock = 0;
    repo = CollabBundleRepository(db, nowMs: () => clock++);
    await repo.ensureBundle(
      bundleId: 'b1',
      proposalType: 'DIRECTIVE',
      initiatorId: 'u1',
      participants: ['u1', 'u2', 'u3'],
      expiresAtMs: 9999999999999,
    );
  });

  tearDown(() async => await db.close());

  Future<void> emitCollabEvent(int seq, String action, Map<String, Object?> payload,
      {String sender = 'u1'}) async {
    final sealed =
        await sealPayload(Uint8List.fromList(_k), utf8.encode(jsonEncode(payload)), 1);
    await repo.recordEvent(
      bundleId: 'b1',
      seqId: seq,
      actionType: action,
      senderId: sender,
      cipher: utf8.encode(sealed.encode()),
      keyVer: 1,
      clientMsgId: 'm$seq',
      topologyRef: 't0',
      serverAtMs: 1700000000000 + seq,
    );
  }

  test('物化回填覆盖 ensureStep 的默认 PENDING（钉子根因修复）', () async {
    // ensureStep 先塞占位默认行（IGNOER 语义下这行之后再也刷新不了）
    await repo.ensureStep(
      bundleId: 'b1',
      stepId: 's1',
      requiredUids: ['u2', 'u3'],
      threshold: 2,
    );
    expect((await repo.listSteps('b1')).single.status, 'PENDING');

    await emitCollabEvent(1, 'PROPOSE', {
      'proposal_type': 'DIRECTIVE',
      'skeleton': [
        {
          'step_id': 's1',
          'required_uids': ['u2', 'u3'],
          'threshold': 2,
          'is_optional': false,
          'starts_at_ms': 1700003600000,
          'ends_at_ms': 1700007200000,
        }
      ],
      'participants': ['u1', 'u2', 'u3'],
    });
    await emitCollabEvent(2, 'ACCEPT', {'step_id': 's1'}, sender: 'u2');
    await emitCollabEvent(3, 'ACCEPT', {'step_id': 's1'}, sender: 'u3');

    final n = await materializeBundle(repo, 'b1', bundleKey: (_, _) async => _k);
    expect(n, 1);

    final step = (await repo.listSteps('b1')).single;
    // 两人同意且达门限 2 → READY（不再是默认 PENDING）
    expect(step.status, 'READY');
    expect(step.threshold, 2);
    // 逐成员表决落表：u2/u3 记 OK
    expect(step.votes, {'u2': 'OK', 'u3': 'OK'});
    // 时间槽物化成功（B 页叠块数据源到位）
    expect(step.startsAtMs, 1700003600000);
    expect(step.endsAtMs, 1700007200000);
  });

  test('拒绝一步：TIME_CLASH 状态与 NO 票落表', () async {
    await emitCollabEvent(1, 'PROPOSE', {
      'proposal_type': 'DIRECTIVE',
      'skeleton': [
        {
          'step_id': 's1',
          'required_uids': ['u2', 'u3'],
          'threshold': 2,
          'is_optional': false,
        }
      ],
      'participants': ['u1', 'u2', 'u3'],
    });
    await emitCollabEvent(2, 'ACCEPT', {'step_id': 's1'}, sender: 'u2');
    await emitCollabEvent(3, 'REJECT', {
      'step_id': 's1',
      'reason': 'TIME_CONFLICT',
    }, sender: 'u3');

    await materializeBundle(repo, 'b1', bundleKey: (_, _) async => _k);

    final step = (await repo.listSteps('b1')).single;
    expect(step.status, 'TIME_CLASH');
    expect(step.votes, {'u2': 'OK', 'u3': 'NO'});
    // 未抓取到时段 → 两列留空（读侧据此降级）
    expect(step.startsAtMs, isNull);
    expect(step.endsAtMs, isNull);
  });

  test('缺密钥：半盲跳过后不写行、不抹既有', () async {
    await repo.ensureStep(
        bundleId: 'b1', stepId: 's-old', requiredUids: ['u2'], threshold: 1);
    await emitCollabEvent(1, 'PROPOSE', {
      'proposal_type': 'DIRECTIVE',
      'skeleton': [
        {
          'step_id': 's1',
          'required_uids': ['u2'],
          'threshold': 1,
          'is_optional': false,
        }
      ],
      'participants': ['u1', 'u2'],
    });

    final n = await materializeBundle(repo, 'b1', bundleKey: (_, _) async => null);
    expect(n, 0);
    final steps = await repo.listSteps('b1');
    expect(steps.single.id, 's-old'); // 既有行未被抹
  });

  test('重复回填幂等（replace 不累积）', () async {
    await emitCollabEvent(1, 'PROPOSE', {
      'proposal_type': 'DIRECTIVE',
      'skeleton': [
        {
          'step_id': 's1',
          'required_uids': ['u2'],
          'threshold': 1,
          'is_optional': false,
        }
      ],
      'participants': ['u1', 'u2'],
    });
    await materializeBundle(repo, 'b1', bundleKey: (_, _) async => _k);
    await materializeBundle(repo, 'b1', bundleKey: (_, _) async => _k);
    await materializeBundle(repo, 'b1', bundleKey: (_, _) async => _k);
    expect((await repo.listSteps('b1')).length, 1);
  });
}
