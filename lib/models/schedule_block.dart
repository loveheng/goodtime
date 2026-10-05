/// schedule_blocks 表实体（schedule-app.md §4）：日程块实例 = 肉身（Avatar）——
/// 本体在某日「天气与地貌」下临时凝聚的形态，随时可变形可牺牲；
/// 与 plans 绝对解耦，一件事 ≠ 一个日程块。
class ScheduleBlock {
  ScheduleBlock({
    this.id,
    required this.date,
    required this.startMin,
    required this.endMin,
    this.planId,
    this.label,
    required this.source,
    this.pinned = false,
    this.status = statusProposed,
    this.postponeCount = 0,
    this.executionQuality = qualityFull,
    this.isDaySpark = false,
    this.isCelebration = false,
    this.createdAt,
    this.updatedAt,
    this.version = 0,
  });

  static const sourceHuman = 'human';
  static const sourceAi = 'ai';

  static const statusProposed = 'proposed';
  static const statusConfirmed = 'confirmed';
  static const statusDone = 'done';
  static const statusSkipped = 'skipped';
  static const statusMissed = 'missed';
  static const statusArchived = 'archived'; // 断流静默保护的中性归档态
  static const statusMelted = 'melted'; // 水流溢出/熔断的中性融化态

  static const qualityFull = 'full';
  static const qualitySpark = 'spark';

  final String? id;

  /// 'yyyy-MM-DD' 开始日（作息归属唯一真相；跨午夜块不拆段）
  final String date;

  /// 当日起始分钟 0..1439
  final int startMin;

  /// 当日结束分钟 1..1440（1440=24:00）；小于 [startMin] ⇒ 跨午夜；
  /// 必须有起止（§11 拍板，不支持弹性块）
  final int endMin;

  /// 可空——用户直接放的孤立块不欠清单交代
  final String? planId;

  /// 块自己的行动描述（动词开头），可空，缺省回落 plan 标题
  final String? label;
  final String source; // human | ai（和平条款依据）

  /// 钉住：AI 建的无 plan 块（火车/面试/航班）默认 pinned；用户可钉任何块
  final bool pinned;
  final String status; // §4 状态机七态
  final int postponeCount; // 顺延计数，≥3 打「需人工决策」标
  final String executionQuality; // full | spark

  /// 当日黄金火种，每作息日至多一个
  final bool isDaySpark;

  /// 庆祝块：最高豁免权，过期绝不 missed
  final bool isCelebration;

  final int? createdAt; // 毫秒；落库前可空，由仓库补
  final int? updatedAt; // 毫秒
  final int version; // 乐观锁

  ScheduleBlock copyWith({
    String? date,
    int? startMin,
    int? endMin,
    String? planId,
    String? label,
    String? source,
    bool? pinned,
    String? status,
    int? postponeCount,
    String? executionQuality,
    bool? isDaySpark,
    bool? isCelebration,
  }) =>
      ScheduleBlock(
        id: id,
        date: date ?? this.date,
        startMin: startMin ?? this.startMin,
        endMin: endMin ?? this.endMin,
        planId: planId ?? this.planId,
        label: label ?? this.label,
        source: source ?? this.source,
        pinned: pinned ?? this.pinned,
        status: status ?? this.status,
        postponeCount: postponeCount ?? this.postponeCount,
        executionQuality: executionQuality ?? this.executionQuality,
        isDaySpark: isDaySpark ?? this.isDaySpark,
        isCelebration: isCelebration ?? this.isCelebration,
        createdAt: createdAt,
        updatedAt: updatedAt,
        version: version,
      );

  Map<String, Object?> toMap() => {
        if (id != null) 'id': id,
        'date': date,
        'start_min': startMin,
        'end_min': endMin,
        'plan_id': planId,
        'label': label,
        'source': source,
        'pinned': pinned ? 1 : 0,
        'status': status,
        'postpone_count': postponeCount,
        'execution_quality': executionQuality,
        'is_day_spark': isDaySpark ? 1 : 0,
        'is_celebration': isCelebration ? 1 : 0,
        if (createdAt != null) 'created_at': createdAt,
        if (updatedAt != null) 'updated_at': updatedAt,
        'version': version,
      };

  factory ScheduleBlock.fromMap(Map<String, Object?> map) => ScheduleBlock(
        id: map['id'] as String,
        date: map['date'] as String,
        startMin: map['start_min'] as int,
        endMin: map['end_min'] as int,
        planId: map['plan_id'] as String?,
        label: map['label'] as String?,
        source: map['source'] as String,
        pinned: (map['pinned'] as int? ?? 0) != 0,
        status: map['status'] as String? ?? statusProposed,
        postponeCount: map['postpone_count'] as int? ?? 0,
        executionQuality: map['execution_quality'] as String? ?? qualityFull,
        isDaySpark: (map['is_day_spark'] as int? ?? 0) != 0,
        isCelebration: (map['is_celebration'] as int? ?? 0) != 0,
        createdAt: map['created_at'] as int?,
        updatedAt: map['updated_at'] as int?,
        version: map['version'] as int? ?? 0,
      );
}
