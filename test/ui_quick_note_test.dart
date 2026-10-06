import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shiguang/main.dart';
import 'package:shiguang/models/plan.dart';
import 'helpers/ui.dart';

/// 快记入口（2026-10-06 拍板 FAB+抽屉形态）：捕获落池。
/// 一测一文件（helpers/ui.dart 约定）：UI 流程测试跨测试真实异步残留会互相干扰。
void main() {
  setUp(() async {
    await setUpUiTest();
  });

  testWidgets('快记 FAB：展开抽屉 输入+星标 → 落清单愿望池并清草稿', (tester) async {
    await real(tester, seedSettings);
    await tester.pumpWidget(const ShiguangApp());
    await pumpFlush(tester);

    await tester.tap(find.byTooltip('记一笔'));
    await pumpFlush(tester);
    expect(find.text('记一笔，30 秒的事'), findsOneWidget);

    await tester.enterText(find.byType(TextField), '想去云南玩');
    await tester.tap(find.byIcon(Icons.star_border));
    await pumpFlush(tester); // 星标即改即存
    await tester.tap(find.text('收入清单'));
    await pumpFlush(tester);

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
