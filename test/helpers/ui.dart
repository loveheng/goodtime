import 'package:flutter_test/flutter_test.dart';
import 'package:shiguang/action/command_handler.dart';
import 'package:shiguang/action/commands.dart';
import 'package:shiguang/app_services.dart';
import 'package:shiguang/data/db.dart';
import 'package:shiguang/data/repository.dart';
import 'package:sqflite_common_ffi/sqflite_ffi.dart';

/// UI 流程测试公共夹具（一测一文件——flutter test 每文件独立 isolate，
/// 避免跨测试的真实异步残留互相干扰流通道）。
Repository get repo => AppServices.repo;

Future<T> real<T>(WidgetTester tester, Future<T> Function() action) async =>
    (await tester.runAsync(action)) as T;

Future<void> seedSettings() =>
    CommandHandler(repo).execute(const UpdateSettingsCommand(values: {
      'wake_time': 420,
      'sleep_time': 1380,
    }));

/// 冲刷真实异步后渲染（固定次数 pump——快记条光标闪烁会让 pumpAndSettle 永不收敛）。
Future<void> pumpFlush(WidgetTester tester) async {
  for (var i = 0; i < 10; i++) {
    await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 80)));
    await tester.pump(const Duration(milliseconds: 100));
  }
}

/// 轮询等待真实 DB 效果落地（每轮一个 runAsync 窗口推进一跳）。
Future<void> waitFor(WidgetTester tester, Future<bool> Function() check) async {
  for (var i = 0; i < 30; i++) {
    await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 100)));
    await tester.pump();
    if (await real(tester, check)) return;
  }
  fail('waitFor: 条件 3 秒内未满足');
}

/// 统一环境准备：ffi 内存库 + 清表 + 服务装配。
Future<void> setUpUiTest() async {
  sqfliteFfiInit();
  databaseFactory = databaseFactoryFfi;
  Db.overridePath(inMemoryDatabasePath);
  final db = await Db.instance();
  for (final t in ['schedule_blocks', 'fixed_slots', 'plans', 'app_settings']) {
    await db.delete(t);
  }
  await AppServices.init();
}

Future<String> todayIso() async {
  final wake = int.tryParse(await repo.settingsGet('wake_time') ?? '420') ?? 420;
  final now = DateTime.now();
  var day = DateTime(now.year, now.month, now.day);
  if (now.hour * 60 + now.minute < wake) {
    day = day.subtract(const Duration(days: 1));
  }
  return '${day.year.toString().padLeft(4, '0')}-${day.month.toString().padLeft(2, '0')}-${day.day.toString().padLeft(2, '0')}';
}
