import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../action/command_handler.dart';
import '../action/commands.dart';
import '../action/queries.dart';
import '../app_services.dart';
import '../data/repository.dart';
import '../models/plan.dart';
import '../models/schedule_block.dart';
import '../theme/tokens.dart';
import '../util/schedule_day.dart';

/// 清单页 M1（ui-spec §5 裁剪）：三分区单页滚动——今天（今日有块的 plan）/
/// 当下推进中（有未来块）/ 待安排（四象限分组、空组隐藏、Q4=愿望池）。
/// 卡片点开编辑，长按归档/删除；「待确认 N 项」角标 + 🔥 今日核心标记。
class PlansPage extends StatelessWidget {
  const PlansPage({super.key});

  Repository get _repo => AppServices.repo;

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: _repo,
      builder: (context, _) => FutureBuilder<_PlansView>(
        future: _load(),
        builder: (context, snap) {
          if (snap.connectionState != ConnectionState.done) {
            return const Center(child: CircularProgressIndicator());
          }
          final view = snap.data;
          if (view == null) return const SizedBox.shrink();
          return _body(context, view);
        },
      ),
    );
  }

  Future<_PlansView> _load() async {
    final settings = await ScheduleQueries(_repo).getSettings();
    final wake = settings['wake_time'] as int? ?? 0;
    final today = scheduleDayOf(DateTime.now(), wake);
    final todayIso = isoDate(today);
    final plans = await _repo.listPlans();

    final todayBlocks = await _repo.blocksOnDate(todayIso);
    final planOfBlock = <String, String>{}; // block planId -> date
    final proposedByPlan = <String, int>{};
    for (final b in todayBlocks) {
      if (b.planId != null) planOfBlock[b.planId!] = todayIso;
      if (b.status == ScheduleBlock.statusProposed && b.planId != null) {
        proposedByPlan[b.planId!] = (proposedByPlan[b.planId!] ?? 0) + 1;
      }
    }
    // 未来块（今天起 30 天，单条范围 SQL 派生查询）
    final futureBlocks = await _repo.blocksInRange(todayIso, isoDate(addDays(today, 30)));
    for (final b in futureBlocks) {
      if (b.planId == null) continue;
      if (b.date != todayIso) planOfBlock.putIfAbsent(b.planId!, () => b.date);
      if (b.status == ScheduleBlock.statusProposed) {
        proposedByPlan[b.planId!] = (proposedByPlan[b.planId!] ?? 0) + 1;
      }
    }
    final sparkPlanIds = {
      for (final b in todayBlocks)
        if (b.isDaySpark && b.planId != null) b.planId!,
    };

    final todayPlans = <Plan>[];
    final inProgress = <Plan>[];
    final waiting = <Plan>[];
    for (final p in plans) {
      final blockDate = planOfBlock[p.id!];
      if (blockDate == todayIso) {
        todayPlans.add(p);
      } else if (blockDate != null) {
        inProgress.add(p);
      } else {
        waiting.add(p);
      }
    }
    return _PlansView(
      today: todayPlans,
      inProgress: inProgress,
      waiting: waiting,
      proposedByPlan: proposedByPlan,
      sparkPlanIds: sparkPlanIds,
      todayIso: todayIso,
    );
  }

  Widget _body(BuildContext context, _PlansView view) {
    final empty = view.today.isEmpty && view.inProgress.isEmpty && view.waiting.isEmpty;
    if (empty) {
      return Center(
        child: Text('池子空空的——想到什么就记下来，30 秒的事',
            style: Theme.of(context).textTheme.bodySmall?.copyWith(color: StColors.textSecondary)),
      );
    }
    return SingleChildScrollView(
      padding: const EdgeInsets.fromLTRB(16, 8, 16, StScale.fabClearanceDp),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          if (view.today.isNotEmpty) ...[
            _sectionTitle(context, '今天'),
            for (final p in view.today)
              _PlanCard(plan: p, proposed: view.proposedByPlan[p.id] ?? 0, spark: view.sparkPlanIds.contains(p.id)),
          ],
          if (view.inProgress.isNotEmpty) ...[
            _sectionTitle(context, '当下推进中'),
            for (final p in view.inProgress)
              _PlanCard(plan: p, proposed: view.proposedByPlan[p.id] ?? 0, spark: false),
          ],
          if (view.waiting.isNotEmpty) ...[
            _sectionTitle(context, '待安排'),
            ..._quadrants(context, view.waiting),
          ],
        ],
      ),
    );
  }

  /// 四象限分组（§5）：Q1 重要×紧急 / Q2 重要 / Q3 紧急 / Q4 愿望池，空组隐藏。
  List<Widget> _quadrants(BuildContext context, List<Plan> waiting) {
    const urgentDays = 3;
    final now = DateTime.now();
    bool urgent(Plan p) =>
        p.deadline != null &&
        tryParseIsoDate(p.deadline!) is DateTime &&
        tryParseIsoDate(p.deadline!)!.isBefore(now.add(const Duration(days: urgentDays + 1)));
    final groups = {
      '重要 × 紧急': waiting.where((p) => p.importance && urgent(p)).toList(),
      '重要': waiting.where((p) => p.importance && !urgent(p)).toList(),
      '紧急': waiting.where((p) => !p.importance && urgent(p)).toList(),
      '愿望池': waiting.where((p) => !p.importance && !urgent(p)).toList(),
    };
    final widgets = <Widget>[];
    for (final e in groups.entries) {
      if (e.value.isEmpty) continue;
      widgets.add(_groupTitle(context, e.key, e.value.length));
      for (final p in e.value) {
        widgets.add(_PlanCard(plan: p, proposed: 0, spark: false));
      }
    }
    return widgets;
  }

  Widget _sectionTitle(BuildContext context, String text) => Padding(
        padding: const EdgeInsets.only(top: 12, bottom: 4),
        child: Text(text, style: Theme.of(context).textTheme.titleSmall),
      );

  Widget _groupTitle(BuildContext context, String text, int count) => Padding(
        padding: const EdgeInsets.only(top: 8, bottom: 4),
        child: Text('$text · $count',
            style: Theme.of(context).textTheme.labelMedium?.copyWith(color: StColors.textSecondary)),
      );
}

class _PlansView {
  _PlansView({
    required this.today,
    required this.inProgress,
    required this.waiting,
    required this.proposedByPlan,
    required this.sparkPlanIds,
    required this.todayIso,
  });

  final List<Plan> today;
  final List<Plan> inProgress;
  final List<Plan> waiting;
  final Map<String, int> proposedByPlan;
  final Set<String> sparkPlanIds;
  final String todayIso;
}

class _PlanCard extends StatelessWidget {
  const _PlanCard({required this.plan, required this.proposed, required this.spark});

  final Plan plan;
  final int proposed;
  final bool spark;

  Repository get _repo => AppServices.repo;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Dismissible(
      key: Key('plan_swipe_${plan.id}'),
      direction: DismissDirection.endToStart, // 左滑（右滑挂账待「排期」提名流）
      dismissThresholds: const {
        DismissDirection.endToStart: StGesture.swapFraction, // 35% 卡点族
      },
      background: _swipeBackground(
          Alignment.centerRight, const EdgeInsets.only(right: 20)),
      onDismissed: (_) => _archiveWithUndo(context),
      child: Card(
      margin: const EdgeInsets.only(bottom: 8),
      child: InkWell(
        borderRadius: BorderRadius.circular(StScale.radiusCard),
        onTap: () => _editSheet(context),
        onLongPress: () => _actionSheet(context),
        child: Padding(
          padding: const EdgeInsets.all(12),
          child: Row(
            children: [
              // 象限色点：重要=主色 / 紧急=琥珀 / 两者=深主色 / 愿望池=灰
              Container(
                width: 10,
                height: 10,
                decoration: BoxDecoration(
                  color: _quadrantColor(scheme),
                  shape: BoxShape.circle,
                ),
              ),
              const SizedBox(width: 10),
              Expanded(
                child: Text(plan.title, style: Theme.of(context).textTheme.bodyLarge),
              ),
              if (spark) const Text('🔥', style: TextStyle(fontSize: 12)),
              if (proposed > 0)
                Container(
                  margin: const EdgeInsets.only(left: 8),
                  padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
                  decoration: BoxDecoration(
                    color: scheme.primaryContainer,
                    borderRadius: BorderRadius.circular(StScale.radiusCapsule),
                  ),
                  child: Text('待确认 $proposed 项',
                      style: TextStyle(fontSize: 11, color: scheme.onPrimaryContainer)),
                ),
              if (plan.deadline != null)
                Padding(
                  padding: const EdgeInsets.only(left: 8),
                  child: Text(plan.deadline!,
                      style: TextStyle(fontSize: 11, color: StColors.textSecondary)),
                ),
            ],
          ),
        ),
      ),
      ),
    );
  }

  /// 左滑背景（中性灰，「归档」，绝不显红——减震器语义）。
  Widget _swipeBackground(Alignment alignment, EdgeInsets padding) {
    return Container(
      alignment: alignment,
      padding: padding,
      margin: const EdgeInsets.only(bottom: 8),
      decoration: BoxDecoration(
        color: StColors.fixedSlotBg,
        borderRadius: BorderRadius.circular(StScale.radiusCard),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(Icons.archive_outlined, color: StColors.textSecondary),
          const SizedBox(width: 4),
          Text('归档', style: TextStyle(color: StColors.textSecondary)),
        ],
      ),
    );
  }

  /// 左滑归档 + SnackBar 撤销（归档在 UI 上暂不可逆，必附撤销——减震器）。
  /// 归档会触发清单重建并卸载本卡片，messenger 须在命令执行前捕获（根级、跨卸载存活）。
  Future<void> _archiveWithUndo(BuildContext context) async {
    unawaited(HapticFeedback.selectionClick());
    final messenger = ScaffoldMessenger.of(context);
    final result = await CommandHandler(_repo).execute(
      UpdatePlanCommand(
          id: plan.id!, archived: true, expectedVersion: plan.version),
    );
    final fresh = result.snapshot?['version'] as int? ?? plan.version;
    messenger.showSnackBar(SnackBar(
      // 浮动+FAB 净空：撤销钮不能被右下角快记 FAB 遮挡（ui-spec §3 层叠规则）
      behavior: SnackBarBehavior.floating,
      margin:
          const EdgeInsets.fromLTRB(16, 0, 16, StScale.fabClearanceDp),
      content: Text('已归档「${plan.title}」'),
      action: SnackBarAction(
        label: '撤销',
        onPressed: () => CommandHandler(_repo).execute(
          UpdatePlanCommand(id: plan.id!, archived: false, expectedVersion: fresh),
        ),
      ),
    ));
  }

  Color _quadrantColor(ColorScheme scheme) {
    if (plan.importance && plan.deadline != null) return scheme.primary;
    if (plan.importance) return scheme.primary.withValues(alpha: 0.5);
    if (plan.deadline != null) return const Color(0xFFFFB300); // sparkStroke 琥珀
    return StColors.safelineOff;
  }

  Future<void> _editSheet(BuildContext context) {
    final title = TextEditingController(text: plan.title);
    final spec = TextEditingController(text: plan.spec ?? '');
    final notes = TextEditingController(text: plan.notes ?? '');
    return showModalBottomSheet<void>(
      context: context,
      showDragHandle: true,
      isScrollControlled: true,
      builder: (sheetContext) => SafeArea(
        child: Padding(
          padding: EdgeInsets.fromLTRB(
              20, 0, 20, 20 + MediaQuery.of(sheetContext).viewInsets.bottom),
          child: StatefulBuilder(
            builder: (context, setState) => Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Text('编辑计划', style: Theme.of(sheetContext).textTheme.titleLarge),
                const SizedBox(height: 12),
                TextField(
                  controller: title,
                  decoration: const InputDecoration(labelText: '标题', border: OutlineInputBorder()),
                ),
                const SizedBox(height: 12),
                TextField(
                  controller: spec,
                  maxLines: 3,
                  decoration: const InputDecoration(
                      labelText: '精确描述（spec）', border: OutlineInputBorder()),
                ),
                const SizedBox(height: 12),
                TextField(
                  controller: notes,
                  maxLines: 2,
                  decoration: const InputDecoration(
                      labelText: '备注（首行写「马上开始」的第一步）',
                      border: OutlineInputBorder()),
                ),
                const SizedBox(height: 12),
                FilledButton(
                  onPressed: () async {
                    await CommandHandler(_repo).execute(
                      UpdatePlanCommand(
                        id: plan.id!,
                        title: title.text.trim().isEmpty ? null : title.text.trim(),
                        spec: spec.text.trim().isEmpty ? null : spec.text.trim(),
                        notes: notes.text.trim().isEmpty ? null : notes.text.trim(),
                        expectedVersion: plan.version,
                      ),
                    );
                    if (sheetContext.mounted) Navigator.of(sheetContext).pop();
                  },
                  child: const Text('保存'),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  Future<void> _actionSheet(BuildContext context) {
    return showModalBottomSheet<void>(
      context: context,
      showDragHandle: true,
      builder: (sheetContext) => SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            ListTile(
              leading: const Icon(Icons.inventory_2_outlined),
              title: const Text('归档'),
              onTap: () async {
                await CommandHandler(_repo).execute(
                  UpdatePlanCommand(id: plan.id!, archived: true, expectedVersion: plan.version),
                );
                if (sheetContext.mounted) Navigator.of(sheetContext).pop();
              },
            ),
            ListTile(
              leading: const Icon(Icons.delete_outline),
              title: const Text('删除'),
              onTap: () async {
                Navigator.of(sheetContext).pop();
                final confirmed = await showDialog<bool>(
                  context: context,
                  builder: (dialogContext) => AlertDialog(
                    title: const Text('删除这条计划？'),
                    content: Text('「${plan.title}」将从清单移除，无法恢复。'),
                    actions: [
                      TextButton(
                        onPressed: () => Navigator.of(dialogContext).pop(false),
                        child: const Text('取消'),
                      ),
                      FilledButton(
                        onPressed: () => Navigator.of(dialogContext).pop(true),
                        child: const Text('删除'),
                      ),
                    ],
                  ),
                );
                if (confirmed == true) {
                  try {
                    await CommandHandler(_repo).execute(DeletePlanCommand(plan.id!));
                  } on ActionException catch (e) {
                    if (context.mounted) {
                      ScaffoldMessenger.of(context).showSnackBar(
                          SnackBar(content: Text(e.message)));
                    }
                  }
                }
              },
            ),
            ListTile(
              leading: const Icon(Icons.close),
              title: const Text('取消'),
              onTap: () => Navigator.of(sheetContext).pop(),
            ),
          ],
        ),
      ),
    );
  }
}
