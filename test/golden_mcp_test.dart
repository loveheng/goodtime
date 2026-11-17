// P0 黄金向量基准执行器（Dart 侧）——命令拒绝码（lib/mcp/mcp_server.dart 内核）。
//
// 职责：把 test/golden/mcp_rejection_vectors.json 跑通并锁定期望值（不依赖 Kotlin）。
// - 向量 = 跨实现契约数据（Dart 与 Kotlin 双侧消费，Kotlin 对拍在 P2 接入）；
// - 本执行器逐 case 对拍真实 McpServer.handleJsonRpc / McpProtocol.negotiate，
//   凡实现漂移即红；
// - fake 工具面与规范化为独立副本（与生成器解耦，同 golden_e2ee_test 先例）：
//   两副本漂移即红，规范化规则以向量集 rules 节为准。
// 对拍口径（rules.message_policy）：error.message 为参考文案不比（剔除后深比），
// code / data / id / result 逐字比对（instructions 政策精华整段逐字契约）。
// 向量语义与字段说明见 test/golden/README.md；向量文件为只读契约，实现漂移时
// 唯一出路是修实现，严禁改向量迁就实现（dev-loop 测试写保护）。
import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

import 'package:shiguang/mcp/jsonrpc.dart';
import 'package:shiguang/mcp/mcp_server.dart';
import 'package:shiguang/mcp/tool_face.dart';

// ---- fake 工具面（与生成器同形独立副本） --------------------------------------

class FakeToolFace implements McpToolFace {
  @override
  List<Map<String, Object?>> schemas() => const [
        {
          'name': 'fake_tool_a',
          'description': 'alpha',
          'inputSchema': {'type': 'object'},
        },
        {
          'name': 'fake_tool_b',
          'description': 'beta',
          'inputSchema': {'type': 'object'},
        },
      ];

  @override
  Future<List<Map<String, Object?>>> call(
      String name, Map<String, Object?> args) async {
    switch (name) {
      case 'echo':
        return [
          {'type': 'text', 'text': 'echo:${args['msg']}'},
        ];
      case 'reject_domain':
        throw McpRpcError(errInvalidParams, '提案被拒', {
          'code': 'schedule_rejected',
          'available_free_windows': [
            {'start_min': 660, 'end_min': 720},
          ],
        });
      case 'boom':
        throw StateError('boom');
      default:
        throw McpRpcError(errInvalidParams, 'Unknown tool: $name');
    }
  }
}

McpServer _newServer() =>
    McpServer(toolFace: FakeToolFace(), tokenProvider: () => null);

// ---- 对拍辅助（rules.message_policy：error.message 剔除后深比） ----------------

Map<String, Object?> _loadSet(String name) =>
    jsonDecode(File('test/golden/$name').readAsStringSync())
        as Map<String, dynamic>;

Map<String, Object?> _stripMessage(Object? frame) {
  if (frame is! Map<String, Object?>) return frame as Map<String, Object?>;
  final clone = Map<String, Object?>.of(frame);
  final err = clone['error'];
  if (err is Map<String, Object?>) {
    clone['error'] = (Map<String, Object?>.of(err)..remove('message'));
  }
  return clone;
}

void main() {
  final set = _loadSet('mcp_rejection_vectors.json');
  group('golden: mcp-rejection', () {
    for (final c in (set['cases'] as List).cast<Map<String, dynamic>>()) {
      final id = c['id'] as String;
      final input = c['input'] as Map<String, dynamic>;
      final expected = c['expect'] as Map<String, dynamic>;
      switch (c['op'] as String) {
        case 'negotiate':
          test('negotiate $id', () {
            expect(McpProtocol.negotiate(input['requested']),
                expected['version']);
          });
          break;
        case 'handle':
          test('handle $id', () async {
            final resp =
                await _newServer().handleJsonRpc(input['raw_body'] as String);
            if (expected.containsKey('empty')) {
              expect(resp, isEmpty, reason: '通知必须返回空串（传输层回 202）');
              return;
            }
            final actual = jsonDecode(resp) as Map<String, Object?>;
            expect(_stripMessage(actual), _stripMessage(expected));
          });
          break;
        default:
          fail('未知 op: ${c['op']}（向量集与执行器失配，须同步更新）');
      }
    }
  });
}
