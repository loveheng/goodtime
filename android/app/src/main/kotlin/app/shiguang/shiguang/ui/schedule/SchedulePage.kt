package app.shiguang.shiguang.ui.schedule

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.shiguang.core.model.ScheduleBlock
import app.shiguang.core.model.ScheduleDay
import app.shiguang.core.tokens.StGesture
import app.shiguang.core.tokens.StScale
import app.shiguang.shiguang.ui.factsheet.FactVoucherSheet
import app.shiguang.core.tokens.StUi
import app.shiguang.shiguang.ui.timeline.Timeline

/**
 * 今日页（P4③主干页；schedule_page.md §A/§B/§E/§G/§H/§I 转正清单）：VM 单向流
 * （D15）+ §A 顶行三段切换（日｜周｜月 segmented 左段 Expanded+协同日程入口——
 * MemberCalendarPage 随协作页轮接线，先上屏入口）+ 视图态 rememberSaveable（丢失点
 * #9）+ 横滑翻页（day ±1 日/month ±1 月不保留几号；week 横滑归列滚动不响应；
 * ≥pageSwipeDp 触发）。周月=只读派生视图（WeekView/MonthView，点选跳日）；
 * day=执行主场（DayView：装配/命令/事件全经 VM）。
 */
private val WEEKDAY_LABELS = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SchedulePage(
    vm: TodayViewModel,
    modifier: Modifier = Modifier,
    onBlockTap: (ScheduleBlock) -> Unit = {},
    onBackgroundTap: (slotStartMin: Int, past: Boolean) -> Unit = { _, _ -> },
    onDegrade: (ScheduleBlock) -> Unit = {},
    onMelt: (ScheduleBlock) -> Unit = {},
    onSwap: (ScheduleBlock) -> Unit = {},
    onCreateFromEmpty: () -> Unit = {},
) {
    val selectedIso by vm.selectedIso.collectAsState()
    var viewMode by rememberSaveable { mutableStateOf("day") } // §A 三段视图态（丢失点 #9）
    val anchor = ScheduleDay.tryParseIsoDate(selectedIso)

    Column(modifier.fillMaxSize()) {
        // §A 顶行：三段切换（左段 Expanded）+协同日程入口（图标钮 tooltip「协同日程」）
        Row(
            Modifier.padding(start = StScale.insetLg.dp, end = StScale.insetLg.dp, top = StScale.insetSm.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                SegmentedButton(
                    selected = viewMode == "day",
                    onClick = { viewMode = "day" },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3),
                ) { Text("日") }
                SegmentedButton(
                    selected = viewMode == "week",
                    onClick = { viewMode = "week" },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3),
                ) { Text("周") }
                SegmentedButton(
                    selected = viewMode == "month",
                    onClick = { viewMode = "month" },
                    shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3),
                ) { Text("月") }
            }
            // 协同日程入口（routing_map）：顶栏「👥」已接 MemberCalendarPage（AppShell），
            // 页内挂账空钮已移除（edge-to-edge 美化轮——重复入口实证）
        }
        when (viewMode) {
            "week" -> WeekView(
                vm = vm,
                anchor = anchor ?: LocalDateToday(vm),
                onPick = { date ->
                    vm.selectDate(ScheduleDay.isoDate(date))
                    viewMode = "day" // §H：点块跳日视图定位该日
                },
                onWeekChange = { delta -> vm.shiftDate(delta * 7) },
                modifier = Modifier.weight(1f),
            )
            "month" -> MonthView(
                vm = vm,
                anchor = anchor ?: LocalDateToday(vm),
                onPick = { date ->
                    vm.selectDate(ScheduleDay.isoDate(date))
                    viewMode = "day" // §I：点格跳日视图
                },
                onMonthChange = { delta -> vm.shiftMonth(delta, preserveDay = true) },
                modifier = Modifier.weight(1f),
            )
            else -> DayView(
                vm = vm,
                modifier = Modifier
                    .weight(1f)
                    // 视图横滑翻页（§A）：累计 ≥60dp 判定一次；右滑=回退；仅 day 生效
                    .pointerInput("day_swipe") {
                        var dx = 0f
                        val threshold = StGesture.pageSwipeDp.dp.toPx()
                        detectHorizontalDragGestures(
                            onDragStart = { dx = 0f },
                            onDragEnd = {
                                if (kotlin.math.abs(dx) >= threshold) {
                                    if (dx > 0) vm.shiftDate(-1) else vm.shiftDate(1)
                                }
                            },
                        ) { change, dragAmount ->
                            dx += dragAmount
                            change.consume()
                        }
                    },
                onBlockTap = onBlockTap,
                onBackgroundTap = onBackgroundTap,
                onDegrade = onDegrade,
                onMelt = onMelt,
                onSwap = onSwap,
                onCreateFromEmpty = onCreateFromEmpty,
            )
        }
    }
}

private fun LocalDateToday(vm: TodayViewModel): java.time.LocalDate =
    ScheduleDay.scheduleDayOf(vm.now.value, 420)

/**
 * day 视图（执行主场，原 SchedulePage 主体迁移）：分钟墙钟 tick + 装配/命令/事件
 * 全经 VM；确认卡两态滚动驱动（NestedScrollConnection：forward 收缩/reverse 且
 * pixels<24 展开，不拦截滚动）；守卫态门控矩阵 §G；全局动作 sheet 页内浮层。
 */
@Composable
private fun DayView(
    vm: TodayViewModel,
    modifier: Modifier = Modifier,
    onBlockTap: (ScheduleBlock) -> Unit = {},
    onBackgroundTap: (slotStartMin: Int, past: Boolean) -> Unit = { _, _ -> },
    onDegrade: (ScheduleBlock) -> Unit = {},
    onMelt: (ScheduleBlock) -> Unit = {},
    onSwap: (ScheduleBlock) -> Unit = {},
    onCreateFromEmpty: () -> Unit = {},
) {
    val ui by vm.uiState.collectAsState()
    val selectedIso by vm.selectedIso.collectAsState()
    val now by vm.now.collectAsState()
    // 通关卡宿主（通关条/凭证入口共用浮层——fact_sheet.md §C；复制事件经 VM 2s）
    var voucherFact by remember { mutableStateOf<app.shiguang.core.model.Artifact?>(null) }
    var goNowTick by remember { mutableIntStateOf(0) }
    var collapsed by rememberSaveable { mutableStateOf(false) } // 确认卡收缩态（丢失点 #9）
    var showGlobalActions by remember { mutableStateOf(false) }
    // 时间轴滚动域提升（确认卡滚动判定消费）
    // 丢失点 #9：滚动偏移 rememberSaveable（进程重建回位；ScrollState.Saver 官方形态）
    val timelineScroll = androidx.compose.runtime.saveable.rememberSaveable(
        saver = androidx.compose.foundation.ScrollState.Saver,
    ) { androidx.compose.foundation.ScrollState(0) }
    // §E 展开阈值 24dp → 像素（remember 外取值，connection 捕获）
    val expandThresholdPx = with(LocalDensity.current) { StUi.scrollExpandThresholdDp.dp.toPx() }

    // 日切换复位确认卡展开态（§A：日切换复位确认卡展开态）
    LaunchedEffect(selectedIso) { collapsed = false }

    // 滚动驱动坍缩（§E 拍板回归 2026-10-08）：捕获时间轴竖向滚动——下滑浏览（forward）
    // 收为 44dp 胶囊，回滚至顶（pixels<24dp）恢复展开；不拦截滚动。
    val collapseConnection = remember(expandThresholdPx) {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (source != NestedScrollSource.UserInput) return Offset.Zero
                if (available.y < 0f) {
                    collapsed = true // forward：继续向下浏览 → 收缩
                } else if (available.y > 0f && timelineScroll.value < expandThresholdPx) {
                    collapsed = false // reverse 回滚至顶 → 展开
                }
                return Offset.Zero // 返回不拦截通知、滚动照常
            }
        }
    }

    Column(modifier.fillMaxSize()) {
        // 当前/下一个 glance（§3 派生；仅今日，Dart _DayView 纵向序镜像）
        val dTop = ui.data
        if (dTop?.isToday == true) {
            NowNextCard(repo = vm.repo, now = now)
        }
        // 导航+工具行（compact 密度；日期行独占整行完整显示）
        Row(
            Modifier.padding(start = StScale.insetLg.dp, end = StScale.insetLg.dp, top = StScale.insetSm.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = { vm.shiftDate(-1) },
                content = { Text("‹ 前一天", fontSize = StUi.compactTextSp.sp) },
            )
            TextButton(
                onClick = { vm.shiftDate(1) },
                content = { Text("后一天 ›", fontSize = StUi.compactTextSp.sp) },
            )
            Spacer(Modifier.weight(1f))
            val d = ui.data
            if (d != null && d.isToday) {
                TextButton(
                    onClick = { goNowTick++ },
                    content = { Text("回到现在", fontSize = StUi.compactTextSp.sp) },
                )
                IconButton(onClick = { showGlobalActions = true }) {
                    Icon(
                        Icons.Filled.WarningAmber,
                        contentDescription = "遇到突发情况？",
                        modifier = Modifier.size(StUi.smallIconDp.dp),
                    )
                }
            }
        }
        // 日期行
        Row(
            Modifier.padding(start = StScale.insetLg.dp, end = StScale.insetLg.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val d = ui.data
            val label = if (d?.isToday == true) "今天" else "日程"
            val date = ScheduleDay.tryParseIsoDate(selectedIso)
            if (date != null) {
                Text(
                    "$label · ${date.monthValue}月${date.dayOfMonth}日 ${WEEKDAY_LABELS[date.dayOfWeek.value - 1]}",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Spacer(Modifier.weight(1f))
            if (d?.isToday == true && d.spark != null) {
                Text(
                    "🔥 今日核心 1 项",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        if (ui.data?.isToday == false) {
            TextButton(
                onClick = { vm.pickToday() },
                modifier = Modifier.padding(start = StScale.insetLg.dp),
                content = { Text("回到今天", fontSize = StUi.compactTextSp.sp) },
            )
        }
        // 双轨仪表盘（§3.1；电量三档命令接线=§D）
        val dData = ui.data
        if (dData != null) {
            Dashboard(data = dData, onEnergySelect = { vm.setEnergy(it) })
        }
        // 临场通关条（action_pass_bar.md §C 插桩：仪表盘下、遗留区上；仅今日；窗口判定在 VM 装配段）
        val passes by vm.actionPasses.collectAsState()
        if (dData != null && dData.isToday && passes.isNotEmpty()) {
            ActionPassBar(passes = passes, onTap = { p -> voucherFact = p.fact })
        }
        // 守卫态派生（§G：sleepAdj−120 ≤ now < sleepAdj 2h 窗字面量；本地物理时钟）
        val windDown = dData?.isToday == true && run {
            val nowMin = ScheduleDay.minutesOfDay(now)
            nowMin >= dData.sleepAdj - 120 && nowMin < dData.sleepAdj
        }
        val proposedAi = dData?.proposedAi.orEmpty()
        // 纵向序（Dart _DayView 镜像）：遗留区 / 守卫横幅 / 确认卡 → 时间轴主体
        if (dData != null && !windDown && !dData.cleanSlate && dData.isToday && dData.leftovers.isNotEmpty()) {
            LeftoverSection(data = dData, vm = vm)
        }
        if (windDown) {
            WindDownBanner(data = dData!!, vm = vm)
        } else if (dData?.cleanSlate == true && dData.isToday) {
            CleanSlateBanner()
        } else if (dData?.isToday == true && proposedAi.isNotEmpty()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .nestedScroll(collapseConnection),
            ) {
                TieredConfirmCard(
                    proposed = proposedAi,
                    data = dData,
                    collapsed = collapsed,
                    onToggle = { collapsed = it },
                    vm = vm,
                )
            }
        }
        // 主体：装载态 / 空态 / 时间轴
        val d = ui.data
        when {
            d == null -> Column(
                Modifier.fillMaxSize().weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.weight(1f))
                CircularProgressIndicator()
                Spacer(Modifier.weight(1f))
            }
            d.visibleBlocks.isEmpty() -> Column(
                Modifier.fillMaxSize().weight(1f).padding(StScale.insetLg.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.weight(1f))
                Text(
                    if (d.isToday) "留白的一天，自由支配时间已备好。" else "这一天没有日程块。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (d.isToday) {
                    Spacer(Modifier.padding(StScale.insetXs.dp))
                    Text(
                        "想让 AI 排一排？打开桌面 AI 说「帮我排今天」",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.padding(StScale.insetSm.dp))
                    Button(onClick = onCreateFromEmpty) { Text("自己放一件事进来") }
                }
                Spacer(Modifier.weight(1f))
            }
            else -> Timeline(
                data = d,
                now = now,
                scrollState = timelineScroll,
                goNowTick = goNowTick,
                modifier = Modifier.fillMaxSize().weight(1f),
                onBlockTap = onBlockTap,
                onBackgroundTap = onBackgroundTap,
                onDegrade = onDegrade,
                onMelt = onMelt,
                onSwap = onSwap,
            )
        }
    }
    // 全局动作 sheet（页内浮层，不进 NavHost；§B 转正清单）
    val dBlocks = ui.data
    if (showGlobalActions && dBlocks != null && dBlocks.isToday) {
        GlobalActionSheet(
            todayBlocks = dBlocks.blocks,
            now = now,
            vm = vm,
            onDismiss = { showGlobalActions = false },
        )
    }
    // 丢失点 #8：浮层逐层返回（全局动作→通关卡）
    androidx.activity.compose.BackHandler(enabled = showGlobalActions) { showGlobalActions = false }
    androidx.activity.compose.BackHandler(enabled = voucherFact != null) { voucherFact = null }
    // 通关卡宿主（fact_sheet.md §C：通关条点按/独立入口同源装配；复制事件经 VM 2s）
    voucherFact?.let { f ->
        FactVoucherSheet(
            fact = f,
            onDismiss = { voucherFact = null },
            onCopy = { code -> vm.notifyFactCopied(code) },
        )
    }
}
