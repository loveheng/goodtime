import 'package:flutter_test/flutter_test.dart';
import 'package:shiguang/data/db.dart';
import 'package:shiguang/data/repository.dart';
import 'package:shiguang/models/artifact.dart';
import 'package:shiguang/models/plan.dart';
import 'package:sqflite_common_ffi/sqflite_ffi.dart';

/// artifacts 数据层契约单测（fact-user-relay-draft.md §1.1；夹具照抄 repository_test）。
void main() {
  setUpAll(() {
    sqfliteFfiInit();
    databaseFactory = databaseFactoryFfi;
    Db.overridePath(inMemoryDatabasePath);
  });

  late Repository repo;
  setUp(() async {
    repo = Repository();
    final db = await Db.instance();
    await db.delete('artifacts');
    await db.delete('backgrounds');
    await db.delete('schedule_blocks');
    await db.delete('fixed_slots');
    await db.delete('plans');
  });

  Artifact ticket({String? planId, String state = Artifact.stateRaw}) => Artifact(
        category: Artifact.categoryTransit,
        sourceKind: Artifact.sourceBooking,
        state: state,
        origin: Artifact.originShared,
        title: '大理→丽江 动车 D8724',
        badge: '05车12F',
        payload: {
          'time_anchors': [
            {'role': '发车', 'kind': 'moment', 'date': '2026-10-20', 'min': 540},
          ],
          'raw_text': '【12306】……',
        },
        planId: planId,
      );

  group('建行与投影', () {
    test('入库生成 uuid、version 从 0 起、attachments 恒空数组', () async {
      final p = await repo.addPlan(Plan(title: '云南七天'));
      final a = await repo.addArtifact(ticket(planId: p.id));
      expect(a.id, matches(RegExp(r'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$')));
      expect(a.version, 0);
      expect(a.attachments, isEmpty, reason: '预留列 MVP 恒空（§1.2）');
      final back = await repo.artifactById(a.id!);
      expect(back!.title, '大理→丽江 动车 D8724');
      expect(back.payload['raw_text'], '【12306】……');
      expect(back.payload['v'], 1, reason: '首字段固定 v:1');
      expect(back.planId, p.id);
    });

    test('payload 读侧防御：畸形 JSON 丢弃回最小契约，不挡读路径', () async {
      final a = await repo.addArtifact(ticket());
      final db = await Db.instance();
      await db.rawUpdate('UPDATE artifacts SET payload = ? WHERE id = ?', ['not-json', a.id]);
      final back = await repo.artifactById(a.id!);
      expect(back!.payload, {'v': 1});
      expect(back.title, '大理→丽江 动车 D8724', reason: '元数据列不受 payload 畸形牵连');
    });
  });

  group('patch 与 CAS', () {
    test('提炼回填：state/payload/badge 字段级 patch + version CAS', () async {
      final p = await repo.addPlan(Plan(title: '云南七天'));
      final a = await repo.addArtifact(ticket(planId: p.id));
      final newPayload = Artifact.payloadWithRefs(a.payload, planId: p.id);
      expect(
        await repo.patchArtifact(
          a.id!,
          {'state': Artifact.stateStructured, 'badge': '05车12F', 'payload': Artifact.encodePayload(newPayload)},
          expectedVersion: 0,
        ),
        isTrue,
      );
      final back = await repo.artifactById(a.id!);
      expect(back!.state, Artifact.stateStructured);
      expect(back.version, 1);
      expect(
        await repo.patchArtifact(back.id!, {'title': '踩踏写入'}, expectedVersion: 0),
        isFalse,
        reason: '旧版本号 CAS 拒绝',
      );
    });

    test('改挂回未归属：plan_id 置 null 与 payload 同步（双写铁律的仓库面）', () async {
      final p = await repo.addPlan(Plan(title: '云南七天'));
      final a = await repo.addArtifact(ticket(planId: p.id));
      final payload = Artifact.payloadWithRefs(a.payload, planId: null);
      await repo.patchArtifact(a.id!, {'plan_id': null, 'payload': Artifact.encodePayload(payload)});
      final back = await repo.artifactById(a.id!);
      expect(back!.planId, isNull);
      expect(back.payload.containsKey('plan_id'), isFalse, reason: 'payload 投影同步移除');
    });

    test('patch 未知列直接抛（防呆下沉）', () async {
      final a = await repo.addArtifact(ticket());
      expect(() => repo.patchArtifact(a.id!, {'category': 'hotel'}), throwsArgumentError,
          reason: 'category 出生不可变，不在白名单');
    });
  });

  group('查询口径', () {
    test('artifactsForPlans 单条 SQL 聚合多计划、排除 voided、created_at ASC', () async {
      final root = await repo.addPlan(Plan(title: '云南七天'));
      final leaf = await repo.addPlan(Plan(title: 'c5 玉龙雪山一日', parentId: root.id));
      final a1 = await repo.addArtifact(ticket(planId: root.id));
      final a2 = await repo.addArtifact(ticket(planId: leaf.id));
      await repo.addArtifact(ticket(planId: leaf.id, state: Artifact.stateVoided));
      final got = await repo.artifactsForPlans([root.id!, leaf.id!]);
      expect(got.map((e) => e.id), [a1.id, a2.id], reason: 'voided 不进凭证区卡面（读侧白名单）');
      expect(await repo.artifactsForPlans(const []), isEmpty);
    });

    test('未归属池只出 plan_id 为空的行，新者在前', () async {
      final p = await repo.addPlan(Plan(title: '云南七天'));
      final orphan = await repo.addArtifact(ticket());
      await repo.addArtifact(ticket(planId: p.id));
      final got = await repo.artifactsUnattributed();
      expect(got.map((e) => e.id), [orphan.id]);
    });

    test('countArtifacts 白名单计数（list_plans「N 条原文待提炼」口径）', () async {
      await repo.addArtifact(ticket());
      await repo.addArtifact(ticket(state: Artifact.stateStructured));
      await repo.addArtifact(ticket(state: Artifact.stateVoided));
      expect(await repo.countArtifacts(state: Artifact.stateRaw), 1);
      expect(await repo.countArtifacts(), 3);
    });

    test('exportAll 带 artifacts 存储面（§11 数据出口全量）', () async {
      await repo.addArtifact(ticket());
      final dump = await repo.exportAll();
      expect((dump['artifacts'] as List).length, 1);
    });
  });

  group('删除', () {
    test('物理删除；不存在抛 StateError', () async {
      final a = await repo.addArtifact(ticket());
      await repo.deleteArtifact(a.id!);
      expect(await repo.artifactById(a.id!), isNull);
      expect(() => repo.deleteArtifact('nope'), throwsStateError);
    });
  });
}
