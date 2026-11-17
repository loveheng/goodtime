package app.shiguang.shiguang.ui.schedule

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.shiguang.core.model.ScheduleBlock
import app.shiguang.core.model.ScheduleDay
import app.shiguang.core.tokens.StScale
import app.shiguang.core.tokens.StUi
import app.shiguang.shiguang.ui.block.BlockStatus
import app.shiguang.shiguang.ui.component.ShiguangBottomSheet
import app.shiguang.shiguang.ui.theme.LocalStColors
import java.time.LocalDateTime

/**
 * 全局动作 sheet（ui-pages §3.6；§B 转正清单）：标题「遇到突发情况？」，三选项+
 * 两段式确认；「熔断优先于微调」一级直达。human 块与 pinned 硬行程永不动（和平条款），
 * 底部附注明示。清空/后移走 panic_clear（命令层原子执行），降档走 today_energy=low
 * + reflow_day 水流重算。确认后才收 sheet 执行（Dart confirmAndRun 序）。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun GlobalActionSheet(
    todayBlocks: List<ScheduleBlock>,
    now: LocalDateTime,
    vm: TodayViewModel,
    onDismiss: () -> Unit,
) {
    val palette = LocalStColors.current
    val nowMin = ScheduleDay.minutesOfDay(now)
    val remaining = todayBlocks.count {
        it.source == ScheduleBlock.SOURCE_AI && !it.pinned &&
            (it.status == BlockStatus.PROPOSED || it.status == BlockStatus.CONFIRMED) &&
            it.startMin > nowMin
    }
    // 两段式确认：pendingAction ∈ null/clear/push/energy（确认 AlertDialog 承载）
    var pendingAction by remember { mutableStateOf<String?>(null) }

    fun confirmAndRun(action: String) {
        pendingAction = null
        onDismiss() // 先收 sheet 再执行（Dart navigator.pop 序）
        when (action) {
            "clear" -> vm.panicClear("clear_remaining", nowMin)
            "push" -> vm.panicClear("push_2h", nowMin)
            "energy" -> vm.setEnergy("low")
        }
    }

    ShiguangBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.padding(start = StScale.insetXl.dp, end = StScale.insetXl.dp, top = StScale.insetSm.dp, bottom = StScale.insetXl.dp),
            verticalArrangement = Arrangement.spacedBy(StScale.insetXs.dp),
        ) {
            Text("遇到突发情况？", style = MaterialTheme.typography.titleLarge)
            ListItem(
                modifier = Modifier.clickable(enabled = remaining > 0) { pendingAction = "clear" },
                headlineContent = { Text("今天下午全清空（剩余计划退回清单）") },
                supportingContent = {
                    Text(
                        "$remaining 项未开始的 AI 安排",
                        color = Color(palette.textSecondary),
                    )
                },
                leadingContent = { Text("☕", fontSize = StUi.sheetGlyphSp.sp) },
            )
            ListItem(
                modifier = Modifier.clickable(enabled = remaining > 0) { pendingAction = "push" },
                headlineContent = { Text("整体往后推 2 小时") },
                supportingContent = {
                    Text(
                        "$remaining 项未开始的 AI 安排",
                        color = Color(palette.textSecondary),
                    )
                },
                leadingContent = { Text("⏳", fontSize = StUi.sheetGlyphSp.sp) },
            )
            ListItem(
                modifier = Modifier.clickable { pendingAction = "energy" },
                headlineContent = { Text("今天精力见底，全部切换为 5 分钟启动版") },
                supportingContent = {
                    Text(
                        "按低电量重算今天：压缩火种、溢出融化",
                        color = Color(palette.textSecondary),
                    )
                },
                leadingContent = { Text("🪫", fontSize = StUi.sheetGlyphSp.sp) },
            )
            Text(
                "你的固定日程和自己排的事项不会被改动。",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = StScale.insetXs.dp),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodySmall,
                color = Color(palette.textSecondary),
            )
        }
    }
    // 两段式确认 AlertDialog（title/impact 逐字 = §B 转正清单）
    pendingAction?.let { action ->
        AlertDialog(
            onDismissRequest = { pendingAction = null },
            title = {
                Text(
                    when (action) {
                        "clear" -> "全清空今天剩余安排？"
                        "push" -> "整体往后推 2 小时？"
                        else -> "切换为 5 分钟启动版？"
                    },
                )
            },
            text = {
                Text(
                    when (action) {
                        "clear" -> "今天剩余的 $remaining 项 AI 安排将全部放回清单待安排（无痕、零心理负债），随时可以让 AI 重新排。"
                        "push" -> "今天剩余的 $remaining 项 AI 安排将整体后移 2 小时；若撞上你的手动安排或固定占用，会整单拒绝不做半截。"
                        else -> "今天将按低电量重算：AI 安排压缩为 5 分钟启动版或无痕融化，核心尽量保住；固定日程不动。"
                    },
                )
            },
            confirmButton = {
                Button(onClick = { confirmAndRun(action) }) { Text("确认执行") }
            },
            dismissButton = {
                TextButton(onClick = { pendingAction = null }) { Text("取消") }
            },
        )
    }
}
