import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:shiguang/data/db.dart';
import 'package:shiguang/data/repository.dart';
import 'package:shiguang/mcp/jsonrpc.dart';
import 'package:shiguang/mcp/mcp_server.dart';
import 'package:shiguang/util/schedule_day.dart';
import 'package:sqflite_common_ffi/sqflite_ffi.dart';

/// 内嵌 MCP 服务端到端单测：HTTP 层握手/鉴权/工具链路（模式照抄拾贝 mcp_server_test）。
void main() {
  setUpAll(() {
    sqfliteFfiInit();
    databaseFactory = databaseFactoryFfi;
    Db.overridePath(inMemoryDatabasePath);
  });

  setUp(() async {
    final db = await Db.instance();
    await db.delete('schedule_blocks');
    await db.delete('fixed_slots');
    await db.delete('plans');
    await db.delete('app_settings');
  });

  Future<({McpServer server, HttpClient client, String base})> spinUp({String? token}) async {
    final server = McpServer(repo: Repository(), tokenProvider: () => token);
    await server.start(port: 0);
    final client = HttpClient();
    return (server: server, client: client, base: 'http://127.0.0.1:${server.port}');
  }

  Future<({int status, Map<String, Object?>? body})> rpc(
    HttpClient client,
    String base,
    Object id,
    String method, [
    Object? params,
    Map<String, String> headers = const {},
  ]) async {
    final req = await client.postUrl(Uri.parse('$base/mcp'));
    req.headers.contentType = ContentType.json;
    headers.forEach((k, v) => req.headers.set(k, v));
    req.write(jsonEncode({'jsonrpc': '2.0', 'id': id, 'method': method, 'params': ?params}));
    final res = await req.close();
    final body = await utf8.decoder.bind(res).join();
    return (
      status: res.statusCode,
      body: body.isEmpty ? null : jsonDecode(body) as Map<String, Object?>,
    );
  }

  Map<String, Object?> resultOf(Map<String, Object?>? body) =>
      (body?['result'] ?? <String, Object?>{}) as Map<String, Object?>;

  Map<String, Object?> errorOf(Map<String, Object?>? body) =>
      (body?['error'] ?? <String, Object?>{}) as Map<String, Object?>;

  test('initialize 握手：版本协商与拾光 instructions（政策精华）', () async {
    final env = await spinUp();
    final r = await rpc(env.client, env.base, 1, 'initialize', {'protocolVersion': '2025-03-26'});
    expect(r.status, 200);
    expect(resultOf(r.body)['protocolVersion'], '2025-03-26');
    expect((resultOf(r.body)['serverInfo'] as Map)['name'], 'shiguang');
    final instructions = resultOf(r.body)['instructions'] as String;
    expect(instructions, contains('先读后排'), reason: 'propose 前必读纪律进 instructions（§7 政策精华）');
    expect(instructions, contains('布朗运动'), reason: 'MCP 第一定律（六轮拍板）');

    final r2 = await rpc(env.client, env.base, 2, 'initialize', {'protocolVersion': '1999-01-01'});
    expect(resultOf(r2.body)['protocolVersion'], McpProtocol.latestVersion);
    env.client.close();
    await env.server.stop();
  });

  test('通知 202 / GET 405 / token 鉴权', () async {
    final env = await spinUp(token: 'secret');
    final req = await env.client.postUrl(Uri.parse('${env.base}/mcp'));
    req.headers.contentType = ContentType.json;
    req.headers.set('x-api-key', 'secret');
    req.write(jsonEncode({'jsonrpc': '2.0', 'method': 'notifications/initialized'}));
    final res = await req.close();
    expect(res.statusCode, HttpStatus.accepted);

    final unauth = await rpc(env.client, env.base, 1, 'ping');
    expect(unauth.status, HttpStatus.unauthorized);
    final auth = await rpc(env.client, env.base, 2, 'ping', null, {'x-api-key': 'secret'});
    expect(auth.status, 200);
    expect(resultOf(auth.body), isEmpty, reason: 'ping 回空对象');
    env.client.close();
    await env.server.stop();
  });

  test('M1 全链路（HTTP）：引导播种 → 排程提案 → 确认 → 勾选 → get_history 聚合', () async {
    final env = await spinUp();
    Future<Object?> call(String name, Map<String, Object?> args) async {
      final r = await rpc(env.client, env.base, 1, 'tools/call', {'name': name, 'arguments': args});
      expect(r.status, 200);
      final result = resultOf(r.body);
      expect(result['isError'], false);
      return jsonDecode(((result['content'] as List).single as Map)['text'] as String);
    }

    // ① 首启引导播种（settings + 一周节奏）
    final settings = await call('update_settings', {'wake_time': 420, 'sleep_time': 1380}) as Map;
    expect(settings['ok'], true);
    await call('update_fixed_slots', {
      'slots': [
        {'name': '睡眠', 'weekdays': [1, 2, 3, 4, 5, 6, 7], 'start_min': 1410, 'end_min': 450},
      ],
    });

    // ② AI 建计划 → 整天提案（提案日期=今天的作息日，get_history 视窗必覆盖）
    final plan = await call('add_plan', {'title': '有个考试', 'min_viable_action': '花 5 分钟过一遍错题本'}) as Map;
    final planId = plan['snapshot']['id'] as String;
    final today = isoDate(scheduleDayOf(DateTime.now(), 420));
    final proposed = await call('propose_schedule', {
      'date': today,
      'items': [
        {'plan_id': planId, 'label': '高数：第三章极限习题', 'start_min': 540, 'end_min': 620, 'is_day_spark': true},
      ],
    }) as Map;
    final blockId = ((proposed['data']['blocks'] as List).single as Map)['id'] as String;

    // ③ 人在 app 确认 + 勾选（命令层直调=UI 同通道）
    final repo = Repository();
    expect((await repo.blockById(blockId))!.status, 'proposed');
    final confirmed = await repo.patchBlock(blockId, {'status': 'confirmed'}, expectedVersion: 0);
    expect(confirmed, isTrue);
    final ticked = await repo.patchBlock(blockId, {'status': 'done'}, expectedVersion: 1);
    expect(ticked, isTrue);

    // ④ get_history 看到聚合（验收闭环 §1 M1）
    final history = await call('get_history', {'days': 3}) as Map;
    final totals = history['totals'] as Map;
    expect(totals['done_count'], 1);
    expect(totals['done_minutes'], 80);
    expect(totals['day_spark_done'], 1);

    env.client.close();
    await env.server.stop();
  });

  test('propose 被拒：HTTP 错误体携带逐条明细与空窗', () async {
    final env = await spinUp();
    await rpc(env.client, env.base, 1, 'tools/call',
        {'name': 'update_settings', 'arguments': {'wake_time': 420, 'sleep_time': 1380}});
    final r = await rpc(env.client, env.base, 2, 'tools/call', {
      'name': 'propose_schedule',
      'arguments': {
        'date': '2026-10-06',
        'items': [
          {'start_min': 300, 'end_min': 360},
        ],
      },
    });
    expect(r.status, 200, reason: '领域拒绝走 JSON-RPC error（HTTP 200）');
    final err = errorOf(r.body);
    final errData = err['data'] as Map;
    expect(errData, containsPair('code', 'schedule_rejected'));
    expect(errData['available_free_windows'] as List, isNotEmpty);
    env.client.close();
    await env.server.stop();
  });

  test('tools/list 注册十三工具，prompts 先空实现', () async {
    final env = await spinUp();
    final tools = await rpc(env.client, env.base, 1, 'tools/list');
    expect(((resultOf(tools.body)['tools'] as List).length), 13,
        reason: '工具面 tripwire：增减工具必须显式过本测（suggest_user_setting 2026-10 增；'
            'upsert_background/merge_backgrounds 背景双工具 2026-10-07 增）');
    final prompts = await rpc(env.client, env.base, 2, 'prompts/list');
    expect(resultOf(prompts.body)['prompts'], isEmpty);
    env.client.close();
    await env.server.stop();
  });
}
