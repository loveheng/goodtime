import 'package:flutter_test/flutter_test.dart';
import 'package:shiguang/app_services.dart';
import 'package:shiguang/data/db.dart';
import 'package:shiguang/main.dart';
import 'package:sqflite_common_ffi/sqflite_ffi.dart';

/// DB 是真实异步（ffi isolate），FakeAsync 区内需 runAsync 冲刷后 pump。
Future<void> pumpFlush(WidgetTester tester) async {
  // 固定次数 pump 而非 pumpAndSettle：快记条 TextField 光标闪烁会无限请求帧。
  for (var i = 0; i < 10; i++) {
    await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 60)));
    await tester.pump(const Duration(milliseconds: 100));
  }
}

void main() {
  setUpAll(() {
    sqfliteFfiInit();
    databaseFactory = databaseFactoryFfi;
    Db.overridePath(inMemoryDatabasePath);
  });

  setUp(() async {
    final db = await Db.instance();
    await db.delete('schedule_blocks');
    await db.delete('fixed_slots');
    await db.delete('plans');
    await db.delete('app_settings');
    await AppServices.init();
  });

  testWidgets('App 冒烟：未初始化时进首启引导', (tester) async {
    await tester.pumpWidget(const ShiguangApp());
    await pumpFlush(tester);
    expect(find.text('几点起床？几点睡觉？'), findsOneWidget);
    expect(find.text('下一步'), findsOneWidget);
  });
}
