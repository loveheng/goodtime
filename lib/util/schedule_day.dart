/// 日期工具层：作息日换算与跨午夜几何（schedule-app.md §4/§8/§11）。
///
/// 「作息日」以 wake_time 切割而非物理 00:00（§11 拍板）：凌晨收尾的工作
/// 仍算「昨天」，管家日切、复盘聚合、propose 默认目标日全部用它。
/// 跨午夜块（如睡眠 23:30–07:30）按开始日归属、不拆段（§4 拍板）：
/// 归属规则只在数据层执行，显示层截断/溢出归 BlockRenderer（§10）。
library;

/// 当日分钟数（0..1439）。
int minutesOfDay(DateTime d) => d.hour * 60 + d.minute;

/// 作息日换算：[now] 落在 wake 切割点之前 → 属于前一日历日。
/// 返回作息日（当地零点）。[wakeMinute] 取 settings.wake_time（0..1439）。
DateTime scheduleDayOf(DateTime now, int wakeMinute) {
  final day = DateTime(now.year, now.month, now.day);
  return minutesOfDay(now) < wakeMinute ? addDays(day, -1) : day;
}

/// 当地零点起算加 N 天（日历算术，月/年边界由 DateTime 进位保证）。
DateTime addDays(DateTime d, int n) => DateTime(d.year, d.month, d.day + n);

/// ISO 日期 'yyyy-MM-dd'（当地时区，月/日补零）。
String isoDate(DateTime d) =>
    '${d.year.toString().padLeft(4, '0')}-'
    '${d.month.toString().padLeft(2, '0')}-'
    '${d.day.toString().padLeft(2, '0')}';

/// 解析 'yyyy-MM-dd' 为当地零点；格式或日历不合法（如 02-30）返回 null，不回绕。
DateTime? tryParseIsoDate(String s) {
  final m = RegExp(r'^(\d{4})-(\d{2})-(\d{2})$').firstMatch(s);
  if (m == null) return null;
  final y = int.parse(m.group(1)!);
  final mo = int.parse(m.group(2)!);
  final d = int.parse(m.group(3)!);
  if (mo < 1 || mo > 12 || d < 1 || d > 31) return null;
  final t = DateTime(y, mo, d);
  return (t.year == y && t.month == mo && t.day == d) ? t : null;
}

/// 跨午夜判定：end < start（如 23:30–07:30）。同起止为零长块，合法性归命令层。
bool crossesMidnight(int startMin, int endMin) => endMin < startMin;

/// 块/占用的时长分钟；跨午夜经 24:00 折返（1410→450 = 480）。
int spanMinutes(int startMin, int endMin) =>
    crossesMidnight(startMin, endMin) ? 1440 - startMin + endMin : endMin - startMin;

/// 两个起止区间是否重叠（各自按时长归一化到 [start, start+span)，跨午夜自然展开；
/// 端点相接不算重叠：540-600 与 600-660 相容）。
bool overlapsMinutes(int aStart, int aEnd, int bStart, int bEnd) {
  final a0 = aStart, a1 = aStart + spanMinutes(aStart, aEnd);
  final b0 = bStart, b1 = bStart + spanMinutes(bStart, bEnd);
  return a0 < b1 && b0 < a1;
}

/// 分钟 → 'HH:MM'（1440 = '24:00'），AI 与 UI 共用的时刻显示口径。
String clockOf(int minutes) {
  final m = minutes % 1440;
  return '${(m ~/ 60).toString().padLeft(2, '0')}:${(m % 60).toString().padLeft(2, '0')}';
}

/// 合并重叠/相接的分钟区间（占用并集计算用；输入 [start, end) 绝对分钟制，
/// 返回按起点排序的不相交区间）。
List<(int, int)> mergeMinutesIntervals(List<(int, int)> intervals) {
  if (intervals.isEmpty) return const [];
  final sorted = [...intervals]..sort((a, b) => a.$1.compareTo(b.$1));
  final out = <(int, int)>[sorted.first];
  for (final (s, e) in sorted.skip(1)) {
    final last = out.last;
    if (s <= last.$2) {
      if (e > last.$2) out[out.length - 1] = (last.$1, e);
    } else {
      out.add((s, e));
    }
  }
  return out;
}

/// 窗口 [windowStart, windowEnd) 内的空闲分钟数 = 窗长 − 占用并集∩窗
/// （§6 可用时间口径；命令层填充率与 UI 双轨仪表盘「自由留白」同源）。
int freeMinutesIn(int windowStart, int windowEnd, List<(int, int)> occupied) {
  var busy = 0;
  for (final (s, e) in mergeMinutesIntervals(occupied)) {
    final lo = s < windowStart ? windowStart : s;
    final hi = e > windowEnd ? windowEnd : e;
    if (hi > lo) busy += hi - lo;
  }
  return windowEnd - windowStart - busy;
}

/// 求窗口内空隙：占用并集的补集（派生保护区/自由流动区渲染用）。
List<(int, int)> gapsIn(int windowStart, int windowEnd, List<(int, int)> occupied) {
  final gaps = <(int, int)>[];
  var cursor = windowStart;
  for (final (s, e) in mergeMinutesIntervals(occupied)) {
    if (s > cursor) gaps.add((cursor, s < windowEnd ? s : windowEnd));
    if (e > cursor) cursor = e;
  }
  if (cursor < windowEnd) gaps.add((cursor, windowEnd));
  return gaps.where((g) => g.$2 > g.$1).toList();
}
