import '../models/artifact.dart';
import '../models/background.dart';
import '../models/fixed_slot.dart';
import '../models/plan.dart';
import '../models/schedule_block.dart';
import '../util/schedule_day.dart';

/// 命令协议（Command Pattern）：UI 与 AI 的唯一共同语言（Human-AI Parity 基石，
/// 模式照抄拾贝 commands.dart——schedule-app.md §12 领料）。
///
/// 设计意图——把 App 变成「无头（Headless）系统」，UI 与 AI 只是两个平等客户端：
///
/// - **人类操作**：UI 把表单/按键组装成 [ScheduleCommand] 交给命令层；
/// - **AI 操作**：MCP 把大模型输出的 JSON 经 [ScheduleCommand.fromJson] 反序列化成
///   **同一个**命令对象交给**同一个**命令层。
///
/// 核心逻辑不知道、也不关心这次请求是人按出来的还是 AI 算出来的。
/// 检验标准：删掉整个 Flutter UI，只留 MCP 命令面，M1 验收全链路应能完整运行。

// ───────────────────────────── 主体（Actor） ─────────────────────────────

/// 命令发起主体。防呆下沉的核心维度：同一条命令，不同主体的可执行性不同。
///
/// **刻意不由命令载荷携带**——否则大模型可以在 JSON 里把自己声明成 `human` 来越权
/// （和平条款 §3 的前提：AI 与人的通道必须物理可辨）。
/// 主体由传输层注入，不可被载荷伪造：
///
/// | 传输层 | 主体 |
/// |---|---|
/// | Flutter UI / app 内动作 | [CommandActor.human] |
/// | MCP / 桌面大模型客户端 | [CommandActor.ai] |
enum CommandActor {
  /// 手机 UI 与 app 内命令（终审方：确认三键/勾选/熔断等执行权在此）。
  human,

  /// MCP / 桌面大模型（排程师：提案与清单维护，受和平条款与机械校验约束）。
  ai,
}

// ───────────────────────────── 领域拒绝码 ─────────────────────────────

/// 机器可读的拒绝码。MCP 层原样放进 JSON-RPC error.data，
/// 让大模型不只是「看到报错」，而是能读懂原因并自我纠正。
abstract final class ActionErrorCode {
  static const invalidRequest = 'invalid_request';
  static const notFound = 'not_found';
  static const forbidden = 'forbidden';
  static const versionConflict = 'version_conflict';

  /// settings 未初始化（缺作息边界）——propose_schedule 整单硬拒（§6 校验铁律）。
  static const settingsMissing = 'settings_missing';

  /// propose_schedule 机械校验未过（逐条原因 + available_free_windows）。
  static const scheduleRejected = 'schedule_rejected';

  /// scope=global 背景注入预算超限（background-context-draft.md §2，终态双指标：
  /// 条数>8 或 content 总字数>400）。仅拦 ai actor；data 附当前全局背景全量摘要
  /// ——AI 无需重读即可提出具体归并方案（version_conflict/schedule_rejected 同款
  /// 返回契约风格）。
  static const budgetExceeded = 'budget_exceeded';
}

/// 动作被拒绝（领域校验不过 / 目标不存在 / 主体越权）。
///
/// 同一份异常，两个客户端各取所需：
/// - UI：catch 后弹提示（用 [message]）；
/// - AI：MCP 转成 JSON 错误对象（用 [code] + [hint] + [data]），大模型读懂后自我纠正。
class ActionException implements Exception {
  ActionException(this.message, {this.code = ActionErrorCode.invalidRequest, this.hint, this.data});

  final String message;
  final String code;

  /// 给 AI 的下一步建议（人类可读）。UI 不消费。
  final String? hint;

  /// 结构化附加数据：version_conflict 回最新快照（§6 返回契约，AI 无需重拉）；
  /// schedule_rejected 回逐条拒绝明细 + available_free_windows。
  final Map<String, Object?>? data;

  Map<String, Object?> toJson() => {
        'code': code,
        'message': message,
        if (hint != null) 'hint': hint,
        if (data != null) 'data': data,
      };

  @override
  String toString() => message;
}

// ───────────────────────────── 执行结果 ─────────────────────────────

/// 命令执行结果。**写路径一律回最新快照**——状态可见性对称（§3）。
///
/// 人类靠 ChangeNotifier 订阅看到刷新；AI 没有眼睛，只能靠返回值对齐
/// 「短期记忆」与数据库真实状态，否则下一步就会基于旧数据胡言乱语。
class CommandResult {
  const CommandResult({required this.op, this.targetId, this.snapshot, this.note, this.data});

  final String op;

  /// 目标 id；创建类命令为新建对象 id。
  final String? targetId;

  /// 落库后的最新快照（JSON 口径见 [planToJson]/[blockToJson]）；删除类命令为 null。
  final Map<String, Object?>? snapshot;

  /// 人类可读的结果说明（UI 提示 / AI 阅读共用；人放行的越界警告也在此）。
  final String? note;

  /// 命令专属附加数据（如 propose 回填 date/blocks、shift 回联级清单）。
  final Map<String, Object?>? data;

  Map<String, Object?> toJson() => {
        'ok': true,
        'op': op,
        if (targetId != null) 'id': targetId,
        if (note != null) 'note': note,
        if (snapshot != null) 'snapshot': snapshot,
        if (data != null) 'data': data,
      };
}

// ───────────────────────────── 快照 JSON（MCP 唯一序列化口径） ─────────────────────────────

/// plans 快照 JSON：保证 AI 拿到的 plan 形状与 list_plans 完全一致。
Map<String, Object?> planToJson(Plan p) => {
      'id': p.id,
      'title': p.title,
      'spec': p.spec,
      'notes': p.notes,
      'open_items': [
        for (final o in p.openItems)
          {'question': o.question, if (o.answer != null) 'answer': o.answer},
      ],
      'min_viable_action': p.minViableAction,
      'energy_level': p.energyLevel,
      'tool_required': p.toolRequired,
      'reward_spec': p.rewardSpec,
      'importance': p.importance,
      'deadline': p.deadline,
      'estimate': p.estimate,
      'parent_id': p.parentId,
      'archived': p.archived,
      'version': p.version,
    };

/// schedule_blocks 快照 JSON。
Map<String, Object?> blockToJson(ScheduleBlock b) => {
      'id': b.id,
      'date': b.date,
      'start': clockOf(b.startMin),
      'end': clockOf(b.endMin),
      'start_min': b.startMin,
      'end_min': b.endMin,
      'plan_id': b.planId,
      'label': b.label,
      'source': b.source,
      'pinned': b.pinned,
      'status': b.status,
      'postpone_count': b.postponeCount,
      'execution_quality': b.executionQuality,
      'is_day_spark': b.isDaySpark,
      'is_celebration': b.isCelebration,
      'version': b.version,
    };

/// fixed_slots JSON。
Map<String, Object?> slotToJson(FixedSlot s) => {
      'id': s.id,
      'name': s.name,
      'weekdays': s.weekdays,
      'start_min': s.startMin,
      'end_min': s.endMin,
    };

/// backgrounds 快照 JSON（MCP 唯一序列化口径）。
Map<String, Object?> backgroundToJson(Background b) => {
      'id': b.id,
      'scope': b.scope,
      'plan_id': b.planId,
      'content': b.content,
      'raw_source_text': b.rawSourceText,
      'tags': b.tags,
      'applicable_dates': b.applicableDates,
      'source': b.source,
      'captured_by': b.capturedBy,
      'version': b.version,
    };

/// artifacts 快照 JSON（MCP 唯一序列化口径）：payload 契约 JSON 原样透出（单一
/// 真相，槽位不再拍平复制——防快照内出现第二份）；plan_id/block_id 为投影列现值。
Map<String, Object?> artifactToJson(Artifact a) => {
      'id': a.id,
      'category': a.category,
      'source_kind': a.sourceKind,
      'state': a.state,
      'origin': a.origin,
      'title': a.title,
      'badge': a.badge,
      'payload': a.payload,
      'captured_by': a.capturedBy,
      'plan_id': a.planId,
      'block_id': a.blockId,
      'version': a.version,
    };

// ───────────────────────────── 命令本体 ─────────────────────────────

/// 命令基类（sealed：新增命令必须在本文件登记并同步到命令层 switch）。
sealed class ScheduleCommand {
  const ScheduleCommand({this.expectedVersion});

  /// **乐观锁断言**：发起方「看到的」目标版本号。非空时命令层做 CAS——版本不一致
  /// 即拒绝并回最新快照，防止人类慢速编辑与 AI 瞬时写入互相**静默覆盖**。
  /// 空 = 不校验。
  final int? expectedVersion;

  /// 命令字：JSON 载荷的判别字段，与 MCP 工具名解耦（工具只是命令的包装）。
  String get op;

  /// 目标 id；创建类命令为 null。
  String? get targetId;

  Map<String, Object?> toJson();

  /// JSON → 命令。AI 侧唯一入口：大模型输出什么就反序列化成什么，
  /// 不做任何「只有 UI 才懂」的隐转换。
  /// open_items 载荷解析（add_plan/update_plan 可携带；坏条目静默跳过）
  static List<OpenItem>? _openItems(Object? raw) {
    if (raw is! List) return null;
    return [
      for (final e in raw)
        if (e is Map && e['question'] is String)
          OpenItem(
            question: e['question'] as String,
            answer: e['answer'] is String ? e['answer'] as String : null,
          ),
    ];
  }

  static ScheduleCommand fromJson(Map<String, Object?> json) {
    final op = json['op'];
    if (op is! String || op.isEmpty) {
      throw ActionException(
        '命令缺少 op 字段',
        code: ActionErrorCode.invalidRequest,
        hint: '可选 op：${supportedOps.join(', ')}',
      );
    }
    final ev = _int(json['expected_version']);
    switch (op) {
      case 'quick_capture':
        return QuickCaptureCommand(
          title: _reqStr(json, 'title', op),
          importance: json['importance'] == true,
          deadline: _str(json['deadline']),
        );
      case 'upsert_plan':
        return UpsertPlanCommand(
          title: _reqStr(json, 'title', op),
          spec: _str(json['spec']),
          notes: _str(json['notes']),
          minViableAction: _str(json['min_viable_action']),
          energyLevel: _str(json['energy_level']),
          toolRequired: _str(json['tool_required']),
          rewardSpec: _str(json['reward_spec']),
          importance: json['importance'] == true,
          deadline: _str(json['deadline']),
          estimate: _int(json['estimate']),
          parentId: _str(json['parent_id']),
          openItems: _openItems(json['open_items']),
        );
      case 'update_plan':
        return UpdatePlanCommand(
          id: _reqStr(json, 'id', op),
          title: _str(json['title']),
          spec: _str(json['spec']),
          notes: _str(json['notes']),
          minViableAction: _str(json['min_viable_action']),
          energyLevel: _str(json['energy_level']),
          toolRequired: _str(json['tool_required']),
          rewardSpec: _str(json['reward_spec']),
          importance: json.containsKey('importance') ? json['importance'] == true : null,
          deadline: _str(json['deadline']),
          estimate: _int(json['estimate']),
          parentId: _str(json['parent_id']),
          archived: json.containsKey('archived') ? json['archived'] == true : null,
          openItems: _openItems(json['open_items']),
          expectedVersion: ev,
        );
      case 'propose_schedule':
        final rawItems = json['items'];
        if (rawItems is! List || rawItems.isEmpty) {
          throw ActionException(
            'propose_schedule 需要非空 items 数组',
            code: ActionErrorCode.invalidRequest,
            hint: 'items 每项：{plan_id?, label?, start_min, end_min, is_day_spark?, is_celebration?}',
          );
        }
        return ProposeScheduleCommand(
          date: _reqStr(json, 'date', op),
          items: [
            for (var i = 0; i < rawItems.length; i++)
              ProposedItem.fromJson(_map(rawItems[i], 'items[$i]', op)),
          ],
        );
      case 'place_block':
        return PlaceBlockCommand(
          date: _reqStr(json, 'date', op),
          startMin: _int(json['start_min']) ?? -1,
          endMin: _int(json['end_min']) ?? -1,
          planId: _str(json['plan_id']),
          label: _str(json['label']),
        );
      case 'delete_plan':
        return DeletePlanCommand(_reqStr(json, 'id', op));
      case 'adjust_blocks':
        final action = _str(json['action']);
        if (action == null) {
          throw ActionException(
            'adjust_blocks 需要 action 字段（add/move/resize/remove）',
            code: ActionErrorCode.invalidRequest,
          );
        }
        return AdjustBlocksCommand(
          action: action,
          blockId: _str(json['block_id']),
          date: _str(json['date']),
          startMin: _int(json['start_min']),
          endMin: _int(json['end_min']),
          planId: _str(json['plan_id']),
          label: _str(json['label']),
          pinned: json.containsKey('pinned') ? json['pinned'] == true : null,
          expectedVersion: ev,
        );
      case 'retro_log':
        return RetroLogCommand(
          date: _reqStr(json, 'date', op),
          startMin: _int(json['start_min']) ?? -1,
          endMin: _int(json['end_min']) ?? -1,
          planId: _str(json['plan_id']),
          label: _str(json['label']),
          executionQuality: _str(json['execution_quality']),
        );
      case 'swap_block':
        return SwapBlockCommand(
          _reqStr(json, 'id', op),
          targetPlanId: _str(json['target_plan_id']),
          expectedVersion: ev,
        );
      case 'degrade_block':
        return DegradeBlockCommand(_reqStr(json, 'id', op), expectedVersion: ev);
      case 'melt_block':
        return MeltBlockCommand(_reqStr(json, 'id', op), expectedVersion: ev);
      case 'reflow_day':
        return ReflowDayCommand(date: _str(json['date']));
      case 'confirm_block':
        return ConfirmBlockCommand(_reqStr(json, 'id', op), expectedVersion: ev);
      case 'reject_block':
        return RejectBlockCommand(_reqStr(json, 'id', op), reason: _str(json['reason']), expectedVersion: ev);
      case 'adjust_block_time':
        return AdjustBlockTimeCommand(
          id: _reqStr(json, 'id', op),
          startMin: _int(json['start_min']),
          endMin: _int(json['end_min']),
          expectedVersion: ev,
        );
      case 'shift_block':
        return ShiftBlockCommand(
          _reqStr(json, 'id', op),
          minutes: _int(json['minutes']) ?? 30,
          expectedVersion: ev,
        );
      case 'tick_block':
        return TickBlockCommand(
          _reqStr(json, 'id', op),
          executionQuality: _str(json['execution_quality']),
          expectedVersion: ev,
        );
      case 'update_settings':
        final values = json['values'];
        if (values is! Map) {
          throw ActionException(
            'update_settings 需要 values 对象',
            code: ActionErrorCode.invalidRequest,
            hint: '合法键：wake_time/sleep_time/min_block_minutes/daily_new_blocks_limit/'
                'today_energy/user_rules/weather_location/exceptions',
          );
        }
        return UpdateSettingsCommand(
          values: values.cast<String, Object?>(),
        );
      case 'update_fixed_slots':
        final rawSlots = json['slots'];
        if (rawSlots is! List) {
          throw ActionException(
            'update_fixed_slots 需要 slots 数组（空表合法）',
            code: ActionErrorCode.invalidRequest,
          );
        }
        return UpdateFixedSlotsCommand(slots: [
          for (var i = 0; i < rawSlots.length; i++)
            FixedSlot.fromMap(_map(rawSlots[i], 'slots[$i]', op)),
        ]);
      case 'upsert_background':
        final scope = _reqStr(json, 'scope', op);
        if (scope != Background.scopeGlobal && scope != Background.scopePlan) {
          throw ActionException(
            'upsert_background scope 非法：$scope',
            code: ActionErrorCode.invalidRequest,
            hint: 'global=常驻画像叙事 / plan=行程背景（须带 plan_id）',
          );
        }
        return UpsertBackgroundCommand(
          id: _str(json['id']),
          scope: scope,
          planId: _str(json['plan_id']),
          content: _reqStr(json, 'content', op),
          rawSourceText: _str(json['raw_source_text']),
          tags: _strList(json['tags'], 'tags', op),
          applicableDates: _strList(json['applicable_dates'], 'applicable_dates', op),
          expectedVersion: ev,
        );
      case 'merge_backgrounds':
        final rawOps = json['ops'];
        if (rawOps is! List || rawOps.isEmpty) {
          throw ActionException(
            'merge_backgrounds 需要非空 ops 数组',
            code: ActionErrorCode.invalidRequest,
            hint: '每项：{kind: delete, id} 或 {kind: upsert, id?, scope?, plan_id?, content, tags?, applicable_dates?, raw_source_text?}',
          );
        }
        return MergeBackgroundsCommand(ops: [
          for (var i = 0; i < rawOps.length; i++)
            BackgroundMergeOp.fromJson(_map(rawOps[i], 'ops[$i]', op), 'ops[$i]', op),
        ]);
      case 'upsert_facts':
        return UpsertFactsCommand(
          id: _str(json['id']),
          // category/source_kind 编辑路径可缺省（=沿用现值）；建档必填校验在
          // command_handler 建档分支（真正守门点），解析层不重复收紧
          category: _str(json['category']),
          sourceKind: _str(json['source_kind']),
          title: _str(json['title']),
          state: _str(json['state']),
          origin: _str(json['origin']),
          badge: _str(json['badge']),
          heroMetrics: _objList(json['hero_metrics'], 'hero_metrics', op),
          timeAnchors: _objList(json['time_anchors'], 'time_anchors', op),
          constraints:
              json['constraints'] == null ? null : _map(json['constraints'], 'constraints', op),
          location: _str(json['location']),
          copyableCode: _str(json['copyable_code']),
          contactPhone: _str(json['contact_phone']),
          rawText: _str(json['raw_text']),
          planId: _str(json['plan_id']),
          blockId: _str(json['block_id']),
          expectedVersion: ev,
        );
      case 'delete_artifact':
        return DeleteArtifactCommand(_reqStr(json, 'id', op));
      default:
        throw ActionException(
          '未知命令：$op',
          code: ActionErrorCode.invalidRequest,
          hint: '可选 op：${supportedOps.join(', ')}',
        );
    }
  }

  /// 所有合法命令字（错误提示与文档同步用）。
  static const supportedOps = [
    'quick_capture',
    'upsert_plan',
    'update_plan',
    'propose_schedule',
    'adjust_blocks',
    'place_block',
    'delete_plan',
    'retro_log',
    'swap_block',
    'degrade_block',
    'melt_block',
    'reflow_day',
    'confirm_block',
    'reject_block',
    'adjust_block_time',
    'shift_block',
    'tick_block',
    'update_settings',
    'update_fixed_slots',
    'upsert_background',
    'merge_backgrounds',
    'upsert_facts',
    'delete_artifact',
  ];
}

/// propose_schedule 单项提案（全部 source=ai）。
class ProposedItem {
  const ProposedItem({
    this.planId,
    this.label,
    required this.startMin,
    required this.endMin,
    this.isDaySpark = false,
    this.isCelebration = false,
  });

  final String? planId;
  final String? label;
  final int startMin;
  final int endMin;
  final bool isDaySpark;
  final bool isCelebration;

  factory ProposedItem.fromJson(Map<String, Object?> json) {
    final start = _int(json['start_min']);
    final end = _int(json['end_min']);
    if (start == null || end == null) {
      throw ActionException(
        '提案项缺少 start_min/end_min',
        code: ActionErrorCode.invalidRequest,
        hint: '一切块必须有起止（§11 拍板），分钟数 0..1440',
      );
    }
    return ProposedItem(
      planId: _str(json['plan_id']),
      label: _str(json['label']),
      startMin: start,
      endMin: end,
      isDaySpark: json['is_day_spark'] == true,
      isCelebration: json['is_celebration'] == true,
    );
  }
}

/// 快记（§3 QuickCapture，human）：零细节捕获 → plan（默认 light/anywhere）。
final class QuickCaptureCommand extends ScheduleCommand {
  const QuickCaptureCommand({
    required this.title,
    this.importance = false,
    this.deadline,
  });

  final String title;
  final bool importance;

  /// 可选截止日 'yyyy-MM-dd'（快记条可选字段）
  final String? deadline;

  @override
  String get op => 'quick_capture';
  @override
  String? get targetId => null;
  @override
  Map<String, Object?> toJson() => {
        'op': op,
        'title': title,
        'importance': importance,
        if (deadline != null) 'deadline': deadline,
      };
}

/// 快记草稿静默持久化（§10 快记入口 2026-10-06 拍板）：三字段整体覆写，
/// text 为空=整组清除（保存成功/清空收起是唯一清除通道）。
/// 仅 UI 使用、不进 MCP 工具面（human-only）；键为 quick_note_draft_* 内部键。
final class QuickNoteDraftCommand extends ScheduleCommand {
  const QuickNoteDraftCommand({
    required this.text,
    this.importance = false,
    this.deadline,
  });

  final String text;
  final bool importance;

  /// 可选截止日 'yyyy-MM-dd'（草稿暂存，保存时随快记落 plan）
  final String? deadline;

  @override
  String get op => 'quick_note_draft';
  @override
  String? get targetId => null;
  @override
  Map<String, Object?> toJson() => {
        'op': op,
        'text': text,
        'importance': importance,
        if (deadline != null) 'deadline': deadline,
      };
}

/// 计划创建（§3 UpsertPlan 的创建语义；AI 走 MCP add_plan 映射到本命令）。
final class UpsertPlanCommand extends ScheduleCommand {
  const UpsertPlanCommand({
    required this.title,
    this.spec,
    this.notes,
    this.minViableAction,
    this.energyLevel,
    this.toolRequired,
    this.rewardSpec,
    this.importance = false,
    this.deadline,
    this.estimate,
    this.parentId,
    this.openItems,
  });

  final String title;
  final String? spec;
  final String? notes;
  final String? minViableAction;
  final String? energyLevel;
  final String? toolRequired;
  final String? rewardSpec;
  final bool importance;
  final String? deadline;
  final int? estimate;
  final String? parentId;
  final List<OpenItem>? openItems;

  @override
  String get op => 'upsert_plan';
  @override
  String? get targetId => null;
  @override
  Map<String, Object?> toJson() => {
        'op': op,
        'title': title,
        if (spec != null) 'spec': spec,
        if (notes != null) 'notes': notes,
        if (minViableAction != null) 'min_viable_action': minViableAction,
        if (energyLevel != null) 'energy_level': energyLevel,
        if (toolRequired != null) 'tool_required': toolRequired,
        if (rewardSpec != null) 'reward_spec': rewardSpec,
        'importance': importance,
        if (deadline != null) 'deadline': deadline,
        if (estimate != null) 'estimate': estimate,
        if (parentId != null) 'parent_id': parentId,
      };
}

/// 计划字段级 patch（§3 UpdatePlan；只发要改的字段，§11 并发契约）。
final class UpdatePlanCommand extends ScheduleCommand {
  const UpdatePlanCommand({
    required this.id,
    this.title,
    this.spec,
    this.notes,
    this.minViableAction,
    this.energyLevel,
    this.toolRequired,
    this.rewardSpec,
    this.importance,
    this.deadline,
    this.estimate,
    this.parentId,
    this.openItems,
    this.archived,
    super.expectedVersion,
  });

  final String id;
  final String? title;
  final String? spec;
  final String? notes;
  final String? minViableAction;
  final String? energyLevel;
  final String? toolRequired;
  final String? rewardSpec;
  final bool? importance;
  final String? deadline;
  final int? estimate;
  final String? parentId;
  final List<OpenItem>? openItems;

  /// archived 只在显式给出时进 patch（§11：只发要改的字段）
  final bool? archived;

  @override
  String get op => 'update_plan';
  @override
  String? get targetId => id;
  @override
  Map<String, Object?> toJson() => {
        'op': op,
        'id': id,
        if (title != null) 'title': title,
        if (spec != null) 'spec': spec,
        if (notes != null) 'notes': notes,
        if (minViableAction != null) 'min_viable_action': minViableAction,
        if (energyLevel != null) 'energy_level': energyLevel,
        if (toolRequired != null) 'tool_required': toolRequired,
        if (rewardSpec != null) 'reward_spec': rewardSpec,
        if (importance != null) 'importance': importance,
        if (deadline != null) 'deadline': deadline,
        if (estimate != null) 'estimate': estimate,
        if (parentId != null) 'parent_id': parentId,
        if (archived != null) 'archived': archived,
        if (expectedVersion != null) 'expected_version': expectedVersion,
      };
}

/// 整天原子提案（§3 ProposeSchedule，AI 专属）：全部合法才落库，否则逐条返回原因。
final class ProposeScheduleCommand extends ScheduleCommand {
  const ProposeScheduleCommand({required this.date, required this.items});

  /// 目标日 'yyyy-MM-dd'（服务端供日期口径：AI 不自己算「下周三」，§6）
  final String date;
  final List<ProposedItem> items;

  @override
  String get op => 'propose_schedule';
  @override
  String? get targetId => null;
  @override
  Map<String, Object?> toJson() => {
        'op': op,
        'date': date,
        'items': [
          for (final i in items)
            {
              if (i.planId != null) 'plan_id': i.planId,
              if (i.label != null) 'label': i.label,
              'start_min': i.startMin,
              'end_min': i.endMin,
              'is_day_spark': i.isDaySpark,
              'is_celebration': i.isCelebration,
            }
        ],
      };
}

/// 人直接放块（human 专属）：点空槽创建/从清单选的命令化（§3 校验刻度不对称——
/// human 允许任性，重叠/越界由 UI 橙色提示放行，命令层只拦起止非法）。
/// source=human、status=confirmed（人放下的即是已定之事）；AI 单块写走 adjust_blocks（M2）。
final class PlaceBlockCommand extends ScheduleCommand {
  const PlaceBlockCommand({
    required this.date,
    required this.startMin,
    required this.endMin,
    this.planId,
    this.label,
  });

  final String date;
  final int startMin;
  final int endMin;
  final String? planId;
  final String? label;

  @override
  String get op => 'place_block';
  @override
  String? get targetId => null;
  @override
  Map<String, Object?> toJson() => {
        'op': op,
        'date': date,
        'start_min': startMin,
        'end_min': endMin,
        if (planId != null) 'plan_id': planId,
        if (label != null) 'label': label,
      };
}

/// 计划删除（human 专属，不可逆）：物理删除；有日程块/子计划引用时被外键拒绝
/// （处置策略：先处理引用再删——命令层转可读错误）。
final class DeletePlanCommand extends ScheduleCommand {
  const DeletePlanCommand(this.id);
  final String id;

  @override
  String get op => 'delete_plan';
  @override
  String? get targetId => id;
  @override
  Map<String, Object?> toJson() => {'op': op, 'id': id};
}

/// AI 单块建/挪/缩/删（§3 adjust_blocks：火车行程等硬日程入口；受和平条款门控
/// ——human 块与 pinned 块不可动；remove 对 confirmed 块 = 熔断同效动作走 melted）。
final class AdjustBlocksCommand extends ScheduleCommand {
  const AdjustBlocksCommand({
    required this.action,
    this.blockId,
    this.date,
    this.startMin,
    this.endMin,
    this.planId,
    this.label,
    this.pinned,
    super.expectedVersion,
  });

  /// add | move | resize | remove
  final String action;
  final String? blockId;
  final String? date; // add 必填；move 可选（跨日改期）
  final int? startMin; // add 必填；move/resize 至少给其一
  final int? endMin;
  final String? planId; // add 可选
  final String? label; // add 可选

  /// add 默认 true（AI 建的无 plan 硬行程默认 pinned，§4）；其余动作忽略
  final bool? pinned;
  @override
  String get op => 'adjust_blocks';
  @override
  String? get targetId => blockId;
  @override
  Map<String, Object?> toJson() => {
        'op': op,
        'action': action,
        if (blockId != null) 'block_id': blockId,
        if (date != null) 'date': date,
        if (startMin != null) 'start_min': startMin,
        if (endMin != null) 'end_min': endMin,
        if (planId != null) 'plan_id': planId,
        if (label != null) 'label': label,
        if (pinned != null) 'pinned': pinned,
        if (expectedVersion != null) 'expected_version': expectedVersion,
      };
}

/// 逆向记账（§10 七轮，human 专属）：空白时段补记 done 块——**事实通道豁免
/// 排程校验**（记录现实不是制定计划），计入深潜净值；凡真实专注全部承认。
final class RetroLogCommand extends ScheduleCommand {
  const RetroLogCommand({
    required this.date,
    required this.startMin,
    required this.endMin,
    this.planId,
    this.label,
    this.executionQuality,
  });

  final String date;
  final int startMin;
  final int endMin;
  final String? planId;
  final String? label;
  final String? executionQuality; // full（默认）/ spark
  @override
  String get op => 'retro_log';
  @override
  String? get targetId => null;
  @override
  Map<String, Object?> toJson() => {
        'op': op,
        'date': date,
        'start_min': startMin,
        'end_min': endMin,
        if (planId != null) 'plan_id': planId,
        if (label != null) 'label': label,
        if (executionQuality != null) 'execution_quality': executionQuality,
      };
}

/// 换乘（§10 七轮，human 专属）：原地换入待安排池时长相近的 light/anywhere
/// 琐事，原任务无痕回池——化解精力死锁。
final class SwapBlockCommand extends ScheduleCommand {
  const SwapBlockCommand(this.id, {this.targetPlanId, super.expectedVersion});
  final String id;

  /// 可选：换乘抽屉显式挑选的候选计划；缺省自动就近匹配（时长相近优先）。
  final String? targetPlanId;
  @override
  String get op => 'swap_block';
  @override
  String? get targetId => id;
  @override
  Map<String, Object?> toJson() => {
        'op': op,
        'id': id,
        if (targetPlanId != null) 'target_plan_id': targetPlanId,
        if (expectedVersion != null) 'expected_version': expectedVersion,
      };
}

/// 坍缩降级（§3 DegradeBlock，human 专属）：长按原地坍缩——块缩为 5 分钟
/// 火种胶囊（剩余时间释放为补给带），执行内容切 min_viable_action；
/// human 块不可坍缩（§6.4）。
final class DegradeBlockCommand extends ScheduleCommand {
  const DegradeBlockCommand(this.id, {super.expectedVersion});
  final String id;
  @override
  String get op => 'degrade_block';
  @override
  String? get targetId => id;
  @override
  Map<String, Object?> toJson() => {
        'op': op,
        'id': id,
        if (expectedVersion != null) 'expected_version': expectedVersion,
      };
}

/// 融化（§3 MeltBlock，human 专属）：右滑化为水汽——melted 中性态无痕回池，
/// 绝不显红；仅物理位移/用户主动触发，绝不因「没打卡」触发（§4）。
final class MeltBlockCommand extends ScheduleCommand {
  const MeltBlockCommand(this.id, {super.expectedVersion});
  final String id;
  @override
  String get op => 'melt_block';
  @override
  String? get targetId => id;
  @override
  Map<String, Object?> toJson() => {
        'op': op,
        'id': id,
        if (expectedVersion != null) 'expected_version': expectedVersion,
      };
}

/// 水流模型确定性重算（§7 六轮拍板，human 专属）：现实扰动（电量晚点选/人工块
/// 加入）后当日重算——三级阻尼：①淹没留白（免动作）→②后序 AI 块降 spark
/// （压 min_viable_action）→③溢出 melted 蒸发回池；牺牲顺序=非核心最先、
/// 火种靠后（压 spark 优先于融化）、庆祝块豁免（仅全局熔断可动）。
final class ReflowDayCommand extends ScheduleCommand {
  const ReflowDayCommand({this.date});
  final String? date; // 缺省=今日作息日
  @override
  String get op => 'reflow_day';
  @override
  String? get targetId => null;
  @override
  Map<String, Object?> toJson() => {'op': op, if (date != null) 'date': date};
}

/// 确认键（human）：proposed → confirmed。
final class ConfirmBlockCommand extends ScheduleCommand {
  const ConfirmBlockCommand(this.id, {super.expectedVersion});
  final String id;
  @override
  String get op => 'confirm_block';
  @override
  String? get targetId => id;
  @override
  Map<String, Object?> toJson() => {
        'op': op,
        'id': id,
        if (expectedVersion != null) 'expected_version': expectedVersion,
      };
}

/// 否决键（human）：proposed 块删除蒸发，plan 自然回落待安排；原因可选（AI 下轮参考）。
final class RejectBlockCommand extends ScheduleCommand {
  const RejectBlockCommand(this.id, {this.reason, super.expectedVersion});
  final String id;
  final String? reason;
  @override
  String get op => 'reject_block';
  @override
  String? get targetId => id;
  @override
  Map<String, Object?> toJson() => {
        'op': op,
        'id': id,
        if (reason != null) 'reason': reason,
        if (expectedVersion != null) 'expected_version': expectedVersion,
      };
}

/// 改时间键（human）：patch 起止；重叠/越界橙色提示放行（校验刻度不对称 §3）。
final class AdjustBlockTimeCommand extends ScheduleCommand {
  const AdjustBlockTimeCommand({
    required this.id,
    this.startMin,
    this.endMin,
    super.expectedVersion,
  });
  final String id;

  /// 两值至少给一个；给 null 表示不改动该端。
  final int? startMin;
  final int? endMin;
  @override
  String get op => 'adjust_block_time';
  @override
  String? get targetId => id;
  @override
  Map<String, Object?> toJson() => {
        'op': op,
        'id': id,
        if (startMin != null) 'start_min': startMin,
        if (endMin != null) 'end_min': endMin,
        if (expectedVersion != null) 'expected_version': expectedVersion,
      };
}

/// +30min 顺延微调（human）：级联顺延同日后续 AI 块，human/pinned 原地不动（§10）。
final class ShiftBlockCommand extends ScheduleCommand {
  const ShiftBlockCommand(this.id, {this.minutes = 30, super.expectedVersion});
  final String id;

  /// 顺延分钟数，默认 30。
  final int minutes;
  @override
  String get op => 'shift_block';
  @override
  String? get targetId => id;
  @override
  Map<String, Object?> toJson() => {
        'op': op,
        'id': id,
        'minutes': minutes,
        if (expectedVersion != null) 'expected_version': expectedVersion,
      };
}

/// 勾选（human）：proposed/confirmed → done；spark=打折执行（§4）。
final class TickBlockCommand extends ScheduleCommand {
  const TickBlockCommand(this.id, {this.executionQuality, super.expectedVersion});
  final String id;

  /// full（默认）/ spark——spark 时执行内容取 plan.min_viable_action（playbook 口径）。
  final String? executionQuality;
  @override
  String get op => 'tick_block';
  @override
  String? get targetId => id;
  @override
  Map<String, Object?> toJson() => {
        'op': op,
        'id': id,
        if (executionQuality != null) 'execution_quality': executionQuality,
        if (expectedVersion != null) 'expected_version': expectedVersion,
      };
}

/// 恢复（human 专属，UI 撤销通道）：把误触弄丢的块状态还原回活跃态——
/// melted（右滑融化误触）/ done（打卡误触）→ proposed/confirmed。
/// 仅 UI 撤销 SnackBar 使用、不进 MCP 工具面（同 quick_note_draft 口径）：
/// 状态机不放开通用回退，只允许这两个中性/终态回活跃态，守住「减震器」语义。
final class RestoreBlockCommand extends ScheduleCommand {
  const RestoreBlockCommand(this.id, {required this.toStatus, super.expectedVersion});

  final String id;

  /// 还原目标态：proposed/confirmed（活跃态）。命令层校验，其余拒绝。
  final String toStatus;

  @override
  String get op => 'restore_block';

  @override
  String? get targetId => id;

  @override
  Map<String, Object?> toJson() => {
        'op': op,
        'id': id,
        'to_status': toStatus,
        if (expectedVersion != null) 'expected_version': expectedVersion,
      };
}

/// 设置写（§3 UpdateSettings，人/AI）：键注册制，传 null 清空对应项。
/// 遗留区「顺延今日」（ui-spec §3.2，2026-10-06 拍板）：missed 块整体搬到
/// 目标日，起止钟点不变、身份不变（postpone_count+1）。仅 missed 可顺延；
/// human 专属（AI 挪块走 adjust_blocks 校验刻度）。
final class PostponeBlockCommand extends ScheduleCommand {
  const PostponeBlockCommand(this.id, {required this.date, super.expectedVersion});

  final String id;

  /// 目标日 'yyyy-MM-dd'（遗留区固定传今日）
  final String date;

  @override
  String get op => 'postpone_block';
  @override
  String? get targetId => id;
  @override
  Map<String, Object?> toJson() => {
        'op': op,
        'id': id,
        'date': date,
        if (expectedVersion != null) 'expected_version': expectedVersion,
      };
}

/// 现实熔断（§8 五轮拍板；ui-spec §3.6，2026-10-06 UI 落地）：human 专属批量
/// 动作，命令层原子执行。mode：
/// `clear_remaining`——今日未开始 AI 块（非 pinned）整体 melted 无痕回池；
/// `push_2h`——今日未开始 AI 块整体 +120min（顺延计数+1），撞手动/pinned/
/// 固定占用或越作息边界整单拒。human 块与 pinned 硬行程永不动（和平条款）。
final class PanicClearCommand extends ScheduleCommand {
  const PanicClearCommand({required this.mode, this.nowMin, super.expectedVersion});

  final String mode;

  /// 「未开始」判定基准（当日分钟数），UI 传当前时刻；缺省取命令执行时刻。
  final int? nowMin;

  @override
  String get op => 'panic_clear';
  @override
  String? get targetId => null;
  @override
  Map<String, Object?> toJson() => {
        'op': op,
        'mode': mode,
        if (nowMin != null) 'now_min': nowMin,
      };
}

final class UpdateSettingsCommand extends ScheduleCommand {
  const UpdateSettingsCommand({required this.values});
  final Map<String, Object?> values;
  @override
  String get op => 'update_settings';
  @override
  String? get targetId => null;
  @override
  Map<String, Object?> toJson() => {'op': op, 'values': values};
}

/// 一周节奏批量原子替换（§3 UpdateFixedSlots）：空表合法（自由职业预设 §9）。
final class UpdateFixedSlotsCommand extends ScheduleCommand {
  const UpdateFixedSlotsCommand({required this.slots});
  final List<FixedSlot> slots;
  @override
  String get op => 'update_fixed_slots';
  @override
  String? get targetId => null;
  @override
  Map<String, Object?> toJson() => {
        'op': op,
        'slots': [for (final s in slots) s.toMap()],
      };
}

/// 背景信息写（background-context-draft.md §2/§3，人/AI 双通道）：id 缺省=建档；
/// id 给定且存在=编辑（手编契约：scope/plan_id 不可变——载荷与现值不符即拒、
/// raw_source_text 永不变——编辑路径一律忽略传入原话）。source 不作为载荷字段，
/// 由 actor 派生（human→user / ai→ai_derived，溯源不可伪造）。
/// scope=global 受注入预算终态双指标约束（budget_exceeded 仅拦 ai，human 放行）。
final class UpsertBackgroundCommand extends ScheduleCommand {
  const UpsertBackgroundCommand({
    this.id,
    required this.scope,
    this.planId,
    required this.content,
    this.rawSourceText,
    this.tags,
    this.applicableDates,
    super.expectedVersion,
  });

  final String? id;
  final String scope; // global | plan（建档定死，改挂=删旧增新走 merge）
  final String? planId; // scope=plan 必填
  final String content;

  /// 原话溯源，仅建档时写入一次；编辑路径忽略
  final String? rawSourceText;
  final List<String>? tags; // #健康 式自由标签，不锁枚举

  /// 瞬态作用日期窗（单日 ISO 数组）；null=长期；空数组由模型归一 null
  final List<String>? applicableDates;

  @override
  String get op => 'upsert_background';
  @override
  String? get targetId => id;
  @override
  Map<String, Object?> toJson() => {
        'op': op,
        if (id != null) 'id': id,
        'scope': scope,
        if (planId != null) 'plan_id': planId,
        'content': content,
        if (rawSourceText != null) 'raw_source_text': rawSourceText,
        if (tags != null) 'tags': tags,
        if (applicableDates != null) 'applicable_dates': applicableDates,
        if (expectedVersion != null) 'expected_version': expectedVersion,
      };
}

/// 归并批处理单项（背景草案 §2）：delete={id} / upsert={id?, scope?, plan_id?,
/// content, tags?, applicable_dates?, raw_source_text?}。upsert 带 id 且行存在=
/// 编辑（契约字段不可变同上）；否则=建档（id 可自带，幂等重试友好）。
class BackgroundMergeOp {
  const BackgroundMergeOp.delete(this.id)
      : kind = 'delete',
        scope = null,
        planId = null,
        content = null,
        rawSourceText = null,
        tags = null,
        applicableDates = null;

  const BackgroundMergeOp.upsert({
    this.id,
    this.scope,
    this.planId,
    required this.content,
    this.rawSourceText,
    this.tags,
    this.applicableDates,
  }) : kind = 'upsert';

  final String kind; // delete | upsert
  final String? id; // delete 必填；upsert 目标行（可空=建档）
  final String? scope; // 建档必填；编辑路径与现值不符即拒
  final String? planId;
  final String? content; // upsert 必填（归并后的归纳描述）
  final String? rawSourceText; // 仅建档生效
  final List<String>? tags;
  final List<String>? applicableDates;

  factory BackgroundMergeOp.fromJson(Map<String, Object?> json, String key, String op) {
    final kind = json['kind'];
    if (kind == 'delete') {
      final id = json['id'];
      if (id is! String || id.isEmpty) {
        throw ActionException(
          '$op 的 $key（delete）需要非空 id',
          code: ActionErrorCode.invalidRequest,
        );
      }
      return BackgroundMergeOp.delete(id);
    }
    if (kind == 'upsert') {
      final content = _str(json['content']);
      if (content == null) {
        throw ActionException(
          '$op 的 $key（upsert）需要非空 content（归并后的归纳描述）',
          code: ActionErrorCode.invalidRequest,
        );
      }
      return BackgroundMergeOp.upsert(
        id: _str(json['id']),
        scope: _str(json['scope']),
        planId: _str(json['plan_id']),
        content: content,
        rawSourceText: _str(json['raw_source_text']),
        tags: _strList(json['tags'], '$key.tags', op),
        applicableDates: _strList(json['applicable_dates'], '$key.applicable_dates', op),
      );
    }
    throw ActionException(
      '$op 的 $key kind 非法：$kind（delete/upsert）',
      code: ActionErrorCode.invalidRequest,
    );
  }
}

/// 背景归并原子批处理（背景草案 §2 预算治理）：ops 数组（删 A / 删 B / 插 C）
/// 单事务执行、终态校验全过才落库、否则整批拒——防「先插 C 再被拦」死锁与
/// 「先删 A」有损中间态（模式先例=update_fixed_slots 整批原子替换 /
/// propose_schedule 按天整单原子写）。human/AI 双通道（app 内手动归并同命令）；
/// 预算终态校验仍仅拦 ai（校验刻度不对称）。
final class MergeBackgroundsCommand extends ScheduleCommand {
  const MergeBackgroundsCommand({required this.ops});
  final List<BackgroundMergeOp> ops;
  @override
  String get op => 'merge_backgrounds';
  @override
  String? get targetId => null;
  @override
  Map<String, Object?> toJson() => {
        'op': op,
        'ops': [
          for (final o in ops)
            {
              'kind': o.kind,
              if (o.id != null) 'id': o.id,
              if (o.scope != null) 'scope': o.scope,
              if (o.planId != null) 'plan_id': o.planId,
              if (o.content != null) 'content': o.content,
              if (o.rawSourceText != null) 'raw_source_text': o.rawSourceText,
              if (o.tags != null) 'tags': o.tags,
              if (o.applicableDates != null) 'applicable_dates': o.applicableDates,
            }
        ],
      };
}

/// 事实凭证写（fact-user-relay-draft.md §1.1/§1.4/§8-3，人/AI 双通道）：
/// id 缺省=建档（系统分享原文入册 state=raw / AI 对话投喂直接结构化）；
/// id 给定且存在=编辑（AI 提炼回填 structured / 改挂 plan_id / 作废 voided）——
/// 字段级 merge 只覆盖传入槽位，raw_text **追加双段只增不清**（改签 SOP 溯源）。
/// 槽位由命令层组装成 §1.2 契约 JSON（v:1 经 Artifact.encodePayload 单点编码）；
/// plan_id/block_id 双写铁律=列与 payload 投影同步（Artifact.payloadWithRefs）。
/// category/source_kind/origin/captured_by 出生不可变（编辑路径忽略传入），
/// **例外=出生确认**（§1.2 施工定案）：raw 建档零解析恒以 verbal/verbal 占位
/// （命令层拒非占位的 raw 建档），首次 raw→structured 回填允许定二值终值，
/// structured 后恒不可变。
/// 作废=state=voided（存证保留）；物理删除走 DeleteArtifact（仅 human）。
final class UpsertFactsCommand extends ScheduleCommand {
  const UpsertFactsCommand({
    this.id,
    this.category,
    this.sourceKind,
    this.title,
    this.state,
    this.origin,
    this.badge,
    this.heroMetrics,
    this.timeAnchors,
    this.constraints,
    this.location,
    this.copyableCode,
    this.contactPhone,
    this.rawText,
    this.planId,
    this.blockId,
    super.expectedVersion,
  });

  final String? id; // 缺省=建档；给定=编辑
  final String? category; // transit/ticket/hotel/venue/verbal；建档必填，
      // raw 建档恒 verbal 占位，出生确认（首次 raw→structured）定终值
  final String? sourceKind; // booking/announcement/verbal；建档必填，口径同上
  final String? title; // 建档必填（命令层校验）；编辑缺省=不变
  final String? state; // raw/structured/voided；建档缺省=raw
  final String? origin; // 建档必填（shared/quicknote/ai/manual）；编辑忽略
  final String? badge; // 超 12 字符命令层截断（截断优于拒绝）
  final List<Map<String, Object?>>? heroMetrics; // ≤3 截前 3
  final List<Map<String, Object?>>? timeAnchors; // moment/span/rule 三类锚
  final Map<String, Object?>? constraints; // 三数组+机读参数
  final String? location;
  final String? copyableCode;
  final String? contactPhone;

  /// 原文永存：编辑路径与已存原文不同时**追加**新段，绝不覆盖
  final String? rawText;
  final String? planId; // 建档 null=未归属池；编辑给定=改挂
  final String? blockId; // AI 升格回填关联块（软引用）

  @override
  String get op => 'upsert_facts';
  @override
  String? get targetId => id;
  @override
  Map<String, Object?> toJson() => {
        'op': op,
        if (id != null) 'id': id,
        if (category != null) 'category': category,
        if (sourceKind != null) 'source_kind': sourceKind,
        if (title != null) 'title': title,
        if (state != null) 'state': state,
        if (origin != null) 'origin': origin,
        if (badge != null) 'badge': badge,
        if (heroMetrics != null) 'hero_metrics': heroMetrics,
        if (timeAnchors != null) 'time_anchors': timeAnchors,
        if (constraints != null) 'constraints': constraints,
        if (location != null) 'location': location,
        if (copyableCode != null) 'copyable_code': copyableCode,
        if (contactPhone != null) 'contact_phone': contactPhone,
        if (rawText != null) 'raw_text': rawText,
        if (planId != null) 'plan_id': planId,
        if (blockId != null) 'block_id': blockId,
        if (expectedVersion != null) 'expected_version': expectedVersion,
      };
}

/// 凭证物理删除（fact-user-relay-draft.md §1.4，human 专属）：误分享文本清理。
/// 退票/作废走 upsert_facts state=voided（存证保留，AI 经对话同意可写）；
/// AI 无删除权、只可提议。删凭证绝不删块——关联块失去挂载并从 pinned 降格
/// （同事务，§1.4 反向销毁防护）。
final class DeleteArtifactCommand extends ScheduleCommand {
  const DeleteArtifactCommand(this.id);
  final String id;
  @override
  String get op => 'delete_artifact';
  @override
  String? get targetId => id;
  @override
  Map<String, Object?> toJson() => {'op': op, 'id': id};
}

// ───────────────────────────── 载荷解析助手 ─────────────────────────────

String? _str(Object? v) => v is String && v.isNotEmpty ? v : null;
int? _int(Object? v) => v is int ? v : (v is String ? int.tryParse(v) : null);

/// JSON 对象数组解析（hero_metrics/time_anchors 载荷）：给定了就必须是数组且元素
/// 为对象，类型不对整组拒——写入口严进（读侧容错过滤是另一道，不适用于此）。
List<Map<String, Object?>>? _objList(Object? v, String key, String op) {
  if (v == null) return null;
  if (v is! List) {
    throw ActionException('$op 的 $key 必须是数组', code: ActionErrorCode.invalidRequest);
  }
  return [for (var i = 0; i < v.length; i++) _map(v[i], '$key[$i]', op)];
}

String _reqStr(Map<String, Object?> json, String key, String op) {
  final v = json[key];
  if (v is! String || v.isEmpty) {
    throw ActionException(
      '$op 需要非空字符串字段 $key',
      code: ActionErrorCode.invalidRequest,
    );
  }
  return v;
}

Map<String, Object?> _map(Object? v, String key, String op) {
  if (v is! Map) {
    throw ActionException(
      '$op 的 $key 必须是对象',
      code: ActionErrorCode.invalidRequest,
    );
  }
  return v.cast<String, Object?>();
}

/// JSON 字符串数组解析（tags/applicable_dates 载荷）：给定了就必须是字符串数组，
/// 元素类型不对整组拒——写入口严进（读侧的容错过滤是另一道，不适用于此）。
List<String>? _strList(Object? v, String key, String op) {
  if (v == null) return null;
  if (v is! List) {
    throw ActionException(
      '$op 的 $key 必须是字符串数组',
      code: ActionErrorCode.invalidRequest,
    );
  }
  for (final e in v) {
    if (e is! String) {
      throw ActionException(
        '$op 的 $key 元素须为字符串（got: $e）',
        code: ActionErrorCode.invalidRequest,
      );
    }
  }
  return v.cast<String>();
}
