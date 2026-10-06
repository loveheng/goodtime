import 'package:flutter/material.dart';

import '../action/command_handler.dart';
import '../action/commands.dart';
import '../app_services.dart';
import '../models/schedule_block.dart';
import '../theme/tokens.dart';
import '../util/schedule_day.dart';

/// 全局动作 sheet（ui-spec §3.6）：标题「遇到突发情况？」，三选项+两段式确认；
/// 「熔断优先于微调」一级直达。human 块与 pinned 硬行程永不动（和平条款），
/// 底部附注明示。清空/后移走 PanicClearCommand（命令层原子执行），
/// 降档走 today_energy=low + ReflowDay 水流重算。
///
/// [nowMin] 供测试注入「当前时刻」；UI 调用缺省取真实时钟。
Future<void> showGlobalActionSheet(
  BuildContext context,
  List<ScheduleBlock> todayBlocks, {
  int? nowMin,
}) {
  final now = nowMin ?? minutesOfDay(DateTime.now());
  final remaining = todayBlocks
      .where((b) =>
          b.source == ScheduleBlock.sourceAi &&
          !b.pinned &&
          (b.status == ScheduleBlock.statusProposed ||
              b.status == ScheduleBlock.statusConfirmed) &&
          b.startMin > now)
      .length;

  Future<void> confirmAndRun(
    BuildContext sheetContext, {
    required String title,
    required String impact,
    required Future<CommandResult> Function() run,
  }) async {
    final messenger = ScaffoldMessenger.of(sheetContext);
    final navigator = Navigator.of(sheetContext);
    final confirmed = await showDialog<bool>(
      context: sheetContext,
      builder: (dialogContext) => AlertDialog(
        title: Text(title),
        content: Text(impact),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(dialogContext).pop(false),
            child: const Text('取消'),
          ),
          FilledButton(
            onPressed: () => Navigator.of(dialogContext).pop(true),
            child: const Text('确认执行'),
          ),
        ],
      ),
    );
    if (confirmed != true) return;
    navigator.pop(); // 收起 sheet 再执行
    try {
      final r = await run();
      messenger.showSnackBar(SnackBar(
          content: Text(r.note ?? '完成'), duration: const Duration(seconds: 2)));
    } on ActionException catch (e) {
      messenger.showSnackBar(SnackBar(
          content: Text(e.message), duration: const Duration(seconds: 3)));
    }
  }

  return showModalBottomSheet<void>(
    context: context,
    showDragHandle: true,
    builder: (sheetContext) => SafeArea(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(20, 8, 20, 20),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text('遇到突发情况？', style: Theme.of(sheetContext).textTheme.titleLarge),
            const SizedBox(height: 4),
            ListTile(
              leading: const Text('☕', style: TextStyle(fontSize: 20)),
              title: const Text('今天下午全清空（剩余计划退回清单）'),
              subtitle: Text('$remaining 项未开始的 AI 安排',
                  style: TextStyle(color: StColors.textSecondary)),
              enabled: remaining > 0,
              onTap: () => confirmAndRun(
                sheetContext,
                title: '全清空今天剩余安排？',
                impact: '今天剩余的 $remaining 项 AI 安排将全部放回清单待安排（无痕、零心理负债），随时可以让 AI 重新排。',
                run: () => CommandHandler(AppServices.repo).execute(
                    PanicClearCommand(mode: 'clear_remaining', nowMin: now)),
              ),
            ),
            ListTile(
              leading: const Text('⏳', style: TextStyle(fontSize: 20)),
              title: const Text('整体往后推 2 小时'),
              subtitle: Text('$remaining 项未开始的 AI 安排',
                  style: TextStyle(color: StColors.textSecondary)),
              enabled: remaining > 0,
              onTap: () => confirmAndRun(
                sheetContext,
                title: '整体往后推 2 小时？',
                impact: '今天剩余的 $remaining 项 AI 安排将整体后移 2 小时；若撞上你的手动安排或固定占用，会整单拒绝不做半截。',
                run: () => CommandHandler(AppServices.repo)
                    .execute(PanicClearCommand(mode: 'push_2h', nowMin: now)),
              ),
            ),
            ListTile(
              leading: const Text('🪫', style: TextStyle(fontSize: 20)),
              title: const Text('今天精力见底，全部切换为 5 分钟启动版'),
              subtitle: Text('按低电量重算今天：压缩火种、溢出融化',
                  style: TextStyle(color: StColors.textSecondary)),
              onTap: () => confirmAndRun(
                sheetContext,
                title: '切换为 5 分钟启动版？',
                impact: '今天将按低电量重算：AI 安排压缩为 5 分钟启动版或无痕融化，核心尽量保住；固定日程不动。',
                run: () async {
                  await CommandHandler(AppServices.repo).execute(
                      const UpdateSettingsCommand(values: {'today_energy': 'low'}));
                  return CommandHandler(AppServices.repo)
                      .execute(const ReflowDayCommand());
                },
              ),
            ),
            const SizedBox(height: 4),
            Text('你的固定日程和自己排的事项不会被改动。',
                textAlign: TextAlign.center,
                style: Theme.of(sheetContext)
                    .textTheme
                    .bodySmall
                    ?.copyWith(color: StColors.textSecondary)),
          ],
        ),
      ),
    ),
  );
}
