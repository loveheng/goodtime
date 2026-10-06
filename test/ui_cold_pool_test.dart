import 'package:flutter_test/flutter_test.dart';
import 'package:shiguang/action/command_handler.dart';
import 'package:shiguang/action/commands.dart';
import 'package:shiguang/main.dart';
import 'helpers/ui.dart';

/// 冷藏池「收起的旧想法」（schedule-app §8）：archived 折叠可见、可恢复。
/// 一测一文件（helpers/ui.dart 约定）。
void main() {
  setUp(() async {
    await setUpUiTest();
  });

  testWidgets('冷藏池：归档后折叠可见，恢复后回主列表', (tester) async {
    await real(tester, seedSettings);
    final pid = await real(tester, () async {
      final r = await CommandHandler(repo)
          .execute(const UpsertPlanCommand(title: '旧想法甲'));
      return r.targetId!;
    });
    // 归档（左滑归档的最终归宿走同一命令）
    await real(tester, () => CommandHandler(repo)
        .execute(UpdatePlanCommand(id: pid, archived: true)));
    await tester.pumpWidget(const ShiguangApp());
    await pumpFlush(tester);

    await tester.tap(find.text('清单'));
    await pumpFlush(tester);
    // 折叠态：主列表无条目，冷藏池头可见
    expect(find.text('旧想法甲'), findsNothing);
    expect(find.text('收起的旧想法 · 1 条'), findsOneWidget);

    // 展开 → 恢复 → 回主列表、池头消失
    await tester.tap(find.text('收起的旧想法 · 1 条'));
    await pumpFlush(tester);
    expect(find.text('旧想法甲'), findsOneWidget);
    await tester.tap(find.text('恢复'));
    await pumpFlush(tester);
    await waitFor(tester, () async => (await repo.planById(pid))?.archived == false);
    await pumpFlush(tester);
    expect(find.text('收起的旧想法 · 1 条'), findsNothing);
    expect(find.text('旧想法甲'), findsOneWidget);
  });
}
