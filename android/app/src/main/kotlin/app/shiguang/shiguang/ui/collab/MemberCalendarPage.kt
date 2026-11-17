package app.shiguang.shiguang.ui.collab

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.PeopleOutline
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import app.shiguang.core.model.collab.CollabBundleHeader
import app.shiguang.core.model.collab.CollabStep
import app.shiguang.core.tokens.StScale
import app.shiguang.core.tokens.StUi
import app.shiguang.shiguang.ui.component.ShiguangBottomSheet
import app.shiguang.shiguang.ui.component.ShiguangScaffold
import app.shiguang.shiguang.ui.component.ShiguangSnackbarHost
import app.shiguang.shiguang.ui.component.showShiguangSnackbar
import app.shiguang.shiguang.ui.theme.LocalStColors
import kotlinx.coroutines.launch

/**
 * 协同日程页（behavior/member_calendar.md 转正清单 §B，T4.3）：包列表三态预占 +
 * 一键响应浮条（同意/拒绝并说明→双维拒绝面板）+ 时间轴/决策双入口 + 发起协同门禁。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemberCalendarPage(
    onOpenFriends: () -> Unit,
    onOpenCompose: () -> Unit,
    onOpenTimeline: (String) -> Unit,
    onOpenDecisions: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm: MemberCalendarViewModel = viewModel(factory = MemberCalendarViewModel.factory)
    val s by vm.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(vm) {
        vm.events.collect { msg -> snackbarHostState.showShiguangSnackbar(msg) }
    }
    LaunchedEffect(Unit) {
        vm.loadCurrentUser()
        vm.refresh()
    }
    // 发起返回刷新（Dart _compose「pop 后重拉」镜像）
    var wentCompose by rememberSaveable { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) wentCompose = true
            if (event == Lifecycle.Event.ON_RESUME && wentCompose) {
                wentCompose = false
                vm.refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // 响应浮层族宿主（不进 NavHost；返回键逐层=ShiguangBottomSheet 内建+BackHandler 压栈）
    var responseFor by remember { mutableStateOf<Pair<CollabBundleHeader, CollabStep>?>(null) }
    var rejectFor by remember { mutableStateOf<Pair<CollabBundleHeader, CollabStep>?>(null) }

    ShiguangScaffold(
        modifier = modifier,
        snackbarHostState = snackbarHostState,
        snackbarHost = { ShiguangSnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("协同日程") },
                actions = {
                    IconButton(onClick = onOpenFriends) {
                        Icon(Icons.Outlined.PeopleOutline, contentDescription = "好友")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { scope.launch { if (vm.loadComposeGate()) onOpenCompose() } },
                icon = { Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(StUi.collabActionIconDp.dp)) },
                text = { Text("发起协同") },
            )
        },
    ) { padding ->
        if (s.loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("加载中…")
            }
        } else if (s.bundles.isEmpty()) {
            EmptyCalendar(padding)
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(StScale.insetLg.dp),
                verticalArrangement = Arrangement.spacedBy(StScale.insetLg.dp),
            ) {
                items(s.bundles.size) { i ->
                    val view = s.bundles[i]
                    BundleCard(
                        view = view,
                        onRespond = { step -> responseFor = view.bundle to step },
                        onOpenTimeline = { onOpenTimeline(view.bundle.id) },
                        onOpenDecisions = { onOpenDecisions(view.bundle.id) },
                    )
                }
            }
        }
        // 一键响应浮条（§B）
        responseFor?.let { (bundle, step) ->
            ResponseSheet(
                bundle = bundle,
                step = step,
                onAccept = {
                    scope.launch { vm.accept(bundle, step) }
                    responseFor = null
                },
                onReject = {
                    responseFor = null
                    rejectFor = bundle to step
                },
                onDismiss = { responseFor = null },
            )
        }
        // 双维拒绝面板（D 页；关浮条后串行开）
        rejectFor?.let { (bundle, step) ->
            RejectPanel(
                stepId = step.id,
                participants = bundle.participants,
                currentUserId = s.currentUserId,
                onConfirm = { result ->
                    vm.reject(bundle, step, result)
                    rejectFor = null
                },
                onDismiss = { rejectFor = null },
            )
        }
    }
}

/** 空态（§B：groups 主图标+标题+引导文）。 */
@Composable
private fun EmptyCalendar(padding: PaddingValues) {
    val palette = LocalStColors.current
    Column(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = StScale.insetLg.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        Icon(Icons.Outlined.Groups, contentDescription = null, modifier = Modifier.size(StUi.collabHeroIconDp.dp), tint = Color(palette.textSecondary))
        Spacer(Modifier.height(StScale.insetLg.dp))
        Text("还没有协同日程", fontSize = StUi.collabHeroSp.sp, fontWeight = FontWeight.SemiBold, color = Color(palette.textPrimary))
        Spacer(Modifier.height(StScale.insetSm.dp))
        Text(
            "和朋友约起来，邀约会混合显示在这里——待确认、候选、已确认三态一眼分清。",
            color = Color(palette.textSecondary),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.weight(1f))
    }
}

/** 协同包卡（§B：头行双 Chip+N 人协同+时间轴/决策双钮+步骤三态瓦片）。 */
@Composable
private fun BundleCard(
    view: MemberCalendarViewModel.BundleView,
    onRespond: (CollabStep) -> Unit,
    onOpenTimeline: () -> Unit,
    onOpenDecisions: () -> Unit,
) {
    val palette = LocalStColors.current
    val tier = MemberCalendarViewModel.tierOf(view.bundle.status, view.bundle.proposalType)
    Card {
        Column(Modifier.padding(StUi.collabDimPadDp.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Card(
                    colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = Color(palette.sparkBg)),
                ) {
                    Text(
                        MemberCalendarViewModel.proposalLabel(view.bundle.proposalType),
                        modifier = Modifier.padding(horizontal = StScale.insetSm.dp, vertical = StUi.collabChipPadVDp.dp),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                Spacer(Modifier.width(StScale.insetSm.dp))
                androidx.compose.material3.Card(
                    colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = Color(palette.chipGrey)),
                ) {
                    Text(
                        MemberCalendarViewModel.statusLabel(view.bundle.status),
                        modifier = Modifier.padding(horizontal = StScale.insetSm.dp, vertical = StUi.collabChipPadVDp.dp),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "${view.bundle.participants.size} 人协同",
                    fontSize = StUi.collabFootnoteSp.sp,
                    color = Color(palette.textSecondary),
                )
            }
            Spacer(Modifier.height(StUi.collabRowGapDp.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                FilledTonalButton(onClick = onOpenTimeline) {
                    Icon(Icons.Outlined.Timeline, contentDescription = null, modifier = Modifier.size(StUi.collabActionIconDp.dp))
                    Spacer(Modifier.width(StScale.insetXs.dp))
                    Text("时间轴")
                }
                Spacer(Modifier.width(StScale.insetSm.dp))
                FilledTonalButton(onClick = onOpenDecisions) {
                    Icon(Icons.Outlined.AccountTree, contentDescription = null, modifier = Modifier.size(StUi.collabActionIconDp.dp))
                    Spacer(Modifier.width(StScale.insetXs.dp))
                    Text("决策")
                }
            }
            if (view.steps.isEmpty()) {
                Spacer(Modifier.height(StScale.insetSm.dp))
                Text("暂无步骤", color = Color(palette.textSecondary))
            } else {
                view.steps.forEach { step ->
                    StepTile(
                        step = step,
                        tier = tier,
                        onTap = if (tier == MemberCalendarViewModel.ReservationTier.CONFIRMED) null else ({ onRespond(step) }),
                    )
                }
            }
        }
    }
}

/** 步骤瓦片（§B 三态预占视觉：实底/主色虚线/浅灰细线+徽标+chevron）。 */
@Composable
private fun StepTile(
    step: CollabStep,
    tier: MemberCalendarViewModel.ReservationTier,
    onTap: (() -> Unit)?,
) {
    val palette = LocalStColors.current
    val badgeLabel = when (tier) {
        MemberCalendarViewModel.ReservationTier.CONFIRMED -> "已确认"
        MemberCalendarViewModel.ReservationTier.HARD_TENTATIVE -> "待确认"
        MemberCalendarViewModel.ReservationTier.SOFT_CANDIDATE -> "候选"
    }
    val badgeColor = when (tier) {
        MemberCalendarViewModel.ReservationTier.CONFIRMED -> palette.sparkStroke
        MemberCalendarViewModel.ReservationTier.HARD_TENTATIVE -> palette.sparkStroke
        MemberCalendarViewModel.ReservationTier.SOFT_CANDIDATE -> palette.textSecondary
    }
    val borderColor = when (tier) {
        MemberCalendarViewModel.ReservationTier.CONFIRMED -> Color(palette.sparkStroke)
        MemberCalendarViewModel.ReservationTier.HARD_TENTATIVE -> Color(palette.sparkStroke)
        MemberCalendarViewModel.ReservationTier.SOFT_CANDIDATE -> Color(palette.hairlineGrey)
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = StScale.insetSm.dp)
            .clickable(enabled = onTap != null, onClick = { onTap?.invoke() })
            .border(
                BorderStroke(
                    when (tier) {
                        MemberCalendarViewModel.ReservationTier.CONFIRMED -> StUi.collabIdleBorderDp.dp
                        MemberCalendarViewModel.ReservationTier.HARD_TENTATIVE -> StUi.collabSelectedBorderDp.dp
                        MemberCalendarViewModel.ReservationTier.SOFT_CANDIDATE -> StUi.collabIdleBorderDp.dp
                    },
                    borderColor,
                ),
                RoundedCornerShape(StUi.collabTileRadiusDp.dp),
            )
            .background(if (tier == MemberCalendarViewModel.ReservationTier.CONFIRMED) Color(palette.sparkBg) else Color.Transparent)
            .padding(horizontal = StScale.insetMd.dp, vertical = StUi.collabTilePadVDp.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("步骤 ${step.id}", fontSize = StUi.collabSectionSp.sp, modifier = Modifier.weight(1f))
            TierBadge(badgeLabel, Color(badgeColor))
            if (onTap != null) {
                Spacer(Modifier.width(StScale.insetXs.dp))
                Icon(Icons.Outlined.ChevronRight, contentDescription = null, modifier = Modifier.size(StUi.collabMenuIconDp.dp), tint = Color(palette.textSecondary))
            }
        }
    }
}

/** 徽标（§B：12sp w600，色 12% alpha 底 6 圆角）。 */
@Composable
private fun TierBadge(label: String, color: Color) {
    Box(
        Modifier
            .background(color.copy(alpha = StUi.collabBadgeAlpha), RoundedCornerShape(StUi.collabBadgeRadiusDp.dp))
            .padding(horizontal = StScale.insetSm.dp, vertical = StUi.collabChipPadVDp.dp),
    ) {
        Text(label, fontSize = StUi.collabHintSp.sp, fontWeight = FontWeight.SemiBold, color = color)
    }
}

/** 一键响应浮条（§B：标题+副题+同意/拒绝并说明双钮）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ResponseSheet(
    bundle: CollabBundleHeader,
    step: CollabStep,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalStColors.current
    ShiguangBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = StScale.insetLg.dp, vertical = StScale.insetLg.dp)) {
            Text("回应这一步", fontSize = StUi.collabHeroSp.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(StScale.insetXs.dp))
            Text(
                "步骤 ${step.id} · ${MemberCalendarViewModel.proposalLabel(bundle.proposalType)}",
                color = Color(palette.textSecondary),
            )
            Spacer(Modifier.height(StScale.insetLg.dp))
            androidx.compose.material3.Button(onClick = onAccept, modifier = Modifier.fillMaxWidth()) { Text("同意") }
            Spacer(Modifier.height(StScale.insetSm.dp))
            OutlinedButton(onClick = onReject, modifier = Modifier.fillMaxWidth()) { Text("拒绝并说明") }
        }
    }
}
