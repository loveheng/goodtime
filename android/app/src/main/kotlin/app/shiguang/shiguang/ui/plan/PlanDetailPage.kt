package app.shiguang.shiguang.ui.plan

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.outlined.EventAvailable
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.ConfirmationNumber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.shiguang.core.model.ScheduleDay
import app.shiguang.core.tokens.StScale
import app.shiguang.core.tokens.StUi
import app.shiguang.shiguang.ui.component.ShiguangScaffold
import app.shiguang.shiguang.ui.component.ShiguangSnackbarHost
import app.shiguang.shiguang.ui.component.ShiguangSnackbarDuration
import app.shiguang.shiguang.ui.component.showShiguangSnackbar
import app.shiguang.shiguang.ui.nav.ShiguangRoute
import app.shiguang.shiguang.ui.theme.LocalStColors
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * 计划详情页（ui-pages §5；behavior/plan_detail.md 已过目转正 D24）：**默认查看态**
 * ——roadmap 进度行 / notes 首行「马上开始」高亮框 / open_items 手答（人机澄清闭环）/
 * 子树 ≤3 级 /「背景」「随行凭证」子页入口行；右上「编辑」进四字段编辑态（保存回
 * 查看态不关页）；「排期」=日期/时间双 picker → place_block。装载态静默（D24-F2：
 * 三态 UiState 消除 Dart 首帧闪「不存在」——装配自由度，基线采稳定态不受影响）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanDetailPage(
    vm: PlanDetailViewModel,
    onBack: () -> Unit,
    onOpenBackground: () -> Unit,
    onOpenFacts: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s by vm.uiState.collectAsState()
    val palette = LocalStColors.current
    val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }
    var errorToast by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // 排期双 picker 链（§D：日期→时间，取消任一步中止）
    var pickingDate by remember { mutableStateOf<LocalDate?>(null) }
    var pickingTime by remember { mutableStateOf<LocalDate?>(null) }
    // 丢失点 #8：picker 逐层返回（日期→时间压栈序，取消任一步中止=返回同语义）
    androidx.activity.compose.BackHandler(enabled = pickingTime != null) { pickingTime = null }
    androidx.activity.compose.BackHandler(enabled = pickingDate != null) { pickingDate = null }
    androidx.activity.compose.BackHandler(enabled = s.editing) { vm.toggleEditing() } // 编辑态返回=回查看态（不保存，§C 现状）

    LaunchedEffect(vm) {
        vm.events.collect { e ->
            errorToast = e.error
            snackbarHostState.showShiguangSnackbar(e.message, ShiguangSnackbarDuration.fromMs(e.durationMs))
        }
    }

    ShiguangScaffold(
        modifier = modifier,
        snackbarHostState = snackbarHostState,
        snackbarHost = {
            ShiguangSnackbarHost(
                snackbarHostState,
                containerColorOverride = if (errorToast) MaterialTheme.colorScheme.error else null,
            )
        },
        topBar = {
            TopAppBar(
                title = { Text(if (s.editing) "编辑计划" else "计划详情") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(onClick = { scope.launch { pickingDate = vm.scheduleDefaultDay() } }, enabled = s.plan != null) {
                        Icon(Icons.Outlined.EventAvailable, contentDescription = null, modifier = Modifier.size(StUi.iconSmDp.dp))
                        Text("排期")
                    }
                    TextButton(onClick = { vm.toggleEditing() }, enabled = s.plan != null) {
                        Text(if (s.editing) "完成" else "编辑")
                    }
                },
            )
        },
    ) { padding ->
        when {
            // F2：装载静默帧（不闪「不存在」）
            s.loading -> Box(Modifier.padding(padding).fillMaxSize())
            s.plan == null -> Box(
                Modifier.padding(padding).fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { Text("这条计划不存在了") }
            s.editing -> EditBody(vm, Modifier.padding(padding))
            else -> ViewBody(
                vm = vm,
                state = s,
                modifier = Modifier.padding(padding),
                onOpenBackground = onOpenBackground,
                onOpenFacts = onOpenFacts,
            )
        }
    }

    // 日期 picker（§A：initial/first=作息日，last=+30 天；选窗不可点范围外）
    pickingDate?.let { day ->
        val startMs = day.toEpochDay() * 86_400_000
        val endMs = day.plusDays(30).toEpochDay() * 86_400_000
        val dpState = rememberDatePickerState(
            initialSelectedDateMillis = startMs,
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis in startMs..endMs
            },
        )
        DatePickerDialog(
            onDismissRequest = { pickingDate = null },
            confirmButton = {
                TextButton(onClick = {
                    val ms = dpState.selectedDateMillis
                    if (ms != null) {
                        pickingDate = null
                        pickingTime = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
                    }
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { pickingDate = null }) { Text("取消") } },
        ) { DatePicker(state = dpState) }
    }
    // 时间 picker（§D：initial 09:00；确认→place_block）
    pickingTime?.let { day ->
        val state = rememberTimePickerState(initialHour = 9, initialMinute = 0)
        TimePickerDialog(
            onDismissRequest = { pickingTime = null },
            title = { Text("选择时间") },
            confirmButton = {
                TextButton(onClick = {
                    val start = state.hour * 60 + state.minute
                    pickingTime = null
                    vm.schedule(ScheduleDay.isoDate(day), start)
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { pickingTime = null }) { Text("取消") } },
        ) {
            TimePicker(state = state)
        }
    }
}

/** 查看态骨架（§B 逐字）。 */
@Composable
private fun ViewBody(
    vm: PlanDetailViewModel,
    state: PlanDetailViewModel.PlanDetailUiState,
    modifier: Modifier = Modifier,
    onOpenBackground: () -> Unit,
    onOpenFacts: () -> Unit,
) {
    val plan = state.plan!!
    val palette = LocalStColors.current
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(androidx.compose.runtime.saveable.rememberSaveable(saver = androidx.compose.foundation.ScrollState.Saver) { androidx.compose.foundation.ScrollState(0) })
            .padding(
                start = StScale.insetXl.dp,
                top = StScale.insetSm.dp,
                end = StScale.insetXl.dp,
                bottom = StUi.planPageBottomInsetDp.dp,
            ),
    ) {
        // roadmap 进度行（F1 裁决：派生进度行，Checklist 记号=内容书写约定）
        val spec = plan.spec
        val progress = spec?.let { roadmapProgress(it) }
        if (progress != null) {
            Text(
                "▶ $progress",
                modifier = Modifier.padding(bottom = StScale.insetSm.dp),
                fontSize = StUi.planRoadmapTextSp.sp,
                color = scheme.primary,
            )
        }
        Text(plan.title, style = MaterialTheme.typography.titleLarge)
        // 「马上开始」第一步高亮框（notes trim 非空）
        val notes = plan.notes?.trim()
        if (!notes.isNullOrEmpty()) {
            Spacer(Modifier.height(StScale.insetMd.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(scheme.primaryContainer, RoundedCornerShape(StScale.radiusCard.dp))
                    .padding(StScale.insetMd.dp),
            ) {
                Text("马上开始", style = MaterialTheme.typography.labelSmall, color = Color(palette.textSecondary))
                val lines = notes.split('\n')
                Text(lines.first(), style = MaterialTheme.typography.titleMedium)
                if (lines.size > 1) {
                    Text(
                        lines.drop(1).joinToString("\n"),
                        modifier = Modifier.padding(top = StUi.compactGapDp.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        if (!spec.isNullOrEmpty()) {
            Spacer(Modifier.height(StScale.insetMd.dp))
            Text(spec, style = MaterialTheme.typography.bodyMedium)
        }
        val reward = plan.rewardSpec
        if (!reward.isNullOrEmpty()) {
            Spacer(Modifier.height(StScale.insetMd.dp))
            Text("犒赏：$reward", style = MaterialTheme.typography.bodySmall, color = Color(palette.textSecondary))
        }
        // open_items 手答（人机澄清闭环）——查看态内联
        for ((i, item) in state.openItems.withIndex()) {
            if (item.answer == null) {
                Spacer(Modifier.height(StUi.compactGapLgDp.dp))
                Text("？${item.question}", style = MaterialTheme.typography.bodySmall, color = Color(palette.textSecondary))
                var answer by remember(i) { mutableStateOf("") }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = answer,
                        onValueChange = { answer = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        placeholder = { Text("写下答案，敲定它") },
                    )
                    TextButton(onClick = {
                        vm.answer(i, answer)
                        answer = ""
                    }) { Text("敲定") }
                }
            } else {
                Text(
                    "✓ ${item.question} → ${item.answer}",
                    modifier = Modifier.padding(top = StUi.compactGapDp.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(palette.textSecondary),
                )
            }
        }
        // 子计划（≤3 级缩进，§A walk 口径）
        if (state.subtree.isNotEmpty()) {
            Spacer(Modifier.height(StScale.insetMd.dp))
            Text("子计划", style = MaterialTheme.typography.labelMedium, color = Color(palette.textSecondary))
            for ((depth, p) in state.subtree) {
                Text(
                    "└ ${p.title}",
                    modifier = Modifier.padding(
                        start = (depth * StUi.planSubtreeIndentDp).dp,
                        top = StUi.compactPadDp.dp,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Spacer(Modifier.height(StScale.insetLg.dp))
        // 「背景」「随行凭证」子页入口行（目录形态，同设置页 _entryRow 口径）
        EntryRow(
            icon = { Icon(Icons.Outlined.Info, contentDescription = null, modifier = Modifier.size(StUi.planEntryIconDp.dp)) },
            title = "背景",
            subtitle = "身体状况、偏好、注意事项",
            onClick = onOpenBackground,
        )
        EntryRow(
            icon = { Icon(Icons.Outlined.ConfirmationNumber, contentDescription = null, modifier = Modifier.size(StUi.planEntryIconDp.dp)) },
            title = "随行凭证",
            subtitle = "车票/门票/预订与须知",
            onClick = onOpenFacts,
        )
    }
}

/** 子页入口行（Card bottom 8 + ListTile + chevron 20 textSecondary）。 */
@Composable
private fun EntryRow(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    val palette = LocalStColors.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = StScale.insetSm.dp),
        shape = RoundedCornerShape(StScale.radiusCard.dp),
    ) {
        ListItem(
            modifier = Modifier.clickable(onClick = onClick),
            leadingContent = icon,
            headlineContent = { Text(title) },
            supportingContent = { Text(subtitle) },
            trailingContent = {
                Icon(
                    Icons.Filled.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier.size(StUi.smallIconDp.dp),
                    tint = Color(palette.textSecondary),
                )
            },
        )
    }
}

/** 编辑态（§C：四字段表单，保存回查看态；trim 空=null=不改动）。 */
@Composable
private fun EditBody(vm: PlanDetailViewModel, modifier: Modifier = Modifier) {
    val title by vm.title.collectAsState()
    val spec by vm.spec.collectAsState()
    val notes by vm.notes.collectAsState()
    val reward by vm.reward.collectAsState()
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(androidx.compose.runtime.saveable.rememberSaveable(saver = androidx.compose.foundation.ScrollState.Saver) { androidx.compose.foundation.ScrollState(0) })
            .padding(
                start = StScale.insetXl.dp,
                top = StScale.insetSm.dp,
                end = StScale.insetXl.dp,
                bottom = StUi.planPageBottomInsetDp.dp,
            ),
    ) {
        OutlinedTextField(
            value = title,
            onValueChange = { vm.setTitle(it) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("标题") },
            singleLine = true,
        )
        Spacer(Modifier.height(StScale.insetMd.dp))
        OutlinedTextField(
            value = spec,
            onValueChange = { vm.setSpec(it) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("精确描述（spec）") },
            maxLines = 3,
        )
        Spacer(Modifier.height(StScale.insetMd.dp))
        OutlinedTextField(
            value = notes,
            onValueChange = { vm.setNotes(it) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("备注（首行写「马上开始」的第一步）") },
            maxLines = 2,
        )
        Spacer(Modifier.height(StScale.insetMd.dp))
        OutlinedTextField(
            value = reward,
            onValueChange = { vm.setReward(it) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("犒赏（打完这一仗怎么奖励自己）") },
            singleLine = true,
        )
        Spacer(Modifier.height(StScale.insetLg.dp))
        Button(onClick = { vm.save() }) { Text("保存") }
    }
}
