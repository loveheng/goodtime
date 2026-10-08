import 'package:flutter/material.dart';

import '../action/command_handler.dart';
import '../action/commands.dart';
import '../app_services.dart';
import '../data/repository.dart';
import '../models/artifact.dart';
import '../theme/tokens.dart';
import 'fact_sheet.dart';

/// 计划「随行凭证」子页（ui-spec §1/§5 子页化 2026-10-08 拍板）：自
/// plan_detail_page 整区迁出（fact 草案 §2.1 入口 1；文案=ui-spec §0.4 凭证区组）
/// ——子树派生聚合（一趟旅行的根计划=行程凭证总览页）；raw=聚合琥珀卡
/// 「N 条原文待提炼」（金样本 A1 口径）；全部锚点最晚日历日早于今日沉
/// 「过去的凭证」折叠组；voided 不进卡面（读侧白名单挡掉）。
/// 写通道=AI 命令层+分享/快记入口，本区无 human 添加钮；删除仅 human 长按
/// （DeleteArtifact，删凭证绝不删块）。
class PlanFactsPage extends StatefulWidget {
  const PlanFactsPage({super.key, required this.planId});

  final String planId;

  @override
  State<PlanFactsPage> createState() => _PlanFactsPageState();
}

class _PlanFactsPageState extends State<PlanFactsPage> {
  Repository get _repo => AppServices.repo;

  /// 子树 id 集（根+全部子孙，≤3 级，与详情页「子计划」同口径）。
  Future<List<String>> _subtreeIds(String rootId) async {
    final out = <String>[rootId];
    Future<void> walk(String parentId, int depth) async {
      if (depth > 3) return;
      for (final k in await _repo.listPlans(parentId: parentId)) {
        out.add(k.id!);
        await walk(k.id!, depth + 1);
      }
    }

    await walk(rootId, 1);
    return out;
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('随行凭证')),
      body: ListenableBuilder(
        listenable: _repo,
        builder: (context, _) => FutureBuilder<List<Artifact>>(
          future: _subtreeIds(widget.planId)
              .then((ids) => _repo.artifactsForPlans(ids)),
          builder: (context, snap) {
            final all = snap.data ?? const <Artifact>[];
            if (all.isEmpty) {
              return Center(
                child: Text('还没有凭证——车票/门票/预订信息分享给拾光就会出现在这',
                    textAlign: TextAlign.center,
                    style: Theme.of(context)
                        .textTheme
                        .bodySmall
                        ?.copyWith(color: StColors.textSecondary)),
              );
            }
            final active = all.where((f) => !factExpired(f)).toList()
              ..sort((a, b) => (a.createdAt ?? 0).compareTo(b.createdAt ?? 0));
            final expired = all.where(factExpired).toList();
            final raw =
                active.where((f) => f.state == Artifact.stateRaw).toList();
            final structured =
                active.where((f) => f.state != Artifact.stateRaw).toList();
            return ListView(
              padding: const EdgeInsets.all(16),
              children: [
                if (raw.isNotEmpty) _RawFactsCard(raw: raw),
                for (final f in structured)
                  FactCard(
                    fact: f,
                    onTap: () => showVoucherSheet(context, f),
                    onLongPress: () => _confirmDelete(context, f),
                  ),
                if (expired.isNotEmpty)
                  _PastFactsGroup(
                    expired: expired,
                    onOpen: (f) => showVoucherSheet(context, f),
                    onDelete: _confirmDelete,
                  ),
              ],
            );
          },
        ),
      ),
    );
  }

  /// 删除二次确认（§0.4：仅 human；删凭证绝不删块——块处置归命令层）。
  Future<void> _confirmDelete(BuildContext context, Artifact fact) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: Text('删除「${fact.title}」？'),
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
      final r = await CommandHandler(_repo)
          .execute(DeleteArtifactCommand(fact.id!), actor: CommandActor.human);
      if (context.mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(content: Text(r.note ?? '已删除'),
                duration: const Duration(seconds: 2)));
      }
    } on ActionException catch (e) {
      if (context.mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text(e.message)));
      }
    }
  }
}

/// raw 聚合琥珀卡（金样本 A1：凭证区「N 条原文待提炼」）：1 条直开详情，
/// 多条开列表逐条点开（原文逐字，未解析无锚点日期）。
class _RawFactsCard extends StatelessWidget {
  const _RawFactsCard({required this.raw});

  final List<Artifact> raw;

  @override
  Widget build(BuildContext context) {
    return Material(
      color: StColors.voucherBg,
      borderRadius: BorderRadius.circular(StScale.radiusCard),
      child: InkWell(
        borderRadius: BorderRadius.circular(StScale.radiusCard),
        onTap: raw.length == 1
            ? () => showVoucherSheet(context, raw.single)
            : () => _listSheet(context),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
          child: Row(children: [
            const Icon(Icons.receipt_long_outlined, size: 18),
            const SizedBox(width: 8),
            Text(
                raw.length == 1 ? '1 条原文待提炼' : '${raw.length} 条原文待提炼',
                style: Theme.of(context).textTheme.bodyMedium),
          ]),
        ),
      ),
    );
  }

  Future<void> _listSheet(BuildContext context) {
    return showModalBottomSheet<void>(
      context: context,
      showDragHandle: true,
      isScrollControlled: true,
      builder: (_) => SafeArea(
        child: Padding(
          padding: const EdgeInsets.fromLTRB(12, 0, 12, 20),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Padding(
                padding: const EdgeInsets.fromLTRB(12, 8, 12, 4),
                child: Text('待提炼的原文',
                    style: Theme.of(context).textTheme.titleSmall),
              ),
              for (final f in raw)
                FactCard(
                  fact: f,
                  onTap: () {
                    Navigator.of(context).pop();
                    showVoucherSheet(context, f);
                  },
                ),
            ],
          ),
        ),
      ),
    );
  }
}

/// 「过去的凭证」折叠组（ui-spec §0.4）：全部锚点最晚日历日早于今日的派生
/// 折叠（span 取 end_date），与「过去的背景」同收纳模式；逐条点开通关卡、
/// 长按删除（§0.4 凭证删除）。
class _PastFactsGroup extends StatefulWidget {
  const _PastFactsGroup({required this.expired, required this.onOpen, required this.onDelete});

  final List<Artifact> expired;
  final void Function(Artifact) onOpen;
  final void Function(BuildContext, Artifact) onDelete;

  @override
  State<_PastFactsGroup> createState() => _PastFactsGroupState();
}

class _PastFactsGroupState extends State<_PastFactsGroup> {
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
                Text('过去的凭证 · ${widget.expired.length} 条',
                    style: Theme.of(context)
                        .textTheme
                        .labelMedium
                        ?.copyWith(color: StColors.textSecondary)),
              ]),
            ),
          ),
          if (_expanded)
            for (final f in widget.expired)
              FactCard(
                fact: f,
                onTap: () => widget.onOpen(f),
                onLongPress: () => widget.onDelete(context, f),
              ),
        ],
      ),
    );
  }
}
