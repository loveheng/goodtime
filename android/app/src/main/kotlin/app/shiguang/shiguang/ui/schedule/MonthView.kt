package app.shiguang.shiguang.ui.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
 * 简易月历（ui-pages §4 只读；schedule_page.md §I+§H.1 实现级微度量已转正）：
 * 只读月网格 + 格子摘要渲染（状态点阵 P7 裁决全实心 + 🔥/🎂 微标 + 例外日标签）+
 * 点格跳日视图（过去的日程串起来）。翻月钮保留几号钳月末（MonthGrid 纯函数）。
 */
@Composable
fun MonthView(
    vm: TodayViewModel,
    anchor: LocalDate,
    onPick: (LocalDate) -> Unit,
    onMonthChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var data by remember(anchor.year, anchor.month) { mutableStateOf<JsonObject?>(null) }
    LaunchedEffect(anchor.year, anchor.month) {
        data = null
        data = runCatching { vm.monthOverview(anchor.year, anchor.monthValue) }.getOrNull()
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
    val days = (d["days"] as? JsonArray)?.filterIsInstance<JsonObject>() ?: emptyList()
    val byDate = days.associateBy { (it["date"] as? JsonPrimitive)?.contentOrNull }
    val todayIso = (d["today"] as? JsonPrimitive)?.contentOrNull ?: ""
    val first = LocalDate.of(anchor.year, anchor.month, 1)
    val leadingBlanks = MonthGrid.leadingBlanks(first)
    val monthLen = first.lengthOfMonth()

    Column(modifier.fillMaxSize()) {
        // 头部：‹ YYYY年M月 ›（翻月钮保留几号钳月末）
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onMonthChange(-1) }) {
                Icon(Icons.Filled.ChevronLeft, contentDescription = "上一月")
            }
            Text(
                "${first.year}年${first.monthValue}月",
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleMedium,
            )
            IconButton(onClick = { onMonthChange(1) }) {
                Icon(Icons.Filled.ChevronRight, contentDescription = "下一月")
            }
        }
        // 周头（周一起始）
        Row(Modifier.padding(horizontal = StScale.insetSm.dp)) {
            for (w in listOf("一", "二", "三", "四", "五", "六", "日")) {
                Text(
                    w,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(palette.textSecondary),
                )
            }
        }
        HorizontalDivider(thickness = StUi.dividerWidthDp.dp)
        // 月网格：前导空位 + 日期格（只读摘要）
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = StScale.insetXs.dp, top = StScale.insetXs.dp, end = StScale.insetXs.dp, bottom = StScale.fabClearanceDp.dp),
        ) {
            var cellIndex = 0
            val totalCells = leadingBlanks + monthLen
            val rows = (totalCells + 6) / 7
            for (row in 0 until rows) {
                Row {
                    for (col in 0 until 7) {
                        val idx = row * 7 + col
                        if (idx < leadingBlanks) {
                            Box(
                                Modifier
                                    .weight(1f)
                                    .aspectRatio(StUi.monthCellAspect),
                            )
                        } else if (idx < totalCells) {
                            val dayNum = idx - leadingBlanks + 1
                            val date = first.withDayOfMonth(dayNum)
                            CalendarCell(
                                date = date,
                                data = byDate[ScheduleDay.isoDate(date)],
                                todayIso = todayIso,
                                onPick = onPick,
                                modifier = Modifier
                                    .weight(1f)
                                    .aspectRatio(StUi.monthCellAspect),
                            )
                        } else {
                            Spacer(Modifier.weight(1f))
                        }
                        cellIndex++
                    }
                }
            }
        }
    }
}

/** 月历单格：日期号（今日描边圈）+ 状态点阵（P7 全实心）+ 微标 + 例外标签。 */
@Composable
private fun CalendarCell(
    date: LocalDate,
    data: JsonObject?,
    todayIso: String,
    onPick: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalStColors.current
    val iso = ScheduleDay.isoDate(date)
    val isToday = iso == todayIso
    val dots = buildList {
        if (data != null) {
            repeat((data["done"] as? JsonPrimitive)?.intOrNull ?: 0) { add(Color(palette.safelineOn)) }
            repeat((data["missed"] as? JsonPrimitive)?.intOrNull ?: 0) { add(Color(palette.safelineOff)) }
            repeat((data["proposed"] as? JsonPrimitive)?.intOrNull ?: 0) { add(MaterialTheme.colorScheme.primary) }
            repeat((data["human"] as? JsonPrimitive)?.intOrNull ?: 0) { add(Color(palette.textSecondary)) }
        }
    }
    val shown = dots.take(4)
    val rest = dots.size - shown.size
    val exception = (data?.get("exception") as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
    Column(
        modifier
            .padding(StUi.monthCellMarginDp.dp)
            .then(
                if (isToday) {
                    Modifier.border(
                        StUi.monthRingWidthDp.dp,
                        MaterialTheme.colorScheme.primary,
                        RoundedCornerShape(StScale.radiusBlock.dp),
                    )
                } else {
                    Modifier
                },
            )
            .clickable { onPick(date) }
            .padding(StUi.monthCellPadDp.dp),
        verticalArrangement = Arrangement.Top,
    ) {
        Text(
            "${date.dayOfMonth}",
            style = MaterialTheme.typography.labelMedium,
            color = when {
                isToday -> MaterialTheme.colorScheme.primary
                data == null -> Color(palette.textSecondary)
                else -> Color.Unspecified
            },
            fontWeight = if (isToday) FontWeight.Bold else null,
        )
        if (dots.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(StUi.monthDotSpacingDp.dp)) {
                for (c in shown) {
                    Box(
                        Modifier
                            .size(StUi.monthDotSizeDp.dp)
                            .background(c, CircleShape),
                    )
                }
                if (rest > 0) {
                    Text(
                        "+$rest",
                        fontSize = StUi.monthSmallTextSp.sp,
                        lineHeight = StUi.monthSmallTextSp.sp.times(1.2f),
                    )
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(StUi.monthDotSpacingDp.dp)) {
            if ((data?.get("spark_done") as? JsonPrimitive)?.booleanOrNull == true) {
                Text("🔥", fontSize = StUi.monthGlyphSp.sp, lineHeight = StUi.monthGlyphSp.sp.times(1.2f))
            }
            if ((data?.get("celebration") as? JsonPrimitive)?.booleanOrNull == true) {
                Text("🎂", fontSize = StUi.monthGlyphSp.sp, lineHeight = StUi.monthGlyphSp.sp.times(1.2f))
            }
        }
        if (exception != null) {
            Text(
                exception,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = StUi.monthSmallTextSp.sp,
                lineHeight = StUi.monthSmallTextSp.sp.times(1.2f),
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}

/** 月历网格纯函数（JVM 对拍面）：前导空位与翻月钳制。 */
object MonthGrid {

    /** 前导空位=首日 weekday−1（周一起始，Dart leadingBlanks 镜像）。 */
    fun leadingBlanks(firstOfMonth: LocalDate): Int = firstOfMonth.dayOfWeek.value - 1

    /**
     * 翻月保留几号钳月末（Dart _shiftMonth 逐字镜像）：从原 day 对目标月末 clamp
     * ——非 plusMonths 链式钳制（1/31 → 2 月=28 → 3 月=31，链式会丢成 3/28）。
     */
    fun shiftMonthPreservingDay(anchor: LocalDate, delta: Int): LocalDate {
        val target = anchor.plusMonths(delta.toLong())
        val monthLen = LocalDate.of(target.year, target.month, 1).lengthOfMonth()
        return LocalDate.of(target.year, target.month, anchor.dayOfMonth.coerceAtMost(monthLen))
    }
}
