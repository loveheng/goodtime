import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:shiguang/data/db.dart';
import 'package:shiguang/data/repository.dart';
import 'package:shiguang/data/settings.dart';
import 'package:shiguang/mcp/jsonrpc.dart';
import 'package:shiguang/mcp/tools.dart';
import 'package:shiguang/models/fixed_slot.dart';
import 'package:shiguang/models/plan.dart';
import 'package:shiguang/models/schedule_block.dart';
import 'package:sqflite_common_ffi/sqflite_ffi.dart';

/// MCP 工具面契约单测（§6 十工具；模式照抄拾贝 mcp_tools_test）。
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
    await db.delete('schedule_blocks');
    await db.delete('fixed_slots');
    await db.delete('plans');
    await db.delete('app_settings');
  });

  Future<Object?> call(String name, [Map<String, Object?> args = const {}]) async {
    final content = await callTool(name, args, repo);
    expect(content, hasLength(1));
    expect(content.single['type'], 'text');
    return jsonDecode(content.single['text'] as String);
  }

  Future<void> seedSettings() => repo.settingsSet({'wake_time': '420', 'sleep_time': '1380'});

  test('工具面注册（§6 十个排程工具 + 一个建言通道）', () {
    final names = [for (final t in toolSchemas()) t['name']];
    expect(names, [
      'list_plans',
      'add_plan',
      'update_plan',
      'get_settings',
      'update_settings',
      'update_fixed_slots',
      'propose_schedule',
      'adjust_blocks',
      'get_schedule',
      'get_history',
      'suggest_user_setting',
    ]);
  });

  test('suggest_user_setting：合法仅用户自设键写入建议列表，非法键被拒', () async {
    await call('suggest_user_setting', {'key': 'user_rules', 'reason': '周五实际常加班'});
    final raw = await repo.settingsGet(SettingsKeys.aiSettingSuggestions);
    expect(raw, isNotNull);
    final list = jsonDecode(raw!) as List;
    expect(list, hasLength(1));
    expect(list.single['key'], 'user_rules');
    expect(list.single['status'], 'pending');

    expect(
      () => call('suggest_user_setting', {'key': 'wake_time', 'reason': 'x'}),
      throwsA(isA<McpRpcError>()),
    );
  });

  test('add_plan → list_plans：写入与读取同走命令层/仓库', () async {
    final r = await call('add_plan', {
      'title': '想去云南玩',
      'min_viable_action': '花 5 分钟收藏两个攻略视频',
    }) as Map;
    expect(r['ok'], true);
    expect(r['snapshot']['energy_level'], Plan.energyLight);

    final listed = await call('list_plans') as Map;
    expect(listed['count'], 1);
    expect(listed['plans'].single['title'], '想去云南玩');

    // update_plan patch + 乐观锁回快照
    final updated = await call('update_plan', {
      'id': r['snapshot']['id'],
      'spec': '2026 云南行',
      'expected_version': 0,
    }) as Map;
    expect(updated['snapshot']['version'], 1);

    // version 冲突：错误透传领域拒绝码 + latest 快照（§6 返回契约）
    await expectLater(
      call('update_plan', {'id': r['snapshot']['id'], 'title': '踩踏', 'expected_version': 0}),
      throwsA(
        isA<McpRpcError>().having(
          (e) => e.data,
          'data',
          containsPair('code', 'version_conflict'),
        ),
      ),
    );
  });

  test('get_settings：设置+fixed_slots+当前日期同返回体（§6）', () async {
    await seedSettings();
    await repo.replaceFixedSlots([
      FixedSlot(name: '睡眠', weekdays: const [1, 2, 3, 4, 5, 6, 7], startMin: 1410, endMin: 450),
    ]);
    final r = await call('get_settings') as Map;
    expect(r['initialized'], true);
    expect(r['today_energy'], 'normal');
    expect(r['today'], isNotEmpty);
    expect((r['fixed_slots'] as List).single['name'], '睡眠');
  });

  test('propose_schedule：合法提案经 MCP 落库，拒绝明细与空窗透传', () async {
    await seedSettings();
    final r = await call('propose_schedule', {
      'date': '2026-10-06',
      'items': [
        {'label': '高数：第三章习题', 'start_min': 540, 'end_min': 600, 'is_day_spark': true},
      ],
    }) as Map;
    expect(r['ok'], true);
    final blocks = await repo.blocksOnDate('2026-10-06');
    expect(blocks.single.status, ScheduleBlock.statusProposed);

    await expectLater(
      call('propose_schedule', {
        'date': '2026-10-06',
        'items': [
          {'start_min': 300, 'end_min': 360},
        ],
      }),
      throwsA(isA<McpRpcError>().having(
        (e) => e.data,
        'data',
        allOf(containsPair('code', 'schedule_rejected'), containsPair('available_free_windows', isNotEmpty)),
      )),
    );
  });

  test('update_settings / update_fixed_slots 错误透传领域码', () async {
    await expectLater(
      call('update_settings', {'today_energy': 'low-but-not'}),
      throwsA(isA<McpRpcError>().having((e) => e.data, 'data', containsPair('code', 'invalid_request'))),
    );
    await expectLater(
      call('update_fixed_slots', {
        'slots': [
          {'name': 'x', 'weekdays': [9], 'start_min': 0, 'end_min': 60},
        ],
      }),
      throwsA(isA<McpRpcError>()),
    );
  });

  test('adjust_blocks：add 单块提案（pinned 默认 true）+ move', () async {
    await call('update_settings', {'wake_time': 420, 'sleep_time': 1380});
    final r = await call('adjust_blocks', {
      'action': 'add',
      'date': '2026-10-06',
      'start_min': 540,
      'end_min': 600,
      'label': '赶车',
    }) as Map;
    expect(r['snapshot']['pinned'], true, reason: 'AI 建的无 plan 硬行程默认 pinned（§4）');
    expect(r['snapshot']['status'], 'proposed');

    // pinned 块 AI 自己也不可动（和平条款 §3），显式 pinned=false 的提案块可挪
    final soft = await call('adjust_blocks', {
      'action': 'add',
      'date': '2026-10-06',
      'start_min': 620,
      'end_min': 680,
      'label': '候补安排',
      'pinned': false,
    }) as Map;
    expect(soft['snapshot']['pinned'], false);

    final moved = await call('adjust_blocks', {
      'action': 'move',
      'block_id': soft['snapshot']['id'],
      'start_min': 700,
      'end_min': 760,
      'expected_version': 0,
    }) as Map;
    expect(moved['snapshot']['start_min'], 700);

    await expectLater(
      call('adjust_blocks', {
        'action': 'move',
        'block_id': r['snapshot']['id'],
        'start_min': 700,
        'expected_version': 0,
      }),
      throwsA(isA<McpRpcError>().having(
          (e) => e.data, 'data', containsPair('code', 'forbidden'))),
    );
  });

  test('get_schedule / get_history：days 钳制与聚合形状', () async {
    await seedSettings();
    final schedule = await call('get_schedule', {'days': 99}) as Map;
    expect((schedule['days'] as List).length, 3, reason: 'days ≤3（§6）');
    expect(schedule['today'], isNotEmpty);

    final history = await call('get_history') as Map;
    expect(history['totals'], isNotNull);
    expect((history['days'] as List).length, 14);
  });

  test('未知工具拒绝', () async {
    await expectLater(
      call('no_such_tool'),
      throwsA(isA<McpRpcError>().having((e) => e.message, 'message', 'Unknown tool: no_such_tool')),
    );
  });
}
