package app.shiguang.shiguang.ui.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.shiguang.core.model.ScheduleBlock
import app.shiguang.core.model.ScheduleDay
import app.shiguang.core.tokens.StScale
import app.shiguang.core.tokens.StUi
import app.shiguang.shiguang.ui.theme.LocalStColors
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.time.LocalDate

/**
 * 周视图（ui-pages §4 只读；schedule_page.md §H+实现级微度量已转正）：只读 7 列
 * 网格（列时间窗同日视图聚焦窗）+ 块紧凑渲染（来源着色/单行截断/无手势）+ 点块跳
 * 日视图。数据经 VM 读面透传（anchor 变化重取，Dart FutureBuilder 同构）；
 * week_start/wake/sleep_adj/days 投影=Kotlin weekOverview 契约（23 测钉）。
 */
@Composable
fun WeekView(
    vm: TodayViewModel,
    anchor: LocalDate,
    onPick: (LocalDate) -> Unit,
    onWeekChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var data by remember(anchor) { mutableStateOf<JsonObject?>(null) }
    LaunchedEffect(anchor) {
        data = null
        data = runCatching { vm.weekOverview(anchor) }.getOrNull()
    }
    val d = data ?: Column(
        modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        CircularProgressIndicator()
        Spacer(Modifier.weight(1f))
    }.let { return }
    val palette = LocalStColors.current
    val wake = (d["wake"] as? JsonPrimitive)?.intOrNull ?: 420
    val sleepAdj = (d["sleep_adj"] as? JsonPrimitive)?.intOrNull ?: 1380
    val windowMin = sleepAdj - wake
    val weekStart = (d["week_start"] as? JsonPrimitive)?.contentOrNull
        ?.let { ScheduleDay.tryParseIsoDate(it) } ?: anchor
    val weekEnd = weekStart.plusDays(6)
    val days = (d["days"] as? JsonArray)?.filterIsInstance<JsonObject>() ?: emptyList()
    val todayIso = ScheduleDay.isoDate(ScheduleDay.scheduleDayOf(vm.now.value, wake))

    Column(modifier.fillMaxSize()) {
        // 头部：‹ M月D日 – M月D日 ›（翻周仅 ‹› 钮）
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onWeekChange(-1) }) {
                Icon(Icons.Filled.ChevronLeft, contentDescription = "上一周")
            }
            Text(
                "${weekStart.monthValue}月${weekStart.dayOfMonth}日 – ${weekEnd.monthValue}月${weekEnd.dayOfMonth}日",
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleMedium,
            )
            IconButton(onClick = { onWeekChange(1) }) {
                Icon(Icons.Filled.ChevronRight, contentDescription = "下一周")
            }
        }
        // 双滚容器：外层竖滚（尾留白 fabClearanceDp）、内层横滚（总宽=112×7）
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(bottom = StScale.fabClearanceDp.dp),
        ) {
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                Column {
                    // 列头行（行高=28dp 追加量）：D日 周X（今日 primary+bold）+例外日 🏖
                    Row(Modifier.height(StUi.weekColumnHeaderExtraDp.dp)) {
                        for (day in days) {
                            val date = (day["date"] as? JsonPrimitive)?.contentOrNull
                                ?.let { ScheduleDay.tryParseIsoDate(it) }
                            val weekday = (day["weekday"] as? JsonPrimitive)?.contentOrNull ?: ""
                            val exception = day["exception"] is JsonNull
                            val isToday = date != null && ScheduleDay.isoDate(date) == todayIso
                            Text(
                                "${date?.dayOfMonth ?: ""}日 周$weekday${if (exception) " 🏖" else ""}",
                                modifier = Modifier.width(WEEK_COLUMN_WIDTH.dp),
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isToday) MaterialTheme.colorScheme.primary else Color(palette.textSecondary),
                                fontWeight = if (isToday) FontWeight.Bold else null,
                            )
                        }
                    }
                    // 列体行：7 列同窗 Stack
                    Row {
                        for (day in days) {
                            WeekColumn(day, wake, windowMin, todayIso, onPick)
                        }
                    }
                }
            }
        }
    }
}

/** 周视图列宽（§H 清单转正值 112dp×7 列）。 */
private const val WEEK_COLUMN_WIDTH = 112f

/** 周视图单列：小时分隔线（无时钟 label）+ fixed 纯色带 + 紧凑块（车道 greedy）。 */
@Composable
private fun WeekColumn(
    day: JsonObject,
    wake: Int,
    windowMin: Int,
    todayIso: String,
    onPick: (LocalDate) -> Unit,
) {
    val palette = LocalStColors.current
    val date = (day["date"] as? JsonPrimitive)?.contentOrNull
        ?.let { ScheduleDay.tryParseIsoDate(it) } ?: return
    val blocks = (day["blocks"] as? JsonArray)?.filterIsInstance<JsonObject>() ?: emptyList()
    val fixedOnDate = (day["fixed_on_date"] as? JsonArray)?.filterIsInstance<JsonObject>() ?: emptyList()
    val fixedSpillover = (day["fixed_spillover"] as? JsonArray)?.filterIsInstance<JsonObject>() ?: emptyList()

    fun topOf(minutes: Int): Int = ((minutes - wake).coerceIn(0, windowMin) * StScale.dpPerMinute).toInt()
    fun heightOf(startMin: Int, endMin: Int): Int {
        val h = ScheduleDay.spanMinutes(startMin, endMin) * StScale.dpPerMinute
        return if (h < StUi.weekBlockMinHeightDp) StUi.weekBlockMinHeightDp.toInt() else h.toInt()
    }

    Box(
        Modifier
            .width(WEEK_COLUMN_WIDTH.dp)
            .height((windowMin * StScale.dpPerMinute).dp),
    ) {
        // 小时分隔线：自 wake 整点后第一个整点步进 60 至窗尾（1dp 无 label）
        var m = (wake / 60 + 1) * 60
        while (m < wake + windowMin) {
            Box(
                Modifier
                    .offset(y = topOf(m).dp)
                    .fillMaxWidth()
                    .height(StUi.dividerWidthDp.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )
            m += 60
        }
        // fixed 带纯色无圆角无 label（onDate top 钳窗内、spillover top=0）
        for (s in fixedOnDate) {
            val st = (s["start_min"] as? JsonPrimitive)?.intOrNull ?: 0
            val en = (s["end_min"] as? JsonPrimitive)?.intOrNull ?: 0
            Box(
                Modifier
                    .offset(y = topOf(st).dp)
                    .fillMaxWidth()
                    .height(heightOf(st, en).dp)
                    .background(Color(palette.fixedSlotBg)),
            )
        }
        for (s in fixedSpillover) {
            val en = (s["end_min"] as? JsonPrimitive)?.intOrNull ?: 0
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(heightOf(0, en).dp)
                    .background(Color(palette.fixedSlotBg)),
            )
        }
        // 紧凑块（车道 greedy 列内独立实现：laneWidth=列宽/最大车道数，laneWidth−1 留缝）
        for (item in layoutBlocks(blocks, WEEK_COLUMN_WIDTH)) {
            val b = item.block
            val st = (b["start_min"] as? JsonPrimitive)?.intOrNull ?: 0
            val en = (b["end_min"] as? JsonPrimitive)?.intOrNull ?: 0
            val isProposed = (b["status"] as? JsonPrimitive)?.contentOrNull == ScheduleBlock.STATUS_PROPOSED
            val isCelebration = (b["is_celebration"] as? JsonPrimitive)?.booleanOrNull == true
            val isHuman = (b["source"] as? JsonPrimitive)?.contentOrNull == ScheduleBlock.SOURCE_HUMAN
            Box(
                Modifier
                    .offset(x = (item.lane * item.laneWidth).dp, y = topOf(st).dp)
                    .width((item.laneWidth - StUi.weekLaneGapDp).dp)
                    .height(heightOf(st, en).dp)
                    .padding(StUi.weekBlockInsetDp.dp)
                    .background(
                        if (isHuman) Color(palette.humanBlockBg) else MaterialTheme.colorScheme.primaryContainer,
                        RoundedCornerShape(StUi.weekBlockCornerRadiusDp.dp),
                    )
                    .then(
                        if (isProposed) {
                            Modifier.border(
                                StUi.dividerWidthDp.dp,
                                MaterialTheme.colorScheme.primary,
                                RoundedCornerShape(StUi.weekBlockCornerRadiusDp.dp),
                            )
                        } else if (isCelebration) {
                            Modifier.border(
                                StUi.dividerWidthDp.dp,
                                Color(palette.celebrationStroke),
                                RoundedCornerShape(StUi.weekBlockCornerRadiusDp.dp),
                            )
                        } else {
                            Modifier
                        },
                    )
                    .clickable { onPick(date) }
                    .padding(StUi.weekBlockPadDp.dp),
            ) {
                Text(
                    (b["effective_label"] as? JsonPrimitive)?.contentOrNull ?: "(未命名)",
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = StUi.weekBlockTextSp.sp,
                    lineHeight = StUi.weekBlockTextSp.sp.times(StUi.weekBlockLineHeight),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

/** 列内车道布局（Dart _layout 逐字镜像：简单 greedy 单数组 laneEnds，非日视图簇切分）。 */
internal data class WeekLaneSlot(val block: JsonObject, val lane: Int, val laneWidth: Float)

internal fun layoutBlocks(blocks: List<JsonObject>, width: Float): List<WeekLaneSlot> {
    val sorted = blocks.sortedBy { (it["start_min"] as? JsonPrimitive)?.intOrNull ?: 0 }
    val laneEnds = mutableListOf<Int>()
    val lanes = HashMap<String, Int>()
    var maxLane = 1
    for (b in sorted) {
        val start = (b["start_min"] as? JsonPrimitive)?.intOrNull ?: 0
        val end = (b["end_min"] as? JsonPrimitive)?.intOrNull ?: 0
        val endAbs = if (end < start) end + 1440 else end
        var lane = 0
        while (lane < laneEnds.size && laneEnds[lane] > start) lane++
        if (lane == laneEnds.size) laneEnds.add(endAbs) else laneEnds[lane] = endAbs
        if (lane + 1 > maxLane) maxLane = lane + 1
        (b["id"] as? JsonPrimitive)?.contentOrNull?.let { lanes[it] = lane }
    }
    return sorted.map { b ->
        WeekLaneSlot(
            block = b,
            lane = (b["id"] as? JsonPrimitive)?.contentOrNull?.let { lanes[it] } ?: 0,
            laneWidth = width / maxLane,
        )
    }
}
