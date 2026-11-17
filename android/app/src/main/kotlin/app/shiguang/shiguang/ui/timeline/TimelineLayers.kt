package app.shiguang.shiguang.ui.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.shiguang.core.model.ScheduleDay
import app.shiguang.core.tokens.StColorsPalette
import app.shiguang.core.tokens.StUi
import app.shiguang.shiguang.ui.theme.LocalStColors
import app.shiguang.core.tokens.StScale
import app.shiguang.shiguang.ui.schedule.ScheduleDayData

/**
 * 时间轴底层三层（P4③；behavior/schedule_page.md §F）：小时网格 / 派生保护区
 * 三级渲染（F9 虚线框补实现）/ 固定占用带。全部 offset 定位在画布坐标系
 * （x=52dp gutter 右侧起；块层 z 最高见 Timeline）。
 */

/** ① 小时网格：整点自 wake 后首整点起、步进 60min——gutter 时钟 + 1dp 分隔线。 */
@Composable
internal fun HourGridLayer(data: ScheduleDayData, windowMinutes: Int) {
    val palette = LocalStColors.current
    var m = (data.wake / 60 + 1) * 60
    while (m < data.sleepAdj) {
        val top = TimelineMath.topOfDp(m, data.wake, windowMinutes)
        Row(
            Modifier.offset(y = top.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                ScheduleDay.clockOf(m),
                modifier = Modifier.width(StUi.timelineGutterDp.dp).padding(start = StScale.insetXs.dp),
                style = MaterialTheme.typography.labelSmall,
                color = Color(palette.textSecondary),
            )
            HorizontalDivider(Modifier.weight(1f), thickness = StUi.dividerWidthDp.dp)
        }
        m += 60
    }
}

/** ② 派生保护区层（§6.5 三级绘制过滤；F9 虚线框补实现——supply/freeFlow 统一虚线）。 */
@Composable
internal fun ProtectedBandLayer(data: ScheduleDayData, windowMinutes: Int, canvasWidth: androidx.compose.ui.unit.Dp) {
    val palette = LocalStColors.current
    for (band in ProtectedBands.derive(data.visibleBlocks, data.occupiedIntervals(), data.wake, data.sleepAdj)) {
        val tier = ProtectedBands.tierOf(band)
        if (tier == ProtectedBands.RenderTier.SILENT) continue
        val top = TimelineMath.topOfDp(band.startMin, data.wake, windowMinutes)
        val heightDp = ((band.endMin - band.startMin) * StScale.dpPerMinute).dp
        val bg = if (band.supply) palette.supplyBandBg else palette.freeFlowBg
        val stroke = if (band.supply) palette.supplyBandStroke else palette.textSecondary
        Box(
            Modifier
                .offset(x = StUi.timelineGutterDp.dp, y = top.dp)
                .width(canvasWidth)
                .height(heightDp)
                .drawBehind {
                    drawRoundRect(
                        color = Color(bg),
                        cornerRadius = CornerRadius(StScale.radiusBlock.dp.toPx()),
                    )
                    // 虚线密度档 6/4dp 已拍板转正（2026-11-16；Dart 基线为实线无虚线
                    // 可对拍——F9 虚线框为规范驱动原生新增，密度档属人工视觉裁决）
                    drawRoundRect(
                        color = Color(stroke),
                        cornerRadius = CornerRadius(StScale.radiusBlock.dp.toPx()),
                        style = Stroke(
                            width = StUi.supplyLineWidthDp.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(
                                floatArrayOf(StUi.bandDashLenDp.dp.toPx(), StUi.bandDashGapDp.dp.toPx()),
                            ),
                        ),
                    )
                }
                .padding(start = StScale.insetSm.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            when (tier) {
                ProtectedBands.RenderTier.FULL -> Text(
                    "${if (band.supply) "🛡️ 留白缓冲" else "🍃 自由流动区"} ${band.endMin - band.startMin} 分钟",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(palette.textSecondary),
                )
                ProtectedBands.RenderTier.ICON_ONLY ->
                    Text(if (band.supply) "🛡️" else "🍃", fontSize = StUi.compactTextSp.sp)
                ProtectedBands.RenderTier.SILENT -> {}
            }
        }
    }
}

/** ③ 固定占用带层（当天开始段 + 前夜溢出段；Dart _fixedBands 镜像）。 */
@Composable
internal fun FixedBandLayer(data: ScheduleDayData, windowMinutes: Int, canvasWidth: androidx.compose.ui.unit.Dp) {
    val palette = LocalStColors.current
    val fixedBands = buildList {
        for (s in data.fixedOnDate) {
            val end = if (ScheduleDay.crossesMidnight(s.startMin, s.endMin)) 1440 else s.endMin
            if (end > data.wake) add(Triple(maxOf(s.startMin, data.wake), end, s.name))
        }
        for (s in data.fixedSpillover) {
            if (s.endMin > data.wake) add(Triple(data.wake, s.endMin, s.name))
        }
    }
    for ((start, end, name) in fixedBands) {
        val top = TimelineMath.topOfDp(start, data.wake, windowMinutes)
        val heightDp = TimelineMath.heightOfDp(start, end)
        Box(
            Modifier
                .offset(x = StUi.timelineGutterDp.dp, y = top.dp)
                .width(canvasWidth)
                .height(heightDp.dp)
                .background(Color(palette.fixedSlotBg), RoundedCornerShape(StScale.radiusBlock.dp))
                .padding(start = StScale.insetSm.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall,
                color = Color(palette.textSecondary),
            )
        }
    }
}
