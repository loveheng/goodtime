import 'package:flutter_test/flutter_test.dart';
import 'package:shiguang/util/schedule_day.dart';

/// 日期工具层边界测试（作息日换算 + 跨午夜几何；schedule-app.md §4/§8/§11）。
void main() {
  group('作息日换算（wake_time 切割，§11 拍板）', () {
    const wake = 450; // 07:30

    test('wake 前属前一日，wake 起属当日', () {
      expect(scheduleDayOf(DateTime(2026, 10, 5, 6, 59), wake), DateTime(2026, 10, 4));
      expect(scheduleDayOf(DateTime(2026, 10, 5, 7, 30), wake), DateTime(2026, 10, 5));
      expect(scheduleDayOf(DateTime(2026, 10, 5, 12, 0), wake), DateTime(2026, 10, 5));
    });

    test('凌晨收尾仍算昨天（§8 日切防夜猫子惩罚感）', () {
      expect(scheduleDayOf(DateTime(2026, 10, 5, 2, 15), wake), DateTime(2026, 10, 4));
    });

    test('跨年边界：1 月 1 日凌晨属上一年 12 月 31 日', () {
      expect(scheduleDayOf(DateTime(2027, 1, 1, 3, 0), 420), DateTime(2026, 12, 31));
    });

    test('wake=0 以物理 00:00 切割', () {
      expect(scheduleDayOf(DateTime(2026, 10, 5, 0, 0), 0), DateTime(2026, 10, 5));
      expect(scheduleDayOf(DateTime(2026, 10, 4, 23, 59), 0), DateTime(2026, 10, 4));
    });
  });

  group('ISO 日期换算', () {
    test('isoDate 月/日补零', () {
      expect(isoDate(DateTime(2026, 3, 5)), '2026-03-05');
      expect(isoDate(DateTime(2026, 10, 25)), '2026-10-25');
    });

    test('round trip 解析为当地零点', () {
      const d = '2026-10-05';
      final p = tryParseIsoDate(d)!;
      expect(isoDate(p), d);
      expect(p.hour, 0);
      expect(p.minute, 0);
    });

    test('拒绝非法日期与格式（不回绕）', () {
      expect(tryParseIsoDate('2026-02-30'), isNull, reason: '溢出日不静默回绕成 3 月');
      expect(tryParseIsoDate('2026-13-01'), isNull);
      expect(tryParseIsoDate('2026-2-5'), isNull, reason: '必须补零');
      expect(tryParseIsoDate('abc'), isNull);
    });
  });

  group('跨午夜几何（§4：按开始日归属，不拆段）', () {
    test('睡眠 23:30–07:30 跨午夜，时长 480 分钟', () {
      expect(crossesMidnight(1410, 450), isTrue);
      expect(spanMinutes(1410, 450), 480);
    });

    test('常规块与 24:00 收尾不跨午夜', () {
      expect(crossesMidnight(540, 560), isFalse);
      expect(spanMinutes(540, 560), 20);
      expect(crossesMidnight(0, 1440), isFalse);
      expect(spanMinutes(0, 1440), 1440);
    });
  });

  test('addDays 月/年边界', () {
    expect(isoDate(addDays(DateTime(2026, 10, 31), 1)), '2026-11-01');
    expect(isoDate(addDays(DateTime(2026, 12, 31), 1)), '2027-01-01');
    expect(isoDate(addDays(DateTime(2027, 1, 1), -1)), '2026-12-31');
  });
}
