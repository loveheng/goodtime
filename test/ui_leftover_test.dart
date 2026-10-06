import 'dart:ui' show Size;

import 'package:flutter_test/flutter_test.dart';
import 'package:shiguang/action/command_handler.dart';
import 'package:shiguang/action/commands.dart';
import 'package:shiguang/main.dart';
import 'package:shiguang/models/schedule_block.dart';
import 'package:shiguang/util/schedule_day.dart';
import 'helpers/ui.dart';

/// 昨日遗留区（ui-spec §3.2）：missed 逐条三键——补勾（补记）/顺延今日/退回愿望池。
/// 一测一文件（helpers/ui.dart 约定）。种子用跨午夜睡眠（sleep 5:00 < wake 7:00），
/// 使放工守卫窗口落在真实时刻之外，任何钟点跑测试都确定。
void main() {
  setUp(() async {
    await setUpUiTest();
  });

  testWidgets('昨日遗留区：三键处置', (tester) async {
    // 高表面：贴近真机纵向空间（600dp 测试默认面对多行卡片区过于局促）
    await tester.binding.setSurfaceSize(const Size(800, 1400));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    await real(tester, seedSettings);
    await real(tester, () => CommandHandler(repo).execute(const UpdateSettingsCommand(values: {
          'sleep_time': 300, // 5:00（跨午夜）→ 守卫窗 [27:00,29:00) 真实时刻不可达
        })));
    final todayStr = await real(tester, todayIso);
    final yIso = isoDate(tryParseIsoDate(todayStr)!.subtract(const Duration(days: 1)));
    if (bool.fromEnvironment('SKIP_SEED')) {
      await tester.pumpWidget(const ShiguangApp());
      await pumpFlush(tester);
      return;
    }
    final ids = await real(tester, () async {
      Future<String> seed(String label, int start) async {
        final b = await repo.addBlock(ScheduleBlock(
          date: yIso,
          startMin: start,
          endMin: start + 60,
          label: label,
          source: ScheduleBlock.sourceAi,
        ));
        await repo.patchBlock(b.id!, {'status': ScheduleBlock.statusMissed});
        return b.id!;
      }

      return [
        await seed('遗留甲', 600),
        await seed('遗留乙', 700),
        await seed('遗留丙', 800),
      ];
    });

    // 注意：waitFor 的 check 本身已在 runAsync 窗口内，此处不得再包 real()（重入拒绝）
    Future<List<ScheduleBlock>> seeded() async => [
          ...await repo.blocksOnDate(yIso),
          ...await repo.blocksOnDate(todayStr),
        ];

    await tester.pumpWidget(const ShiguangApp());
    await pumpFlush(tester);
    if (bool.fromEnvironment('SKIP_SEED')) return;
    expect(find.text('昨日遗留 · 3 条'), findsOneWidget);

    // 补勾 → done（补记口径）
    await tester.tap(find.text('补勾').first);
    await pumpFlush(tester);
    await waitFor(tester, () async {
      final rows = await seeded();
      return rows.where((b) => ids.contains(b.id) && b.status == ScheduleBlock.statusDone).length == 1;
    });
    expect(find.text('昨日遗留 · 2 条'), findsOneWidget);

    // 顺延今日 → 搬到今天且 postpone_count+1
    if (bool.fromEnvironment('SKIP_REST')) return;
    await tester.tap(find.text('顺延今日').first);
    await pumpFlush(tester);
    await waitFor(tester, () async {
      final rows = await seeded();
      return rows.where((b) => ids.contains(b.id) && b.date == todayStr && b.postponeCount == 1).length == 1;
    });
    expect(find.text('昨日遗留 · 1 条'), findsOneWidget);

    // 退回愿望池 → melted；区清空后整区淡出
    await tester.tap(find.text('退回愿望池'));
    await pumpFlush(tester);
    await waitFor(tester, () async {
      final rows = await seeded();
      return rows.where((b) => ids.contains(b.id) && b.status == ScheduleBlock.statusMelted).length == 1;
    });
    expect(find.text('昨日遗留 · 1 条'), findsNothing);
  });
}
