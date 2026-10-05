/// fixed_slots 表实体（schedule-app.md §4）：固定占用 = 约束，不是愿望。
/// 星期几集合 + 起止 + 名称；工作日/周末两套模板 = 不同 weekday 集合的行并存；
/// 空表合法（自由职业预设，§9）。
class FixedSlot {
  FixedSlot({
    this.id,
    required this.name,
    required this.weekdays,
    required this.startMin,
    required this.endMin,
  });

  final String? id;

  /// 占用名称（如「睡眠」「通勤」）
  final String name;

  /// ISO 星期集合：1=周一 … 7=周日
  final List<int> weekdays;

  /// 当日起始分钟 0..1439
  final int startMin;

  /// 当日结束分钟 1..1440（1440=24:00）；小于 [startMin] ⇒ 跨午夜段
  /// （如睡眠 23:30–07:30），按开始日归属不拆段
  final int endMin;

  bool coversWeekday(int isoWeekday) => weekdays.contains(isoWeekday);

  Map<String, Object?> toMap() => {
        if (id != null) 'id': id,
        'name': name,
        'weekdays': weekdays.join(','),
        'start_min': startMin,
        'end_min': endMin,
      };

  /// 双形态解析：DB 行（id 恒在、weekdays=CSV）与命令/工具载荷（id 可省、
  /// weekdays=数组）共用——缺 id 时由仓库 replaceFixedSlots 补配。
  factory FixedSlot.fromMap(Map<String, Object?> map) => FixedSlot(
        id: map['id'] as String?,
        name: map['name'] as String,
        weekdays: _parseWeekdays(map['weekdays']),
        startMin: map['start_min'] as int,
        endMin: map['end_min'] as int,
      );

  static List<int> _parseWeekdays(Object? raw) => switch (raw) {
        final String s => [
            for (final e in s.split(','))
              if (e.trim().isNotEmpty) int.parse(e.trim()),
          ],
        final List l => [for (final e in l) e as int],
        _ => const [],
      };
}
