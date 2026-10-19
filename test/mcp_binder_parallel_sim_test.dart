import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:shiguang/data/db.dart';
import 'package:shiguang/data/repository.dart';
import 'package:shiguang/mcp/mcp_server.dart';
import 'package:shiguang/service/weather.dart';
import 'package:sqflite_common_ffi/sqflite_ffi.dart';

/// F1 并行 dispatch 模拟单测：复现 ShiguangMcpBinderService 异步化后 Dart 层
/// 的并行形态——3 条 tools/call 并发打同一 isolate 的 handleJsonRpc（entrypoint
/// 同款直调，不经 Binder/MethodChannel），全部应返回且不互相卡死。
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

  test('3 条并发 tools/call 全部返回（Dart 层无并行卡死）', () async {
    final server = McpServer(repo: Repository(), tokenProvider: () => null, weather: WeatherService());
    final bodies = List.generate(3, (i) => jsonEncode({
          'jsonrpc': '2.0',
          'id': i + 1,
          'method': 'tools/call',
          'params': {'name': 'get_schedule', 'arguments': {'date': '2026-10-17'}},
        }));
    final results = await Future.wait(
      bodies.map((b) => server.handleJsonRpc(b)).map((f) => f.timeout(const Duration(seconds: 10),
          onTimeout: () => throw TimeoutException('dispatch 未在 10s 内返回'))),
    );
    for (final raw in results) {
      expect(raw, isNotNull);
      final msg = jsonDecode(raw) as Map<String, Object?>;
      expect(msg['id'], anyOf(1, 2, 3));
      expect(msg.containsKey('result') || msg.containsKey('error'), isTrue);
    }
  });
}

class TimeoutException implements Exception {
  TimeoutException(this.message);
  final String message;
  @override
  String toString() => message;
}
