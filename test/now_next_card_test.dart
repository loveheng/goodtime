import 'package:flutter_test/flutter_test.dart';

import 'package:shiguang/models/schedule_block.dart';
import 'package:shiguang/widgets/now_next_card.dart';

ScheduleBlock block(String id, int start, int end,
    {String status = ScheduleBlock.statusProposed, String? planId}) {
  return ScheduleBlock(
    id: id,
    date: '2026-10-08',
    startMin: start,
    endMin: end,
    planId: planId,
    source: ScheduleBlock.sourceHuman,
    status: status,
  );
}

void main() {
  group('pickNowAndNext · 功能评估', () {
    test('无块 → 两者皆空', () {
      final r = pickNowAndNext([], 600);
      expect(r.current, isNull);
      expect(r.next, isNull);
    });

    test('now 早于所有块 → 仅 next，无 current', () {
      final blocks = [
        block('a', 600, 660), // 10:00–11:00
        block('b', 720, 780), // 12:00–13:00
      ];
      final r = pickNowAndNext(blocks, 540); // 09:00
      expect(r.current, isNull);
      expect(r.next?.id, 'a');
    });

    test('now 晚于所有块 → 两者皆空', () {
      final blocks = [
        block('a', 600, 660),
        block('b', 720, 780),
      ];
      final r = pickNowAndNext(blocks, 800); // 13:20
      expect(r.current, isNull);
      expect(r.next, isNull);
    });

    test('now 落在某块窗内 → current 命中，next 为下一块', () {
      final blocks = [
        block('a', 600, 660), // 10:00–11:00
        block('b', 720, 780), // 12:00–13:00
      ];
      final r = pickNowAndNext(blocks, 630); // 10:30
      expect(r.current?.id, 'a');
      expect(r.next?.id, 'b');
    });

    test('多个块重叠罩住 now → current 取最晚开始者', () {
      final blocks = [
        block('a', 600, 660), // 10:00–11:00
        block('b', 630, 690), // 10:30–11:30（与 a 重叠至 11:00）
        block('c', 720, 780), // 12:00–13:00
      ];
      final r = pickNowAndNext(blocks, 645); // 10:45，a、b 皆罩住
      expect(r.current?.id, 'b'); // 重叠取最晚开始（b）
      expect(r.next?.id, 'c');
    });

    test('终态块不参与判定（done/skipped/missed/archived/melted）', () {
      final blocks = [
        block('done', 600, 660, status: ScheduleBlock.statusDone),
        block('skipped', 660, 720, status: ScheduleBlock.statusSkipped),
        block('missed', 720, 780, status: ScheduleBlock.statusMissed),
        block('archived', 780, 840, status: ScheduleBlock.statusArchived),
        block('melted', 840, 900, status: ScheduleBlock.statusMelted),
        block('live', 900, 960),
      ];
      final r = pickNowAndNext(blocks, 630);
      expect(r.current, isNull); // 所有罩住 now 的都已终态
      expect(r.next?.id, 'live');
    });

    test('confirmed 块视为进行中（非终态）', () {
      final blocks = [block('c', 600, 660, status: ScheduleBlock.statusConfirmed)];
      final r = pickNowAndNext(blocks, 630);
      expect(r.current?.id, 'c');
    });

    test('跨午夜块（23:30–07:30）在凌晨罩住 now → current，且不误判为 next', () {
      final sleep = block('s', 1410, 450); // 23:30–07:30
      final morning = block('m', 480, 540); // 08:00–09:00
      final r = pickNowAndNext([sleep, morning], 360); // 06:00
      expect(r.current?.id, 's');
      expect(r.next?.id, 'm');
    });

    test('跨午夜块已结束（now 在 end 之后）→ 不算 current，但属今夜待开始 → 是 next', () {
      final sleep = block('s', 1410, 450); // 今夜 23:30–明早 07:30
      final r = pickNowAndNext([sleep], 480); // 08:00，睡眠已结束
      expect(r.current, isNull);
      expect(r.next?.id, 's'); // 今夜 23:30 仍需开始 → 下一个
    });

    test('当前块结束后、下一块尚未开始（存在空档）→ next 顶上', () {
      final blocks = [
        block('a', 600, 660), // 10:00–11:00
        block('b', 720, 780), // 12:00–13:00（与 a 留 1h 空档）
      ];
      final r = pickNowAndNext(blocks, 660); // 恰好 11:00，a 已结束、b 未开始
      expect(r.current, isNull);
      expect(r.next?.id, 'b');
    });

    test('空窗日（全部块均已终态）→ 两者皆空', () {
      final blocks = [block('a', 600, 660, status: ScheduleBlock.statusDone)];
      final r = pickNowAndNext(blocks, 630);
      expect(r.current, isNull);
      expect(r.next, isNull);
    });
  });
}
