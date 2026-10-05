import 'dart:convert';

import '../data/repository.dart';
import '../data/settings.dart';
import '../models/fixed_slot.dart';
import '../models/plan.dart';
import '../models/schedule_block.dart';
import '../action/rules.dart';
import '../util/schedule_day.dart';
import 'commands.dart';

/// 查询层：MCP 读工具与 UI 的共用读入口（只读，不入 FIFO 写锁）。
///
/// - **服务端供日期**（§6）：get_schedule 以作息日为「今天」，AI 不自己算「下周三」；
/// - **label 回落**（§4）：块 label 缺省回落 plan 标题，回 `effective_label`；
/// - get_history M1 为基础聚合（恒为聚合体、永不返回明细行 §6）；校准指标
///   （膨胀系数/时段热力/耐受阈值/深潜净值）全量在 M3。
class ScheduleQueries {
  ScheduleQueries(this._repo);

  final Repository _repo;

  Future<Map<String, Object?>> getSettings() async {
    final raw = await _repo.settingsAll();
    final exceptionsRaw = SettingsKeys.stringOf(raw, SettingsKeys.exceptions);
    return {
      'initialized': SettingsKeys.initialized(raw),
      'wake_time': SettingsKeys.intOf(raw, SettingsKeys.wakeTime),
      'sleep_time': SettingsKeys.intOf(raw, SettingsKeys.sleepTime),
      'min_block_minutes': SettingsKeys.intOf(raw, SettingsKeys.minBlockMinutes),
      'daily_new_blocks_limit': SettingsKeys.intOf(raw, SettingsKeys.dailyNewBlocksLimit),
      'today_energy': raw[SettingsKeys.todayEnergy] ?? 'normal', // 默认平稳（十轮拍板）
      'user_rules': SettingsKeys.stringOf(raw, SettingsKeys.userRules),
      'weather_location': SettingsKeys.stringOf(raw, SettingsKeys.weatherLocation),
      'exceptions':
          exceptionsRaw == null ? <Object?>[] : (jsonDecode(exceptionsRaw) as List<dynamic>),
    };
  }

  /// 今天+明天（days 可选拉长，≤3，§6）。按作息日取「今天」。
  /// 附**三日容量水位线**（§6 七轮：空闲=作息−固定占用−human/pinned 墙，纯几何；
  /// AI 自有块可替换不占容）与例外日旗标（§13 旅行模式：fixed 挂起、水位线抬升）。
  Future<Map<String, Object?>> getSchedule({DateTime? now, int days = 2}) async {
    final raw = await _repo.settingsAll();
    final wake = SettingsKeys.intOf(raw, SettingsKeys.wakeTime) ?? 0;
    final sleep = SettingsKeys.intOf(raw, SettingsKeys.sleepTime) ?? 1380;
    final sleepAdj = sleep <= wake ? sleep + 1440 : sleep;
    final exceptions = parseExceptions(raw);
    final today = scheduleDayOf(now ?? DateTime.now(), wake);
    final n = days < 1 ? 1 : (days > 3 ? 3 : days);
    final out = <Map<String, Object?>>[];
    for (var i = 0; i < n; i++) {
      final date = addDays(today, i);
      final iso = isoDate(date);
      final blocks = await _repo.blocksOnDate(iso);
      final exception =
          exceptions.where((w) => w.covers(date)).map((w) => w.label ?? '假期与出行').toList();
      final suspended = exception.isNotEmpty;
      final resolved = suspended
          ? (onDate: const <FixedSlot>[], spillover: const <FixedSlot>[])
          : await _repo.fixedSlotsForDate(date);
      final planIds = {for (final b in blocks) if (b.planId != null) b.planId!};
      final plans = {for (final id in planIds) id: await _repo.planById(id)};
      // 水位线：空闲 = 作息窗 − 固定占用（旅行挂起日为 0）− human/pinned 墙
      final occupied = <(int, int)>[
        for (final b in blocks)
          if (b.pinned || b.source == ScheduleBlock.sourceHuman)
            (b.startMin, b.startMin + spanMinutes(b.startMin, b.endMin)),
        for (final s in resolved.onDate)
          (s.startMin, crossesMidnight(s.startMin, s.endMin) ? 1440 : s.endMin),
        for (final s in resolved.spillover) (0, s.endMin),
      ];
      out.add({
        'date': iso,
        'exception': suspended ? exception.join('、') : null,
        'free_minutes': freeMinutesIn(wake, sleepAdj, occupied),
        'blocks': [
          for (final b in blocks)
            {
              ...blockToJson(b),
              'effective_label': b.label ?? (b.planId != null ? plans[b.planId]?.title : null),
            },
        ],
        'fixed_slots': {
          'on_date': [for (final s in resolved.onDate) slotToJson(s)],
          'spillover': [for (final s in resolved.spillover) slotToJson(s)],
          'suspended': suspended,
        },
      });
    }
    return {'today': isoDate(today), 'days': out};
  }

  /// 近 N 个作息日的 done 聚合 + 校准指标全量（§6，2026-10-05 拍板）。
  /// 恒为聚合体、永不返回明细行；App 负责无偏见的事实统计，AI 负责带理由的
  /// 温和提案，人掌握显式规则与一键赦免权（§3 反压迫条款）。
  ///
  /// 校准指标口径（rules.dart 同源）：
  /// - **深潜净值**（主指标）= 重要象限（plan.importance）done 绝对分钟，spark 计入；
  /// - **膨胀系数** = Σ实际时长 / Σ原始估算（done 块，估算缺失不计）；
  /// - **时段完成率** = 早/中/晚分区的 done / (done+missed)；
  /// - **耐受阈值** = 次日 missed 率 ≥50% 激增日的当日执行分钟峰值；
  ///   检测不出回退默认 = 单日新增排量上限 × 最小块粒度；
  /// - **能量回血** = 庆祝块兑现率与分钟数（庆祝豁免 → 分母为 done+archived）。
  Future<Map<String, Object?>> getHistory({DateTime? now, int days = 14}) async {
    final raw = await _repo.settingsAll();
    final wake = SettingsKeys.intOf(raw, SettingsKeys.wakeTime) ?? 0;
    final sleep = SettingsKeys.intOf(raw, SettingsKeys.sleepTime) ?? 1380;
    final sleepAdj = sleep <= wake ? sleep + 1440 : sleep;
    final today = scheduleDayOf(now ?? DateTime.now(), wake);
    final n = days < 1 ? 1 : (days > 90 ? 90 : days);

    final all = await _repo.blocksInRange(isoDate(addDays(today, -(n - 1))), isoDate(today));
    final planIds = {for (final b in all) if (b.planId != null) b.planId!};
    final plans = <String, Plan?>{
      for (final id in planIds) id: await _repo.planById(id),
    };
    bool urgent(Plan? p) {
      final d = p?.deadline;
      if (d == null) return false;
      final dd = tryParseIsoDate(d);
      return dd != null &&
          dd.isBefore(addDays(today, ScheduleRules.urgentDays + 1));
    }

    // 按日统计（旧→新，供耐受阈值的相邻日判定）
    final stats = <Map<String, Object?>>[]; // date/doneCount/missedCount/doneMinutes
    for (var i = n - 1; i >= 0; i--) {
      final iso = isoDate(addDays(today, -i));
      final day = all.where((b) => b.date == iso);
      final done = day.where((b) => b.status == ScheduleBlock.statusDone);
      final missed = day.where((b) => b.status == ScheduleBlock.statusMissed).length;
      stats.add({
        'date': iso,
        'doneCount': done.length,
        'missedCount': missed,
        'doneMinutes': done.fold<int>(0, (acc, b) => acc + spanMinutes(b.startMin, b.endMin)),
      });
    }

    // 深潜净值 / 膨胀系数 / 分象限 / 能量回血（全期聚合）
    var deepMinutes = 0;
    var realSpan = 0;
    var estimateSpan = 0;
    final quadrant = {
      'Q1': [0, 0], 'Q2': [0, 0], 'Q3': [0, 0], 'Q4': [0, 0], // [done, missed]
    };
    var celebrationDone = 0;
    var celebrationDoneMinutes = 0;
    var celebrationArchived = 0;
    for (final b in all) {
      final span = spanMinutes(b.startMin, b.endMin);
      final p = b.planId == null ? null : plans[b.planId!];
      if (b.isCelebration) {
        if (b.status == ScheduleBlock.statusDone) {
          celebrationDone++;
          celebrationDoneMinutes += span;
        } else if (b.status == ScheduleBlock.statusArchived) {
          celebrationArchived++;
        }
      }
      if (b.status != ScheduleBlock.statusDone && b.status != ScheduleBlock.statusMissed) {
        continue;
      }
      final isDone = b.status == ScheduleBlock.statusDone;
      if (isDone && p?.importance == true) deepMinutes += span;
      if (isDone && p != null && (p.estimate ?? 0) >= 1) {
        realSpan += span;
        estimateSpan += p.estimate!;
      }
      if (p != null) {
        final key = p.importance
            ? (urgent(p) ? 'Q1' : 'Q2')
            : (urgent(p) ? 'Q3' : 'Q4');
        quadrant[key]![isDone ? 0 : 1] += 1;
      }
    }

    // 时段完成率（块按开始时刻落段；跨午夜块归开始段）
    double? segRate(int lo, int hi) {
      var done = 0, missed = 0;
      for (final b in all) {
        if (b.startMin < lo || b.startMin >= hi) continue;
        if (b.status == ScheduleBlock.statusDone) {
          done++;
        } else if (b.status == ScheduleBlock.statusMissed) {
          missed++;
        }
      }
      return done + missed == 0 ? null : done / (done + missed);
    }

    // 耐受阈值：次日 missed 率激增日的当日执行峰值；检测不出走回退默认
    int? detected;
    for (var i = 0; i + 1 < stats.length; i++) {
      final denom = (stats[i + 1]['doneCount'] as int) + (stats[i + 1]['missedCount'] as int);
      if (denom == 0) continue;
      final rate = (stats[i + 1]['missedCount'] as int) / denom;
      if (rate >= ScheduleRules.toleranceMissedRateSpike) {
        final d = stats[i]['doneMinutes'] as int;
        if (d > 0 && (detected == null || d > detected)) detected = d;
      }
    }
    final fallback = (SettingsKeys.intOf(raw, SettingsKeys.dailyNewBlocksLimit) ??
            ScheduleRules.toleranceFallbackBlocks) *
        (SettingsKeys.intOf(raw, SettingsKeys.minBlockMinutes) ??
            ScheduleRules.toleranceFallbackBlockMin);

    final perDay = [
      // 输出 newest first（today 在前）
      for (var i = n - 1; i >= 0; i--) stats[i],
    ].map((s) => {
          'date': s['date'],
          'done_count': s['doneCount'],
          'missed_count': s['missedCount'],
          'done_minutes': s['doneMinutes'],
          'day_spark_done': all
              .where((b) =>
                  b.date == s['date'] &&
                  b.isDaySpark &&
                  b.status == ScheduleBlock.statusDone)
              .length,
        }).toList();

    double? rate(Map<String, List<int>> q, String k) {
      final v = q[k]!;
      return v[0] + v[1] == 0 ? null : v[0] / (v[0] + v[1]);
    }

    return {
      'days': perDay,
      'totals': {
        'done_count': stats.fold(0, (a, s) => a + (s['doneCount'] as int)),
        'done_minutes': stats.fold(0, (a, s) => a + (s['doneMinutes'] as int)),
        'day_spark_done': perDay.fold(0, (a, s) => a + (s['day_spark_done'] as int)),
      },
      'calibration': {
        'deep_net_minutes': deepMinutes,
        'expansion_factor': estimateSpan == 0 ? null : realSpan / estimateSpan,
        'segment_done_rates': {
          'morning': segRate(wake, ScheduleRules.morningEndMin),
          'afternoon': segRate(ScheduleRules.morningEndMin, ScheduleRules.afternoonEndMin),
          'evening': segRate(ScheduleRules.afternoonEndMin, sleepAdj),
        },
        'quadrant_done_rates': {
          'Q1': rate(quadrant, 'Q1'),
          'Q2': rate(quadrant, 'Q2'),
          'Q3': rate(quadrant, 'Q3'),
          'Q4': rate(quadrant, 'Q4'),
        },
        'tolerance': {
          'minutes': detected ?? fallback,
          'source': detected == null ? 'default' : 'detected',
        },
        'energy_recovery': {
          'celebration_done_count': celebrationDone,
          'celebration_minutes': celebrationDoneMinutes,
          'fulfillment_rate': celebrationDone + celebrationArchived == 0
              ? null
              : celebrationDone / (celebrationDone + celebrationArchived),
        },
      },
    };
  }

  /// 断流保护判定（§8 Clean Slate，2026-10-05 拍板）：连续 [ScheduleRules.breakdownDays]
  /// 作息日无 done/confirm 交互 或 未处置 missed 累计 ≥ [ScheduleRules.breakdownMissedTotal]。
  /// 零块历史（新用户）不判断流；恢复交互（done/confirm 落库）即自然退出保护——
  /// 状态无缓存、每次判定现算，杜绝保护状态与现实的二次真相。
  Future<bool> isBreakdown({DateTime? now}) async {
    final raw = await _repo.settingsAll();
    final wake = SettingsKeys.intOf(raw, SettingsKeys.wakeTime) ?? 0;
    final today = scheduleDayOf(now ?? DateTime.now(), wake);
    final history =
        await _repo.blocksInRange(isoDate(addDays(today, -30)), isoDate(today));
    final missedTotal =
        history.where((b) => b.status == ScheduleBlock.statusMissed).length;
    if (missedTotal >= ScheduleRules.breakdownMissedTotal) return true;
    String? lastActive;
    for (final b in history) {
      if (b.status == ScheduleBlock.statusDone ||
          b.status == ScheduleBlock.statusConfirmed) {
        if (lastActive == null || b.date.compareTo(lastActive) > 0) {
          lastActive = b.date;
        }
      }
    }
    if (lastActive == null) return false;
    final last = tryParseIsoDate(lastActive);
    return last != null && today.difference(last).inDays >= ScheduleRules.breakdownDays;
  }

  /// 简易月历数据（§4，2026-10-06 拍板替换原周视图）：单月只读摘要——
  /// 同一 SQLite 派生投影（blocksInRange 单月单查询），历史与未来同构呈现，
  /// 点格跳日视图（日视图日期参数化，过去的日程串起来）。
  Future<Map<String, Object?>> monthOverview(int year, int month) async {
    final first = DateTime(year, month, 1);
    final last = DateTime(year, month + 1, 0); // 月末
    final blocks = await _repo.blocksInRange(isoDate(first), isoDate(last));
    final raw = await _repo.settingsAll();
    final exceptions = parseExceptions(raw);
    final wake = SettingsKeys.intOf(raw, SettingsKeys.wakeTime) ?? 0;
    final todayIso = isoDate(scheduleDayOf(DateTime.now(), wake));
    final byDate = <String, List<ScheduleBlock>>{};
    for (final b in blocks) {
      if (b.status == ScheduleBlock.statusMelted) continue; // 无痕蒸发不进月历
      byDate.putIfAbsent(b.date, () => []).add(b);
    }
    final days = <Map<String, Object?>>[];
    for (var d = first;
        d.isBefore(last.add(const Duration(days: 1)));
        d = addDays(d, 1)) {
      final iso = isoDate(d);
      final list = byDate[iso] ?? const <ScheduleBlock>[];
      final exception =
          exceptions.where((w) => w.covers(d)).map((w) => w.label ?? '假期与出行').join('、');
      days.add({
        'date': iso,
        'total': list.length,
        'done': list.where((b) => b.status == ScheduleBlock.statusDone).length,
        'missed': list.where((b) => b.status == ScheduleBlock.statusMissed).length,
        'proposed': list.where((b) => b.status == ScheduleBlock.statusProposed).length,
        'human': list.where((b) => b.source == ScheduleBlock.sourceHuman).length,
        'spark_done': list.any((b) => b.isDaySpark && b.status == ScheduleBlock.statusDone),
        'celebration': list.any((b) => b.isCelebration),
        'exception': exception.isEmpty ? null : exception,
      });
    }
    return {'year': year, 'month': month, 'today': todayIso, 'days': days};
  }

  /// 周视图数据（§4.1，2026-10-05 原拍板恢复）：含选中日所在周的周一..周日
  /// 七天全量块（紧凑渲染由 UI 承载）+ 聚焦窗 + 固定占用投射；同一 SQLite 派生投影。
  Future<Map<String, Object?>> weekOverview(DateTime anchor) async {
    final monday =
        DateTime(anchor.year, anchor.month, anchor.day - (anchor.weekday - 1));
    final blocks = await _repo.blocksInRange(isoDate(monday), isoDate(addDays(monday, 6)));
    final raw = await _repo.settingsAll();
    final wake = SettingsKeys.intOf(raw, SettingsKeys.wakeTime) ?? 0;
    final sleep = SettingsKeys.intOf(raw, SettingsKeys.sleepTime) ?? 1380;
    final exceptions = parseExceptions(raw);
    final days = <Map<String, Object?>>[];
    for (var i = 0; i < 7; i++) {
      final d = addDays(monday, i);
      final iso = isoDate(d);
      final dayBlocks = blocks.where((b) => b.date == iso).toList();
      final planIds = {for (final b in dayBlocks) if (b.planId != null) b.planId!};
      final plans = {for (final id in planIds) id: await _repo.planById(id)};
      final resolved = await _repo.fixedSlotsForDate(d);
      days.add({
        'date': iso,
        'weekday': ['一', '二', '三', '四', '五', '六', '日'][i],
        'exception':
            exceptions.where((w) => w.covers(d)).map((w) => w.label ?? '假期与出行').join('、'),
        'blocks': [
          for (final b in dayBlocks)
            {
              ...blockToJson(b),
              'effective_label':
                  b.label ?? (b.planId != null ? plans[b.planId]?.title : null),
            },
        ],
        'fixed_on_date': [for (final s in resolved.onDate) slotToJson(s)],
        'fixed_spillover': [for (final s in resolved.spillover) slotToJson(s)],
      });
    }
    return {
      'week_start': isoDate(monday),
      'wake': wake,
      'sleep_adj': sleep <= wake ? sleep + 1440 : sleep,
      'days': days,
    };
  }

  /// 晨间 digest（§8 确定性视图，防晨间道德审判的口径见设计）：
  /// ① 昨日遗留（近 7 天 missed 未处置块）；② 今日计划；③ 孤儿段——无块计划，
  /// 新孤儿（≤[ScheduleRules.orphanColdDays] 天）进 digest、超龄自动「收起的旧想法」只报数；
  /// ④ 悬空段（十一轮）：子树曾有 done/confirmed（曾启动）、当前无未来块、无未决
  /// open_items 的父计划——「XX 已停滞 N 天」。
  Future<Map<String, Object?>> morningDigest({DateTime? now}) async {
    final raw = await _repo.settingsAll();
    final wake = SettingsKeys.intOf(raw, SettingsKeys.wakeTime) ?? 0;
    final n = now ?? DateTime.now();
    final today = scheduleDayOf(n, wake);
    final todayIso = isoDate(today);

    final missed = [
      for (final b in await _repo.blocksInRange(
          isoDate(addDays(today, -7)), isoDate(addDays(today, -1))))
        if (b.status == ScheduleBlock.statusMissed) b,
    ];
    final todayBlocks = await _repo.blocksOnDate(todayIso);

    // 近一年块史一次取回：孤儿判定 + 悬空判定共用
    final history = await _repo.blocksInRange(
        isoDate(addDays(today, -365)), isoDate(addDays(today, 30)));
    final plansWithBlocks = <String>{};
    final lastActive = <String, String>{}; // planId -> 最近 done/confirmed 块日期
    final hasFutureBlock = <String>{};
    for (final b in history) {
      if (b.planId == null) continue;
      plansWithBlocks.add(b.planId!);
      if (b.status == ScheduleBlock.statusDone ||
          b.status == ScheduleBlock.statusConfirmed) {
        final cur = lastActive[b.planId!];
        if (cur == null || b.date.compareTo(cur) > 0) lastActive[b.planId!] = b.date;
      }
      if (b.date.compareTo(todayIso) >= 0 &&
          (b.status == ScheduleBlock.statusProposed ||
              b.status == ScheduleBlock.statusConfirmed)) {
        hasFutureBlock.add(b.planId!);
      }
    }

    final plans = await _repo.listPlans();
    final byId = <String, Plan>{};
    final children = <String, List<Plan>>{};
    for (final p in plans) {
      byId[p.id!] = p;
      if (p.parentId != null) children.putIfAbsent(p.parentId!, () => []).add(p);
    }
    final coldCutoffMs =
        addDays(today, -ScheduleRules.orphanColdDays).millisecondsSinceEpoch;
    final freshOrphans = <Map<String, Object?>>[];
    var coldCount = 0;
    for (final p in plans) {
      if (plansWithBlocks.contains(p.id)) continue;
      if ((p.updatedAt ?? 0) < coldCutoffMs) {
        coldCount++;
        continue;
      }
      freshOrphans.add(planToJson(p));
    }

    Set<String> descendantsOf(String rootId) {
      final out = <String>{};
      void walk(String id, int depth) {
        for (final c in children[id] ?? const <Plan>[]) {
          if (out.add(c.id!) && depth < 2) walk(c.id!, depth + 1);
        }
      }
      walk(rootId, 0);
      return out;
    }

    final dangling = <Map<String, Object?>>[];
    for (final p in plans) {
      final kids = descendantsOf(p.id!);
      if (kids.isEmpty) continue;
      var everStarted = false;
      var hasFuture = false;
      var hasOpen = p.openItems.any((o) => o.answer == null);
      String? lastActiveDate;
      for (final id in {p.id!, ...kids}) {
        final d = lastActive[id];
        if (d != null) {
          everStarted = true;
          if (lastActiveDate == null || d.compareTo(lastActiveDate) > 0) {
            lastActiveDate = d;
          }
        }
        if (hasFutureBlock.contains(id)) hasFuture = true;
        final pl = byId[id];
        if (pl != null && pl.openItems.any((o) => o.answer == null)) hasOpen = true;
      }
      if (!everStarted || hasFuture || hasOpen || lastActiveDate == null) continue;
      final startedOn = tryParseIsoDate(lastActiveDate);
      if (startedOn == null) continue;
      final days = today.difference(startedOn).inDays;
      if (days < 1) continue;
      dangling.add({
        'plan_id': p.id,
        'title': p.title,
        'days': days,
        'last_active': lastActiveDate,
      });
    }
    dangling.sort((a, b) => (b['days'] as int).compareTo(a['days'] as int));

    return {
      'today': todayIso,
      'clean_slate': await isBreakdown(now: n),
      'leftovers': [for (final b in missed) blockToJson(b)],
      'today_blocks': [for (final b in todayBlocks) blockToJson(b)],
      'orphans': freshOrphans,
      'cold_storage_count': coldCount,
      'dangling': dangling,
    };
  }
}

/// 换乘候选（§10 换乘开关）：待安排池（无任何排期块）中 light/anywhere 的计划，
/// 时长相近优先（与 [block] 时长差最小，无估算靠后）。
Future<List<Plan>> swapCandidatesFor(Repository repo, ScheduleBlock block) async {
  final span = spanMinutes(block.startMin, block.endMin);
  final anchor = tryParseIsoDate(block.date)!;
  final busyPlans = {
    for (final blk in await repo.blocksInRange(
        isoDate(addDays(anchor, -365)), isoDate(addDays(anchor, 30))))
      if (blk.planId != null) blk.planId!,
  };
  final candidates = [
    for (final p in await repo.listPlans())
      if (!busyPlans.contains(p.id) &&
          p.energyLevel == Plan.energyLight &&
          p.toolRequired == Plan.toolAnywhere)
        p,
  ]..sort((a, b) {
      final da = a.estimate == null ? 1 << 30 : (a.estimate! - span).abs();
      final db = b.estimate == null ? 1 << 30 : (b.estimate! - span).abs();
      return da.compareTo(db);
    });
  return candidates;
}
