package app.shiguang.shiguang.ui.blocksheet

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.shiguang.core.model.Plan
import app.shiguang.core.model.ScheduleDay
import app.shiguang.core.tokens.StScale
import app.shiguang.core.tokens.StUi
import app.shiguang.shiguang.ui.component.ShiguangBottomSheet
import app.shiguang.shiguang.ui.schedule.ScheduleDayData
import app.shiguang.shiguang.ui.schedule.TodayViewModel

/**
 * 空槽点按创建双通道（behavior/new_block_sheets.md 转正清单 §B/§C，D25 过目）：
 * 未来时段=「新建事项/从清单选」（place_block）；过去时段=**逆向记账「刚才做了
 * 什么」**（retro_log 事实通道落 done 豁免排程校验——凡真实专注全部承认，UI 全程
 * 无「补排程」暗示 §7/§10）。成功静默 pop（无 toast——revision 刷新自现）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewBlockSheet(
    vm: TodayViewModel,
    data: ScheduleDayData,
    slotStart: Int?,
    past: Boolean,
    onDismiss: () -> Unit,
) {
    var fromPlan by rememberSaveable { mutableStateOf<String?>(null) } // null=新建态 / 非空=清单态（planId；空串=空清单占位）
    var label by remember { mutableStateOf("") }
    var plans by remember { mutableStateOf<List<Plan>>(emptyList()) }
    var start by remember { mutableStateOf(slotStart ?: (data.wake + 60)) }
    var end by remember { mutableStateOf((slotStart ?: (data.wake + 60)) + 60) }
    var picking by remember { mutableStateOf<Char?>(null) }

    LaunchedEffect(Unit) {
        plans = vm.sheetPlans()
        // Dart：切「从清单选」=plans.first 兜底；初值即新建态——此处仅拉清单
    }

    val selectedPlan = fromPlan?.let { pid -> plans.firstOrNull { it.id == pid } }

    ShiguangBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = StScale.insetXl.dp, end = StScale.insetXl.dp, bottom = StScale.insetXl.dp),
        ) {
            Text(if (past) "刚才做了什么" else "新建事项", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(StScale.insetMd.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = fromPlan == null,
                    onClick = { fromPlan = null },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                ) { Text("新建事项") }
                SegmentedButton(
                    selected = fromPlan != null,
                    onClick = { fromPlan = plans.firstOrNull()?.id ?: "" }, // 空清单保持新建态（Dart null 同构）
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                ) { Text("从清单选") }
            }
            Spacer(Modifier.height(StScale.insetMd.dp))
            if (fromPlan == null) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("做什么（label）") },
                    singleLine = true,
                )
            } else {
                PlanDropdown(
                    plans = plans,
                    selected = selectedPlan,
                    onSelect = { fromPlan = it?.id ?: "" },
                )
            }
            Spacer(Modifier.height(StScale.insetMd.dp))
            Row {
                Column(Modifier.weight(1f)) {
                    Text("从几点", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { picking = 's' }) {
                        Text(ScheduleDay.clockOf(start), fontSize = StUi.sheetClockTextSp.sp)
                    }
                }
                Spacer(Modifier.width(StScale.insetMd.dp))
                Column(Modifier.weight(1f)) {
                    Text("到几点", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { picking = 'e' }) {
                        Text(ScheduleDay.clockOf(end), fontSize = StUi.sheetClockTextSp.sp)
                    }
                }
            }
            Spacer(Modifier.height(StScale.insetMd.dp))
            val canSubmit = selectedPlan != null || label.trim().isNotEmpty()
            Button(
                onClick = {
                    if (!canSubmit) return@Button
                    val pid = selectedPlan?.id
                    val lbl = if (pid == null) label.trim() else null
                    // 提交即关（Dart pop 无 SnackBar——静默落账；失败留窗由 VM DEGRADE 镜像）
                    scopelessSubmit(vm, ScheduleDay.isoDate(data.date), start, end, pid, lbl, past) { ok ->
                        if (ok) onDismiss()
                    }
                },
                enabled = canSubmit,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("放入日程") }
        }
    }
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

/** 提交桥（避开 Composable 内 launch 语法噪音）：VM suspend 经协程作用域外置。 */
private fun scopelessSubmit(
    vm: TodayViewModel,
    dateIso: String,
    start: Int,
    end: Int,
    planId: String?,
    label: String?,
    past: Boolean,
    onDone: (Boolean) -> Unit,
) {
    vm.submitNewBlock(dateIso, start, end, planId, label, past, onDone)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlanDropdown(
    plans: List<Plan>,
    selected: Plan?,
    onSelect: (Plan?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected?.title ?: "",
            onValueChange = {},
            readOnly = true,
            modifier = Modifier.fillMaxWidth().menuAnchor(),
            label = { Text("从清单选") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (p in plans) {
                DropdownMenuItem(
                    text = { Text(p.title) },
                    onClick = {
                        onSelect(p)
                        expanded = false
                    },
                )
            }
        }
    }
}
