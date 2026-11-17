package app.shiguang.shiguang.ui.schedule

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.shiguang.core.model.ScheduleBlock
import app.shiguang.core.model.ScheduleDay
import app.shiguang.core.tokens.StScale
import app.shiguang.shiguang.ui.theme.LocalStColors

/**
 * 昨日遗留区（ui-pages §3.2；behavior/schedule_page.md §G 转正清单）：昨日 missed
 * 逐条三键——补勾（自动带补记标）/顺延今日/退回愿望池；missedAccent 米黄卡（绝不红）。
 * 守卫态与 Clean Slate 保护期整区淡出（SchedulePage 门控）；命令经 VM（D15），
 * 失败=事件 3s、成功=revision 驱动刷新（Dart _act 同构）。
 */
@Composable
fun LeftoverSection(
    data: ScheduleDayData,
    vm: TodayViewModel,
    modifier: Modifier = Modifier,
) {
    val palette = LocalStColors.current
    Card(
        modifier = modifier
            .padding(start = StScale.insetLg.dp, end = StScale.insetLg.dp, top = StScale.insetXs.dp, bottom = StScale.insetXs.dp)
            .fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(palette.missedAccent)),
        shape = RoundedCornerShape(StScale.radiusCard.dp),
    ) {
        Column(
            Modifier.padding(StScale.insetMd.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(StScale.insetXs.dp),
        ) {
            Text(
                "昨日遗留 · ${data.leftovers.size} 条",
                style = MaterialTheme.typography.titleSmall,
            )
            for (b in data.leftovers) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.width(StScale.insetXs.dp))
                    Text(
                        "${labelOf(b, data)} · ${ScheduleDay.clockOf(b.startMin.toInt())}–${ScheduleDay.clockOf(b.endMin.toInt())}",
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = { vm.tickBlock(b) }) { Text("补勾") }
                    TextButton(onClick = { vm.postponeBlock(b, ScheduleDay.isoDate(data.date)) }) { Text("顺延今日") }
                    TextButton(onClick = { vm.meltBlock(b) }) { Text("退回愿望池") }
                }
            }
        }
    }
}

/** 块标签解析（同块浮层口径 label→plan.title→(未命名)/(计划)）。 */
internal fun labelOf(b: ScheduleBlock, data: ScheduleDayData): String =
    b.label ?: (b.planId?.let { data.plans[it]?.title ?: "(计划)" } ?: "(未命名)")
