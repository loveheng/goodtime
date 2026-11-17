package app.shiguang.shiguang.ui.plans

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FolderShared
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.shiguang.core.tokens.StUi
import kotlinx.coroutines.launch
import app.shiguang.core.model.Plan
import app.shiguang.core.tokens.StScale
import app.shiguang.shiguang.ui.component.ShiguangBottomSheet
import app.shiguang.shiguang.ui.theme.LocalStColors

/**
 * 清单页（ui-pages §5；behavior/plans_page.md §A–§E 已过目转正）：三分区单页
 * 滚动（今天/当下推进中/待安排四象限）+ 冷藏池折叠 + 搜索标题子串过滤 + PlanCard
 * （左滑归档撤销/长按 sheet/删除确认）+ 空池态记一笔（快记抽屉统一通道——壳层
 * onOpenQuickNote 上抛）+ 未归属凭证入口（面板随浮层族轮）+ PlanDetail 路由接线。
 * 数据/命令全经 PlansViewModel（D15 同形）——组件零直调命令面。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun PlansPage(
    vm: PlansViewModel,
    factVm: app.shiguang.shiguang.ui.factsheet.FactSheetsViewModel,
    onOpenPlanDetail: (String) -> Unit,
    onOpenQuickNote: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ui by vm.uiState.collectAsState()
    val query by vm.query.collectAsState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var actionSheetPlan by remember { mutableStateOf<Plan?>(null) }
    var deleteConfirmPlan by remember { mutableStateOf<Plan?>(null) }
    // 浮层族宿主（D25 轮）：未归属管理面板 + 通关卡（N7 入口空壳回接）
    var unattrPanel by remember { mutableStateOf(false) }
    var voucherFact by remember { mutableStateOf<app.shiguang.core.model.Artifact?>(null) }
    val palette = LocalStColors.current

    fun filter(list: List<Plan>): List<Plan> =
        if (query.isEmpty()) list else list.filter { it.title.lowercase().contains(query.lowercase()) }

    val view = ui.view
    when {
        ui.loading -> Column(
            modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))
            androidx.compose.material3.CircularProgressIndicator()
            Spacer(Modifier.weight(1f))
        }
        view == null -> Box(modifier.fillMaxSize())
        // 空池态（§B：四分区全空；搜索非空走无命中态——出口隐藏）
        view.isEmpty && query.isEmpty() -> Column(
            modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))
            Text(
                "池子空空的——想到什么就记下来，30 秒的事",
                style = MaterialTheme.typography.bodySmall,
                color = Color(palette.textSecondary),
            )
            Spacer(Modifier.height(StScale.insetLg.dp))
            FilledTonalButton(onClick = onOpenQuickNote) { Text("记一笔") }
            Spacer(Modifier.weight(1f))
        }
        else -> {
            val today = filter(view.today)
            val inProgress = filter(view.inProgress)
            val waiting = filter(view.waiting)
            val archived = filter(view.archived)
            val searching = query.isNotEmpty()
            val noHit = searching && today.isEmpty() && inProgress.isEmpty() &&
                waiting.isEmpty() && archived.isEmpty()
            if (noHit) {
                Column(modifier.fillMaxSize()) {
                    SearchBar(vm = vm, query = query)
                    Spacer(Modifier.weight(1f))
                    Text(
                        "没有匹配「$query」的计划",
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(palette.textSecondary),
                    )
                    Spacer(Modifier.weight(1f))
                }
            } else {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(start = StScale.insetLg.dp, end = StScale.insetLg.dp, bottom = StScale.fabClearanceDp.dp),
                ) {
                    SearchBar(vm = vm, query = query)
                    // 未归属凭证入口（§B：N=0 隐藏；面板随浮层族轮——挂账空点击）
                    if (view.unattributedCount > 0) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(bottom = StScale.insetSm.dp)
                                .background(Color(palette.voucherBg), RoundedCornerShape(StScale.radiusCard.dp))
                                .clickable { unattrPanel = true }
                                .padding(horizontal = StScale.insetMd.dp, vertical = StUi.plansRowVDp.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.FolderShared, contentDescription = null, modifier = Modifier.size(StUi.iconSmDp.dp))
                                Spacer(Modifier.width(StScale.insetSm.dp))
                                Text(
                                    "${view.unattributedCount} 条未归属凭证",
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.W600,
                                )
                                Icon(
                                    Icons.Filled.ChevronRight,
                                    contentDescription = null,
                                    modifier = Modifier.size(StUi.iconSmDp.dp),
                                    tint = Color(palette.textSecondary),
                                )
                            }
                        }
                    }
                    if (today.isNotEmpty()) {
                        SectionTitle("今天")
                        for (p in today) {
                            PlanCard(
                                plan = p,
                                proposed = view.proposedByPlan[p.id ?: ""] ?: 0,
                                spark = view.sparkPlanIds.contains(p.id),
                                onOpenPlanDetail = onOpenPlanDetail,
                                onArchiveUndo = { scope.launch { vm.archiveWithUndo(p) } },
                                onActionSheet = { actionSheetPlan = p },
                            )
                        }
                    }
                    if (inProgress.isNotEmpty()) {
                        SectionTitle("当下推进中")
                        for (p in inProgress) {
                            PlanCard(
                                plan = p,
                                proposed = view.proposedByPlan[p.id ?: ""] ?: 0,
                                spark = false,
                                onOpenPlanDetail = onOpenPlanDetail,
                                onArchiveUndo = { scope.launch { vm.archiveWithUndo(p) } },
                                onActionSheet = { actionSheetPlan = p },
                            )
                        }
                    }
                    if (waiting.isNotEmpty()) {
                        SectionTitle("待安排")
                        for ((title, group) in quadrants(waiting)) {
                            GroupTitle(title, group.size)
                            for (p in group) {
                                PlanCard(
                                    plan = p,
                                    proposed = 0,
                                    spark = false,
                                    onOpenPlanDetail = onOpenPlanDetail,
                                    onArchiveUndo = { scope.launch { vm.archiveWithUndo(p) } },
                                    onActionSheet = { actionSheetPlan = p },
                                )
                            }
                        }
                    }
                    if (archived.isNotEmpty()) {
                        ColdPoolSection(plans = archived, onRestore = { vm.restore(it) })
                    }
                }
            }
        }
    }
    // 长按动作 sheet（§D：归档/删除/取消；删除先 pop 再确认弹框）
    actionSheetPlan?.let { plan ->
        ShiguangBottomSheet(onDismissRequest = { actionSheetPlan = null }) {
            ListItem(
                modifier = Modifier.clickableRow { actionSheetPlan = null },
                headlineContent = { Text("归档") },
                leadingContent = { Icon(Icons.Outlined.Inventory2, contentDescription = null) },
            )
            ListItem(
                modifier = Modifier.clickableRow {
                    actionSheetPlan = null
                    deleteConfirmPlan = plan
                },
                headlineContent = { Text("删除") },
                leadingContent = { Icon(Icons.Outlined.Delete, contentDescription = null) },
            )
            ListItem(
                modifier = Modifier.clickableRow { actionSheetPlan = null },
                headlineContent = { Text("取消") },
                leadingContent = { Icon(Icons.Filled.Close, contentDescription = null) },
            )
        }
    }
    // 删除二次确认（词汇表「删除」）
    deleteConfirmPlan?.let { plan ->
        AlertDialog(
            onDismissRequest = { deleteConfirmPlan = null },
            title = { Text("删除这条计划？") },
            text = { Text("「${plan.title}」将从清单移除，无法恢复。") },
            confirmButton = {
                Button(onClick = {
                    deleteConfirmPlan = null
                    vm.deletePlan(plan)
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirmPlan = null }) { Text("取消") }
            },
        )
    }
    // 丢失点 #8：浮层逐层返回（动作 sheet→确认弹框→面板→通关卡压栈序）
    androidx.activity.compose.BackHandler(enabled = actionSheetPlan != null) { actionSheetPlan = null }
    androidx.activity.compose.BackHandler(enabled = deleteConfirmPlan != null) { deleteConfirmPlan = null }
    androidx.activity.compose.BackHandler(enabled = unattrPanel) { unattrPanel = false }
    androidx.activity.compose.BackHandler(enabled = voucherFact != null) { voucherFact = null }
    // 未归属管理面板（fact_sheet.md §E：查看/长按改挂/左滑删除——D25 轮回接）
    if (unattrPanel) {
        app.shiguang.shiguang.ui.factsheet.UnattributedFactsPanel(
            vm = factVm,
            onDismiss = { unattrPanel = false },
            onOpenVoucher = { voucherFact = it },
        )
    }
    voucherFact?.let { f ->
        app.shiguang.shiguang.ui.factsheet.FactVoucherSheet(
            fact = f,
            onDismiss = { voucherFact = null },
            onCopy = { code -> factVm.notifyFactCopied(code) },
        )
    }
}

/** 搜索框（§B：页顶常驻，trim 即滤，非空 suffix 清空钮）。 */
@Composable
private fun SearchBar(vm: PlansViewModel, query: String) {
    OutlinedTextField(
        value = query,
        onValueChange = { vm.setSearch(it) },
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = StScale.insetSm.dp, bottom = StScale.insetXs.dp),
        singleLine = true,
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(StUi.smallIconDp.dp)) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                androidx.compose.material3.IconButton(onClick = { vm.setSearch("") }) {
                    Icon(Icons.Filled.Close, contentDescription = "清空", modifier = Modifier.size(StUi.iconSmDp.dp))
                }
            }
        },
        placeholder = { Text("搜计划标题…") },
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        modifier = Modifier.padding(top = StScale.insetMd.dp, bottom = StScale.insetXs.dp),
        style = MaterialTheme.typography.titleSmall,
    )
}

@Composable
private fun GroupTitle(text: String, count: Int) {
    Text(
        "$text · $count",
        modifier = Modifier.padding(top = StScale.insetSm.dp, bottom = StScale.insetXs.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 四象限分组（§C：urgentDays=3 字面量；Q1 重要×紧急/Q2 重要/Q3 紧急/Q4 愿望池，空组隐藏）。 */
internal fun quadrants(waiting: List<Plan>): List<Pair<String, List<Plan>>> {
    val now = java.time.LocalDateTime.now()
    val urgentLimit = now.plusDays(3 + 1)
    fun urgent(p: Plan): Boolean =
        p.deadline != null && app.shiguang.core.model.ScheduleDay.tryParseIsoDate(p.deadline!!)
            ?.atStartOfDay()?.isBefore(urgentLimit) == true
    return listOf(
        "重要 × 紧急" to waiting.filter { it.importance && urgent(it) },
        "重要" to waiting.filter { it.importance && !urgent(it) },
        "紧急" to waiting.filter { !it.importance && urgent(it) },
        "愿望池" to waiting.filter { !it.importance && !urgent(it) },
    ).filter { it.second.isNotEmpty() }
}

/** 冷藏池（§C：archived 折叠区，默认收起；逐条「恢复」=archived:false CAS）。 */
@Composable
private fun ColdPoolSection(plans: List<Plan>, onRestore: (Plan) -> Unit) {
    val palette = LocalStColors.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = StScale.insetMd.dp),
        shape = RoundedCornerShape(StScale.radiusCard.dp),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = StScale.insetMd.dp, vertical = StUi.plansRowVDp.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(StUi.iconSmDp.dp),
                    tint = Color(palette.textSecondary),
                )
                Spacer(Modifier.width(StUi.plansHeadGapDp.dp))
                Text(
                    "收起的旧想法 · ${plans.size} 条",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(palette.textSecondary),
                )
            }
            if (expanded) {
                for (p in plans) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = StScale.insetMd.dp, vertical = StScale.insetXs.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(p.title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { onRestore(p) }) { Text("恢复") }
                    }
                }
            }
        }
    }
}

/** 计划卡（§D：左滑归档撤销/点按详情/长按 sheet/象限色点/胶囊/微标）。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlanCard(
    plan: Plan,
    proposed: Int,
    spark: Boolean,
    onOpenPlanDetail: (String) -> Unit,
    onArchiveUndo: () -> Unit,
    onActionSheet: () -> Unit,
) {
    val palette = LocalStColors.current
    val scheme = MaterialTheme.colorScheme
    // 象限色点（§D 四态）
    val dotColor = when {
        plan.importance && plan.deadline != null -> scheme.primary
        plan.importance -> scheme.primary.copy(alpha = 0.5f)
        plan.deadline != null -> Color(palette.sparkStroke) // 琥珀
        else -> Color(palette.safelineOff)
    }
    SwipeToDismissBox(
        state = rememberSwipeToDismissBoxState(
            confirmValueChange = { value ->
                if (value == SwipeToDismissBoxValue.EndToStart) {
                    onArchiveUndo()
                    true
                } else {
                    false
                }
            },
        ),
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            // 左滑背景（中性灰「归档」，绝不显红——减震器语义）
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(bottom = StScale.insetSm.dp)
                    .background(Color(palette.fixedSlotBg), RoundedCornerShape(StScale.radiusCard.dp))
                    .padding(end = StScale.insetXl.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Archive, contentDescription = null, tint = Color(palette.textSecondary))
                    Spacer(Modifier.width(StScale.insetXs.dp))
                    Text("归档", color = Color(palette.textSecondary))
                }
            }
        },
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = StScale.insetSm.dp)
                .combinedClickable(
                    onClick = { plan.id?.let(onOpenPlanDetail) },
                    onLongClick = onActionSheet,
                ),
            shape = RoundedCornerShape(StScale.radiusCard.dp),
        ) {
            Row(
                Modifier.padding(StScale.insetMd.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(StUi.plansDotSizeDp.dp)
                        .background(dotColor, CircleShape),
                )
                Spacer(Modifier.width(StUi.plansDotGapDp.dp))
                Text(
                    plan.title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (spark) Text("🔥", fontSize = StUi.compactTextSp.sp)
                if (proposed > 0) {
                    Box(
                        Modifier
                            .padding(start = StScale.insetSm.dp)
                            .background(scheme.primaryContainer, RoundedCornerShape(StScale.radiusCapsule.dp))
                            .padding(horizontal = StScale.insetSm.dp, vertical = StUi.compactPadDp.dp),
                    ) {
                        Text(
                            "待确认 $proposed 项",
                            fontSize = StUi.plansMetaTextSp.sp,
                            color = scheme.onPrimaryContainer,
                        )
                    }
                }
                if (plan.deadline != null) {
                    Text(
                        plan.deadline!!,
                        modifier = Modifier.padding(start = StScale.insetSm.dp),
                        fontSize = StUi.plansMetaTextSp.sp,
                        color = Color(palette.textSecondary),
                    )
                }
            }
        }
    }
}

/** Row 单击扩展（sheet 列表行——无长按语义）。 */
private fun Modifier.clickableRow(onClick: () -> Unit): Modifier =
    this.clickable(onClick = onClick)
