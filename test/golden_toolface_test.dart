// P0 黄金向量基准执行器（Dart 侧）——MCP tools/call 返回（lib/mcp/tools.dart 工具面）。
//
// 职责：把 test/golden/mcp_toolface_vectors.json 跑通并锁定期望值（不依赖 Kotlin）。
// - 向量 = 跨实现契约数据（Dart 与 Kotlin 双侧消费，Kotlin 对拍在 P2 接入）；
// - 本执行器逐 case 真跑库对拍真实工具面，凡实现漂移即红；
// - 值泛化与 $ref 解引用为独立副本（与生成器解耦，同 golden_e2ee_test 先例）：
//   两副本漂移即红，规则以向量集 rules 节为准。
// 对拍口径：tool_call=值泛化结构深比（uuid/date/iso_datetime/timestamp_ms 泛化为类型
// 标记）；tool_reject=code + data.code + data 键集值级钉样（message 参考文案不比）。
// 向量语义与字段说明见 test/golden/README.md；向量文件为只读契约，实现漂移时
// 唯一出路是修实现，严禁改向量迁就实现（dev-loop 测试写保护）。
import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:shiguang/data/db.dart';
import 'package:shiguang/data/repository.dart';
import 'package:shiguang/mcp/jsonrpc.dart';
import 'package:shiguang/mcp/tools.dart';
import 'package:sqflite_common_ffi/sqflite_ffi.dart';

// ---- 值泛化（独立副本；规则=向量集 rules.generalize_rule） ----------------------

final _uuidRe =
    RegExp(r'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$');
final _dateRe = RegExp(r'^\d{4}-\d{2}-\d{2}$');
final _isoDtRe = RegExp(r'^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}');

Object? _generalize(Object? v) {
  if (v is String) {
    if (_uuidRe.hasMatch(v)) return 'uuid';
    if (_isoDtRe.hasMatch(v)) return 'iso_datetime';
    if (_dateRe.hasMatch(v)) return 'date';
    return v;
  }
  if (v is int) return v > 1000000000000 ? 'timestamp_ms' : v;
  if (v is List) return [for (final e in v) _generalize(e)];
  if (v is Map) {
    return {for (final e in v.entries) e.key: _generalize(e.value)};
  }
  return v;
}

/// args 叶子 `{"$ref": "<seed索引>.<点路径>"}` 解引用（独立副本；规则=rules.ref_rule）。
Object? _deref(Object? v, List<Object?> seedResults) {
  if (v is Map && v.length == 1 && v['\$ref'] is String) {
    final parts = (v['\$ref'] as String).split('.');
    final idx = int.parse(parts.removeAt(0));
    Object? cur = seedResults[idx];
    for (final seg in parts) {
      cur = (cur as Map)[seg];
    }
    return cur;
  }
  if (v is List) return [for (final e in v) _deref(e, seedResults)];
  if (v is Map) {
    return {for (final e in v.entries) e.key as String: _deref(e.value, seedResults)};
  }
  return v;
}

Map<String, dynamic> _loadSet(String name) =>
    jsonDecode(File('test/golden/$name').readAsStringSync())
        as Map<String, dynamic>;

Future<List<Object?>> _runSeed(Repository repo, List<Object?> seedJson) async {
  final results = <Object?>[];
  for (final s in (seedJson as List).cast<Map<String, dynamic>>()) {
    final content = await callTool(s['tool'] as String,
        _deref(s['args'], results) as Map<String, Object?>, repo);
    results.add(jsonDecode(content.single['text'] as String));
  }
  return results;
}

void main() {
  setUpAll(() {
    sqfliteFfiInit();
    databaseFactory = databaseFactoryFfi;
    Db.overridePath(inMemoryDatabasePath);
  });

  // 每 case 清库隔离（= 生成器 callCase/rejectCase 的 _clean 口径）
  setUp(() async {
    final db = await Db.instance(); // 同时注册默认存储工厂
    for (final t in [
      'backgrounds', 'schedule_blocks', 'fixed_slots', 'plans', 'app_settings',
    ]) {
      await db.delete(t);
    }
  });

  final set = _loadSet('mcp_toolface_vectors.json');
  group('golden: mcp-toolface', () {
    for (final c in (set['cases'] as List).cast<Map<String, dynamic>>()) {
      final id = c['id'] as String;
      final input = c['input'] as Map<String, dynamic>;
      final expected = c['expect'] as Map<String, dynamic>;
      switch (c['op'] as String) {
        case 'tool_call':
          test('tool_call $id（值泛化结构对拍）', () async {
            final repo = Repository();
            final seedResults = await _runSeed(repo, input['seed']);
            final content = await callTool(
                input['tool'] as String,
                _deref(input['args'], seedResults) as Map<String, Object?>,
                repo);
            final payload =
                jsonDecode(content.single['text'] as String) as Object?;
            expect(_generalize(payload), expected['payload']);
          });
          break;
        case 'tool_reject':
          test('tool_reject $id（领域码值级对拍）', () async {
            final repo = Repository();
            final seedResults = await _runSeed(repo, input['seed']);
            await expectLater(
              callTool(input['tool'] as String,
                  _deref(input['args'], seedResults) as Map<String, Object?>, repo),
              throwsA(
                isA<McpRpcError>().having((e) => e.code, 'code', expected['code']).having(
                      (e) => e.data is Map ? (e.data as Map)['code'] : null,
                      'data.code',
                      expected['data_code'],
                    ),
              ),
            );
            // data 键集对拍（data 缺省=null 时跳过）
            final wantKeys = expected['data_keys'];
            if (wantKeys != null) {
              McpRpcError caught;
              try {
                await callTool(input['tool'] as String,
                    _deref(input['args'], seedResults) as Map<String, Object?>, repo);
                fail('预期拒绝但成功');
              } on McpRpcError catch (e) {
                caught = e;
              }
              final data = caught.data;
              expect(data, isA<Map>());
              expect((data as Map).keys.toList()..sort(), wantKeys);
            }
          });
          break;
        default:
          fail('未知 op: ${c['op']}（向量集与执行器失配，须同步更新）');
      }
    }
  });
}
