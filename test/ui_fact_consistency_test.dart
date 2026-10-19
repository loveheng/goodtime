import 'package:flutter_test/flutter_test.dart';
import 'package:shiguang/models/artifact.dart';
import 'package:shiguang/models/schedule_block.dart';
import 'package:shiguang/ui/fact_sheet.dart';

/// 一致性比对选锚规则（fact-user-relay-draft.md §9.5-5，2026-10-08 拍板）：
/// **重叠度贪心**——多 span（往返票 / 入住+预约段）只比对与块重叠最大的那一条；
/// 均不重叠时取起始最早者。派生不入库，纯函数单测。
/// 块-凭证关联已反转至 `blocks.artifact_id`（§9.5-6）：调用方经块侧配对传入，
/// 凭证侧无 blockId，块 null 即不比对。
Artifact _fact(List<Map<String, Object?>> anchors) => Artifact(
      category: Artifact.categoryTransit,
      sourceKind: Artifact.sourceBooking,
      state: Artifact.stateStructured,
      origin: Artifact.originShared,
      title: '大理→丽江 动车 D8724',
      payload: {'time_anchors': anchors},
    );

ScheduleBlock _block(String date, int s, int e) => ScheduleBlock(
      date: date,
      startMin: s,
      endMin: e,
      label: '乘车',
      source: 'ai',
      pinned: true,
    );

Map<String, Object?> _span(String date, int s, int e, {String? endDate}) {
  final m = <String, Object?>{
    'role': 'departure',
    'kind': 'span',
    'date': date,
    'start_min': s,
    'end_min': e,
  };
  if (endDate != null) m['end_date'] = endDate;
  return m;
}

void main() {
  group('单 span', () {
    test('与块起止一致 → 不报琥珀条', () {
      final a = _fact([_span('2026-10-20', 540, 680)]);
      expect(factInconsistentWithBlock(a, _block('2026-10-20', 540, 680)), isFalse);
    });

    test('改签后只更 fact 未挪块 → 报琥珀条', () {
      final a = _fact([_span('2026-10-20', 570, 710)]);
      expect(factInconsistentWithBlock(a, _block('2026-10-20', 540, 680)), isTrue);
    });

    test('无关联块 / 块悬空 → 不自检', () {
      final a = _fact([_span('2026-10-20', 540, 680)]);
      expect(factInconsistentWithBlock(a, null), isFalse);
      expect(factInconsistentWithBlock(_fact([_span('2026-10-20', 540, 680)]),
          _block('2026-10-20', 540, 680)), isFalse, reason: '块 null（悬空）不比对');
    });
  });

  group('多 span 贪心选锚', () {
    test('去程与块完全重叠、回程不重叠 → 只比去程，不误报', () {
      final a = _fact(
        [
          _span('2026-10-20', 540, 680), // 去程：与块一致
          _span('2026-10-24', 900, 1040), // 回程：另一天，与块不重叠
        ],
      );
      expect(factInconsistentWithBlock(a, _block('2026-10-20', 540, 680)), isFalse,
          reason: '贪心选中重叠最大的去程 span（旧实现：任一 span 命中即报）');
    });

    test('重叠最大者一致、重叠小者不一致 → 不误报（贪心命中前者）', () {
      final a = _fact(
        [
          _span('2026-10-20', 540, 680), // 重叠 140min（最大）且与块一致
          _span('2026-10-20', 600, 620), // 重叠 20min，不一致但不被选中
        ],
      );
      expect(factInconsistentWithBlock(a, _block('2026-10-20', 540, 680)), isFalse);
    });

    test('重叠最大者与块不一致 → 报（不因重叠小者一致而掩盖）', () {
      final a = _fact(
        [
          _span('2026-10-20', 540, 700), // 重叠 140min（最大），end 与块不符
          _span('2026-10-20', 600, 620), // 重叠 20min
        ],
      );
      expect(factInconsistentWithBlock(a, _block('2026-10-20', 540, 680)), isTrue);
    });

    test('均不重叠 → 取起始最早者比对', () {
      final a = _fact(
        [
          _span('2026-10-22', 540, 680), // 起始最早
          _span('2026-10-24', 540, 680),
        ],
      );
      expect(factInconsistentWithBlock(a, _block('2026-10-20', 540, 680)), isTrue);
    });
  });

  group('跨午夜折算', () {
    test('红眼块 22:30→次日 06:00 与跨日 span 一致 → 不报', () {
      final a = _fact(
        [_span('2026-10-20', 1350, 360, endDate: '2026-10-21')],
      );
      expect(
        factInconsistentWithBlock(a, _block('2026-10-20', 1350, 360)),
        isFalse,
        reason: '两侧统一折算绝对时间轴（§1.3，严禁同日直比分钟）',
      );
    });

    test('跨日 span end_date 缺失但 end<start → 按溢出次日折算', () {
      final a = _fact([_span('2026-10-20', 1350, 360)]);
      expect(factInconsistentWithBlock(a, _block('2026-10-20', 1350, 360)), isFalse);
    });
  });
}
