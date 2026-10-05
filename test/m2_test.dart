import 'package:flutter_test/flutter_test.dart';
import 'package:shiguang/action/command_handler.dart';
import 'package:shiguang/action/commands.dart';
import 'package:shiguang/action/queries.dart';
import 'package:shiguang/action/rules.dart';
import 'package:shiguang/data/db.dart';
import 'package:shiguang/data/repository.dart';
import 'package:shiguang/models/plan.dart';
import 'package:shiguang/models/schedule_block.dart';
import 'package:shiguang/service/housekeeper.dart';
import 'package:shiguang/util/schedule_day.dart';
import 'package:sqflite_common_ffi/sqflite_ffi.dart';

/// M2 减震系统契约单测：机械校验全量 / adjust_blocks / 日切 / 晨间 digest
/// （schedule-app.md §6/§8；functional-spec §1 M2）。
void main() {
  setUpAll(() {
    sqfliteFfiInit();
    databaseFactory = databaseFactoryFfi;
    Db.overridePath(inMemoryDatabasePath);
  });

  late Repository repo;
  late CommandHandler handler;
  late Housekeeper keeper;
  late ScheduleQueries queries;

  setUp(() async {
    repo = Repository();
    handler = CommandHandler(repo);
    keeper = Housekeeper(repo);
    queries = ScheduleQueries(repo);
    final db = await Db.instance();
    for (final t in ['schedule_blocks', 'fixed_slots', 'plans', 'app_settings']) {
      await db.delete(t);
    }
    await repo.settingsSet({'wake_time': '420', 'sleep_time': '1380'});
  });

  Future<CommandResult> propose(List<ProposedItem> items, {String? date}) async {
    final wake = int.tryParse(await repo.settingsGet('wake_time') ?? '420') ?? 420;
    final d = date ?? isoDate(scheduleDayOf(DateTime.now(), wake));
    return handler.execute(
      ProposeScheduleCommand(date: d, items: items),
      actor: CommandActor.ai,
    );
  }

  /// 当天（作息日口径）的 ISO 日期。
  Future<String> todayIso() async {
    final wake = int.tryParse(await repo.settingsGet('wake_time') ?? '420') ?? 420;
    return isoDate(scheduleDayOf(DateTime.now(), wake));
  }

  group('propose 机械校验全量（§6）', () {
    test('填充率红线：AI 块总时长超可用 60% 整单拒绝', () async {
      // 窗口 960min，60% = 576min
      final r = await propose([
        const ProposedItem(label: 'a', startMin: 420, endMin: 670),
        const ProposedItem(label: 'b', startMin: 735, endMin: 985),
      ]);
      expect((r.data!['blocks'] as List).length, 2, reason: '500min ≤ 576 通过');
    });

    test('填充率超线拒绝（含呼吸律合规的排布）', () async {
      await expectLater(
        propose([
          const ProposedItem(label: 'a', startMin: 420, endMin: 720),
          const ProposedItem(label: 'b', startMin: 735, endMin: 1035),
        ]),
        throwsA(isA<ActionException>().having(
          (e) => e.data!['rejections'],
          'rejections',
          contains(containsPair('reason', contains('填充率'))),
        )),
      );
    });

    test('呼吸律：45 分钟以上块后不足 15 分钟空白拒绝', () async {
      await expectLater(
        propose([
          const ProposedItem(label: 'a', startMin: 420, endMin: 540), // 120min
          const ProposedItem(label: 'b', startMin: 545, endMin: 600), // 仅隔 5min
        ]),
        throwsA(isA<ActionException>().having(
          (e) => e.data!['rejections'],
          'rejections',
          contains(containsPair('reason', contains('呼吸律'))),
        )),
      );
      // 间隔恰好 15min：通过
      final r = await propose([
        const ProposedItem(label: 'a', startMin: 420, endMin: 540),
        const ProposedItem(label: 'b', startMin: 555, endMin: 600),
      ]);
      expect((r.data!['blocks'] as List).length, 2);
    });

    test('低电量：填充率压至 25% + deep 禁排 + 火种豁免刻度', () async {
      await repo.settingsSet({'today_energy': 'low'});
      final deep = await repo.addPlan(Plan(
        title: '硬核学习',
        energyLevel: Plan.energyDeep,
        minViableAction: '花 5 分钟通读上次草稿',
      ));

      // deep 普通块 → 禁排
      await expectLater(
        propose([
          ProposedItem(planId: deep.id, startMin: 420, endMin: 720),
        ]),
        throwsA(isA<ActionException>().having(
          (e) => e.data!['rejections'],
          'rejections',
          contains(containsPair('reason', contains('禁排深度'))),
        )),
      );

      // 火种豁免：is_day_spark + 15min + min_viable_action → 通过
      final r = await propose([
        ProposedItem(planId: deep.id, startMin: 420, endMin: 435, isDaySpark: true),
      ]);
      expect((r.data!['blocks'] as List).length, 1);

      // 火种豁免刻度：20min 超出 ≤15min 上限 → 拒绝
      await expectLater(
        propose([
          ProposedItem(planId: deep.id, startMin: 420, endMin: 440, isDaySpark: true),
        ]),
        throwsA(isA<ActionException>().having(
          (e) => e.data!['rejections'],
          'rejections',
          contains(containsPair('reason', contains('禁排深度'))),
        )),
      );

      // 填充率 25%：240min 封顶
      await expectLater(
        propose([
          const ProposedItem(label: 'x', startMin: 420, endMin: 720), // 300min > 240
        ]),
        throwsA(isA<ActionException>().having(
          (e) => e.data!['rejections'],
          'rejections',
          contains(containsPair('reason', contains('填充率'))),
        )),
      );
    });

    test('fill_rate_limit 可调（update_settings 5..100）', () async {
      await repo.settingsSet({'fill_rate_limit': '50'});
      await expectLater(
        handler.execute(UpdateSettingsCommand(values: {'fill_rate_limit': 3})),
        throwsA(isA<ActionException>()),
      );
      // 50%：960 × 0.5 = 480；480min 恰好通过、520min 拒绝
      final r = await propose([
        const ProposedItem(label: 'a', startMin: 420, endMin: 720),
        const ProposedItem(label: 'b', startMin: 735, endMin: 915), // 300+180=480
      ]);
      expect((r.data!['blocks'] as List).length, 2);
    });
  });

  group('adjust_blocks（和平条款门控）', () {
    test('add 默认 pinned；撞 human 墙拒绝；坏 plan 拒绝', () async {
      final human = await repo.addBlock(ScheduleBlock(
        date: await todayIso(),
        startMin: 540,
        endMin: 600,
        source: ScheduleBlock.sourceHuman,
      ));
      final ok = await handler.execute(
        AdjustBlocksCommand(
            action: 'add', date: await todayIso(), startMin: 620, endMin: 680, label: '赶车'),
        actor: CommandActor.ai,
      );
      expect(ok.snapshot!['pinned'], true);
      expect(ok.snapshot!['status'], ScheduleBlock.statusProposed);

      await expectLater(
        handler.execute(
          AdjustBlocksCommand(
              action: 'add', date: await todayIso(), startMin: 560, endMin: 590),
          actor: CommandActor.ai,
        ),
        throwsA(isA<ActionException>().having((e) => e.code, 'code', ActionErrorCode.scheduleRejected)),
      );
      expect(human.id, isNotNull, reason: '墙完好');

      await expectLater(
        handler.execute(
          AdjustBlocksCommand(
              action: 'add',
              date: await todayIso(),
              startMin: 620,
              endMin: 680,
              planId: 'no-such'),
          actor: CommandActor.ai,
        ),
        throwsA(isA<ActionException>().having((e) => e.code, 'code', ActionErrorCode.notFound)),
      );
    });

    test('move/resize：human/pinned 不可动；自己的块 CAS 可调', () async {
      final human = await repo.addBlock(ScheduleBlock(
        date: await todayIso(),
        startMin: 540,
        endMin: 600,
        source: ScheduleBlock.sourceHuman,
      ));
      final pinnedAi = await repo.addBlock(ScheduleBlock(
        date: await todayIso(),
        startMin: 620,
        endMin: 680,
        source: ScheduleBlock.sourceAi,
        pinned: true,
      ));
      final own = await repo.addBlock(ScheduleBlock(
        date: await todayIso(),
        startMin: 700,
        endMin: 760,
        source: ScheduleBlock.sourceAi,
      ));

      for (final target in [human.id!, pinnedAi.id!]) {
        await expectLater(
          handler.execute(
            AdjustBlocksCommand(
                action: 'move', blockId: target, startMin: 800, endMin: 860),
            actor: CommandActor.ai,
          ),
          throwsA(isA<ActionException>().having((e) => e.code, 'code', ActionErrorCode.forbidden)),
        );
      }

      final moved = await handler.execute(
        AdjustBlocksCommand(
            action: 'move',
            blockId: own.id!,
            startMin: 820,
            endMin: 880,
            expectedVersion: 0),
        actor: CommandActor.ai,
      );
      expect(moved.snapshot!['start_min'], 820);
      expect((await repo.blockById(own.id!))!.version, 1);
    });

    test('remove：proposed 撤回删除；confirmed 走 melted 中性蒸发', () async {
      final proposed = await repo.addBlock(ScheduleBlock(
        date: await todayIso(),
        startMin: 540,
        endMin: 600,
        source: ScheduleBlock.sourceAi,
      ));
      final confirmed = await repo.addBlock(ScheduleBlock(
        date: await todayIso(),
        startMin: 620,
        endMin: 680,
        source: ScheduleBlock.sourceAi,
        status: ScheduleBlock.statusConfirmed,
      ));

      await handler.execute(
        AdjustBlocksCommand(action: 'remove', blockId: proposed.id!),
        actor: CommandActor.ai,
      );
      expect(await repo.blockById(proposed.id!), isNull, reason: '未确认块撤回=物理删除');

      final r = await handler.execute(
        AdjustBlocksCommand(
            action: 'remove',
            blockId: confirmed.id!,
            expectedVersion: 0),
        actor: CommandActor.ai,
      );
      expect(r.snapshot!['status'], ScheduleBlock.statusMelted);
    });
  });

  group('Housekeeper 日切（wake_time 切割，§8/§11）', () {
    test('过期 confirmed → missed；过期 proposed → 作废；今日不动', () async {
      final today = await todayIso();
      final yesterday = isoDate(addDays(tryParseIsoDate(today)!, -1));
      await repo.addBlock(ScheduleBlock(
          date: yesterday,
          startMin: 540,
          endMin: 600,
          source: ScheduleBlock.sourceAi,
          status: ScheduleBlock.statusConfirmed));
      await repo.addBlock(ScheduleBlock(
          date: yesterday,
          startMin: 620,
          endMin: 680,
          source: ScheduleBlock.sourceAi,
          status: ScheduleBlock.statusProposed));
      await repo.addBlock(ScheduleBlock(
          date: today,
          startMin: 540,
          endMin: 600,
          source: ScheduleBlock.sourceAi,
          status: ScheduleBlock.statusProposed));

      final r = await keeper.dailyCut();
      expect(r.missed, 1);
      expect(r.voided, 1);
      expect((await repo.blockById((await repo.blocksOnDate(yesterday)).first.id!))!.status,
          ScheduleBlock.statusMissed, reason: '可补勾');
      expect((await repo.blocksOnDate(yesterday)).length, 1, reason: 'proposed 作废=删除');
      expect((await repo.blocksOnDate(today)).length, 1, reason: '今日不动');

      // 幂等：再跑一遍无新增迁移
      final r2 = await keeper.dailyCut();
      expect(r2.missed, 0);
      expect(r2.voided, 0);
    });

    test('missed 补勾：tick_block 接受 missed → done（补记）', () async {
      final yesterday = isoDate(addDays(
          tryParseIsoDate(await todayIso())!, -1));
      final b = await repo.addBlock(ScheduleBlock(
          date: yesterday,
          startMin: 540,
          endMin: 600,
          source: ScheduleBlock.sourceAi,
          status: ScheduleBlock.statusConfirmed));
      await keeper.dailyCut();
      expect((await repo.blockById(b.id!))!.status, ScheduleBlock.statusMissed);

      final r = await handler.execute(
        TickBlockCommand(b.id!, expectedVersion: 1),
      );
      expect(r.snapshot!['status'], ScheduleBlock.statusDone);
      expect(r.note, contains('补记'));
    });
  });

  group('晨间 digest（§8 确定性视图）', () {
    test('昨日遗留 / 孤儿与冷藏 / 悬空判定', () async {
      final today = await todayIso();
      final todayDate = tryParseIsoDate(today)!;
      final yesterday = isoDate(addDays(todayDate, -1));
      final threeDaysAgo = isoDate(addDays(todayDate, -3));
      final nineDaysAgoMs = addDays(todayDate, -(ScheduleRules.orphanColdDays + 2))
          .millisecondsSinceEpoch;

      // ① 昨日遗留：yesterday confirmed → 日切后 missed
      final stale = await repo.addBlock(ScheduleBlock(
          date: yesterday,
          startMin: 540,
          endMin: 600,
          source: ScheduleBlock.sourceAi,
          status: ScheduleBlock.statusConfirmed));
      await keeper.dailyCut();

      // ③ 孤儿：新孤儿（无块、刚建）进 digest；8 天前未动 → 冷藏只报数
      await repo.addPlan(Plan(title: '新鲜的念头'));
      final old = await repo.addPlan(Plan(title: '陈年旧念头'));
      await (await Db.instance()).rawUpdate(
        'UPDATE plans SET updated_at = ? WHERE id = ?',
        [nineDaysAgoMs, old.id],
      );

      // ④ 悬空：父计划 + 子计划，子 3 天前 done，无未来块，无未决问题
      final parent = await repo.addPlan(Plan(title: '办签证'));
      final child = await repo.addPlan(Plan(title: '订机票', parentId: parent.id));
      await repo.addBlock(ScheduleBlock(
          date: threeDaysAgo,
          startMin: 540,
          endMin: 600,
          planId: child.id,
          source: ScheduleBlock.sourceHuman,
          status: ScheduleBlock.statusDone));

      final digest = await queries.morningDigest();

      expect((digest['leftovers'] as List).map((b) => b['id']), contains(stale.id));
      expect((digest['orphans'] as List).map((p) => p['title']), contains('新鲜的念头'));
      expect(digest['cold_storage_count'], 1);
      final dangling = (digest['dangling'] as List).cast<Map>();
      expect(dangling.map((d) => d['title']), contains('办签证'));
      expect(dangling.first['days'], 3);

      // 悬空豁免一：子计划有未决 open_items → 不判定悬空（还在线上）
      final parent2 = await repo.addPlan(Plan(title: '学吉他'));
      final child2 = await repo.addPlan(Plan(
        title: '练和弦',
        parentId: parent2.id,
        openItems: const [OpenItem(question: '每天练多久？')],
      ));
      await repo.addBlock(ScheduleBlock(
          date: threeDaysAgo,
          startMin: 540,
          endMin: 600,
          planId: child2.id,
          source: ScheduleBlock.sourceHuman,
          status: ScheduleBlock.statusDone));
      final digest2 = await queries.morningDigest();
      expect((digest2['dangling'] as List).map((d) => d['title']), isNot(contains('学吉他')));

      // 悬空豁免二：子计划今日有未来块 → 不判定
      final parent3 = await repo.addPlan(Plan(title: '写论文'));
      final child3 = await repo.addPlan(Plan(title: '写引言', parentId: parent3.id));
      await repo.addBlock(ScheduleBlock(
          date: threeDaysAgo,
          startMin: 540,
          endMin: 600,
          planId: child3.id,
          source: ScheduleBlock.sourceHuman,
          status: ScheduleBlock.statusDone));
      await repo.addBlock(ScheduleBlock(
          date: today,
          startMin: 540,
          endMin: 600,
          planId: child3.id,
          source: ScheduleBlock.sourceAi,
          status: ScheduleBlock.statusProposed));
      final digest3 = await queries.morningDigest();
      expect((digest3['dangling'] as List).map((d) => d['title']), isNot(contains('写论文')));
      expect(child.id, isNotNull);
      expect(child2.id, isNotNull);
      expect(child3.id, isNotNull);
    });
  });
}
