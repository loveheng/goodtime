package app.shiguang.shiguang.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.shiguang.core.tokens.StUi
import app.shiguang.core.model.ExceptionWindow
import app.shiguang.core.model.ScheduleDay
import app.shiguang.core.tokens.StScale
import app.shiguang.shiguang.ui.component.ShiguangBottomSheet
import app.shiguang.shiguang.ui.theme.LocalStColors
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * 假期与出行设置页（ui-pages §1 子页化拍板；behavior/settings_subpages.md §B 已过目
 * 转正）：例外日窗口+标签——期间工作类 fixed_slots 挂起、填充率降至 35%（§13 域
 * 语义本页只管窗口数据）。R2 口径：读侧 parseExceptions 数据层委托/写侧结构化值
 * 直传（编解码归命令层）。单校验内联（结束≥开始）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExceptionsSettingsPage(vm: SubpagesViewModel, modifier: Modifier = Modifier) {
    val ui by vm.uiState.collectAsState()
    var editorWindow by remember { mutableStateOf<ExceptionWindow?>(null) }
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
            "没有特别安排是完全正常的",
            style = MaterialTheme.typography.bodySmall.copy(color = Color(palette.textSecondary)),
        )
        Spacer(Modifier.height(StScale.insetSm.dp))
        for (w in ui.exceptions) {
            ListItem(
                headlineContent = { Text(w.label ?: "假期与出行") },
                supportingContent = { Text("${w.start} → ${w.end}") },
                trailingContent = {
                    Row {
                        IconButton(onClick = { editorWindow = w; showEditor = true }) {
                            Icon(Icons.Outlined.Edit, contentDescription = "编辑", modifier = Modifier.size(StUi.iconSmDp.dp))
                        }
                        IconButton(onClick = { vm.deleteException(w) }) {
                            Icon(Icons.Outlined.Delete, contentDescription = "删除", modifier = Modifier.size(StUi.iconSmDp.dp))
                        }
                    }
                },
            )
        }
        Spacer(Modifier.height(StScale.insetSm.dp))
        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = { editorWindow = null; showEditor = true },
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(StUi.iconSmDp.dp))
            Text("添加")
        }
    }
    if (showEditor) {
        ExceptionEditorSheet(
            existing = editorWindow,
            current = ui.exceptions,
            vm = vm,
            onDismiss = { showEditor = false },
        )
    }
}

/** 例外日单条编辑（§B：起止日期+标签；单校验内联不关 sheet）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExceptionEditorSheet(
    existing: ExceptionWindow?,
    current: List<ExceptionWindow>,
    vm: SubpagesViewModel,
    onDismiss: () -> Unit,
) {
    val today = LocalDate.now()
    var start by remember { mutableStateOf(existing?.start?.let { ScheduleDay.tryParseIsoDate(it) } ?: today) }
    var end by remember { mutableStateOf(existing?.end?.let { ScheduleDay.tryParseIsoDate(it) } ?: today) }
    var label by remember { mutableStateOf(existing?.label ?: "") }
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
                if (existing == null) "添加假期与出行" else "编辑假期与出行",
                style = MaterialTheme.typography.titleLarge,
            )
            Row {
                ListItem(
                    modifier = Modifier.weight(1f).clickableRow { pickStart = true },
                    headlineContent = { Text("从") },
                    supportingContent = { Text(ScheduleDay.isoDate(start)) },
                )
                ListItem(
                    modifier = Modifier.weight(1f).clickableRow { pickEnd = true },
                    headlineContent = { Text("到") },
                    supportingContent = { Text(ScheduleDay.isoDate(end)) },
                )
            }
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("标签（如「云南行」「春节」）") },
            )
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    // 单校验内联（§B：结束日期须不早于开始——SnackBar 后不关 sheet）
                    if (ScheduleDay.isoDate(end) < ScheduleDay.isoDate(start)) {
                        vm.notifyValidation("结束日期须不早于开始")
                        return@Button
                    }
                    val next = ExceptionWindow(
                        start = ScheduleDay.isoDate(start),
                        end = ScheduleDay.isoDate(end),
                        label = label.trim().ifEmpty { null },
                    )
                    // 整列替换：current−替换项+新项（Dart _edit 镜像）
                    vm.saveExceptions(current.filter { it != existing && it != next } + next)
                    onDismiss()
                },
            ) { Text("保存") }
        }
    }
    if (pickStart) {
        DatePickDialog(
            initial = start,
            onDismiss = { pickStart = false },
            onConfirm = { start = it; pickStart = false },
        )
    }
    if (pickEnd) {
        DatePickDialog(
            initial = end,
            onDismiss = { pickEnd = false },
            onConfirm = { end = it; pickEnd = false },
        )
    }
}

/** DatePicker 弹框（2020–2035 窗口，§B 清单转正值）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DatePickDialog(
    initial: LocalDate,
    onDismiss: () -> Unit,
    onConfirm: (LocalDate) -> Unit,
) {
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = state.selectedDateMillis != null,
                onClick = {
                    onConfirm(
                        Instant.ofEpochMilli(state.selectedDateMillis!!)
                            .atZone(ZoneOffset.UTC).toLocalDate(),
                    )
                },
            ) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    ) {
        DatePicker(state = state)
    }
}
