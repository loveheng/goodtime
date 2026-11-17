package app.shiguang.shiguang.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import app.shiguang.core.tokens.StMotion
import app.shiguang.shiguang.ui.theme.ShiguangMotion
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.shiguang.core.tokens.StScale
import app.shiguang.shiguang.ShiguangApplication
import app.shiguang.shiguang.ui.component.ShiguangScaffold
import app.shiguang.shiguang.ui.component.ShiguangSnackbarHost
import app.shiguang.shiguang.ui.component.ShiguangSnackbarDuration
import app.shiguang.shiguang.ui.component.showShiguangSnackbar
import app.shiguang.shiguang.ui.nav.ShiguangNavHost
import app.shiguang.shiguang.ui.nav.ShiguangRoute
import app.shiguang.shiguang.ui.nav.navigateTab
import app.shiguang.shiguang.ui.plans.PlansPage
import app.shiguang.shiguang.ui.plans.PlansViewModel
import app.shiguang.shiguang.ui.plan.PlanBackgroundPage
import app.shiguang.shiguang.ui.plan.PlanBackgroundViewModel
import app.shiguang.shiguang.ui.plan.PlanDetailPage
import app.shiguang.shiguang.ui.plan.PlanDetailViewModel
import app.shiguang.shiguang.ui.plan.PlanFactsPage
import app.shiguang.shiguang.ui.plan.PlanFactsViewModel
import app.shiguang.shiguang.ui.blocksheet.BlockSheet
import app.shiguang.shiguang.ui.blocksheet.NewBlockSheet
import app.shiguang.shiguang.ui.factsheet.FactMountSheet
import app.shiguang.shiguang.ui.factsheet.FactSheetsViewModel
import app.shiguang.shiguang.ui.factsheet.FactVoucherSheet
import androidx.navigation.toRoute
import app.shiguang.shiguang.ui.settings.ExceptionsSettingsPage
import app.shiguang.shiguang.ui.settings.PrefsSettingsPage
import app.shiguang.shiguang.ui.settings.RhythmSettingsPage
import app.shiguang.shiguang.ui.settings.SettingsPage
import app.shiguang.shiguang.ui.settings.SettingsViewModel
import app.shiguang.shiguang.ui.mcp.McpSettingsPage
import app.shiguang.shiguang.ui.settings.SubpagesViewModel
import app.shiguang.shiguang.ui.update.UpdatePage
import app.shiguang.shiguang.ui.schedule.QuickNoteDraft
import app.shiguang.shiguang.ui.schedule.SchedulePage
import app.shiguang.shiguang.ui.schedule.TodayViewModel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import app.shiguang.core.model.SettingsKeys
import kotlinx.serialization.json.contentOrNull

/**
 * 主框架壳（routing_map OP1/OP4 拍板：三顶层 destination，本件=bottomBar+FAB 宿主；
 * §A 转正清单）：底部三 Tab「日程｜清单｜设置」+ 右下角快记 FAB 两 Tab 常驻（守卫
 * 态不撤=快记永不关门平移；设置页无 FAB）+ AppBar 标题随 Tab + AI 建言推送面
 * （suggestion bus → SnackBar 8s+「去设置」跳设置 Tab）。
 * 今日页反馈面（命令/快记事件）在本壳统一消费——组件零直调 Snackbar。
 * Plans/Settings 两 Tab 内容区暂空占位（随 N7/N8 接页，不落文案）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShiguangAppShell(modifier: Modifier = Modifier) {
    val container = (LocalContext.current.applicationContext as ShiguangApplication).container
    val nav = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var floatingSnackbar by remember { mutableStateOf(false) }
    // ui-design §3.1（2026-11-18 拍板）：下滑隐藏/上滑重现改为设置项 nav_auto_hide（默认关=常驻）
    var navBarVisible by remember { mutableStateOf(true) }
    // 错误底色状态（update_page 转正清单 §A：error 口径 SnackBar——壳层统一提升）
    var errorSnackbar by remember { mutableStateOf(false) }
    val vm: TodayViewModel = viewModel(factory = TodayViewModel.factory)
    val plansVm: PlansViewModel = viewModel(factory = PlansViewModel.factory)
    val settingsVm: SettingsViewModel = viewModel(factory = SettingsViewModel.factory)
    // nav_auto_hide（uiOnly，默认 false=常驻）：开启时才挂滚动隐现连接（设置变更重挂连接）
    val settingsUi by settingsVm.uiState.collectAsState()
    val navAutoHide = (settingsUi.settings?.get(SettingsKeys.NAV_AUTO_HIDE) as? JsonPrimitive)
        ?.contentOrNull == "true"
    val navBarScrollConnection = remember(navAutoHide) {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            override fun onPreScroll(
                available: androidx.compose.ui.geometry.Offset,
                source: androidx.compose.ui.input.nestedscroll.NestedScrollSource,
            ): androidx.compose.ui.geometry.Offset {
                if (!navAutoHide) {
                    navBarVisible = true // 关闭开关（或默认）：强制常驻
                    return androidx.compose.ui.geometry.Offset.Zero
                }
                val dy = available.y
                if (dy < -40f) navBarVisible = false // 下滑内容→隐
                else if (dy > 40f) navBarVisible = true // 上滑→回
                return androidx.compose.ui.geometry.Offset.Zero
            }
        }
    }
    val subpagesVm: SubpagesViewModel = viewModel(factory = SubpagesViewModel.factory)
    var quickNoteInitial by remember { mutableStateOf<QuickNoteDraft?>(null) }
    var showQuickNote by remember { mutableStateOf(false) }
    // 浮层族宿主（D25 轮）：块弹层/新建双通道/挂载 sheet/通关卡——sheet 不进 NavHost
    val factSheetsVm: FactSheetsViewModel = viewModel(factory = FactSheetsViewModel.factory)
    var blockSheetBlock by remember { mutableStateOf<app.shiguang.core.model.ScheduleBlock?>(null) }
    var newSheetReq by remember { mutableStateOf<NewBlockRequest?>(null) }
    var mountFactId by remember { mutableStateOf<String?>(null) }
    var shellVoucher by remember { mutableStateOf<app.shiguang.core.model.Artifact?>(null) }
    // 手势·换乘候选选择面（D6 拍板：左滑过卡点呼出「换件轻松的？」候选列表显式挑选）
    var swapReq by remember {
        mutableStateOf<Pair<app.shiguang.core.model.ScheduleBlock, List<app.shiguang.core.model.Plan>>?>(null)
    }
    // 丢失点 #8：浮层逐层返回（嵌套浮层 BackHandler 逐层压栈消费，禁止透传页面级返回；
    // 后注册者优先=天然 LIFO——宿主 composition 序即层级）
    androidx.activity.compose.BackHandler(enabled = showQuickNote) {
        showQuickNote = false
        quickNoteInitial = null
        vm.onSheetClosed()
    }
    androidx.activity.compose.BackHandler(enabled = blockSheetBlock != null) { blockSheetBlock = null }
    androidx.activity.compose.BackHandler(enabled = newSheetReq != null) { newSheetReq = null }
    androidx.activity.compose.BackHandler(enabled = mountFactId != null) { mountFactId = null }
    androidx.activity.compose.BackHandler(enabled = shellVoucher != null) { shellVoucher = null }
    androidx.activity.compose.BackHandler(enabled = swapReq != null) { swapReq = null }

    // 今日页反馈面（D15 事件面）：四档时长消费；浮动=快记 1s FAB 净空；打卡撤销=restore_block
    LaunchedEffect(vm) {
        vm.events.collect { e ->
            floatingSnackbar = e.floating
            errorSnackbar = e.error
            val result = snackbarHostState.showShiguangSnackbar(
                e.message,
                ShiguangSnackbarDuration.fromMs(e.durationMs),
                actionLabel = e.actionLabel,
            )
            if (result == SnackbarResult.ActionPerformed && e.actionUndoBlockId != null) {
                vm.blockRestore(e.actionUndoBlockId!!, e.actionUndoBlockToStatus ?: app.shiguang.core.model.ScheduleBlock.STATUS_CONFIRMED)
            }
            // 融化补记（§5.4 Day-2 附问）：补记动作=按原块时段 retro_log 逆向记账
            if (result == SnackbarResult.ActionPerformed && e.actionRetroPlanId != null) {
                vm.retroLogMelted(
                    date = e.actionRetroDate!!,
                    startMin = e.actionRetroStartMin!!,
                    endMin = e.actionRetroEndMin!!,
                    planId = e.actionRetroPlanId,
                    label = e.actionRetroLabel,
                )
            }
        }
    }
    // 通关卡族反馈面（fact_sheet.md §C–§E：挂载/改挂/删除/复制事件）
    LaunchedEffect(factSheetsVm) {
        factSheetsVm.events.collect { e ->
            floatingSnackbar = e.floating
            errorSnackbar = e.error
            snackbarHostState.showShiguangSnackbar(e.message, ShiguangSnackbarDuration.fromMs(e.durationMs))
        }
    }
    // 分享入册→挂载 sheet 唤起（GateViewModel.mountedBus → 容器总线上抛，浮层族轮 D25 接线）
    LaunchedEffect(Unit) {
        container.mountedFactId.collect { mountFactId = it }
    }
    // 清单页反馈面（N7）：归档撤销（ActionPerformed→undoArchive）/失败 3s
    LaunchedEffect(plansVm) {
        plansVm.events.collect { e ->
            floatingSnackbar = e.floating
            errorSnackbar = e.error
            val result = snackbarHostState.showShiguangSnackbar(
                e.message,
                ShiguangSnackbarDuration.fromMs(e.durationMs),
                actionLabel = e.actionLabel,
            )
            if (result == SnackbarResult.ActionPerformed && e.actionUndoPlanId != null) {
                plansVm.undoArchive(e.actionUndoPlanId!!, e.actionUndoVersion ?: 0L)
            }
        }
    }
    // 设置页反馈面（N8a）：采纳/复制事件
    LaunchedEffect(settingsVm) {
        settingsVm.events.collect { e ->
            floatingSnackbar = e.floating
            errorSnackbar = e.error
            snackbarHostState.showShiguangSnackbar(e.message, ShiguangSnackbarDuration.fromMs(e.durationMs))
        }
    }
    // 基线采集浮层面（capture stage3/4 open_overlay → 合成态截图；release 无发射方）：
    // block_sheet 族=种子块（id=baseline_ov_a）唤 BlockSheet；fact_sheet 族挂账随验收轮
    LaunchedEffect(Unit) {
        container.baselineOverlayBus.collect { req ->
            when (req.split(":").firstOrNull()) {
                "block_sheet", "block_sheet_edit" -> {
                    val blocks = vm.uiState.value.data?.blocks.orEmpty()
                    blockSheetBlock = blocks.firstOrNull { it.id == "baseline_ov_a" }
                        ?: blocks.firstOrNull { it.status == "proposed" || it.status == "confirmed" }
                }
            }
        }
    }
    // 调试走查导航面（NativeDebugBridgeWiring nav 动作 → 路由；release 无发射方）
    LaunchedEffect(Unit) {
        container.navRequests.collect { route ->
            if (route == ShiguangRoute.Today || route == ShiguangRoute.Plans || route == ShiguangRoute.Settings) {
                nav.navigateTab(route)
            } else {
                nav.navigate(route)
            }
        }
    }
    // AI 建言推送面（§A）：suggest_user_setting → SnackBar 8s + 「去设置」跳设置 Tab
    LaunchedEffect(Unit) {
        container.suggestionBus.collect { s ->
            val key = (s["key"] as? JsonPrimitive)?.contentOrNull ?: ""
            val reason = (s["reason"] as? JsonPrimitive)?.contentOrNull ?: ""
            floatingSnackbar = false
            val result = snackbarHostState.showShiguangSnackbar(
                "AI 建议修改「$key」：$reason",
                duration = ShiguangSnackbarDuration.SEC8,
                actionLabel = "去设置",
            )
            if (result == SnackbarResult.ActionPerformed) {
                nav.navigateTab(ShiguangRoute.Settings)
            }
        }
    }

    val backStack by nav.currentBackStackEntryAsState()
    val isToday = backStack?.destination?.hasRoute(ShiguangRoute.Today::class.qualifiedName.orEmpty(), null) == true
    // Day-2 残留修复：子路由（PlanDetail/PlanBackground/PlanFacts/Plan 族其余 + Collab 族）
    // 也要归入所属 Tab——否则标题/底部选中恒落「设置」else 分支（用户报「跳转有残留」）
    val destRoute = backStack?.destination?.route ?: ""
    val routePrefix = "app.shiguang.shiguang.ui.nav.ShiguangRoute."
    val isPlansTab = destRoute == routePrefix + "Plans"
    val isPlans = isPlansTab ||
        destRoute.startsWith(routePrefix + "PlanDetail") ||
        destRoute.startsWith(routePrefix + "PlanBackground") ||
        destRoute.startsWith(routePrefix + "PlanFacts")
    val isTodayFamily = isToday || destRoute.startsWith(routePrefix + "Collab")
    val isSettingsMain = destRoute == routePrefix + "Settings"
    // 主三 Tab 才显示壳层大标题与 Tab 级修饰；子路由（详情/编辑器族）自带标题栏
    val isTopLevel = isTodayFamily && isToday || isPlansTab || isSettingsMain
    val isTopLevelFamily = isTodayFamily || isPlans || isSettingsMain
    val tabTitle = when {
        isToday -> "日程"
        isPlans -> "清单"
        else -> "设置"
    }

    ShiguangScaffold(
        modifier = modifier,
        snackbarHostState = snackbarHostState,
        snackbarHost = {
            // 浮动 SnackBar（FAB 净空 §3.2 层叠规则）：快记 1s 提交反馈避让右下角 FAB
            if (floatingSnackbar) {
                Box(Modifier.padding(bottom = StScale.fabClearanceDp.dp, start = StScale.insetLg.dp, end = StScale.insetLg.dp)) {
                    ShiguangSnackbarHost(
                        snackbarHostState,
                        containerColorOverride = if (errorSnackbar) MaterialTheme.colorScheme.error else null,
                    )
                }
            } else {
                ShiguangSnackbarHost(
                    snackbarHostState,
                    containerColorOverride = if (errorSnackbar) MaterialTheme.colorScheme.error else null,
                )
            }
        },
        topBar = {
            // 子路由（详情/编辑器族）自带标题栏，隐藏壳层大标题避免双标题堆叠；
            // 进出走 §3.3 导航过渡档：淡出+垂直收展（220ms ease-out）
            AnimatedVisibility(
                visible = isTopLevel,
                enter = fadeIn(tween(StMotion.navTransitionMs, easing = ShiguangMotion.easeOut)) +
                    expandVertically(animationSpec = tween(StMotion.navTransitionMs, easing = ShiguangMotion.easeOut)),
                exit = fadeOut(tween(StMotion.navTransitionMs, easing = ShiguangMotion.easeOut)) +
                    shrinkVertically(animationSpec = tween(StMotion.navTransitionMs, easing = ShiguangMotion.easeOut)),
            ) {
                TopAppBar(
                    title = { Text(tabTitle) },
                    actions = {
                        // 协同日程入口（ui-pages §9.1：日程 Tab 顶部「👥」图标钮——T4.9 接线）
                        if (isToday) {
                            IconButton(onClick = { nav.navigate(ShiguangRoute.CollabMemberCalendar) }) {
                                Icon(Icons.Outlined.Groups, contentDescription = "协同日程")
                            }
                        }
                    },
                )
            }
        },
        bottomBar = {
            // ui-design §3.1（2026-11-18 拍板）：64dp 仅图标（无标签，双层结构在 64dp 含手势带内裁切）
            // + 下滑隐藏/上滑重现（scroll-aware chrome，slide 动效）
            AnimatedVisibility(
                visible = navBarVisible,
                enter = slideInVertically { it },
                exit = slideOutVertically { it },
            ) {
                NavigationBar(
                    modifier = Modifier.height(72.dp),
                    // 内建 bottom inset（手势带 24dp）保留：图标+标签悬于其上，
                    // 与系统小横条不重叠（对齐云闪付同级高度）
                    containerColor = MaterialTheme.colorScheme.background,
                ) {
                    // ui-design §3.1（2026-11-18 拍板，云闪付参考）：选中态无药丸底，
                    // 纯靠图标着色区分——选中=primary，未选中=中性灰
                    val navItemColors = NavigationBarItemDefaults.colors(
                        indicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    )
                    NavigationBarItem(
                        selected = isTodayFamily,
                        onClick = { nav.navigateTab(ShiguangRoute.Today) },
                        icon = {
                            Icon(
                                Icons.Outlined.CalendarMonth,
                                contentDescription = "日程",
                                tint = if (isTodayFamily) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        label = { Text("日程") },
                        colors = navItemColors,
                    )
                    NavigationBarItem(
                        selected = isPlans,
                        onClick = { nav.navigateTab(ShiguangRoute.Plans) },
                        icon = {
                            Icon(
                                Icons.AutoMirrored.Outlined.ListAlt,
                                contentDescription = "清单",
                                tint = if (isPlans) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        label = { Text("清单") },
                        colors = navItemColors,
                    )
                    NavigationBarItem(
                        selected = !isTodayFamily && !isPlans,
                        onClick = { nav.navigateTab(ShiguangRoute.Settings) },
                        icon = {
                            Icon(
                                Icons.Outlined.Settings,
                                contentDescription = "设置",
                                tint = if (!isTodayFamily && !isPlans) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        label = { Text("设置") },
                        colors = navItemColors,
                    )
                }
            }
        },
        floatingActionButton = {
            // 快记两 Tab 恒在（OP1）；设置页与子路由（详情/编辑器）无 FAB
            if (isToday || isPlansTab) {
                val hasDraft by vm.hasDraft.collectAsState()
                QuickNoteFab(
                    hasDraft = hasDraft,
                    onOpen = {
                        // 打开抽屉前预载草稿（防异步回填覆盖输入）
                        scope.launch {
                            val draft = vm.loadDraft()
                            quickNoteInitial = draft
                            showQuickNote = true
                        }
                    },
                )
            }
        },
        content = { padding ->
            // 丢失点 #8（Day-2 修订 2026-11-17）：仅保留底部手势带避让；横向保留区曾致
            // 内容两侧各 30dp 额外内缩（+页面 insetLg=46dp 大外边界，用户报「左右没全屏」）。
            // 回退手势区横排透传=系统返回手势区与内容重叠属标准 e2e 行为。
            // 丢失点 #8（Day-2 五轮修订）：手势带扣减已由 Scaffold bottomBar padding 承担，
            // 此处再叠 windowInsetsPadding 属重复扣减——内容区底界恒定、导航栏缩减全部
            // 无效（用户报「总高度一直没降」的真正根因）。横向保留区此前亦已撤销。
            Box(
                Modifier
                    .padding(padding)
                    .nestedScroll(navBarScrollConnection),
            ) {
                ShiguangNavHost(navController = nav, startDestination = ShiguangRoute.Today) {
                    composable<ShiguangRoute.Today> {
                        SchedulePage(
                            vm = vm,
                            onBlockTap = { blockSheetBlock = it },
                            onBackgroundTap = { slot, past ->
                                newSheetReq = vm.uiState.value.data?.let { NewBlockRequest(it, slot, past) }
                            },
                            onCreateFromEmpty = {
                                newSheetReq = vm.uiState.value.data?.let { NewBlockRequest(it, null, false) }
                            },
                            // 三手势命令链接线（S4 对拍发现项）：降档/融化直发命令；
                            // 换乘=候选直查→空池轻提示 / 非空弹「换件轻松的？」候选选择
                            onDegrade = { vm.degradeBlock(it) },
                            onMelt = { vm.meltBlock(it) },
                            onSwap = { b ->
                                scope.launch {
                                    val candidates = vm.swapCandidates(b)
                                    if (candidates.isEmpty()) {
                                        snackbarHostState.showShiguangSnackbar(
                                            "愿望池没有可换乘的轻松事（light/anywhere 且未被排期）",
                                            ShiguangSnackbarDuration.fromMs(3000),
                                        )
                                    } else {
                                        swapReq = b to candidates
                                    }
                                }
                            },
                        )
                    }
                    composable<ShiguangRoute.Plans> {
                        PlansPage(
                            vm = plansVm,
                            factVm = factSheetsVm,
                            onOpenPlanDetail = { planId ->
                                nav.navigate(ShiguangRoute.PlanDetail(planId))
                            },
                            onOpenQuickNote = {
                                // 空态记一笔=快记抽屉统一通道（§B；预载草稿防回填覆盖）
                                scope.launch {
                                    val draft = vm.loadDraft()
                                    quickNoteInitial = draft
                                    showQuickNote = true
                                }
                            },
                        )
                    }
                    composable<ShiguangRoute.Settings> {
                        SettingsPage(
                            vm = settingsVm,
                            onNavigate = { route -> nav.navigate(route) },
                        )
                    }
                    // 设置族三编辑器接装（N8b）；update/mcp 接装 N8c；account 占位随协作轮
                    composable<ShiguangRoute.RhythmSettings> { RhythmSettingsPage(vm = subpagesVm) }
                    composable<ShiguangRoute.ExceptionsSettings> { ExceptionsSettingsPage(vm = subpagesVm) }
                    composable<ShiguangRoute.PrefsSettings> { PrefsSettingsPage(vm = subpagesVm) }
                    composable<ShiguangRoute.Update> { UpdatePage(onClose = { nav.popBackStack() }) }
                    composable<ShiguangRoute.McpDebug> { McpSettingsPage() }
                    // 协同账号页接装（T4.8——占位退役）
                    composable<ShiguangRoute.Account> { app.shiguang.shiguang.ui.collab.AccountPage() }
                    // 协作九页接装随协作轮——扫码页（D26 夜链）+好友页（T4.1）
                    composable<ShiguangRoute.CollabScan> {
                        app.shiguang.shiguang.ui.collab.ScanPage(onDone = { nav.popBackStack() })
                    }
                    composable<ShiguangRoute.CollabFriends> {
                        app.shiguang.shiguang.ui.collab.FriendsPage(
                            onOpenMyQr = { nav.navigate(ShiguangRoute.CollabMyQr) },
                            onOpenScan = { nav.navigate(ShiguangRoute.CollabScan) },
                            onVerify = { uid -> nav.navigate(ShiguangRoute.CollabSafetyNumber(uid)) },
                        )
                    }
                    composable<ShiguangRoute.CollabMemberCalendar> {
                        app.shiguang.shiguang.ui.collab.MemberCalendarPage(
                            onOpenFriends = { nav.navigate(ShiguangRoute.CollabFriends) },
                            onOpenCompose = { nav.navigate(ShiguangRoute.CollabComposeBundle) },
                            onOpenTimeline = { bid -> nav.navigate(ShiguangRoute.CollabAuditTimeline(bid)) },
                            onOpenDecisions = { bid -> nav.navigate(ShiguangRoute.CollabDecisionMatrix(bid)) },
                        )
                    }
                    composable<ShiguangRoute.CollabDecisionMatrix> { entry ->
                        val r = entry.toRoute<ShiguangRoute.CollabDecisionMatrix>()
                        val vm: app.shiguang.shiguang.ui.collab.DecisionMatrixViewModel = viewModel(
                            key = "decision_matrix_${r.bundleId}",
                            factory = app.shiguang.shiguang.ui.collab.DecisionMatrixViewModel.factory(r.bundleId),
                        )
                        app.shiguang.shiguang.ui.collab.DecisionMatrixPage(
                            vm = vm,
                            // Day-2 F1：排期画板正式入口（窗口 0,0=画板页按已载步骤自适配）
                            onOpenCanvas = {
                                nav.navigate(
                                    app.shiguang.shiguang.ui.nav.ShiguangRoute.CollabCanvas(r.bundleId, 0L, 0L),
                                )
                            },
                        )
                    }
                    composable<ShiguangRoute.CollabComposeBundle> {
                        app.shiguang.shiguang.ui.collab.ComposeBundlePage(
                            onAddFriend = { nav.navigate(ShiguangRoute.CollabFriends) },
                            onDone = { nav.popBackStack() },
                        )
                    }
                    composable<ShiguangRoute.CollabAuditTimeline> { entry ->
                        val r = entry.toRoute<ShiguangRoute.CollabAuditTimeline>()
                        val vm: app.shiguang.shiguang.ui.collab.AuditTimelineViewModel = viewModel(
                            key = "audit_timeline_${r.bundleId}",
                            factory = app.shiguang.shiguang.ui.collab.AuditTimelineViewModel.factory(r.bundleId),
                        )
                        app.shiguang.shiguang.ui.collab.AuditTimelinePage(vm = vm)
                    }
                    composable<ShiguangRoute.CollabMyQr> {
                        app.shiguang.shiguang.ui.collab.MyQrPage()
                    }
                    composable<ShiguangRoute.CollabSafetyNumber> { entry ->
                        val r = entry.toRoute<ShiguangRoute.CollabSafetyNumber>()
                        app.shiguang.shiguang.ui.collab.SafetyNumberPage(friendUserId = r.friendUserId)
                    }
                    composable<ShiguangRoute.CollabCanvas> { entry ->
                        val r = entry.toRoute<ShiguangRoute.CollabCanvas>()
                        app.shiguang.shiguang.ui.collab.CollabCanvasPage(
                            bundleId = r.bundleId,
                            windowStartMs = r.windowStartMs,
                            windowEndMs = r.windowEndMs,
                        )
                    }
                    // 详情族三页接装（D24 轮：plan_detail + 背景子页 + 凭证子页，planId 随路由注入）
                    composable<ShiguangRoute.PlanDetail> { entry ->
                        val route = entry.toRoute<ShiguangRoute.PlanDetail>()
                        val vm: PlanDetailViewModel = viewModel(
                            key = "plan_detail_${route.planId}",
                            factory = PlanDetailViewModel.factory(route.planId),
                        )
                        PlanDetailPage(
                            vm = vm,
                            onBack = { nav.popBackStack() },
                            onOpenBackground = { nav.navigate(ShiguangRoute.PlanBackground(route.planId)) },
                            onOpenFacts = { nav.navigate(ShiguangRoute.PlanFacts(route.planId)) },
                        )
                    }
                    composable<ShiguangRoute.PlanBackground> { entry ->
                        val route = entry.toRoute<ShiguangRoute.PlanBackground>()
                        val vm: PlanBackgroundViewModel = viewModel(
                            key = "plan_background_${route.planId}",
                            factory = PlanBackgroundViewModel.factory(route.planId),
                        )
                        PlanBackgroundPage(vm = vm, onBack = { nav.popBackStack() })
                    }
                    composable<ShiguangRoute.PlanFacts> { entry ->
                        val route = entry.toRoute<ShiguangRoute.PlanFacts>()
                        val vm: PlanFactsViewModel = viewModel(
                            key = "plan_facts_${route.planId}",
                            factory = PlanFactsViewModel.factory(route.planId),
                        )
                        PlanFactsPage(vm = vm, onBack = { nav.popBackStack() })
                    }
                }
                quickNoteInitial?.let { draft ->
                    if (showQuickNote) {
                        QuickNoteSheet(
                            initial = draft,
                            vm = vm,
                            onDismiss = {
                                showQuickNote = false
                                quickNoteInitial = null
                            },
                        )
                    }
                }
                // 块弹层（M1 点块表单；block_sheet.md）
                blockSheetBlock?.let { b ->
                    BlockSheet(vm = vm, block = b, onDismiss = { blockSheetBlock = null })
                }
                // 新建事项/逆向记账双通道（new_block_sheets.md）
                newSheetReq?.let { r ->
                    NewBlockSheet(vm = vm, data = r.data, slotStart = r.slotStart, past = r.past, onDismiss = { newSheetReq = null })
                }
                // 挂载 sheet（分享入册→归属归一；fact_sheet.md §D）
                mountFactId?.let { fid ->
                    FactMountSheet(
                        factId = fid,
                        vm = factSheetsVm,
                        onDismiss = { mountFactId = null },
                        onOpenVoucher = { f -> shellVoucher = f },
                    )
                }
                // 换乘候选选择（D6 拍板形态：显式挑选经 SwapBlockCommand 换入）
                swapReq?.let { (b, candidates) ->
                    AlertDialog(
                        onDismissRequest = { swapReq = null },
                        title = { Text("换件轻松的？") },
                        text = {
                            Column {
                                for (p in candidates) {
                                    TextButton(onClick = {
                                        swapReq = null
                                        p.id?.let { vm.swapBlock(b, it) }
                                    }) { Text(p.title) }
                                }
                            }
                        },
                        confirmButton = {},
                    )
                }
                // 通关卡宿主（挂载 sheet 常驻组点按）
                shellVoucher?.let { f ->
                    FactVoucherSheet(
                        fact = f,
                        onDismiss = { shellVoucher = null },
                        onCopy = { code -> factSheetsVm.notifyFactCopied(code) },
                    )
                }
            }
        },
    )
}

/** 空槽/空态唤起请求（new_block_sheets.md：data+slot+past 三参聚合）。 */
private data class NewBlockRequest(
    val data: app.shiguang.shiguang.ui.schedule.ScheduleDayData,
    val slotStart: Int?,
    val past: Boolean,
)
