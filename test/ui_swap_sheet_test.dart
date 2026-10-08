import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shiguang/action/command_handler.dart';
import 'package:shiguang/action/commands.dart';
import 'package:shiguang/app_services.dart';
import 'package:shiguang/main.dart';
import 'package:shiguang/models/plan.dart';

import 'helpers/ui.dart';

/// 三批回归 UI 契约（2026-10-08 拍板）：
/// ①D6 换乘抽屉=左滑过卡点呼出候选列表（不再自动换第一候选），显式挑选落库；
/// ②D7 否决原因=弹可选输入框，留空直接否决、带原因入 note。

// seed 助手走顶层变量注入 tester（一测一文件，无并发互扰）
late WidgetTester tester0;

void main() {
  setUp(() async {
    await setUpUiTest();
  });

  testWidgets('左滑换乘：呼出候选抽屉 → 显式挑选 → 原块删除+新块落库', (tester) async {
    tester0 = tester;
    tester.view.physicalSize = const Size(900, 1600);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);
    await real(tester, seedSettings);
    final deepId = await real(tester,
        () async => (await AppServices.repo.addPlan(Plan(title: '写周报', energyLevel: Plan.energyDeep))).id!);
    final lightId = await real(tester,
        () async => (await AppServices.repo.addPlan(Plan(title: '回消息', energyLevel: Plan.energyLight))).id!);
    final today = await real(tester, todayIso);
    final blockId = await real(tester, () async {
      final r = await CommandHandler(AppServices.repo).execute(PlaceBlockCommand(
        date: today,
        startMin: 540,
        endMin: 600,
        planId: deepId,
      ));
      return r.targetId!;
    });

    await tester.pumpWidget(const ShiguangApp());
    await pumpFlush(tester);

    // 左滑过换乘卡点（35%）：不再自动换，呼出抽屉
    await tester.drag(find.byKey(ValueKey('block-$blockId')), const Offset(-420, 2));
    await pumpFlush(tester);
    expect(find.text('换件轻松的？'), findsOneWidget, reason: '左滑呼出候选抽屉（§6.4 拍板回归）');
    expect(find.text('回消息'), findsWidgets, reason: 'light/anywhere 候选在列');

    // 显式挑选 → swap：原块删除、同位新块挂「回消息」
    await tester.tap(find.text('回消息').last);
    await pumpFlush(tester);
    await waitFor(tester, () async {
      final blocks =
          await AppServices.repo.blocksOnDate(await todayIso());
      return blocks.length == 1 && blocks.first.planId == lightId;
    });
    final fresh = await real(
        tester, () async => AppServices.repo.blocksOnDate(await todayIso()));
    expect(fresh.single.startMin, 540, reason: '同位换乘（时段不变）');
    expect(fresh.single.id, isNot(blockId), reason: '换入新块，原任务回池');
  });

}
