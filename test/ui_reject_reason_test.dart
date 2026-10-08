import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shiguang/action/command_handler.dart';
import 'package:shiguang/action/commands.dart';
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

  testWidgets('确认卡全部否决：原因弹框留空直接否决，块删除', (tester) async {
    tester0 = tester;
    tester.view.physicalSize = const Size(900, 1600);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);
    await real(tester, seedSettings);
    final planId = await real(tester,
        () async => (await repo.addPlan(Plan(title: '有个考试'))).id!);
    final today = await real(tester, todayIso);
    await real(tester, () => CommandHandler(repo).execute(
          ProposeScheduleCommand(date: today, items: [
            ProposedItem(planId: planId, label: '高数复习', startMin: 540, endMin: 600, isDaySpark: true),
          ]),
          actor: CommandActor.ai,
        ));
    await tester.pumpWidget(const ShiguangApp());
    await pumpFlush(tester);

    // 全部否决 → 原因弹框（可选输入）→ 留空直接否决
    expect(find.widgetWithText(OutlinedButton, '全部否决'), findsOneWidget,
        reason: '确认卡展开态含全部否决键');
    await tester.tap(find.widgetWithText(OutlinedButton, '全部否决'));
    await pumpFlush(tester);
    await tester.pump(const Duration(milliseconds: 300));
    expect(find.text('全部否决这 1 项提案？'), findsOneWidget,
        reason: '批量否决弹可选原因框（§3.4 回归；标题带提案数）');
    await tester.tap(find.widgetWithText(FilledButton, '否决'));
    await pumpFlush(tester);
    await waitFor(tester, () async {
      final blocks = await repo.blocksOnDate(today);
      return blocks.isEmpty;
    });
    final plan = await real(tester, () => repo.planById(planId));
    expect(plan, isNotNull, reason: '否决删块不删 plan');
  });
}
