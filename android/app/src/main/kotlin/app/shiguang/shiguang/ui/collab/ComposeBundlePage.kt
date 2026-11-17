package app.shiguang.shiguang.ui.collab

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.PersonAddAlt1
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDialog
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.shiguang.core.model.collab.ProposalKind
import app.shiguang.core.model.collab.ProposalStepDraft
import app.shiguang.core.tokens.StScale
import app.shiguang.core.tokens.StUi
import app.shiguang.shiguang.ui.component.ShiguangScaffold
import app.shiguang.shiguang.ui.component.ShiguangSnackbarHost
import app.shiguang.shiguang.ui.component.showShiguangSnackbar
import app.shiguang.shiguang.ui.theme.LocalStColors
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 发起协同页（behavior/compose_bundle.md 转正清单 §B，T4.5）：三块表单三件事
 * （和谁一起/怎么定/做什么）+ 三连 picker（日期→开始→结束，逐层消费=丢失点 #8）
 * + 发出邀请回执翻译（§A 逐字）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposeBundlePage(
    onAddFriend: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm: ComposeBundleViewModel = viewModel(factory = ComposeBundleViewModel.factory)
    val s by vm.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(vm) {
        vm.events.collect { msg -> snackbarHostState.showShiguangSnackbar(msg) }
    }
    LaunchedEffect(Unit) { vm.init() }

    // 三连 picker 状态机（§A：DatePicker→TimePicker 开始 10:00→TimePicker 结束=开始+1h）
    var pickDateFor by rememberSaveable { mutableStateOf<Int?>(null) }
    var pickStartFor by rememberSaveable { mutableStateOf<Int?>(null) }
    var pickEndFor by rememberSaveable { mutableStateOf<Int?>(null) }
    var pickedDateMs by rememberSaveable { mutableStateOf<Long?>(null) }
    var pickedStartHour by rememberSaveable { mutableStateOf(10) }
    var pickedStartMinute by rememberSaveable { mutableStateOf(0) }

    ShiguangScaffold(
        modifier = modifier,
        snackbarHostState = snackbarHostState,
        snackbarHost = { ShiguangSnackbarHost(snackbarHostState) },
        topBar = { TopAppBar(title = { Text("发起协同") }) },
    ) { padding ->
        if (s.loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (s.gateError) {
            // 门禁兜底态（正常路由经 member_calendar 门禁不可达；debug 直进引导）
            Box(Modifier.fillMaxSize().padding(padding).padding(horizontal = StScale.insetLg.dp), contentAlignment = Alignment.Center) {
                Text(
                    "先到「设置 → 协同账号」登录，才能发出邀约",
                    color = Color(LocalStColors.current.textSecondary),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        } else {
            ComposeForm(
                padding = padding,
                s = s,
                onToggleContact = vm::toggleMember,
                onKindChanged = vm::setKind,
                onToggleRequired = vm::toggleRequired,
                onTitleChanged = vm::setTitle,
                onPickSlot = { pickDateFor = it },
                onAddStep = vm::addStep,
                onRemoveStep = vm::removeStep,
                onSubmit = { vm.submit(onDone) },
                onAddFriend = onAddFriend,
            )
        }
    }
    // —— 三连 picker（逐层；BACK=取消——丢失点 #8 既有机制） ——
    pickDateFor?.let { stepIndex ->
        val today = LocalDate.now()
        val dateState = rememberDatePickerState(
            initialSelectedDateMillis = System.currentTimeMillis(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcMillis: Long): Boolean =
                    Instant.ofEpochMilli(utcMillis).atZone(ZoneId.of("UTC")).toLocalDate() >= today
            },
        )
        DatePickerDialog(
            onDismissRequest = { pickDateFor = null },
            confirmButton = {
                TextButton(onClick = {
                    val ms = dateState.selectedDateMillis
                    if (ms != null) {
                        pickedDateMs = ms
                        pickDateFor = null
                        pickStartFor = stepIndex
                    }
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { pickDateFor = null }) { Text("取消") } },
        ) {
            DatePicker(state = dateState)
        }
    }
    pickStartFor?.let { stepIndex ->
        val startState = rememberTimePickerState(initialHour = 10, initialMinute = 0, is24Hour = true)
        TimePickerDialog(
            onDismissRequest = { pickStartFor = null },
            title = { Text("开始时间") },
            confirmButton = {
                TextButton(onClick = {
                    val hour = startState.hour
                    val minute = startState.minute
                    pickStartFor = null
                    pickEndFor = stepIndex
                    pickedStartHour = hour
                    pickedStartMinute = minute
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { pickStartFor = null }) { Text("取消") } },
        ) {
            TimePicker(state = startState)
        }
    }
    pickEndFor?.let { stepIndex ->
        // F3 跨日怪点镜像现状：结束默认=开始+1h（(hour+1)%24 同分钟，无跨日标记）
        val endState = rememberTimePickerState(
            initialHour = (pickedStartHour + 1) % 24,
            initialMinute = pickedStartMinute,
            is24Hour = true,
        )
        TimePickerDialog(
            onDismissRequest = { pickEndFor = null },
            title = { Text("结束时间") },
            confirmButton = {
                TextButton(onClick = {
                    val dateMs = pickedDateMs
                    if (dateMs != null) {
                        val date = Instant.ofEpochMilli(dateMs).atZone(ZoneId.of("UTC")).toLocalDate()
                        val startMs = LocalDateTime.of(date, java.time.LocalTime.of(pickedStartHour, pickedStartMinute))
                            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                        var endMs = LocalDateTime.of(date, java.time.LocalTime.of(endState.hour, endState.minute))
                            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                        // Day-2 跨日修正（F3 挂账）：结束 ≤ 开始=跨日语义，end 回卷次日
                        //（默认 start+1h 在 23:xx 起始时由此合法；用户显式早于开始的
                        // 选择同样按次日结束理解，不再落 validateDraft 拒绝）
                        if (endMs <= startMs) endMs += 24L * 60 * 60 * 1000
                        vm.setSlot(stepIndex, startMs, endMs)
                    }
                    pickEndFor = null
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { pickEndFor = null }) { Text("取消") } },
        ) {
            TimePicker(state = endState)
        }
    }
}

/** 表单主体（§B：区块标签/好友 FilterChip/kind Segmented/步骤卡/提交钮）。 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ComposeForm(
    padding: PaddingValues,
    s: ComposeBundleViewModel.ComposeUiState,
    onToggleContact: (String) -> Unit,
    onKindChanged: (ProposalKind) -> Unit,
    onToggleRequired: (Int, String) -> Unit,
    onTitleChanged: (Int, String) -> Unit,
    onPickSlot: (Int) -> Unit,
    onAddStep: () -> Unit,
    onRemoveStep: (Int) -> Unit,
    onSubmit: () -> Unit,
    onAddFriend: () -> Unit,
) {
    val palette = LocalStColors.current
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(start = StScale.insetLg.dp, top = StScale.insetMd.dp, end = StScale.insetLg.dp, bottom = StUi.collabListTailDp.dp),
        verticalArrangement = Arrangement.spacedBy(StScale.insetMd.dp),
    ) {
        if (s.friendsError) {
            item {
                Text("暂时连不上中继，取不到好友名单", fontSize = StUi.collabFootnoteSp.sp, color = Color(palette.textSecondary))
            }
        }
        item { SectionLabel("和谁一起", if (s.selected.isEmpty()) "至少选一位" else null) }
        if (s.contacts.isEmpty()) {
            item {
                Column {
                    Text("还没有互相确认的好友", fontSize = StUi.collabFootnoteSp.sp, color = Color(palette.textSecondary))
                    TextButton(onClick = onAddFriend) {
                        Icon(Icons.Outlined.PersonAddAlt1, contentDescription = null, modifier = Modifier.size(StUi.collabActionIconDp.dp))
                        Spacer(Modifier.width(StScale.insetXs.dp))
                        Text("去好友里加一位")
                    }
                }
            }
        } else {
            item {
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(StScale.insetSm.dp),
                    verticalArrangement = Arrangement.spacedBy(StScale.insetSm.dp),
                ) {
                    s.contacts.forEach { f ->
                        FilterChip(
                            selected = f.userId in s.selected,
                            onClick = { onToggleContact(f.userId) },
                            label = { Text(f.userId) },
                        )
                    }
                }
            }
        }
        item { SectionLabel("怎么定", null) }
        item {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = s.kind == ProposalKind.DIRECTIVE,
                    onClick = { onKindChanged(ProposalKind.DIRECTIVE) },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                ) { Text("各司其职") }
                SegmentedButton(
                    selected = s.kind == ProposalKind.CONSENSUS,
                    onClick = { onKindChanged(ProposalKind.CONSENSUS) },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                ) { Text("大家投票") }
            }
        }
        item { SectionLabel(if (s.kind == ProposalKind.CONSENSUS) "候选时间" else "要做什么", null) }
        items(s.steps.size) { i ->
            StepCard(
                index = i,
                step = s.steps[i].draft,
                title = s.steps[i].title,
                kind = s.kind,
                selectedUids = s.selected.toList(),
                onTitleChanged = { onTitleChanged(i, it) },
                onToggleRequired = { uid -> onToggleRequired(i, uid) },
                onPickSlot = { onPickSlot(i) },
                onRemove = if (s.steps.size > 1) ({ onRemoveStep(i) }) else null,
            )
        }
        item {
            TextButton(onClick = onAddStep) {
                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(StUi.collabActionIconDp.dp))
                Spacer(Modifier.width(StScale.insetXs.dp))
                Text(if (s.kind == ProposalKind.CONSENSUS) "再加一个候选" else "再加一步")
            }
        }
        item {
            Button(onClick = onSubmit, enabled = !s.sending, modifier = Modifier.fillMaxWidth()) {
                Text(s.submitLabel)
            }
        }
    }
}

/** 区块标签（§B：14sp w600+可选 hint 12sp textSecondary）。 */
@Composable
private fun SectionLabel(title: String, hint: String?) {
    val palette = LocalStColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = StUi.collabSectionSp.sp, fontWeight = FontWeight.SemiBold)
        if (hint != null) {
            Spacer(Modifier.width(StScale.insetSm.dp))
            Text(hint, fontSize = StUi.collabHintSp.sp, color = Color(palette.textSecondary))
        }
    }
}

/** 步骤卡（§B：标题无框输入+close 钮+设置时段+directive 必答名单）。 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun StepCard(
    index: Int,
    step: ProposalStepDraft,
    title: String,
    kind: ProposalKind,
    selectedUids: List<String>,
    onTitleChanged: (String) -> Unit,
    onToggleRequired: (String) -> Unit,
    onPickSlot: () -> Unit,
    onRemove: (() -> Unit)?,
) {
    val palette = LocalStColors.current
    Card {
        Column(Modifier.padding(start = StScale.insetMd.dp, top = StScale.insetSm.dp, end = StScale.insetMd.dp, bottom = StScale.insetMd.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = title,
                    onValueChange = onTitleChanged,
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = {
                        Text(
                            if (kind == ProposalKind.CONSENSUS) "候选时段名（如「周六下午」）" else "这一步做什么",
                        )
                    },
                )
                if (onRemove != null) {
                    IconButton(onClick = onRemove) {
                        Icon(Icons.Outlined.Close, contentDescription = "移除", modifier = Modifier.size(StUi.collabMenuIconDp.dp))
                    }
                }
            }
            Spacer(Modifier.height(StScale.insetXs.dp))
            TextButton(onClick = onPickSlot) {
                Icon(Icons.Outlined.Schedule, contentDescription = null, modifier = Modifier.size(StUi.collabActionIconDp.dp))
                Spacer(Modifier.width(StScale.insetXs.dp))
                Text(
                    if (step.startsAtMs == null) {
                        "设置时段"
                    } else {
                        "${fmt(step.startsAtMs!!)} – ${fmt(step.endsAtMs ?: step.startsAtMs!!)}"
                    },
                )
            }
            if (kind == ProposalKind.DIRECTIVE) {
                Spacer(Modifier.height(StScale.insetXs.dp))
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(StScale.insetXs.dp),
                    verticalArrangement = Arrangement.spacedBy(StScale.insetXs.dp),
                ) {
                    selectedUids.forEach { uid ->
                        FilterChip(
                            selected = uid in step.requiredUids,
                            onClick = { onToggleRequired(uid) },
                            label = { Text(uid) },
                        )
                    }
                }
            }
        }
    }
}

/** 时段展示（§A：M/d HH:mm，双位补零）。 */
private fun fmt(ms: Long): String {
    val t = LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault())
    return "%d/%d %02d:%02d".format(t.monthValue, t.dayOfMonth, t.hour, t.minute)
}
