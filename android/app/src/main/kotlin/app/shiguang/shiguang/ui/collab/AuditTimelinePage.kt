package app.shiguang.shiguang.ui.collab

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.NoteAdd
import androidx.compose.material.icons.outlined.AddCircle
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.HowToVote
import androidx.compose.material.icons.outlined.Note
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.ReportProblem
import androidx.compose.material.icons.outlined.RocketLaunch
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.StickyNote2
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.RemoveCircle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.toRoute
import app.shiguang.core.model.collab.CollabStep
import app.shiguang.core.model.collab.StoredCollabEvent
import app.shiguang.core.tokens.StScale
import app.shiguang.core.tokens.StUi
import app.shiguang.shiguang.ui.component.ShiguangBottomSheet
import app.shiguang.shiguang.ui.component.ShiguangScaffold
import app.shiguang.shiguang.ui.component.ShiguangSnackbarHost
import app.shiguang.shiguang.ui.component.showShiguangSnackbar
import app.shiguang.shiguang.ui.theme.LocalStColors
import kotlinx.coroutines.launch

/**
 * 审计时间轴页（behavior/audit_timeline.md 转正清单 §B，T4.6）：无输入框只读垂直
 * 时间树（事件气泡结构化+REJECT 非本人半盲骨架提示）+ 步骤便签抽屉（≤50 字覆盖式）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuditTimelinePage(
    vm: AuditTimelineViewModel,
    modifier: Modifier = Modifier,
) {
    val s by vm.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(vm) {
        vm.events.collect { msg -> snackbarHostState.showShiguangSnackbar(msg) }
    }
    LaunchedEffect(Unit) { vm.load() }
    var noteSheetOpen by rememberSaveable { mutableStateOf(false) }

    ShiguangScaffold(
        modifier = modifier,
        snackbarHostState = snackbarHostState,
        snackbarHost = { ShiguangSnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("时间轴") },
                actions = {
                    IconButtonHost(onClick = { noteSheetOpen = true })
                },
            )
        },
    ) { padding ->
        if (s.loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { Text("加载中…") }
        } else if (s.events.isEmpty()) {
            EmptyTimeline(padding)
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(StScale.insetLg.dp),
                verticalArrangement = Arrangement.spacedBy(StScale.insetMd.dp), // Dart separator 12（insetMd 同档）
            ) {
                items(s.events.size) { i ->
                    EventBubble(
                        event = s.events[i],
                        isLast = i == s.events.size - 1,
                        me = s.me,
                    )
                }
            }
        }
        if (noteSheetOpen) {
            StepNoteSheet(
                steps = s.steps,
                onSave = { stepId, note ->
                    scope.launch {
                        vm.saveNote(stepId, note)
                        noteSheetOpen = false
                    }
                },
                onDismiss = { noteSheetOpen = false },
            )
        }
    }
}

/** AppBar 便签钮（note_add 图标 tooltip「步骤便签」——48dp 触控面由 IconButton 内建）。 */
@Composable
private fun IconButtonHost(onClick: () -> Unit) {
    androidx.compose.material3.IconButton(onClick = onClick) {
        Icon(Icons.AutoMirrored.Outlined.NoteAdd, contentDescription = "步骤便签")
    }
}

/** 空态（§B：timeline 主图标+标题+说明）。 */
@Composable
private fun EmptyTimeline(padding: PaddingValues) {
    val palette = LocalStColors.current
    Column(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = StScale.insetLg.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        Icon(Icons.Outlined.Timeline, contentDescription = null, modifier = Modifier.size(StUi.collabHeroIconDp.dp), tint = Color(palette.textSecondary))
        Spacer(Modifier.height(StScale.insetLg.dp))
        Text("还没有动态", fontSize = StUi.collabHeroSp.sp, fontWeight = FontWeight.SemiBold, color = Color(palette.textPrimary))
        Spacer(Modifier.height(StScale.insetSm.dp))
        Text(
            "方案从发起、响应到终态的全过程都会留在这里。",
            color = Color(palette.textSecondary),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.weight(1f))
    }
}

/** 单事件气泡（§B：左轨图标+连接线；sparkBg 圆角 12；who+动作+时间+骨架提示）。 */
@Composable
private fun EventBubble(event: StoredCollabEvent, isLast: Boolean, me: String?) {
    val palette = LocalStColors.current
    val who = if (event.senderId == me) "你" else event.senderId
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(actionIcon(event.actionType), contentDescription = null, modifier = Modifier.size(StUi.collabMenuIconDp.dp), tint = Color(palette.sparkStroke))
            if (!isLast) {
                Box(
                    Modifier
                        .width(StUi.collabRailWidthDp.dp)
                        .fillMaxHeight()
                        .padding(vertical = StScale.insetXs.dp)
                        .background(Color(palette.hairlineGrey)),
                )
            }
        }
        Spacer(Modifier.width(StUi.collabRowGapDp.dp))
        Box(
            Modifier
                .weight(1f)
                .background(Color(palette.sparkBg), RoundedCornerShape(StUi.collabCardRadiusDp.dp))
                .padding(StUi.collabDimPadDp.dp),
        ) {
            Column {
                Text("$who ${AuditTimelineViewModel.actionLabel(event.actionType)}", fontSize = StUi.collabSectionSp.sp)
                Spacer(Modifier.height(StScale.insetXs.dp))
                Text(
                    AuditTimelineViewModel.formatTime(event.serverAtMs),
                    color = Color(palette.textSecondary),
                    fontSize = StUi.collabHintSp.sp,
                )
                AuditTimelineViewModel.privacyHint(event.actionType, event.senderId, me)?.let { hint ->
                    Spacer(Modifier.height(StScale.insetXs.dp))
                    Text(hint, color = Color(palette.textSecondary), fontSize = StUi.collabHintSp.sp)
                }
            }
        }
    }
}

/** 动作→图标映射（§A 17 项对位 Dart _describe；未知=Circle）。 */
private fun actionIcon(action: String) = when (action) {
    "PROPOSE" -> Icons.Outlined.RocketLaunch
    "ACCEPT" -> Icons.Outlined.CheckCircle
    "REJECT" -> Icons.Outlined.ReportProblem
    "PATCH_TIME" -> Icons.Outlined.Schedule
    "REASSIGN" -> Icons.Outlined.SwapHoriz
    "MAKE_OPTIONAL" -> Icons.Outlined.RemoveCircle
    "BYPASS" -> Icons.Outlined.SkipNext
    "PRUNE" -> Icons.Outlined.ContentCut
    "REOPEN" -> Icons.Outlined.Replay
    "CANCEL" -> Icons.Outlined.Cancel
    "VOTE" -> Icons.Outlined.HowToVote
    "ADD_SLOT" -> Icons.Outlined.AddCircle
    "FORCE_FINALIZE" -> Icons.Outlined.Gavel
    "NUDGE" -> Icons.Outlined.NotificationsActive
    "BRIEFING_UPDATE" -> Icons.Outlined.StickyNote2
    "STEP_NOTE" -> Icons.Outlined.Note
    else -> Icons.Outlined.Circle
}

/** 步骤便签抽屉（§B：选步骤+≤50 字覆盖式+保存门禁 stepId!=null && trim 非空）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StepNoteSheet(
    steps: List<CollabStep>,
    onSave: suspend (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalStColors.current
    val scope = rememberCoroutineScope()
    var stepId by rememberSaveable { mutableStateOf<String?>(null) }
    var note by rememberSaveable { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    val canSave = stepId != null && note.trim().isNotEmpty()
    ShiguangBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = StScale.insetLg.dp, vertical = StScale.insetLg.dp)) {
            Text("步骤便签", fontSize = StUi.collabHeroSp.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(StScale.insetXs.dp))
            Text(
                "给某一步加一条 ≤50 字的备注，覆盖式更新，经加密随事件流入库。",
                color = Color(palette.textSecondary),
                fontSize = StUi.collabHintSp.sp,
            )
            Spacer(Modifier.height(StUi.collabFieldGapDp.dp))
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(StScale.insetSm.dp),
                verticalArrangement = Arrangement.spacedBy(StScale.insetSm.dp),
            ) {
                steps.forEach { step ->
                    FilterChip(
                        selected = stepId == step.id,
                        onClick = { stepId = step.id },
                        label = { Text("步骤 ${step.id}") },
                    )
                }
            }
            Spacer(Modifier.height(StUi.collabRowGapDp.dp))
            OutlinedTextField(
                value = note,
                onValueChange = { if (it.length <= AuditTimelineViewModel.NoteMaxLength) note = it },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 2,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                placeholder = { Text("备注内容（选填要点，不形成聊天流）") },
                supportingText = { Text("${note.length}/50") },
            )
            Spacer(Modifier.height(StUi.collabFieldGapDp.dp))
            Button(
                onClick = {
                    val sid = stepId ?: return@Button
                    scope.launch {
                        saving = true
                        onSave(sid, note.trim())
                        saving = false
                    }
                },
                enabled = canSave && !saving,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (saving) {
                    CircularProgressIndicator(modifier = Modifier.size(StUi.collabSpinnerDp.dp), strokeWidth = StUi.collabSpinnerStrokeDp.dp)
                } else {
                    Text("保存便签")
                }
            }
        }
    }
}
