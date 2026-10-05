import '../action/queries.dart';
import '../action/rules.dart';
import '../data/repository.dart';
import '../data/settings.dart';
import '../models/schedule_block.dart';
import '../util/schedule_day.dart';

/// 中央管家（schedule-app.md §8）：确定性时间驱动状态迁移与漂移浮出，
/// 无 LLM、不做判断——「需要判断」的部分浮出给桌面 AI（晨间 digest 见 queries）。
class Housekeeper {
  Housekeeper(this._repo);

  final Repository _repo;

  /// 日切扫描（幂等，wake_time 切割 §11）：回扫 [ScheduleRules.dailyCutLookbackDays]
  /// 天（设备数日未开机也能补齐）——
  /// ① 已确认块错过日切 → **missed**（可补勾，宽容视窗；补记走 tick_block）；
  ///    但 Clean Slate 保护期（[ScheduleQueries.isBreakdown]）或庆祝块 → 静默
  ///    **archived**（中性淡出，不计入复盘聚合——「破罐破摔」防线，绝不显红）；
  /// ② 未确认提案块过期 → **作废**（物理删除：提案从未成为现实，AI 可整单重提；
  ///    melted 仅物理位移触发、archived 属断流保护，均不适用——口径决策记 memory）。
  /// 顺延记账（postpone_count）在 shift_block 命令内完成，此处不重复。
  Future<({int missed, int voided, int archived, bool protected})> dailyCut(
      {DateTime? now}) async {
    final settings = await _repo.settingsAll();
    final wake = SettingsKeys.intOf(settings, SettingsKeys.wakeTime) ?? 420;
    final n = now ?? DateTime.now();
    final today = scheduleDayOf(n, wake);
    final stale = await _repo.blocksInRange(
      isoDate(addDays(today, -ScheduleRules.dailyCutLookbackDays)),
      isoDate(addDays(today, -1)),
    );
    final protected = await ScheduleQueries(_repo).isBreakdown(now: n);
    var missed = 0;
    var voided = 0;
    var archived = 0;
    for (final b in stale) {
      if (b.status == ScheduleBlock.statusConfirmed) {
        if (b.isCelebration || protected) {
          await _repo.patchBlock(b.id!, {'status': ScheduleBlock.statusArchived});
          archived++;
        } else {
          await _repo.patchBlock(b.id!, {'status': ScheduleBlock.statusMissed});
          missed++;
        }
      } else if (b.status == ScheduleBlock.statusProposed) {
        if (b.isCelebration) {
          await _repo.patchBlock(b.id!, {'status': ScheduleBlock.statusArchived});
          archived++;
        } else {
          await _repo.deleteBlock(b.id!);
          voided++;
        }
      }
    }
    return (missed: missed, voided: voided, archived: archived, protected: protected);
  }

  /// 冷启动日切：每作息日至多执行一次（app_settings 内部键 last_daily_cut）。
  Future<void> dailyCutIfNeeded({DateTime? now}) async {
    final n = now ?? DateTime.now();
    final settings = await _repo.settingsAll();
    final wake = SettingsKeys.intOf(settings, SettingsKeys.wakeTime) ?? 420;
    final today = isoDate(scheduleDayOf(n, wake));
    if (settings['last_daily_cut'] == today) return;
    await dailyCut(now: n);
    await _repo.settingsSet({'last_daily_cut': today});
  }
}
