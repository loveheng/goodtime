import 'package:flutter_test/flutter_test.dart';
import 'package:shiguang/models/plan.dart';
import 'package:flutter/material.dart';
import 'package:shiguang/main.dart';
import 'helpers/ui.dart';

void main() {
  setUpAll(() {});

  setUp(() async {
    await setUpUiTest();
  });

  testWidgets('快记条：输入+星标 → 落清单愿望池', (tester) async {
    await real(tester, seedSettings);
    await tester.pumpWidget(const ShiguangApp());
    await pumpFlush(tester);

    await tester.enterText(find.byType(TextField), '想去云南玩');
    await tester.tap(find.byIcon(Icons.star_border));
    await tester.tap(find.byTooltip('收入清单'));

    await waitFor(tester, () async {
      final list = await repo.listPlans();
      return list.isNotEmpty && list.first.importance;
    });
    final plans = await real(tester, () => repo.listPlans());
    expect(plans.single.title, '想去云南玩');
    expect(plans.single.importance, isTrue);
    expect(plans.single.energyLevel, Plan.energyLight);
  });
}
