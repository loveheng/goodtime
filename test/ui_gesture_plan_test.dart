import 'package:flutter_test/flutter_test.dart';
import 'package:shiguang/action/command_handler.dart';
import 'package:shiguang/action/commands.dart';
import 'package:shiguang/main.dart';
import 'helpers/ui.dart';

/// 清单左滑归档 + 撤销（ui-spec §6.4，2026-10-06 拍板）。
/// 一测一文件（helpers/ui.dart 约定：UI 流程跨测试异步残留互扰）。
void main() {
  setUp(() async {
    await setUpUiTest();
  });

  testWidgets('清单条目左滑过卡点 → 归档 → SnackBar 撤销恢复', (tester) async {
    await real(tester, seedSettings);
    final pid = await real(tester, () async {
      final r = await CommandHandler(repo)
          .execute(const UpsertPlanCommand(title: '旧想法一条'));
      return r.targetId!;
    });
    await tester.pumpWidget(const ShiguangApp());
    await pumpFlush(tester);

    await tester.tap(find.text('清单'));
    await pumpFlush(tester);
    expect(find.text('旧想法一条'), findsOneWidget);

    // 左滑过 35% 卡点 → 归档
    await tester.fling(find.text('旧想法一条'), const Offset(-300, 0), 600);
    await pumpFlush(tester);
    await waitFor(tester, () async => (await repo.planById(pid))?.archived == true);

    // 等 SnackBar 入场动画收位后点「撤销」恢复
    await waitFor(tester, () async => find.text('撤销').evaluate().isNotEmpty);
    // 固定 pump 而非 pumpAndSettle（本页有常驻帧源会永不收敛，见 helpers/ui.dart）
    await tester.pump(const Duration(milliseconds: 300));
    await tester.pump(const Duration(milliseconds: 300));
    await tester.tap(find.text('撤销'), warnIfMissed: false);
    await pumpFlush(tester);
    await waitFor(
        tester, () async => (await repo.planById(pid))?.archived == false);
  });
}
