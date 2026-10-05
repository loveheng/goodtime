import 'package:flutter_test/flutter_test.dart';
import 'package:shiguang/action/command_handler.dart';
import 'package:shiguang/action/commands.dart';
import 'package:shiguang/models/plan.dart';
import 'package:shiguang/models/schedule_block.dart';

import 'package:flutter/material.dart';
import 'package:shiguang/main.dart';
import 'helpers/ui.dart';

void main() {
  setUpAll(() {});

  setUp(() async {
    await setUpUiTest();
  });

  testWidgets('日视图：proposed 块渲染「提案」，确认键落 confirmed', (tester) async {
    await real(tester, seedSettings);
    final planId = await real(tester, () async {
      final plan = await repo.addPlan(Plan(title: '有个考试'));
      return plan.id!;
    });
    final today = await real(tester, todayIso);
    final proposed = await real(tester, () => CommandHandler(repo).execute(
          ProposeScheduleCommand(date: today, items: [
            ProposedItem(planId: planId, label: '高数复习', startMin: 540, endMin: 600, isDaySpark: true),
          ]),
          actor: CommandActor.ai,
        ));
    final blockId =
        ((proposed.data!['blocks'] as List).first as Map)['id'] as String;
    await tester.pumpWidget(const ShiguangApp());
    await pumpFlush(tester);

    expect(find.text('提案'), findsOneWidget);
    expect(find.textContaining('高数复习'), findsWidgets, reason: '分级确认卡与时间轴块都呈现行动名');
    // 点提案块卡片（分级确认卡与块文案重名，用块 id key 定位；大视口防裁剪）
    tester.view.physicalSize = const Size(900, 1600);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);
    await tester.pump(); // 视口变更后强制重排，否则 hit test 用陈旧几何
    await tester.tap(find.byKey(ValueKey('block-$blockId')));
    await pumpFlush(tester);
    await tester.tap(find.widgetWithText(FilledButton, '确认'));

    await waitFor(tester, () async {
      final blocks = await repo.blocksOnDate(today);
      return blocks.isNotEmpty && blocks.first.status == ScheduleBlock.statusConfirmed;
    });
    final blocks = await real(tester, () => repo.blocksOnDate(today));
    expect(blocks.single.status, ScheduleBlock.statusConfirmed);
  });
}
