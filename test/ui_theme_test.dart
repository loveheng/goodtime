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

    // 2. 设置页切「深色」→ 主题与全局语义面板联动
    await tester.tap(find.byTooltip('设置'));
    await pumpFlush(tester);
    await tester.tap(find.text('深色'));
    await pumpFlush(tester);
    await tester.pump(const Duration(seconds: 1));
    expect(tester.widget<MaterialApp>(find.byType(MaterialApp)).themeMode,
        ThemeMode.dark);
    expect(StColors.textPrimary, const Color(0xFFE3E2E0));
    final stored = await real(tester, () => repo.settingsGet('theme_mode'));
    expect(stored, 'dark');

    // 3. 切回「浅色」
    await tester.tap(find.text('浅色'));
    await pumpFlush(tester);
    await tester.pump(const Duration(seconds: 1));
    expect(tester.widget<MaterialApp>(find.byType(MaterialApp)).themeMode,
        ThemeMode.light);
    expect(StColors.textPrimary, const Color(0xFF1A1C1E));
  });
}
