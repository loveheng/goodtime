import 'package:flutter_test/flutter_test.dart';
import 'package:shiguang/main.dart';
import 'helpers/ui.dart';

void main() {
  setUpAll(() {});

  setUp(() async {
    await setUpUiTest();
  });

  testWidgets('首启引导：三步可走完，settings 正确播种', (tester) async {
    await tester.pumpWidget(const ShiguangApp());
    await pumpFlush(tester);
    expect(find.text('几点起床？几点睡觉？'), findsOneWidget);

    await tester.tap(find.text('下一步'));
    await tester.pump(const Duration(milliseconds: 100));
    await tester.tap(find.text('跳过'));
    await tester.pump(const Duration(milliseconds: 100));
    await tester.tap(find.text('开始使用'));

    await waitFor(tester, () async {
      final s = await repo.settingsAll();
      return s['wake_time'] == '420' && s['min_block_minutes'] == '30';
    });
    final s = await real(tester, () => repo.settingsAll());
    expect(s['sleep_time'], '1380');
    expect(s['daily_new_blocks_limit'], '8');
  });
}
