package app.shiguang.shiguang.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDialog
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.shiguang.core.model.FixedSlot
import app.shiguang.core.model.ScheduleDay
import app.shiguang.core.tokens.StScale
import app.shiguang.core.tokens.StUi
import app.shiguang.shiguang.ui.component.ShiguangBottomSheet
import app.shiguang.shiguang.ui.theme.LocalStColors

/**
 * 一周节奏设置页（ui-pages §1 子页化拍板；behavior/settings_subpages.md §A 已过目
 * 转正）：fixed_slots 增删改——首启后唯一编辑入口，整单批量替换（update_fixed_slots）
 * 条目独立（工作日/周末可并存）。编辑 sheet 三校验内联（SnackBar 后不关 sheet）。
 */
private val WEEKDAY_NAMES = listOf("", "周一", "周二", "周三", "周四", "周五", "周六", "周日")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RhythmSettingsPage(vm: SubpagesViewModel, modifier: Modifier = Modifier) {
    val ui by vm.uiState.collectAsState()
    var editorSlot by remember { mutableStateOf<FixedSlot?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    val palette = LocalStColors.current

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(StScale.insetLg.dp),
    ) {
        if (ui.loading) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(StScale.insetXl.dp))
                CircularProgressIndicator()
            }
            return@Column
        }
        Text(
            "没有固定安排是完全正常的（自由职业预设）",
            style = if (ui.slots.isEmpty()) {
                MaterialTheme.typography.bodySmall.copy(color = Color(palette.textSecondary))
            } else {
                MaterialTheme.typography.bodySmall.copy(color = Color(palette.textSecondary))
            },
        )
        Spacer(Modifier.height(StScale.insetSm.dp))
        for (s in ui.slots) {
            ListItem(
                headlineContent = { Text(s.name) },
                supportingContent = {
                    Text(
                        "${s.weekdays.sorted().joinToString("、") { WEEKDAY_NAMES[it] }} " +
                            "${ScheduleDay.clockOf(s.startMin)}–${ScheduleDay.clockOf(s.endMin)}",
                    )
                },
                trailingContent = {
                    Row {
                        IconButton(onClick = { editorSlot = s; showEditor = true }) {
                            Icon(Icons.Outlined.Edit, contentDescription = "编辑", modifier = Modifier.size(StUi.iconSmDp.dp))
                        }
                        IconButton(onClick = { vm.deleteFixedSlot(s) }) {
                            Icon(Icons.Outlined.Delete, contentDescription = "删除", modifier = Modifier.size(StUi.iconSmDp.dp))
                        }
                    }
                },
            )
        }
        Spacer(Modifier.height(StScale.insetSm.dp))
        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = { editorSlot = null; showEditor = true },
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(StUi.iconSmDp.dp))
            Text("添加")
        }
    }
    if (showEditor) {
        FixedSlotEditorSheet(
            existing = editorSlot,
            current = ui.slots,
            vm = vm,
            onDismiss = { showEditor = false },
        )
    }
}

/** 一周节奏单条编辑（§A：名称+ISO 星期多选+起止钟点；三校验内联不关 sheet）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FixedSlotEditorSheet(
    existing: FixedSlot?,
    current: List<FixedSlot>,
    vm: SubpagesViewModel,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var weekdays by remember { mutableStateOf(existing?.weekdays?.toSet() ?: emptySet()) }
    var startMin by remember {
        mutableStateOf(existing?.startMin ?: (9 * 60))
    }
    var endMin by remember {
        mutableStateOf(existing?.endMin?.let { it % 1440 } ?: (18 * 60))
    }
    var pickStart by remember { mutableStateOf(false) }
    var pickEnd by remember { mutableStateOf(false) }

    ShiguangBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = StScale.insetXl.dp)
                .padding(top = StScale.insetMd.dp, bottom = StScale.insetXl.dp),
            verticalArrangement = Arrangement.spacedBy(StScale.insetMd.dp),
        ) {
            Text(
                if (existing == null) "添加固定安排" else "编辑固定安排",
                style = MaterialTheme.typography.titleLarge,
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("名称（如「上班」「健身」）") },
            )
            Text("适用星期（可多选）")
            Row(
                horizontalArrangement = Arrangement.spacedBy(StScale.insetXs.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                for (w in 1..7) {
                    FilterChip(
                        selected = weekdays.contains(w),
                        onClick = { weekdays = if (weekdays.contains(w)) weekdays - w else weekdays + w },
                        label = { Text(WEEKDAY_NAMES[w]) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Row {
                ListItem(
                    modifier = Modifier.weight(1f).clickableRow { pickStart = true },
                    headlineContent = { Text("开始") },
                    supportingContent = { Text(ScheduleDay.clockOf(startMin)) },
                )
                ListItem(
                    modifier = Modifier.weight(1f).clickableRow { pickEnd = true },
                    headlineContent = { Text("结束") },
                    supportingContent = { Text(ScheduleDay.clockOf(endMin)) },
                )
            }
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    // 三校验内联（§A：SnackBar 后不关 sheet——Dart 同构）
                    if (name.trim().isEmpty()) {
                        vm.notifyValidation("名称不能为空")
                        return@Button
                    }
                    if (weekdays.isEmpty()) {
                        vm.notifyValidation("至少选一个适用星期")
                        return@Button
                    }
                    if (endMin <= startMin) {
                        vm.notifyValidation("结束须晚于开始")
                        return@Button
                    }
                    vm.saveFixedSlot(
                        FixedSlot(
                            id = existing?.id,
                            name = name.trim(),
                            weekdays = weekdays.sorted(),
                            startMin = startMin,
                            endMin = endMin,
                        ),
                    )
                    onDismiss()
                },
            ) { Text("保存") }
        }
    }
    if (pickStart) {
        TimePickDialog(
            initialMinutes = startMin,
            onDismiss = { pickStart = false },
            onConfirm = { startMin = it; pickStart = false },
        )
    }
    if (pickEnd) {
        TimePickDialog(
            initialMinutes = endMin,
            onDismiss = { pickEnd = false },
            onConfirm = { endMin = it; pickEnd = false },
        )
    }
}

/** TimePicker 弹框（分钟数出入）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TimePickDialog(
    initialMinutes: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val state = rememberTimePickerState(initialHour = initialMinutes / 60, initialMinute = initialMinutes % 60)
    TimePickerDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择时间") },
        confirmButton = { Button(onClick = { onConfirm(state.hour * 60 + state.minute) }) { Text("确定") } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("取消") } },
    ) {
        TimePicker(state = state)
    }
}

internal fun Modifier.clickableRow(onClick: () -> Unit): Modifier = this.clickable(onClick = onClick)
