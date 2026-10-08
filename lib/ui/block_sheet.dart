import 'package:flutter/material.dart';

import '../action/command_handler.dart';
import '../action/commands.dart';
import '../app_services.dart';
import '../models/artifact.dart';
import '../models/plan.dart';
import '../models/schedule_block.dart';
import '../theme/tokens.dart';
import '../util/schedule_day.dart';
import 'fact_sheet.dart';
import 'schedule_page.dart';

/// 块浮层（M1 表单形态，ui-spec §10「点块表单」；Landing Gear 抽屉 M4）：
/// proposed=确认三键，confirmed=打卡/心流专注中(+30m，AI 块)/改时间，done=只查看。
Future<void> showBlockSheet(BuildContext context, ScheduleBlock block) {
  return showModalBottomSheet<void>(
    context: context,
    showDragHandle: true,
    builder: (_) => _BlockSheet(block: block),
  );
}

/// 空槽点按创建（ui-spec §3.5）：未来时段「新建事项/从清单选」；
/// 过去时段 → 逆向记账「刚才做了什么」（§10 事实通道，豁免排程校验）。
Future<void> showNewBlockSheet(BuildContext context, DayData data,
    {int? slotStart, bool past = false}) {
  return showModalBottomSheet<void>(
    context: context,
    showDragHandle: true,
    isScrollControlled: true,
    builder: (_) => _NewBlockSheet(data: data, slotStart: slotStart, past: past),
  );
}

class _BlockSheet extends StatefulWidget {
  const _BlockSheet({required this.block});

  final ScheduleBlock block;

  @override
  State<_BlockSheet> createState() => _BlockSheetState();
}

class _BlockSheetState extends State<_BlockSheet> {
  bool _editing = false;
  late int _start = widget.block.startMin;
  late int _end = widget.block.endMin;
  String? _busy;

  CommandHandler get _handler => CommandHandler(AppServices.repo);
  ScheduleBlock get _b => widget.block;

  /// [canUndo]：打卡误触撤销——RestoreBlock 还原回打卡前状态（2026-10-08 拍板）。
  Future<void> _run(String action, Future<CommandResult> Function() run,
      {bool canUndo = false}) async {
    setState(() => _busy = action);
    // pop 后本 sheet 卸载，messenger 与撤销目标态须在 await 前捕获
    final messenger = ScaffoldMessenger.of(context);
    final prevStatus = _b.status;
    try {
      final r = await run();
      if (mounted) Navigator.of(context).pop();
      SnackBarAction? undo;
      if (canUndo) {
        undo = SnackBarAction(
          label: '撤销',
          onPressed: () async {
            try {
              final ur = await CommandHandler(AppServices.repo).execute(
                  RestoreBlockCommand(_b.id!, toStatus: prevStatus));
              messenger.showSnackBar(SnackBar(
                  content: Text(ur.note ?? '已恢复'),
                  duration: const Duration(seconds: 2)));
            } on ActionException catch (e) {
              messenger.showSnackBar(SnackBar(content: Text(e.message)));
            }
          },
        );
      }
      messenger.showSnackBar(SnackBar(
        content: Text(r.note ?? '完成'),
        duration: undo == null
            ? const Duration(seconds: 2)
            : const Duration(seconds: 5),
        // 浮动+FAB 净空：撤销钮不被右下角快记 FAB 遮挡（ui-spec §3 层叠规则）
        behavior: SnackBarBehavior.floating,
        margin: const EdgeInsets.fromLTRB(16, 0, 16, StScale.fabClearanceDp),
        action: undo,
      ));
    } on ActionException catch (e) {
      if (mounted) {
        messenger.showSnackBar(
          SnackBar(content: Text(e.message), duration: const Duration(seconds: 3)),
        );
      }
    } finally {
      if (mounted) setState(() => _busy = null);
    }
  }

  @override
  Widget build(BuildContext context) {
    final planFuture = _b.planId == null ? Future<Plan?>.value(null) : AppServices.repo.planById(_b.planId!);
    // 关联凭证（§7 块浮层通关卡）：「马上开始」→ 关联凭证首屏；无凭证整层消失
    final artifactFuture = AppServices.repo.artifactForBlock(_b.id!);
    return SafeArea(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(20, 0, 20, 20),
        child: FutureBuilder<Plan?>(
          future: planFuture,
          builder: (context, snap) {
            final plan = snap.data;
            return Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                _header(context, plan),
                const SizedBox(height: 12),
                FutureBuilder<Artifact?>(
                  future: artifactFuture,
                  builder: (context, aSnap) {
                    final fact = aSnap.data;
                    if (fact == null) return const SizedBox.shrink();
                    return Padding(
                      padding: const EdgeInsets.only(bottom: 12),
                      child: FactDetailSheet(fact: fact, block: _b, padding: EdgeInsets.zero),
                    );
                  },
                ),
                if (_editing) _editForm(context) else _actions(context),
              ],
            );
          },
        ),
      ),
    );
  }

  Widget _header(BuildContext context, Plan? plan) {
    final label = _b.label ?? plan?.title ?? '(未命名)';
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(label, style: Theme.of(context).textTheme.titleLarge),
        const SizedBox(height: 4),
        Text(
          '${_statusLabel()} · ${clockOf(_b.startMin)}–${clockOf(_b.endMin)}'
          '${plan != null ? ' · 来自清单：${plan.title}' : ''}',
          style: Theme.of(context).textTheme.bodySmall?.copyWith(color: StColors.textSecondary),
        ),
        // Landing Gear（§7 七轮）：顶部恒为启动第一步——行动前 60 秒是认知摩擦
        // 最大的时段；「马上开始」用 headline 级（全 app 最大字，ui-spec §0.2）
        if (plan?.notes != null && plan!.notes!.trim().isNotEmpty) ...[
          const SizedBox(height: 12),
          Text('马上开始',
              style: Theme.of(context)
                  .textTheme
                  .labelSmall
                  ?.copyWith(color: StColors.textSecondary)),
          Text(plan.notes!.trim().split('\n').first,
              style: Theme.of(context).textTheme.headlineSmall),
          if (plan.notes!.trim().split('\n').length > 1)
            Padding(
              padding: const EdgeInsets.only(top: 8),
              child: Text(plan.notes!.trim().split('\n').sublist(1).join('\n'),
                  style: Theme.of(context).textTheme.bodySmall),
            ),
        ],
      ],
    );
  }

  String _statusLabel() => switch (_b.status) {
        ScheduleBlock.statusProposed => '提案（AI 建议）',
        ScheduleBlock.statusConfirmed => '已确认',
        ScheduleBlock.statusDone => '已完成',
        ScheduleBlock.statusMissed => '未打卡',
        ScheduleBlock.statusArchived => '已归档',
        _ => _b.status,
      };

  Widget _actions(BuildContext context) {
    final aiBlock = _b.source == ScheduleBlock.sourceAi;
    final children = <Widget>[
      if (_b.status == ScheduleBlock.statusProposed) ...[
        FilledButton(
          onPressed: _busy == null
              ? () => _run('confirm',
                  () => _handler.execute(ConfirmBlockCommand(_b.id!, expectedVersion: _b.version)))
              : null,
          child: const Text('确认'),
        ),
        OutlinedButton(
          onPressed: _busy == null
              ? () => _run('reject', () => _handler.execute(RejectBlockCommand(_b.id!)))
              : null,
          child: const Text('否决'),
        ),
      ],
      if (_b.status == ScheduleBlock.statusProposed ||
          _b.status == ScheduleBlock.statusConfirmed) ...[
        if (_b.status == ScheduleBlock.statusConfirmed)
          FilledButton.tonal(
            onPressed: _busy == null
                ? () => _run('tick',
                    () => _handler.execute(TickBlockCommand(_b.id!, expectedVersion: _b.version)),
                    canUndo: true)
                : null,
            child: const Text('打卡'),
          ),
        if (aiBlock && _b.status == ScheduleBlock.statusConfirmed)
          OutlinedButton(
            onPressed: _busy == null
                ? () => _run('shift',
                    () => _handler.execute(
                        ShiftBlockCommand(_b.id!, minutes: 30, expectedVersion: _b.version)))
                : null,
            child: const Text('心流专注中 (+30m)'),
          ),
        OutlinedButton(
          onPressed: _busy == null ? () => setState(() => _editing = true) : null,
          child: const Text('改时间'),
        ),
      ],
      if (_b.status == ScheduleBlock.statusDone)
        Padding(
          padding: const EdgeInsets.symmetric(vertical: 8),
          child: Text('这块已完成，没有待办了。',
              style: Theme.of(context).textTheme.bodySmall),
        ),
    ];
    if (children.isEmpty) return const SizedBox.shrink();
    return Wrap(spacing: 8, runSpacing: 8, children: children);
  }

  Widget _editForm(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Row(
          children: [
            Expanded(child: _timeField('从几点', _start, (v) => setState(() => _start = v))),
            const SizedBox(width: 12),
            Expanded(child: _timeField('到几点', _end, (v) => setState(() => _end = v))),
          ],
        ),
        const SizedBox(height: 12),
        FilledButton(
          onPressed: _busy == null
              ? () => _run(
                    'adjust',
                    () => _handler.execute(
                      AdjustBlockTimeCommand(
                        id: _b.id!,
                        startMin: _start,
                        endMin: _end,
                        expectedVersion: _b.version,
                      ),
                    ),
                  )
              : null,
          child: const Text('保存'),
        ),
      ],
    );
  }

  Widget _timeField(String label, int minutes, ValueChanged<int> onChanged) {
    return ListTile(
      contentPadding: EdgeInsets.zero,
      title: Text(label, style: Theme.of(context).textTheme.bodySmall),
      subtitle: Text(clockOf(minutes), style: const TextStyle(fontSize: 20)),
      onTap: () async {
        final picked = await showTimePicker(
          context: context,
          initialTime: TimeOfDay(hour: minutes ~/ 60, minute: minutes % 60),
        );
        if (picked != null) onChanged(picked.hour * 60 + picked.minute);
      },
    );
  }
}

class _NewBlockSheet extends StatefulWidget {
  const _NewBlockSheet({required this.data, this.slotStart, this.past = false});

  final DayData data;
  final int? slotStart;

  /// 过去时段 → 逆向记账（retro_log 事实通道）
  final bool past;

  @override
  State<_NewBlockSheet> createState() => _NewBlockSheetState();
}

class _NewBlockSheetState extends State<_NewBlockSheet> {
  final _label = TextEditingController();
  Plan? _fromPlan;
  late int _start = widget.slotStart ?? widget.data.wake + 60;
  late int _end = _start + 60;

  CommandHandler get _handler => CommandHandler(AppServices.repo);

  @override
  void dispose() {
    _label.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (_fromPlan == null && _label.text.trim().isEmpty) return;
    if (widget.past) {
      // 逆向记账：事实通道，落 done（豁免排程校验，凡真实专注全部承认）
      await _handler.execute(RetroLogCommand(
        date: isoDate(widget.data.today),
        startMin: _start,
        endMin: _end,
        planId: _fromPlan?.id,
        label: _fromPlan == null ? _label.text.trim() : null,
      ));
    } else {
      await _handler.execute(PlaceBlockCommand(
        date: isoDate(widget.data.today),
        startMin: _start,
        endMin: _end,
        planId: _fromPlan?.id,
        label: _fromPlan == null ? _label.text.trim() : null,
      ));
    }
    if (mounted) Navigator.of(context).pop();
  }

  @override
  Widget build(BuildContext context) {
    return FutureBuilder<List<Plan>>(
      future: AppServices.repo.listPlans(),
      builder: (context, snap) {
        final plans = snap.data ?? const <Plan>[];
        return SafeArea(
          child: Padding(
            padding: EdgeInsets.fromLTRB(
                20, 0, 20, 20 + MediaQuery.of(context).viewInsets.bottom),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Text(widget.past ? '刚才做了什么' : '新建事项',
                    style: Theme.of(context).textTheme.titleLarge),
                const SizedBox(height: 12),
                SegmentedButton<bool>(
                  segments: const [
                    ButtonSegment(value: true, label: Text('新建事项')),
                    ButtonSegment(value: false, label: Text('从清单选')),
                  ],
                  selected: {_fromPlan == null},
                  onSelectionChanged: (s) =>
                      setState(() => _fromPlan = s.first ? null : (plans.isEmpty ? null : plans.first)),
                ),
                const SizedBox(height: 12),
                if (_fromPlan == null)
                  TextField(
                    controller: _label,
                    autofocus: true,
                    decoration: const InputDecoration(
                      labelText: '做什么（label）',
                      border: OutlineInputBorder(),
                    ),
                  )
                else
                  DropdownButtonFormField<Plan>(
                    initialValue: _fromPlan,
                    items: [
                      for (final p in plans)
                        DropdownMenuItem(value: p, child: Text(p.title)),
                    ],
                    onChanged: (p) => setState(() => _fromPlan = p),
                    decoration: const InputDecoration(
                      labelText: '从清单选',
                      border: OutlineInputBorder(),
                    ),
                  ),
                const SizedBox(height: 12),
                Row(
                  children: [
                    Expanded(child: _timeField('从几点', _start, (v) => setState(() => _start = v))),
                    const SizedBox(width: 12),
                    Expanded(child: _timeField('到几点', _end, (v) => setState(() => _end = v))),
                  ],
                ),
                const SizedBox(height: 12),
                FilledButton(
                  onPressed: (_fromPlan != null || _label.text.trim().isNotEmpty) ? _submit : null,
                  child: const Text('放入日程'),
                ),
              ],
            ),
          ),
        );
      },
    );
  }

  Widget _timeField(String label, int minutes, ValueChanged<int> onChanged) {
    return ListTile(
      contentPadding: EdgeInsets.zero,
      title: Text(label, style: Theme.of(context).textTheme.bodySmall),
      subtitle: Text(clockOf(minutes), style: const TextStyle(fontSize: 20)),
      onTap: () async {
        final picked = await showTimePicker(
          context: context,
          initialTime: TimeOfDay(hour: minutes ~/ 60, minute: minutes % 60),
        );
        if (picked != null) onChanged(picked.hour * 60 + picked.minute);
      },
    );
  }
}
