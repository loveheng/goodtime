import 'dart:convert';

/// plans 表实体（schedule-app.md §4）：清单条目 = 唯一的「家」，本体（Soul）——
/// 持有意图、上下文、notes 与 open_items，恒常；与肉身 schedule_blocks 绝对解耦
/// （一件事 ≠ 一个日程块，肉身可变形可牺牲，灵魂不丢）。
class Plan {
  Plan({
    this.id,
    required this.title,
    this.spec,
    this.notes,
    this.openItems = const [],
    this.minViableAction,
    this.energyLevel = energyLight,
    this.toolRequired = toolAnywhere,
    this.rewardSpec,
    this.importance = false,
    this.deadline,
    this.estimate,
    this.parentId,
    this.archived = false,
    this.createdAt,
    this.updatedAt,
    this.version = 0,
  });

  static const energyDeep = 'deep'; // 深度专注
  static const energyLight = 'light'; // 轻松琐事（快记默认，零负担）
  static const toolDesk = 'desk'; // 需电脑桌前
  static const toolAnywhere = 'anywhere'; // 手机即可（默认）

  final String? id;
  final String title;

  /// 精确无歧义描述（AI 维护的唯一权威陈述），可空
  final String? spec;

  /// 已敲定细节沉淀；首行约定 = 启动第一步（playbook Landing Gear）
  final String? notes;

  /// 未决问题（澄清管道产物）
  final List<OpenItem> openItems;

  /// 降级行动描述（「哪怕只读 1 页」）；spark 模式的执行依据
  final String? minViableAction;
  final String energyLevel; // deep | light
  final String toolRequired; // desk | anywhere

  /// 犒赏锚点，兑现时生成庆祝块
  final String? rewardSpec;

  /// 手动一击（快记条旁一颗星）；urgency 为派生不入库
  final bool importance;

  /// 可空截止日 'yyyy-MM-dd'；临近升「紧急」为派生态
  final String? deadline;

  /// 预估时长（分钟，概念态粗估，细化后修正）
  final int? estimate;

  /// 父计划，可空，放开多级（政策建议深度 ≤3）
  final String? parentId;

  /// 清单「归档」落点；list 默认过滤
  final bool archived;

  final int? createdAt; // 毫秒；落库前可空，由仓库补
  final int? updatedAt; // 毫秒；冷藏池「超龄」派生判据
  final int version; // 乐观锁

  /// 清空可空字段（spec/notes 等）不走 copyWith（无法表达 null），走仓库 patch 传 null。
  Plan copyWith({
    String? title,
    String? spec,
    String? notes,
    List<OpenItem>? openItems,
    String? minViableAction,
    String? energyLevel,
    String? toolRequired,
    String? rewardSpec,
    bool? importance,
    String? deadline,
    int? estimate,
    String? parentId,
    bool? archived,
  }) =>
      Plan(
        id: id,
        title: title ?? this.title,
        spec: spec ?? this.spec,
        notes: notes ?? this.notes,
        openItems: openItems ?? this.openItems,
        minViableAction: minViableAction ?? this.minViableAction,
        energyLevel: energyLevel ?? this.energyLevel,
        toolRequired: toolRequired ?? this.toolRequired,
        rewardSpec: rewardSpec ?? this.rewardSpec,
        importance: importance ?? this.importance,
        deadline: deadline ?? this.deadline,
        estimate: estimate ?? this.estimate,
        parentId: parentId ?? this.parentId,
        archived: archived ?? this.archived,
        createdAt: createdAt,
        updatedAt: updatedAt,
        version: version,
      );

  Map<String, Object?> toMap() => {
        if (id != null) 'id': id,
        'title': title,
        'spec': spec,
        'notes': notes,
        'open_items': encodeOpenItems(openItems),
        'min_viable_action': minViableAction,
        'energy_level': energyLevel,
        'tool_required': toolRequired,
        'reward_spec': rewardSpec,
        'importance': importance ? 1 : 0,
        'deadline': deadline,
        'estimate': estimate,
        'parent_id': parentId,
        'archived': archived ? 1 : 0,
        if (createdAt != null) 'created_at': createdAt,
        if (updatedAt != null) 'updated_at': updatedAt,
        'version': version,
      };

  factory Plan.fromMap(Map<String, Object?> map) => Plan(
        id: map['id'] as String,
        title: map['title'] as String,
        spec: map['spec'] as String?,
        notes: map['notes'] as String?,
        openItems: decodeOpenItems(map['open_items'] as String?),
        minViableAction: map['min_viable_action'] as String?,
        energyLevel: map['energy_level'] as String? ?? energyLight,
        toolRequired: map['tool_required'] as String? ?? toolAnywhere,
        rewardSpec: map['reward_spec'] as String?,
        importance: (map['importance'] as int? ?? 0) != 0,
        deadline: map['deadline'] as String?,
        estimate: map['estimate'] as int?,
        parentId: map['parent_id'] as String?,
        archived: (map['archived'] as int? ?? 0) != 0,
        createdAt: map['created_at'] as int?,
        updatedAt: map['updated_at'] as int?,
        version: map['version'] as int? ?? 0,
      );

  static String encodeOpenItems(List<OpenItem> items) => jsonEncode([
        for (final o in items)
          {
            'question': o.question,
            if (o.answer != null) 'answer': o.answer,
          }
      ]);

  static List<OpenItem> decodeOpenItems(String? raw) {
    if (raw == null || raw.isEmpty) return const [];
    final list = jsonDecode(raw) as List<dynamic>;
    return [
      for (final e in list)
        OpenItem(
          question: (e as Map<String, dynamic>)['question'] as String,
          answer: e['answer'] as String?,
        ),
    ];
  }
}

/// 未决问题（§4 open_items：[{question, answer?}]）；answer 非空即已敲定。
class OpenItem {
  const OpenItem({required this.question, this.answer});

  final String question;
  final String? answer;
}
