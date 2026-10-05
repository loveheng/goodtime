import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shiguang/app_services.dart';
import 'package:shiguang/data/db.dart';
import 'package:shiguang/data/repository.dart';
import 'package:shiguang/main.dart';
import 'package:shiguang/models/schedule_block.dart';
import 'package:shiguang/util/schedule_day.dart';
import 'package:sqflite_common_ffi/sqflite_ffi.dart';

import 'helpers/ui.dart';

// 三手势契约（§6.4/§6.5）：右滑过 45% 卡点 → melt_block 落 melted；
// 手势锁（水平 >20dp 且 |Δx/Δy|>2.0）+ 触觉反馈在真机走查验证。
Repository get repo => AppServices.repo;

Future<void> pumpFlush(WidgetTester tester) async {
  for (var i = 0; i < 10; i++) {
    await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 80)));
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
    for (final t in ['schedule_blocks', 'fixed_slots', 'plans', 'app_settings']) {
      await db.delete(t);
    }
    await AppServices.init();
  });

  testWidgets('右滑过 45% → melt；写路径经命令层 FIFO 落库', (tester) async {
    await real(tester, () => seedSettings());
    final b = await real(tester, () => repo.addBlock(ScheduleBlock(
        date: isoDate(scheduleDayOf(DateTime.now(), 420)),
        startMin: 540,
        endMin: 600,
        source: ScheduleBlock.sourceAi)));
    tester.view.physicalSize = const Size(900, 1600);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);

    await tester.pumpWidget(const ShiguangApp());
    await pumpFlush(tester);

    // 水平直线右拖 420dp（>45% 卡点；手势锁条件满足）
    await tester.drag(find.byKey(ValueKey('block-${b.id}')), const Offset(420, 2));
    // 命令链多跳真实 I/O：每轮 runAsync 窗口推进一跳，pumpFlush 多轮冲刷
    await pumpFlush(tester);

    final fresh = await real(tester, () => repo.blockById(b.id!));
    expect(fresh!.status, ScheduleBlock.statusMelted);
  });
}
