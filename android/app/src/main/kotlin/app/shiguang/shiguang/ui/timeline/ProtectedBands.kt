package app.shiguang.shiguang.ui.timeline

import app.shiguang.core.model.ScheduleBlock
import app.shiguang.core.model.ScheduleDay
import app.shiguang.core.model.ScheduleRules
import app.shiguang.core.tokens.StGesture

/** 派生保护区数据（§3.1/§6.5）：supply=true 🛡️ 留白缓冲 / false 🍃 自由流动区。 */
data class ProtectedBand(val startMin: Int, val endMin: Int, val supply: Boolean)

/**
 * 派生保护区推导（Dart _protectedBands 逐字镜像；派生渲染不入库 §11）：
 * 🛡️ 留白缓冲 = AI 块 span>BREATH_MIN(45) 后 BREATH_BUFFER_MIN(15) 呼吸位——对占用取
 * 差集（现实挤进缓冲只渲染自由部分）；🍃 自由流动区 = 占用+补给带之外空隙。
 * 三级绘制过滤：≥bandFullMin(30) 全渲染 / ≥bandIconMin(15) 仅图标 / <15 静默。
 * 纯逻辑 JVM 对拍件（F9 虚线框补实现随时间轴装配层）。
 */
object ProtectedBands {

    enum class RenderTier { FULL, ICON_ONLY, SILENT }

    fun derive(
        visibleBlocks: List<ScheduleBlock>,
        occupiedIntervals: List<Pair<Int, Int>>,
        windowStart: Int,
        windowEnd: Int,
    ): List<ProtectedBand> {
        val supply = mutableListOf<Pair<Int, Int>>()
        for (b in visibleBlocks) {
            if (b.source != ScheduleBlock.SOURCE_AI) continue
            val span = ScheduleDay.spanMinutes(b.startMin.toInt(), b.endMin.toInt())
            if (span <= ScheduleRules.BREATH_MIN) continue
            val s = b.startMin.toInt() + span
            supply.add(Pair(s, s + ScheduleRules.BREATH_BUFFER_MIN))
        }
        val out = mutableListOf<ProtectedBand>()
        // 补给带：对占用取差集后渲染（现实挤进缓冲时只渲染自由部分）
        for (g in ScheduleDay.gapsIn(windowStart, windowEnd, occupiedIntervals)) {
            for (s in supply) {
                val lo = maxOf(s.first, g.first)
                val hi = minOf(s.second, g.second)
                if (hi > lo) out.add(ProtectedBand(lo, hi, supply = true))
            }
        }
        // 自由流动区：占用 + 补给带之外的空隙
        val everything = occupiedIntervals + supply
        for (g in ScheduleDay.gapsIn(windowStart, windowEnd, everything)) {
            out.add(ProtectedBand(g.first, g.second, supply = false))
        }
        return out
    }

    /** 三级绘制过滤（§6.5，StGesture.bandFullMin/bandIconMin 逐字）。 */
    fun tierOf(band: ProtectedBand): RenderTier {
        val minutes = band.endMin - band.startMin
        return when {
            minutes >= StGesture.bandFullMin -> RenderTier.FULL
            minutes >= StGesture.bandIconMin -> RenderTier.ICON_ONLY
            else -> RenderTier.SILENT
        }
    }
}
