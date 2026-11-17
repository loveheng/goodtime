package app.shiguang.shiguang.ui.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import app.shiguang.core.model.ScheduleBlock
import app.shiguang.core.model.ScheduleDay
import app.shiguang.core.tokens.StScale
import app.shiguang.core.tokens.StUi
import app.shiguang.shiguang.ui.theme.LocalStColors
import kotlinx.coroutines.launch

/**
 * 分级确认卡（ui-pages §3.4 拍板：页内 sticky 卡片+两态收缩，防批量盲签；§E 转正清单）：
 * 火种突出（为什么排在这），其余折叠「其余 N 项日常推进」；收缩=44dp 吸顶胶囊
 * 保留一键确认快速通道。展开/坍缩由父级滚动驱动（SchedulePage NestedScrollConnection，
 * Dart NotificationListener 对应物），本件受控呈现+手动收起钮。
 * 批量语义：逐块顺序 confirm/reject；单块 ActionException 吞错不中断——失败块留
 * 提案态逐块处理（点块浮层）；busy 期按钮禁用。
 */
@Composable
fun TieredConfirmCard(
    proposed: List<ScheduleBlock>,
    data: ScheduleDayData,
    collapsed: Boolean,
    onToggle: (Boolean) -> Unit,
    vm: TodayViewModel,
    modifier: Modifier = Modifier,
) {
    val palette = LocalStColors.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var showRejectDialog by remember { mutableStateOf(false) }
    val spark = proposed.firstOrNull { it.isDaySpark }
    val others = proposed.filterNot { it.isDaySpark }

    fun runBatch(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            block()
            busy = false
        }
    }

    if (collapsed) {
        Column(modifier.padding(start = StScale.insetLg.dp, end = StScale.insetLg.dp, top = StScale.insetXs.dp, bottom = StScale.insetXs.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(StScale.blockMinHeightDp.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(StScale.radiusCapsule.dp))
                    .clickable { onToggle(false) }
                    .padding(horizontal = StScale.insetMd.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "⚡ AI 排程建议：${if (spark != null) "重点攻坚 1 项，" else ""}顺带推进 ${others.size} 项 · 点击查看理由",
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = StUi.compactTextSp.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                TextButton(
                    enabled = !busy,
                    onClick = { runBatch { vm.confirmAllSuspend(proposed) } },
                ) { Text("全部确认") }
            }
        }
        return
    }
    Card(
        modifier = modifier
            .padding(start = StScale.insetLg.dp, end = StScale.insetLg.dp, top = StScale.insetXs.dp, bottom = StScale.insetXs.dp)
            .fillMaxWidth(),
        shape = RoundedCornerShape(StScale.radiusCard.dp),
    ) {
        Column(Modifier.padding(StScale.insetMd.dp)) {
            if (spark != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "🔥 今日核心",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    IconButton(onClick = { onToggle(true) }) {
                        Icon(
                            Icons.Filled.KeyboardArrowUp,
                            contentDescription = "收起",
                            modifier = Modifier.size(StUi.smallIconDp.dp),
                        )
                    }
                }
                Text(
                    "「${labelOf(spark, data)}」 ${ScheduleDay.clockOf(spark.startMin.toInt())}–${ScheduleDay.clockOf(spark.endMin.toInt())}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "这是今天最重要的一件事，其余都为它让路。",
                    modifier = Modifier.padding(top = StScale.insetXs.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(palette.textSecondary),
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "AI 排程建议 · ${proposed.size} 项",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    IconButton(onClick = { onToggle(true) }) {
                        Icon(
                            Icons.Filled.KeyboardArrowUp,
                            contentDescription = "收起",
                            modifier = Modifier.size(StUi.smallIconDp.dp),
                        )
                    }
                }
            }
            if (others.isNotEmpty()) {
                Text(
                    "其余 ${others.size} 项日常推进",
                    modifier = Modifier.padding(top = StScale.insetSm.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(palette.textSecondary),
                )
            }
            Row(
                modifier = Modifier.padding(top = StScale.insetSm.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(enabled = !busy, onClick = { runBatch { vm.confirmAllSuspend(proposed) } }) {
                    Text("全部确认")
                }
                Spacer(Modifier.width(StScale.insetSm.dp))
                OutlinedButton(
                    enabled = !busy,
                    onClick = {
                        // 否决先问原因（可选，§3.4：回传 AI 下轮参考）；取消=不动
                        showRejectDialog = true
                    },
                ) { Text("全部否决") }
                Spacer(Modifier.width(StScale.insetSm.dp))
                Text(
                    "逐块调整：点时间轴上的提案块",
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(palette.textSecondary),
                )
            }
        }
    }
    if (showRejectDialog) {
        RejectReasonDialog(
            title = "全部否决这 ${proposed.size} 项提案？",
            onDismiss = { showRejectDialog = false },
            onConfirm = { reason ->
                showRejectDialog = false
                runBatch { vm.rejectAllSuspend(proposed, reason.ifEmpty { null }) }
            },
        )
    }
}
