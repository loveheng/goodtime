package app.shiguang.shiguang.ui.blocksheet

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDialog
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.shiguang.core.model.Plan
import app.shiguang.core.model.ScheduleBlock
import app.shiguang.shiguang.ui.block.BlockStatus
import app.shiguang.core.model.ScheduleDay
import app.shiguang.core.tokens.StScale
import app.shiguang.core.tokens.StUi
import app.shiguang.shiguang.ui.component.ShiguangBottomSheet
import app.shiguang.shiguang.ui.factsheet.FactDetailSheet
import app.shiguang.shiguang.ui.schedule.RejectReasonDialog
import app.shiguang.shiguang.ui.schedule.TodayViewModel
import app.shiguang.shiguang.ui.theme.LocalStColors
import kotlinx.coroutines.launch

/**
 * 块弹层（M1 点块表单，behavior/block_sheet.md 转正清单 §A/§B，D25 过目）：
 * proposed=确认/否决（原因可选）；confirmed=打卡（可撤销）/心流专注中 (+30m，
 * AI 块)/改时间；done=只读；missed/archived=无键（键区空）。头部恒为「马上开始」
 * 第一步（headlineSmall=全 app 最大字）；规则提醒角标=硬红线 human 通道（绝不
 * 阻断仅提醒 §9.3）；通关卡嵌入（artifactForBlock 非空，padding zero）。
 */
@OptIn(ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun BlockSheet(
    vm: TodayViewModel,
    block: ScheduleBlock,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val palette = LocalStColors.current
    var busy by remember { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var start by remember { mutableStateOf(block.startMin.toInt()) }
    var end by remember { mutableStateOf(block.endMin.toInt()) }
    var plan by remember { mutableStateOf<Plan?>(null) }
    var fact by remember { mutableStateOf<app.shiguang.core.model.Artifact?>(null) }
    var warnings by remember { mutableStateOf<List<String>>(emptyList()) }
    var rejectDialog by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf<Char?>(null) } // 编辑态时刻 picker：'s'=从几点 / 'e'=到几点

    LaunchedEffect(block.id) {
        plan = vm.sheetPlan(block)
        fact = vm.factForBlock(block)
        warnings = vm.ruleWarnings(block)
    }

    fun run(action: suspend () -> Boolean) {
        if (busy) return
        scope.launch {
            busy = true
            val ok = action()
            busy = false
            if (ok) onDismiss()
        }
    }

    ShiguangBottomSheet(onDismissRequest = onDismiss) {
        // 通关卡嵌入后内容可超屏（Dart 侧同修 2026-11-16）——主体收 verticalScroll。
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = StScale.insetXl.dp, end = StScale.insetXl.dp, bottom = StScale.insetXl.dp),
        ) {
            // 头部：label + 状态行 + 规则提醒角标 + 马上开始（§B）
            val label = block.label ?: plan?.title ?: "(未命名)"
            Text(label, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(StScale.insetXs.dp))
            Text(
                "${statusLabel(block.status)} · ${ScheduleDay.clockOf(block.startMin.toInt())}–${ScheduleDay.clockOf(block.endMin.toInt())}" +
                    (if (plan != null) " · 来自清单：${plan!!.title}" else ""),
                style = MaterialTheme.typography.bodySmall,
                color = Color(palette.textSecondary),
            )
            if (warnings.isNotEmpty()) {
                FlowRow(
                    Modifier.padding(top = StScale.insetSm.dp),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(StUi.compactGapDp.dp),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(StUi.compactGapDp.dp),
                ) {
                    for (w in warnings) RuleWarningChip(w)
                }
            }
            val notes = plan?.notes?.trim()
            if (!notes.isNullOrEmpty()) {
                Spacer(Modifier.height(StScale.insetMd.dp))
                Text("马上开始", style = MaterialTheme.typography.labelSmall, color = Color(palette.textSecondary))
                val lines = notes.split('\n')
                Text(lines.first(), style = MaterialTheme.typography.headlineSmall)
                if (lines.size > 1) {
                    Text(
                        lines.drop(1).joinToString("\n"),
                        modifier = Modifier.padding(top = StScale.insetSm.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Spacer(Modifier.height(StScale.insetMd.dp))
            // 通关卡嵌入（关联凭证首屏；无凭证整层消失 §A）
            fact?.let { f ->
                FactDetailSheet(
                    fact = f,
                    block = block,
                    onNavigateToBlock = null,
                    onCopy = { code -> vm.notifyFactCopied(code) },
                    contentPadding = PaddingValues(StUi.factEmbedPadDp.dp),
                )
                Spacer(Modifier.height(StScale.insetMd.dp))
            }
            if (editing) {
                BlockEditForm(
                    start = start,
                    end = end,
                    busy = busy,
                    onPick = { picking = it },
                    onSubmit = { s, e -> run { vm.blockAdjust(block, s, e) } },
                )
            } else {
                BlockActionRow(block = block, busy = busy, onAction = { action ->
                    when (action) {
                        "confirm" -> run { vm.blockConfirm(block) }
                        "reject" -> rejectDialog = true
                        "tick" -> run { vm.blockTick(block) }
                        "shift" -> run { vm.blockShift(block) }
                        "edit" -> editing = true
                    }
                })
            }
        }
    }
    // 否决原因弹框（复用 N5 同形件 RejectReasonDialog）：取消=不动 / 空=无原因 / 非空=带原因
    if (rejectDialog) {
        RejectReasonDialog(
            title = "否决这条提案？",
            onDismiss = { rejectDialog = false },
            onConfirm = { reason ->
                rejectDialog = false
                run { vm.blockReject(block, reason.ifEmpty { null }) }
            },
        )
    }
    // 编辑态时刻 picker（从几点/到几点）
    picking?.let { which ->
        val minutes = if (which == 's') start else end
        val state = rememberTimePickerState(initialHour = minutes / 60, initialMinute = minutes % 60)
        TimePickerDialog(
            onDismissRequest = { picking = null },
            title = { Text("选择时间") },
            confirmButton = {
                TextButton(onClick = {
                    val picked = state.hour * 60 + state.minute
                    if (which == 's') start = picked else end = picked
                    picking = null
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { picking = null }) { Text("取消") } },
        ) { TimePicker(state = state) }
    }
}

private fun statusLabel(status: String): String = when (status) {
    BlockStatus.PROPOSED -> "提案（AI 建议）"
    BlockStatus.CONFIRMED -> "已确认"
    BlockStatus.DONE -> "已完成"
    BlockStatus.MISSED -> "未打卡"
    BlockStatus.ARCHIVED -> "已归档"
    else -> status
}

/** 硬红线 human 通道角标胶囊（sparkStroke 15% 底；reason 尾段已由 VM 截好）。 */
@Composable
private fun RuleWarningChip(text: String) {
    val palette = LocalStColors.current
    Row(
        Modifier
            .background(Color(palette.sparkStroke).copy(alpha = 0.15f), RoundedCornerShape(StScale.radiusCapsule.dp))
            .padding(horizontal = StScale.insetSm.dp, vertical = StUi.compactPadDp.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.WarningAmber,
            contentDescription = null,
            modifier = Modifier.size(StUi.factChipIconDp.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(StScale.insetXs.dp))
        Text(text, fontSize = StUi.plansMetaTextSp.sp)
    }
}

/** 键区（§A 分流；Wrap 8/8；done=只读文案；missed/archived=键区空消失）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BlockActionRow(block: ScheduleBlock, busy: Boolean, onAction: (String) -> Unit) {
    val aiBlock = block.source == ScheduleBlock.SOURCE_AI
    when (block.status) {
        BlockStatus.PROPOSED -> FlowRow(
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(StScale.insetSm.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(StScale.insetSm.dp),
        ) {
            Button(onClick = { onAction("confirm") }, enabled = !busy) { Text("确认") }
            OutlinedButton(onClick = { onAction("reject") }, enabled = !busy) { Text("否决") }
        }
        BlockStatus.CONFIRMED -> FlowRow(
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(StScale.insetSm.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(StScale.insetSm.dp),
        ) {
            FilledTonalButton(onClick = { onAction("tick") }, enabled = !busy) { Text("打卡") }
            if (aiBlock) {
                OutlinedButton(onClick = { onAction("shift") }, enabled = !busy) { Text("心流专注中 (+30m)") }
            }
            OutlinedButton(onClick = { onAction("edit") }, enabled = !busy) { Text("改时间") }
        }
        BlockStatus.DONE -> Text(
            "这块已完成，没有待办了。",
            modifier = Modifier.padding(vertical = StScale.insetSm.dp),
            style = MaterialTheme.typography.bodySmall,
        )
        else -> Unit // missed/archived：键区空（无键可给）
    }
}

/** 编辑改时间表单（从几点/到几点双 ListTile+TimePicker；保存=adjust_block_time）。 */
@Composable
private fun BlockEditForm(
    start: Int,
    end: Int,
    busy: Boolean,
    onPick: (Char) -> Unit,
    onSubmit: (Int, Int) -> Unit,
) {
    Column {
        TimeField("从几点", start) { onPick('s') }
        Spacer(Modifier.height(StScale.insetMd.dp))
        TimeField("到几点", end) { onPick('e') }
        Spacer(Modifier.height(StScale.insetMd.dp))
        Button(onClick = { onSubmit(start, end) }, enabled = !busy) { Text("保存") }
    }
}

@Composable
private fun TimeField(label: String, minutes: Int, onTap: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = onTap) {
            Text(ScheduleDay.clockOf(minutes), fontSize = StUi.sheetClockTextSp.sp)
        }
    }
}
