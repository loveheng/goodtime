import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:shiguang/action/command_handler.dart';
import 'package:shiguang/action/commands.dart';
import 'package:shiguang/action/queries.dart';
import 'package:shiguang/data/db.dart';
import 'package:shiguang/data/repository.dart';
import 'package:shiguang/data/settings.dart';
import 'package:shiguang/models/fixed_slot.dart';
import 'package:shiguang/models/plan.dart';
import 'package:shiguang/models/schedule_block.dart';
import 'package:shiguang/service/weather.dart';
import 'dart:io';

import 'package:shiguang/util/schedule_day.dart';
import 'package:sqflite_common_ffi/sqflite_ffi.dart';

/// M4 现实接口契约单测：例外日旅行模式 / 天气投影 / 三日水位线 /
/// 逆向记账 / 换乘 / 坍缩 / 融化 / 水流降档重算 / JSON 导出。
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
    for (final t in ['schedule_blocks', 'fixed_slots', 'plans', 'app_settings']) {
      await db.delete(t);
    }
    await repo.settingsSet({'wake_time': '420', 'sleep_time': '1380'});
  });

  Future<String> todayIso() async {
    final wake = int.tryParse(await repo.settingsGet('wake_time') ?? '420') ?? 420;
    return isoDate(scheduleDayOf(DateTime.now(), wake));
  }

  group('例外日旅行模式（§13）', () {
    test('fixed_slots 挂起 + 填充率降至 35% + get_schedule 旗标与水位线抬升', () async {
      final tomorrow = isoDate(addDays(tryParseIsoDate(await todayIso())!, 1));
      await repo.replaceFixedSlots([
        FixedSlot(name: '上班', weekdays: [1, 2, 3, 4, 5, 6, 7], startMin: 540, endMin: 1020),
      ]);
      await repo.settingsSet({
        'exceptions': jsonEncode([
          {'start': tomorrow, 'end': tomorrow, 'label': '云南行'},
        ]),
      });

      // 上班时段提案不再撞固定占用（旅行挂起）
      final r = await handler.execute(
        ProposeScheduleCommand(date: tomorrow, items: const [
          ProposedItem(label: '古城慢逛', startMin: 540, endMin: 600),
        ]),
        actor: CommandActor.ai,
      );
      expect((r.data!['blocks'] as List).length, 1);

      // 填充率 35%：可用 960 × 0.35 = 336；400min 拒绝
      await expectLater(
        handler.execute(
          ProposeScheduleCommand(date: tomorrow, items: const [
            ProposedItem(label: 'a', startMin: 620, endMin: 1020),
          ]),
          actor: CommandActor.ai,
        ),
        throwsA(isA<ActionException>().having(
          (e) => e.data!['rejections'],
          'rejections',
          contains(containsPair('reason', contains('填充率'))),
        )),
      );

      // get_schedule：例外旗标 + fixed 挂起 + 水位线抬升（960 全空闲）
      final schedule = await queries.getSchedule(now: tryParseIsoDate(tomorrow), days: 2);
      final day1 = (schedule['days'] as List).last as Map;
      expect(day1['exception'], '云南行');
      expect((day1['fixed_slots'] as Map)['suspended'], true);
      expect(day1['free_minutes'], 960);
    });

    test('无例外日水位线：空闲 = 作息 − human/pinned 墙（AI 自有块不占容）', () async {
      final today = await todayIso();
      await repo.addBlock(ScheduleBlock(
          date: today, startMin: 540, endMin: 600, source: ScheduleBlock.sourceHuman));
      await repo.addBlock(ScheduleBlock(
          date: today, startMin: 620, endMin: 700, source: ScheduleBlock.sourceAi));
      final schedule = await queries.getSchedule(days: 1);
      final day0 = (schedule['days'] as List).first as Map;
      expect(day0['free_minutes'], 960 - 60, reason: 'AI 块可替换不占容量水位线');
    });
  });

  group('天气投影（§11：无 key 源 + TTL + 静默降级）', () {
    test('成功路径：geocoding + forecast 映射三日结构化预报', () async {
      var calls = 0;
      final svc = WeatherService(fetch: (url) async {
        calls++;
        if (url.host.contains('geocoding')) {
          return jsonEncode({
            'results': [
              {'latitude': 25.6, 'longitude': 100.2},
            ],
          });
        }
        return jsonEncode({
          'daily': {
            'time': ['2026-10-06', '2026-10-07', '2026-10-08'],
            'weather_code': [0, 61, 95],
            'temperature_2m_max': [28.0, 24.0, 22.0],
            'temperature_2m_min': [16.0, 15.0, 14.0],
            'precipitation_probability_max': [5, 80, 60],
          },
        });
      });
      final wf = await svc.forecast3('大理');
      expect(wf, isNotNull);
      expect(wf!['city'], '大理');
      final days = wf['days'] as List;
      expect(days.length, 3);
      expect((days[0] as Map)['summary'], '晴');
      expect((days[1] as Map)['summary'], '雨');
      expect((days[2] as Map)['summary'], '雷雨');

      // TTL 内二次调用不重新拉源
      await svc.forecast3('大理', now: DateTime.now().add(const Duration(minutes: 30)));
      expect(calls, 2, reason: 'geocoding+forecast 各一次，缓存命中不增');
    });

    test('失败静默降级：源不可达返回 null 不抛（AI 可改口问用户）', () async {
      final svc = WeatherService(fetch: (url) async => throw const SocketException('offline'));
      expect(await svc.forecast3('大理'), isNull);
      expect(await svc.forecast3(''), isNull, reason: '城市为空直接降级');
    });

    test('get_schedule 搭载：设了城市才有 weather 字段', () async {
      // 不可达源 → 静默；此处只验证「未设城市不搭载」的分支
      await repo.settingsSet({'weather_location': ''});
      final schedule = await queries.getSchedule(days: 1);
      expect(schedule.containsKey('weather'), isFalse);
    });
  });

  group('逆向记账 / 换乘 / 坍缩 / 融化（§3/§10）', () {
    test('retro_log：事实通道豁免排程校验（越界时段可记，落 done）', () async {
      final b = await repo.addPlan(Plan(title: '湖边看日落'));
      final r = await handler.execute(RetroLogCommand(
        date: await todayIso(),
        startMin: 300, // 凌晨 05:00，早于起床边界——现实发生了就承认
        endMin: 360,
        planId: b.id,
      ));
      expect(r.snapshot!['status'], ScheduleBlock.statusDone);
      expect(r.note, contains('事实通道'));
    });

    test('swap_block：换入 light/anywhere 候选，原任务回池；pinned 拒绝', () async {
      final candidate = await repo.addPlan(Plan(title: '收拾桌面'));
      final original = await repo.addPlan(Plan(title: '写报告'));
      final block = await repo.addBlock(ScheduleBlock(
          date: await todayIso(),
          startMin: 540,
          endMin: 600,
          planId: original.id,
          source: ScheduleBlock.sourceAi));
      final pinned = await repo.addBlock(ScheduleBlock(
          date: await todayIso(),
          startMin: 700,
          endMin: 760,
          source: ScheduleBlock.sourceAi,
          pinned: true));

      await expectLater(
        handler.execute(SwapBlockCommand(pinned.id!)),
        throwsA(isA<ActionException>().having((e) => e.code, 'code', ActionErrorCode.forbidden)),
      );

      final r = await handler.execute(SwapBlockCommand(block.id!));
      expect(r.data!['swapped_in'], candidate.id);
      expect(r.note, contains('原任务已放回清单待安排'));
      expect(await repo.blockById(block.id!), isNull, reason: '原块无痕移除');
      expect(await repo.planById(original.id!), isNotNull, reason: '原任务回池不丢');
      final swapped = (await repo.blocksOnDate(await todayIso()))
          .where((x) => x.startMin == 540)
          .single;
      expect(swapped.planId, candidate.id);
    });

    test('swap_block：无候选时报可读错误', () async {
      final block = await repo.addBlock(ScheduleBlock(
          date: await todayIso(), startMin: 540, endMin: 600,
          source: ScheduleBlock.sourceAi));
      await expectLater(
        handler.execute(SwapBlockCommand(block.id!)),
        throwsA(isA<ActionException>()
            .having((e) => e.message, 'message', contains('没有可换乘'))),
      );
    });

    test('degrade_block：AI 块缩 5 分钟火种胶囊；human 块拒绝', () async {
      final plan = await repo.addPlan(Plan(
          title: '写论文',
          minViableAction: '花 5 分钟通读上次草稿'));
      final ai = await repo.addBlock(ScheduleBlock(
          date: await todayIso(), startMin: 540, endMin: 600,
          planId: plan.id, source: ScheduleBlock.sourceAi));
      final human = await repo.addBlock(ScheduleBlock(
          date: await todayIso(), startMin: 700, endMin: 760,
          source: ScheduleBlock.sourceHuman));

      final r = await handler.execute(DegradeBlockCommand(ai.id!, expectedVersion: 0));
      expect(r.snapshot!['end_min'], 545);
      expect(r.snapshot!['execution_quality'], ScheduleBlock.qualitySpark);
      expect(r.note, contains('5 分钟启动版'));
      expect(r.note, contains('通读上次草稿'));

      await expectLater(
        handler.execute(DegradeBlockCommand(human.id!)),
        throwsA(isA<ActionException>().having((e) => e.code, 'code', ActionErrorCode.forbidden)),
      );
    });

    test('melt_block：活跃块 → melted 中性态；done 终态拒绝', () async {
      final proposed = await repo.addBlock(ScheduleBlock(
          date: await todayIso(), startMin: 540, endMin: 600,
          source: ScheduleBlock.sourceAi));
      final done = await repo.addBlock(ScheduleBlock(
          date: await todayIso(), startMin: 700, endMin: 760,
          source: ScheduleBlock.sourceHuman, status: ScheduleBlock.statusDone));

      final r = await handler.execute(MeltBlockCommand(proposed.id!));
      expect(r.snapshot!['status'], ScheduleBlock.statusMelted);
      expect(r.note, contains('放回清单待安排'));

      await expectLater(
        handler.execute(MeltBlockCommand(done.id!)),
        throwsA(isA<ActionException>().having((e) => e.code, 'code', ActionErrorCode.invalidRequest)),
      );
    });
  });

  group('水流模型确定性重算（§7 六轮：电量晚点选三级阻尼）', () {
    test('低电量：留白淹没（免动作）→ 后序块降 spark → 溢出 melted；庆祝/pinned/human 豁免', () async {
      final deepPlan = await repo.addPlan(Plan(
          title: '硬核学习', energyLevel: Plan.energyDeep, minViableAction: '花 5 分钟通读上次草稿'));
      final today = await todayIso();
      // AI 块三段：早（minViable 可压）、中（minViable 可压）、晚（无 minViable 先融）
      final a = await repo.addBlock(ScheduleBlock(
          date: today, startMin: 420, endMin: 720,
          planId: deepPlan.id, source: ScheduleBlock.sourceAi,
          status: ScheduleBlock.statusConfirmed));
      final b = await repo.addBlock(ScheduleBlock(
          date: today, startMin: 730, endMin: 1030,
          planId: deepPlan.id, source: ScheduleBlock.sourceAi,
          status: ScheduleBlock.statusConfirmed));
      final c = await repo.addBlock(ScheduleBlock(
          date: today, startMin: 1040, endMin: 1100,
          source: ScheduleBlock.sourceAi,
          status: ScheduleBlock.statusConfirmed));
      // 豁免三方：human 墙、pinned AI、庆祝
      final human = await repo.addBlock(ScheduleBlock(
          date: today, startMin: 1110, endMin: 1170, source: ScheduleBlock.sourceHuman,
          status: ScheduleBlock.statusConfirmed));
      final pinned = await repo.addBlock(ScheduleBlock(
          date: today, startMin: 1180, endMin: 1240,
          source: ScheduleBlock.sourceAi, pinned: true,
          status: ScheduleBlock.statusConfirmed));
      final celebration = await repo.addBlock(ScheduleBlock(
          date: today, startMin: 1250, endMin: 1280,
          source: ScheduleBlock.sourceAi, status: ScheduleBlock.statusConfirmed,
          isCelebration: true));

      await repo.settingsSet({'today_energy': 'low'});
      final r = await handler.execute(ReflowDayCommand(), actor: CommandActor.human);

      expect(r.data!['budget_minutes'], (960 - 120) * 25 ~/ 100, reason: '可用 = 作息窗 − human/pinned 墙 120min');
      final melted = r.data!['melted'] as List;
      final sparked = r.data!['sparked'] as List;
      expect(melted, contains(c.id), reason: '无 min_viable 的后序块最先蒸发回池');
      expect(sparked, containsAll([a.id, b.id]), reason: '二级阻尼压为火种');
      expect((await repo.blockById(a.id!))!.endMin, 435, reason: '压为 15 分钟火种刻度');
      expect((await repo.blockById(a.id!))!.executionQuality, ScheduleBlock.qualitySpark);
      // 豁免三方原样
      expect((await repo.blockById(human.id!))!.status, ScheduleBlock.statusConfirmed);
      expect((await repo.blockById(pinned.id!))!.status, ScheduleBlock.statusConfirmed);
      expect((await repo.blockById(celebration.id!))!.status, ScheduleBlock.statusConfirmed);
      expect(r.note, contains('不产生任何惩罚'));
    });

    test('非低电量 no-op', () async {
      final r = await handler.execute(ReflowDayCommand());
      expect(r.note, contains('无需降档'));
    });
  });

  group('日/周/月三视图数据层（2026-10-06 拍板）', () {
    test('weekOverview：周一..周日七天块 + 聚焦窗', () async {
      final today = await todayIso();
      final anchor = tryParseIsoDate(today)!;
      final monday =
          DateTime(anchor.year, anchor.month, anchor.day - (anchor.weekday - 1));
      // 星期无关的双日选择：anchor 必在本周；另一块放周一（anchor 即周一时放周日）
      final other = anchor.weekday == 1 ? addDays(anchor, 6) : monday;
      await repo.addBlock(ScheduleBlock(
          date: today, startMin: 540, endMin: 600, source: ScheduleBlock.sourceAi));
      await repo.addBlock(ScheduleBlock(
          date: isoDate(other),
          startMin: 420,
          endMin: 480,
          source: ScheduleBlock.sourceHuman));
      final w = await queries.weekOverview(anchor);
      final days = (w['days'] as List).cast<Map>();
      expect(days.length, 7);
      expect(days.first['weekday'], '一');
      expect(days.first['date'], isoDate(monday));
      expect((days.singleWhere((d) => d['date'] == today)['blocks'] as List).length, 1);
      expect(
          (days.singleWhere((d) => d['date'] == isoDate(other))['blocks'] as List).length,
          1);
      expect(w['wake'], 420);
      expect(w['sleep_adj'], 1380);
    });

    test('monthOverview：分日摘要（done/missed/proposed/火种/庆祝/例外）', () async {
      final todayDate = tryParseIsoDate(await todayIso())!;
      final monthFirst = DateTime(todayDate.year, todayDate.month, 1);
      await repo.addBlock(ScheduleBlock(
          date: isoDate(todayDate),
          startMin: 540,
          endMin: 600,
          source: ScheduleBlock.sourceAi,
          status: ScheduleBlock.statusDone,
          isDaySpark: true));
      await repo.addBlock(ScheduleBlock(
          date: isoDate(todayDate),
          startMin: 700,
          endMin: 760,
          source: ScheduleBlock.sourceHuman,
          status: ScheduleBlock.statusMissed));
      await repo.addBlock(ScheduleBlock(
          date: isoDate(monthFirst),
          startMin: 540,
          endMin: 600,
          source: ScheduleBlock.sourceAi,
          status: ScheduleBlock.statusProposed,
          isCelebration: true));
      await repo.settingsSet({
        'exceptions': jsonEncode([
          {
            'start': isoDate(monthFirst),
            'end': isoDate(monthFirst),
            'label': '云南行',
          }
        ]),
      });

      final today = await todayIso();
      final m = await queries.monthOverview(todayDate.year, todayDate.month);
      expect(m['today'], today);
      final days = (m['days'] as List).cast<Map>();
      expect(days.length, DateTime(todayDate.year, todayDate.month + 1, 0).day);
      final todayCell = days.singleWhere((d) => d['date'] == today);
      expect(todayCell['done'], 1);
      expect(todayCell['missed'], 1);
      expect(todayCell['spark_done'], true);
      final firstCell = days.first;
      expect(firstCell['proposed'], 1);
      expect(firstCell['celebration'], true);
      expect(firstCell['exception'], '云南行');
    });
  });

  group('JSON 导出（§11 数据出口）', () {
    test('exportAll：四存储面全量', () async {
      await repo.addPlan(Plan(title: '想去云南玩'));
      await repo.replaceFixedSlots([
        FixedSlot(name: '睡眠', weekdays: const [1, 2, 3, 4, 5, 6, 7], startMin: 1410, endMin: 450),
      ]);
      await repo.addBlock(ScheduleBlock(
          date: await todayIso(), startMin: 540, endMin: 600,
          source: ScheduleBlock.sourceHuman));
      await repo.settingsSet({'today_energy': 'high'});

      final data = await repo.exportAll();
      expect((data['plans'] as List).length, 1);
      expect((data['fixed_slots'] as List).length, 1);
      expect((data['schedule_blocks'] as List).length, 1);
      final settings = (data['app_settings'] as List)
          .where((r) => r['key'] == SettingsKeys.todayEnergy)
          .toList();
      expect(settings.single['value'], 'high');
      expect(jsonEncode(data), contains('exported_at'));
    });
  });
}
