import 'package:flutter_test/flutter_test.dart';
import 'package:shiguang/action/command_handler.dart';
import 'package:shiguang/action/commands.dart';
import 'package:shiguang/models/schedule_block.dart';
import 'package:shiguang/util/schedule_day.dart';
import 'helpers/ui.dart';

/// 现实熔断命令（§8/ui-spec §3.6）：clear_remaining 与 push_2h 批量语义与原子性。
void main() {
  setUp(() async {
    await setUpUiTest();
    await seedSettings();
  });

  final todayIso =
      isoDate(scheduleDayOf(DateTime.now(), 420));

  Future<String> ai(int start, {bool pinned = false, String? status}) async {
    final b = await repo.addBlock(ScheduleBlock(
      date: todayIso,
      startMin: start,
      endMin: start + 60,
      label: 'AI块$start',
      source: ScheduleBlock.sourceAi,
      pinned: pinned,
    ));
    if (status != null) await repo.patchBlock(b.id!, {'status': status});
    return b.id!;
  }

  Future<String> human(int start) async {
    final b = await repo.addBlock(ScheduleBlock(
      date: todayIso,
      startMin: start,
      endMin: start + 60,
      label: '手动$start',
      source: ScheduleBlock.sourceHuman,
    ));
    return b.id!;
  }

  test('clear_remaining：未开始 AI 全 melted，human/pinned/done 不动', () async {
    final a1 = await ai(600);
    final a2 = await ai(700, status: ScheduleBlock.statusConfirmed);
    final h = await human(800);
    final p = await ai(900, pinned: true);
    final d = await ai(1000, status: ScheduleBlock.statusDone);

    final r = await CommandHandler(repo)
        .execute(const PanicClearCommand(mode: 'clear_remaining', nowMin: 500));

    expect(r.note, contains('2 项'));
    expect((await repo.blockById(a1))!.status, ScheduleBlock.statusMelted);
    expect((await repo.blockById(a2))!.status, ScheduleBlock.statusMelted);
    expect((await repo.blockById(h))!.status, ScheduleBlock.statusProposed);
    expect((await repo.blockById(p))!.status, ScheduleBlock.statusProposed);
    expect((await repo.blockById(d))!.status, ScheduleBlock.statusDone);
  });

  test('push_2h：整体 +120 且 postpone_count+1', () async {
    final a1 = await ai(600);
    final a2 = await ai(700, status: ScheduleBlock.statusConfirmed);

    await CommandHandler(repo)
        .execute(const PanicClearCommand(mode: 'push_2h', nowMin: 500));

    final b1 = (await repo.blockById(a1))!;
    final b2 = (await repo.blockById(a2))!;
    expect(b1.startMin, 720);
    expect(b1.postponeCount, 1);
    expect(b2.startMin, 820);
    expect(b2.postponeCount, 1);
  });

  test('push_2h：撞手动块整单拒（原子，未动半截）', () async {
    final a1 = await ai(600);
    await human(730); // +120 后 720–780 与 730–790 重叠 → 整单拒

    await expectLater(
      CommandHandler(repo)
          .execute(const PanicClearCommand(mode: 'push_2h', nowMin: 500)),
      throwsA(isA<ActionException>()),
    );
    expect((await repo.blockById(a1))!.startMin, 600);
  });

  test('clear_remaining：无未开始 → 中性提示', () async {
    final r = await CommandHandler(repo)
        .execute(const PanicClearCommand(mode: 'clear_remaining', nowMin: 1400));
    expect(r.note, '今天剩余没有需要熔断的 AI 安排');
  });
}
