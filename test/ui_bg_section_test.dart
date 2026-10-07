import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shiguang/main.dart';
import 'package:shiguang/models/background.dart';
import 'package:shiguang/models/plan.dart';
import 'package:shiguang/util/schedule_day.dart';
import 'helpers/ui.dart';

/// 计划详情「背景」区流程（背景草案 §3 入口 1；文案=ui-spec §0.4；一测一文件约定）。
void main() {
  setUp(() async {
    await setUpUiTest();
  });

  testWidgets('背景区：添加（长期）上屏、日期角标渲染、过期沉折叠组、组内删除二次确认',
      (tester) async {
    await real(tester, seedSettings);
    final planId = await real(tester, () async => (await repo.addPlan(Plan(title: '云南七天'))).id!);
    // 预置：一条过期（整窗在过去）+ 一条当日窗（角标断言用）；UI 写通道在下方覆盖
    await real(tester, () => repo.addBackground(Background(
          scope: Background.scopePlan,
          planId: planId,
          content: '脚扭伤少走路',
          source: Background.sourceUser,
          applicableDates: const ['2026-01-01'],
        )));
    final d1 = DateTime(DateTime.now().year, DateTime.now().month, DateTime.now().day);
    final d2 = d1.add(const Duration(days: 1));
    String f(DateTime d) =>
        '${d.month.toString().padLeft(2, '0')}.${d.day.toString().padLeft(2, '0')}';
    await real(tester, () => repo.addBackground(Background(
          scope: Background.scopePlan,
          planId: planId,
          content: '复诊随访电话',
          source: Background.sourceUser,
          applicableDates: [isoDate(d1), isoDate(d2)],
        )));
    await tester.pumpWidget(const ShiguangApp());
    await pumpFlush(tester);
    await tester.tap(find.widgetWithText(NavigationDestination, '清单'));
    await pumpFlush(tester);

    // 点卡进全页详情（ui-spec §5 路由；全页滚动比 sheet 高，深位先滚后点坑9 同款）
    await tester.tap(find.text('云南七天'));
    await pumpFlush(tester);
    expect(find.text('背景'), findsOneWidget);
    expect(find.text('过去的背景 · 1 条'), findsOneWidget, reason: '过期条目沉折叠组');
    expect(find.text('复诊随访电话'), findsOneWidget);
    expect(find.text('[${f(d1)}–${f(d2)}]'), findsOneWidget, reason: '日期角标派生渲染');
    expect(find.text('脚扭伤少走路'), findsNothing, reason: '过期条目不占主列表');

    // 添加长期背景（无日期窗）：写通道=UpsertBackground（human）
    await tester.drag(find.byType(SingleChildScrollView).last, const Offset(0, -300));
    await pumpFlush(tester);
    await tester.tap(find.text('添加背景'));
    await pumpFlush(tester);
    expect(find.text('背景内容'), findsOneWidget);
    await tester.enterText(find.widgetWithText(TextField, '背景内容'), '妈妈膝盖不好，少爬坡');
    await tester.enterText(find.widgetWithText(TextField, '标签（空格分隔）'), '#健康 #体力');
    await tester.tap(find.text('保存').last);
    await waitFor(tester, () async {
      final list = await repo.listBackgrounds(scope: Background.scopePlan, planId: planId);
      return list.length == 3;
    });
    await pumpFlush(tester);
    expect(find.text('妈妈膝盖不好，少爬坡'), findsOneWidget);
    expect(find.text('#健康 #体力'), findsOneWidget, reason: '标签小字随卡渲染');

    // 展开折叠组 → 逐条删除 → 二次确认 → 台账消失（深位控件先滚后点，坑9）
    await tester.tap(find.text('过去的背景 · 1 条'));
    await pumpFlush(tester);
    expect(find.text('脚扭伤少走路'), findsOneWidget);
    await tester.scrollUntilVisible(
      find.text('删除').first,
      120,
      scrollable: find.byType(Scrollable).last,
    );
    await pumpFlush(tester);
    await tester.tap(find.text('删除').first);
    await pumpFlush(tester);
    expect(find.text('删除这条背景？'), findsOneWidget, reason: '二次确认');
    await tester.tap(find.widgetWithText(FilledButton, '删除'));
    await waitFor(tester, () async {
      final list = await repo.listBackgrounds(scope: Background.scopePlan, planId: planId);
      return list.length == 2;
    });
    await pumpFlush(tester);
    expect(find.text('过去的背景 · 1 条'), findsNothing, reason: '清空后折叠组随内容消失');
    expect(find.text('脚扭伤少走路'), findsNothing);
  });
}
