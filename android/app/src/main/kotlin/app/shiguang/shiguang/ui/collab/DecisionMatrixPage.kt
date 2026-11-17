package app.shiguang.shiguang.ui.collab

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.StickyNote2
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel

import app.shiguang.core.model.collab.CollabFsm
import app.shiguang.core.tokens.StScale
import app.shiguang.core.tokens.StUi
import app.shiguang.shiguang.ui.component.ShiguangScaffold
import app.shiguang.shiguang.ui.component.ShiguangSnackbarHost
import app.shiguang.shiguang.ui.component.showShiguangSnackbar
import app.shiguang.shiguang.ui.theme.LocalStColors

/**
 * 决策矩阵页（behavior/decision_matrix.md 转正清单 §B，T4.4）：发起人聚合决策面板
 * ——M-of-N 门限进度条+逐成员表决徽标+催办铃铛+冲突步骤闭环三键（转派/降级/裁撤，
 * 仅 clash 步骤挂=D27 F5 现状口径）+备忘置顶+方案级 Reopen/Cancel（outbox 落库）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DecisionMatrixPage(
    vm: DecisionMatrixViewModel,
    modifier: Modifier = Modifier,
    onOpenCanvas: (() -> Unit)? = null,
) {
    val s by vm.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(vm) {
        vm.events.collect { msg -> snackbarHostState.showShiguangSnackbar(msg) }
    }
    LaunchedEffect(Unit) { vm.load() }

    ShiguangScaffold(
        modifier = modifier,
        snackbarHostState = snackbarHostState,
        snackbarHost = { ShiguangSnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("决策") },
                // Day-2 F1（2026-11-17 用户裁决）：排期画板正式入口=决策矩阵页顶栏
                actions = {
                    if (onOpenCanvas != null) {
                        TextButton(onClick = onOpenCanvas) { Text("画板") }
                    }
                },
            )
        },
    ) { padding ->
        val state = s.state
        if (s.loading || state == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("加载中…")
            }
        } else {
            MatrixBody(padding, s, state, vm)
        }
    }
}

/** 矩阵主体（§B：头部卡/备忘置顶/步骤瓦片序/空态/底部方案级动作）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MatrixBody(
    padding: PaddingValues,
    s: DecisionMatrixViewModel.DecisionMatrixUiState,
    state: CollabFsm.BundleState,
    vm: DecisionMatrixViewModel,
) {
    val palette = LocalStColors.current
    var editBriefing by remember { mutableStateOf(false) }
    // 转派/降级选人对话框（§B SimpleDialog 对位：title+candidates）
    var pickFor by remember { mutableStateOf<Pair<String, List<String>>?>(null) } // title to candidates
    var pickAction by remember { mutableStateOf<((String) -> Unit)?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(StScale.insetLg.dp),
        verticalArrangement = Arrangement.spacedBy(StUi.collabFieldGapDp.dp),
    ) {
        item { HeaderCard(state) }
        item {
            BriefingSticky(
                briefing = state.briefing,
                isInitiator = s.isInitiator,
                onEdit = { editBriefing = true },
            )
        }
        val sorted = state.steps.values.sortedBy { it.stepId }
        if (sorted.isEmpty()) {
            item { EmptyMatrix() }
        } else {
            items(sorted.size) { i ->
                StepMatrixTile(
                    step = sorted[i],
                    state = s,
                    onNudge = { sid, uid -> vm.nudge(sid, uid) },
                    onReassign = { sid, uid -> vm.reassign(sid, uid) },
                    onBypass = { sid, uid -> vm.bypass(sid, uid) },
                    onPrune = { sid -> vm.prune(sid) },
                    onPick = { title, candidates, action -> pickFor = title to candidates; pickAction = action },
                )
            }
        }
        if (s.isInitiator) {
            item {
                Spacer(Modifier.height(StUi.collabFieldGapDp.dp))
                when {
                    state.status == CollabFsm.BundleStatus.CONFIRMED ->
                        FilledTonalButton(onClick = { vm.reopen() }, modifier = Modifier.fillMaxWidth()) { Text("重新打开方案") }
                    state.status != CollabFsm.BundleStatus.CANCELLED && state.status != CollabFsm.BundleStatus.EXPIRED ->
                        OutlinedButton(onClick = { vm.cancel() }, modifier = Modifier.fillMaxWidth()) { Text("取消方案") }
                }
            }
        }
    }
    if (editBriefing) {
        BriefingEditDialog(
            initial = state.briefing,
            onDismiss = { editBriefing = false },
            onSave = { text ->
                editBriefing = false
                vm.updateBriefing(text)
            },
        )
    }
    pickFor?.let { (title, candidates) ->
        AlertDialog(
            onDismissRequest = { pickFor = null; pickAction = null },
            title = { Text(title) },
            text = {
                Column {
                    candidates.forEach { uid ->
                        TextButton(onClick = {
                            pickFor = null
                            pickAction?.invoke(uid)
                            pickAction = null
                        }) {
                            Text(if (uid == s.me) "你 ($uid)" else uid)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { pickFor = null; pickAction = null }) { Text("取消") } },
        )
    }
}

/** 头部卡（§B：类型 Chip sparkBg+状态 Chip 灰底+N 人协同）。 */
@Composable
private fun HeaderCard(state: CollabFsm.BundleState) {
    val palette = LocalStColors.current
    Card {
        Row(
            Modifier.fillMaxWidth().padding(StUi.collabDimPadDp.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.background(Color(palette.sparkBg), RoundedCornerShape(50)).padding(horizontal = StScale.insetSm.dp, vertical = StUi.collabChipPadVDp.dp),
            ) {
                Text(MemberCalendarViewModel.proposalLabel(state.proposalType.name), style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.width(StScale.insetSm.dp))
            Box(
                Modifier.background(Color(palette.chipGrey), RoundedCornerShape(50)).padding(horizontal = StScale.insetSm.dp, vertical = StUi.collabChipPadVDp.dp),
            ) {
                Text(
                    MemberCalendarViewModel.statusLabel(state.status.name),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Spacer(Modifier.weight(1f))
            Text("${state.participants.size} 人协同", fontSize = StUi.collabFootnoteSp.sp, color = Color(palette.textSecondary))
        }
    }
}

/** 备忘置顶（§B：sparkBg 卡+提示文+发起人「编辑」；Day-2 F6：回显已存 briefing——
 *  replay 折叠 PROPOSE 草稿与 BRIEFING_UPDATE 的最新值，空则维持提示文）。 */
@Composable
private fun BriefingSticky(briefing: String?, isInitiator: Boolean, onEdit: () -> Unit) {
    val palette = LocalStColors.current
    Card(
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = Color(palette.sparkBg)),
    ) {
        Row(Modifier.fillMaxWidth().padding(StUi.collabDimPadDp.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.StickyNote2, contentDescription = null, modifier = Modifier.size(StUi.collabMenuIconDp.dp))
            Spacer(Modifier.width(StScale.insetSm.dp))
            Text(
                briefing?.takeIf { it.isNotBlank() } ?: "备忘：发起人可置顶一段对全员的说明",
                fontSize = StUi.collabFootnoteSp.sp,
                modifier = Modifier.weight(1f),
            )
            if (isInitiator) {
                TextButton(onClick = onEdit) { Text("编辑") }
            }
        }
    }
}

/** 空态（§B：visibility_off+「暂无步骤数据」+半盲提示）。 */
@Composable
private fun EmptyMatrix() {
    val palette = LocalStColors.current
    Column(
        Modifier.fillMaxWidth().padding(vertical = StUi.collabListTailDp.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Outlined.VisibilityOff, contentDescription = null, modifier = Modifier.size(StUi.collabHeroIconDp.dp), tint = Color(palette.textSecondary))
        Spacer(Modifier.height(StUi.collabFieldGapDp.dp))
        Text("暂无步骤数据", fontSize = StUi.collabEmptyTitleSp.sp, fontWeight = FontWeight.SemiBold, color = Color(palette.textPrimary))
        Spacer(Modifier.height(StScale.insetXs.dp))
        Text("该包密钥尚未取到，联网补齐后即可解密回放", color = Color(palette.textSecondary), textAlign = TextAlign.Center)
    }
}

/** 单步决策矩阵瓦片（§B：M-of-N 进度+表决徽标+Nudge+冲突步骤三键）。 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun StepMatrixTile(
    step: CollabFsm.StepState,
    state: DecisionMatrixViewModel.DecisionMatrixUiState,
    onNudge: (String, String) -> Unit,
    onReassign: (String, String) -> Unit,
    onBypass: (String, String) -> Unit,
    onPrune: (String) -> Unit,
    onPick: (String, List<String>, (String) -> Unit) -> Unit,
) {
    val palette = LocalStColors.current
    val need = DecisionMatrixViewModel.requiredCount(step)
    val have = step.acceptCount
    val ratio = if (need == 0) 0f else (have.toFloat() / need).coerceIn(0f, 1f)
    val ready = step.status == CollabFsm.StepStatus.READY
    val clash = step.status == CollabFsm.StepStatus.TIME_CLASH || step.status == CollabFsm.StepStatus.ROLE_CLASH
    val accent = if (ready) palette.sparkStroke else palette.textSecondary
    val pendingUids = step.requiredUids.filter { step.ballots[it]?.state == CollabFsm.VoteState.PENDING }

    Card {
        Column(Modifier.padding(StUi.collabDimPadDp.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("步骤 ${step.stepId}", fontSize = StUi.collabSectionSp.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                StatusTag(DecisionMatrixViewModel.stepStatusLabel(step.status), Color(accent))
            }
            Spacer(Modifier.height(StUi.collabRowGapDp.dp))
            LinearProgressIndicator(
                progress = { ratio },
                modifier = Modifier.fillMaxWidth().height(StUi.collabProgressBarDp.dp),
                color = Color(accent),
                trackColor = Color(palette.trackGrey),
            )
            Spacer(Modifier.height(StScale.insetXs.dp))
            Text("$have/$need 人同意", fontSize = StUi.collabHintSp.sp, color = Color(accent))
            Spacer(Modifier.height(StUi.collabRowGapDp.dp))
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(StScale.insetXs.dp),
                verticalArrangement = Arrangement.spacedBy(StScale.insetXs.dp),
            ) {
                step.requiredUids.forEach { uid ->
                    val ballot = step.ballots[uid]
                    val canNudge = state.isInitiator && ballot?.state == CollabFsm.VoteState.PENDING && !ready
                    MemberBadge(
                        uid = uid,
                        voteState = ballot?.state,
                        isYou = uid == state.me,
                        canNudge = canNudge,
                        onNudge = { onNudge(step.stepId, uid) },
                    )
                }
            }
            // 仅冲突（时间/权责）步骤挂闭环三键——日常 pending 不该打扰发起人（D27 F5）
            if (state.isInitiator && clash) {
                Spacer(Modifier.height(StUi.collabRowGapDp.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(StScale.insetSm.dp)) {
                    OutlinedButton(onClick = {
                        if (step.requiredUids.isNotEmpty()) onPick("转派给", step.requiredUids) { uid -> onReassign(step.stepId, uid) }
                    }) {
                        Icon(Icons.Outlined.SwapHoriz, contentDescription = null, modifier = Modifier.size(StUi.collabActionIconDp.dp))
                        Spacer(Modifier.width(StScale.insetXs.dp))
                        Text("转派")
                    }
                    OutlinedButton(
                        onClick = {
                            if (pendingUids.isNotEmpty()) onPick("降级成员", pendingUids) { uid -> onBypass(step.stepId, uid) }
                        },
                        enabled = pendingUids.isNotEmpty(),
                    ) {
                        Icon(Icons.Outlined.SkipNext, contentDescription = null, modifier = Modifier.size(StUi.collabActionIconDp.dp))
                        Spacer(Modifier.width(StScale.insetXs.dp))
                        Text("降级")
                    }
                    OutlinedButton(onClick = { onPrune(step.stepId) }) {
                        Icon(Icons.Outlined.ContentCut, contentDescription = null, modifier = Modifier.size(StUi.collabActionIconDp.dp))
                        Spacer(Modifier.width(StScale.insetXs.dp))
                        Text("裁撤")
                    }
                }
            }
        }
    }
}

/** 逐成员表决徽标（§B：圆点四色 accepted 绿/rejected 红/pendingRecheck 琥珀/其余灰；canNudge=铃铛尾缀+整 chip 可点）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MemberBadge(
    uid: String,
    voteState: CollabFsm.VoteState?,
    isYou: Boolean,
    canNudge: Boolean,
    onNudge: () -> Unit,
) {
    val palette = LocalStColors.current
    val dotColor = when (voteState) {
        CollabFsm.VoteState.ACCEPTED -> palette.voteAccepted
        CollabFsm.VoteState.REJECTED -> palette.voteRejected
        CollabFsm.VoteState.PENDING_RECHECK -> palette.votePendingRecheck
        else -> palette.textSecondary
    }
    InputChip(
        selected = false,
        onClick = { if (canNudge) onNudge() },
        avatar = {
            Box(
                Modifier
                    .size(StUi.collabDotDp.dp)
                    .background(Color(dotColor), CircleShape),
            )
        },
        label = { Text(if (isYou) "你" else uid, fontSize = StUi.collabHintSp.sp) },
        trailingIcon = if (canNudge) {
            { Icon(Icons.Outlined.NotificationsActive, contentDescription = "催办", modifier = Modifier.size(StUi.collabBadgeIconDp.dp)) }
        } else {
            null
        },
    )
}

/** 状态 tag（§B：色 12% alpha 底 6 圆角 12sp w600）。 */
@Composable
private fun StatusTag(text: String, color: Color) {
    Box(
        Modifier
            .background(color.copy(alpha = StUi.collabBadgeAlpha), RoundedCornerShape(StUi.collabBadgeRadiusDp.dp))
            .padding(horizontal = StScale.insetSm.dp, vertical = StUi.collabChipPadVDp.dp),
    ) {
        Text(text, fontSize = StUi.collabHintSp.sp, fontWeight = FontWeight.SemiBold, color = color)
    }
}

/** 备忘编辑对话框（§B：TextField maxLines 2+取消/保存；保存=trim 后落 outbox；
 *  Day-2 F6：预填已存 briefing——回显编辑不丢既有内容）。 */
@Composable
private fun BriefingEditDialog(onDismiss: () -> Unit, onSave: (String) -> Unit, initial: String? = null) {
    var text by rememberSaveable { mutableStateOf(initial ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑备忘") },
        text = {
            TextField(
                value = text,
                onValueChange = { text = it },
                maxLines = 2,
                placeholder = { Text("对全员的说明（选填）") },
            )
        },
        confirmButton = { Button(onClick = { onSave(text) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
