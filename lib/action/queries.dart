import 'dart:convert';

import '../data/repository.dart';
import '../data/settings.dart';
import '../models/artifact.dart';
import '../models/background.dart';
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
      'fill_rate_limit': SettingsKeys.intOf(raw, SettingsKeys.fillRateLimit) ?? 60, // 默认 60（§6 默认可调）
      'today_energy': raw[SettingsKeys.todayEnergy] ?? 'normal', // 默认平稳（十轮拍板）
      'user_rules': SettingsKeys.stringOf(raw, SettingsKeys.userRules),
      'weather_location': SettingsKeys.stringOf(raw, SettingsKeys.weatherLocation),
      'identity_prompt': raw[SettingsKeys.identityPrompt],
      'exceptions':
          exceptionsRaw == null ? <Object?>[] : (jsonDecode(exceptionsRaw) as List<dynamic>),
      'theme_mode': raw[SettingsKeys.themeMode] ?? 'system', // 外观三档（uiOnly 键）
    };
  }

  /// 全局背景治理装配（背景草案 §4 治理/对话通道）：**全量带标**——超期条目不下发
  /// 删除、带 expired 标记，AI 可发起「『脚扭伤』已过期，顺手清理？」建议（衔接
  /// §2 归并询问）；与排程通道的物理过滤（get_schedule 另行装配，超期根本不进
  /// 上下文）互为双通道。预算态随行——AI 能自测才不会反复撞墙（§2 度量口径：
  /// String.length 单点、只计 content）。
  Future<Map<String, Object?>> globalBackgroundsPayload({DateTime? now}) async {
    final all = await _repo.listBackgrounds(scope: Background.scopeGlobal);
    final today = isoDate(now ?? DateTime.now());
    final chars = all.fold(0, (sum, b) => sum + b.content.length);
    return {
      'entries': [
        for (final b in all)
          {
            ...backgroundToJson(b),
            // 过期=有日期窗且整窗已在过去（无窗=长期恒不过期；读侧 fail-closed
            // 的空窗=死窗，按过期处理与「永不进排程上下文」一致）
            'expired': b.applicableDates != null &&
                (b.applicableDates!.isEmpty || b.applicableDates!.last.compareTo(today) < 0),
          },
      ],
      'budget': {
        'entries': all.length,
        'chars': chars,
        'limit_entries': ScheduleRules.globalBackgroundsMax,
        'limit_chars': ScheduleRules.globalBackgroundCharsMax,
      },
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
    // 凭证摘要搭载（fact 草案 §1.6 定稿修正：窗口对齐实际返回视窗，state 白名单）
    // ——视窗内一次性取行，逐日按锚点覆盖过滤装配
    final structuredFacts = await _repo.artifactsByState(Artifact.stateStructured);
    final factPlanTitles = <String, String>{};
    for (final f in structuredFacts) {
      final pid = f.planId;
      if (pid != null && !factPlanTitles.containsKey(pid)) {
        factPlanTitles[pid] = (await _repo.planById(pid))?.title ?? '未知计划';
      }
    }
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
        // 排程背景装配（背景草案 §4 排程通道）：物理过滤+祖先链+分区禁平铺，
        // 与容量水位线/天气（几何机械数据）严格分区——日级态势句不携带任何背景
        'backgrounds': await _dayBackgroundPayload(iso, planIds),
        // 凭证摘要搭载（fact 草案 §1.6）：当日硬约束自动到达 AI 视线——
        // 排程必须避开发车/止检/止入场锚点
        'facts': _dayFactsPayload(structuredFacts, factPlanTitles, iso),
      });
    }
    return {
      'today': isoDate(today),
      'days': out,
      // 视窗外硬锚点摘要（fact 草案 §1.6 边界补全，2026-10-08 拍板）：3 天视窗
      // 之后 ~14 天内的 structured 凭证轻量投影——补「提前多日整段排行程看不到票」
      // 盲区；只给 date/title/deadline_min/plan_title 四字段，防上下文膨胀。
      'upcoming_facts': _upcomingFactsPayload(structuredFacts, factPlanTitles, today, n),
    };
  }

  /// 视窗外（窗口末日之后 ~14 天内）structured 凭证摘要：按最早锚 date 升序，
  /// 每条 {date, title, deadline_min?, plan_title?}；不带锚数组/constraints
  /// （§1.6 边界补全：与 list_plans「归档冷数据不拉」同原则，轻字段防膨胀）。
  List<Map<String, Object?>> _upcomingFactsPayload(List<Artifact> facts,
      Map<String, String> titles, DateTime today, int windowDays) {
    final windowEnd = addDays(today, windowDays - 1);
    final horizon = addDays(today, windowDays + 13); // 视窗外 ~14 天
    final rows = <(DateTime, Map<String, Object?>)>[];
    for (final f in facts) {
      if (f.state != Artifact.stateStructured) continue;
      final anchors = f.payload['time_anchors'];
      if (anchors is! List) continue;
      DateTime? earliest;
      int? deadlineMin;
      for (final e in anchors) {
        if (e is! Map) continue;
        final date = e['date'];
        if (date is! String || date.length < 10) continue;
        final d = DateTime.tryParse(date.substring(0, 10));
        if (d == null) continue;
        if (earliest == null || d.isBefore(earliest)) earliest = d;
        // 若该锚恰在本实现关注的视窗外段，取其时刻/段首作 deadline
        if (d.isAfter(windowEnd) && !d.isAfter(horizon)) {
          for (final key in const {'min', 'start_min'}) {
            final v = e[key];
            if (v is int && (deadlineMin == null || v < deadlineMin)) {
              deadlineMin = v;
            }
          }
        }
      }
      if (earliest == null) continue;
      if (!earliest.isAfter(windowEnd)) continue; // 窗内锚走 facts 正常搭载
      if (earliest.isAfter(horizon)) continue; // 过远冷数据不拉
      rows.add((
        earliest,
        {
          'date': isoDate(earliest),
          'title': f.title,
          'deadline_min': ?deadlineMin,
          if (f.planId != null) 'plan_title': titles[f.planId!],
        },
      ));
    }
    rows.sort((a, b) => a.$1.compareTo(b.$1));
    return [for (final r in rows) r.$2];
  }

  /// 当日 ∈ applicable_dates（背景草案 §4 排程物理过滤；无窗=恒注入）。
  /// 一律日历日比对（施工钉子：严禁作息日过滤）；读侧 fail-closed 的空窗
  /// contains=false → 不注入，与「永不进排程上下文」一致。
  bool _backgroundApplies(Background b, String iso) =>
      b.applicableDates == null || b.applicableDates!.contains(iso);

  /// 单日排程背景装配（背景草案 §4）：
  /// - **物理过滤**：逐条做 [ _backgroundApplies] 集合判断，超期/不在窗的背景
  ///   根本不进排程上下文（省 token + 杜绝「用过期的脚扭伤排今天徒步」幻觉）；
  /// - **分区禁平铺**：global 段 + 各涉事计划段，每段带 scope_note——pB 的块
  ///   绝不吃到 pC 的慢节奏（global 唯一穿透权）；
  /// - **祖先链继承 ≤3 级**：注入范围=计划自身+沿 parent_id 上溯——挂根计划的
  ///   背景由此到达每个子计划的块；继承条目带 from_plan 溯源；
  /// - **特异性优先**：段内存在继承条目时整段携带 inherit_note 静态常量注记
  ///   （确定性字符串零智能；单层不携带防噪音）。
  /// 无背景的计划不出段（省 token）；与容量/天气严格分区（机械数据不混叙事）。
  Future<Map<String, Object?>> _dayBackgroundPayload(String iso, Set<String> planIds) async {
    final globals = [
      for (final b in await _repo.listBackgrounds(scope: Background.scopeGlobal))
        if (_backgroundApplies(b, iso))
          {'id': b.id, 'content': b.content, 'tags': b.tags},
    ];
    final planSections = <String, Object?>{};
    for (final id in planIds) {
      final plan = await _repo.planById(id);
      if (plan == null) continue; // 孤儿引用防御（FK 兜底，理论不可达）
      final items = <Map<String, Object?>>[];
      var hasInherited = false;
      // 祖先链：自身（depth 0）+ ≤3 级上溯（与粒度定律子树深度一致）
      var cur = plan;
      for (var depth = 0;; depth++) {
        final bgs = await _repo.listBackgrounds(scope: Background.scopePlan, planId: cur.id!);
        for (final b in bgs) {
          if (!_backgroundApplies(b, iso)) continue;
          final inherited = depth > 0;
          hasInherited |= inherited;
          items.add({
            'id': b.id,
            'content': b.content,
            'tags': b.tags,
            if (inherited) 'from_plan': cur.title,
          });
        }
        if (depth >= 3 || cur.parentId == null) break;
        final parent = await _repo.planById(cur.parentId!);
        if (parent == null) break;
        cur = parent;
      }
      if (items.isEmpty) continue;
      planSections[id] = {
        'title': plan.title,
        'scope_note': '仅约束该计划下的活动块',
        if (hasInherited)
          'inherit_note': '祖先背景为泛化默认；当前计划背景为具体细化，局部差异以当前计划为准',
        'items': items,
      };
    }
    return {'global': globals, 'plans': planSections};
  }

  /// 单日凭证摘要装配（fact-user-relay-draft.md §1.6，2026-10-07 定稿修正）：
  /// - **窗口对齐**：搭载范围=get_schedule 实际返回视窗（无参=当日+次日、
  ///   days=N 全窗）——固定两日窗会让远期硬约束全盲（周三排周五到周日）；
  /// - **state 白名单**（§8 施工微观约定 3）：仅 state=structured——raw 原文不
  ///   消耗排程 prompt token，voided 不出现在排程视线；
  /// - **按计划分组禁平铺**（背景分区装配同款）：每段带计划标题；
  ///   摘要四件套=category/title/badge/最早 deadline_min（≈100 token/日），
  ///   凭证全文只进 UI 不进 AI 上下文。
  Map<String, Object?> _dayFactsPayload(
      List<Artifact> facts, Map<String, String> titles, String iso) {
    final planSections = <String, Object?>{};
    final unattributed = <Map<String, Object?>>[];
    for (final f in facts) {
      if (!_factCoversDate(f, iso)) continue;
      final item = <String, Object?>{
        'id': f.id,
        'category': f.category,
        'title': f.title,
        'badge': ?f.badge,
        'deadline_min': ?_earliestDeadlineMin(f, iso),
      };
      final pid = f.planId;
      if (pid == null) {
        unattributed.add(item);
        continue;
      }
      var section = planSections[pid] as Map<String, Object?>?;
      if (section == null) {
        section = <String, Object?>{'title': titles[pid], 'items': <Map<String, Object?>>[]};
        planSections[pid] = section;
      }
      (section['items'] as List<Map<String, Object?>>).add(item);
    }
    return {'plans': planSections, 'unattributed': unattributed};
  }

  /// 锚点覆盖判定：任意锚 date 命中当日，或 span 跨日覆盖 [date, end_date]
  /// 闭区间（一律日历日口径 §1.3）。
  bool _factCoversDate(Artifact f, String iso) {
    final anchors = f.payload['time_anchors'];
    if (anchors is! List) return false;
    for (final e in anchors) {
      if (e is! Map) continue;
      final date = e['date'];
      if (date is! String) continue;
      if (date == iso) return true;
      final end = e['end_date'];
      if (end is String && date.compareTo(iso) < 0 && iso.compareTo(end) <= 0) return true;
    }
    return false;
  }

  /// 最早 deadline_min：当日 moment.min 与 span.start_min 取最小（检票/止检类
  /// 时刻是排程硬约束锚）；纯日期锚无分钟 → 缺省不携带。
  int? _earliestDeadlineMin(Artifact f, String iso) {
    final anchors = f.payload['time_anchors'];
    if (anchors is! List) return null;
    int? best;
    for (final e in anchors) {
      if (e is! Map) continue;
      if (e['date'] != iso) continue;
      for (final key in const {'min', 'start_min'}) {
        final v = e[key];
        if (v is int && (best == null || v < best)) best = v;
      }
    }
    return best;
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
