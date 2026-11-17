package app.shiguang.shiguang.ui.timeline

import app.shiguang.core.model.ScheduleDay
import app.shiguang.core.tokens.StScale

/**
 * 时间轴像素几何（P4③主干页，丢失点 #3）——纯逻辑 JVM 对拍件（BlockVisualSpec 同范式）。
 * goNow 换算/钳制与 topOf/heightOf 逐字取 block_renderer.md「时间轴挂载语境」过目口径，
 * 禁现推参数。分钟域 Int、像素域 Float（StScale.dpPerMinute）。
 */
object TimelineMath {

    /** 时间轴画布高 = 聚焦窗分钟 × dpPerMinute（1 分钟=1dp）。 */
    fun windowHeightDp(windowMinutes: Int): Float = windowMinutes * StScale.dpPerMinute

    /**
     * 「回到现在」滚动目标（丢失点 #3，2026-10-08 拍板）：现在居中，
     * 钳 [0, max(0, 窗高−viewport)]——窗外（凌晨/深夜）落首尾。
     */
    fun goNowOffsetDp(nowMin: Int, wakeMin: Int, windowMinutes: Int, viewportDp: Float): Float {
        val windowHeight = windowHeightDp(windowMinutes)
        val maxScroll = if (windowHeight > viewportDp) windowHeight - viewportDp else 0f
        val target = (nowMin - wakeMin) * StScale.dpPerMinute - viewportDp / 2f
        return target.coerceIn(0f, maxScroll)
    }

    /** 分钟 → 画布顶偏移：钳进聚焦窗后换算（窗外值钳到窗沿）。 */
    fun topOfDp(minutes: Int, wakeMin: Int, windowMinutes: Int): Float =
        (minutes - wakeMin).coerceIn(0, windowMinutes) * StScale.dpPerMinute

    /** 块高：跨午夜经 24:00 折返的时长换算，钳下限 blockMinHeightDp。 */
    fun heightOfDp(startMin: Int, endMin: Int): Float =
        (ScheduleDay.spanMinutes(startMin, endMin) * StScale.dpPerMinute)
            .coerceAtLeast(StScale.blockMinHeightDp)
}
