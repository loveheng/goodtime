// P0 黄金向量基准执行器（Dart 侧）——时间基准（lib/util/schedule_day.dart 纯函数族）。
//
// 职责：把 test/golden/time_basis_vectors.json 跑通并锁定期望值（不依赖 Kotlin）。
// - 向量 = 跨实现契约数据（Dart 与 Kotlin 双侧消费，Kotlin 对拍在 P2 接入，
//   时钟基建以 java.time 复算同输入对拍——DST/时区两点挂账见 rules.dst_timezone_defer）；
// - 本执行器逐 case 对拍真实纯函数，凡实现漂移即红。
// 对拍口径：schedule_day/iso/parse/add_days 的 expected_iso 为源测试断言双记
// （执行器比实际输出 iso 与之一致——双保险）；epoch64 校验 ms > 2^31 往返无损。
// 向量语义与字段说明见 test/golden/README.md；向量文件为只读契约，实现漂移时
// 唯一出路是修实现，严禁改向量迁就实现（dev-loop 测试写保护）。
import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

import 'package:shiguang/util/schedule_day.dart';

Map<String, dynamic> _loadSet(String name) =>
    jsonDecode(File('test/golden/$name').readAsStringSync())
        as Map<String, dynamic>;

void main() {
  final set = _loadSet('time_basis_vectors.json');
  group('golden: time-basis', () {
    for (final c in (set['cases'] as List).cast<Map<String, dynamic>>()) {
      final id = c['id'] as String;
      final input = c['input'] as Map<String, dynamic>;
      final expected = c['expect'] as Map<String, dynamic>;
      switch (c['op'] as String) {
        case 'schedule_day':
          test('schedule_day $id', () {
            final r = scheduleDayOf(
              DateTime(input['y'] as int, input['mo'] as int, input['d'] as int,
                  input['h'] as int, input['mi'] as int),
              input['wake'] as int,
            );
            expect(isoDate(r), expected['iso']);
            // 双记一致性：向量期望值与源测试断言不得漂移
            expect(expected['iso'], expected['expected_iso']);
          });
          break;
        case 'iso_date':
          test('iso_date $id', () {
            expect(isoDate(DateTime(input['y'] as int, input['mo'] as int,
                input['d'] as int)), expected['iso']);
            expect(expected['iso'], expected['expected_iso']);
          });
          break;
        case 'parse_iso':
          test('parse_iso $id', () {
            final r = tryParseIsoDate(input['s'] as String);
            if (expected['reject'] == true) {
              expect(r, isNull, reason: '非法输入必须 null 不回绕');
              return;
            }
            expect(r, isNotNull);
            expect(isoDate(r!), expected['iso']);
            expect(r.hour, expected['hour']);
            expect(r.minute, expected['minute']);
            if (expected['expected_iso'] != null) {
              expect(expected['iso'], expected['expected_iso']);
            }
          });
          break;
        case 'add_days':
          test('add_days $id', () {
            final r = addDays(
                DateTime(input['y'] as int, input['mo'] as int, input['d'] as int),
                input['n'] as int);
            expect(isoDate(r), expected['iso']);
            expect(expected['iso'], expected['expected_iso']);
          });
          break;
        case 'cross_midnight':
          test('cross_midnight $id', () {
            final start = input['start'] as int;
            final end = input['end'] as int;
            expect(crossesMidnight(start, end), expected['crosses']);
            expect(spanMinutes(start, end), expected['span']);
            expect(expected['crosses'], expected['expected_crosses']);
            expect(expected['span'], expected['expected_span']);
          });
          break;
        case 'epoch64':
          test('epoch64 $id（64 位毫秒往返）', () {
            final dt = DateTime(input['y'] as int, input['mo'] as int,
                input['d'] as int);
            final ms = dt.millisecondsSinceEpoch;
            expect(ms, expected['ms']);
            expect(
              isoDate(DateTime.fromMillisecondsSinceEpoch(ms)),
              expected['back_iso'],
            );
            expect(ms, greaterThan(1 << 31), reason: '32 位秒溢出量级（64 位语义）');
          });
          break;
        default:
          fail('未知 op: ${c['op']}（向量集与执行器失配，须同步更新）');
      }
    }
  });
}
