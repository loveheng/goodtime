// P2 黄金向量基准执行器（Dart 侧）——命令解析契约（ScheduleCommand.fromJson）。
// 对拍口径：成功 case 断言 op/target_id/关键字段（expected_version/importance 等）；
// 拒绝 case 断言抛 ActionException（异常类非契约——message/hint 含锚文本即可）。
import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

import 'package:shiguang/action/commands.dart';

Map<String, dynamic> _loadSet(String name) =>
    jsonDecode(File('test/golden/$name').readAsStringSync()) as Map<String, dynamic>;

void main() {
  final set = _loadSet('command_parse_vectors.json');
  group('golden: command-parse', () {
    for (final c in (set['cases'] as List).cast<Map<String, dynamic>>()) {
      final id = c['id'] as String;
      final input = c['input'] as Map<String, dynamic>;
      final expected = c['expect'] as Map<String, dynamic>;
      test('command_parse $id', () {
        final json = (input['json'] as Map).cast<String, Object?>();
        if (expected['status'] == 'reject_argument') {
          try {
            ScheduleCommand.fromJson(json);
            fail('$id: 预期拒绝但成功');
          } on ActionException catch (e) {
            if (expected['error_contains'] != null) {
              expect(e.message, contains(expected['error_contains']), reason: id);
            }
            if (expected['error_hint_contains'] != null) {
              expect(e.hint, contains(expected['error_hint_contains']), reason: id);
            }
          }
          return;
        }
        final cmd = ScheduleCommand.fromJson(json);
        expect(cmd.op, expected['op'], reason: id);
        if (expected.containsKey('target_id')) {
          expect(cmd.targetId, expected['target_id'], reason: id);
        }
        if (expected.containsKey('expected_version')) {
          expect(cmd.expectedVersion, expected['expected_version'], reason: id);
        }
        if (cmd is QuickCaptureCommand) {
          expect(cmd.title, expected['title'], reason: id);
          expect(cmd.importance, expected['importance'], reason: id);
          expect(cmd.deadline, expected['deadline'], reason: id);
        }
        if (cmd is UpdatePlanCommand) {
          expect(cmd.title, expected['title'], reason: id);
          if (expected.containsKey('importance')) {
            expect(cmd.importance, expected['importance'], reason: id);
          }
          if (expected.containsKey('archived')) {
            expect(cmd.archived, expected['archived'], reason: id);
          }
        }
        if (cmd is AdjustBlocksCommand) {
          expect(cmd.action, expected['action'], reason: id);
          if (expected.containsKey('start_min')) {
            expect(cmd.startMin, expected['start_min'], reason: id);
          }
          if (expected.containsKey('end_min')) {
            expect(cmd.endMin, expected['end_min'], reason: id);
          }
          if (expected.containsKey('pinned')) {
            expect(cmd.pinned, expected['pinned'], reason: id);
          }
        }
        if (cmd is UpsertBackgroundCommand) {
          expect(cmd.scope, expected['scope'], reason: id);
          expect(cmd.content, expected['content'], reason: id);
        }
        if (cmd is ShiftBlockCommand) {
          expect(cmd.minutes, expected['minutes'], reason: id);
        }
      });
    }
  });
}
