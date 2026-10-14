import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:shiguang/data/db.dart';
import 'package:shiguang/data/repository.dart';
import 'package:shiguang/mcp/mcp_server.dart';
import 'package:sqflite_common_ffi/sqflite_ffi.dart';

/// Binder 插件等价体单测（goodhub-local-plugin 终点，模式照抄 goodshare
/// mcp_binder_test）：构造「binder isolate 装配路径」——
/// `Db.registerDefaultStorageFactory()` + `Repository()` + `McpServer(tokenProvider: null)`
/// 的组合（与 lib/mcp_binder_entrypoint.dart 同一装配，去掉 MethodChannel 壳），
/// 验证零鉴权下 handleJsonRpc 全链路可用（goodhub bind 后经原生 dispatch 到这里）。
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

  /// 与 mcp_binder_entrypoint.dart 同构的最小装配（无 UI/无 HTTP/无 token）。
  McpServer binderServer() {
    Db.registerDefaultStorageFactory(); // entrypoint 同款显式注册
    return McpServer(
      repo: Repository(),
      tokenProvider: () => null, // Binder 同签名零鉴权
    );
  }

  Future<Map<String, Object?>> call(
    McpServer server,
    Object id,
    String method, [
    Object? params,
  ]) async {
    final raw = await server.handleJsonRpc(
      jsonEncode({'jsonrpc': '2.0', 'id': id, 'method': method, 'params': ?params}),
    );
    expect(raw, isNotEmpty, reason: '请求带 id 必须有响应帧（B3 回显 id）');
    final frame = jsonDecode(raw) as Map<String, Object?>;
    expect(frame['id'], id, reason: 'B3：响应帧 id 必须回显请求 id');
    return frame;
  }

  test('零鉴权 initialize 握手：serverInfo=shiguang + instructions 政策精华', () async {
    final server = binderServer();
    final frame = await call(server, 1, 'initialize', {'protocolVersion': '2025-03-26'});
    final result = frame['result'] as Map<String, Object?>;
    expect((result['serverInfo'] as Map)['name'], 'shiguang');
    expect(result['protocolVersion'], '2025-03-26');
    expect(result['instructions'], contains('先读后排'));
  });

  test('零鉴权 tools/list：tokenProvider 恒 null 不挡工具面', () async {
    final server = binderServer();
    final frame = await call(server, 2, 'tools/list');
    final tools = (frame['result'] as Map<String, Object?>)['tools'] as List;
    expect(tools, isNotEmpty);
    final names = tools.map((t) => (t as Map)['name']).toSet();
    expect(names, containsAll(['add_plan', 'get_schedule', 'propose_schedule']));
  });

  test('零鉴权写入全链路：add_plan 落库可读', () async {
    final server = binderServer();
    final frame = await call(server, 3, 'tools/call', {
      'name': 'add_plan',
      'arguments': {'title': 'binder 冒烟计划', 'spec': '验证 binder isolate 写入链路'},
    });
    final result = frame['result'] as Map<String, Object?>;
    final content = (result['content'] as List).first as Map<String, Object?>;
    expect(result['isError'], false, reason: '零鉴权下写命令必须成功：$content');
    final snapshot = jsonDecode(content['text'] as String) as Map<String, Object?>;
    final planId = snapshot['id'] as String; // 写路径回最新快照：顶层 id=目标计划

    final db = await Db.instance();
    final rows = await db.query('plans', where: 'id = ?', whereArgs: [planId]);
    expect(rows, hasLength(1), reason: 'binder 等价体经同一存储工厂落库');
    expect(rows.single['title'], 'binder 冒烟计划');
  });

  test('坏 JSON 与未知方法：错误帧回显 id（B3）不穿透异常', () async {
    final server = binderServer();
    // 解析失败：无 id 可回显，标准 parse 错误帧
    final bad = jsonDecode(await server.handleJsonRpc('not-json')) as Map<String, Object?>;
    expect(bad['id'], isNull);
    expect((bad['error'] as Map)['code'], isNotNull);
    // 未知方法：错误帧回显请求 id（B3）
    final unknown = await call(server, 4, 'no/such/method');
    expect((unknown['error'] as Map)['code'], -32601);
  });
}
