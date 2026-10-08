import 'package:flutter/material.dart';

import '../action/command_handler.dart';
import '../action/commands.dart';
import '../app_services.dart';
import '../data/repository.dart';
import '../models/background.dart';
import '../theme/tokens.dart';
import '../util/schedule_day.dart';

/// 计划「背景」子页（ui-spec §1/§5 子页化 2026-10-08 拍板）：自 plan_detail_page
/// 整区迁出（背景草案 §3 入口 1；文案=ui-spec §0.4 背景区文案组）——条目卡=
/// content＋标签小字＋日期角标（派生渲染不入库，无窗不显示）；超期沉
/// 「过去的背景」折叠组，清理动作（逐条删/清空已过期，均二次确认）仅在组内、
/// 主列表不加清理图标降噪。写通道=UpsertBackground/MergeBackgrounds（human），
/// 全经命令层（arch-guard：UI 禁直连 repo 写）。
class PlanBackgroundPage extends StatefulWidget {
  const PlanBackgroundPage({super.key, required this.planId});

  final String planId;

  @override
  State<PlanBackgroundPage> createState() => _PlanBackgroundPageState();
}

class _PlanBackgroundPageState extends State<PlanBackgroundPage> {
  Repository get _repo => AppServices.repo;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('背景')),
      body: ListenableBuilder(
        listenable: _repo,
        builder: (context, _) => FutureBuilder<List<Background>>(
          future: _repo.listBackgrounds(
              scope: Background.scopePlan, planId: widget.planId),
          builder: (context, snap) {
            final all = snap.data ?? const <Background>[];
            final active = all.where((b) => !_expired(b)).toList();
            final expired = all.where(_expired).toList();
            return ListView(
              padding: const EdgeInsets.all(16),
              children: [
                Row(children: [
                  Text('进行中',
                      style: Theme.of(context)
                          .textTheme
                          .labelMedium
                          ?.copyWith(color: StColors.textSecondary)),
                  const Spacer(),
                  TextButton.icon(
                    onPressed: () => _editSheet(context, null),
                    icon: const Icon(Icons.add, size: 16),
                    label: const Text('添加背景'),
                    style:
                        TextButton.styleFrom(visualDensity: VisualDensity.compact),
                  ),
                ]),
                if (active.isEmpty)
                  Padding(
                    padding: const EdgeInsets.symmetric(vertical: 12),
                    child: Text('还没有背景——身体状况、偏好、注意事项都记在这',
                        style: Theme.of(context)
                            .textTheme
                            .bodySmall
                            ?.copyWith(color: StColors.textSecondary)),
                  ),
                for (final b in active)
                  InkWell(
                    borderRadius: BorderRadius.circular(StScale.radiusCard),
                    onTap: () => _editSheet(context, b),
                    child: Padding(
                      padding: const EdgeInsets.symmetric(vertical: 6),
                      child: Row(children: [
                        Expanded(
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Text(b.content,
                                  style: Theme.of(context).textTheme.bodyMedium),
                              if (b.tags.isNotEmpty)
                                Text(b.tags.join(' '),
                                    style: Theme.of(context)
                                        .textTheme
                                        .bodySmall
                                        ?.copyWith(color: StColors.textSecondary)),
                            ],
                          ),
                        ),
                        if (b.applicableDates != null &&
                            b.applicableDates!.isNotEmpty)
                          Text(_badge(b.applicableDates!),
                              style: TextStyle(
                                  fontSize: 11, color: StColors.textSecondary)),
                      ]),
                    ),
                  ),
                if (expired.isNotEmpty)
                  _PastBackgroundsGroup(
                    expired: expired,
                    badge: _badge,
                    onEdit: (b) => _editSheet(context, b),
                    onDelete: (targets) => _confirmDelete(context, targets),
                  ),
              ],
            );
          },
        ),
      ),
    );
  }

  /// 超期=有日期窗且整窗已过（无窗=长期恒不过期；读侧 fail-closed 空窗按过期）。
  static bool _expired(Background b) {
    final d = b.applicableDates;
    if (d == null) return false;
    if (d.isEmpty) return true;
    return d.last.compareTo(isoDate(DateTime.now())) < 0;
  }

  /// 日期角标（ui-spec §0.4）：[10.08–10.10]，首末日记起止，派生渲染不入库。
  static String _badge(List<String> dates) {
    String f(String iso) {
      final d = tryParseIsoDate(iso)!;
      return '${d.month.toString().padLeft(2, '0')}.${d.day.toString().padLeft(2, '0')}';
    }

    return dates.length == 1
        ? '[${f(dates.first)}]'
        : '[${f(dates.first)}–${f(dates.last)}]';
  }

  /// 删除/清空已过期共用确认口（二次确认；批量走 merge 批命令单事务）。
  Future<void> _confirmDelete(
      BuildContext context, List<Background> targets) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: Text(targets.length == 1
            ? '删除这条背景？'
            : '清空这 ${targets.length} 条过去的背景？'),
        content: const Text('删除后无法恢复。'),
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
    if (confirmed != true) return;
    try {
      await CommandHandler(_repo).execute(
        MergeBackgroundsCommand(
            ops: [for (final t in targets) BackgroundMergeOp.delete(t.id!)]),
      );
    } on ActionException catch (e) {
      if (context.mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text(e.message)));
      }
    }
  }

  /// 背景编辑 sheet（新增/编辑共用）：内容＋标签＋作用日期窗（不选=长期，起止
  /// 枚举逐日落库——长窗由命令层劝改 note 提示）。human 通道，source 自动=user。
  Future<void> _editSheet(BuildContext context, Background? existing) {
    final content = TextEditingController(text: existing?.content ?? '');
    final tags = TextEditingController(text: existing?.tags.join(' ') ?? '');
    DateTime? start;
    DateTime? end;
    final dates = existing?.applicableDates;
    if (dates != null && dates.isNotEmpty) {
      start = tryParseIsoDate(dates.first);
      end = tryParseIsoDate(dates.last);
    }

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
                Text(existing == null ? '添加背景' : '编辑背景',
                    style: Theme.of(sheetContext).textTheme.titleLarge),
                const SizedBox(height: 12),
                TextField(
                  controller: content,
                  maxLines: 3,
                  autofocus: existing == null,
                  decoration: const InputDecoration(
                    labelText: '背景内容',
                    hintText: '例如：妈妈膝盖不好，少长台阶陡坡',
                    border: OutlineInputBorder(),
                  ),
                ),
                const SizedBox(height: 12),
                TextField(
                  controller: tags,
                  decoration: const InputDecoration(
                    labelText: '标签（空格分隔）',
                    hintText: '#健康 #体力',
                    border: OutlineInputBorder(),
                  ),
                ),
                const SizedBox(height: 10),
                Text('作用日期窗（不选=长期）',
                    style: Theme.of(sheetContext)
                        .textTheme
                        .bodySmall
                        ?.copyWith(color: StColors.textSecondary)),
                const SizedBox(height: 6),
                Row(children: [
                  Expanded(
                    child: OutlinedButton(
                      onPressed: () async {
                        final picked = await showDatePicker(
                          context: sheetContext,
                          initialDate: start ?? DateTime.now(),
                          firstDate: DateTime(2020),
                          lastDate: DateTime.now().add(const Duration(days: 365)),
                        );
                        if (picked != null) setState(() => start = picked);
                      },
                      child:
                          Text(start == null ? '开始日期' : isoDate(start!)),
                    ),
                  ),
                  const SizedBox(width: 8),
                  Expanded(
                    child: OutlinedButton(
                      onPressed: start == null
                          ? null
                          : () async {
                              final picked = await showDatePicker(
                                context: sheetContext,
                                initialDate: end ?? start!,
                                firstDate: start!,
                                lastDate:
                                    DateTime.now().add(const Duration(days: 365)),
                              );
                              if (picked != null) setState(() => end = picked);
                            },
                      child: Text(start == null
                          ? '—'
                          : (end == null ? isoDate(start!) : isoDate(end!))),
                    ),
                  ),
                  if (start != null)
                    IconButton(
                      onPressed: () => setState(() {
                        start = null;
                        end = null;
                      }),
                      icon: const Icon(Icons.close, size: 18),
                      tooltip: '清除日期窗（改为长期）',
                    ),
                ]),
                const SizedBox(height: 14),
                Row(children: [
                  if (existing != null)
                    TextButton(
                      onPressed: () {
                        Navigator.of(sheetContext).pop();
                        _confirmDelete(context, [existing]);
                      },
                      child: const Text('删除',
                          style: TextStyle(color: Colors.redAccent)),
                    ),
                  const Spacer(),
                  FilledButton(
                    onPressed: () async {
                      final text = content.text.trim();
                      if (text.isEmpty) return;
                      final tagList = [
                        for (final t in tags.text.split(RegExp(r'[,,，、\s]+')))
                          if (t.trim().isNotEmpty) t.trim(),
                      ];
                      List<String>? window;
                      if (start != null) {
                        final s = DateTime(start!.year, start!.month, start!.day);
                        final e =
                            end == null ? s : DateTime(end!.year, end!.month, end!.day);
                        if (e.isBefore(s)) {
                          ScaffoldMessenger.of(sheetContext).showSnackBar(const SnackBar(
                              content: Text('结束日期早于开始日期'),
                              duration: Duration(seconds: 3)));
                          return;
                        }
                        window = [
                          for (var cur = s; !cur.isAfter(e);
                              cur = cur.add(const Duration(days: 1)))
                            isoDate(cur),
                        ];
                      }
                      try {
                        final r = await CommandHandler(_repo).execute(
                          UpsertBackgroundCommand(
                            id: existing?.id,
                            scope: Background.scopePlan,
                            planId: widget.planId,
                            content: text,
                            tags: tagList,
                            applicableDates: window,
                            expectedVersion: existing?.version,
                          ),
                        );
                        if (sheetContext.mounted) {
                          if (r.note != null) {
                            ScaffoldMessenger.of(sheetContext).showSnackBar(SnackBar(
                                content: Text(r.note!),
                                duration: const Duration(seconds: 3)));
                          }
                          Navigator.of(sheetContext).pop();
                        }
                      } on ActionException catch (e) {
                        if (sheetContext.mounted) {
                          ScaffoldMessenger.of(sheetContext).showSnackBar(SnackBar(
                              content: Text(e.message),
                              duration: const Duration(seconds: 3)));
                        }
                      }
                    },
                    child: const Text('保存'),
                  ),
                ]),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

/// 「过去的背景」折叠组（ui-spec §0.4）：超期条目收纳＋组内清理，与「收起的旧
/// 想法」同收纳模式；逐条可点开编辑。
class _PastBackgroundsGroup extends StatefulWidget {
  const _PastBackgroundsGroup({
    required this.expired,
    required this.badge,
    required this.onEdit,
    required this.onDelete,
  });

  final List<Background> expired;
  final String Function(List<String>) badge;
  final void Function(Background) onEdit;
  final void Function(List<Background>) onDelete;

  @override
  State<_PastBackgroundsGroup> createState() => _PastBackgroundsGroupState();
}

class _PastBackgroundsGroupState extends State<_PastBackgroundsGroup> {
  bool _expanded = false;

  @override
  Widget build(BuildContext context) {
    return Card(
      margin: const EdgeInsets.only(top: 4),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          InkWell(
            borderRadius: BorderRadius.circular(StScale.radiusCard),
            onTap: () => setState(() => _expanded = !_expanded),
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
              child: Row(children: [
                Icon(
                  _expanded ? Icons.expand_less : Icons.expand_more,
                  size: 18,
                  color: StColors.textSecondary,
                ),
                const SizedBox(width: 6),
                Text('过去的背景 · ${widget.expired.length} 条',
                    style: Theme.of(context)
                        .textTheme
                        .labelMedium
                        ?.copyWith(color: StColors.textSecondary)),
              ]),
            ),
          ),
          if (_expanded) ...[
            for (final b in widget.expired)
              ListTile(
                dense: true,
                onTap: () => widget.onEdit(b),
                title:
                    Text(b.content, style: Theme.of(context).textTheme.bodySmall),
                subtitle: b.applicableDates != null && b.applicableDates!.isNotEmpty
                    ? Text(widget.badge(b.applicableDates!),
                        style: TextStyle(
                            fontSize: 11, color: StColors.textSecondary))
                    : null,
                trailing: TextButton(
                  onPressed: () => widget.onDelete([b]),
                  child: const Text('删除'),
                ),
              ),
            Padding(
              padding: const EdgeInsets.fromLTRB(12, 0, 12, 8),
              child: Align(
                alignment: Alignment.centerLeft,
                child: TextButton.icon(
                  onPressed: () => widget.onDelete(widget.expired),
                  icon: const Icon(Icons.cleaning_services, size: 16),
                  label: const Text('清空已过期'),
                ),
              ),
            ),
          ],
        ],
      ),
    );
  }
}
