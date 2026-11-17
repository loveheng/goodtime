// P0/P2 黄金向量基准执行器（Dart 侧）——领域模型 JSON 契约（试点：Artifact）。
//
// 职责：把 test/golden/model_json_vectors.json 跑通并锁定期望值（不依赖 Kotlin）。
// - 向量 = 跨实现契约数据（Kotlin 对拍 = :core:model GoldenModelJsonTest，已绿）；
// - 本执行器逐 case 对拍真实模型实现，凡实现漂移即红；
// - 对拍口径：**JSON 串逐字（键序=序列化契约）**——actual toMap 经 jsonEncode（Dart
//   Map 保序）与文件 expect 的再序列化串比对；payload/attachments 为 TEXT 串形态。
// 向量语义与字段说明见 test/golden/README.md；向量文件为只读契约，实现漂移时
// 唯一出路是修实现，严禁改向量迁就实现（dev-loop 测试写保护）。
import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

import 'package:shiguang/models/artifact.dart';

Map<String, dynamic> _loadSet(String name) =>
    jsonDecode(File('test/golden/$name').readAsStringSync()) as Map<String, dynamic>;

String _encode(Object? o) => jsonEncode(o);

void main() {
  final set = _loadSet('model_json_vectors.json');
  group('golden: model-json-artifact', () {
    for (final c in (set['cases'] as List).cast<Map<String, dynamic>>()) {
      final id = c['id'] as String;
      final input = c['input'] as Map<String, dynamic>;
      final expected = c['expect'] as Map<String, dynamic>;
      switch (c['op'] as String) {
        case 'payload_encode':
          test('payload_encode $id（v 恒首位）', () {
            final payload =
                (input['payload'] as Map).cast<String, Object?>();
            // encodePayload 已返回 JSON 串——直接字符串比（勿再编码，双转义即错）。
            expect(Artifact.encodePayload(payload), expected['json']);
          });
          break;
        case 'payload_decode':
          test('payload_decode $id（读侧防御不抛）', () {
            final raw = input['raw'] as String?;
            final got = Artifact.decodePayload(raw);
            expect(got, (expected['payload'] as Map).cast<String, Object?>());
          });
          break;
        case 'payload_with_refs':
          test('payload_with_refs $id（planId 三态）', () {
            final payload =
                (input['payload'] as Map).cast<String, Object?>();
            // 三态：input 无 plan_id 键 = 保持（缺省不传）；显式 null = 移除；有值 = 置键。
            final got = input.containsKey('plan_id')
                ? Artifact.payloadWithRefs(payload,
                    planId: input['plan_id'] as String?)
                : Artifact.payloadWithRefs(payload);
            expect(got, (expected['payload'] as Map).cast<String, Object?>());
          });
          break;
        case 'artifact_to_map':
          test('artifact_to_map $id（键序=序列化契约逐字）', () {
            final f = (input['fields'] as Map).cast<String, Object?>();
            final entity = Artifact(
              id: f['id'] as String?,
              category: f['category'] as String,
              sourceKind: f['source_kind'] as String,
              state: f['state'] as String,
              origin: f['origin'] as String,
              title: f['title'] as String,
              badge: f['badge'] as String?,
              payload: (f['payload'] as Map).cast<String, Object?>(),
              attachments: (f['attachments'] as List?) ?? const [],
              capturedBy: f['captured_by'] as String? ?? Artifact.capturedByMe,
              planId: f['plan_id'] as String?,
              createdAt: f['created_at'] as int?,
              updatedAt: f['updated_at'] as int?,
              version: f['version'] as int? ?? 0,
            );
            expect(_encode(entity.toMap()),
                _encode((expected['map'] as Map).cast<String, Object?>()));
          });
          break;
        case 'artifact_from_map':
          test('artifact_from_map $id（对拍 Dart 产出钉样；输入=DB 行 TEXT 串形态）', () {
            final entity = Artifact.fromMap(
                (input['map'] as Map).cast<String, Object?>());
            // 对拍 Dart 产出（expected.map）——fromMap 缺省补全后 toMap 比输入多
            // 缺省键（badge:null/captured_by/version）是正常语义，勿与输入比幂等。
            expect(_encode(entity.toMap()),
                _encode((expected['map'] as Map).cast<String, Object?>()));
            if (expected.containsKey('title')) {
              expect(entity.title, expected['title'], reason: id);
            }
            if (expected.containsKey('payload')) {
              expect(entity.payload,
                  (expected['payload'] as Map).cast<String, Object?>(),
                  reason: id);
            }
            if (expected.containsKey('badge')) {
              expect(entity.badge, expected['badge'], reason: id);
            }
            if (expected.containsKey('captured_by')) {
              expect(entity.capturedBy, expected['captured_by'], reason: id);
            }
            if (expected.containsKey('version')) {
              expect(entity.version, expected['version'], reason: id);
            }
            if (expected.containsKey('plan_id')) {
              expect(entity.planId, expected['plan_id'], reason: id);
            }
          });
          break;
        default:
          fail('未知 op: ${c['op']}（向量集与执行器失配，须同步更新）');
      }
    }
  });
}
