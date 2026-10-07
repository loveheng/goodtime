import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shiguang/action/command_handler.dart';
import 'package:shiguang/action/commands.dart';
import 'package:shiguang/main.dart';
import 'package:shiguang/models/plan.dart';
import 'helpers/ui.dart';

/// 清单详情簇（functional-spec §2）：roadmap 进度/open_items 手答/子树/排期/reward。
/// 一测一文件（helpers/ui.dart 约定）。
void main() {
  setUp(() async {
    await setUpUiTest();
  });

  testWidgets('清单详情：进度可见、手答敲定、排期入日程、reward 保存', (tester) async {
    await tester.binding.setSurfaceSize(const Size(800, 1400));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    await real(tester, seedSettings);

    final parentId = await real(tester, () async {
      final r = await CommandHandler(repo).execute(const UpsertPlanCommand(
        title: '考驾照',
        spec: '前置：报名完成\n## 路线图\n- [x] 报名\n- [ ] (ACTIVE) 科目一\n- [ ] 科目二',
        estimate: 60,
      ));
      await CommandHandler(repo).execute(UpdatePlanCommand(
        id: r.targetId!,
        openItems: const [OpenItem(question: '每周几练车？')],
      ));
      return r.targetId!;
    });
    await real(tester, () => CommandHandler(repo)
        .execute(UpsertPlanCommand(title: '科目一刷题', parentId: parentId)));

    await tester.pumpWidget(const ShiguangApp());
    await pumpFlush(tester);
    await tester.tap(find.text('清单'));
    await pumpFlush(tester);

    // 全页详情（ui-spec §5 路由）：roadmap 进度 + 子树 + 待敲定问题
    await tester.tap(find.text('考驾照'));
    await pumpFlush(tester);
    expect(find.text('▶ 当前进度 1/3'), findsOneWidget);
    expect(find.text('子计划'), findsOneWidget);
    expect(find.text('└ 科目一刷题'), findsOneWidget);
    expect(find.text('？每周几练车？'), findsOneWidget);

    // open_items 手答 → 敲定（answer 落库、条目转为已答）
    await tester.enterText(find.byType(TextField).at(4), '周六上午');
    await tester.tap(find.text('敲定'));
    await pumpFlush(tester);
    await waitFor(tester, () async {
      final p = await repo.planById(parentId);
      return p?.openItems.length == 1 && p!.openItems.first.answer == '周六上午';
    });
    expect(find.text('✓ 每周几练车？ → 周六上午'), findsOneWidget);

    // 排期：日期/时间双 picker 默认值直接 OK → place_block 落日程
    await tester.tap(find.text('排期'));
    await pumpFlush(tester);
    await tester.tap(find.text('OK'));
    await pumpFlush(tester);
    await tester.tap(find.text('OK'));
    await pumpFlush(tester);
    await waitFor(tester, () async {
      final blocks = await repo.blocksOnDate(await todayIso());
      return blocks.any((b) => b.planId == parentId);
    });

    // reward 保存（全页详情直改第 4 字段→保存关页）
    await pumpFlush(tester);
    await tester.enterText(find.byType(TextField).at(3), '火锅');
    await tester.ensureVisible(find.text('保存'));
    await pumpFlush(tester);
    await tester.tap(find.text('保存'));
    await pumpFlush(tester);
    await waitFor(tester, () async => (await repo.planById(parentId))?.rewardSpec == '火锅');
  });
}
