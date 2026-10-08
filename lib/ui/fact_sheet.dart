import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:url_launcher/url_launcher.dart';

import '../action/command_handler.dart';
import '../action/commands.dart';
import '../app_services.dart';
import '../models/artifact.dart';
import '../models/plan.dart';
import '../models/schedule_block.dart';
import '../theme/tokens.dart';
import '../util/schedule_day.dart';

// ─────────────────────────── 类别与派生 helper ───────────────────────────
// 白话类别=ui-spec §0.4（黑话不上屏）；派生口径一律不入库（与「过去的凭证」
// 「留白缓冲」同纪律）。

/// category 五类 → 白话（§0.4）：交通/门票/住宿/须知/口头信息。
String factCategoryLabel(String category) => switch (category) {
      Artifact.categoryTransit => '交通',
      Artifact.categoryTicket => '门票',
      Artifact.categoryHotel => '住宿',
      Artifact.categoryVenue => '须知',
      Artifact.categoryVerbal => '口头信息',
      _ => '信息',
    };

/// category → 图标（卡面/通关卡 Header 同源，不为任何门类硬编码）。
IconData factCategoryIcon(String category) => switch (category) {
      Artifact.categoryTransit => Icons.train_outlined,
      Artifact.categoryTicket => Icons.confirmation_number_outlined,
      Artifact.categoryHotel => Icons.hotel_outlined,
      Artifact.categoryVenue => Icons.museum_outlined,
      Artifact.categoryVerbal => Icons.chat_bubble_outline,
      _ => Icons.info_outline,
    };

List<Object?> _anchorList(Artifact a) {
  final v = a.payload['time_anchors'];
  return v is List ? v : const [];
}

/// 全部锚点最晚日历日（span 取 end_date）；无锚 → null（raw 未解析恒 null）。
String? factLatestAnchorDate(Artifact a) {
  String? latest;
  for (final raw in _anchorList(a)) {
    if (raw is! Map) continue;
    final d = raw['end_date'] as String? ?? raw['date'] as String?;
    if (d == null) continue;
    if (latest == null || d.compareTo(latest) > 0) latest = d;
  }
  return latest;
}

/// 过期=全部锚点最晚日历日早于今日（派生不入库；无锚=长期恒不过期）。
bool factExpired(Artifact a) {
  final latest = factLatestAnchorDate(a);
  if (latest == null) return false;
  return latest.compareTo(isoDate(DateTime.now())) < 0;
}

String _md(String? iso) {
  final d = tryParseIsoDate(iso ?? '');
  if (d == null) return iso ?? '';
  return '${d.month.toString().padLeft(2, '0')}.${d.day.toString().padLeft(2, '0')}';
}

/// 日期角标（§0.4）：[10.20] 或 [10.17–10.19]，由锚点派生，无锚不显示。
String? factDateBadge(Artifact a) {
  final days = <String>{};
  for (final raw in _anchorList(a)) {
    if (raw is! Map) continue;
    final d = raw['date'] as String?;
    final e = raw['end_date'] as String?;
    if (d != null) days.add(d);
    if (e != null) days.add(e);
  }
  if (days.isEmpty) return null;
  final sorted = days.toList()..sort();
  return sorted.length == 1
      ? '[${_md(sorted.first)}]'
      : '[${_md(sorted.first)}–${_md(sorted.last)}]';
}

DateTime? _abs(String? date, int? min) {
  final d = tryParseIsoDate(date ?? '');
  if (d == null || min == null) return null;
  return DateTime(d.year, d.month, d.day).add(Duration(minutes: min));
}

/// §1.4 一致性派生比对：span 锚与关联块起止（跨午夜按绝对时刻折算，严禁同日
/// 直接比分钟）；不一致 → 卡顶琥珀条。派生不入库，关联块悬空自然不自检。
bool factInconsistentWithBlock(Artifact a, ScheduleBlock? block) {
  if (a.blockId == null || block == null) return false;
  for (final raw in _anchorList(a)) {
    if (raw is! Map || raw['kind'] != 'span') continue;
    DateTime? s0 = _abs(raw['date'] as String?, raw['start_min'] as int?);
    DateTime? s1 = _abs(raw['end_date'] as String? ?? raw['date'] as String?,
        raw['end_min'] as int?);
    final b0 = _abs(block.date, block.startMin);
    DateTime? b1 = _abs(block.date, block.endMin);
    if (s0 == null || s1 == null || b0 == null || b1 == null) continue;
    if (s1.isBefore(s0)) s1 = s1.add(const Duration(days: 1));
    if (b1.isBefore(b0)) b1 = b1.add(const Duration(days: 1));
    if (s0 != b0 || s1 != b1) return true;
  }
  return false;
}

// ─────────────────────────── 紧凑凭证卡 ───────────────────────────

/// 紧凑凭证卡（计划详情「随行凭证」区 / 未归属面板共用）：类别图标+title+
/// badge+日期。raw=琥珀卡（原文待提炼，未解析无日期）；verbal=弱化+「口头
/// 信息 · 待核实」角标；voided 由读侧白名单挡掉，到不了本层。
class FactCard extends StatelessWidget {
  const FactCard({super.key, required this.fact, this.onTap, this.onLongPress});

  final Artifact fact;
  final VoidCallback? onTap;
  final VoidCallback? onLongPress;

  @override
  Widget build(BuildContext context) {
    final isRaw = fact.state == Artifact.stateRaw;
    final isVerbal = fact.category == Artifact.categoryVerbal;
    final badge = factDateBadge(fact);
    return Material(
      color: isRaw ? StColors.voucherBg : Colors.transparent,
      borderRadius: BorderRadius.circular(StScale.radiusCard),
      child: InkWell(
        borderRadius: BorderRadius.circular(StScale.radiusCard),
        onTap: onTap,
        onLongPress: onLongPress,
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
          child: Row(children: [
            Icon(factCategoryIcon(fact.category),
                size: 18,
                color: isVerbal ? StColors.sparkStroke : StColors.textSecondary),
            const SizedBox(width: 8),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    fact.title,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: isVerbal
                        ? Theme.of(context)
                            .textTheme
                            .bodySmall
                            ?.copyWith(color: StColors.textSecondary)
                        : Theme.of(context).textTheme.bodyMedium,
                  ),
                  if (isRaw)
                    Text('原文待提炼',
                        style: TextStyle(
                            fontSize: 10, color: StColors.sparkStroke))
                  else if (isVerbal)
                    Text('口头信息 · 待核实',
                        style: TextStyle(
                            fontSize: 10, color: StColors.sparkStroke)),
                ],
              ),
            ),
            if (fact.badge != null && fact.badge!.isNotEmpty)
              ConstrainedBox(
                constraints: const BoxConstraints(maxWidth: 96),
                child: Padding(
                  padding: const EdgeInsets.only(left: 6),
                  child: Text(fact.badge!,
                      style: TextStyle(
                          fontSize: 10, color: StColors.textSecondary),
                      overflow: TextOverflow.ellipsis),
                ),
              ),
            if (badge != null)
              Padding(
                padding: const EdgeInsets.only(left: 6),
                child: Text(badge,
                    style: TextStyle(
                        fontSize: 11, color: StColors.textSecondary)),
              ),
          ]),
        ),
      ),
    );
  }
}

// ─────────────────────────── 通关卡（Slot Assembler）───────────────────────────

/// 凭证详情/通关卡（fact 草案 §2.2/§2.4）：单一 Widget 按序装配四层，空层自动
/// 消失——Header 药丸 → Hero 通关卡（voucherSurface，hero_metrics 空整层消失）→
/// 时空网格 → 约束 Chip 流（🚫 禁止=中性灰，绝不用红）→ 功能动作行（零权限全
/// intent）→ 溯源折叠。块浮层与独立入口同源装配（`showVoucherSheet`）。
class FactDetailSheet extends StatelessWidget {
  const FactDetailSheet({
    super.key,
    required this.fact,
    this.block,
    this.onNavigateToBlock,
    this.padding = const EdgeInsets.fromLTRB(20, 0, 20, 20),
  });

  final Artifact fact;
  final ScheduleBlock? block; // 关联块（一致性比对数据源；null=无关联）
  final VoidCallback? onNavigateToBlock;
  final EdgeInsetsGeometry padding; // 块浮层嵌入时传 zero（外层已有边距）

  @override
  Widget build(BuildContext context) {
    final inconsistent = factInconsistentWithBlock(fact, block);
    final isVerbal = fact.category == Artifact.categoryVerbal;
    return SingleChildScrollView(
      padding: padding,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          if (isVerbal) _header(context, '💬 口头信息 · 待核实'),
          if (isVerbal) const SizedBox(height: 12),
          if (inconsistent && onNavigateToBlock != null)
            _AmberBar(
              text: '与日程时间不一致，点此核对',
              onTap: onNavigateToBlock!,
            ),
          if (inconsistent && onNavigateToBlock != null)
            const SizedBox(height: 12),
          // Header：类别药丸 + title + 日期角标
          _header(context, fact.title),
          // Hero 通关区：voucherSurface 琥珀面，hero_metrics ≤3 组 KV
          // （字级纪律：title·medium 不夺块浮层「马上开始」的 title·large 焦点）
          _hero(context),
          // 时空网格：锚点 label 化双列 KV + location
          _anchors(context),
          // 行动约束：三数组 Chip 流（零任务压力——不打勾不计数）
          _constraints(context),
          // 功能动作：复制/拨号/导航（零新权限，全 intent）
          _actions(context),
          // 溯源：raw_text 手风琴（默认折叠）
          _rawText(context),
        ],
      ),
    );
  }

  Widget _header(BuildContext context, String title) {
    final badge = factDateBadge(fact);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(children: [
          _Pill(
            icon: factCategoryIcon(fact.category),
            label: factCategoryLabel(fact.category),
          ),
          const SizedBox(width: 8),
          Expanded(
            child: Text(title,
                style: Theme.of(context).textTheme.titleMedium,
                maxLines: 2,
                overflow: TextOverflow.ellipsis),
          ),
        ]),
        if (badge != null)
          Padding(
            padding: const EdgeInsets.only(top: 4),
            child: Text(badge,
                style: TextStyle(
                    fontSize: 11, color: StColors.textSecondary)),
          ),
      ],
    );
  }

  Widget _hero(BuildContext context) {
    final hero = fact.payload['hero_metrics'];
    final metrics = (hero is List) ? hero.whereType<Map>().toList() : const [];
    if (metrics.isEmpty) return const SizedBox.shrink();
    return Container(
      margin: const EdgeInsets.only(top: 12),
      padding: const EdgeInsets.all(StScale.insetMd),
      decoration: BoxDecoration(
        color: StColors.voucherBg,
        borderRadius: BorderRadius.circular(StScale.radiusCard),
        border: Border.all(color: StColors.sparkStroke, width: 1),
      ),
      child: Row(
        children: [
          for (final m in metrics)
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(m['k'] as String? ?? '',
                      style: Theme.of(context)
                          .textTheme
                          .labelSmall
                          ?.copyWith(color: StColors.textSecondary)),
                  const SizedBox(height: 2),
                  Text(m['v'] as String? ?? '',
                      style: Theme.of(context).textTheme.titleMedium),
                ],
              ),
            ),
        ],
      ),
    );
  }

  Widget _anchors(BuildContext context) {
    final anchors = <Map>[];
    for (final raw in _anchorList(fact)) {
      if (raw is Map) anchors.add(raw);
    }
    final location = fact.payload['location'] as String?;
    if (anchors.isEmpty && (location == null || location.isEmpty)) {
      return const SizedBox.shrink();
    }
    final rows = <Widget>[];
    for (final a in anchors) {
      final kind = a['kind'] as String? ?? '';
      final role = a['role'] as String? ?? '';
      final deadline = a['role']?.contains('停止') ?? false;
      String when;
      if (kind == 'span') {
        final d = _md(a['date'] as String?);
        final e = a['end_date'] as String?;
        final sm = a['start_min'] as int?;
        final em = a['end_min'] as int?;
        if (e != null && e != a['date']) {
          when = '${_md(a['date'] as String?)} → ${_md(e)}';
          if (sm != null && em != null) {
            when = '$d ${clockOf(sm)} → ${_md(e)} ${clockOf(em)}';
          }
        } else if (sm != null && em != null) {
          when = '$d ${clockOf(sm)}–${clockOf(em)}';
        } else {
          when = d;
        }
      } else if (kind == 'moment') {
        final min = a['min'] as int?;
        when = min == null
            ? _md(a['date'] as String?)
            : '${_md(a['date'] as String?)} ${clockOf(min)}';
      } else {
        when = role.isNotEmpty ? role : '长期';
      }
      rows.add(Padding(
        padding: const EdgeInsets.symmetric(vertical: 3),
        child: Row(
          children: [
            if (deadline) const Padding(
              padding: EdgeInsets.only(right: 4),
              child: Text('⚠', style: TextStyle(fontSize: 11)),
            ),
            Expanded(
              child: Text(
                  role.isNotEmpty ? '$role · $when' : when,
                  style: Theme.of(context).textTheme.bodySmall),
            ),
          ],
        ),
      ));
    }
    if (location != null && location.isNotEmpty) {
      rows.add(Padding(
        padding: const EdgeInsets.symmetric(vertical: 3),
        child: Text('📍 $location',
            style: Theme.of(context).textTheme.bodySmall),
      ));
    }
    return Padding(padding: const EdgeInsets.only(top: 12), child: Column(children: rows));
  }

  Widget _constraints(BuildContext context) {
    final c = fact.payload['constraints'];
    if (c is! Map) return const SizedBox.shrink();
    final requiredItems = _strList(c['required_items']);
    final rules = _strList(c['rules']);
    final notices = _strList(c['notices']);
    final chips = <Widget>[
      for (final r in requiredItems)
        _Chip(icon: Icons.check_box_outline_blank, text: r, color: StColors.supplyBandBg),
      for (final r in rules)
        // 🚫 禁止=中性灰：禁令不是失败，红色语义全 app 保留（§2.2）
        _Chip(icon: Icons.block, text: r, color: StColors.fixedSlotBg),
      for (final n in notices) _Chip(icon: Icons.info_outline, text: n, color: StColors.sparkBg),
    ];
    if (chips.isEmpty) return const SizedBox.shrink();
    return Padding(
      padding: const EdgeInsets.only(top: 12),
      child: Wrap(spacing: 6, runSpacing: 6, children: chips),
    );
  }

  Widget _actions(BuildContext context) {
    final code = fact.payload['copyable_code'] as String?;
    final phone = fact.payload['contact_phone'] as String?;
    final location = fact.payload['location'] as String?;
    final buttons = <Widget>[];
    if (code != null && code.isNotEmpty) {
      buttons.add(
        OutlinedButton.icon(
          onPressed: () {
            Clipboard.setData(ClipboardData(text: code));
            ScaffoldMessenger.of(context).showSnackBar(
              SnackBar(content: Text('已复制 $code'), duration: const Duration(seconds: 2)),
            );
          },
          icon: const Icon(Icons.copy, size: 16),
          label: const Text('复制'),
        ),
      );
    }
    if (phone != null && phone.isNotEmpty) {
      buttons.add(
        OutlinedButton.icon(
          onPressed: () async {
            final uri = Uri.parse('tel:$phone');
            if (await canLaunchUrl(uri)) await launchUrl(uri);
          },
          icon: const Icon(Icons.phone, size: 16),
          label: Text('拨号 $phone'),
        ),
      );
    }
    if (location != null && location.isNotEmpty) {
      buttons.add(
        OutlinedButton.icon(
          onPressed: () async {
            final uri = Uri.parse('geo:0,0?q=${Uri.encodeComponent(location)}');
            if (await canLaunchUrl(uri)) await launchUrl(uri);
          },
          icon: const Icon(Icons.place_outlined, size: 16),
          label: const Text('导航'),
        ),
      );
    }
    if (buttons.isEmpty) return const SizedBox.shrink();
    return Padding(
      padding: const EdgeInsets.only(top: 12),
      child: Wrap(spacing: 8, runSpacing: 8, children: buttons),
    );
  }

  Widget _rawText(BuildContext context) {
    final raw = fact.payload['raw_text'] as String?;
    if (raw == null || raw.isEmpty) return const SizedBox.shrink();
    return _Accordion(label: '查看原文', text: raw);
  }
}

/// 通关卡独立入口（compact =false 时全屏装配）：凭证卡点按/🎫 微标同源开卡。
Future<void> showVoucherSheet(
  BuildContext context,
  Artifact fact, {
  ScheduleBlock? block,
  VoidCallback? onNavigateToBlock,
}) {
  return showModalBottomSheet<void>(
    context: context,
    showDragHandle: true,
    isScrollControlled: true,
    builder: (_) => SafeArea(
      child: FactDetailSheet(
        fact: fact,
        block: block,
        onNavigateToBlock: onNavigateToBlock,
      ),
    ),
  );
}

// ─────────────────────────── 挂载 sheet（分享入口 + 未归属常驻组）───────────────────────────

/// 挂载 sheet（fact 草案 §1.7）：系统分享 raw 入册后开——预填原文 → 计划列表
/// （最近在前+例外日行程置顶）→ 点计划即归属，两次点击内完成；顶部常驻
/// 「未归属 N 条」折叠组（D2 宿主=本 sheet）；「先记下，稍后归属」=留池退场。
/// human 通道：归属走 upsert_facts plan_id（同命令改挂）。
Future<void> showFactMountSheet(BuildContext context, {required Artifact fact}) {
  return showModalBottomSheet<void>(
    context: context,
    showDragHandle: true,
    isScrollControlled: true,
    builder: (_) => _FactMountSheet(factId: fact.id!),
  );
}

/// 未归属管理面板（§1.7 定稿修正，App 内主场=计划列表页提示行唤起）：复用
/// 挂载 sheet 常驻组折叠卡片形态——查看/长按改挂/删除（DeleteArtifact 仅 human）。
Future<void> showUnattributedFactsPanel(BuildContext context) {
  return showModalBottomSheet<void>(
    context: context,
    showDragHandle: true,
    isScrollControlled: true,
    builder: (_) => const _UnattributedFactsPanel(),
  );
}

class _FactMountSheet extends StatefulWidget {
  const _FactMountSheet({required this.factId});

  final String factId;

  @override
  State<_FactMountSheet> createState() => _FactMountSheetState();
}

class _FactMountSheetState extends State<_FactMountSheet> {
  bool _busy = false;

  CommandHandler get _handler => CommandHandler(AppServices.repo);

  Future<void> _mountTo(String planId) async {
    if (_busy) return;
    setState(() => _busy = true);
    try {
      final r = await _handler.execute(
        UpsertFactsCommand(id: widget.factId, planId: planId),
        actor: CommandActor.human,
      );
      if (!mounted) return;
      Navigator.of(context).pop();
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(r.note ?? '已归属'), duration: const Duration(seconds: 2)),
      );
    } on ActionException catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text(e.message), duration: const Duration(seconds: 3)));
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: AppServices.repo,
      builder: (context, _) => FutureBuilder<(Artifact?, List<Plan>, List<Artifact>)>(
        future: Future.wait([
          AppServices.repo.artifactById(widget.factId),
          AppServices.repo.listPlans(),
          AppServices.repo.artifactsUnattributed(),
        ]).then((l) => (l[0] as Artifact?, l[1] as List<Plan>, l[2] as List<Artifact>)),
        builder: (context, snap) {
          if (snap.connectionState != ConnectionState.done) {
            return const Padding(
                padding: EdgeInsets.all(40), child: Center(child: CircularProgressIndicator()));
          }
          final (fact, plans, unattr) = snap.data!;
          return SingleChildScrollView(
            padding: const EdgeInsets.fromLTRB(20, 0, 20, 20),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                if (fact != null) ...[
                  _UnattributedGroup(unattr: unattr),
                  const SizedBox(height: 8),
                  _sharePreview(fact),
                  const SizedBox(height: 8),
                  Text('归到哪条计划？',
                      style: Theme.of(context).textTheme.titleSmall),
                  const SizedBox(height: 4),
                  for (final p in plans)
                    ListTile(
                      dense: true,
                      onTap: _busy ? null : () => _mountTo(p.id!),
                      leading: const Icon(Icons.folder_outlined, size: 18),
                      title: Text(p.title,
                          maxLines: 1, overflow: TextOverflow.ellipsis),
                    ),
                  TextButton(
                    onPressed: _busy ? null : () => Navigator.of(context).pop(),
                    child: const Text('先记下，稍后归属'),
                  ),
                ] else
                  const Padding(
                      padding: EdgeInsets.all(24), child: Text('这条凭证不存在了')),
              ],
            ),
          );
        },
      ),
    );
  }

  /// 预填原文（raw 一字不解析，逐字展示）。
  Widget _sharePreview(Artifact fact) {
    final raw = fact.payload['raw_text'] as String? ?? '';
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(StScale.insetMd),
      decoration: BoxDecoration(
        color: StColors.voucherBg,
        borderRadius: BorderRadius.circular(StScale.radiusBlock),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(fact.title,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: Theme.of(context).textTheme.bodySmall?.copyWith(fontWeight: FontWeight.w600)),
          if (raw.isNotEmpty)
            Padding(
              padding: const EdgeInsets.only(top: 6),
              child: SelectableText(raw,
                  style: Theme.of(context)
                      .textTheme
                      .bodySmall
                      ?.copyWith(color: StColors.textSecondary)),
            ),
        ],
      ),
    );
  }
}

/// 「未归属 N 条」折叠组（挂载 sheet 常驻组 + 管理面板共用形态）。
class _UnattributedGroup extends StatefulWidget {
  const _UnattributedGroup({required this.unattr});

  final List<Artifact> unattr;

  @override
  State<_UnattributedGroup> createState() => _UnattributedGroupState();
}

class _UnattributedGroupState extends State<_UnattributedGroup> {
  bool _expanded = false;

  @override
  Widget build(BuildContext context) {
    if (widget.unattr.isEmpty) return const SizedBox.shrink();
    return Card(
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
                Text('未归属 · ${widget.unattr.length} 条',
                    style: Theme.of(context)
                        .textTheme
                        .labelMedium
                        ?.copyWith(color: StColors.textSecondary)),
              ]),
            ),
          ),
          if (_expanded)
            for (final f in widget.unattr)
              ListTile(
                dense: true,
                onTap: () => showVoucherSheet(context, f),
                onLongPress: () => _remount(context, f),
                title: Text(f.title,
                    maxLines: 1, overflow: TextOverflow.ellipsis),
                subtitle: Text(
                    f.state == Artifact.stateRaw ? '原文待提炼' : factCategoryLabel(f.category),
                    style: TextStyle(fontSize: 11, color: StColors.textSecondary)),
              ),
        ],
      ),
    );
  }

  /// 长按改挂：计划列表选其一（同 upsert_facts plan_id 改挂，human）。
  Future<void> _remount(BuildContext context, Artifact fact) async {
    final plans = await AppServices.repo.listPlans();
    if (!context.mounted) return;
    await showModalBottomSheet<void>(
      context: context,
      showDragHandle: true,
      isScrollControlled: true,
      builder: (_) => SafeArea(
        child: Padding(
          padding: const EdgeInsets.fromLTRB(20, 0, 20, 20),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Text('把「${fact.title}」挂到哪条计划？',
                  style: Theme.of(context).textTheme.titleSmall),
              const SizedBox(height: 8),
              if (plans.isEmpty)
                Padding(
                    padding: const EdgeInsets.all(12),
                    child: const Text('还没有计划——先建一条计划',
                        textAlign: TextAlign.center)),
              for (final p in plans)
                ListTile(
                  dense: true,
                  onTap: () async {
                    try {
                      final r = await CommandHandler(AppServices.repo)
                          .execute(UpsertFactsCommand(id: fact.id!, planId: p.id!));
                      if (context.mounted) {
                        Navigator.of(context).pop();
                        ScaffoldMessenger.of(context).showSnackBar(
                          SnackBar(content: Text(r.note ?? '已改挂'), duration: const Duration(seconds: 2)),
                        );
                      }
                    } on ActionException catch (e) {
                      if (context.mounted) {
                        ScaffoldMessenger.of(context)
                            .showSnackBar(SnackBar(content: Text(e.message)));
                      }
                    }
                  },
                  title: Text(p.title,
                      maxLines: 1, overflow: TextOverflow.ellipsis),
                ),
            ],
          ),
        ),
      ),
    );
  }
}

/// 未归属管理面板（计划列表页提示行唤起；查看/长按改挂/删除）。
class _UnattributedFactsPanel extends StatelessWidget {
  const _UnattributedFactsPanel();

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: AppServices.repo,
      builder: (context, _) => FutureBuilder<List<Artifact>>(
        future: AppServices.repo.artifactsUnattributed(),
        builder: (context, snap) {
          final unattr = snap.data ?? const <Artifact>[];
          if (snap.connectionState != ConnectionState.done) {
            return const Padding(
                padding: EdgeInsets.all(40), child: Center(child: CircularProgressIndicator()));
          }
          if (unattr.isEmpty) {
            return Padding(
                padding: const EdgeInsets.all(32),
                child: Text('未归属凭证都处理完了',
                    textAlign: TextAlign.center,
                    style: TextStyle(color: StColors.textSecondary)));
          }
          return SingleChildScrollView(
            padding: const EdgeInsets.fromLTRB(12, 0, 12, 20),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Padding(
                  padding: const EdgeInsets.fromLTRB(12, 8, 12, 4),
                  child: Text('未归属凭证', style: Theme.of(context).textTheme.titleSmall),
                ),
                for (final f in unattr)
                  Dismissible(
                    key: ValueKey(f.id),
                    direction: DismissDirection.endToStart,
                    background: Container(
                      alignment: Alignment.centerRight,
                      padding: const EdgeInsets.only(right: 20),
                      decoration: BoxDecoration(
                        color: StColors.fixedSlotBg,
                        borderRadius: BorderRadius.circular(StScale.radiusCard),
                      ),
                      child: const Icon(Icons.delete_outline, size: 18),
                    ),
                    confirmDismiss: (_) async =>
                        await _confirmDelete(context, f),
                    onDismissed: (_) {},
                    child: Padding(
                      padding: const EdgeInsets.symmetric(vertical: 4),
                      child: FactCard(
                        fact: f,
                        onTap: () => showVoucherSheet(context, f),
                        onLongPress: () => _remountPanel(context, f),
                      ),
                    ),
                  ),
              ],
            ),
          );
        },
      ),
    );
  }
}

/// 删除二次确认（§0.4：删除=DeleteArtifact 仅 human；删凭证绝不删块）。
Future<bool> _confirmDelete(BuildContext context, Artifact fact) async {
  final ok = await showDialog<bool>(
    context: context,
    builder: (dialogContext) => AlertDialog(
      title: Text('删除「${fact.title}」？'),
      content: const Text('删除后无法恢复。'),
      actions: [
        TextButton(
            onPressed: () => Navigator.of(dialogContext).pop(false),
            child: const Text('取消')),
        FilledButton(
            onPressed: () => Navigator.of(dialogContext).pop(true),
            child: const Text('删除')),
      ],
    ),
  );
  if (ok != true) return false;
  try {
    final r = await CommandHandler(AppServices.repo)
        .execute(DeleteArtifactCommand(fact.id!), actor: CommandActor.human);
    if (context.mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text(r.note ?? '已删除'), duration: const Duration(seconds: 2)));
    }
  } on ActionException catch (e) {
    if (context.mounted) {
      ScaffoldMessenger.of(context)
          .showSnackBar(SnackBar(content: Text(e.message)));
    }
  }
  return true;
}

/// 面板内长按改挂（与挂载 sheet 常驻组同源）。
Future<void> _remountPanel(BuildContext context, Artifact fact) async {
  final plans = await AppServices.repo.listPlans();
  if (!context.mounted) return;
  await showModalBottomSheet<void>(
    context: context,
    showDragHandle: true,
    isScrollControlled: true,
    builder: (_) => SafeArea(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(20, 0, 20, 20),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text('把「${fact.title}」挂到哪条计划？',
                style: Theme.of(context).textTheme.titleSmall),
            const SizedBox(height: 8),
            if (plans.isEmpty)
              Padding(
                  padding: const EdgeInsets.all(12),
                  child: const Text('还没有计划——先建一条计划',
                      textAlign: TextAlign.center)),
            for (final p in plans)
              ListTile(
                dense: true,
                onTap: () async {
                  try {
                    final r = await CommandHandler(AppServices.repo)
                        .execute(UpsertFactsCommand(id: fact.id!, planId: p.id!));
                    if (context.mounted) {
                      Navigator.of(context).pop();
                      ScaffoldMessenger.of(context).showSnackBar(
                        SnackBar(content: Text(r.note ?? '已改挂'), duration: const Duration(seconds: 2)),
                      );
                    }
                  } on ActionException catch (e) {
                    if (context.mounted) {
                      ScaffoldMessenger.of(context)
                          .showSnackBar(SnackBar(content: Text(e.message)));
                    }
                  }
                },
                title: Text(p.title,
                    maxLines: 1, overflow: TextOverflow.ellipsis),
              ),
          ],
        ),
      ),
    ),
  );
}

List<String> _strList(Object? v) =>
    (v is List) ? v.whereType<String>().toList() : const [];

/// 琥珀常驻条（一致性提示，派生不入库）。
class _AmberBar extends StatelessWidget {
  const _AmberBar({required this.text, required this.onTap});

  final String text;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return Material(
      color: StColors.voucherBg,
      borderRadius: BorderRadius.circular(StScale.radiusBlock),
      child: InkWell(
        borderRadius: BorderRadius.circular(StScale.radiusBlock),
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
          child: Row(children: [
            const Icon(Icons.warning_amber_rounded, size: 16),
            const SizedBox(width: 6),
            Expanded(
              child: Text(text, style: Theme.of(context).textTheme.bodySmall),
            ),
          ]),
        ),
      ),
    );
  }
}

/// 类别药丸微标（Header 层）。
class _Pill extends StatelessWidget {
  const _Pill({required this.icon, required this.label});

  final IconData icon;
  final String label;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
      decoration: BoxDecoration(
        color: StColors.fixedSlotBg,
        borderRadius: BorderRadius.circular(StScale.radiusCapsule),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(icon, size: 12, color: StColors.textSecondary),
          const SizedBox(width: 3),
          Text(label,
              style: TextStyle(
                  fontSize: 11, color: StColors.textSecondary)),
        ],
      ),
    );
  }
}

/// 约束 Chip（三数组语义分色；🚫 禁止=中性灰，绝不用红）。
class _Chip extends StatelessWidget {
  const _Chip({required this.icon, required this.text, required this.color});

  final IconData icon;
  final String text;
  final Color color;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
      decoration: BoxDecoration(
        color: color,
        borderRadius: BorderRadius.circular(StScale.radiusCapsule),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(icon, size: 12, color: StColors.textSecondary),
          const SizedBox(width: 4),
          Text(text, style: const TextStyle(fontSize: 11)),
        ],
      ),
    );
  }
}

/// 溯源手风琴（默认折叠；raw_text 永存原文）。
class _Accordion extends StatefulWidget {
  const _Accordion({required this.label, required this.text});

  final String label;
  final String text;

  @override
  State<_Accordion> createState() => _AccordionState();
}

class _AccordionState extends State<_Accordion> {
  bool _open = false;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(top: 12),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          InkWell(
            onTap: () => setState(() => _open = !_open),
            child: Padding(
              padding: const EdgeInsets.symmetric(vertical: 4),
              child: Row(children: [
                Icon(
                  _open ? Icons.expand_less : Icons.expand_more,
                  size: 16,
                  color: StColors.textSecondary,
                ),
                const SizedBox(width: 4),
                Text(widget.label,
                    style: Theme.of(context)
                        .textTheme
                        .bodySmall
                        ?.copyWith(color: StColors.textSecondary)),
              ]),
            ),
          ),
          if (_open)
            Container(
              width: double.infinity,
              padding: const EdgeInsets.all(StScale.insetMd),
              decoration: BoxDecoration(
                color: StColors.projectionBg,
                borderRadius: BorderRadius.circular(StScale.radiusBlock),
              ),
              child: SelectableText(
                widget.text,
                style: Theme.of(context).textTheme.bodySmall,
              ),
            ),
        ],
      ),
    );
  }
}
