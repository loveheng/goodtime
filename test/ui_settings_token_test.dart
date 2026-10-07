import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shiguang/app_services.dart';
import 'package:shiguang/ui/settings_page.dart';

import 'helpers/ui.dart';

/// 设置页 MCP 令牌行：复制按钮把完整令牌写入剪贴板（真机配对免手抄 24 位串）。
void main() {
  setUp(() async {
    await setUpUiTest();
  });

  testWidgets('MCP 令牌行：复制按钮写剪贴板 + 已复制轻提示', (tester) async {
    final token = AppServices.mcp.token!;

    Object? copied;
    tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
      SystemChannels.platform,
      (call) async {
        if (call.method == 'Clipboard.setData') {
          copied = (call.arguments as Map)['text'];
        }
        return null;
      },
    );

    await tester.pumpWidget(const MaterialApp(home: SettingsPage()));
    await pumpFlush(tester);

    // MCP 区在长 ListView 首屏外，先滚到可见（坑9 同款先滚后点）
    await tester.scrollUntilVisible(
      find.text('访问令牌'),
      200,
      scrollable: find.byType(Scrollable).first,
    );
    await pumpFlush(tester);

    expect(find.text(token), findsOneWidget, reason: '令牌明文展示在设置页');
    final tile =
        find.ancestor(of: find.text('访问令牌'), matching: find.byType(ListTile)).first;
    await tester.tap(find.descendant(of: tile, matching: find.byIcon(Icons.copy)));
    await tester.pump(const Duration(milliseconds: 100));

    expect(copied, token, reason: '复制按钮把完整令牌写入剪贴板');
    expect(find.text('已复制'), findsOneWidget, reason: '复制成功轻提示');
  });
}
