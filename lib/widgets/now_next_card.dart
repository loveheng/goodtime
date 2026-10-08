import 'dart:async';

import 'package:flutter/material.dart';

import '../action/queries.dart';
import '../app_services.dart';
import '../models/schedule_block.dart';
import '../theme/tokens.dart';
import '../util/schedule_day.dart';

/// 当前/下一个任务卡片（ui-spec §3 派生 glance）：实时显示此刻在进行的事与
/// 紧接着要开始的事。数据经 [AppServices.repo] 监听（台账改写即时刷新）+ 每分钟
/// 墙钟 tick（当前块到点→下一个自动顶上）。核心判定走纯函数 [pickNowAndNext]，
/// 与 UI 解耦、可单测。

/// 终态：不再作为「进行中/待办」参与当前/下一个判定（§4 状态机七态）。
const Set<String> _terminalStatuses = {
  ScheduleBlock.statusDone,
  ScheduleBlock.statusSkipped,
  ScheduleBlock.statusMissed,
  ScheduleBlock.statusArchived,
  ScheduleBlock.statusMelted,
};

bool _isPending(ScheduleBlock b) => !_terminalStatuses.contains(b.status);

/// 块时间窗是否罩住 [nowMin]（含跨午夜：23:30–07:30 在 06:00 仍为进行中）。
bool containsMinute(ScheduleBlock b, int nowMin) {
  if (crossesMidnight(b.startMin, b.endMin)) {
    return nowMin >= b.startMin || nowMin < b.endMin;
  }
  return nowMin >= b.startMin && nowMin < b.endMin;
}

/// 纯函数：从候选块中挑「当前」与「下一个」。
///
/// 约定（功能契约，单测据此覆盖）：
/// - 仅看未终态（pending）块；
/// - current = 时间窗罩住 [nowMin] 的块；多个重叠时取**最晚开始**者（最近切到的）；
/// - next    = 时间窗不罩 now、且 startMin > nowMin 的块中**最早开始**者；
///   （跨午夜溢出块若罩住 now 算 current，绝不会误判成 next）
/// - 任一侧缺失返回 null。
({ScheduleBlock? current, ScheduleBlock? next}) pickNowAndNext(
    List<ScheduleBlock> blocks, int nowMin) {
  final pending = blocks.where(_isPending).toList()
    ..sort((a, b) => a.startMin.compareTo(b.startMin));

  ScheduleBlock? current;
  for (final b in pending) {
    if (containsMinute(b, nowMin)) current = b; // 排序后后者即更晚开始
  }

  ScheduleBlock? next;
  for (final b in pending) {
    if (containsMinute(b, nowMin)) continue; // 进行中不算「下一个」
    if (b.startMin > nowMin) {
      next = b; // pending 已按 startMin 升序，首命中即最早
      break;
    }
  }
  return (current: current, next: next);
}

/// 卡片展示快照（已解析 label，便于纯渲染）。
class NowNextSnapshot {
  const NowNextSnapshot({this.current, this.next, this.currentLabel, this.nextLabel});

  final ScheduleBlock? current;
  final ScheduleBlock? next;
  final String? currentLabel;
  final String? nextLabel;
}

/// 当前/下一个任务卡片：自动刷新（repo 改写 + 每分钟 tick）。
class NowNextCard extends StatefulWidget {
  const NowNextCard({super.key});

  @override
  State<NowNextCard> createState() => _NowNextCardState();
}

class _NowNextCardState extends State<NowNextCard> {
  int _minuteKey = 0;
  Timer? _tick;

  @override
  void initState() {
    super.initState();
    // 墙钟推进：当前块到时→下一个顶上，需周期性重判（不依赖 repo 写入）。
    _tick = Timer.periodic(const Duration(minutes: 1), (_) {
      if (mounted) setState(() => _minuteKey++);
    });
  }

  @override
  void dispose() {
    _tick?.cancel();
    super.dispose();
  }

  Future<NowNextSnapshot> _compute() async {
    final repo = AppServices.repo;
    final settings = await ScheduleQueries(repo).getSettings();
    final wake = settings['wake_time'] as int? ?? 420;
    final now = DateTime.now();
    final today = scheduleDayOf(now, wake);
    final todayIso = isoDate(today);
    final yIso = isoDate(addDays(today, -1));
    // 取昨日+今日：承载跨午夜溢出块（昨夜起、今晨仍在进行）。
    final range = await repo.blocksInRange(yIso, todayIso);
    final nowMin = minutesOfDay(now);
    final blocks = range.where((b) {
      if (b.date == todayIso) return true;
      // 昨夜跨午夜块：仅当其仍罩住 now（今晨尚未结束）才算「今天进行中」，
      // 否则属过去日程，绝不可顶成「下一个」。
      if (b.date == yIso && crossesMidnight(b.startMin, b.endMin)) {
        return containsMinute(b, nowMin);
      }
      return false;
    }).toList();
    final picked = pickNowAndNext(blocks, nowMin);

    Future<String?> labelOf(ScheduleBlock? b) async {
      if (b == null) return null;
      if (b.label != null && b.label!.isNotEmpty) return b.label;
      if (b.planId != null) {
        final p = await repo.planById(b.planId!);
        if (p?.title.isNotEmpty ?? false) return p!.title;
      }
      return '(未命名)';
    }

    return NowNextSnapshot(
      current: picked.current,
      next: picked.next,
      currentLabel: await labelOf(picked.current),
      nextLabel: await labelOf(picked.next),
    );
  }

  @override
  Widget build(BuildContext context) {
    // repo 改写 → 代次自增 → ListenableBuilder 重入；分钟 tick 由 _minuteKey 驱动。
    return ListenableBuilder(
      listenable: AppServices.repo,
      builder: (context, _) => FutureBuilder<NowNextSnapshot>(
        key: ValueKey('${AppServices.repo.revision}:$_minuteKey'),
        future: _compute(),
        builder: (context, snap) {
          if (snap.connectionState != ConnectionState.done) {
            return const SizedBox.shrink();
          }
          final s = snap.data!;
          if (s.current == null && s.next == null) return const SizedBox.shrink();
          return _NowNextBody(snapshot: s);
        },
      ),
    );
  }
}

class _NowNextBody extends StatelessWidget {
  const _NowNextBody({required this.snapshot});

  final NowNextSnapshot snapshot;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Card(
      margin: const EdgeInsets.fromLTRB(16, 4, 16, 4),
      color: scheme.surfaceContainerHighest,
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          children: [
            if (snapshot.current != null)
              _Row(
                leading: '进行中',
                tint: scheme.primary,
                label: snapshot.currentLabel ?? '(未命名)',
                time:
                    '${clockOf(snapshot.current!.startMin)}–${clockOf(snapshot.current!.endMin)}',
              ),
            if (snapshot.current != null && snapshot.next != null)
              const SizedBox(height: 8),
            if (snapshot.next != null)
              _Row(
                leading: '下一个',
                tint: StColors.textSecondary,
                label: snapshot.nextLabel ?? '(未命名)',
                time: clockOf(snapshot.next!.startMin),
              ),
          ],
        ),
      ),
    );
  }
}

class _Row extends StatelessWidget {
  const _Row({
    required this.leading,
    required this.tint,
    required this.label,
    required this.time,
  });

  final String leading;
  final Color tint;
  final String label;
  final String time;

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        Container(
          padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
          decoration: BoxDecoration(
            color: tint.withValues(alpha: 0.16),
            borderRadius: BorderRadius.circular(StScale.radiusCapsule),
          ),
          child: Text(leading,
              style: TextStyle(fontSize: 12, color: tint, fontWeight: FontWeight.w600)),
        ),
        const SizedBox(width: 10),
        Expanded(
          child: Text(label,
              style: Theme.of(context)
                  .textTheme
                  .bodyMedium
                  ?.copyWith(color: StColors.textPrimary),
              maxLines: 1,
              overflow: TextOverflow.ellipsis),
        ),
        const SizedBox(width: 8),
        Text(time,
            style: Theme.of(context)
                .textTheme
                .bodySmall
                ?.copyWith(color: StColors.textSecondary)),
      ],
    );
  }
}
