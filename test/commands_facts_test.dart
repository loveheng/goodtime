import 'package:flutter_test/flutter_test.dart';
import 'package:shiguang/action/command_handler.dart';
import 'package:shiguang/action/commands.dart';
import 'package:shiguang/data/db.dart';
import 'package:shiguang/data/repository.dart';
import 'package:shiguang/models/background.dart';
import 'package:shiguang/models/plan.dart';
import 'package:shiguang/models/schedule_block.dart';
import 'package:sqflite_common_ffi/sqflite_ffi.dart';

/// 事实凭证命令层契约单测（fact-user-relay-draft.md §1.1/§1.4/§8-3；
/// 夹具模式照抄 commands_test）。
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

  /// 升格关联的 pinned 块（乘车块）
  Future<ScheduleBlock> pinnedRideBlock() => repo.addBlock(ScheduleBlock(
        date: '2026-10-20',
        startMin: 540,
        endMin: 680,
        label: '大理→丽江 动车',
        source: 'ai',
        pinned: true,
      ));

  group('建档', () {
    test('分享原文入册：raw 一字不解析 + verbal 占位 + origin=shared + 归属计划', () async {
      final p = await plan('云南七天');
      final r = await handler.execute(
        UpsertFactsCommand(
          category: 'verbal',
          sourceKind: 'verbal',
          title: '【12306】赵志恒先生，您已购10月20日D8724次…',
          origin: 'shared',
          rawText: '【12306】……',
          planId: p.id,
        ),
        actor: CommandActor.human,
      );
      final snap = r.snapshot!;
      expect(snap['state'], 'raw');
      expect(snap['category'], 'verbal', reason: '出生确认：raw 建档恒 verbal 占位');
      expect(snap['origin'], 'shared');
      expect((snap['payload'] as Map)['v'], 1);
      expect(r.note, contains('待 AI 提炼'));
    });

    test('raw 建档非 verbal 类别被拒（零解析猜类别=第二真相）', () async {
      await expectLater(
        handler.execute(
          UpsertFactsCommand(
            category: 'transit',
            sourceKind: 'booking',
            title: '猜出来的类别',
            origin: 'shared',
            rawText: '原文',
          ),
          actor: CommandActor.human,
        ),
        throwsCode(ActionErrorCode.invalidRequest),
        reason: 'raw 建档强制 verbal/verbal 占位，类别待首次提炼出生确认',
      );
    });

    test('AI 对话投喂直接结构化：槽位入 payload + 锚点严进通过', () async {
      final p = await plan('云南七天');
      final r = await handler.execute(
        UpsertFactsCommand(
          category: 'transit',
          sourceKind: 'booking',
          title: '大理→丽江 动车 D8724',
          state: 'structured',
          origin: 'ai',
          badge: '05车12F',
          heroMetrics: [
            {'k': '车厢/座位', 'v': '05车 12F（靠窗）'},
            {'k': '检票口', 'v': '2B'},
          ],
          timeAnchors: [
            {'role': '发车', 'kind': 'moment', 'date': '2026-10-20', 'min': 540},
            {'role': '乘车', 'kind': 'span', 'date': '2026-10-20', 'start_min': 540, 'end_min': 680},
          ],
          rawText: '【12306】……',
          planId: p.id,
        ),
        actor: CommandActor.ai,
      );
      final payload = r.snapshot!['payload'] as Map;
      expect(payload['hero_metrics'], hasLength(2));
      expect(payload['time_anchors'], hasLength(2));
      expect(payload['plan_id'], p.id, reason: '双写铁律：payload 投影与列同源');
      expect(r.snapshot!['plan_id'], p.id);
    });

    test('写入口严进：缺 title/origin/category、非法枚举、非法锚点全拒', () async {
      await expectLater(
        handler.execute(
          UpsertFactsCommand(category: 'transit', sourceKind: 'booking', origin: 'ai'),
          actor: CommandActor.ai,
        ),
        throwsCode(ActionErrorCode.invalidRequest),
      );
      await expectLater(
        handler.execute(
          UpsertFactsCommand(title: 'x', origin: 'ai'),
          actor: CommandActor.ai,
        ),
        throwsCode(ActionErrorCode.invalidRequest),
        reason: '建档 category/source_kind 必填（编辑路径缺省=不变）',
      );
      await expectLater(
        handler.execute(
          UpsertFactsCommand(category: 'transit', sourceKind: 'booking', title: 'x'),
          actor: CommandActor.ai,
        ),
        throwsCode(ActionErrorCode.invalidRequest),
        reason: '建档缺 origin',
      );
      await expectLater(
        handler.execute(
          UpsertFactsCommand(category: 'flight', sourceKind: 'booking', title: 'x', origin: 'ai'),
          actor: CommandActor.ai,
        ),
        throwsCode(ActionErrorCode.invalidRequest),
        reason: 'category 五类收敛，flight 非法',
      );
      await expectLater(
        handler.execute(
          UpsertFactsCommand(
            category: 'venue',
            sourceKind: 'announcement',
            title: 'x',
            origin: 'ai',
            timeAnchors: [
              {'kind': 'moment', 'date': '2026-02-30'},
            ],
          ),
          actor: CommandActor.ai,
        ),
        throwsCode(ActionErrorCode.invalidRequest),
        reason: '2026-02-30 非法日历日（DateTime.tryParse 回写比对）',
      );
      await expectLater(
        handler.execute(
          UpsertFactsCommand(
            category: 'venue',
            sourceKind: 'announcement',
            title: 'x',
            origin: 'ai',
            timeAnchors: [
              {'kind': 'anchor'},
            ],
          ),
          actor: CommandActor.ai,
        ),
        throwsCode(ActionErrorCode.invalidRequest),
        reason: 'kind ∈ moment/span/rule',
      );
    });
  });

  group('截断优于拒绝（§1.2）', () {
    test('badge 超 12 字符截断 + note 交代', () async {
      final r = await handler.execute(
        UpsertFactsCommand(
          category: 'transit',
          sourceKind: 'booking',
          title: 'x',
          state: 'structured',
          origin: 'ai',
          badge: '二等座05车厢12F靠窗加',
        ),
        actor: CommandActor.ai,
      );
      expect((r.snapshot!['badge'] as String).length, 12);
      expect(r.note, contains('已截断'));
    });

    test('hero_metrics 超 3 项截前 3 落库不阻断', () async {
      final r = await handler.execute(
        UpsertFactsCommand(
          category: 'ticket',
          sourceKind: 'booking',
          title: '索道票',
          state: 'structured',
          origin: 'ai',
          heroMetrics: [
            {'k': '1', 'v': 'a'},
            {'k': '2', 'v': 'b'},
            {'k': '3', 'v': 'c'},
            {'k': '4', 'v': 'd'},
          ],
        ),
        actor: CommandActor.ai,
      );
      expect(((r.snapshot!['payload'] as Map)['hero_metrics'] as List), hasLength(3));
      expect(r.note, contains('截前 3'));
    });
  });

  group('提炼回填与改挂', () {
    test('raw → structured 同 id 回填，version CAS 生效（出生确认定类别终值）', () async {
      final p = await plan('云南七天');
      final created = await handler.execute(
        UpsertFactsCommand(
          category: 'verbal',
          sourceKind: 'verbal',
          title: '【12306】……',
          origin: 'shared',
          planId: p.id,
        ),
        actor: CommandActor.human,
      );
      final id = created.targetId!;
      final r = await handler.execute(
        UpsertFactsCommand(
          id: id,
          category: 'transit',
          sourceKind: 'booking',
          title: '大理→丽江 动车 D8724',
          state: 'structured',
          badge: '05车12F',
          expectedVersion: 0,
        ),
        actor: CommandActor.ai,
      );
      expect(r.snapshot!['state'], 'structured');
      expect(r.snapshot!['category'], 'transit',
          reason: '出生确认：首次 raw→structured 回填定类别终值');
      expect(r.snapshot!['source_kind'], 'booking');
      expect(r.snapshot!['version'], 1);
      // 旧版本号再写 → CAS 拒并回最新快照
      await expectLater(
        handler.execute(
          UpsertFactsCommand(
            id: id,
            category: 'transit',
            sourceKind: 'booking',
            title: '踩踏写入',
            expectedVersion: 0,
          ),
          actor: CommandActor.ai,
        ),
        throwsA(isA<ActionException>()
            .having((e) => e.code, 'code', ActionErrorCode.versionConflict)
            .having((e) => e.data?['latest'], 'latest', isNotNull)),
      );
    });

    test('改挂：plan_id 列与 payload 投影同步（双写铁律）', () async {
      final p1 = await plan('云南七天');
      final p2 = await plan('丽江中转');
      final created = await handler.execute(
        UpsertFactsCommand(
          category: 'hotel',
          sourceKind: 'booking',
          title: '丽江客栈',
          state: 'structured',
          origin: 'ai',
          planId: p1.id,
        ),
        actor: CommandActor.ai,
      );
      final r = await handler.execute(
        UpsertFactsCommand(
          id: created.targetId!,
          category: 'hotel',
          sourceKind: 'booking',
          planId: p2.id,
        ),
        actor: CommandActor.human,
      );
      expect(r.snapshot!['plan_id'], p2.id);
      expect((r.snapshot!['payload'] as Map)['plan_id'], p2.id);
      expect(r.note, contains('已改挂'));
      final back = await repo.artifactById(created.targetId!);
      expect(back!.planId, p2.id);
      expect(back.payload['plan_id'], p2.id);
    });

    test('structured 后类别恒不可变（出生确认后 patch 通道改不到）', () async {
      final created = await handler.execute(
        UpsertFactsCommand(
          category: 'transit',
          sourceKind: 'booking',
          title: 'D8724',
          state: 'structured',
          origin: 'ai',
        ),
        actor: CommandActor.ai,
      );
      final id = created.targetId!;
      final r = await handler.execute(
        UpsertFactsCommand(
          id: id,
          category: 'hotel',
          sourceKind: 'announcement',
          title: 'D8724',
          state: 'structured',
        ),
        actor: CommandActor.ai,
      );
      expect(r.snapshot!['category'], 'transit',
          reason: 'structured 后 category/source_kind 恒不可变（patch 白名单外）');
      expect(r.snapshot!['source_kind'], 'booking');
    });

    test('raw_text 追加双段只增不清（改签 SOP 溯源）', () async {
      final created = await handler.execute(
        UpsertFactsCommand(
          category: 'verbal',
          sourceKind: 'verbal',
          title: 'D8724',
          origin: 'shared',
          rawText: '【12306】您已购 D8724 09:00 开',
        ),
        actor: CommandActor.human,
      );
      final id = created.targetId!;
      await handler.execute(
        UpsertFactsCommand(
          id: id,
          category: 'verbal',
          sourceKind: 'verbal',
          rawText: '【12306】您已改签 D8732 09:30 开',
        ),
        actor: CommandActor.ai,
      );
      final back = await repo.artifactById(id);
      expect(back!.payload['raw_text'], contains('D8724'));
      expect(back.payload['raw_text'], contains('D8732'));
      expect((back.payload['raw_text'] as String).split('\n'), hasLength(2));
    });
  });

  group('作废与删除（§1.4 反向销毁防护）', () {
    test('voided：存证保留 + 关联 pinned 块同事务降格（绝不删块）', () async {
      final block = await pinnedRideBlock();
      final created = await handler.execute(
        UpsertFactsCommand(
          category: 'transit',
          sourceKind: 'booking',
          title: 'D8724',
          state: 'structured',
          origin: 'ai',
          blockId: block.id,
        ),
        actor: CommandActor.ai,
      );
      final r = await handler.execute(
        UpsertFactsCommand(
          id: created.targetId!,
          category: 'transit',
          sourceKind: 'booking',
          state: 'voided',
        ),
        actor: CommandActor.ai,
      );
      expect(r.snapshot!['state'], 'voided', reason: '存证保留不删');
      final blockAfter = await repo.blockById(block.id!);
      expect(blockAfter, isNotNull, reason: '绝不物理删块');
      expect(blockAfter!.pinned, isFalse, reason: 'pinned 降格交回晨间 propose');
    });

    test('delete_artifact：human 删除+降格；ai 越权 forbidden', () async {
      final block = await pinnedRideBlock();
      final created = await handler.execute(
        UpsertFactsCommand(
          category: 'verbal',
          sourceKind: 'verbal',
          title: '误分享的文本',
          origin: 'shared',
          blockId: block.id,
        ),
        actor: CommandActor.human,
      );
      await expectLater(
        handler.execute(DeleteArtifactCommand(created.targetId!), actor: CommandActor.ai),
        throwsCode(ActionErrorCode.forbidden),
      );
      final r = await handler.execute(
        DeleteArtifactCommand(created.targetId!),
        actor: CommandActor.human,
      );
      expect(r.note, contains('解除钉定'));
      expect(await repo.artifactById(created.targetId!), isNull);
      final blockAfter = await repo.blockById(block.id!);
      expect(blockAfter, isNotNull);
      expect(blockAfter!.pinned, isFalse);
    });
  });

  group('delete_plan detach（§1.1 删除处置）', () {
    test('删计划：凭证移入未归属（列+payload 同步）、背景级联销毁、note 交代', () async {
      final p = await plan('c6 束河线');
      await repo.addBackground(Background(
        scope: Background.scopePlan,
        planId: p.id,
        content: '束河古镇背景',
        source: Background.sourceUser,
      ));
      final structured = await handler.execute(
        UpsertFactsCommand(
          category: 'venue',
          sourceKind: 'announcement',
          title: '茶马古道博物馆须知',
          state: 'structured',
          origin: 'ai',
          planId: p.id,
        ),
        actor: CommandActor.ai,
      );
      await handler.execute(
        UpsertFactsCommand(
          category: 'verbal',
          sourceKind: 'verbal',
          title: '束河古镇门票',
          origin: 'shared',
          planId: p.id,
        ),
        actor: CommandActor.human,
      );
      final r = await handler.execute(DeletePlanCommand(p.id!), actor: CommandActor.human);
      expect(r.note, contains('2 条凭证已移入未归属'));
      expect(r.note, contains('1 条背景随计划销毁'));
      final orphaned = await repo.artifactsUnattributed();
      expect(orphaned, hasLength(2), reason: '删计划≠删现实，凭证 detach 至未归属池');
      final kept = await repo.artifactById(structured.targetId!);
      expect(kept!.planId, isNull);
      expect(kept.payload.containsKey('plan_id'), isFalse, reason: '双写铁律：payload 投影同步移除');
      expect(await repo.listBackgrounds(), isEmpty, reason: '背景级联销毁（硬/软不对称处置）');
    });
  });
}
