package app.shiguang.shiguang.ui.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.shiguang.core.tokens.StScale
import app.shiguang.shiguang.ui.theme.LocalStColors

/**
 * 守卫态横幅族（behavior/schedule_page.md §G 转正清单）：放工守卫（sleepAdj−120 ≤
 * now < sleepAdj 2h 窗字面量，转完成面陈述+明日概要）与 Clean Slate 轻装重启条。
 * 两横幅互斥（守卫优先）；快记 FAB 不撤（壳常驻，快记永不关门）。
 */

/** 放工守卫静默视图（§3.7 八轮）：完成面陈述 + 明日概要（块数+最早一块）。 */
@Composable
fun WindDownBanner(
    data: ScheduleDayData,
    vm: TodayViewModel,
    modifier: Modifier = Modifier,
) {
    val palette = LocalStColors.current
    val (doneCount, doneMinutes) = vm.doneStats(data)
    // 明日概要（Dart FutureBuilder 镜像：DayData 变化重拉；null=未达不渲染）
    var summary by remember(data) { mutableStateOf<TomorrowSummary?>(null) }
    LaunchedEffect(data) {
        summary = runCatching { vm.tomorrowSummary() }.getOrNull()
    }
    Column(
        modifier = modifier
            .padding(start = StScale.insetLg.dp, end = StScale.insetLg.dp, top = StScale.insetXs.dp, bottom = StScale.insetXs.dp)
            .fillMaxWidth()
            .background(Color(palette.freeFlowBg), RoundedCornerShape(StScale.radiusCard.dp))
            .padding(StScale.insetMd.dp),
    ) {
        Text(
            "放工了：今天完成 $doneCount 项 / $doneMinutes 分钟。",
            style = MaterialTheme.typography.bodySmall,
        )
        summary?.let { t ->
            Text(
                if (t.count == 0) {
                    "明天还没有安排——想到什么随时记，快记条不打烊。"
                } else {
                    "明天有 ${t.count} 项安排，最早 ${t.firstTime}「${t.firstLabel}」。"
                },
                modifier = Modifier.padding(top = StScale.insetXs.dp),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** Clean Slate 静默视图条（§3.7/§9，界面文案「轻装重启」，词汇表 §0.4）。 */
@Composable
fun CleanSlateBanner(modifier: Modifier = Modifier) {
    Text(
        "轻装重启：生活有时需要喘息，过去的事已安全归档，今天从一件小事开始。",
        modifier = modifier
            .padding(start = StScale.insetLg.dp, end = StScale.insetLg.dp, top = StScale.insetXs.dp, bottom = StScale.insetXs.dp)
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(StScale.radiusCard.dp))
            .padding(StScale.insetMd.dp),
        style = MaterialTheme.typography.bodySmall,
    )
}
