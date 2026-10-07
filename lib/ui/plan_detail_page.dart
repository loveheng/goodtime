import 'package:flutter/material.dart';

import '../action/command_handler.dart';
import '../action/commands.dart';
import '../app_services.dart';
import '../data/repository.dart';
import '../models/background.dart';
import '../models/plan.dart';
import '../theme/tokens.dart';
import '../util/schedule_day.dart';

/// 计划详情页（ui-spec §5：全页路由，内容多不用浮层）：spec 尾部路线图进度 /
/// 四字段编辑 / open_items 手答（人机澄清闭环）/ reward_spec / 子树（≤3 级）/
/// 「背景」区（backgrounds scope=plan，背景草案 §3 入口 1）/「排期」快捷动作。
/// version 随每次成功写从快照回填，保证同会话连续操作不被乐观锁卡住。
/// 2026-10-07 全页路由提前落地：M1 的 sheet 浮层形态里背景区无扩容余地
/// （条目多 + 过期折叠组挤 0.85 屏浮层），形态对齐 spec 定稿。
class PlanDetailPage extends StatefulWidget {
  const PlanDetailPage({super.key, required this.planId});

  final String planId;

  @override
  State<PlanDetailPage> createState() => _PlanDetailPageState();
}

class _PlanDetailPageState extends State<PlanDetailPage> {
  Repository get _repo => AppServices.repo;

  final TextEditingController _title = TextEditingController();
  final TextEditingController _spec = TextEditingController();
  final TextEditingController _notes = TextEditingController();
  final TextEditingController _reward = TextEditingController();

  Plan? _plan;
  int _version = 0;
  List<OpenItem> _items = const [];
  final Map<int, TextEditingController> _answers = {};

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final p = await _repo.planById(widget.planId);
    if (!mounted || p == null) return;
    _plan = p;
    _version = p.version;
    _items = List<OpenItem>.from(p.openItems);
    _title.text = p.title;
    _spec.text = p.spec ?? '';
    _notes.text = p.notes ?? '';
    _reward.text = p.rewardSpec ?? '';
    setState(() {});
  }

  @override
  void dispose() {
    _title.dispose();
    _spec.dispose();
    _notes.dispose();
    _reward.dispose();
    for (final c in _answers.values) {
      c.dispose();
    }
    super.dispose();
  }

  /// 「排期」快捷动作（functional-spec §2）：日期/时间双 picker → place_block。
  /// 成功不关页（全页形态下用户常继续编辑/记背景）。
  Future<void> _schedule() async {
    final plan = _plan;
    if (plan == null) return;
    final picked = await showDatePicker(
      context: context,
      initialDate: DateTime.now(),
      firstDate: DateTime.now(),
      lastDate: DateTime.now().add(const Duration(days: 30)),
    );
    if (picked == null || !mounted) return;
    final time = await showTimePicker(
      context: context,
      initialTime: const TimeOfDay(hour: 9, minute: 0),
    );
    if (time == null || !mounted) return;
    final start = time.hour * 60 + time.minute;
    try {
      await CommandHandler(_repo).execute(PlaceBlockCommand(
        date: isoDate(picked),
        startMin: start,
        endMin: start + (plan.estimate ?? 60),
        planId: plan.id,
      ));
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(
          content: Text('已排入 ${isoDate(picked)} ${clockOf(start)}，可在日程页调整'),
          duration: const Duration(seconds: 2),
        ));
      }
    } on ActionException catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(content: Text(e.message), duration: const Duration(seconds: 3)));
      }
    }
  }

  Future<void> _save() async {
    final plan = _plan;
    if (plan == null) return;
    try {
      final r = await CommandHandler(_repo).execute(
        UpdatePlanCommand(
          id: plan.id!,
          title: _title.text.trim().isEmpty ? null : _title.text.trim(),
          spec: _spec.text.trim().isEmpty ? null : _spec.text.trim(),
          notes: _notes.text.trim().isEmpty ? null : _notes.text.trim(),
          rewardSpec: _reward.text.trim().isEmpty ? null : _reward.text.trim(),
          expectedVersion: _version,
        ),
      );
      _version = (r.snapshot?['version'] as int?) ?? _version + 1;
      if (mounted) Navigator.of(context).pop();
    } on ActionException catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(content: Text(e.message), duration: const Duration(seconds: 3)));
      }
    }
  }

  /// open_items 手答敲定（人机澄清闭环）：答案落库、条目转已答。
  Future<void> _answer(int i) async {
    final answer = _answers[i]!.text.trim();
    if (answer.isEmpty) return;
    final plan = _plan;
    if (plan == null) return;
    final updated = [
      for (var j = 0; j < _items.length; j++)
        j == i
            ? OpenItem(question: _items[j].question, answer: answer)
            : _items[j],
    ];
    try {
      final r = await CommandHandler(_repo).execute(
          UpdatePlanCommand(
              id: plan.id!,
              openItems: updated,
              expectedVersion: _version));
      _version = (r.snapshot?['version'] as int?) ?? _version + 1;
      if (!mounted) return;
      setState(() {
        _items = updated;
        _answers.remove(i)?.dispose();
      });
    } on ActionException catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text(e.message)));
      }
    }
  }

  /// spec 尾部路线图块进度（『## 路线图』+ Markdown Checklist）。
  String? _roadmapProgress(String spec) {
    final idx = spec.indexOf('## 路线图');
    if (idx < 0) return null;
    var section = spec.substring(idx);
    final next = section.indexOf('\n## ', 1);
    if (next >= 0) section = section.substring(0, next);
    final done =
        RegExp(r'^\s*-\s+\[x\]', multiLine: true).allMatches(section).length;
    final open =
        RegExp(r'^\s*-\s+\[ \]', multiLine: true).allMatches(section).length;
    if (done + open == 0) return null;
    return '当前进度 $done/${done + open}';
  }

  /// 子树（≤3 级缩进）：按 parentId 逐层拉取。
  Future<List<(int, Plan)>> _subtree(String rootId) async {
    final out = <(int, Plan)>[];
    Future<void> walk(String parentId, int depth) async {
      if (depth > 3) return;
      for (final k in await _repo.listPlans(parentId: parentId)) {
        out.add((depth, k));
        await walk(k.id!, depth + 1);
      }
    }

    await walk(rootId, 1);
    return out;
  }

  @override
  Widget build(BuildContext context) {
    final plan = _plan;
    return Scaffold(
      appBar: AppBar(
        title: const Text('编辑计划'),
        actions: [
          TextButton.icon(
            onPressed: plan == null ? null : _schedule,
            icon: const Icon(Icons.event_available, size: 18),
            label: const Text('排期'),
          ),
        ],
      ),
      body: plan == null
          ? const Center(child: Text('这条计划不存在了'))
          : SingleChildScrollView(
              padding: const EdgeInsets.fromLTRB(20, 8, 20, 24),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  if (_roadmapProgress(_spec.text) != null)
                    Padding(
                      padding: const EdgeInsets.only(bottom: 8),
                      child: Text('▶ ${_roadmapProgress(_spec.text)!}',
                          style: TextStyle(
                              fontSize: 12,
                              color: Theme.of(context).colorScheme.primary)),
                    ),
                  TextField(
                    controller: _title,
                    decoration:
                        const InputDecoration(labelText: '标题', border: OutlineInputBorder()),
                  ),
                  const SizedBox(height: 12),
                  TextField(
                    controller: _spec,
                    maxLines: 3,
                    decoration: const InputDecoration(
                        labelText: '精确描述（spec）', border: OutlineInputBorder()),
                  ),
                  const SizedBox(height: 12),
                  TextField(
                    controller: _notes,
                    maxLines: 2,
                    decoration: const InputDecoration(
                        labelText: '备注（首行写「马上开始」的第一步）',
                        border: OutlineInputBorder()),
                  ),
                  const SizedBox(height: 12),
                  TextField(
                    controller: _reward,
                    decoration: const InputDecoration(
                        labelText: '犒赏（打完这一仗怎么奖励自己）',
                        border: OutlineInputBorder()),
                  ),
                  const SizedBox(height: 12),
                  for (var i = 0; i < _items.length; i++)
                    if (_items[i].answer == null)
                      Padding(
                        padding: const EdgeInsets.only(bottom: 10),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text('？${_items[i].question}',
                                style: Theme.of(context)
                                    .textTheme
                                    .bodySmall
                                    ?.copyWith(color: StColors.textSecondary)),
                            Row(children: [
                              Expanded(
                                child: TextField(
                                  controller: _answers.putIfAbsent(i,
                                      () => TextEditingController()),
                                  decoration: const InputDecoration(
                                      isDense: true,
                                      hintText: '写下答案，敲定它',
                                      border: OutlineInputBorder()),
                                ),
                              ),
                              TextButton(
                                onPressed: () => _answer(i),
                                child: const Text('敲定'),
                              ),
                            ]),
                          ],
                        ),
                      )
                    else
                      Padding(
                        padding: const EdgeInsets.only(bottom: 6),
                        child: Text('✓ ${_items[i].question} → ${_items[i].answer}',
                            style: Theme.of(context)
                                .textTheme
                                .bodySmall
                                ?.copyWith(color: StColors.textSecondary)),
                      ),
                  FutureBuilder<List<(int, Plan)>>(
                    future: _subtree(plan.id!),
                    builder: (context, snap) {
                      final tree = snap.data ?? const <(int, Plan)>[];
                      if (tree.isEmpty) return const SizedBox.shrink();
                      return Padding(
                        padding: const EdgeInsets.only(bottom: 12),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            const SizedBox(height: 4),
                            Text('子计划',
                                style: Theme.of(context)
                                    .textTheme
                                    .labelMedium
                                    ?.copyWith(color: StColors.textSecondary)),
                            for (final (depth, p) in tree)
                              Padding(
                                padding: EdgeInsets.only(left: depth * 16.0),
                                child: Text('└ ${p.title}',
                                    style: Theme.of(context).textTheme.bodySmall),
                              ),
                          ],
                        ),
                      );
                    },
                  ),
                  _BackgroundSection(planId: plan.id!),
                  FilledButton(
                    onPressed: _save,
                    child: const Text('保存'),
                  ),
                ],
              ),
            ),
    );
  }
}

/// 计划详情「背景」区（背景草案 §3 入口 1；文案=ui-spec §0.4 背景区文案组）：
/// 条目卡=content＋标签小字＋日期角标（派生渲染不入库，无窗不显示）；超期沉
/// 「过去的背景」折叠组，清理动作（逐条删/清空已过期，均二次确认）仅在组内、
/// 主列表不加清理图标降噪。写通道=UpsertBackground/MergeBackgrounds（human），
/// 全经命令层（arch-guard：UI 禁直连 repo 写）。
/// 2026-10-07 自 plans_page 编辑 sheet 迁入全页详情页（spec §5 对齐）。
class _BackgroundSection extends StatefulWidget {
  const _BackgroundSection({required this.planId});

  final String planId;

  @override
  State<_BackgroundSection> createState() => _BackgroundSectionState();
}

class _BackgroundSectionState extends State<_BackgroundSection> {
  Repository get _repo => AppServices.repo;

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: _repo,
      builder: (context, _) => FutureBuilder<List<Background>>(
        future: _repo.listBackgrounds(scope: Background.scopePlan, planId: widget.planId),
        builder: (context, snap) {
          final all = snap.data ?? const <Background>[];
          final active = all.where((b) => !_expired(b)).toList();
          final expired = all.where(_expired).toList();
          return Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(children: [
                Text('背景',
                    style: Theme.of(context)
                        .textTheme
                        .labelMedium
                        ?.copyWith(color: StColors.textSecondary)),
                const Spacer(),
                TextButton.icon(
                  onPressed: () => _editSheet(context, null),
                  icon: const Icon(Icons.add, size: 16),
                  label: const Text('添加背景'),
                  style: TextButton.styleFrom(visualDensity: VisualDensity.compact),
                ),
              ]),
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
                            Text(b.content, style: Theme.of(context).textTheme.bodyMedium),
                            if (b.tags.isNotEmpty)
                              Text(b.tags.join(' '),
                                  style: Theme.of(context)
                                      .textTheme
                                      .bodySmall
                                      ?.copyWith(color: StColors.textSecondary)),
                          ],
                        ),
                      ),
                      if (b.applicableDates != null && b.applicableDates!.isNotEmpty)
                        Text(_badge(b.applicableDates!),
                            style: TextStyle(fontSize: 11, color: StColors.textSecondary)),
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
              const SizedBox(height: 8),
            ],
          );
        },
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
  Future<void> _confirmDelete(BuildContext context, List<Background> targets) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: Text(targets.length == 1 ? '删除这条背景？' : '清空这 ${targets.length} 条过去的背景？'),
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
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(e.message)));
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
                      child: Text(start == null ? '开始日期' : isoDate(start!)),
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
                                lastDate: DateTime.now().add(const Duration(days: 365)),
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
                        final e = end == null ? s : DateTime(end!.year, end!.month, end!.day);
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
                title: Text(b.content, style: Theme.of(context).textTheme.bodySmall),
                subtitle: b.applicableDates != null && b.applicableDates!.isNotEmpty
                    ? Text(widget.badge(b.applicableDates!),
                        style: TextStyle(fontSize: 11, color: StColors.textSecondary))
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
