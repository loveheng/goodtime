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

/// M3 心理层契约单测：校准指标全量 / Clean Slate 断流保护 / 庆祝豁免
/// （schedule-app.md §6 三组物理校准指标 + §8 断流静默保护 + §4 庆祝豁免）。
void main() {
  setUpAll(() {
    sqfliteFfiInit();
    databaseFactory = databaseFactoryFfi;
    Db.overridePath(inMemoryDatabasePath);
  });

  late Repository repo;
  late Housekeeper keeper;
  late ScheduleQueries queries;

  setUp(() async {
    repo = Repository();
    keeper = Housekeeper(repo);
    queries = ScheduleQueries(repo);
    final db = await Db.instance();
    for (final t in ['schedule_blocks', 'fixed_slots', 'plans', 'app_settings']) {
      await db.delete(t);
    }
    await repo.settingsSet({'wake_time': '420', 'sleep_time': '1380'});
  });

  Future<String> todayIso() async {
    final wake = int.tryParse(await repo.settingsGet('wake_time') ?? '420') ?? 420;
    return isoDate(scheduleDayOf(DateTime.now(), wake));
  }

  Future<CommandResult> handler2Propose() => CommandHandler(repo).execute(
        ProposeScheduleCommand(date: isoDate(scheduleDayOf(DateTime.now(), 420)), items: const [
          ProposedItem(label: '护航叙事样本', startMin: 700, endMin: 740),
        ]),
        actor: CommandActor.ai,
      );

  group('get_history 校准指标全量（§6，2026-10-05 拍板）', () {
    test('深潜净值 / 膨胀系数 / 分象限完成率 / 能量回血', () async {
      final imp = await repo.addPlan(Plan(
        title: '高数复习',
        importance: true,
        estimate: 60,
        deadline: isoDate(addDays(DateTime.now(), 2)),
      ));
      final trivial = await repo.addPlan(Plan(title: '回消息', estimate: 20));
      final today = await todayIso();

      // 重要象限 done 60min（深潜）+ missed 30min（Q2 done/missed = 1/1）
      await repo.addBlock(ScheduleBlock(
          date: today, startMin: 420, endMin: 480, planId: imp.id,
          source: ScheduleBlock.sourceAi, status: ScheduleBlock.statusDone));
      await repo.addBlock(ScheduleBlock(
          date: today, startMin: 480, endMin: 510, planId: imp.id,
          source: ScheduleBlock.sourceAi, status: ScheduleBlock.statusMissed));
      // 非重要 done 40min（Q4 done=1，无 estimate 不进膨胀）
      await repo.addBlock(ScheduleBlock(
          date: today, startMin: 520, endMin: 560, planId: trivial.id,
          source: ScheduleBlock.sourceHuman, status: ScheduleBlock.statusDone));
      // 庆祝 done 30min + 庆祝 archived 1 → 兑现率 0.5
      await repo.addBlock(ScheduleBlock(
          date: today, startMin: 570, endMin: 600,
          source: ScheduleBlock.sourceAi, status: ScheduleBlock.statusDone,
          isCelebration: true));
      await repo.addBlock(ScheduleBlock(
          date: today, startMin: 610, endMin: 640,
          source: ScheduleBlock.sourceAi, status: ScheduleBlock.statusArchived,
          isCelebration: true));

      // protection_manifesto（§6 八轮）：护航摘要随 propose 返回体下发
      final pr = await handler2Propose();
      final manifesto = (pr.data!['protection_manifesto'] as Map);
      expect(manifesto['shielded_free_minutes'], greaterThanOrEqualTo(0));
      expect(manifesto['narrative'], contains('拦截'));

      final h = await queries.getHistory(days: 7);
      final cal = h['calibration'] as Map;
      expect(cal['deep_net_minutes'], 60, reason: '重要象限沉浸绝对分钟（§6 主指标）');
      expect((cal['expansion_factor'] as double).toStringAsFixed(2), '1.25',
          reason: 'Σ实际 100（60+40）/ Σ估算 80（60+20）——两侧只含有估算的 done 块');
      final q = cal['quadrant_done_rates'] as Map;
      expect(q['Q1'], 0.5, reason: 'important + deadline 2 天内 = Q1 重要×紧急');
      expect(q['Q4'], 1.0);
      final rec = cal['energy_recovery'] as Map;
      expect(rec['celebration_done_count'], 1);
      expect(rec['celebration_minutes'], 30);
      expect(rec['fulfillment_rate'], 0.5);
    });

    test('耐受阈值：激增日检测命中，无激增回退默认（上限×粒度）', () async {
      final trivial = await repo.addPlan(Plan(title: 'p'));
      final todayDate = tryParseIsoDate(await todayIso())!;

      // 昨天干满 300min，今天全 missed → 检测命中 300
      final yesterday = isoDate(addDays(todayDate, -1));
      for (var k = 0; k < 5; k++) {
        await repo.addBlock(ScheduleBlock(
            date: yesterday,
            startMin: 420 + k * 60,
            endMin: 480 + k * 60,
            source: ScheduleBlock.sourceAi,
            status: ScheduleBlock.statusDone));
      }
      final h1 = await queries.getHistory(days: 7);
      var tol = (h1['calibration'] as Map)['tolerance'] as Map;
      expect(tol['source'], 'default', reason: '今天尚无 missed，激增未成立');

      final today = await todayIso();
      await repo.addBlock(ScheduleBlock(
          date: today, startMin: 420, endMin: 480,
          source: ScheduleBlock.sourceAi, status: ScheduleBlock.statusMissed));
      final h2 = await queries.getHistory(days: 7);
      tol = (h2['calibration'] as Map)['tolerance'] as Map;
      expect(tol['source'], 'detected');
      expect(tol['minutes'], 300);
      expect(trivial.id, isNotNull);
    });
  });

  group('Clean Slate 断流静默保护（§8，界面文案「轻装重启」）', () {
    test('missed ≥5 触发保护：过期 confirmed 静默 archived 而非 missed', () async {
      final yesterday = isoDate(addDays(tryParseIsoDate(await todayIso())!, -1));
      for (var k = 0; k < ScheduleRules.breakdownMissedTotal; k++) {
        await repo.addBlock(ScheduleBlock(
            date: yesterday,
            startMin: 420 + k * 60,
            endMin: 480 + k * 60,
            source: ScheduleBlock.sourceAi,
            status: ScheduleBlock.statusMissed));
      }
      expect(await queries.isBreakdown(), isTrue, reason: 'missed 累计 ≥5');
      final expired = await repo.addBlock(ScheduleBlock(
          date: yesterday,
          startMin: 900,
          endMin: 960,
          source: ScheduleBlock.sourceAi,
          status: ScheduleBlock.statusConfirmed));
      final r = await keeper.dailyCut();
      expect(r.protected, isTrue);
      expect((await repo.blockById(expired.id!))!.status, ScheduleBlock.statusArchived,
          reason: '保护期不再打红色 missed，改中性 archived（破罐破摔防线）');
    });

    test('恢复交互即退出保护（done 落库重置连续无交互）', () async {
      final yesterday = isoDate(addDays(tryParseIsoDate(await todayIso())!, -1));
      final missed = <String>[];
      for (var k = 0; k < 5; k++) {
        final b = await repo.addBlock(ScheduleBlock(
            date: yesterday,
            startMin: 420 + k * 60,
            endMin: 480 + k * 60,
            source: ScheduleBlock.sourceAi,
            status: ScheduleBlock.statusMissed));
        missed.add(b.id!);
      }
      expect(await queries.isBreakdown(), isTrue);
      // 用户回来补勾全部 missed（交互）
      for (final id in missed) {
        await repo.patchBlock(id, {'status': ScheduleBlock.statusDone});
      }
      expect(await queries.isBreakdown(), isFalse, reason: 'missed 清零 + 今日有交互');
    });

    test('庆祝块最高豁免：过期绝不 missed，静默 archived 淡出', () async {
      final yesterday = isoDate(addDays(tryParseIsoDate(await todayIso())!, -1));
      final confirmed = await repo.addBlock(ScheduleBlock(
          date: yesterday,
          startMin: 540,
          endMin: 600,
          source: ScheduleBlock.sourceAi,
          status: ScheduleBlock.statusConfirmed,
          isCelebration: true));
      final proposed = await repo.addBlock(ScheduleBlock(
          date: yesterday,
          startMin: 620,
          endMin: 680,
          source: ScheduleBlock.sourceAi,
          status: ScheduleBlock.statusProposed,
          isCelebration: true));
      final r = await keeper.dailyCut();
      expect(r.missed, 0, reason: '庆祝块绝不显红（九轮拍板）');
      expect((await repo.blockById(confirmed.id!))!.status, ScheduleBlock.statusArchived);
      expect((await repo.blockById(proposed.id!))!.status, ScheduleBlock.statusArchived,
          reason: 'proposed 庆祝同样淡出而非作废删除');
    });

    test('3 作息日无交互触发保护；digest 携带 clean_slate 旗标', () async {
      final threeDaysAgo = isoDate(addDays(tryParseIsoDate(await todayIso())!, -3));
      await repo.addBlock(ScheduleBlock(
          date: threeDaysAgo,
          startMin: 540,
          endMin: 600,
          source: ScheduleBlock.sourceAi,
          status: ScheduleBlock.statusDone));
      expect(await queries.isBreakdown(), isTrue, reason: '上次交互距今 3 个作息日');
      final digest = await queries.morningDigest();
      expect(digest['clean_slate'], isTrue);
    });
  });
}
