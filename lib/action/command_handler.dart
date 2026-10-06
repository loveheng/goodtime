import 'dart:convert';

import '../data/repository.dart';
import '../data/settings.dart';
import 'dart:math' show min;
import '../models/fixed_slot.dart';
import '../models/plan.dart';
import '../models/schedule_block.dart';
import '../util/schedule_day.dart';
import 'commands.dart';
import 'queries.dart' show swapCandidatesFor;
import 'rules.dart';

/// UI / MCP 共用的**唯一写入口**（Human-AI 对称架构核心，模式照抄拾贝
/// item_action_handler.dart——schedule-app.md §12 领料）。
///
/// 三条硬性质：
///
/// 1. **单一入参形态**：只接收 [ScheduleCommand]。UI 组装对象、MCP 反序列化 JSON、
///    核心逻辑不感知来源（无头化）。
/// 2. **防呆全下沉**：主体越权、plan 存在性、重叠/作息边界（AI 侧）、settings
///    前置，全部在本类内部拦截。UI 把按钮置灰只是「快路径」而非安全边界
///    ——AI 是瞎子，看不到按钮灰没灰。
/// 3. **写后回状态**：每条命令都回 [CommandResult] 携带最新快照；version_conflict
///    的错误体也直接带最新快照（§6 返回契约），AI 当场合并重试、无需重拉。
///
/// 主体 × 命令 权限矩阵（越权在此拦截，绝不下放到传输层）：
///
/// | 命令 | human | ai |
/// |---|---|---|
/// | quick_capture / confirm_block / reject_block / adjust_block_time / shift_block / tick_block | ✓ | ✗（人=终审，§3） |
/// | upsert_plan / update_plan / update_settings / update_fixed_slots | ✓ | ✓ |
/// | propose_schedule | ✗（人直接放块走 app 通道） | ✓（机械校验全量适用） |
class CommandHandler {
  CommandHandler(this._repo);

  final Repository _repo;

  /// 执行单条命令——UI 与 AI 唯一的落库路径。
  ///
  /// [actor] 由传输层注入（app=human / MCP=ai，不可由命令载荷伪造）。
  /// 写路径统一进 [Repository.synchronized] FIFO 锁：把「读 → 校验 → 写」
  /// 压成串行，消除并发交错导致的「基于过期快照做校验」（TOCTOU）。
  /// 越权门在锁内异步抛——保证本方法恒返 Future，不向调用方同步泄异常。
  Future<CommandResult> execute(ScheduleCommand cmd, {CommandActor actor = CommandActor.human}) {
    return _repo.synchronized(() {
      _gate(cmd, actor);
      return _dispatch(cmd);
    });
  }

  Future<CommandResult> _dispatch(ScheduleCommand cmd) => switch (cmd) {
        final QuickCaptureCommand c => _quickCapture(c),
        final QuickNoteDraftCommand c => _quickNoteDraft(c),
        final UpsertPlanCommand c => _upsertPlan(c),
        final UpdatePlanCommand c => _updatePlan(c),
        final ProposeScheduleCommand c => _propose(c),
        final AdjustBlocksCommand c => _adjustBlocks(c),
        final PlaceBlockCommand c => _placeBlock(c),
        final RetroLogCommand c => _retroLog(c),
        final SwapBlockCommand c => _swapBlock(c),
        final DegradeBlockCommand c => _degradeBlock(c),
        final MeltBlockCommand c => _meltBlock(c),
        final ReflowDayCommand c => _reflowDay(c),
        final DeletePlanCommand c => _deletePlan(c),
        final ConfirmBlockCommand c => _confirmBlock(c),
        final RejectBlockCommand c => _rejectBlock(c),
        final AdjustBlockTimeCommand c => _adjustBlockTime(c),
        final ShiftBlockCommand c => _shiftBlock(c),
        final TickBlockCommand c => _tickBlock(c),
        final UpdateSettingsCommand c => _updateSettings(c),
        final UpdateFixedSlotsCommand c => _updateFixedSlots(c),
      };

  void _gate(ScheduleCommand cmd, CommandActor actor) {
    final humanOnly = switch (cmd) {
      QuickCaptureCommand() ||
      QuickNoteDraftCommand() ||
      PlaceBlockCommand() ||
      RetroLogCommand() ||
      SwapBlockCommand() ||
      DegradeBlockCommand() ||
      MeltBlockCommand() ||
      ReflowDayCommand() ||
      DeletePlanCommand() ||
      ConfirmBlockCommand() ||
      RejectBlockCommand() ||
      AdjustBlockTimeCommand() ||
      ShiftBlockCommand() ||
      TickBlockCommand() =>
        true,
      _ => false,
    };
    if (humanOnly && actor != CommandActor.human) {
      throw ActionException(
        '越权：${cmd.op} 仅限人类执行（人=终审，§3 三方职责契约）',
        code: ActionErrorCode.forbidden,
        hint: 'AI 侧请走对应工具（add_plan/update_plan/propose_schedule）',
      );
    }
    final aiOnly = cmd is ProposeScheduleCommand || cmd is AdjustBlocksCommand;
    if (aiOnly && actor != CommandActor.ai) {
      throw ActionException(
        '越权：${cmd.op} 仅限 AI（校验刻度不对称，§3：人直接放块走 place_block/app 通道）',
        code: ActionErrorCode.forbidden,
      );
    }
    // uiOnly 键：外观/感官偏好仅人可写，AI 经 update_settings 触碰即拒
    // （Human-AI 对称性：人的感官偏好 AI 无权代拨，2026-10-06 拍板）。
    if (cmd is UpdateSettingsCommand && actor == CommandActor.ai) {
      for (final key in cmd.values.keys) {
        if (SettingsKeys.uiOnly.contains(key)) {
          throw ActionException(
            '越权：设置键 $key 仅限用户本人在设置页修改',
            code: ActionErrorCode.forbidden,
            hint: '外观偏好（theme_mode）由用户自选，AI 请勿代拨',
          );
        }
      }
    }
  }

  // ---- plans ----

  Future<CommandResult> _quickCapture(QuickCaptureCommand cmd) async {
    _validateDeadline(cmd.deadline);
    final plan = await _repo.addPlan(Plan(
      title: cmd.title,
      importance: cmd.importance,
      deadline: cmd.deadline,
    ));
    return CommandResult(
      op: cmd.op,
      targetId: plan.id,
      snapshot: planToJson(plan),
      note: '已收入清单（默认轻松/随手可做，被排期即升格）',
    );
  }

  // ---- 快记草稿（§10 快记入口 2026-10-06 拍板）----

  /// 草稿三字段整体覆写；text 空=整组清除（保存成功/清空收起的唯一清除通道）。
  /// 仅 UI 通道（human-only），不进 MCP 工具面；deadline 缺省时显式清旧值防残留。
  Future<CommandResult> _quickNoteDraft(QuickNoteDraftCommand cmd) async {
    final text = cmd.text.trim();
    if (text.isEmpty) {
      await _repo.settingsClear(SettingsKeys.quickNoteDraftText);
      await _repo.settingsClear(SettingsKeys.quickNoteDraftImportant);
      await _repo.settingsClear(SettingsKeys.quickNoteDraftDeadline);
      return CommandResult(op: cmd.op, note: '草稿已清除');
    }
    await _repo.settingsSet({
      SettingsKeys.quickNoteDraftText: text,
      SettingsKeys.quickNoteDraftImportant: cmd.importance ? '1' : '0',
    });
    if (cmd.deadline == null) {
      await _repo.settingsClear(SettingsKeys.quickNoteDraftDeadline);
    } else {
      await _repo.settingsSet(
          {SettingsKeys.quickNoteDraftDeadline: cmd.deadline!});
    }
    return CommandResult(op: cmd.op, note: '草稿已暂存');
  }

  Future<CommandResult> _upsertPlan(UpsertPlanCommand cmd) async {
    _validatePlanFields(
      energyLevel: cmd.energyLevel,
      toolRequired: cmd.toolRequired,
      deadline: cmd.deadline,
      estimate: cmd.estimate,
    );
    if (cmd.parentId != null) await _requirePlan(cmd.parentId!);
    final plan = await _repo.addPlan(Plan(
      title: cmd.title,
      spec: cmd.spec,
      notes: cmd.notes,
      minViableAction: cmd.minViableAction,
      energyLevel: cmd.energyLevel ?? Plan.energyLight,
      toolRequired: cmd.toolRequired ?? Plan.toolAnywhere,
      rewardSpec: cmd.rewardSpec,
      importance: cmd.importance,
      deadline: cmd.deadline,
      estimate: cmd.estimate,
      parentId: cmd.parentId,
    ));
    return CommandResult(op: cmd.op, targetId: plan.id, snapshot: planToJson(plan), note: '计划已创建');
  }

  Future<CommandResult> _updatePlan(UpdatePlanCommand cmd) async {
    await _requirePlan(cmd.id);
    _validatePlanFields(
      energyLevel: cmd.energyLevel,
      toolRequired: cmd.toolRequired,
      deadline: cmd.deadline,
      estimate: cmd.estimate,
    );
    final values = <String, Object?>{
      if (cmd.title != null) 'title': cmd.title,
      if (cmd.spec != null) 'spec': cmd.spec,
      if (cmd.notes != null) 'notes': cmd.notes,
      if (cmd.minViableAction != null) 'min_viable_action': cmd.minViableAction,
      if (cmd.energyLevel != null) 'energy_level': cmd.energyLevel,
      if (cmd.toolRequired != null) 'tool_required': cmd.toolRequired,
      if (cmd.rewardSpec != null) 'reward_spec': cmd.rewardSpec,
      if (cmd.importance != null) 'importance': cmd.importance! ? 1 : 0,
      if (cmd.deadline != null) 'deadline': cmd.deadline,
      if (cmd.estimate != null) 'estimate': cmd.estimate,
      if (cmd.parentId != null) 'parent_id': cmd.parentId,
      if (cmd.archived != null) 'archived': cmd.archived! ? 1 : 0,
    };
    if (values.isEmpty) {
      throw ActionException(
        '没有可更新的字段',
        code: ActionErrorCode.invalidRequest,
        hint: 'update_plan 只发要改的字段（§11 并发契约）',
      );
    }
    final ok = await _repo.patchPlan(cmd.id, values, expectedVersion: cmd.expectedVersion);
    if (!ok) {
      final fresh = await _repo.planById(cmd.id);
      throw _conflict(cmd.op, fresh == null ? null : planToJson(fresh));
    }
    final fresh = await _requirePlan(cmd.id);
    return CommandResult(op: cmd.op, targetId: cmd.id, snapshot: planToJson(fresh), note: '已更新');
  }

  // ---- propose_schedule（§6 按天整表原子写） ----

  Future<CommandResult> _propose(ProposeScheduleCommand cmd) async {
    final settings = await _repo.settingsAll();
    if (!SettingsKeys.initialized(settings)) {
      throw ActionException(
        'settings 未初始化：缺少作息边界（wake_time/sleep_time）',
        code: ActionErrorCode.settingsMissing,
        hint: '先经 update_settings 写入作息边界再 propose——绝不静默按 24h 空闲排（§6 校验铁律）',
      );
    }
    final date = tryParseIsoDate(cmd.date);
    if (date == null) {
      throw ActionException('date 非法：${cmd.date}（须 yyyy-MM-dd）', code: ActionErrorCode.invalidRequest);
    }
    final wake = SettingsKeys.intOf(settings, SettingsKeys.wakeTime)!;
    final sleep = SettingsKeys.intOf(settings, SettingsKeys.sleepTime)!;
    // 睡觉早于起床 ⇒ 睡眠跨午夜，当日包络延伸到次日
    final sleepAdj = sleep <= wake ? sleep + 1440 : sleep;

    final existing = await _repo.blocksOnDate(cmd.date);
    // 例外日（§13 旅行模式）：工作类 fixed_slots 挂起（作息边界保留），填充率自动降至 35%
    final suspended = parseExceptions(settings).any((w) => w.covers(date));
    final resolved = suspended
        ? (onDate: const <FixedSlot>[], spillover: const <FixedSlot>[])
        : await _repo.fixedSlotsForDate(date);
    // 和平条款（§3）：human/pinned 是当天固定占用；已成现实的 AI 块（confirmed 及之后）
    // 同等绕行——只有未确认（proposed）的 AI 块会被本单整体替换。
    final walls = [for (final b in existing) if (_isWall(b)) b];
    final replaceable = [
      for (final b in existing)
        if (b.source == ScheduleBlock.sourceAi &&
            b.status == ScheduleBlock.statusProposed &&
            !b.pinned)
          b,
    ];

    final rejections = <Map<String, Object?>>[];
    // 火种至多一个（§4：每作息日至多一个 is_day_spark）
    final sparks = [
      for (var i = 0; i < cmd.items.length; i++)
        if (cmd.items[i].isDaySpark) i,
    ];
    for (final i in sparks.skip(1)) {
      _reject(rejections, i, '当日黄金火种至多一个', hint: 'is_day_spark 指定当天最重要的那件事');
    }
    // 提案内部两两重叠（跨午夜经归一化展开）
    for (var i = 0; i < cmd.items.length; i++) {
      for (var j = i + 1; j < cmd.items.length; j++) {
        if (overlapsMinutes(cmd.items[i].startMin, cmd.items[i].endMin, cmd.items[j].startMin,
            cmd.items[j].endMin)) {
          _reject(rejections, j, '与提案内第 $i 项时间重叠', hint: '先消内部重叠再整单重试');
        }
      }
    }
    // 逐项静态校验 + plan 存在性 + 撞墙
    final planCache = <String, Plan?>{};
    for (var i = 0; i < cmd.items.length; i++) {
      final it = cmd.items[i];
      final staticError =
          _proposeStaticError(it, wake: wake, sleepAdj: sleepAdj);
      if (staticError != null) _reject(rejections, i, staticError);
      if (it.planId != null) {
        if (!planCache.containsKey(it.planId!)) {
          planCache[it.planId!] = await _repo.planById(it.planId!);
        }
        if (planCache[it.planId!] == null) {
          _reject(rejections, i, 'plan_id=${it.planId} 不存在',
              hint: '先 list_plans 确认 id（默认只拉未归档计划）');
        }
      }
      if (rejections.any((r) => r['index'] == i)) continue;
      for (final b in walls) {
        if (overlapsMinutes(it.startMin, it.endMin, b.startMin, b.endMin)) {
          _reject(rejections, i,
              '与当天不可穿透占用重叠（${b.source}${b.pinned ? '/pinned' : ''} 块 ${clockOf(b.startMin)}-${clockOf(b.endMin)}）',
              hint: '和平条款：human/pinned 块必须绕行（§3）');
          break;
        }
      }
      if (rejections.any((r) => r['index'] == i)) continue;
      for (final s in resolved.onDate) {
        final p = _slotPortionOnDate(s, startedHere: true)!;
        if (overlapsMinutes(it.startMin, it.endMin, p.$1, p.$2)) {
          _reject(rejections, i, '与固定占用「${s.name}」重叠', hint: '固定占用=约束不是愿望（§4）');
          break;
        }
      }
      if (rejections.any((r) => r['index'] == i)) continue;
      for (final s in resolved.spillover) {
        final p = _slotPortionOnDate(s, startedHere: false)!;
        if (overlapsMinutes(it.startMin, it.endMin, p.$1, p.$2)) {
          _reject(rejections, i, '与前夜溢出的固定占用「${s.name}」重叠（${clockOf(p.$1)}-${clockOf(p.$2)}）',
              hint: '跨午夜占用按开始日归属，溢出段同样不可穿透（§4）');
          break;
        }
      }
    }

    // —— M2 机械校验全量（§6，同源常量见 rules.dart）——
    final energy = settings[SettingsKeys.todayEnergy] ?? 'normal';
    final rejectedIdx = rejections.map((r) => r['index']).toSet();
    // 呼吸律：>45min 块后必须留 ≥15min 绝对空白（提案内相邻对）
    final order = [
      for (var i = 0; i < cmd.items.length; i++)
        if (!rejectedIdx.contains(i)) i,
    ]..sort((a, b) => cmd.items[a].startMin.compareTo(cmd.items[b].startMin));
    for (var k = 0; k + 1 < order.length; k++) {
      final a = cmd.items[order[k]];
      final aSpan = spanMinutes(a.startMin, a.endMin);
      final aEndAbs = a.startMin + aSpan;
      if (aSpan > ScheduleRules.breathMin &&
          cmd.items[order[k + 1]].startMin < aEndAbs + ScheduleRules.breathBufferMin) {
        _reject(rejections, order[k + 1],
            '呼吸律：$aSpan 分钟块后须留 ≥${ScheduleRules.breathBufferMin} 分钟空白',
            hint: '削减任务总量、扩大留白（减震器原则）');
      }
    }
    // 低电量 deep 禁排（火种豁免刻度：is_day_spark ≤15min 且取 min_viable_action）
    if (energy == 'low') {
      for (var i = 0; i < cmd.items.length; i++) {
        if (rejectedIdx.contains(i)) continue;
        final it = cmd.items[i];
        final plan = it.planId == null ? null : planCache[it.planId!];
        if (plan?.energyLevel != Plan.energyDeep) continue;
        final span = spanMinutes(it.startMin, it.endMin);
        final exempt = it.isDaySpark &&
            span <= ScheduleRules.sparkMaxMinutes &&
            (plan!.minViableAction?.isNotEmpty ?? false);
        if (!exempt) {
          _reject(rejections, i, '低电量日禁排深度任务（today_energy=low）',
              hint: '火种豁免刻度：is_day_spark 块 ≤${ScheduleRules.sparkMaxMinutes}min 且执行内容取 min_viable_action');
        }
      }
    }
    // 填充率红线：AI 块总时长 ≤ 可用时间 × 比例（低电量 25% / 例外日 35% 取更严；
    // update_settings 可调默认 60%——留白是吸收意外的护城河）
    final rate = min(
      energy == 'low'
          ? ScheduleRules.fillRateLowEnergyPercent
          : (SettingsKeys.intOf(settings, SettingsKeys.fillRateLimit) ??
              ScheduleRules.fillRateDefaultPercent),
      suspended ? ScheduleRules.fillRateExceptionsPercent : 100,
    );
    final occupied = <(int, int)>[
      for (final b in walls)
        (b.startMin, b.startMin + spanMinutes(b.startMin, b.endMin)),
      for (final s in resolved.onDate) _slotPortionOnDate(s, startedHere: true)!,
      for (final s in resolved.spillover) _slotPortionOnDate(s, startedHere: false)!,
    ];
    final free = _freeMinutes(wake, sleepAdj, occupied);
    final aiTotal = [
      for (var i = 0; i < cmd.items.length; i++)
        if (!rejectedIdx.contains(i)) spanMinutes(cmd.items[i].startMin, cmd.items[i].endMin),
    ].fold(0, (a, b) => a + b);
    if (aiTotal > free * rate / 100) {
      _reject(rejections, -1,
          '填充率红线：AI 块总时长 $aiTotal 分钟超过当日可用 $free 分钟的 $rate%',
          hint: '削减任务总量、扩大留白——被拒不是因为排得差，是因为排得满（§6 减震器原则）');
    }

    if (rejections.isNotEmpty) {
      throw ActionException(
        'propose_schedule 整单拒绝：${rejections.length} 项不合法（全部合法才落库）',
        code: ActionErrorCode.scheduleRejected,
        data: {
          'date': cmd.date,
          'rejections': rejections,
          'available_free_windows':
              _freeWindows(wake: wake, sleepAdj: sleepAdj, walls: walls, resolved: resolved),
        },
        hint: '按逐条 reason 修正后整单重试；填空优先使用 available_free_windows',
      );
    }

    // 整单原子写：替换当日 proposed AI 块 + 插入新提案（§6：全成功才提交）
    final inserted = <ScheduleBlock>[];
    await _repo.transaction((txn) async {
      for (final b in replaceable) {
        await txn.delete('schedule_blocks', where: 'id = ?', whereArgs: [b.id]);
      }
      final now = DateTime.now().millisecondsSinceEpoch;
      for (final it in cmd.items) {
        final b = ScheduleBlock(
          id: _repo.newId(),
          date: cmd.date,
          startMin: it.startMin,
          endMin: it.endMin,
          planId: it.planId,
          label: it.label,
          source: ScheduleBlock.sourceAi,
          status: ScheduleBlock.statusProposed,
          isDaySpark: it.isDaySpark,
          isCelebration: it.isCelebration,
          createdAt: now,
          updatedAt: now,
        );
        await txn.insert('schedule_blocks', b.toMap());
        inserted.add(b);
      }
    });
    // 护航摘要（§6 protection_manifesto，八轮拍板：命令层按与校验同源规则常量计算）
    final freeAfter = free - aiTotal;
    final waiting = (await _repo.listPlans()).length;
    var suppressed =
        waiting - cmd.items.map((i) => i.planId).whereType<String>().toSet().length;
    if (suppressed < 0) suppressed = 0;
    final slack = free <= 0 ? 0.0 : (freeAfter < 0 ? 0.0 : freeAfter / free);
    final sparkCount = cmd.items.where((i) => i.isDaySpark).length;
    final manifesto = {
      'shielded_free_minutes': freeAfter < 0 ? 0 : freeAfter,
      'slack_ratio': slack,
      'suppressed_tasks_count': suppressed,
      'narrative': '本次拦截 $suppressed 件未排入今天；'
          '守住了 ${freeAfter < 0 ? 0 : freeAfter} 分钟自由流动（留白比 ${(slack * 100).toStringAsFixed(0)}%）；'
          '当日核心 $sparkCount 件。排程不是为了占满时间，而是为了捞起一颗珍珠。',
    };
    return CommandResult(
      op: cmd.op,
      data: {
        'date': cmd.date,
        'blocks': [for (final b in inserted) blockToJson(b)],
        'replaced': replaceable.length,
        'protection_manifesto': manifesto,
      },
      note: '提案已写入 ${cmd.date}：${cmd.items.length} 块待确认（proposed）',
    );
  }

  /// 逐项静态校验：起止合法 + 作息边界（AI 必须讲理 §3；human 放行通道见 adjust_block_time）。
  String? _proposeStaticError(ProposedItem it, {required int wake, required int sleepAdj}) {
    if (it.startMin < 0 || it.startMin > 1439) return 'start_min 越界（0..1439）';
    if (it.endMin < 1 || it.endMin > 1440) return 'end_min 越界（1..1440）';
    if (it.startMin == it.endMin) return '零长块：start_min == end_min（一切块必须有起止）';
    if (it.startMin < wake) return '越作息边界：start 早于起床（${clockOf(wake)}）';
    final endAbs = it.endMin <= it.startMin ? it.endMin + 1440 : it.endMin;
    if (endAbs > sleepAdj) {
      return '越作息边界：end 晚于睡觉（${clockOf(sleepAdj % 1440)}）';
    }
    return null;
  }

  /// 当日剩余连续空闲窗口（§6：被拒时附给，AI 填空不盲猜）。
  /// 占用 = 固定占用投射 + 和平条款墙（human/pinned/已成现实 AI 块）。
  List<Map<String, Object?>> _freeWindows({
    required int wake,
    required int sleepAdj,
    required List<ScheduleBlock> walls,
    required ({List<FixedSlot> onDate, List<FixedSlot> spillover}) resolved,
  }) {
    final occupied = <(int, int)>[
      for (final b in walls)
        (b.startMin, b.startMin + spanMinutes(b.startMin, b.endMin)),
      for (final s in resolved.onDate) _slotPortionOnDate(s, startedHere: true)!,
      for (final s in resolved.spillover) _slotPortionOnDate(s, startedHere: false)!,
    ]..sort((a, b) => a.$1.compareTo(b.$1));
    final windows = <Map<String, Object?>>[];
    var cursor = wake;
    for (final (s, e) in occupied) {
      if (e <= cursor) continue;
      if (s > cursor && cursor < sleepAdj) {
        final end = s < sleepAdj ? s : sleepAdj;
        windows.add({'start_min': cursor, 'end_min': end, 'start': clockOf(cursor), 'end': clockOf(end)});
      }
      if (e > cursor) cursor = e;
    }
    if (cursor < sleepAdj) {
      windows.add({'start_min': cursor, 'end_min': sleepAdj, 'start': clockOf(cursor), 'end': clockOf(sleepAdj)});
    }
    return windows;
  }

  // ---- 确认三键 / 勾选 / 顺延（human 通道） ----

  /// 人直接放块（§3：human 可任性，只拦起止非法；重叠/越界由 UI 橙色提示放行）。
  Future<CommandResult> _placeBlock(PlaceBlockCommand cmd) async {
    final date = tryParseIsoDate(cmd.date);
    if (date == null) {
      throw ActionException('date 非法：${cmd.date}（须 yyyy-MM-dd）', code: ActionErrorCode.invalidRequest);
    }
    if (cmd.startMin < 0 || cmd.startMin > 1439 || cmd.endMin < 1 || cmd.endMin > 1440) {
      throw ActionException('起止越界（start 0..1439 / end 1..1440）', code: ActionErrorCode.invalidRequest);
    }
    if (cmd.startMin == cmd.endMin) {
      throw ActionException('零长块：start == end', code: ActionErrorCode.invalidRequest);
    }
    if (cmd.planId != null) await _requirePlan(cmd.planId!);
    final b = await _repo.addBlock(ScheduleBlock(
      date: cmd.date,
      startMin: cmd.startMin,
      endMin: cmd.endMin,
      planId: cmd.planId,
      label: cmd.label,
      source: ScheduleBlock.sourceHuman,
      status: ScheduleBlock.statusConfirmed,
    ));
    return CommandResult(
      op: cmd.op,
      targetId: b.id,
      snapshot: blockToJson(b),
      note: '已放入日程（自己排的事项，AI 会绕行）',
    );
  }

  /// 删除计划（human 专属）：外键兜底——有日程块/子计划引用时转可读错误。
  Future<CommandResult> _deletePlan(DeletePlanCommand cmd) async {
    await _requirePlan(cmd.id);
    try {
      await _repo.deletePlan(cmd.id);
    } catch (e) {
      throw ActionException(
        '删除被拒：该计划被日程块或子计划引用',
        code: ActionErrorCode.invalidRequest,
        hint: '先处理引用（块否决/子计划删除）或改用「归档」',
      );
    }
    return CommandResult(op: cmd.op, targetId: cmd.id, note: '已删除');
  }

  /// 窗口 [wake, sleepAdj) 内的空闲分钟数 = 窗长 − 占用并集∩窗（§6 可用时间口径，
  /// 几何实现在 schedule_day.freeMinutesIn，与 UI 自由留白同源）。
  int _freeMinutes(int wake, int sleepAdj, List<(int, int)> occupied) =>
      freeMinutesIn(wake, sleepAdj, occupied);

  /// AI 单块建/挪/缩/删（§3 adjust_blocks；和平条款门控：human/pinned 不可动）。
  Future<CommandResult> _adjustBlocks(AdjustBlocksCommand cmd) async {
    switch (cmd.action) {
      case 'add':
        return _adjustAdd(cmd);
      case 'move' || 'resize':
        return _adjustMoveOrResize(cmd);
      case 'remove':
        return _adjustRemove(cmd);
      default:
        throw ActionException(
          '未知 action：${cmd.action}（add/move/resize/remove）',
          code: ActionErrorCode.invalidRequest,
        );
    }
  }

  Future<({Map<String, String> settings, int wake, int sleepAdj})> _envelope() async {
    final settings = await _repo.settingsAll();
    if (!SettingsKeys.initialized(settings)) {
      throw ActionException(
        'settings 未初始化：缺少作息边界（wake_time/sleep_time）',
        code: ActionErrorCode.settingsMissing,
      );
    }
    final wake = SettingsKeys.intOf(settings, SettingsKeys.wakeTime)!;
    final sleep = SettingsKeys.intOf(settings, SettingsKeys.sleepTime)!;
    return (settings: settings, wake: wake, sleepAdj: sleep <= wake ? sleep + 1440 : sleep);
  }

  Future<CommandResult> _adjustAdd(AdjustBlocksCommand cmd) async {
    final env = await _envelope();
    if (cmd.date == null || cmd.startMin == null || cmd.endMin == null) {
      throw ActionException(
        'add 需要 date/start_min/end_min',
        code: ActionErrorCode.invalidRequest,
      );
    }
    if (tryParseIsoDate(cmd.date!) == null) {
      throw ActionException('date 非法：${cmd.date}', code: ActionErrorCode.invalidRequest);
    }
    final item = ProposedItem(
      planId: cmd.planId,
      label: cmd.label,
      startMin: cmd.startMin!,
      endMin: cmd.endMin!,
    );
    final staticError = _proposeStaticError(item, wake: env.wake, sleepAdj: env.sleepAdj);
    if (staticError != null) {
      throw ActionException(staticError, code: ActionErrorCode.invalidRequest);
    }
    if (cmd.planId != null) await _requirePlan(cmd.planId!);
    await _checkWalls(cmd.date!, item);
    final now = DateTime.now().millisecondsSinceEpoch;
    final b = await _repo.addBlock(ScheduleBlock(
      date: cmd.date!,
      startMin: cmd.startMin!,
      endMin: cmd.endMin!,
      planId: cmd.planId,
      label: cmd.label,
      source: ScheduleBlock.sourceAi,
      status: ScheduleBlock.statusProposed,
      // AI 建的无 plan 硬行程默认 pinned（§4：火车/面试/航班）；显式传 pinned 可覆盖
      pinned: cmd.pinned ?? true,
      isCelebration: false,
      createdAt: now,
      updatedAt: now,
    ));
    return CommandResult(
      op: cmd.op,
      targetId: b.id,
      snapshot: blockToJson(b),
      note: '已加入单块提案（pinned=${b.pinned}）；硬行程不参与填充率聚合',
    );
  }

  Future<CommandResult> _adjustMoveOrResize(AdjustBlocksCommand cmd) async {
    if (cmd.blockId == null) {
      throw ActionException('move/resize 需要 block_id', code: ActionErrorCode.invalidRequest);
    }
    final b = await _requireBlock(cmd.blockId!);
    _requireAiMovable(b);
    final env = await _envelope();
    final date = cmd.date ?? b.date;
    final start = cmd.startMin ?? b.startMin;
    final end = cmd.endMin ?? b.endMin;
    if (cmd.date != null && tryParseIsoDate(date) == null) {
      throw ActionException('date 非法：$date', code: ActionErrorCode.invalidRequest);
    }
    final staticError = _proposeStaticError(
      ProposedItem(startMin: start, endMin: end),
      wake: env.wake,
      sleepAdj: env.sleepAdj,
    );
    if (staticError != null) {
      throw ActionException(staticError, code: ActionErrorCode.invalidRequest);
    }
    await _checkWalls(date, ProposedItem(startMin: start, endMin: end), excludeBlockId: b.id);
    final values = <String, Object?>{
      if (cmd.date != null) 'date': cmd.date,
      if (cmd.startMin != null) 'start_min': cmd.startMin,
      if (cmd.endMin != null) 'end_min': cmd.endMin,
    };
    final ok = await _repo.patchBlock(b.id!, values, expectedVersion: cmd.expectedVersion);
    if (!ok) throw await _blockConflict(cmd.op, cmd.blockId!);
    final fresh = await _requireBlock(b.id!);
    return CommandResult(op: cmd.op, targetId: b.id, snapshot: blockToJson(fresh), note: '已调整');
  }

  Future<CommandResult> _adjustRemove(AdjustBlocksCommand cmd) async {
    if (cmd.blockId == null) {
      throw ActionException('remove 需要 block_id', code: ActionErrorCode.invalidRequest);
    }
    final b = await _requireBlock(cmd.blockId!);
    _requireAiMovable(b);
    if (b.status == ScheduleBlock.statusProposed) {
      await _repo.deleteBlock(b.id!);
      return CommandResult(op: cmd.op, targetId: b.id, note: '提案已撤回');
    }
    // confirmed 及之后：熔断同效动作——melted 中性蒸发回池，绝不显红（§4/§10）
    final ok = await _repo.patchBlock(b.id!, {'status': ScheduleBlock.statusMelted},
        expectedVersion: cmd.expectedVersion);
    if (!ok) throw await _blockConflict(cmd.op, cmd.blockId!);
    final fresh = await _requireBlock(b.id!);
    return CommandResult(
      op: cmd.op,
      targetId: b.id,
      snapshot: blockToJson(fresh),
      note: '已融化回愿望池（melted 中性态，无痕）',
    );
  }

  /// 和平条款门控：AI 只能动自己的非 pinned 块（§3/§4）。
  void _requireAiMovable(ScheduleBlock b) {
    if (b.source != ScheduleBlock.sourceAi) {
      throw ActionException(
        '和平条款：human 块不可被 AI 覆盖',
        code: ActionErrorCode.forbidden,
        hint: 'human/pinned 块是当天固定占用，AI 必须绕行',
      );
    }
    if (b.pinned) {
      throw ActionException(
        '和平条款：pinned 块不可被 AI 覆盖',
        code: ActionErrorCode.forbidden,
        hint: '钉住=人终审（硬行程）；AI 撤回提案请用未 pinned 的自己的块',
      );
    }
  }

  /// 单块撞墙校验（AI 讲理）：human/pinned/已成现实 AI 块 + 固定占用（含前夜溢出）。
  Future<void> _checkWalls(String date, ProposedItem item, {String? excludeBlockId}) async {
    final parsed = tryParseIsoDate(date);
    if (parsed == null) {
      throw ActionException('date 非法：$date（须 yyyy-MM-dd）', code: ActionErrorCode.invalidRequest);
    }
    for (final b in await _repo.blocksOnDate(date)) {
      if (b.id == excludeBlockId) continue;
      if (!_isWall(b)) continue;
      if (overlapsMinutes(item.startMin, item.endMin, b.startMin, b.endMin)) {
        throw ActionException(
          '与不可穿透占用重叠（${b.source}${b.pinned ? '/pinned' : ''} 块 ${clockOf(b.startMin)}-${clockOf(b.endMin)}）',
          code: ActionErrorCode.scheduleRejected,
          hint: '和平条款：human/pinned 块必须绕行（§3）',
        );
      }
    }
    if (parseExceptions(await _repo.settingsAll()).any((w) => w.covers(parsed))) {
      return; // 例外日旅行模式：工作类固定占用挂起（作息边界保留，§13）
    }
    final resolved = await _repo.fixedSlotsForDate(parsed);
    for (final s in resolved.onDate) {
      final p = _slotPortionOnDate(s, startedHere: true)!;
      if (overlapsMinutes(item.startMin, item.endMin, p.$1, p.$2)) {
        throw ActionException('与固定占用「${s.name}」重叠', code: ActionErrorCode.scheduleRejected);
      }
    }
    for (final s in resolved.spillover) {
      final p = _slotPortionOnDate(s, startedHere: false)!;
      if (overlapsMinutes(item.startMin, item.endMin, p.$1, p.$2)) {
        throw ActionException('与前夜溢出的固定占用「${s.name}」重叠',
            code: ActionErrorCode.scheduleRejected);
      }
    }
  }

  // ---- 现实接口（M4）：逆向记账 / 换乘 / 坍缩 / 融化 / 水流重算 ----

  /// 逆向记账（§10 事实通道）：豁免排程校验（重叠/边界/填充率），只拦起止非法。
  Future<CommandResult> _retroLog(RetroLogCommand cmd) async {
    if (tryParseIsoDate(cmd.date) == null) {
      throw ActionException('date 非法：${cmd.date}', code: ActionErrorCode.invalidRequest);
    }
    if (cmd.startMin < 0 || cmd.startMin > 1439 || cmd.endMin < 1 || cmd.endMin > 1440) {
      throw ActionException('起止越界（start 0..1439 / end 1..1440）', code: ActionErrorCode.invalidRequest);
    }
    if (cmd.startMin == cmd.endMin) {
      throw ActionException('零长块：start == end', code: ActionErrorCode.invalidRequest);
    }
    if (cmd.planId != null) await _requirePlan(cmd.planId!);
    final q = cmd.executionQuality;
    if (q != null && q != ScheduleBlock.qualityFull && q != ScheduleBlock.qualitySpark) {
      throw ActionException('execution_quality 非法：$q（full/spark）', code: ActionErrorCode.invalidRequest);
    }
    final b = await _repo.addBlock(ScheduleBlock(
      date: cmd.date,
      startMin: cmd.startMin,
      endMin: cmd.endMin,
      planId: cmd.planId,
      label: cmd.label,
      source: ScheduleBlock.sourceHuman,
      status: ScheduleBlock.statusDone,
      executionQuality: q ?? ScheduleBlock.qualityFull,
    ));
    return CommandResult(
      op: cmd.op,
      targetId: b.id,
      snapshot: blockToJson(b),
      note: '已补记入账（事实通道，豁免排程校验；凡真实专注全部承认）',
    );
  }

  /// 换乘（§10）：原地换入待安排池时长相近的 light/anywhere 琐事，原任务无痕回池。
  Future<CommandResult> _swapBlock(SwapBlockCommand cmd) async {
    final b = await _requireBlock(cmd.id);
    if (b.pinned) {
      throw ActionException(
        'pinned 块不可换乘（硬行程不是疲劳问题）',
        code: ActionErrorCode.forbidden,
      );
    }
    if (b.status == ScheduleBlock.statusDone ||
        b.status == ScheduleBlock.statusArchived ||
        b.status == ScheduleBlock.statusMelted) {
      throw ActionException('终态块不可换乘（当前 ${b.status}）', code: ActionErrorCode.invalidRequest);
    }
    final candidates = await swapCandidatesFor(_repo, b);
    if (cmd.targetPlanId != null) {
      final picked =
          candidates.where((c) => c.id == cmd.targetPlanId).toList();
      if (picked.isEmpty) {
        throw ActionException(
          '指定候选不在可换乘池中（须为 light/anywhere 且未被排期）',
          code: ActionErrorCode.invalidRequest,
        );
      }
      return _applySwap(b, picked.first);
    }
    if (candidates.isEmpty) {
      throw ActionException(
        '愿望池没有可换乘的轻松事（light/anywhere 且未被排期）',
        code: ActionErrorCode.invalidRequest,
        hint: '先往清单里存几件 5 分钟就能做的琐事，疲惫时才有得换',
      );
    }
    return _applySwap(b, candidates.first);
  }

  Future<CommandResult> _applySwap(ScheduleBlock b, Plan candidate) async {
    await _repo.deleteBlock(b.id!);
    final nb = await _repo.addBlock(ScheduleBlock(
      date: b.date,
      startMin: b.startMin,
      endMin: b.endMin,
      planId: candidate.id,
      source: b.source,
      status: b.status,
    ));
    return CommandResult(
      op: 'swap_block',
      targetId: nb.id,
      snapshot: blockToJson(nb),
      data: {'swapped_out': b.id, 'swapped_in': candidate.id},
      note: '已换乘「${candidate.title}」，原任务已放回清单待安排',
    );
  }

  /// 坍缩降级（§3/§14）：块缩为 5 分钟火种胶囊，执行内容切 min_viable_action；
  /// 剩余时间释放为补给带；human 块不可坍缩。
  Future<CommandResult> _degradeBlock(DegradeBlockCommand cmd) async {
    final b = await _requireBlock(cmd.id);
    if (b.source != ScheduleBlock.sourceAi) {
      throw ActionException(
        'human 块不可坍缩（自己排的事自己说了算，§6.4）',
        code: ActionErrorCode.forbidden,
      );
    }
    if (b.status != ScheduleBlock.statusProposed &&
        b.status != ScheduleBlock.statusConfirmed) {
      throw ActionException('只有 proposed/confirmed 块可坍缩，当前 ${b.status}',
          code: ActionErrorCode.invalidRequest);
    }
    final plan = b.planId == null ? null : await _repo.planById(b.planId!);
    final ok = await _repo.patchBlock(b.id!, {
      'end_min': _normEnd(b.startMin + 5),
      'execution_quality': ScheduleBlock.qualitySpark,
    }, expectedVersion: cmd.expectedVersion);
    if (!ok) throw await _blockConflict(cmd.op, cmd.id);
    final fresh = await _requireBlock(cmd.id);
    return CommandResult(
      op: cmd.op,
      targetId: cmd.id,
      snapshot: blockToJson(fresh),
      note: '已切换为 5 分钟启动版'
          '${plan?.minViableAction != null ? '：${plan!.minViableAction}' : ''}'
          '（剩余时间已释放为留白缓冲）',
    );
  }

  /// 融化（§3/§4）：melted 中性蒸发回池，绝不显红；仅用户主动触发。
  Future<CommandResult> _meltBlock(MeltBlockCommand cmd) async {
    final b = await _requireBlock(cmd.id);
    if (b.status == ScheduleBlock.statusDone ||
        b.status == ScheduleBlock.statusMelted ||
        b.status == ScheduleBlock.statusArchived) {
      throw ActionException('终态块不可融化（当前 ${b.status}）', code: ActionErrorCode.invalidRequest);
    }
    final ok = await _repo.patchBlock(b.id!, {'status': ScheduleBlock.statusMelted},
        expectedVersion: cmd.expectedVersion);
    if (!ok) throw await _blockConflict(cmd.op, cmd.id);
    final fresh = await _requireBlock(cmd.id);
    return CommandResult(
      op: cmd.op,
      targetId: cmd.id,
      snapshot: blockToJson(fresh),
      note: '已暂缓，放回清单待安排（无痕，零心理负债）',
    );
  }

  /// 水流模型确定性重算（§7 六轮）：电量晚点选（低电量）触发的当日三级阻尼降档。
  /// 摘要中性呈现；受影响清单随返回体下发（可撤销通道挂账 memory 口径）。
  Future<CommandResult> _reflowDay(ReflowDayCommand cmd) async {
    final env = await _envelope();
    final date = cmd.date ?? isoDate(scheduleDayOf(DateTime.now(), env.wake));
    final energy = env.settings[SettingsKeys.todayEnergy] ?? 'normal';
    if (energy != 'low') {
      return CommandResult(op: cmd.op, note: '当前电量非低档，无需降档重算');
    }
    final parsedDate = tryParseIsoDate(date)!;
    final suspended =
        parseExceptions(env.settings).any((w) => w.covers(parsedDate));
    final fixed = suspended
        ? (onDate: const <FixedSlot>[], spillover: const <FixedSlot>[])
        : await _repo.fixedSlotsForDate(parsedDate);
    final blocks = await _repo.blocksOnDate(date);
    final occupied = <(int, int)>[
      for (final b in blocks)
        if (b.pinned || b.source == ScheduleBlock.sourceHuman)
          (b.startMin, b.startMin + spanMinutes(b.startMin, b.endMin)),
      for (final s in fixed.onDate)
        (s.startMin, crossesMidnight(s.startMin, s.endMin) ? 1440 : s.endMin),
      for (final s in fixed.spillover) (0, s.endMin),
    ];
    final available = freeMinutesIn(env.wake, env.sleepAdj, occupied);
    final budget = available * ScheduleRules.fillRateLowEnergyPercent ~/ 100;

    // 可降档的 AI 块（非 pinned、非庆祝、活跃态）；牺牲顺序=开始时间靠后者最先
    final plans = <String, Plan?>{};
    Plan? planOf(ScheduleBlock b) =>
        b.planId == null ? null : plans.putIfAbsent(b.planId!, () => null);
    final movable = <ScheduleBlock>[];
    for (final b in blocks) {
      if (b.source != ScheduleBlock.sourceAi || b.pinned || b.isCelebration) continue;
      if (b.status != ScheduleBlock.statusProposed &&
          b.status != ScheduleBlock.statusConfirmed) {
        continue;
      }
      movable.add(b);
      if (b.planId != null && !plans.containsKey(b.planId)) {
        plans[b.planId!] = await _repo.planById(b.planId!);
      }
    }
    movable.sort((a, b) => b.startMin.compareTo(a.startMin));

    var sparked = 0;
    var meltedCount = 0;
    final sparkedIds = <String>[];
    final meltedIds = <String>[];
    int totalOf(Iterable<ScheduleBlock> list) =>
        list.fold(0, (acc, b) => acc + spanMinutes(b.startMin, b.endMin));

    for (var i = 0; i < movable.length; i++) {
      final alive =
          movable.where((x) => x.status != ScheduleBlock.statusMelted).toList();
      if (totalOf(alive) <= budget) break;
      final b = movable[i];
      final plan = planOf(b);
      final span = spanMinutes(b.startMin, b.endMin);
      if (span > ScheduleRules.sparkMaxMinutes &&
          (plan?.minViableAction?.isNotEmpty ?? false)) {
        // 二级阻尼：压为火种（min_viable_action 核心动作，15 分钟刻度）
        final ok = await _repo.patchBlock(b.id!, {
          'end_min': _normEnd(b.startMin + ScheduleRules.sparkMaxMinutes),
          'execution_quality': ScheduleBlock.qualitySpark,
        });
        if (ok) {
          sparked++;
          sparkedIds.add(b.id!);
          movable[i] = _withSpan(b, ScheduleRules.sparkMaxMinutes);
        }
      } else {
        // 三级阻尼：溢出 melted 蒸发回池（非核心最先；火种靠后兜底）
        final ok = await _repo.patchBlock(b.id!, {'status': ScheduleBlock.statusMelted});
        if (ok) {
          meltedCount++;
          meltedIds.add(b.id!);
          movable[i] = _withSpan(b, 0, melted: true);
        }
      }
    }
    return CommandResult(
      op: cmd.op,
      data: {
        'date': date,
        'budget_minutes': budget,
        'sparked': sparkedIds,
        'melted': meltedIds,
      },
      note: '降档重算完成：$sparked 项切 5 分钟启动版，$meltedCount 项暂缓放回清单'
          '（检测到身体电量低，今日任务自动顺延，不产生任何惩罚）',
    );
  }

  ScheduleBlock _withSpan(ScheduleBlock b, int span, {bool melted = false}) =>
      ScheduleBlock(
        id: b.id,
        date: b.date,
        startMin: b.startMin,
        endMin: b.startMin + span,
        planId: b.planId,
        label: b.label,
        source: b.source,
        pinned: b.pinned,
        status: melted ? ScheduleBlock.statusMelted : b.status,
        postponeCount: b.postponeCount,
        executionQuality: ScheduleBlock.qualitySpark,
        isDaySpark: b.isDaySpark,
        isCelebration: b.isCelebration,
        createdAt: b.createdAt,
        updatedAt: b.updatedAt,
        version: b.version + 1,
      );

  Future<CommandResult> _confirmBlock(ConfirmBlockCommand cmd) async {
    final b = await _requireBlock(cmd.id);
    if (b.status != ScheduleBlock.statusProposed) {
      throw ActionException('只有 proposed 块可确认，当前 ${b.status}', code: ActionErrorCode.invalidRequest);
    }
    final ok = await _repo.patchBlock(cmd.id, {'status': ScheduleBlock.statusConfirmed},
        expectedVersion: cmd.expectedVersion);
    if (!ok) throw await _blockConflict(cmd.op, cmd.id);
    final fresh = await _requireBlock(cmd.id);
    return CommandResult(op: cmd.op, targetId: cmd.id, snapshot: blockToJson(fresh), note: '已确认');
  }

  Future<CommandResult> _rejectBlock(RejectBlockCommand cmd) async {
    final b = await _requireBlock(cmd.id);
    if (b.status != ScheduleBlock.statusProposed) {
      throw ActionException('只有 proposed 块可否决，当前 ${b.status}', code: ActionErrorCode.invalidRequest);
    }
    await _repo.deleteBlock(cmd.id);
    return CommandResult(
      op: cmd.op,
      targetId: cmd.id,
      note: cmd.reason == null ? '已否决，提案块已移除' : '已否决（原因：${cmd.reason}），plan 回落待安排',
    );
  }

  /// 改时间（校验刻度不对称 §3：human 可任性——重叠/越界橙色提示放行，不拒绝）。
  Future<CommandResult> _adjustBlockTime(AdjustBlockTimeCommand cmd) async {
    final b = await _requireBlock(cmd.id);
    final start = cmd.startMin ?? b.startMin;
    final end = cmd.endMin ?? b.endMin;
    if (start < 0 || start > 1439 || end < 1 || end > 1440) {
      throw ActionException('起止越界（start 0..1439 / end 1..1440）', code: ActionErrorCode.invalidRequest);
    }
    if (start == end) throw ActionException('零长块：start == end', code: ActionErrorCode.invalidRequest);
    final warnings = <String>[];
    final settings = await _repo.settingsAll();
    if (SettingsKeys.initialized(settings)) {
      final wake = SettingsKeys.intOf(settings, SettingsKeys.wakeTime)!;
      final sleep = SettingsKeys.intOf(settings, SettingsKeys.sleepTime)!;
      final sleepAdj = sleep <= wake ? sleep + 1440 : sleep;
      final endAbs = end <= start ? end + 1440 : end;
      if (start < wake || endAbs > sleepAdj) {
        warnings.add('越出作息边界（${clockOf(wake)}–${clockOf(sleepAdj % 1440)}）');
      }
    }
    final date = tryParseIsoDate(b.date);
    if (date != null) {
      final resolved = await _repo.fixedSlotsForDate(date);
      for (final s in resolved.onDate) {
        final p = _slotPortionOnDate(s, startedHere: true)!;
        if (overlapsMinutes(start, end, p.$1, p.$2)) warnings.add('与固定占用「${s.name}」重叠');
      }
      for (final other in await _repo.blocksOnDate(b.date)) {
        if (other.id == b.id) continue;
        if (other.pinned || other.source == ScheduleBlock.sourceHuman) {
          if (overlapsMinutes(start, end, other.startMin, other.endMin)) {
            warnings.add('与 ${other.source}${other.pinned ? '/pinned' : ''} 块 ${clockOf(other.startMin)}-${clockOf(other.endMin)} 重叠');
          }
        }
      }
    }
    final ok = await _repo.patchBlock(cmd.id, {'start_min': start, 'end_min': end},
        expectedVersion: cmd.expectedVersion);
    if (!ok) throw await _blockConflict(cmd.op, cmd.id);
    final fresh = await _requireBlock(cmd.id);
    return CommandResult(
      op: cmd.op,
      targetId: cmd.id,
      snapshot: blockToJson(fresh),
      note: warnings.isEmpty ? '已改时间' : '已改时间（${warnings.join('；')}，照常放行）',
    );
  }

  /// +30min 顺延微调（§10）：目标块后移，级联顺延被挤压的同日 AI 块；
  /// human/pinned 原地不动，撞上仅提示。顺延=移动原块保身份，postpone_count+1（§4）。
  Future<CommandResult> _shiftBlock(ShiftBlockCommand cmd) async {
    if (cmd.minutes <= 0) {
      throw ActionException('minutes 须为正分钟数', code: ActionErrorCode.invalidRequest);
    }
    final target = await _requireBlock(cmd.id);
    if (target.startMin + cmd.minutes >= 1440) {
      throw ActionException(
        '顺延越过午夜：M1 不自动跨日顺延',
        code: ActionErrorCode.invalidRequest,
        hint: '请走 adjust_block_time 改期，或由 AI 在次日重新 propose',
      );
    }
    var start = target.startMin + cmd.minutes;
    var end = _normEnd(target.endMin + cmd.minutes);
    final ok = await _repo.patchBlock(target.id!, {
      'start_min': start,
      'end_min': end,
      'postpone_count': target.postponeCount + 1,
    }, expectedVersion: cmd.expectedVersion);
    if (!ok) throw await _blockConflict(cmd.op, cmd.id);
    final shifted = <String>[target.id!];
    final warnings = <String>[];

    // 级联：被挤压的活跃 AI 块逐个后移；done/archived/melted 是终态不推
    for (var guard = 0; guard < 200; guard++) {
      final blocks = await _repo.blocksOnDate(target.date);
      ScheduleBlock? collider;
      for (final b in blocks) {
        if (b.id == null || shifted.contains(b.id)) continue;
        if (b.source != ScheduleBlock.sourceAi || b.pinned) continue;
        if (b.status == ScheduleBlock.statusDone ||
            b.status == ScheduleBlock.statusArchived ||
            b.status == ScheduleBlock.statusMelted) {
          continue;
        }
        if (overlapsMinutes(start, end, b.startMin, b.endMin)) {
          collider = b;
          break;
        }
      }
      if (collider == null) {
        // 无 AI 块可推：撞到 human/pinned/固定占用仅提示（human 块原地不动 §10）
        for (final b in blocks) {
          if (b.id != null && shifted.contains(b.id)) continue;
          if (b.pinned || b.source == ScheduleBlock.sourceHuman) {
            if (overlapsMinutes(start, end, b.startMin, b.endMin)) {
              warnings.add('与 ${b.source}${b.pinned ? '/pinned' : ''} 块 ${clockOf(b.startMin)}-${clockOf(b.endMin)} 重叠');
              break;
            }
          }
        }
        final date = tryParseIsoDate(target.date);
        if (date != null) {
          final resolved = await _repo.fixedSlotsForDate(date);
          for (final s in resolved.onDate) {
            final p = _slotPortionOnDate(s, startedHere: true)!;
            if (overlapsMinutes(start, end, p.$1, p.$2)) {
              warnings.add('与固定占用「${s.name}」重叠');
              break;
            }
          }
        }
        break;
      }
      final ns = collider.startMin + cmd.minutes;
      final ne = _normEnd(collider.endMin + cmd.minutes);
      if (ns >= 1440) {
        warnings.add('后续 AI 块 ${clockOf(collider.startMin)} 顺延将越过午夜，停在原地');
        break;
      }
      await _repo.patchBlock(collider.id!, {
        'start_min': ns,
        'end_min': ne,
        'postpone_count': collider.postponeCount + 1,
      });
      shifted.add(collider.id!);
      start = ns;
      end = ne;
    }

    final fresh = await _requireBlock(cmd.id);
    return CommandResult(
      op: cmd.op,
      targetId: cmd.id,
      snapshot: blockToJson(fresh),
      data: {'shifted': shifted},
      note: '已顺延 ${cmd.minutes} 分钟'
          '${shifted.length > 1 ? '，级联顺延 ${shifted.length - 1} 个后续 AI 块' : ''}'
          '${warnings.isEmpty ? '' : '；${warnings.join('；')}（仅提示）'}',
    );
  }

  Future<CommandResult> _tickBlock(TickBlockCommand cmd) async {
    final b = await _requireBlock(cmd.id);
    final wasMissed = b.status == ScheduleBlock.statusMissed;
    if (b.status != ScheduleBlock.statusProposed &&
        b.status != ScheduleBlock.statusConfirmed &&
        !wasMissed) {
      throw ActionException(
        '只有 proposed/confirmed 块可勾选（missed 可补勾），当前 ${b.status}',
        code: ActionErrorCode.invalidRequest,
        hint: 'archived/melted 是中性终态不可勾',
      );
    }
    final q = cmd.executionQuality;
    if (q != null && q != ScheduleBlock.qualityFull && q != ScheduleBlock.qualitySpark) {
      throw ActionException('execution_quality 非法：$q（full/spark）', code: ActionErrorCode.invalidRequest);
    }
    final ok = await _repo.patchBlock(cmd.id, {
      'status': ScheduleBlock.statusDone,
      'execution_quality': ?q,
    }, expectedVersion: cmd.expectedVersion);
    if (!ok) throw await _blockConflict(cmd.op, cmd.id);
    final fresh = await _requireBlock(cmd.id);
    return CommandResult(
      op: cmd.op,
      targetId: cmd.id,
      snapshot: blockToJson(fresh),
      note: wasMissed ? '已补勾（补记）' : (q == ScheduleBlock.qualitySpark ? '已勾选（5 分钟启动版，计入有效推进）' : '已勾选'),
    );
  }

  // ---- settings / fixed_slots ----

  Future<CommandResult> _updateSettings(UpdateSettingsCommand cmd) async {
    final toSet = <String, String>{};
    final toClear = <String>[];
    for (final e in cmd.values.entries) {
      if (!SettingsKeys.all.contains(e.key)) {
        throw ActionException(
          '未知设置键：${e.key}',
          code: ActionErrorCode.invalidRequest,
          hint: '合法键：${SettingsKeys.all.join(' / ')}',
        );
      }
      if (e.value == null) {
        toClear.add(e.key);
        continue;
      }
      switch (e.key) {
        case SettingsKeys.wakeTime || SettingsKeys.sleepTime:
          final v = _asInt(e.value);
          if (v == null || v < 0 || v > 1439) {
            throw ActionException('${e.key} 须为分钟数 0..1439', code: ActionErrorCode.invalidRequest);
          }
          toSet[e.key] = '$v';
        case SettingsKeys.minBlockMinutes || SettingsKeys.dailyNewBlocksLimit:
          final v = _asInt(e.value);
          if (v == null || v < 1) {
            throw ActionException('${e.key} 须为正整数', code: ActionErrorCode.invalidRequest);
          }
          toSet[e.key] = '$v';
        case SettingsKeys.fillRateLimit:
          final v = _asInt(e.value);
          if (v == null || v < 5 || v > 100) {
            throw ActionException(
              'fill_rate_limit 须为百分比 5..100（默认 60，§6「默认可调」）',
              code: ActionErrorCode.invalidRequest,
            );
          }
          toSet[e.key] = '$v';
        case SettingsKeys.todayEnergy:
          final v = e.value.toString();
          if (v != 'high' && v != 'normal' && v != 'low') {
            throw ActionException(
              'today_energy 须为 high/normal/low（三档手动点选，永不接传感器 §6）',
              code: ActionErrorCode.invalidRequest,
            );
          }
          toSet[e.key] = v;
        case SettingsKeys.userRules || SettingsKeys.weatherLocation:
          toSet[e.key] = e.value.toString();
        case SettingsKeys.themeMode:
          final v = e.value.toString();
          if (v != 'system' && v != 'light' && v != 'dark') {
            throw ActionException(
              'theme_mode 须为 system/light/dark（外观三档，ui-spec §0.3/§0.5）',
              code: ActionErrorCode.invalidRequest,
            );
          }
          toSet[e.key] = v;
        case SettingsKeys.exceptions:
          final decoded = e.value is String
              ? jsonDecode(e.value as String)
              : e.value;
          if (decoded is! List) {
            throw ActionException(
              'exceptions 须为 JSON 数组（[{start,end,label?}]，§13 旅行模式）',
              code: ActionErrorCode.invalidRequest,
            );
          }
          toSet[e.key] = jsonEncode(decoded);
      }
    }
    await _repo.settingsSet(toSet);
    for (final k in toClear) {
      await _repo.settingsClear(k);
    }
    final fresh = await _repo.settingsAll();
    return CommandResult(op: cmd.op, note: '设置已更新', data: {'settings': fresh});
  }

  Future<CommandResult> _updateFixedSlots(UpdateFixedSlotsCommand cmd) async {
    for (final s in cmd.slots) {
      if (s.name.trim().isEmpty) {
        throw ActionException('fixed_slot name 不能为空', code: ActionErrorCode.invalidRequest);
      }
      if (s.weekdays.isEmpty || s.weekdays.any((w) => w < 1 || w > 7)) {
        throw ActionException(
          '「${s.name}」weekdays 非法：须为 1..7 的 ISO 星期（1=周一）',
          code: ActionErrorCode.invalidRequest,
          hint: '工作日/周末两套模板 = 不同 weekday 集合的行并存（§4）',
        );
      }
      if (s.startMin < 0 || s.startMin > 1439 || s.endMin < 1 || s.endMin > 1440 || s.startMin == s.endMin) {
        throw ActionException(
          '「${s.name}」起止非法（start 0..1439 / end 1..1440 / 不可零长）',
          code: ActionErrorCode.invalidRequest,
        );
      }
    }
    await _repo.replaceFixedSlots(cmd.slots);
    final slots = await _repo.listFixedSlots();
    return CommandResult(
      op: cmd.op,
      data: {'slots': [for (final s in slots) slotToJson(s)]},
      note: '一周节奏已整单替换（${slots.length} 项）',
    );
  }

  // ---- 助手 ----

  /// 和平条款墙：human 块、pinned 块、已成现实的 AI 块（confirmed 及之后）。
  static bool _isWall(ScheduleBlock b) =>
      b.pinned ||
      b.source == ScheduleBlock.sourceHuman ||
      (b.source == ScheduleBlock.sourceAi && b.status != ScheduleBlock.statusProposed);

  /// 固定占用在目标日的投射区间：当天开始段（跨午夜截到 24:00）或前日溢出段 [0, end)。
  (int, int)? _slotPortionOnDate(FixedSlot s, {required bool startedHere}) {
    if (startedHere) {
      final end = crossesMidnight(s.startMin, s.endMin) ? 1440 : s.endMin;
      return (s.startMin, end);
    }
    return (0, s.endMin);
  }

  int _normEnd(int end) => end > 1440 ? end - 1440 : end;

  void _reject(List<Map<String, Object?>> acc, int index, String reason, {String? hint}) =>
      acc.add({'index': index, 'reason': reason, 'hint': ?hint});

  Future<Plan> _requirePlan(String id) async {
    final p = await _repo.planById(id);
    if (p == null) {
      throw ActionException(
        '计划不存在：id=$id',
        code: ActionErrorCode.notFound,
        hint: '先 list_plans 确认 id（默认只拉未归档计划）',
      );
    }
    return p;
  }

  Future<ScheduleBlock> _requireBlock(String id) async {
    final b = await _repo.blockById(id);
    if (b == null) {
      throw ActionException(
        '日程块不存在：id=$id',
        code: ActionErrorCode.notFound,
        hint: '先 get_schedule 确认块 id',
      );
    }
    return b;
  }

  void _validatePlanFields({String? energyLevel, String? toolRequired, String? deadline, int? estimate}) {
    if (energyLevel != null && energyLevel != Plan.energyDeep && energyLevel != Plan.energyLight) {
      throw ActionException('energy_level 非法：$energyLevel（deep/light）', code: ActionErrorCode.invalidRequest);
    }
    if (toolRequired != null && toolRequired != Plan.toolDesk && toolRequired != Plan.toolAnywhere) {
      throw ActionException('tool_required 非法：$toolRequired（desk/anywhere）', code: ActionErrorCode.invalidRequest);
    }
    _validateDeadline(deadline);
    if (estimate != null && estimate < 1) {
      throw ActionException('estimate 须为正分钟数', code: ActionErrorCode.invalidRequest);
    }
  }

  void _validateDeadline(String? deadline) {
    if (deadline != null && tryParseIsoDate(deadline) == null) {
      throw ActionException('deadline 非法：$deadline（须 yyyy-MM-dd）', code: ActionErrorCode.invalidRequest);
    }
  }

  int? _asInt(Object? v) => v is int ? v : (v is String ? int.tryParse(v) : null);

  ActionException _conflict(String op, Map<String, Object?>? latest) => ActionException(
        '版本冲突：目标已被另一个人或 AI 修改（op=$op）',
        code: ActionErrorCode.versionConflict,
        data: latest == null ? null : {'latest': latest},
        hint: '在返回的 latest 快照上合并你的修改，以其 version 作为 expected_version 重试（§6 返回契约）',
      );

  Future<ActionException> _blockConflict(String op, String id) async {
    final b = await _repo.blockById(id);
    return _conflict(op, b == null ? null : blockToJson(b));
  }
}
