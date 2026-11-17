package app.shiguang.shiguang.ui.timeline

import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import app.shiguang.core.model.ScheduleBlock
import app.shiguang.core.model.ScheduleDay
import app.shiguang.core.tokens.StScale
import app.shiguang.core.tokens.StUi
import app.shiguang.shiguang.ui.block.BlockCard
import app.shiguang.shiguang.ui.block.GestureBlock
import app.shiguang.shiguang.ui.schedule.ScheduleDayData
import app.shiguang.shiguang.ui.theme.ShiguangMotion
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDateTime
import kotlin.math.roundToInt

/** 块时间区间重叠判定（碰撞提示 F8 口径；跨午夜经 24:00 折返）。 */
fun overlaps(a: ScheduleBlock, b: ScheduleBlock): Boolean {
    val aEnd = if (a.endMin <= a.startMin) a.endMin + 1440 else a.endMin
    val bEnd = if (b.endMin <= b.startMin) b.endMin + 1440 else b.endMin
    return a.startMin < bEnd && b.startMin < aEnd
}

/**
 * 时间轴（P4③主干页，behavior/schedule_page.md §F 已过目转正）：竖轴聚焦窗 1min=1dp、
 * 小时网格/保护区/固定带三层（TimelineLayers）、当前时刻线（F10 补实现，块之下=z 序
 * §3.1 块最高）、车道等宽分栏挂载 GestureBlock+BlockCard、空槽点按 15min 吸附（P5 平移
 * 现状）。goNow 首帧定位（丢失点 #3 公式）+ [goNowTick] 外部「回到现在」信号（>0 时
 * animateTo 250ms easeOut）。Z 序 §3.2：本件=页面内容 Z=0。
 */
@Composable
fun Timeline(
    data: ScheduleDayData,
    now: LocalDateTime,
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
    goNowTick: Int = 0,
    onBlockTap: (ScheduleBlock) -> Unit = {},
    onBackgroundTap: (slotStartMin: Int, past: Boolean) -> Unit = { _, _ -> },
    onDegrade: (ScheduleBlock) -> Unit = {},
    onMelt: (ScheduleBlock) -> Unit = {},
    onSwap: (ScheduleBlock) -> Unit = {},
) {
    val scroll = scrollState
    val windowMinutes = data.sleepAdj - data.wake
    val windowHeightDp = TimelineMath.windowHeightDp(windowMinutes).dp
    val nowMin = ScheduleDay.minutesOfDay(now)
    val density = LocalDensity.current
    var viewportHeightPx by remember { mutableIntStateOf(0) }

    // 首帧定位（今日 goNow，丢失点 #3 公式）：等滚动域出量程再定位；[goNowTick]>0 动画定位
    LaunchedEffect(data.date, data.isToday, goNowTick) {
        if (!data.isToday) return@LaunchedEffect
        snapshotFlow { scroll.maxValue }.filter { it > 0 }.first()
        val viewportDp = with(density) { viewportHeightPx.toDp().value }
        val target = TimelineMath.goNowOffsetDp(nowMin, data.wake, windowMinutes, viewportDp)
        if (goNowTick == 0) {
            scroll.scrollTo(target.roundToInt())
        } else {
            scroll.animateScrollTo(target.roundToInt(), tween(durationMillis = 250, easing = ShiguangMotion.easeOut))
        }
    }

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .onSizeChanged { viewportHeightPx = it.height },
    ) {
        val timelineWidthDp = (maxWidth - StUi.timelineGutterDp.dp - StScale.insetLg.dp).value
        val canvasWidth = maxWidth - StUi.timelineGutterDp.dp
        Box(
            Modifier
                .fillMaxWidth()
                .verticalScroll(scroll)
                .padding(bottom = StScale.fabClearanceDp.dp)
                .height(windowHeightDp)
                .pointerInput(data.wake, data.sleepAdj, data.isToday) {
                    detectTapGestures { offset ->
                        val yDp = with(density) { offset.y.toDp().value }
                        val minute = data.wake + (yDp / StScale.dpPerMinute).roundToInt()
                        // 15min 吸附钳 [wake, sleepAdj−15]（P5 平移现状）；过去时段 → 逆向记账
                        val snapped = ((minute / 15) * 15).coerceIn(data.wake, data.sleepAdj - 15)
                        onBackgroundTap(snapped, data.isToday && snapped < nowMin)
                    }
                },
        ) {
            // ① 小时网格
            HourGridLayer(data, windowMinutes)
            // ② 派生保护区（F9 虚线框）③ 固定占用带
            ProtectedBandLayer(data, windowMinutes, canvasWidth)
            FixedBandLayer(data, windowMinutes, canvasWidth)
            // ④ 当前时刻线（F10 补实现：主题色 1dp 细线不用红；窗外不渲染；块之下=z §3.1）
            if (nowMin >= data.wake && nowMin < data.sleepAdj) {
                Box(
                    Modifier
                        .offset(y = TimelineMath.topOfDp(nowMin, data.wake, windowMinutes).dp)
                        .fillMaxWidth()
                        .height(StUi.dividerWidthDp.dp)
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
            // ⑤ 块（车道等宽分栏 N≥2；GestureBlock+BlockCard 挂载；时间轴内 z 最高）
            val slots = remember(data.blocks, timelineWidthDp) {
                LaneLayout.layout(data.visibleBlocks, timelineWidthDp)
            }
            for (slot in slots) {
                val b = slot.block
                val top = TimelineMath.topOfDp(b.startMin.toInt(), data.wake, windowMinutes)
                val heightDp = TimelineMath.heightOfDp(b.startMin.toInt(), b.endMin.toInt())
                val colliding = remember(data.blocks) {
                    data.visibleBlocks.any { o -> o.id != b.id && overlaps(b, o) }
                }
                Box(
                    Modifier
                        .offset(x = StUi.timelineGutterDp.dp + (slot.lane * slot.laneWidthDp).dp, y = top.dp)
                        .width((slot.laneWidthDp - 2).dp)
                        .height(heightDp.dp),
                ) {
                    GestureBlock(
                        block = b,
                        onDegrade = { onDegrade(b) },
                        onMelt = { onMelt(b) },
                        onSwap = { onSwap(b) },
                    ) {
                        BlockCard(
                            block = b,
                            plan = b.planId?.let { data.plans[it] },
                            energyLow = data.energy == "low",
                            roamDay = data.roam,
                            voucherBadge = b.id?.let { id ->
                                (data.artifactsByBlock[id]?.get("badge") as? JsonPrimitive)
                                    ?.takeIf { it.isString }?.content
                            },
                            isColliding = colliding,
                            modifier = Modifier.fillMaxWidth(),
                            onTap = { onBlockTap(b) },
                        )
                    }
                }
            }
        }
    }
}
