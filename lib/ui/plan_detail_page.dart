import 'package:flutter/material.dart';

import '../action/command_handler.dart';
import '../action/commands.dart';
import '../app_services.dart';
import '../data/repository.dart';
import '../models/plan.dart';
import '../theme/tokens.dart';
import '../util/schedule_day.dart';
import 'plan_background_page.dart';
import 'plan_facts_page.dart';

/// 计划详情页（ui-spec §5 定稿：全页路由；2026-10-08 二批 UX 拆分拍板）：
/// **默认查看态**——roadmap 进度 / notes 首行「马上开始」高亮框 / open_items
/// 手答（人机澄清闭环）/ 子树（≤3 级）/「背景」「随行凭证」子页入口行 /
/// 「排期」快捷动作；右上「编辑」进入编辑态（四字段表单+保存，保存后回查看态）。
/// 背景/凭证两区 CRUD 整体迁出至 PlanBackgroundPage/PlanFactsPage 子页
/// （原单页四表单+两区混排滚动过长，保存钮深埋页底）。
/// version 随每次成功写从快照回填，保证同会话连续操作不被乐观锁卡住。
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

  /// 查看态（默认）/编辑态（四字段表单，2026-10-08 拍板分离）。
  bool _editing = false;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final p = await _repo.planById(widget.planId);
    if (!mounted || p == null) return;
    setState(() {
      _plan = p;
      _version = p.version;
      _items = List<OpenItem>.from(p.openItems);
      _title.text = p.title;
      _spec.text = p.spec ?? '';
      _notes.text = p.notes ?? '';
      _reward.text = p.rewardSpec ?? '';
    });
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
  /// 日期默认=作息日（与日程页/get_schedule 同口径——凌晨日历「今天」≠作息「今天」，
  /// 否则 00:00–wake 间排期默认落日历次日且不可选作息日）。
  Future<void> _schedule() async {
    final plan = _plan;
    if (plan == null) return;
    final wake = int.tryParse(await _repo.settingsGet('wake_time') ?? '420') ?? 420;
    if (!mounted) return;
    final day = scheduleDayOf(DateTime.now(), wake);
    final picked = await showDatePicker(
      context: context,
      initialDate: day,
      firstDate: day,
      lastDate: day.add(const Duration(days: 30)),
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

  /// 编辑态保存（2026-10-08 拍板：保存后回查看态，不再关页——全页详情的
  /// 背景区/凭证区子页入口才是主出口）。
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
      if (mounted) {
        setState(() => _editing = false);
        ScaffoldMessenger.of(context).showSnackBar(const SnackBar(
            content: Text('已保存'), duration: Duration(seconds: 1)));
      }
    } on ActionException catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(content: Text(e.message), duration: const Duration(seconds: 3)));
      }
    }
  }

  /// open_items 手答敲定（人机澄清闭环）：答案落库、条目转已答（查看态内联）。
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
        title: Text(_editing ? '编辑计划' : '计划详情'),
        actions: [
          TextButton.icon(
            onPressed: plan == null ? null : _schedule,
            icon: const Icon(Icons.event_available, size: 18),
            label: const Text('排期'),
          ),
          TextButton(
            onPressed: plan == null
                ? null
                : () => setState(() => _editing = !_editing),
            child: Text(_editing ? '完成' : '编辑'),
          ),
        ],
      ),
      body: plan == null
          ? const Center(child: Text('这条计划不存在了'))
          : _editing
              ? _editBody(context)
              : _viewBody(context, plan),
    );
  }

  // ---- 查看态（默认）----

  Widget _viewBody(BuildContext context, Plan plan) {
    final spec = plan.spec;
    return SingleChildScrollView(
      padding: const EdgeInsets.fromLTRB(20, 8, 20, 24),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          if (spec != null && _roadmapProgress(spec) != null)
            Padding(
              padding: const EdgeInsets.only(bottom: 8),
              child: Text('▶ ${_roadmapProgress(spec)!}',
                  style: TextStyle(
                      fontSize: 12,
                      color: Theme.of(context).colorScheme.primary)),
            ),
          Text(plan.title, style: Theme.of(context).textTheme.titleLarge),
          // Landing Gear（ui-spec §5 notes 首行启动第一步高亮框）
          if (plan.notes != null && plan.notes!.trim().isNotEmpty) ...[
            const SizedBox(height: 12),
            Container(
              width: double.infinity,
              padding: const EdgeInsets.all(12),
              decoration: BoxDecoration(
                color: Theme.of(context).colorScheme.primaryContainer,
                borderRadius: BorderRadius.circular(StScale.radiusCard),
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text('马上开始',
                      style: Theme.of(context)
                          .textTheme
                          .labelSmall
                          ?.copyWith(color: StColors.textSecondary)),
                  Text(plan.notes!.trim().split('\n').first,
                      style: Theme.of(context).textTheme.titleMedium),
                  if (plan.notes!.trim().split('\n').length > 1)
                    Padding(
                      padding: const EdgeInsets.only(top: 6),
                      child: Text(plan.notes!.trim().split('\n').sublist(1).join('\n'),
                          style: Theme.of(context).textTheme.bodySmall),
                    ),
                ],
              ),
            ),
          ],
          if (spec != null && spec.trim().isNotEmpty) ...[
            const SizedBox(height: 12),
            Text(spec, style: Theme.of(context).textTheme.bodyMedium),
          ],
          if (plan.rewardSpec != null && plan.rewardSpec!.trim().isNotEmpty) ...[
            const SizedBox(height: 12),
            Text('犒赏：${plan.rewardSpec}',
                style: Theme.of(context)
                    .textTheme
                    .bodySmall
                    ?.copyWith(color: StColors.textSecondary)),
          ],
          // open_items 手答（人机澄清闭环）——查看态内联
          for (var i = 0; i < _items.length; i++)
            if (_items[i].answer == null)
              Padding(
                padding: const EdgeInsets.only(top: 10),
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
                padding: const EdgeInsets.only(top: 6),
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
                padding: const EdgeInsets.only(top: 12),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text('子计划',
                        style: Theme.of(context)
                            .textTheme
                            .labelMedium
                            ?.copyWith(color: StColors.textSecondary)),
                    for (final (depth, p) in tree)
                      Padding(
                        padding: EdgeInsets.only(left: depth * 16.0, top: 2),
                        child: Text('└ ${p.title}',
                            style: Theme.of(context).textTheme.bodySmall),
                      ),
                  ],
                ),
              );
            },
          ),
          const SizedBox(height: 16),
          // 「背景」「随行凭证」子页入口（2026-10-08 拆分拍板：CRUD 整区迁出）
          _entryRow(context,
              icon: Icons.info_outline,
              title: '背景',
              subtitle: '身体状况、偏好、注意事项',
              page: (_) => PlanBackgroundPage(planId: plan.id!)),
          _entryRow(context,
              icon: Icons.confirmation_number_outlined,
              title: '随行凭证',
              subtitle: '车票/门票/预订与须知',
              page: (_) => PlanFactsPage(planId: plan.id!)),
        ],
      ),
    );
  }

  /// 子页入口行（目录形态，同设置页 _entryRow 口径）。
  Widget _entryRow(BuildContext context,
      {required IconData icon,
      required String title,
      String? subtitle,
      required WidgetBuilder page}) {
    return Card(
      margin: const EdgeInsets.only(bottom: 8),
      child: ListTile(
        leading: Icon(icon, size: 22),
        title: Text(title),
        subtitle: subtitle == null ? null : Text(subtitle),
        trailing: Icon(Icons.chevron_right,
            size: 20, color: StColors.textSecondary),
        onTap: () =>
            Navigator.of(context).push(MaterialPageRoute<void>(builder: page)),
      ),
    );
  }

  // ---- 编辑态（四字段表单；保存回查看态）----

  Widget _editBody(BuildContext context) {
    return SingleChildScrollView(
      padding: const EdgeInsets.fromLTRB(20, 8, 20, 24),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
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
          const SizedBox(height: 16),
          FilledButton(
            onPressed: _save,
            child: const Text('保存'),
          ),
        ],
      ),
    );
  }
}
