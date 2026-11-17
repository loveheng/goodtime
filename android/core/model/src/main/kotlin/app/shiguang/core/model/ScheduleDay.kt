package app.shiguang.core.model

import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 日期工具层：作息日换算与跨午夜几何（util/schedule_day.dart 的 Kotlin 等价）。
 *
 * 「作息日」以 wake_time 切割而非物理 00:00（§11 拍板）；跨午夜块按开始日归属
 * 不拆段（§4 拍板）。pure JVM——无 Android 依赖（:core:model 红线）。
 */
object ScheduleDay {

    /** 当日分钟数（0..1439）。 */
    fun minutesOfDay(now: LocalDateTime): Int = now.hour * 60 + now.minute

    /** 作息日换算：[now] 落在 wake 切割点之前 → 属于前一日历日。 */
    fun scheduleDayOf(now: LocalDateTime, wakeMinute: Int): LocalDate {
        val day = now.toLocalDate()
        return if (minutesOfDay(now) < wakeMinute) day.minusDays(1) else day
    }

    /** ISO 日期 'yyyy-MM-dd'（LocalDate.toString 即补零口径）。 */
    fun isoDate(d: LocalDate): String = d.toString()

    /**
     * 解析 'yyyy-MM-dd' 为 LocalDate；格式或日历不合法（如 02-30）返回 null，不回绕。
     * 与 Dart tryParseIsoDate 同口径：严格 4-2-2 位、拒绝年溢出前缀。
     */
    fun tryParseIsoDate(s: String): LocalDate? {
        if (!Regex("""^\d{4}-\d{2}-\d{2}$""").matches(s)) return null
        return try {
            LocalDate.parse(s)
        } catch (_: Exception) {
            null
        }
    }

    /** 跨午夜判定：end < start（如 23:30–07:30）。 */
    fun crossesMidnight(startMin: Int, endMin: Int): Boolean = endMin < startMin

    /** 块/占用的时长分钟；跨午夜经 24:00 折返（1410→450 = 480）。 */
    fun spanMinutes(startMin: Int, endMin: Int): Int =
        if (crossesMidnight(startMin, endMin)) 1440 - startMin + endMin else endMin - startMin

    /** 合并重叠/相接的分钟区间（占用并集计算用；返回按起点排序的不相交区间）。 */
    fun mergeMinutesIntervals(intervals: List<Pair<Int, Int>>): List<Pair<Int, Int>> {
        if (intervals.isEmpty()) return emptyList()
        val sorted = intervals.sortedBy { it.first }
        val out = mutableListOf(sorted.first())
        for ((s, e) in sorted.drop(1)) {
            val last = out.last()
            if (s <= last.second) {
                if (e > last.second) out[out.size - 1] = Pair(last.first, e)
            } else {
                out.add(Pair(s, e))
            }
        }
        return out
    }

    /** 窗口 [windowStart, windowEnd) 内的空闲分钟数 = 窗长 − 占用并集∩窗。 */
    fun freeMinutesIn(
        windowStart: Int, windowEnd: Int, occupied: List<Pair<Int, Int>>,
    ): Int {
        var busy = 0
        for ((s, e) in mergeMinutesIntervals(occupied)) {
            val lo = if (s < windowStart) windowStart else s
            val hi = if (e > windowEnd) windowEnd else e
            if (hi > lo) busy += hi - lo
        }
        return windowEnd - windowStart - busy
    }

    /** 窗口内空隙：占用并集的补集（派生保护区/自由流动区渲染用；Dart gapsIn 逐字镜像）。 */
    fun gapsIn(
        windowStart: Int, windowEnd: Int, occupied: List<Pair<Int, Int>>,
    ): List<Pair<Int, Int>> {
        val gaps = mutableListOf<Pair<Int, Int>>()
        var cursor = windowStart
        for ((s, e) in mergeMinutesIntervals(occupied)) {
            if (s > cursor) gaps.add(Pair(cursor, if (s < windowEnd) s else windowEnd))
            if (e > cursor) cursor = e
        }
        if (cursor < windowEnd) gaps.add(Pair(cursor, windowEnd))
        return gaps.filter { it.second > it.first }
    }

    /** 分钟 → 'HH:MM'（AI 与 UI 共用的时刻显示口径；超窗值经 %1440 折返）。 */
    fun clockOf(minutes: Int): String {
        val m = minutes % 1440
        return "%02d:%02d".format(m / 60, m % 60)
    }

    /**
     * 下一作息日 wake 边界（严格晚于 [now]；fgs_housekeeper.md 过目 2026-11-15）：
     * 过夜日切精确闹钟的排程几何——wake 时刻还没到（今天）→ 今天 wake；已过 → 明天 wake。
     */
    fun nextWakeBoundary(now: LocalDateTime, wakeMinute: Int): LocalDateTime {
        val todayWake = now.toLocalDate().atStartOfDay().plusMinutes(wakeMinute.toLong())
        return if (now < todayWake) todayWake else todayWake.plusDays(1)
    }
}
