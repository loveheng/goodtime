import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:shiguang/data/db.dart';
import 'package:shiguang/data/repository.dart';
import 'package:shiguang/mcp/tools.dart';
import 'package:shiguang/models/plan.dart';
import 'package:shiguang/util/schedule_day.dart';
import 'package:sqflite_common_ffi/sqflite_ffi.dart';

/// MCP 硬事实工具契约单测（fact-user-relay-draft.md §1.6/§8-4：upsert_facts 注册、
/// get_schedule 凭证摘要窗口对齐搭载、list_plans raw 摘要；夹具照抄 mcp_tools_test）。
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
    await db.delete('app_settings');
    // 作息边界（get_schedule 的「今天」按 wake 切割——测试断言用同一口径计算）
    await repo.settingsSet({'wake_time': '420', 'sleep_time': '1380'});
  });

  Future<Object?> call(String name, [Map<String, Object?> args = const {}]) async {
    final content = await callTool(name, args, repo);
    expect(content, hasLength(1));
    expect(content.single['type'], 'text');
    return jsonDecode(content.single['text'] as String);
  }

  final today = isoDate(scheduleDayOf(DateTime.now(), 420));
  final day3 = isoDate(addDays(scheduleDayOf(DateTime.now(), 420), 2));

  Map<String, Object?> structuredPayload({String? date, String? endDate}) => {
        'category': 'transit',
        'source_kind': 'booking',
        'title': '大理→丽江 动车 D8724',
        'state': 'structured',
        'origin': 'ai',
        'badge': '05车12F',
        'time_anchors': [
          {
            'role': '发车',
            'kind': 'moment',
            'date': date ?? today,
            'min': 540,
          },
          if (endDate != null)
            {
              'role': '乘车',
              'kind': 'span',
              'date': date ?? today,
              'start_min': 540,
              'end_min': 680,
              'end_date': endDate,
            },
        ],
      };

  group('upsert_facts 与 list_plans 摘要', () {
    test('raw 入册 → list_plans 携 raw_facts 计数与原文（消化 SOP 读入口）', () async {
      // 出生确认：raw 建档强制 verbal/verbal 占位（类别待首次提炼定终值）
      await call('upsert_facts', {
        'category': 'verbal',
        'source_kind': 'verbal',
        'title': '【12306】您已购 D8724 次车票',
        'origin': 'shared',
        'raw_text': '【12306】赵志恒先生，您已购10月20日D8724次……',
      });
      final plans = await call('list_plans') as Map;
      final rawFacts = plans['raw_facts'] as Map;
      expect(rawFacts['count'], 1);
      final item = (rawFacts['items'] as List).single as Map;
      expect(item['raw_text'], contains('D8724'));
      expect(item['plan_id'], isNull, reason: '未归属池条目 plan_id=null');
    });
  });

  group('get_schedule 凭证摘要搭载', () {
    test('窗口对齐：当日/次日锚与跨日 span 逐日命中，摘要四件套齐全', () async {
      final p = await repo.addPlan(Plan(title: '云南七天'));
      await call('upsert_facts', {
        ...structuredPayload(),
        'plan_id': p.id,
      });
      final schedule = await call('get_schedule', {'days': 3}) as Map;
      final days = schedule['days'] as List;
      // 当日：moment 540 命中，deadline_min=540，badge 携带，按计划分组
      final day0 = (days[0] as Map)['facts'] as Map;
      final section0 = ((day0['plans'] as Map)[p.id!]) as Map;
      expect(section0['title'], '云南七天');
      final item0 = (section0['items'] as List).single as Map;
      expect(item0['title'], contains('D8724'));
      expect(item0['badge'], '05车12F');
      expect(item0['deadline_min'], 540);
      // 次日：span 无 end_date=单日，不覆盖次日 → 当日锚 moment 也不该出现在次日
      final day1 = ((days[1] as Map)['facts'] as Map);
      expect((day1['plans'] as Map), isEmpty);
    });

    test('跨日 span 覆盖区间内每一天；days=3 视窗对齐到第三天', () async {
      final p = await repo.addPlan(Plan(title: '酒店行程'));
      await call('upsert_facts', {
        'category': 'hotel',
        'source_kind': 'booking',
        'title': '丽江客栈 入住',
        'state': 'structured',
        'origin': 'ai',
        'plan_id': p.id,
        'time_anchors': [
          {'role': '入住', 'kind': 'span', 'date': today, 'start_min': 840, 'end_min': 600, 'end_date': day3},
        ],
      });
      final schedule = await call('get_schedule', {'days': 3}) as Map;
      final days = schedule['days'] as List;
      for (var i = 0; i < 3; i++) {
        final facts = (days[i] as Map)['facts'] as Map;
        final section = ((facts['plans'] as Map)[p.id!]) as Map;
        expect((section['items'] as List), hasLength(1), reason: 'span 闭区间覆盖第 ${i + 1} 天');
      }
    });

    test('state 白名单：raw 与 voided 不进排程搭载（§8 施工微观约定 3）', () async {
      await call('upsert_facts', {
        ...structuredPayload(),
        'category': 'verbal',
        'source_kind': 'verbal',
        'state': 'raw',
        'raw_text': '原文',
      });
      final created = await call('upsert_facts', structuredPayload()) as Map;
      await call('upsert_facts', {
        ...structuredPayload(),
        'state': 'voided',
      });
      await call('upsert_facts', {
        'id': (created['snapshot'] as Map)['id'],
        'category': 'transit',
        'source_kind': 'booking',
        'state': 'voided',
      });
      final schedule = await call('get_schedule') as Map;
      final facts = ((schedule['days'] as List).first as Map)['facts'] as Map;
      final plans = facts['plans'] as Map;
      final unattributed = facts['unattributed'] as List;
      expect(plans, isEmpty, reason: '无归属计划的凭证不出现');
      expect(unattributed, isEmpty, reason: 'raw/voided 均不进排程视线');
    });
  });
}
