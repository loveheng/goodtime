import 'package:flutter_test/flutter_test.dart';
import 'package:shiguang/action/command_handler.dart';
import 'package:shiguang/action/commands.dart';
import 'package:shiguang/action/queries.dart';
import 'package:shiguang/data/db.dart';
import 'package:shiguang/data/repository.dart';
import 'package:shiguang/models/fixed_slot.dart';
import 'package:shiguang/models/plan.dart';
import 'package:shiguang/models/schedule_block.dart';
import 'package:shiguang/util/schedule_day.dart';
import 'package:sqflite_common_ffi/sqflite_ffi.dart';

/// 命令层契约单测（Human-AI 对称性 §3/§6；夹具模式照抄拾贝 action_handler_test）。
void main() {
  setUpAll(() {
    sqfliteFfiInit();
    databaseFactory = databaseFactoryFfi;
    Db.overridePath(inMemoryDatabasePath);
  });

  late Repository repo;
  late CommandHandler handler;
  late ScheduleQueries queries;

  setUp(() async {
    repo = Repository();
    handler = CommandHandler(repo);
    queries = ScheduleQueries(repo);
    final db = await Db.instance();
    await db.delete('schedule_blocks');
    await db.delete('fixed_slots');
    await db.delete('plans');
    await db.delete('app_settings');
  });

  Future<void> seedSettings({int wake = 420, int sleep = 1380}) =>
      repo.settingsSet({'wake_time': '$wake', 'sleep_time': '$sleep'});

  Matcher throwsCode(String code) =>
      throwsA(isA<ActionException>().having((e) => e.code, 'code', code));

  group('主体越权门（防伪：actor 由传输层注入）', () {
    test('human 专属命令对 ai 拒绝', () async {
      await expectLater(
        handler.execute(QuickCaptureCommand(title: 'x'), actor: CommandActor.ai),
        throwsCode(ActionErrorCode.forbidden),
      );
      await expectLater(
        handler.execute(TickBlockCommand('whatever'), actor: CommandActor.ai),
        throwsCode(ActionErrorCode.forbidden),
      );
    });

    test('propose_schedule 对 human 拒绝（校验刻度不对称 §3）', () async {
      await expectLater(
        handler.execute(
          ProposeScheduleCommand(date: '2026-10-06', items: const []),
          actor: CommandActor.human,
        ),
        throwsCode(ActionErrorCode.forbidden),
      );
    });
  });

  group('清单命令', () {
    test('quick_capture：零细节捕获落愿望池默认值', () async {
      final r = await handler.execute(QuickCaptureCommand(title: '想去云南玩', importance: true));
      expect(r.snapshot!['title'], '想去云南玩');
      expect(r.snapshot!['importance'], true);
      expect(r.snapshot!['energy_level'], Plan.energyLight);
      final back = await repo.planById(r.targetId!);
      expect(back!.archived, isFalse);
    });

    test('upsert_plan / update_plan：字段级 patch + CAS 回最新快照（§6）', () async {
      final created = await handler.execute(UpsertPlanCommand(title: '有个考试', energyLevel: 'deep'));
      final id = created.targetId!;
      expect(created.snapshot!['version'], 0);

      final updated = await handler.execute(
        UpdatePlanCommand(id: id, spec: '2026-11-20 期末考试', expectedVersion: 0),
      );
      expect(updated.snapshot!['version'], 1);
      expect(updated.snapshot!['spec'], '2026-11-20 期末考试');
      expect(updated.snapshot!['energy_level'], Plan.energyDeep, reason: '未发的字段不动');

      // 拿旧版本踩踏 → version_conflict，错误体带 latest 快照
      try {
        await handler.execute(UpdatePlanCommand(id: id, title: '被踩踏', expectedVersion: 0));
        fail('应当拒绝');
      } on ActionException catch (e) {
        expect(e.code, ActionErrorCode.versionConflict);
        expect((e.data!['latest'] as Map)['version'], 1);
      }

      await expectLater(
        handler.execute(UpdatePlanCommand(id: id)),
        throwsCode(ActionErrorCode.invalidRequest),
      );
      await expectLater(
        handler.execute(UpdatePlanCommand(id: 'no-such', title: 'x')),
        throwsCode(ActionErrorCode.notFound),
      );
      await expectLater(
        handler.execute(UpsertPlanCommand(title: 'x', energyLevel: 'extreme')),
        throwsCode(ActionErrorCode.invalidRequest),
      );
    });
  });

  group('propose_schedule（整天原子写 + 机械校验）', () {
    test('settings 未初始化整单硬拒（§6 校验铁律）', () async {
      await expectLater(
        handler.execute(
          ProposeScheduleCommand(date: '2026-10-06', items: [
            ProposedItem(startMin: 540, endMin: 600),
          ]),
          actor: CommandActor.ai,
        ),
        throwsCode(ActionErrorCode.settingsMissing),
      );
    });

    test('合法提案落库为 proposed/ai，重复提案只替换未确认 AI 块', () async {
      await seedSettings();
      final plan = await repo.addPlan(Plan(title: '高数复习'));
      final r = await handler.execute(
        ProposeScheduleCommand(date: '2026-10-06', items: [
          ProposedItem(planId: plan.id, label: '高数：第三章习题', startMin: 540, endMin: 580, isDaySpark: true),
          ProposedItem(label: '回消息', startMin: 580, endMin: 620),
        ]),
        actor: CommandActor.ai,
      );
      expect((r.data!['blocks'] as List).length, 2);
      final first = await repo.blocksOnDate('2026-10-06');
      expect(first.length, 2);
      expect(first.every((b) => b.source == ScheduleBlock.sourceAi), isTrue);
      expect(first.every((b) => b.status == ScheduleBlock.statusProposed), isTrue);

      // 再次提案：内部重叠被拒
      await expectLater(
        handler.execute(
          ProposeScheduleCommand(date: '2026-10-06', items: [
            ProposedItem(startMin: 540, endMin: 600),
            ProposedItem(startMin: 560, endMin: 620),
          ]),
          actor: CommandActor.ai,
        ),
        throwsCode(ActionErrorCode.scheduleRejected),
      );
      // 整单原子性：被拒不落库
      expect((await repo.blocksOnDate('2026-10-06')).length, 2);

      // 非重叠新单：旧的 proposed 被替换，不重复堆积
      final r2 = await handler.execute(
        ProposeScheduleCommand(date: '2026-10-06', items: [
          ProposedItem(startMin: 700, endMin: 760),
        ]),
        actor: CommandActor.ai,
      );
      expect(r2.data!['replaced'], 2);
      final after = await repo.blocksOnDate('2026-10-06');
      expect(after.length, 1);
      expect(after.single.startMin, 700);
    });

    test('机械校验矩阵：逐条拒绝 + available_free_windows 附给', () async {
      await seedSettings();
      await repo.replaceFixedSlots([
        FixedSlot(name: '午餐', weekdays: [1, 2, 3, 4, 5, 6, 7], startMin: 720, endMin: 780),
      ]);
      final humanWall = await repo.addBlock(ScheduleBlock(
        date: '2026-10-06', startMin: 900, endMin: 960, source: ScheduleBlock.sourceHuman));
      final aiConfirmed = await repo.addBlock(ScheduleBlock(
        date: '2026-10-06', startMin: 1020, endMin: 1080, source: ScheduleBlock.sourceAi));
      await repo.patchBlock(aiConfirmed.id!, {'status': ScheduleBlock.statusConfirmed});
      final ghostPlan = 'no-such-plan';

      try {
        await handler.execute(
          ProposeScheduleCommand(date: '2026-10-06', items: [
            ProposedItem(planId: ghostPlan, startMin: 540, endMin: 600), // 0 plan 不存在
            ProposedItem(startMin: 300, endMin: 360), // 1 越起床边界
            ProposedItem(startMin: 600, endMin: 740), // 2 撞固定占用
            ProposedItem(startMin: 920, endMin: 970), // 3 撞 human 块
            ProposedItem(startMin: 1040, endMin: 1100), // 4 撞已成现实 AI 块
            ProposedItem(startMin: 540, endMin: 540), // 5 零长块
            ProposedItem(startMin: 1140, endMin: 1440), // 6 越睡觉边界（end 顶到 24:00）
            ProposedItem(startMin: 800, endMin: 860, isDaySpark: true),
            ProposedItem(startMin: 860, endMin: 900, isDaySpark: true), // 7 火种 ×2
          ]),
          actor: CommandActor.ai,
        );
        fail('应当整单拒绝');
      } on ActionException catch (e) {
        expect(e.code, ActionErrorCode.scheduleRejected);
        final rejections = (e.data!['rejections'] as List).cast<Map>();
        final byIndex = {for (final r in rejections) r['index'] as int: r['reason'] as String};
        expect(byIndex[0], contains('不存在'));
        expect(byIndex[1], contains('起床'));
        expect(byIndex[2], contains('固定占用'));
        expect(byIndex[3], contains('human'));
        expect(byIndex[4], contains('不可穿透'));
        expect(byIndex[5], contains('零长'));
        expect(byIndex[6], contains('睡觉'));
        expect(byIndex[8], contains('至多一个'));
        final windows = (e.data!['available_free_windows'] as List).cast<Map>();
        expect(windows, isNotEmpty);
        // 07:00 起床后第一段空闲应从 420 起
        expect(windows.first['start_min'], 420);
      }
      // 整单原子性：一块都没落
      expect((await repo.blocksOnDate('2026-10-06')).length, 2);
      expect(humanWall, isNotNull);
    });

    test('前夜溢出段不可穿透（跨午夜固定占用 §4）', () async {
      await seedSettings(wake: 420, sleep: 1380);
      await repo.replaceFixedSlots([
        // 周二晚睡眠 23:30–07:30：溢出到周三早晨
        FixedSlot(name: '睡眠', weekdays: [2], startMin: 1410, endMin: 450),
      ]);
      final wed = DateTime(2026, 10, 7);
      expect(wed.weekday, DateTime.wednesday, reason: '测试锚点自检');
      try {
        await handler.execute(
          ProposeScheduleCommand(date: isoDate(wed), items: [
            ProposedItem(startMin: 420, endMin: 480),
          ]),
          actor: CommandActor.ai,
        );
        fail('应当拒绝：与溢出睡眠段重叠');
      } on ActionException catch (e) {
        expect(e.code, ActionErrorCode.scheduleRejected);
        expect((e.data!['rejections'] as List).first['reason'], contains('溢出'));
      }
    });
  });

  group('确认三键 / 勾选 / 顺延（human 通道）', () {
    test('confirm → tick → 终态不可再勾；get_history 聚合', () async {
      await seedSettings();
      final proposed = await handler.execute(
        ProposeScheduleCommand(date: '2026-10-06', items: [
          ProposedItem(startMin: 540, endMin: 600, isDaySpark: true),
        ]),
        actor: CommandActor.ai,
      );
      final blockId = (proposed.data!['blocks'] as List).first['id'] as String;

      final confirmed = await handler.execute(ConfirmBlockCommand(blockId, expectedVersion: 0));
      expect(confirmed.snapshot!['status'], ScheduleBlock.statusConfirmed);

      final ticked = await handler.execute(
        TickBlockCommand(blockId, executionQuality: ScheduleBlock.qualitySpark, expectedVersion: 1),
      );
      expect(ticked.snapshot!['status'], ScheduleBlock.statusDone);
      expect(ticked.snapshot!['execution_quality'], ScheduleBlock.qualitySpark);
      expect(ticked.note, contains('启动版'));

      await expectLater(
        handler.execute(TickBlockCommand(blockId)),
        throwsCode(ActionErrorCode.invalidRequest),
      );
      await expectLater(
        handler.execute(ConfirmBlockCommand(blockId)),
        throwsCode(ActionErrorCode.invalidRequest),
      );

      final history = await queries.getHistory(now: DateTime(2026, 10, 7, 8, 0), days: 3);
      final totals = history['totals'] as Map;
      expect(totals['done_count'], 1);
      expect(totals['done_minutes'], 60);
      expect(totals['day_spark_done'], 1);
    });

    test('reject：仅 proposed 可否决，块删除蒸发', () async {
      await seedSettings();
      final proposed = await handler.execute(
        ProposeScheduleCommand(date: '2026-10-06', items: [
          ProposedItem(startMin: 540, endMin: 600),
        ]),
        actor: CommandActor.ai,
      );
      final blockId = (proposed.data!['blocks'] as List).first['id'] as String;
      await handler.execute(RejectBlockCommand(blockId, reason: '当天不想排'));
      expect(await repo.blockById(blockId), isNull);
      await expectLater(
        handler.execute(RejectBlockCommand(blockId)),
        throwsCode(ActionErrorCode.notFound),
      );
    });

    test('adjust_block_time：human 重叠放行仅提示，ai 越权拒绝', () async {
      await seedSettings();
      await repo.addBlock(ScheduleBlock(
        date: '2026-10-06', startMin: 900, endMin: 960, source: ScheduleBlock.sourceHuman));
      final target = await repo.addBlock(ScheduleBlock(
        date: '2026-10-06', startMin: 540, endMin: 600, source: ScheduleBlock.sourceAi));

      final adjusted = await handler.execute(
        AdjustBlockTimeCommand(id: target.id!, startMin: 920, endMin: 980),
      );
      expect(adjusted.note, contains('照常放行'));
      expect(adjusted.snapshot!['start_min'], 920);

      await expectLater(
        handler.execute(AdjustBlockTimeCommand(id: target.id!, startMin: 300, endMin: 360),
            actor: CommandActor.ai),
        throwsCode(ActionErrorCode.forbidden),
      );
    });

    test('shift_block +30：级联顺延 AI 块，human 块原地不动仅提示', () async {
      await seedSettings();
      final a = await repo.addBlock(ScheduleBlock(
        date: '2026-10-06', startMin: 540, endMin: 600, source: ScheduleBlock.sourceAi));
      final b = await repo.addBlock(ScheduleBlock(
        date: '2026-10-06', startMin: 600, endMin: 660, source: ScheduleBlock.sourceAi));
      final h = await repo.addBlock(ScheduleBlock(
        date: '2026-10-06', startMin: 660, endMin: 720, source: ScheduleBlock.sourceHuman));

      final r = await handler.execute(ShiftBlockCommand(a.id!));
      expect((r.data!['shifted'] as List).length, 2, reason: '目标 + 1 个被挤压的 AI 块');
      final fa = await repo.blockById(a.id!);
      final fb = await repo.blockById(b.id!);
      final fh = await repo.blockById(h.id!);
      expect(fa!.startMin, 570);
      expect(fa.postponeCount, 1, reason: '顺延=移动原块保身份，+1（§4）');
      expect(fb!.startMin, 630, reason: '级联顺延');
      expect(fb.postponeCount, 1);
      expect(fh!.startMin, 660, reason: 'human 块原地不动');
      expect(r.note, contains('仅提示'));

      // 级联后撞 human：B 630-690 与 H 660-720 重叠 → 提示但不挪
      expect(r.note, contains('重叠'));
    });

    test('shift 越过午夜拒绝', () async {
      final b = await repo.addBlock(ScheduleBlock(
        date: '2026-10-06', startMin: 1430, endMin: 1435, source: ScheduleBlock.sourceAi));
      await expectLater(
        handler.execute(ShiftBlockCommand(b.id!, minutes: 30)),
        throwsCode(ActionErrorCode.invalidRequest),
      );
    });
  });

  group('settings / fixed_slots 写', () {
    test('update_settings：注册制键 + 类型校验 + null 清空', () async {
      await expectLater(
        handler.execute(UpdateSettingsCommand(values: {'nope': '1'})),
        throwsCode(ActionErrorCode.invalidRequest),
      );
      await expectLater(
        handler.execute(UpdateSettingsCommand(values: {'wake_time': 1440})),
        throwsCode(ActionErrorCode.invalidRequest),
      );
      await expectLater(
        handler.execute(UpdateSettingsCommand(values: {'today_energy': 'low-but-not'})),
        throwsCode(ActionErrorCode.invalidRequest),
      );

      await handler.execute(UpdateSettingsCommand(values: {
        'wake_time': 420,
        'sleep_time': 1380,
        'today_energy': 'low',
        'user_rules': '周五晚上不排深度工作',
      }));
      final s = await queries.getSettings();
      expect(s['initialized'], true);
      expect(s['today_energy'], 'low');
      expect(s['user_rules'], '周五晚上不排深度工作');

      await handler.execute(UpdateSettingsCommand(values: {'today_energy': null}));
      expect((await queries.getSettings())['today_energy'], 'normal', reason: '清空后回落默认平稳');
    });

    test('update_fixed_slots：整单替换 + 非法 weekday 拒绝 + 空表合法', () async {
      await expectLater(
        handler.execute(UpdateFixedSlotsCommand(slots: [
          FixedSlot(name: 'x', weekdays: const [0], startMin: 0, endMin: 60),
        ])),
        throwsCode(ActionErrorCode.invalidRequest),
      );
      await handler.execute(UpdateFixedSlotsCommand(slots: [
        FixedSlot(name: '睡眠', weekdays: [1, 2, 3, 4, 5, 6, 7], startMin: 1410, endMin: 450),
      ]));
      expect((await queries.getSchedule(now: DateTime(2026, 10, 6, 12), days: 1))['days'], isNotEmpty);
      await handler.execute(const UpdateFixedSlotsCommand(slots: []));
      expect(await repo.listFixedSlots(), isEmpty, reason: '空表合法（自由职业预设 §9）');
    });
  });

  group('查询层（服务端供日期 §6）', () {
    test('get_schedule：作息日为「今天」+ label 回落 plan 标题', () async {
      await seedSettings(wake: 420);
      final plan = await repo.addPlan(Plan(title: '高数复习'));
      await repo.addBlock(ScheduleBlock(
        date: '2026-10-05', startMin: 540, endMin: 600, planId: plan.id, source: ScheduleBlock.sourceAi));
      // 凌晨 2 点仍属前一作息日（wake 07:00 切割）
      final s = await queries.getSchedule(now: DateTime(2026, 10, 5, 2, 0), days: 2);
      expect(s['today'], '2026-10-04');
      final day0 = (s['days'] as List).first as Map;
      expect(day0['date'], '2026-10-04');
      final day1 = (s['days'] as List).last as Map;
      expect(day1['date'], '2026-10-05');
      final blocks = (day1['blocks'] as List).cast<Map>();
      expect(blocks.single['effective_label'], '高数复习', reason: 'label 缺省回落 plan 标题（§4）');
    });

    test('get_history：空数据零聚合不炸', () async {
      await seedSettings();
      final h = await queries.getHistory(now: DateTime(2026, 10, 6, 12), days: 7);
      expect((h['days'] as List).length, 7);
      expect((h['totals'] as Map)['done_count'], 0);
    });
  });
}
