import 'package:flutter_test/flutter_test.dart';
import 'package:shiguang/main.dart';
import 'helpers/ui.dart';

void main() {
  setUpAll(() {});

  setUp(() async {
    await setUpUiTest();
  });

  testWidgets('闸门：已播种 → 直接进主框架（日程 Tab）', (tester) async {
    await real(tester, seedSettings);
    await tester.pumpWidget(const ShiguangApp());
    await pumpFlush(tester);
    expect(find.text('几点起床？几点睡觉？'), findsNothing);
    expect(find.text('日程'), findsWidgets, reason: '闸门首帧决策进主框架');
  });
}
