import 'package:flutter_test/flutter_test.dart';
import 'package:shiguang/action/command_handler.dart';
import 'package:shiguang/action/commands.dart';
import 'package:shiguang/models/plan.dart';

import 'package:flutter/material.dart';
import 'package:shiguang/main.dart';
import 'helpers/ui.dart';

void main() {
  setUpAll(() {});

  setUp(() async {
    await setUpUiTest();
  });

  testWidgets('清单三分区：今日有块进「今天」，无块进四象限愿望池', (tester) async {
    await real(tester, seedSettings);
    await real(tester, () async {
      final planA = await repo.addPlan(Plan(title: '要考的试'));
      await CommandHandler(repo).execute(
        ProposeScheduleCommand(date: await todayIso(), items: [
          ProposedItem(planId: planA.id, label: '复习', startMin: 540, endMin: 600),
        ]),
        actor: CommandActor.ai,
      );
      await repo.addPlan(Plan(title: '想去云南玩'));
    });
    await tester.pumpWidget(const ShiguangApp());
    await pumpFlush(tester);
    await tester.tap(find.widgetWithText(NavigationDestination, '清单'));
    await pumpFlush(tester);

    expect(find.text('今天'), findsOneWidget);
    expect(find.text('当下推进中'), findsNothing);
    expect(find.text('待安排'), findsOneWidget);
    expect(find.text('愿望池 · 1'), findsOneWidget);
    expect(find.text('要考的试'), findsOneWidget);
    expect(find.text('想去云南玩'), findsOneWidget);
  });
}
