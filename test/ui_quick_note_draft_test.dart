import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shiguang/main.dart';
import 'package:shiguang/ui/quick_note_fab.dart';
import 'helpers/ui.dart';

/// 快记草稿生命周期（2026-10-06 拍板）：静默持久化、琥珀点、回填、双清通道。
/// 一测一文件（helpers/ui.dart 约定）：UI 流程测试跨测试真实异步残留会互相干扰。
void main() {
  setUp(() async {
    await setUpUiTest();
  });

  Future<void> openDrawer(WidgetTester tester) async {
    await tester.tap(find.byTooltip('记一笔'));
    await pumpFlush(tester);
  }

  Future<void> closeDrawer(WidgetTester tester) async {
    final navigator = tester.state<NavigatorState>(find.byType(Navigator));
    navigator.pop();
    await pumpFlush(tester);
  }

  Finder draftDot() => find.byKey(const Key('quick_note_draft_dot'));

  testWidgets('快记草稿全生命周期：中途收起零询问→回填→保存清稿→清空清稿', (tester) async {
    await real(tester, seedSettings);
    await tester.pumpWidget(const ShiguangApp());
    await pumpFlush(tester);

    // 1. 半途收起：零询问，草稿静默保留 + FAB 琥珀点点亮
    await openDrawer(tester);
    await tester.enterText(find.byType(TextField), '下周四看牙医');
    await pumpFlush(tester); // 冲过 300ms 防抖落盘
    await closeDrawer(tester);
    await waitFor(tester, () async => QuickNoteDraft.hasDraft.value);
    expect(draftDot(), findsOneWidget);

    // 2. 重开回填，保存成功即清草稿
    await openDrawer(tester);
    expect(find.text('下周四看牙医'), findsOneWidget);
    await tester.tap(find.text('收入清单'));
    await pumpFlush(tester);
    await waitFor(tester, () async {
      final list = await repo.listPlans();
      return list.isNotEmpty && list.first.title == '下周四看牙医';
    });
    await waitFor(tester, () async => !QuickNoteDraft.hasDraft.value);
    expect(draftDot(), findsNothing);

    // 等「已收入清单」SnackBar 退场（1s 时长+动画），避免遮住 FAB
    await tester.pump(const Duration(seconds: 2));
    await tester.pump(const Duration(seconds: 1));

    // 3. 清空后收起：显式清草稿通道
    await openDrawer(tester);
    await tester.enterText(find.byType(TextField), '临时念头');
    await pumpFlush(tester);
    await waitFor(tester, () async => QuickNoteDraft.hasDraft.value);
    await tester.enterText(find.byType(TextField), '');
    await pumpFlush(tester);
    await closeDrawer(tester);
    await waitFor(tester, () async => !QuickNoteDraft.hasDraft.value);
    expect(draftDot(), findsNothing);
    // 收尾冲刷：退掉一切残余 Timer（SnackBar/防抖尾巴），防 pending-timer 断言偶发
    await tester.pump(const Duration(seconds: 4));
    await tester.pump(const Duration(seconds: 1));
  });
}
