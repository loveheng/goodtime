import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shiguang/main.dart';
import 'package:shiguang/theme/tokens.dart';
import 'helpers/ui.dart';

/// 夜间模式（2026-10-06 拍板）：外观三档联动——默认跟随系统、深色面板置位、
/// 设置页切档生效。一测一文件（helpers/ui.dart 约定：UI 流程跨测试异步残留互扰）。
void main() {
  setUp(() async {
    await setUpUiTest();
  });

  testWidgets('外观三档：默认跟随系统 → 深色生效 → 浅色生效', (tester) async {
    await real(tester, seedSettings);
    await tester.pumpWidget(const ShiguangApp());
    await pumpFlush(tester);

    // 1. 未设置时跟随系统（测试环境平台亮度=浅色），语义面板=浅色
    expect(tester.widget<MaterialApp>(find.byType(MaterialApp)).themeMode,
        ThemeMode.system);
    expect(Theme.of(tester.element(find.byType(Scaffold).first)).brightness,
        Brightness.light);
    expect(StColors.textPrimary, const Color(0xFF1A1C1E));

    // 2. 设置页切「深色」→ 主题与全局面板联动（设置=底部第三 Tab，2026-10-08 拍板）
    await tester.tap(find.text('设置').last);
    await pumpFlush(tester);
    // 外观段在列表折叠边缘（树内有/视口外），滚到完全可见再点（2026-10-08 子页化后）
    await _scrollToText(tester, '深色');
    await tester.tap(find.text('深色'));
    await pumpFlush(tester);
    await tester.pump(const Duration(seconds: 1));
    expect(tester.widget<MaterialApp>(find.byType(MaterialApp)).themeMode,
        ThemeMode.dark);
    expect(StColors.textPrimary, const Color(0xFFE3E2E0));
    final stored = await real(tester, () => repo.settingsGet('theme_mode'));
    expect(stored, 'dark');

    // 3. 切回「浅色」
    await _scrollToText(tester, '浅色');
    await tester.tap(find.text('浅色'));
    await pumpFlush(tester);
    await tester.pump(const Duration(seconds: 1));
    expect(tester.widget<MaterialApp>(find.byType(MaterialApp)).themeMode,
        ThemeMode.light);
    expect(StColors.textPrimary, const Color(0xFF1A1C1E));
  });
}

/// 在设置页列表中滚动直到目标文本**完全可见**（树内≠视口内：ListView
/// cacheExtent 会预构建视口外 ~250px 的条目，直接 tap 会落空——2026-10-08
/// 子页化后外观段恰在折叠边缘踩中此坑）。固定 pump（设置页有常驻帧源，
/// pumpAndSettle 永不收敛，见项目约定）。
Future<void> _scrollToText(WidgetTester tester, String text) async {
  for (var i = 0; i < 40; i++) {
    if (find.text(text).evaluate().isNotEmpty) {
      await tester.ensureVisible(find.text(text));
      await pumpFlush(tester);
      return;
    }
    await tester.fling(
        find.byType(Scrollable).first, const Offset(0, -300), 1000);
    for (var k = 0; k < 12; k++) {
      await tester.pump(const Duration(milliseconds: 50));
    }
  }
  fail('滚动超时：未找到文本「$text」');
}
