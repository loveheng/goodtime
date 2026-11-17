package app.shiguang.shiguang.ui.shell

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import app.shiguang.core.model.ScheduleDay
import app.shiguang.core.tokens.StScale
import app.shiguang.core.tokens.StUi
import app.shiguang.shiguang.ui.component.ShiguangBottomSheet
import app.shiguang.shiguang.ui.schedule.QuickNoteDraft
import app.shiguang.shiguang.ui.schedule.TodayViewModel
import app.shiguang.shiguang.ui.theme.LocalStColors
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneOffset

/**
 * 快记入口（ui-pages §1/§3；2026-10-06 拍板；§C 转正清单）：右下角 FAB+键盘吸附
 * 展开抽屉；守卫态不撤=「快记永不关门」（壳常驻）。草稿三字段即写即存（R1：UI
 * 不直连 repo 写，走 quick_note_draft 命令），草稿在盘时 FAB 琥珀点信号。
 */

/** 右下角快记悬浮钮：草稿在盘 → 右上角 6dp 琥珀点（sparkStroke，surface 描边 2）。 */
@Composable
fun QuickNoteFab(
    hasDraft: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalStColors.current
    Box(modifier, contentAlignment = Alignment.TopEnd) {
        FloatingActionButton(onClick = onOpen) {
            Icon(Icons.Filled.EditNote, contentDescription = "记一笔")
        }
        if (hasDraft) {
            // Dart Stack clipBehavior:none + Positioned(right:-2, top:-2) 镜像：右上溢出
            Box(
                Modifier
                    .offset(x = StUi.fabDotStrokeDp.dp, y = -StUi.fabDotStrokeDp.dp)
                    .size(StUi.fabDotSizeDp.dp)
                    .background(Color(palette.sparkStroke), CircleShape)
                    .border(
                        BorderStroke(StUi.fabDotStrokeDp.dp, MaterialTheme.colorScheme.surface),
                        CircleShape,
                    ),
            )
        }
    }
}

/**
 * 快记抽屉（FAB 与清单空态共用入口；§C）：预载草稿 → 键盘吸附（ShiguangBottomSheet
 * imePadding 内建）；面板收起=onSheetClosed 冲刷防抖尾巴（未提交时）；提交成功=清
 * 草稿+关闭+事件 1s「已收入清单」浮动+FAB 净空。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun QuickNoteSheet(
    initial: QuickNoteDraft,
    vm: TodayViewModel,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var value by remember {
        mutableStateOf(
            TextFieldValue(
                text = initial.text,
                selection = TextRange(initial.text.length), // 草稿回填光标落末尾（拍板交互细则）
            ),
        )
    }
    var important by remember { mutableStateOf(initial.important) }
    var deadline by remember { mutableStateOf(initial.deadline) }
    var submitting by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() } // autofocus（§C 面板）

    ShiguangBottomSheet(onDismissRequest = {
        vm.onSheetClosed()
        onDismiss()
    }) {
        Column(
            Modifier.padding(StScale.insetLg.dp),
            verticalArrangement = Arrangement.spacedBy(StScale.insetSm.dp),
        ) {
            TextField(
                value = value,
                onValueChange = {
                    value = it
                    vm.onDraftTextChanged(it.text) // 300ms 防抖即写即存
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus),
                minLines = 1,
                maxLines = 3,
                placeholder = { Text("记一笔，30 秒的事") },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = {
                    important = !important
                    vm.onDraftMetaChanged(important = important)
                }) {
                    Icon(
                        if (important) Icons.Filled.Star else Icons.Filled.StarBorder,
                        contentDescription = "标重要",
                        tint = if (important) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (deadline == null) {
                    IconButton(onClick = { showDatePicker = true }) {
                        Icon(Icons.Outlined.Event, contentDescription = "截止")
                    }
                } else {
                    TextButton(
                        modifier = Modifier
                            .height(StScale.blockMinHeightDp.dp)
                            .width(StScale.blockMinHeightDp.dp),
                        onClick = {
                            deadline = null
                            vm.onDraftMetaChanged(deadline = null)
                        },
                    ) { Text(ScheduleDay.isoDate(deadline!!)) }
                }
                Spacer(Modifier.weight(1f))
                Button(
                    enabled = !submitting && value.text.isNotBlank(),
                    onClick = {
                        scope.launch {
                            submitting = true
                            val ok = vm.submitQuickNote(value.text, important, deadline)
                            submitting = false
                            if (ok) onDismiss()
                        }
                    },
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text("收入清单")
                }
            }
        }
    }
    if (showDatePicker) {
        // firstDate=now、lastDate=+365 天（§C 转正清单）；UTC millis ↔ LocalDate
        val state = rememberDatePickerState(
            initialSelectedDateMillis = System.currentTimeMillis(),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    enabled = state.selectedDateMillis != null,
                    onClick = {
                        val d = Instant.ofEpochMilli(state.selectedDateMillis!!)
                            .atZone(ZoneOffset.UTC).toLocalDate()
                        deadline = d
                        vm.onDraftMetaChanged(deadline = d)
                        showDatePicker = false
                    },
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("取消") }
            },
        ) {
            DatePicker(state = state)
        }
    }
}
