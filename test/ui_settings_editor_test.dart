import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shiguang/app_services.dart';
import 'package:shiguang/data/settings.dart';
import 'package:shiguang/main.dart';
import 'helpers/ui.dart';

/// 设置页编辑器簇（functional-spec §2；2026-10-08 子页化后各编辑器独立成页）：
/// 一周节奏增删改 / 例外日增删改 / 排程偏好编辑。一测一文件（helpers/ui.dart 约定）。
void main() {
  setUp(() async {
    await setUpUiTest();
  });

  testWidgets('设置编辑器：一周节奏增改、例外日增、排程偏好改', (tester) async {
    await tester.binding.setSurfaceSize(const Size(800, 1400));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    await real(tester, seedSettings);

    await tester.pumpWidget(const ShiguangApp());
    await pumpFlush(tester);
    // 设置=底部第三 Tab（2026-10-08 拍板；原 AppBar 右上角 tooltip 入口退役）
    await tester.tap(find.text('设置').last);
    await pumpFlush(tester);

    // 一周节奏：进子页 → 添加「上班」周一（FixedSlotEditorSheet）
    await tester.tap(find.text('一周节奏'));
    await pumpFlush(tester);
    await tester.tap(find.text('添加'));
    await pumpFlush(tester);
    await tester.enterText(
        find.descendant(of: find.byKey(const Key('fixedSlotEditor')),
            matching: find.byType(TextField)),
        '上班');
    await tester.tap(find.widgetWithText(FilterChip, '周一'));
    await pumpFlush(tester);
    await tester.tap(find.widgetWithText(FilledButton, '保存'));
    await pumpFlush(tester);
    await waitFor(tester, () async {
      final slots = await AppServices.repo.listFixedSlots();
      return slots.any((s) => s.name == '上班' && s.weekdays.contains(1));
    });
    expect(find.text('上班'), findsWidgets);

    // 一周节奏：改名为「通勤」后保存（编辑路径）
    await tester.tap(find.byTooltip('编辑'));
    await pumpFlush(tester);
    await tester.enterText(
        find.descendant(of: find.byKey(const Key('fixedSlotEditor')),
            matching: find.byType(TextField)),
        '通勤');
    await pumpFlush(tester);
    await tester.tap(find.widgetWithText(FilledButton, '保存'));
    await pumpFlush(tester);
    await waitFor(tester, () async {
      final slots = await AppServices.repo.listFixedSlots();
      return slots.any((s) => s.name == '通勤') && !slots.any((s) => s.name == '上班');
    });

    // 回目录 → 假期与出行子页：添加例外日（默认今天起止 + 标签）
    await tester.pageBack();
    await pumpFlush(tester);
    await tester.tap(find.text('假期与出行'));
    await pumpFlush(tester);
    await tester.tap(find.text('添加'));
    await pumpFlush(tester);
    await tester.enterText(
        find.descendant(of: find.byKey(const Key('exceptionEditor')),
            matching: find.byType(TextField)),
        '云南行');
    await pumpFlush(tester);
    await tester.tap(find.widgetWithText(FilledButton, '保存'));
    await pumpFlush(tester);
    await waitFor(tester, () async {
      final raw = await AppServices.repo.settingsAll();
      final list = parseExceptions(raw);
      return list.any((w) => w.label == '云南行');
    });

    // 回目录 → 排程偏好子页：最小块粒度 30（第一个字段= min_block）
    await tester.pageBack();
    await pumpFlush(tester);
    await tester.tap(find.text('排程偏好'));
    await pumpFlush(tester);
    await tester.enterText(find.byType(TextField).at(0), '30');
    await pumpFlush(tester);
    await tester.tap(find.widgetWithText(TextButton, '保存').at(0));
    await pumpFlush(tester);
    await waitFor(tester, () async {
      final raw = await AppServices.repo.settingsAll();
      return SettingsKeys.intOf(raw, SettingsKeys.minBlockMinutes) == 30;
    });
  });
}
