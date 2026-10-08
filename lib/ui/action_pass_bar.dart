import 'package:flutter/material.dart';

import '../action/fact_rules.dart';
import '../data/repository.dart';
import '../models/artifact.dart';
import '../theme/tokens.dart';

/// 临场通关条（Action Pass Bar，fact 草案 §9.1/§9.4，分期二）：插在系统 Banner 下、
/// 遗留区上，不挤掉双轨仪表盘。多条并存时按行动时刻最早者展开，其余折叠为「+N」胶囊。
/// **本地物理时钟判定**（computeActionPasses 已按 DateTime.now() 过滤窗口，禁用 toUtc）。
class ActionPassBar extends StatelessWidget {
  const ActionPassBar({super.key, required this.passes, this.onTap});

  final List<ActionPass> passes;
  final void Function(ActionPass)? onTap;

  static String _hm(DateTime t) =>
      '${t.hour.toString().padLeft(2, '0')}:${t.minute.toString().padLeft(2, '0')}';

  @override
  Widget build(BuildContext context) {
    if (passes.isEmpty) return const SizedBox.shrink();
    final primary = passes.first;
    final extra = passes.length - 1;
    final advance = _advanceMin(primary.fact);
    final scheme = Theme.of(context).colorScheme;
    return Container(
      margin: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
      decoration: BoxDecoration(
        color: StColors.voucherBg,
        borderRadius: BorderRadius.circular(StScale.radiusCard),
        border: Border.all(color: scheme.primary.withValues(alpha: 0.35)),
      ),
      child: InkWell(
        onTap: () => onTap?.call(primary),
        child: Row(children: [
          const Icon(Icons.confirmation_number_outlined, size: 18),
          const SizedBox(width: 8),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  '${_hm(primary.actionTime)} ${FactRules.roleLabel(primary.role)} · ${primary.title}',
                  style: Theme.of(context).textTheme.bodyMedium,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                ),
                if (advance != null && advance > 0)
                  Text('建议 ${_hm(primary.actionTime.subtract(Duration(minutes: advance)))} 前到站',
                      style: TextStyle(fontSize: 11, color: StColors.textSecondary)),
              ],
            ),
          ),
          if (extra > 0)
            Container(
              margin: const EdgeInsets.only(left: 8),
              padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
              decoration: BoxDecoration(
                color: scheme.primaryContainer,
                borderRadius: BorderRadius.circular(StScale.radiusCapsule),
              ),
              child: Text('+$extra',
                  style: TextStyle(fontSize: 11, color: scheme.onPrimaryContainer)),
            ),
        ]),
      ),
    );
  }

  static int? _advanceMin(Artifact a) {
    final c = a.payload['constraints'] as Map<String, Object?>?;
    final v = c?['advance_arrival_minutes'];
    return v is int ? v : null;
  }
}

/// 今日页通关条数据源：取全部计划的结构化凭证，按本地现在时刻算出可见通关条。
Future<List<ActionPass>> dailyActionPasses(Repository repo) async {
  final plans = await repo.listPlans();
  final ids = [for (final p in plans) if (p.id != null) p.id!];
  final facts =
      ids.isEmpty ? const <Artifact>[] : await repo.artifactsForPlans(ids);
  return computeActionPasses(facts, DateTime.now());
}
