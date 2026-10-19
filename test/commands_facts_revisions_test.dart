import 'package:flutter_test/flutter_test.dart';
import 'package:shiguang/action/command_handler.dart';
import 'package:shiguang/action/commands.dart';
import 'package:shiguang/data/db.dart';
import 'package:shiguang/data/repository.dart';
import 'package:shiguang/models/artifact.dart';
import 'package:shiguang/models/plan.dart';
import 'package:shiguang/models/schedule_block.dart';
import 'package:sqflite_common_ffi/sqflite_ffi.dart';

/// 事实凭证命令层回改契约（fact-user-relay-draft.md §9.5，2026-10-08 拍板）：
/// ① raw→structured **出生确认严进**（category/source_kind 必填，防漏填）；
/// ③ `delete_plan` **解挂三分支**（block_id 随块生命周期，凭证比块长寿）。
/// 夹具模式照抄 commands_facts_test。
void main() {
  setUpAll(() {
    sqfliteFfiInit();
    databaseFactory = databaseFactoryFfi;
    Db.overridePath(inMemoryDatabasePath);
  });

  late Repository repo;
  late CommandHandler handler;

  setUp(() async {
    repo = Repository();
    handler = CommandHandler(repo);
    final db = await Db.instance();
    await db.delete('artifacts');
    await db.delete('backgrounds');
    await db.delete('schedule_blocks');
    await db.delete('fixed_slots');
    await db.delete('plans');
    await db.delete('app_settings');
  });

  Matcher throwsCode(String code) =>
      throwsA(isA<ActionException>().having((e) => e.code, 'code', code));

  Future<Plan> plan(String title) => repo.addPlan(Plan(title: title));

  Future<Artifact> rawFact(String planId) => repo.addArtifact(Artifact(
        category: Artifact.categoryVerbal,
        sourceKind: Artifact.sourceVerbal,
        state: Artifact.stateRaw,
        origin: Artifact.originShared,
        title: '【12306】赵志恒先生，您已购10月20日D8724次…',
        payload: {'raw_text': '【12306】……'},
        planId: planId,
      ));

  group('回改①出生确认严进（§9.5-7）', () {
    test('raw→structured 缺 category 被拒（invalid_request + hint）', () async {
      final p = await plan('云南七天');
      final a = await rawFact(p.id!);
      await expectLater(
        handler.execute(
          UpsertFactsCommand(id: a.id, state: Artifact.stateStructured,
              sourceKind: Artifact.sourceBooking),
          actor: CommandActor.ai,
        ),
        throwsCode('invalid_request'),
        reason: '缺失不再静默沿用 verbal 占位（否则 booking 凭证永久挂口头标签）',
      );
      final back = (await repo.artifactById(a.id!))!;
      expect(back.state, Artifact.stateRaw, reason: '被拒则不落库');
      expect(back.category, Artifact.categoryVerbal);
    });

    test('raw→structured 缺 source_kind 同样被拒', () async {
      final p = await plan('云南七天');
      final a = await rawFact(p.id!);
      await expectLater(
        handler.execute(
          UpsertFactsCommand(id: a.id, state: Artifact.stateStructured,
              category: Artifact.categoryTransit),
          actor: CommandActor.ai,
        ),
        throwsCode('invalid_request'),
      );
    });

    test('二值齐备 → 定终值；structured 后二值恒不可变', () async {
      final p = await plan('云南七天');
      final a = await rawFact(p.id!);
      final r = await handler.execute(
        UpsertFactsCommand(
          id: a.id,
          state: Artifact.stateStructured,
          category: Artifact.categoryTransit,
          sourceKind: Artifact.sourceBooking,
          badge: '05车12F',
        ),
        actor: CommandActor.ai,
      );
      expect(r.snapshot!['category'], Artifact.categoryTransit);
      expect(r.snapshot!['source_kind'], Artifact.sourceBooking);
      expect(r.snapshot!['state'], Artifact.stateStructured);

      // 二次编辑再传类别：忽略（出生不可变）
      await handler.execute(
        UpsertFactsCommand(
          id: a.id,
          category: Artifact.categoryHotel,
          sourceKind: Artifact.sourceAnnouncement,
          title: '大理→丽江 动车 D8724',
        ),
        actor: CommandActor.ai,
      );
      final back = (await repo.artifactById(a.id!))!;
      expect(back.category, Artifact.categoryTransit, reason: 'structured 后恒不可变');
      expect(back.sourceKind, Artifact.sourceBooking);
      expect(back.title, '大理→丽江 动车 D8724', reason: '非出生字段照常可改');
    });

    test('非出生编辑（structured→structured）不受必填约束', () async {
      final p = await plan('云南七天');
      final a = await rawFact(p.id!);
      await handler.execute(
        UpsertFactsCommand(
          id: a.id,
          state: Artifact.stateStructured,
          category: Artifact.categoryTransit,
          sourceKind: Artifact.sourceBooking,
        ),
        actor: CommandActor.ai,
      );
      final r = await handler.execute(
        UpsertFactsCommand(id: a.id, title: '改短标题'),
        actor: CommandActor.ai,
      );
      expect(r.snapshot!['title'], '改短标题');
    });
  });

  group('回改③delete_plan 解挂（反转关联 §9.5-6/§5.5）', () {
    test('块保留（属其他计划）→ plan_id 清空、块侧 artifact_id 不动（🎫 不丢）',
        () async {
      final p = await plan('云南七天');
      final other = await plan('其它行程');
      final blk = await repo.addBlock(ScheduleBlock(
        date: '2026-10-20',
        startMin: 540,
        endMin: 680,
        label: '大理→丽江 动车',
        source: 'human',
        planId: other.id,
      ));
      final a = await repo.addArtifact(Artifact(
        category: Artifact.categoryTransit,
        sourceKind: Artifact.sourceBooking,
        state: Artifact.stateStructured,
        origin: Artifact.originShared,
        title: '大理→丽江 动车 D8724',
        badge: '05车12F',
        payload: {'time_anchors': []},
        planId: p.id,
      ), bindBlockId: blk.id);
      // 计划名下无块（块挂在别的计划），删除可成功
      final r = await handler.execute(DeletePlanCommand(p.id!), actor: CommandActor.human);
      expect(r.note, contains('已移入未归属'));
      final back = (await repo.artifactById(a.id!))!;
      expect(back.planId, isNull, reason: 'detach 至未归属池');
      expect(back.payload.containsKey('plan_id'), isFalse);
      final rebound = await repo.artifactForBlock(blk.id!);
      expect(rebound?.id, a.id, reason: '块侧绑定不随计划删除牵连（反转关联）');
    });

    test('块已日切物理删除 → 块行随删绑定自然消失，凭证不受牵连留未归属池',
        () async {
      final p = await plan('云南七天');
      final blk = await repo.addBlock(ScheduleBlock(
        date: '2026-10-20',
        startMin: 540,
        endMin: 680,
        label: '大理→丽江 动车',
        source: 'human',
        planId: p.id,
      ));
      final a = await repo.addArtifact(Artifact(
        category: Artifact.categoryTransit,
        sourceKind: Artifact.sourceBooking,
        state: Artifact.stateStructured,
        origin: Artifact.originShared,
        title: '大理→丽江 动车 D8724',
        payload: {'time_anchors': []},
        planId: p.id,
      ), bindBlockId: blk.id);
      await repo.deleteBlock(blk.id!); // 日切物理删除（块删仅行消失，不牵连凭证）
      await handler.execute(DeletePlanCommand(p.id!), actor: CommandActor.human);
      final back = (await repo.artifactById(a.id!))!;
      expect(back.planId, isNull, reason: '凭证比块长寿，仍留未归属池');
      expect(await repo.artifactForBlock(blk.id!), isNull,
          reason: '块行已删，反转 JOIN 无行（读侧静默降级）');
    });

    test('未挂块凭证 → detach 未归属池，无块侧绑定', () async {
      final p = await plan('云南七天');
      final a = await rawFact(p.id!);
      await handler.execute(DeletePlanCommand(p.id!), actor: CommandActor.human);
      final back = (await repo.artifactById(a.id!))!;
      expect(back.planId, isNull);
      expect(back.payload.containsKey('plan_id'), isFalse);
    });
  });
}
