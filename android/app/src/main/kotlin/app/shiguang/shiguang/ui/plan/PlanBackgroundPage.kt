package app.shiguang.shiguang.ui.plan

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.shiguang.core.model.Background
import app.shiguang.core.model.ScheduleDay
import app.shiguang.core.tokens.StScale
import app.shiguang.core.tokens.StUi
import app.shiguang.shiguang.ui.component.ShiguangBottomSheet
import app.shiguang.shiguang.ui.component.ShiguangScaffold
import app.shiguang.shiguang.ui.component.ShiguangSnackbarDuration
import app.shiguang.shiguang.ui.component.ShiguangSnackbarHost
import app.shiguang.shiguang.ui.component.showShiguangSnackbar
import app.shiguang.shiguang.ui.theme.LocalStColors
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * 计划「背景」子页（ui-pages §9.4；behavior/plan_background.md 已过目转正 D24）：
 * 进行中列表（点按编辑）+「添加背景」+ 超期沉「过去的背景」折叠组（组内逐条删/
 * 清空已过期，均二次确认；主列表不加清理图标降噪）。写通道全经命令层（human）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanBackgroundPage(
    vm: PlanBackgroundViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ui by vm.uiState.collectAsState()
    val palette = LocalStColors.current
    val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }
    var errorToast by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var editingBg by remember { mutableStateOf<Background?>(null) }
    var showSheet by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<List<Background>?>(null) }
    // 丢失点 #8：sheet→确认弹框逐层返回
    androidx.activity.compose.BackHandler(enabled = confirmDelete != null) { confirmDelete = null }
    androidx.activity.compose.BackHandler(enabled = showSheet) {
        showSheet = false
        editingBg = null
    }

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
                title = { Text("背景") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        val view = ui.view
        Column(
            modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(StScale.insetLg.dp),
        ) {
            // 头行：进行中 + 添加背景（§B）
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "进行中",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(palette.textSecondary),
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { editingBg = null; showSheet = true }) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(StUi.planAddIconDp.dp))
                    Text("添加背景")
                }
            }
            // 空态（§B：active 空）
            if (view != null && view.active.isEmpty()) {
                Text(
                    "还没有背景——身体状况、偏好、注意事项都记在这",
                    modifier = Modifier.padding(vertical = StScale.insetMd.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(palette.textSecondary),
                )
            }
            // 进行中条目卡（点按编辑；角标=派生渲染不入库）
            for (b in view?.active ?: emptyList()) {
                BackgroundRow(
                    content = b.content,
                    tags = b.tags,
                    badge = b.applicableDates?.let { backgroundBadge(it) },
                    onClick = { editingBg = b; showSheet = true },
                )
            }
            // 过去的背景折叠组（§B：默认收起；组内清理）
            val expired = view?.expired ?: emptyList()
            if (expired.isNotEmpty()) {
                Spacer(Modifier.height(StScale.insetXs.dp))
                PastGroup(
                    title = "过去的背景",
                    count = expired.size,
                    rows = {
                        for (b in expired) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { editingBg = b; showSheet = true }
                                    .padding(horizontal = StScale.insetMd.dp, vertical = StScale.insetXs.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(b.content, style = MaterialTheme.typography.bodySmall)
                                    b.applicableDates?.let { dates ->
                                        if (dates.isNotEmpty()) {
                                            Text(
                                                backgroundBadge(dates) ?: "",
                                                fontSize = StUi.plansMetaTextSp.sp,
                                                color = Color(palette.textSecondary),
                                            )
                                        }
                                    }
                                }
                                TextButton(onClick = { confirmDelete = listOf(b) }) { Text("删除") }
                            }
                        }
                        TextButton(
                            onClick = { confirmDelete = expired },
                            modifier = Modifier.padding(start = StScale.insetMd.dp, bottom = StScale.insetSm.dp),
                        ) {
                            Icon(Icons.Outlined.CleaningServices, contentDescription = null, modifier = Modifier.size(StUi.iconSmDp.dp))
                            Text("清空已过期")
                        }
                    },
                )
            }
        }
    }

    // 编辑 sheet（新增/编辑共用 §C）
    if (showSheet) {
        BackgroundEditSheet(
            existing = editingBg,
            vm = vm,
            showSnackbar = { msg ->
                scope.launch {
                    snackbarHostState.showShiguangSnackbar(msg, ShiguangSnackbarDuration.fromMs(3000))
                }
            },
            onDismiss = {
                showSheet = false
                editingBg = null
            },
            onDelete = { bg ->
                // 编辑态删除：先关 sheet 再二次确认（§C——Dart pop→confirm 同序）
                showSheet = false
                editingBg = null
                confirmDelete = listOf(bg)
            },
        )
    }
    // 删除/清空共用二次确认（§D）
    confirmDelete?.let { targets ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(if (targets.size == 1) "删除这条背景？" else "清空这 ${targets.size} 条过去的背景？") },
            text = { Text("删除后无法恢复。") },
            confirmButton = {
                Button(onClick = {
                    confirmDelete = null
                    vm.deleteBackgrounds(targets)
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("取消") } },
        )
    }
}

/** 进行中条目行（§B：content bodyMedium + tags 小字 + 角标 trailing）。 */
@Composable
private fun BackgroundRow(
    content: String,
    tags: List<String>,
    badge: String?,
    onClick: () -> Unit,
) {
    val palette = LocalStColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = StUi.compactGapDp.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(content, style = MaterialTheme.typography.bodyMedium)
            if (tags.isNotEmpty()) {
                Text(
                    tags.joinToString(" "),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(palette.textSecondary),
                )
            }
        }
        if (!badge.isNullOrEmpty()) {
            Text(badge, fontSize = StUi.plansMetaTextSp.sp, color = Color(palette.textSecondary))
        }
    }
}

/** 折叠组通用形态（§B：过去组 Card 头行 expand 图标 + 默认收起；凭证组同形复用）。 */
@Composable
internal fun PastGroup(
    title: String,
    count: Int,
    rows: @Composable () -> Unit,
) {
    val palette = LocalStColors.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(StScale.radiusCard.dp),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = StScale.insetMd.dp, vertical = StUi.planGroupHeadVDp.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(StUi.iconSmDp.dp),
                    tint = Color(palette.textSecondary),
                )
                Spacer(Modifier.width(StUi.compactGapDp.dp))
                Text(
                    "$title · $count 条",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(palette.textSecondary),
                )
            }
            if (expanded) rows()
        }
    }
}

/** 背景编辑 sheet（§C：内容/标签/作用日期窗；新增 autofocus；end 禁用前置于 start）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BackgroundEditSheet(
    existing: Background?,
    vm: PlanBackgroundViewModel,
    showSnackbar: (String) -> Unit,
    onDismiss: () -> Unit,
    onDelete: (Background) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var content by remember { mutableStateOf(existing?.content ?: "") }
    var tags by remember { mutableStateOf(existing?.tags?.joinToString(" ") ?: "") }
    var start by remember { mutableStateOf<LocalDate?>(null) }
    var end by remember { mutableStateOf<LocalDate?>(null) }
    val focusRequester = remember { FocusRequester() }
    // 日期窗回填（编辑：dates 首末）+ 新增 autofocus
    LaunchedEffect(existing) {
        val dates = existing?.applicableDates
        if (!dates.isNullOrEmpty()) {
            start = ScheduleDay.tryParseIsoDate(dates.first())
            end = ScheduleDay.tryParseIsoDate(dates.last())
        }
        if (existing == null) focusRequester.requestFocus()
    }
    // sheet 内日期 picker：'s'=开始 / 'e'=结束（丢失点 #8：picker 层返回先于 sheet）
    var picking by remember { mutableStateOf<Char?>(null) }
    androidx.activity.compose.BackHandler(enabled = picking != null) { picking = null }
    val today = LocalDate.now()
    val windowLast = today.plusDays(365)

    ShiguangBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = StScale.insetXl.dp, end = StScale.insetXl.dp, bottom = StScale.insetXl.dp),
        ) {
            Text(if (existing == null) "添加背景" else "编辑背景", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(StScale.insetMd.dp))
            OutlinedTextField(
                value = content,
                onValueChange = { content = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                label = { Text("背景内容") },
                placeholder = { Text("例如：妈妈膝盖不好，少长台阶陡坡") },
                maxLines = 3,
            )
            Spacer(Modifier.height(StScale.insetMd.dp))
            OutlinedTextField(
                value = tags,
                onValueChange = { tags = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("标签（空格分隔）") },
                placeholder = { Text("#健康 #体力") },
                singleLine = true,
            )
            Spacer(Modifier.height(StScale.insetXs.dp))
            Text(
                "作用日期窗（不选=长期）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(StUi.compactGapDp.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { picking = 's' }, modifier = Modifier.weight(1f)) {
                    Text(start?.toString() ?: "开始日期")
                }
                Spacer(Modifier.width(StScale.insetSm.dp))
                OutlinedButton(onClick = { picking = 'e' }, enabled = start != null, modifier = Modifier.weight(1f)) {
                    Text(
                        when {
                            start == null -> "—"
                            end == null -> start.toString()
                            else -> end.toString()
                        },
                    )
                }
                if (start != null) {
                    IconButton(onClick = { start = null; end = null }) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "清除日期窗（改为长期）",
                            modifier = Modifier.size(StUi.iconSmDp.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(StScale.insetMd.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (existing != null) {
                    TextButton(onClick = { onDelete(existing) }) {
                        Text("删除", color = Color(0xFFFF5252)) // Dart Colors.redAccent 逐字
                    }
                }
                Spacer(Modifier.weight(1f))
                Button(onClick = {
                    scope.launch {
                        val window = start?.let { s ->
                            val e = end ?: s
                            if (e.isBefore(s)) {
                                showSnackbar("结束日期早于开始日期")
                                return@launch
                            }
                            dateWindowDays(s, e)
                        }
                        val ok = vm.save(existing, content, splitTags(tags), window)
                        if (ok) onDismiss()
                    }
                }) { Text("保存") }
            }
        }
    }

    // 日期 picker（§C：开始 initial=今日 2020..今日+365；结束 initial=end??start first=start）
    picking?.let { which ->
        val isStart = which == 's'
        val initial = if (isStart) (start ?: today) else (end ?: start ?: today)
        val rangeStart = if (isStart) LocalDate.of(2020, 1, 1) else start ?: today
        val startMs = rangeStart.toEpochDay() * 86_400_000
        val endMs = windowLast.toEpochDay() * 86_400_000
        val dpState = rememberDatePickerState(
            initialSelectedDateMillis = initial.toEpochDay() * 86_400_000,
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis in startMs..endMs
            },
        )
        DatePickerDialog(
            onDismissRequest = { picking = null },
            confirmButton = {
                TextButton(onClick = {
                    val ms = dpState.selectedDateMillis
                    if (ms != null) {
                        val picked = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
                        if (isStart) start = picked else end = picked
                    }
                    picking = null
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { picking = null }) { Text("取消") } },
        ) { DatePicker(state = dpState) }
    }
}
