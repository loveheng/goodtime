import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shiguang/main.dart';
import 'package:shiguang/models/schedule_block.dart';
import 'package:shiguang/ui/global_action_sheet.dart';
import 'helpers/ui.dart';

/// 全局动作 sheet（ui-spec §3.6）：入口可达、剩余计数、两段确认取消路径。
/// 一测一文件（helpers/ui.dart 约定）。nowMin 注入固定时刻保证确定性；
/// 执行链的 DB 语义由 panic_command_test 覆盖（写+notify 后的 runAsync 轮询
/// 在本夹具下会卡死——devlog 坑7，挂账）。
void main() {
  setUp(() async {
    await setUpUiTest();
  });

  testWidgets('全局动作 sheet：入口 + 剩余计数 + 两段确认取消路径', (tester) async {
    await tester.binding.setSurfaceSize(const Size(800, 1400));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    await real(tester, seedSettings);
    final todayStr = await real(tester, todayIso);
    await real(tester, () async {
      final b = await repo.addBlock(ScheduleBlock(
        date: todayStr,
        startMin: 600,
        endMin: 660,
        label: 'AI事项',
        source: ScheduleBlock.sourceAi,
      ));
      await repo.patchBlock(b.id!, {'status': ScheduleBlock.statusConfirmed});
    });

    await tester.pumpWidget(const ShiguangApp());
    await pumpFlush(tester);

    // 入口：header 图标钮打开 sheet
    await tester.tap(find.byTooltip('遇到突发情况？'));
    await pumpFlush(tester);
    expect(find.text('遇到突发情况？'), findsOneWidget);
    final navigator = tester.state<NavigatorState>(find.byType(Navigator));
    navigator.pop(); // 收起，改用注入 nowMin 的确定性调用
    await pumpFlush(tester);

    // 注入 nowMin=500：600–660 的 AI 块为「未开始」
    final context = tester.element(find.byType(Scaffold).first);
    final blocks = await real(tester, () => repo.blocksOnDate(todayStr));
    unawaited(showGlobalActionSheet(context, blocks, nowMin: 500));
    await pumpFlush(tester);
    expect(find.text('1 项未开始的 AI 安排'), findsNWidgets(2));

    // 两段确认：选「全清空」→ 对话框出现 → 取消 → sheet 保留
    await tester.tap(find.text('今天下午全清空（剩余计划退回清单）'));
    await pumpFlush(tester);
    expect(find.text('确认执行'), findsOneWidget);
    await tester.tap(find.text('取消'));
    await pumpFlush(tester);
    expect(find.text('遇到突发情况？'), findsOneWidget);
  });
}
