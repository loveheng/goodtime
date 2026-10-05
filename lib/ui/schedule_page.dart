import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../action/command_handler.dart';
import '../action/commands.dart';
import '../action/queries.dart';
import '../action/rules.dart';
import '../app_services.dart';
import '../data/repository.dart';
import '../models/fixed_slot.dart';
import '../models/plan.dart';
import '../models/schedule_block.dart';
import '../theme/tokens.dart';
import '../util/schedule_day.dart';
import 'block_sheet.dart';

/// 今日页（ui-spec §3；M1 骨架 + M3 心理层）：双轨仪表盘（🔥今日核心×🛡️自由留白
/// ×安全线徽章）+ 分级确认卡（火种突出/Routine 折叠/两态收缩）+ 时间轴
/// （1dp=1min、fixed 背景带、派生保护区三级绘制过滤、确认三键、+30m、空槽创建）。
/// 遗留区/四形态/三手势按里程碑推进。
class SchedulePage extends StatefulWidget {
  const SchedulePage({super.key});

  @override
  State<SchedulePage> createState() => _SchedulePageState();
}

class _SchedulePageState extends State<SchedulePage> {
  Repository get _repo => AppServices.repo;

  /// 顶部「日｜周｜月」三段切换（§4，2026-10-06 拍板）
  String _viewMode = 'day'; // day | week | month

  /// 日视图选中日期（日期参数化：可从周/月跳入任意一天回看历史）
  late DateTime _selected = scheduleDayOf(DateTime.now(), _wakeGuess);

  int get _wakeGuess => 420;

  Future<DayData> _load() async {
    final queries = ScheduleQueries(_repo);
    final settings = await queries.getSettings();
    final wake = settings['wake_time'] as int? ?? 420;
    final sleep = settings['sleep_time'] as int? ?? 1380;
    final sleepAdj = sleep <= wake ? sleep + 1440 : sleep;
    final today = scheduleDayOf(DateTime.now(), wake);
    // 选中日期未显式设置过时跟随作息日「今天」
    if (_selected == scheduleDayOf(DateTime.now(), _wakeGuess)) {
      _selected = today;
    }
    final selected = _selected;
    final iso = isoDate(selected);
    final blocks = await _repo.blocksOnDate(iso);
    final resolved = await _repo.fixedSlotsForDate(selected);
    final planIds = {for (final b in blocks) if (b.planId != null) b.planId!};
    final plans = <String, Plan?>{
      for (final id in planIds) id: await _repo.planById(id),
    };
    final cleanSlate = settings['initialized'] == true
        ? await queries.isBreakdown()
        : false;
    // 例外日（假期与出行）：ISO 日期字符串直接字典序比较
    final exceptions = settings['exceptions'] as List<dynamic>;
    final roam = exceptions.any((e) =>
        e is Map && e['start'] is String && e['end'] is String &&
        (e['start'] as String).compareTo(iso) <= 0 &&
        (e['end'] as String).compareTo(iso) >= 0);
    return DayData(
      wake: wake,
      sleepAdj: sleepAdj,
      today: selected,
      isToday: iso == isoDate(today),
      blocks: blocks,
      fixedOnDate: resolved.onDate,
      fixedSpillover: resolved.spillover,
      plans: plans,
      settings: settings,
      cleanSlate: cleanSlate,
      energy: settings['today_energy'] as String? ?? 'normal',
      roam: roam,
    );
  }

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: _repo,
      builder: (context, _) => Column(
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 4, 16, 0),
            child: SegmentedButton<String>(
              segments: const [
                ButtonSegment(value: 'day', label: Text('日')),
                ButtonSegment(value: 'week', label: Text('周')),
                ButtonSegment(value: 'month', label: Text('月')),
              ],
              selected: {_viewMode},
              onSelectionChanged: (s) => setState(() => _viewMode = s.first),
            ),
          ),
          Expanded(
            child: switch (_viewMode) {
              'week' => _WeekView(
                  anchor: _selected,
                  onPick: (date) => setState(() {
                    _selected = date;
                    _viewMode = 'day';
                  }),
                  onWeekChange: (weeks) => setState(
                      () => _selected = addDays(_selected, weeks * 7)),
                ),
              'month' => _MonthView(
                  anchor: _selected,
                  onPick: (date) => setState(() {
                    _selected = date;
                    _viewMode = 'day';
                  }),
                  onMonthChange: (m) => setState(() => _selected = m),
                ),
              _ => FutureBuilder<DayData>(
                  future: _load(),
                  builder: (context, snap) {
                    if (snap.connectionState != ConnectionState.done) {
                      return const Center(child: CircularProgressIndicator());
                    }
                    final data = snap.data;
                    if (data == null) return const SizedBox.shrink();
                    return _DayView(
                      data: data,
                      onPickToday: _backToToday,
                      onPrevDay: () => _shiftDay(-1),
                      onNextDay: () => _shiftDay(1),
                    );
                  },
                ),
            },
          ),
        ],
      ),
    );
  }

  void _backToToday() => setState(() =>
      _selected = scheduleDayOf(DateTime.now(), _wakeGuess));

  void _shiftDay(int delta) =>
      setState(() => _selected = addDays(_selected, delta));
}

class DayData {
  DayData({
    required this.wake,
    required this.sleepAdj,
    required this.today,
    required this.blocks,
    required this.fixedOnDate,
    required this.fixedSpillover,
    required this.plans,
    required this.settings,
    required this.cleanSlate,
    this.isToday = true,
    this.energy = 'normal',
    this.roam = false,
  });

  final int wake;
  final int sleepAdj; // 聚焦窗终点（睡眠跨午夜时 >1440）
  final DateTime today;
  final List<ScheduleBlock> blocks;
  final List<FixedSlot> fixedOnDate;
  final List<FixedSlot> fixedSpillover;
  final Map<String, Plan?> plans;
  final Map<String, Object?> settings;
  final bool cleanSlate;

  /// 选中日是否为作息日「今天」（回看历史日时隐藏提案卡等今日专属件）。
  final bool isToday;

  /// 今日生理电量（四形态渲染：deep 攻坚态仅在非低电量日成立，§6.1）。
  final String energy;

  /// 例外日（假期与出行）→ 漫游主题态触发之一（§6.1 十四轮）。
  final bool roam;

  /// 当日黄金火种块（今日核心，§0.4）。
  ScheduleBlock? get spark {
    for (final b in blocks) {
      if (b.isDaySpark && b.status != ScheduleBlock.statusMelted) return b;
    }
    return null;
  }

  /// AI 未确认提案（分级确认卡的观众）。
  List<ScheduleBlock> get proposedAi => [
        for (final b in blocks)
          if (b.source == ScheduleBlock.sourceAi &&
              b.status == ScheduleBlock.statusProposed)
            b,
      ];

  /// 安全线：火种 done（含 spark 打折完成）即「今日底线守住」（§10 八轮）。
  bool get safelineLit =>
      blocks.any((b) => b.isDaySpark && b.status == ScheduleBlock.statusDone);

  /// 可见块（melted 不渲染 §6.2；archived 淡出仍占位）。
  List<ScheduleBlock> get visibleBlocks =>
      blocks.where((b) => b.status != ScheduleBlock.statusMelted).toList();

  /// 占用区间（全部可见块 + 固定占用投射），派生保护区/自由留白共用。
  List<(int, int)> occupiedIntervals() {
    final out = <(int, int)>[
      for (final b in visibleBlocks)
        (b.startMin, b.startMin + spanMinutes(b.startMin, b.endMin)),
      for (final s in fixedOnDate)
        (s.startMin, crossesMidnight(s.startMin, s.endMin) ? 1440 : s.endMin),
      for (final s in fixedSpillover) (0, s.endMin),
    ];
    return out;
  }
}

class _DayView extends StatelessWidget {
  const _DayView({
    required this.data,
    required this.onPickToday,
    required this.onPrevDay,
    required this.onNextDay,
  });

  final DayData data;
  final VoidCallback onPickToday;
  final VoidCallback onPrevDay;
  final VoidCallback onNextDay;

  @override
  Widget build(BuildContext context) {
    final proposed = data.proposedAi;
    // 放工守卫（§3.7 八轮）：sleep 前 2h 转静默视图；快记条不关门（app_shell 常驻）
    final nowMin = minutesOfDay(DateTime.now());
    final windDown =
        data.isToday && nowMin >= data.sleepAdj - 120 && nowMin < data.sleepAdj;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 8, 16, 0),
          child: Row(
            children: [
              Text(_headerTitle(), style: Theme.of(context).textTheme.titleMedium),
              const SizedBox(width: 8),
              if (data.isToday && data.spark != null)
                Text('🔥 今日核心 1 项',
                    style: Theme.of(context)
                        .textTheme
                        .bodySmall
                        ?.copyWith(color: Theme.of(context).colorScheme.primary)),
              const Spacer(),
              TextButton(
                onPressed: onPrevDay,
                child: const Text('‹ 前一天', style: TextStyle(fontSize: 12)),
              ),
              TextButton(
                onPressed: onNextDay,
                child: const Text('后一天 ›', style: TextStyle(fontSize: 12)),
              ),
            ],
          ),
        ),
        if (!data.isToday)
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 0, 16, 4),
            child: Align(
              alignment: Alignment.centerLeft,
              child: TextButton(
                onPressed: onPickToday,
                child: const Text('回到今天', style: TextStyle(fontSize: 12)),
              ),
            ),
          ),
        // 双轨仪表盘（§3.1/§10 八轮）：推进任务 × 保护时长并列核心交付指标
        _Dashboard(data: data),
        if (windDown)
          _WindDownBanner(data: data)
        else if (data.cleanSlate && data.isToday)
          const _CleanSlateBanner()
        else if (data.isToday && proposed.isNotEmpty)
          _TieredConfirmCard(proposed: proposed, data: data),
        Expanded(
          child: data.visibleBlocks.isEmpty
              ? _EmptyState(data: data)
              : _Timeline(data: data),
        ),
      ],
    );
  }

  String _headerTitle() {
    final weekdays = ['周一', '周二', '周三', '周四', '周五', '周六', '周日'];
    final d = data.today;
    final label = data.isToday ? '今天' : '日程';
    return '$label · ${d.month}月${d.day}日 ${weekdays[d.weekday - 1]}';
  }
}

/// 双轨仪表盘（§3.1）：🔥 今日核心 N 项 ｜ 🛡️ 自由留白 X.X h ｜ 🏅 安全线徽章。
/// 保护时长与推进任务并列为核心交付指标；徽章点亮=「今日底线守住啦」。
class _Dashboard extends StatelessWidget {
  const _Dashboard({required this.data});

  final DayData data;

  Future<void> _setEnergy(BuildContext context, String level) async {
    await CommandHandler(AppServices.repo)
        .execute(UpdateSettingsCommand(values: {'today_energy': level}));
    // 电量晚点选（先有提案/确认后点低电量）→ 复用水流模型确定性重算当日（§7 十轮）
    if (level == 'low') {
      final r = await CommandHandler(AppServices.repo).execute(ReflowDayCommand());
      if (context.mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text(r.note ?? '已降档'), duration: const Duration(seconds: 3)),
        );
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final sparkCount =
        data.blocks.where((b) => b.isDaySpark && b.status != ScheduleBlock.statusMelted).length;
    final free = freeMinutesIn(
        data.wake, data.sleepAdj, data.occupiedIntervals());
    final lit = data.safelineLit;
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 6, 16, 6),
      child: Row(
        children: [
          Text('🔥 今日核心 $sparkCount 项',
              style: Theme.of(context).textTheme.bodySmall),
          const SizedBox(width: 12),
          Text('🛡️ 自由留白 ${(free / 60).toStringAsFixed(1)}h',
              style: Theme.of(context).textTheme.bodySmall),
          const Spacer(),
          // 电量三档（§6 十轮：手动点选、永不接传感器；低电量触发当日水流降档重算）
          _EnergyToggle(current: data.settings['today_energy'] as String? ?? 'normal',
              onSelect: (l) => _setEnergy(context, l)),
          const SizedBox(width: 8),
          Icon(Icons.emoji_events,
              size: 16, color: lit ? StColors.safelineOn : StColors.safelineOff),
          const SizedBox(width: 4),
          Text(
            lit ? '今日底线守住啦' : '底线待守',
            style: Theme.of(context).textTheme.labelSmall?.copyWith(
                color: lit ? StColors.safelineOn : StColors.safelineOff),
          ),
        ],
      ),
    );
  }
}

/// 晨间生理电量三档（⚡满血/🔋平稳/🪫低电量）——手动点选、日切重置（十轮拍板）。
class _EnergyToggle extends StatelessWidget {
  const _EnergyToggle({required this.current, required this.onSelect});

  final String current;
  final void Function(String level) onSelect;

  @override
  Widget build(BuildContext context) {
    Widget tier(String level, String icon, String tip) => IconButton(
          icon: Text(icon, style: const TextStyle(fontSize: 14)),
          tooltip: tip,
          visualDensity: VisualDensity.compact,
          color: current == level ? Theme.of(context).colorScheme.primary : null,
          onPressed: current == level ? null : () => onSelect(level),
        );
    return Row(mainAxisSize: MainAxisSize.min, children: [
      tier('high', '⚡', '满血'),
      tier('normal', '🔋', '平稳'),
      tier('low', '🪫', '低电量（自动降档）'),
    ]);
  }
}

/// 放工守卫静默视图（§3.7 八轮）：sleep 前 2h 隐藏推进提示，转完成面陈述；
/// 快记条永不关门（例外条款，app_shell 常驻不撤）。
class _WindDownBanner extends StatelessWidget {
  const _WindDownBanner({required this.data});

  final DayData data;

  @override
  Widget build(BuildContext context) {
    final done = data.blocks
        .where((b) => b.status == ScheduleBlock.statusDone)
        .toList();
    final minutes =
        done.fold(0, (acc, b) => acc + spanMinutes(b.startMin, b.endMin));
    return Container(
      margin: const EdgeInsets.fromLTRB(16, 4, 16, 4),
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: StColors.freeFlowBg,
        borderRadius: BorderRadius.circular(StScale.radiusCard),
      ),
      child: Text(
        '放工了：今天完成 ${done.length} 项 / $minutes 分钟。'
        '明天的安排已备好，快记条不打烊，想到什么随时记。',
        style: Theme.of(context).textTheme.bodySmall,
      ),
    );
  }
}

/// Clean Slate 静默视图条（§3.7/§9，界面文案「轻装重启」，词汇表 §0.4）。
class _CleanSlateBanner extends StatelessWidget {
  const _CleanSlateBanner();

  @override
  Widget build(BuildContext context) {
    return Container(
      margin: const EdgeInsets.fromLTRB(16, 4, 16, 4),
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: Theme.of(context).colorScheme.surfaceContainerHighest,
        borderRadius: BorderRadius.circular(StScale.radiusCard),
      ),
      child: Text(
        '轻装重启：生活有时需要喘息，过去的事已安全归档，今天从一件小事开始。',
        style: Theme.of(context).textTheme.bodySmall,
      ),
    );
  }
}

/// 分级确认卡（§3.4 拍板：页内 sticky 卡片 + 两态收缩，防批量盲签）：
/// 火种突出（为什么排在这），其余折叠为「其余 N 项日常推进」；
/// 收缩为 44dp 吸顶胶囊，保留一键确认快速通道。
class _TieredConfirmCard extends StatefulWidget {
  const _TieredConfirmCard({required this.proposed, required this.data});

  final List<ScheduleBlock> proposed;
  final DayData data;

  @override
  State<_TieredConfirmCard> createState() => _TieredConfirmCardState();
}

class _TieredConfirmCardState extends State<_TieredConfirmCard> {
  bool _expanded = true;
  bool _busy = false;

  ScheduleBlock? get _spark {
    for (final b in widget.proposed) {
      if (b.isDaySpark) return b;
    }
    return null;
  }

  List<ScheduleBlock> get _routine =>
      widget.proposed.where((b) => !b.isDaySpark).toList();

  Future<void> _act(bool confirm) async {
    if (_busy) return;
    setState(() => _busy = true);
    try {
      final handler = CommandHandler(AppServices.repo);
      for (final b in widget.proposed) {
        if (confirm) {
          await handler.execute(
              ConfirmBlockCommand(b.id!, expectedVersion: b.version));
        } else {
          await handler.execute(RejectBlockCommand(b.id!));
        }
      }
    } on ActionException {
      // 单块冲突不中断批量；失败块留在提案态，用户逐块处理（点击块浮层）
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final spark = _spark;
    final others = _routine;
    if (!_expanded) {
      return Padding(
        padding: const EdgeInsets.fromLTRB(16, 4, 16, 4),
        child: GestureDetector(
          onTap: () => setState(() => _expanded = true),
          child: Container(
            height: StScale.blockMinHeightDp,
            padding: const EdgeInsets.symmetric(horizontal: 12),
            decoration: BoxDecoration(
              color: scheme.primaryContainer,
              borderRadius: BorderRadius.circular(StScale.radiusCapsule),
            ),
            alignment: Alignment.centerLeft,
            child: Row(
              children: [
                Expanded(
                  child: Text(
                    '⚡ AI 排程建议：${spark == null ? '' : '重点攻坚 1 项，'}顺带推进 ${others.length} 项 · 点击查看理由',
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(fontSize: 12, color: scheme.onPrimaryContainer),
                  ),
                ),
                TextButton(
                  onPressed: _busy ? null : () => _act(true),
                  child: const Text('全部确认'),
                ),
              ],
            ),
          ),
        ),
      );
    }
    return Card(
      margin: const EdgeInsets.fromLTRB(16, 4, 16, 4),
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            if (spark != null) ...[
              Row(children: [
                Text('🔥 今日核心',
                    style: Theme.of(context)
                        .textTheme
                        .titleSmall
                        ?.copyWith(color: scheme.primary)),
                const Spacer(),
                IconButton(
                  icon: const Icon(Icons.keyboard_arrow_up, size: 20),
                  tooltip: '收起',
                  onPressed: () => setState(() => _expanded = false),
                ),
              ]),
              Text('「${_labelOf(spark)}」 ${clockOf(spark.startMin)}–${clockOf(spark.endMin)}',
                  style: Theme.of(context).textTheme.bodyMedium),
              const SizedBox(height: 4),
              Text('这是今天最重要的一件事，其余都为它让路。',
                  style: Theme.of(context)
                      .textTheme
                      .bodySmall
                      ?.copyWith(color: StColors.textSecondary)),
            ] else
              Row(children: [
                Text('AI 排程建议 · ${widget.proposed.length} 项',
                    style: Theme.of(context).textTheme.titleSmall),
                const Spacer(),
                IconButton(
                  icon: const Icon(Icons.keyboard_arrow_up, size: 20),
                  tooltip: '收起',
                  onPressed: () => setState(() => _expanded = false),
                ),
              ]),
            if (others.isNotEmpty) ...[
              const SizedBox(height: 8),
              Text('其余 ${others.length} 项日常推进',
                  style: Theme.of(context)
                      .textTheme
                      .labelMedium
                      ?.copyWith(color: StColors.textSecondary)),
            ],
            const SizedBox(height: 8),
            Row(
              children: [
                FilledButton(
                  onPressed: _busy ? null : () => _act(true),
                  child: const Text('全部确认'),
                ),
                const SizedBox(width: 8),
                OutlinedButton(
                  onPressed: _busy ? null : () => _act(false),
                  child: const Text('全部否决'),
                ),
                const SizedBox(width: 8),
                Text('逐块调整：点时间轴上的提案块',
                    style: Theme.of(context)
                        .textTheme
                        .labelSmall
                        ?.copyWith(color: StColors.textSecondary)),
              ],
            ),
          ],
        ),
      ),
    );
  }

  String _labelOf(ScheduleBlock b) =>
      b.label ?? (b.planId != null ? widget.data.plans[b.planId]?.title ?? '(计划)' : '(未命名)');
}

/// 空态（§9）：留白的一天不制造焦虑，给 AI 入口也给手动创建。
class _EmptyState extends StatelessWidget {
  const _EmptyState({required this.data});

  final DayData data;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          Text(
            data.isToday ? '留白的一天，自由支配时间已备好。' : '这一天没有日程块。',
            style: Theme.of(context).textTheme.bodyMedium),
          if (data.isToday) ...[
            const SizedBox(height: 8),
            Text('想让 AI 排一排？打开桌面 AI 说「帮我排今天」',
                style: Theme.of(context).textTheme.bodySmall?.copyWith(color: StColors.textSecondary)),
            const SizedBox(height: 16),
            FilledButton.tonal(
              onPressed: () => showNewBlockSheet(context, data),
              child: const Text('自己放一件事进来'),
            ),
          ],
        ],
      ),
    );
  }
}

class _Timeline extends StatelessWidget {
  const _Timeline({required this.data});

  final DayData data;

  static const _gutterWidth = 52.0;
  double get _windowMinutes => (data.sleepAdj - data.wake).toDouble();
  double get _windowHeight => _windowMinutes * StScale.dpPerMinute;

  double _topOf(int minutes) =>
      (minutes - data.wake).clamp(0, _windowMinutes) * StScale.dpPerMinute;

  double _heightOf(int startMin, int endMin) {
    final h = spanMinutes(startMin, endMin) * StScale.dpPerMinute;
    return h < StScale.blockMinHeightDp ? StScale.blockMinHeightDp : h;
  }

  @override
  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(builder: (context, constraints) {
      final timelineWidth = constraints.maxWidth - _gutterWidth - 16;
      return Column(
        children: [
          Expanded(
            child: SingleChildScrollView(
              child: Padding(
                padding: const EdgeInsets.fromLTRB(8, 0, 8, 24),
                child: SizedBox(
                  height: _windowHeight,
                  child: GestureDetector(
                    behavior: HitTestBehavior.opaque,
                    onTapUp: (d) => _onBackgroundTap(context, d),
                    child: Stack(
                      clipBehavior: Clip.none,
                      children: [
                        for (var m = (data.wake ~/ 60 + 1) * 60; m < data.sleepAdj; m += 60)
                          Positioned(
                            left: 0,
                            right: 0,
                            top: _topOf(m),
                            child: Row(
                              children: [
                                SizedBox(
                                  width: _gutterWidth,
                                  child: Text(clockOf(m),
                                      style: Theme.of(context)
                                          .textTheme
                                          .labelSmall
                                          ?.copyWith(color: StColors.textSecondary)),
                                ),
                                const Expanded(child: Divider(height: 1)),
                              ],
                            ),
                          ),
                        // 派生保护区（§3.1/§6.5）：补给带 + 自由流动区，三级绘制过滤
                        for (final band in _protectedBands())
                          Positioned(
                            left: _gutterWidth,
                            right: 0,
                            top: _topOf(band.start),
                            child: _ProtectedBand(band: band),
                          ),
                        // 固定占用背景带（当天开始段 + 前夜溢出段）
                        for (final band in _fixedBands())
                          Positioned(
                            left: _gutterWidth,
                            right: 0,
                            top: _topOf(band.$1),
                            child: Container(
                              height: _heightOf(band.$1, band.$2),
                              decoration: BoxDecoration(
                                color: StColors.fixedSlotBg,
                                borderRadius:
                                    BorderRadius.circular(StScale.radiusBlock),
                              ),
                              alignment: Alignment.centerLeft,
                              padding: const EdgeInsets.only(left: 8),
                              child: Text(band.$3,
                                  style: Theme.of(context)
                                      .textTheme
                                      .labelSmall
                                      ?.copyWith(color: StColors.textSecondary)),
                            ),
                          ),
                        // 块（重叠按车道分栏，§6.5）
                        for (final item in _layoutLanes(timelineWidth))
                          Positioned(
                            left: _gutterWidth + item.lane * item.laneWidth,
                            width: item.laneWidth - 2,
                            top: _topOf(item.block.startMin),
                            height:
                                _heightOf(item.block.startMin, item.block.endMin),
                            child: _BlockCard(
                              block: item.block,
                              plan: item.block.planId == null
                                  ? null
                                  : data.plans[item.block.planId!],
                              data: data,
                              onTap: () => showBlockSheet(context, item.block),
                            ),
                          ),
                      ],
                    ),
                  ),
                ),
              ),
            ),
          ),
        ],
      );
    });
  }

  /// 固定占用在当日聚焦窗内的投射段：(start, end, name)。
  List<(int, int, String)> _fixedBands() {
    final bands = <(int, int, String)>[];
    for (final s in data.fixedOnDate) {
      final end = crossesMidnight(s.startMin, s.endMin) ? 1440 : s.endMin;
      if (end > data.wake) {
        bands.add((s.startMin < data.wake ? data.wake : s.startMin, end, s.name));
      }
    }
    for (final s in data.fixedSpillover) {
      if (s.endMin > data.wake) bands.add((data.wake, s.endMin, s.name));
    }
    return bands;
  }

  /// 派生保护区（§3.1 护航呈现，派生渲染不入库 §11）：
  /// 🛡️ 留白缓冲 = AI 长块（>45min）后的 15min 呼吸位；🍃 自由流动区 = 其余空隙。
  /// 三级绘制过滤（§6.5）：≥30min 全渲染 / 15–30min 仅图标 / <15min 静默。
  List<_ProtectedBandData> _protectedBands() {
    final occupied = data.occupiedIntervals();
    final supply = <(int, int)>[];
    for (final b in data.visibleBlocks) {
      if (b.source != ScheduleBlock.sourceAi) continue;
      final span = spanMinutes(b.startMin, b.endMin);
      if (span <= ScheduleRules.breathMin) continue;
      final s = b.startMin + span;
      supply.add((s, s + ScheduleRules.breathBufferMin));
    }
    final out = <_ProtectedBandData>[];
    // 补给带：对占用取差集后渲染（现实挤进缓冲时只渲染自由部分）
    for (final g in gapsIn(data.wake, data.sleepAdj, occupied)) {
      for (final s in supply) {
        final lo = s.$1 > g.$1 ? s.$1 : g.$1;
        final hi = s.$2 < g.$2 ? s.$2 : g.$2;
        if (hi > lo) out.add(_ProtectedBandData(lo, hi, supply: true));
      }
    }
    // 自由流动区：占用 + 补给带之外的空隙
    final everything = [...occupied, ...supply];
    for (final g in gapsIn(data.wake, data.sleepAdj, everything)) {
      out.add(_ProtectedBandData(g.$1, g.$2, supply: false));
    }
    return out;
  }

  /// 车道分配：按开始时间排序，重叠簇内 greedy 分列（§6.5 重叠分栏的 M1 泛化）。
  List<({ScheduleBlock block, int lane, double laneWidth})> _layoutLanes(
      double timelineWidth) {
    final sorted = [...data.visibleBlocks]
      ..sort((a, b) => a.startMin.compareTo(b.startMin));
    final out = <({ScheduleBlock block, int lane, double laneWidth})>[];
    var cluster = <ScheduleBlock>[];
    var clusterEnd = -1;
    void flush() {
      if (cluster.isEmpty) return;
      final lanes = _assignLanes(cluster);
      var maxLane = 1;
      for (final v in lanes.values) {
        if (v + 1 > maxLane) maxLane = v + 1;
      }
      final width = timelineWidth / maxLane;
      for (final b in cluster) {
        out.add((block: b, lane: lanes[b.id!] ?? 0, laneWidth: width));
      }
      cluster = [];
      clusterEnd = -1;
    }

    for (final b in sorted) {
      if (cluster.isNotEmpty && b.startMin >= clusterEnd) flush();
      cluster.add(b);
      final endAbs = b.endMin <= b.startMin ? b.endMin + 1440 : b.endMin;
      if (endAbs > clusterEnd) clusterEnd = endAbs;
    }
    flush();
    return out;
  }

  /// 簇内 greedy 车道：块排进第一条「上一块结束 ≤ 本块开始」的车道。
  Map<String, int> _assignLanes(List<ScheduleBlock> cluster) {
    final laneEnds = <int>[];
    final lanes = <String, int>{};
    for (final b in cluster) {
      final endAbs = b.endMin <= b.startMin ? b.endMin + 1440 : b.endMin;
      var lane = 0;
      while (lane < laneEnds.length && laneEnds[lane] > b.startMin) {
        lane++;
      }
      if (lane == laneEnds.length) {
        laneEnds.add(endAbs);
      } else {
        laneEnds[lane] = endAbs;
      }
      lanes[b.id!] = lane;
    }
    return lanes;
  }

  void _onBackgroundTap(BuildContext context, TapUpDetails d) {
    final minute = data.wake + (d.localPosition.dy / StScale.dpPerMinute).round();
    final snapped = (minute ~/ 15 * 15).clamp(data.wake, data.sleepAdj - 15);
    // 过去时段 → 逆向记账「刚才做了什么」（§10 事实通道）；未来时段 → 新建
    final past = data.isToday && snapped < minutesOfDay(DateTime.now());
    showNewBlockSheet(context, data, slotStart: snapped, past: past);
  }
}

/// 派生保护区数据与渲染（§6.5 三级过滤：≥30 全渲染 / 15–30 仅图标 / <15 静默）。
class _ProtectedBandData {
  const _ProtectedBandData(this.start, this.end, {required this.supply});
  final int start;
  final int end;
  final bool supply; // true=🛡️ 留白缓冲 / false=🍃 自由流动区
}

class _ProtectedBand extends StatelessWidget {
  const _ProtectedBand({required this.band});

  final _ProtectedBandData band;

  @override
  Widget build(BuildContext context) {
    final minutes = band.end - band.start;
    if (minutes < StGesture.bandIconMin) return const SizedBox.shrink();
    final full = minutes >= StGesture.bandFullMin;
    final label = band.supply ? '🛡️ 留白缓冲' : '🍃 自由流动区';
    return Container(
      height: minutes * StScale.dpPerMinute,
      decoration: BoxDecoration(
        color: band.supply ? StColors.supplyBandBg : StColors.freeFlowBg,
        border: band.supply
            ? Border.all(color: StColors.supplyBandStroke, width: 0.6)
            : null,
        borderRadius: BorderRadius.circular(StScale.radiusBlock),
      ),
      alignment: Alignment.centerLeft,
      padding: const EdgeInsets.only(left: 8),
      child: full
          ? Text('$label $minutes 分钟',
              style: Theme.of(context)
                  .textTheme
                  .labelSmall
                  ?.copyWith(color: StColors.textSecondary))
          : Text(label == '🛡️ 留白缓冲' ? '🛡️' : '🍃',
              style: TextStyle(fontSize: 12)),
    );
  }
}

/// 单个块卡片（ui-spec §6.1 四形态 + §6.2 状态叠加 + §6.3 来源角标）
/// 外挂三手势（§6.4/§6.5：长按坍缩/左滑换乘/右滑融化 + 手势锁 + 卡点 + 触觉）。
class _BlockCard extends StatelessWidget {
  const _BlockCard({required this.block, required this.plan, required this.data, required this.onTap});

  final ScheduleBlock block;
  final Plan? plan;
  final DayData data;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final gesture = _GestureBlock(block: block, child: _card(context));
    return gesture;
  }

  Widget _card(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final isProposed = block.status == ScheduleBlock.statusProposed;
    final isDone = block.status == ScheduleBlock.statusDone;
    final isArchived = block.status == ScheduleBlock.statusArchived;
    final isHuman = block.source == ScheduleBlock.sourceHuman;
    final span = spanMinutes(block.startMin, block.endMin);
    // 四形态（§6.1 十四轮）：庆祝 > 火种胶囊 > 漫游 > 深度攻坚 > 默认
    final isSpark = block.executionQuality == ScheduleBlock.qualitySpark;
    final isDeep = !isHuman &&
        plan?.energyLevel == Plan.energyDeep &&
        data.energy != 'low' &&
        !isSpark;
    final isRoam = data.roam || span >= 180;

    Color bg;
    Color? border;
    Color textColor;
    if (block.isCelebration) {
      bg = StColors.celebrationBg;
      border = StColors.celebrationStroke;
      textColor = StColors.textPrimary;
    } else if (isSpark) {
      bg = StColors.sparkBg;
      border = StColors.sparkStroke;
      textColor = StColors.textPrimary;
    } else if (isDeep) {
      bg = StColors.deepFocusBg;
      textColor = StColors.deepFocusText;
    } else if (isRoam) {
      bg = StColors.roamBg;
      border = StColors.roamStroke;
      textColor = StColors.textPrimary;
    } else if (isHuman) {
      bg = StColors.humanBlockBg;
      textColor = StColors.textPrimary;
    } else {
      bg = scheme.primaryContainer;
      textColor = scheme.onPrimaryContainer;
    }

    // 文本：火种态切 min_viable_action（§6.1）；漫游起止以 ~ 模糊标记（仅渲染层）
    final label = isSpark
        ? (plan?.minViableAction ?? block.label ?? plan?.title ?? '(未命名)')
        : (block.label ?? plan?.title ?? '(未命名)');
    final timePrefix =
        isRoam ? '${clockOf(block.startMin)}~${clockOf(block.endMin)} ' : '${clockOf(block.startMin)} ';

    return Opacity(
      key: ValueKey('block-${block.id}'),
      opacity: isArchived ? 0.15 : 1,
      child: InkWell(
        onTap: isArchived ? null : onTap,
        borderRadius: BorderRadius.circular(StScale.radiusBlock),
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
          decoration: BoxDecoration(
            color: bg,
            borderRadius: BorderRadius.circular(StScale.radiusBlock),
            border: Border.all(
              color: border ??
                  (isProposed ? scheme.primary : Colors.transparent),
              width: isProposed || border != null ? 1.2 : 0,
            ),
          ),
          child: Stack(
            children: [
              Center(
                child: Text(
                  '$timePrefix$label',
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    fontSize: 12,
                    color: textColor,
                    decoration: block.status == ScheduleBlock.statusSkipped
                        ? TextDecoration.lineThrough
                        : null,
                  ),
                ),
              ),
              if (isDeep)
                Positioned(
                  right: 2,
                  bottom: 1,
                  child: Text('[+15m 呼吸留白]',
                      style: TextStyle(
                          fontSize: 8,
                          height: 1.1,
                          color: StColors.deepFocusText.withValues(alpha: 0.8))),
                ),
              if (isProposed)
                Positioned(
                  right: 0,
                  top: 0,
                  child: _chip(context, '提案', scheme.primary),
                ),
              if (block.isCelebration)
                Positioned(
                  right: isProposed ? 40 : 0,
                  top: 0,
                  child: const Text('🎂', style: TextStyle(fontSize: 10)),
                ),
              if (block.pinned)
                Positioned(
                  right: isProposed ? 40 : (block.isCelebration ? 16 : 0),
                  top: 0,
                  child: const Text('📌', style: TextStyle(fontSize: 10)),
                ),
              if (block.planId != null && !block.pinned)
                Positioned(
                  left: 0,
                  top: 0,
                  child: Text('📋',
                      style: TextStyle(fontSize: 10, color: textColor)),
                ),
              if (isDone)
                Positioned(
                  right: 0,
                  bottom: 0,
                  child: Icon(Icons.check_circle, size: 14, color: scheme.primary),
                ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _chip(BuildContext context, String text, Color color) => Container(
        padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 1),
        decoration: BoxDecoration(
          color: color,
          borderRadius: BorderRadius.circular(StScale.radiusCapsule),
        ),
        child: Text(text,
            style: const TextStyle(fontSize: 9, color: Colors.white, height: 1.2)),
      );
}

/// 三手势挂载（§6.4/§6.5）：长按 400ms 坍缩（仅 AI 块）/ 左滑过 35% 换乘 /
/// 右滑过 45% 融化（触觉反馈）；手势锁=水平初位移 >20dp 且 |Δx/Δy|>2.0，
/// 不满足无条件交还外层垂直滚动；拖拽用 Transform 平移不重排（守 60fps）。
class _GestureBlock extends StatefulWidget {
  const _GestureBlock({required this.block, required this.child});

  final ScheduleBlock block;
  final Widget child;

  @override
  State<_GestureBlock> createState() => _GestureBlockState();
}

class _GestureBlockState extends State<_GestureBlock> {
  double _dx = 0;
  Offset? _start;
  bool _locked = false;
  bool _busy = false;

  ScheduleBlock get _b => widget.block;

  Future<void> _run(Future<CommandResult> Function() run) async {
    if (_busy) return;
    setState(() => _busy = true);
    try {
      final r = await run();
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text(r.note ?? '完成'), duration: const Duration(seconds: 2)),
        );
      }
    } on ActionException catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text(e.message)));
      }
    } finally {
      if (mounted) setState(() => _dx = 0);
    }
  }

  Future<void> _melt() async {
    HapticFeedback.mediumImpact();
    await _run(() => CommandHandler(AppServices.repo)
        .execute(MeltBlockCommand(_b.id!, expectedVersion: _b.version)));
  }

  Future<void> _swap() async {
    HapticFeedback.mediumImpact();
    await _run(() => CommandHandler(AppServices.repo)
        .execute(SwapBlockCommand(_b.id!, expectedVersion: _b.version)));
  }

  Future<void> _degrade() async {
    await _run(() => CommandHandler(AppServices.repo)
        .execute(DegradeBlockCommand(_b.id!, expectedVersion: _b.version)));
  }

  @override
  Widget build(BuildContext context) {
    final active = _b.status == ScheduleBlock.statusProposed ||
        _b.status == ScheduleBlock.statusConfirmed;
    final canSwipe = active;
    final canDegrade =
        _b.source == ScheduleBlock.sourceAi && active;
    if (!canSwipe && !canDegrade) return widget.child;
    return LayoutBuilder(builder: (context, constraints) {
      final width = constraints.maxWidth;
      return RawGestureDetector(
        gestures: {
          if (canDegrade)
            LongPressGestureRecognizer: GestureRecognizerFactoryWithHandlers<
                LongPressGestureRecognizer>(
              () => LongPressGestureRecognizer(
                  duration: const Duration(milliseconds: StGesture.longPressMs)),
              (instance) => instance.onLongPress = _degrade,
            ),
        },
        child: GestureDetector(
          behavior: HitTestBehavior.deferToChild,
          onHorizontalDragStart: canSwipe
              ? (d) {
                  _start = d.localPosition;
                  _locked = false;
                }
              : null,
          onHorizontalDragUpdate: canSwipe
              ? (d) {
                  if (_start == null) return;
                  final dx = d.localPosition.dx - _start!.dx;
                  final dy = d.localPosition.dy - _start!.dy;
                  if (!_locked &&
                      dx.abs() > StGesture.horizontalSlopDp &&
                      dx.abs() / (dy.abs() < 1 ? 1 : dy.abs()) >
                          StGesture.axisRatioMin) {
                    _locked = true;
                  }
                  if (_locked) setState(() => _dx = dx);
                }
              : null,
          onHorizontalDragEnd: canSwipe
              ? (d) {
                  final dx = _dx;
                  setState(() {
                    _dx = 0;
                    _start = null;
                    _locked = false;
                  });
                  if (dx >= width * StGesture.meltFraction) {
                    _melt();
                  } else if (-dx >= width * StGesture.swapFraction) {
                    _swap();
                  }
                }
              : null,
          child: Stack(
            children: [
              // 滑动意图提示（过卡点才出现；绝不显红，中性陈述去向）
              if (_dx >= width * StGesture.meltFraction)
                Positioned.fill(
                  child: Container(
                    alignment: Alignment.centerLeft,
                    padding: const EdgeInsets.only(left: 12),
                    decoration: BoxDecoration(
                      color: StColors.freeFlowBg,
                      borderRadius: BorderRadius.circular(StScale.radiusBlock),
                    ),
                    child: const Text('暂缓并收回清单',
                        style: TextStyle(fontSize: 11)),
                  ),
                ),
              if (-_dx >= width * StGesture.swapFraction)
                Positioned.fill(
                  child: Container(
                    alignment: Alignment.centerRight,
                    padding: const EdgeInsets.only(right: 12),
                    decoration: BoxDecoration(
                      color: StColors.supplyBandBg,
                      borderRadius: BorderRadius.circular(StScale.radiusBlock),
                    ),
                    child: const Text('换件轻松的？',
                        style: TextStyle(fontSize: 11)),
                  ),
                ),
              Transform.translate(
                offset: Offset(_dx, 0),
                child: widget.child,
              ),
            ],
          ),
        ),
      );
    });
  }
}

/// 月历格子：日期号 + 状态点阵（done=实心 / missed=灰 / proposed=空心 /
/// human=灰描边，超出 4 点收敛 +N）+ 🔥/🎂 微标 + 例外日标签；今日高亮圈。
class _CalendarCell extends StatelessWidget {
  const _CalendarCell({
    required this.date,
    required this.data,
    required this.todayIso,
    required this.onPick,
  });

  final DateTime date;
  final Map<String, Object?>? data;
  final String todayIso;
  final void Function(DateTime date) onPick;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final iso = isoDate(date);
    final isToday = iso == todayIso;
    final d = data;
    final dots = <Color>[];
    if (d != null) {
      dots.addAll(List.filled(d['done'] as int, StColors.safelineOn));
      dots.addAll(List.filled(d['missed'] as int, StColors.safelineOff));
      dots.addAll(
          List.filled(d['proposed'] as int, scheme.primary));
      dots.addAll(
          List.filled(d['human'] as int, StColors.textSecondary));
    }
    final shown = dots.take(4).toList();
    final rest = dots.length - shown.length;
    final exception = d?['exception'] as String?;
    return InkWell(
      onTap: () => onPick(date),
      child: Container(
        margin: const EdgeInsets.all(2),
        decoration: BoxDecoration(
          border: isToday ? Border.all(color: scheme.primary, width: 1.4) : null,
          borderRadius: BorderRadius.circular(StScale.radiusBlock),
        ),
        padding: const EdgeInsets.all(3),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('${date.day}',
                style: Theme.of(context).textTheme.labelMedium?.copyWith(
                    color: isToday
                        ? scheme.primary
                        : (d == null ? StColors.textSecondary : null),
                    fontWeight: isToday ? FontWeight.bold : null)),
            if (dots.isNotEmpty)
              Wrap(
                spacing: 2,
                runSpacing: 2,
                children: [
                  for (final c in shown)
                    Container(
                      width: 7,
                      height: 7,
                      decoration: BoxDecoration(
                        color: c,
                        shape: BoxShape.circle,
                      ),
                    ),
                  if (rest > 0)
                    Text('+$rest',
                        style: const TextStyle(fontSize: 8, height: 1.2)),
                ],
              ),
            const Spacer(),
            Wrap(
              spacing: 2,
              children: [
                if (d?['spark_done'] == true)
                  const Text('🔥', style: TextStyle(fontSize: 9, height: 1.2)),
                if (d?['celebration'] == true)
                  const Text('🎂', style: TextStyle(fontSize: 9, height: 1.2)),
              ],
            ),
            if (exception != null)
              Text(
                exception,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(
                    fontSize: 8,
                    height: 1.2,
                    color: scheme.tertiary),
              ),
          ],
        ),
      ),
    );
  }
}


/// 翻月并保持「几号」（大月 31 → 小月钳到月末）。
DateTime _shiftMonth(DateTime month, int day, int delta) {
  final target = DateTime(month.year, month.month + delta + 1, 0); // 目标月末
  return DateTime(month.year, month.month + delta, day.clamp(1, target.day));
}

/// 周视图（§4.1，2026-10-05 原拍板恢复）：只读 7 列网格（列时间窗同日视图
/// 聚焦窗）、块紧凑渲染（状态/来源着色、单行截断、无手势）、点块跳日视图。
class _WeekView extends StatelessWidget {
  const _WeekView({
    required this.anchor,
    required this.onPick,
    required this.onWeekChange,
  });

  final DateTime anchor;
  final void Function(DateTime date) onPick;
  final void Function(int weeks) onWeekChange;

  static const _columnWidth = 112.0;

  @override
  Widget build(BuildContext context) {
    return FutureBuilder<Map<String, Object?>>(
      future: ScheduleQueries(AppServices.repo).weekOverview(anchor),
      builder: (context, snap) {
        if (snap.connectionState != ConnectionState.done) {
          return const Center(child: CircularProgressIndicator());
        }
        final data = snap.data;
        if (data == null) return const SizedBox.shrink();
        final wake = data['wake'] as int;
        final sleepAdj = data['sleep_adj'] as int;
        final window = (sleepAdj - wake).toDouble();
        final todayIso = isoDate(scheduleDayOf(DateTime.now(), wake));
        final days = (data['days'] as List).cast<Map<String, Object?>>();
        final weekStart = tryParseIsoDate(data['week_start'] as String)!;
        final weekEnd = addDays(weekStart, 6);
        return Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Row(
              children: [
                IconButton(
                  icon: const Icon(Icons.chevron_left),
                  tooltip: '上一周',
                  onPressed: () => onWeekChange(-1),
                ),
                Expanded(
                  child: Center(
                    child: Text(
                      '${weekStart.month}月${weekStart.day}日 – ${weekEnd.month}月${weekEnd.day}日',
                      style: Theme.of(context).textTheme.titleMedium),
                  ),
                ),
                IconButton(
                  icon: const Icon(Icons.chevron_right),
                  tooltip: '下一周',
                  onPressed: () => onWeekChange(1),
                ),
              ],
            ),
            Expanded(
              child: SingleChildScrollView(
                child: SingleChildScrollView(
                  scrollDirection: Axis.horizontal,
                  child: SizedBox(
                    width: _columnWidth * 7,
                    height: window * StScale.dpPerMinute + 28,
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Row(
                          children: [
                            for (final d in days)
                              SizedBox(
                                width: _columnWidth,
                                child: Padding(
                                  padding: const EdgeInsets.all(2),
                                  child: Text(
                                    '${(d['date'] as String).substring(8)}日 周${d['weekday']}'
                                    '${d['exception'] != null ? ' 🏖' : ''}',
                                    textAlign: TextAlign.center,
                                    style: Theme.of(context)
                                        .textTheme
                                        .labelSmall
                                        ?.copyWith(
                                          color: d['date'] == todayIso
                                              ? Theme.of(context).colorScheme.primary
                                              : StColors.textSecondary,
                                          fontWeight: d['date'] == todayIso
                                              ? FontWeight.bold
                                              : null,
                                        ),
                                  ),
                                ),
                              ),
                          ],
                        ),
                        Row(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            for (final d in days)
                              _WeekColumn(
                                date: tryParseIsoDate(d['date'] as String)!,
                                width: _columnWidth,
                                wake: wake,
                                window: window,
                                blocks:
                                    (d['blocks'] as List).cast<Map<String, Object?>>(),
                                fixedOnDate: (d['fixed_on_date'] as List)
                                    .cast<Map<String, Object?>>(),
                                fixedSpillover: (d['fixed_spillover'] as List)
                                    .cast<Map<String, Object?>>(),
                                onPick: onPick,
                              ),
                          ],
                        ),
                      ],
                    ),
                  ),
                ),
              ),
            ),
          ],
        );
      },
    );
  }
}

/// 周视图单列：fixed 背景带 + 紧凑块（状态/来源着色、单行截断）。
class _WeekColumn extends StatelessWidget {
  const _WeekColumn({
    required this.date,
    required this.width,
    required this.wake,
    required this.window,
    required this.blocks,
    required this.fixedOnDate,
    required this.fixedSpillover,
    required this.onPick,
  });

  final DateTime date;
  final double width;
  final int wake;
  final double window;
  final List<Map<String, Object?>> blocks;
  final List<Map<String, Object?>> fixedOnDate;
  final List<Map<String, Object?>> fixedSpillover;
  final void Function(DateTime date) onPick;

  double _topOf(int minutes) =>
      (minutes - wake).clamp(0, window) * StScale.dpPerMinute;

  double _heightOf(int startMin, int endMin) {
    final h = spanMinutes(startMin, endMin) * StScale.dpPerMinute;
    return h < 24 ? 24 : h;
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return SizedBox(
      width: width,
      height: window * StScale.dpPerMinute,
      child: Stack(
        clipBehavior: Clip.none,
        children: [
          for (var m = (wake ~/ 60 + 1) * 60; m < wake + window.round(); m += 60)
            Positioned(
              left: 0,
              right: 0,
              top: _topOf(m),
              child: const Divider(height: 1),
            ),
          for (final s in fixedOnDate)
            Positioned(
              left: 0,
              right: 0,
              top: _topOf(s['start_min'] as int),
              child: Container(
                height: _heightOf(s['start_min'] as int, s['end_min'] as int),
                color: StColors.fixedSlotBg,
              ),
            ),
          for (final s in fixedSpillover)
            Positioned(
              left: 0,
              right: 0,
              top: 0,
              child: Container(
                height: _heightOf(0, s['end_min'] as int),
                color: StColors.fixedSlotBg,
              ),
            ),
          for (final item in _layout())
            Positioned(
              left: item.lane * item.laneWidth,
              width: item.laneWidth - 1,
              top: _topOf(item.block['start_min'] as int),
              height: _heightOf(
                  item.block['start_min'] as int, item.block['end_min'] as int),
              child: InkWell(
                onTap: () => onPick(date),
                child: Container(
                  margin: const EdgeInsets.all(0.5),
                  padding: const EdgeInsets.all(2),
                  decoration: BoxDecoration(
                    color: (item.block['source'] as String) ==
                            ScheduleBlock.sourceHuman
                        ? StColors.humanBlockBg
                        : scheme.primaryContainer,
                    borderRadius: BorderRadius.circular(6),
                    border: (item.block['status'] as String) ==
                            ScheduleBlock.statusProposed
                        ? Border.all(color: scheme.primary, width: 1)
                        : (item.block['is_celebration'] == true
                            ? Border.all(color: StColors.celebrationStroke, width: 1)
                            : null),
                  ),
                  child: Text(
                    (item.block['effective_label'] as String?) ?? '(未命名)',
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                        fontSize: 9,
                        height: 1.1,
                        color: scheme.onPrimaryContainer),
                  ),
                ),
              ),
            ),
        ],
      ),
    );
  }

  List<({Map<String, Object?> block, int lane, double laneWidth})> _layout() {
    final sorted = [...blocks]..sort((a, b) =>
        (a['start_min'] as int).compareTo(b['start_min'] as int));
    final out = <({Map<String, Object?> block, int lane, double laneWidth})>[];
    final laneEnds = <int>[];
    final lanes = <String, int>{};
    var maxLane = 1;
    for (final b in sorted) {
      final start = b['start_min'] as int;
      final end = b['end_min'] as int;
      final endAbs = end < start ? end + 1440 : end;
      var lane = 0;
      while (lane < laneEnds.length && laneEnds[lane] > start) {
        lane++;
      }
      if (lane == laneEnds.length) {
        laneEnds.add(endAbs);
      } else {
        laneEnds[lane] = endAbs;
      }
      if (lane + 1 > maxLane) maxLane = lane + 1;
      lanes[b['id'] as String] = lane;
    }
    for (final b in sorted) {
      out.add((
        block: b,
        lane: lanes[b['id'] as String] ?? 0,
        laneWidth: width / maxLane,
      ));
    }
    return out;
  }
}

/// 简易月历（§4，2026-10-06 拍板）：只读月网格 + 格子摘要渲染
/// （状态点阵 + 🔥/🎂 微标 + 例外日标签）+ 点格跳日视图——过去的日程串起来。
class _MonthView extends StatelessWidget {
  const _MonthView({
    required this.anchor,
    required this.onPick,
    required this.onMonthChange,
  });

  /// 锚定选中日：翻月时保留「几号」（月末自动钳制）
  final DateTime anchor;
  final void Function(DateTime date) onPick;
  final void Function(DateTime date) onMonthChange;

  @override
  Widget build(BuildContext context) {
    final month = DateTime(anchor.year, anchor.month, 1);
    return FutureBuilder<Map<String, Object?>>(
      future:
          ScheduleQueries(AppServices.repo).monthOverview(month.year, month.month),
      builder: (context, snap) {
        if (snap.connectionState != ConnectionState.done) {
          return const Center(child: CircularProgressIndicator());
        }
        final data = snap.data;
        if (data == null) return const SizedBox.shrink();
        final days = (data['days'] as List).cast<Map<String, Object?>>();
        final byDate = {for (final d in days) d['date'] as String: d};
        final todayIso = data['today'] as String;
        final first = DateTime(month.year, month.month, 1);
        final leadingBlanks = first.weekday - 1; // 周一起始
        final monthLen = DateTime(month.year, month.month + 1, 0).day;
        return Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Row(
              children: [
                IconButton(
                  icon: const Icon(Icons.chevron_left),
                  tooltip: '上一月',
                  onPressed: () => onMonthChange(_shiftMonth(month, anchor.day, -1)),
                ),
                Expanded(
                  child: Center(
                    child: Text('${month.year}年${month.month}月',
                        style: Theme.of(context).textTheme.titleMedium),
                  ),
                ),
                IconButton(
                  icon: const Icon(Icons.chevron_right),
                  tooltip: '下一月',
                  onPressed: () => onMonthChange(_shiftMonth(month, anchor.day, 1)),
                ),
              ],
            ),
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 8),
              child: Row(
                children: [
                  for (final w in ['一', '二', '三', '四', '五', '六', '日'])
                    Expanded(
                      child: Center(
                        child: Text(w,
                            style: Theme.of(context)
                                .textTheme
                                .labelSmall
                                ?.copyWith(color: StColors.textSecondary)),
                      ),
                    ),
                ],
              ),
            ),
            const Divider(height: 1),
            Expanded(
              child: GridView.count(
                crossAxisCount: 7,
                childAspectRatio: 0.82,
                padding: const EdgeInsets.all(4),
                children: [
                  for (var i = 0; i < leadingBlanks; i++) const SizedBox(),
                  for (var day = 1; day <= monthLen; day++)
                    _CalendarCell(
                      date: DateTime(month.year, month.month, day),
                      data: byDate[isoDate(DateTime(month.year, month.month, day))],
                      todayIso: todayIso,
                      onPick: onPick,
                    ),
                ],
              ),
            ),
          ],
        );
      },
    );
  }
}

/// 翻月并保持「几号」（大月 31 → 小月钳到月末）。

