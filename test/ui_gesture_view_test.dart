import 'package:flutter_test/flutter_test.dart';
import 'package:shiguang/main.dart';
import 'package:shiguang/ui/schedule_page.dart';
import 'helpers/ui.dart';

/// 视图横滑翻页（ui-spec §6.4，2026-10-06 拍板）：日视图背景横滑=翻日。
/// 一测一文件（helpers/ui.dart 约定：UI 流程跨测试异步残留互扰）。
void main() {
  setUp(() async {
    await setUpUiTest();
  });

  testWidgets('日视图横滑：左滑=后一天（回看态），右滑=回今天', (tester) async {
    await real(tester, seedSettings);
    await tester.pumpWidget(const ShiguangApp());
    await pumpFlush(tester);
    expect(find.text('回到今天'), findsNothing);

    // 空日程背景左滑 >60dp → 后一天（非今日态出现「回到今天」）
    await tester.fling(find.byType(SchedulePage), const Offset(-120, 0), 300);
    await pumpFlush(tester);
    expect(find.text('回到今天'), findsOneWidget);

    // 右滑回今天
    await tester.fling(find.byType(SchedulePage), const Offset(120, 0), 300);
    await pumpFlush(tester);
    expect(find.text('回到今天'), findsNothing);
  });
}
