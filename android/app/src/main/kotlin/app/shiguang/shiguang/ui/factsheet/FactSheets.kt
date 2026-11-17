package app.shiguang.shiguang.ui.factsheet

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
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import app.shiguang.core.action.ActionException
import app.shiguang.core.action.BridgeRepositoryPort
import app.shiguang.core.model.Artifact
import app.shiguang.core.model.Plan
import app.shiguang.core.tokens.StScale
import app.shiguang.core.tokens.StUi
import app.shiguang.shiguang.ShiguangApplication
import app.shiguang.shiguang.ui.component.ShiguangBottomSheet
import app.shiguang.shiguang.ui.theme.LocalStColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 通关卡 sheet 族（fact_sheet.md 转正清单 §C–§E，D25 过目）：通关 sheet 宿主
 * （FactVoucherSheet）+ 挂载 sheet（分享入册归属归一，两次点击内完成）+ 未归属
 * 管理面板（计划列表页入口行唤起——N7 空壳回接）+ 未归属常驻折叠组（D2 宿主=
 * 挂载 sheet）+ 改挂 sheet。命令面全经 FactSheetsViewModel（Konsist K3）。
 */

/** 通关 sheet 宿主（showVoucherSheet 对位）：页侧状态承载，遮罩/返回关闭。 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun FactVoucherSheet(
    fact: Artifact,
    onDismiss: () -> Unit,
    block: app.shiguang.core.model.ScheduleBlock? = null,
    onNavigateToBlock: (() -> Unit)? = null,
    onCopy: (String) -> Unit = {},
    vm: FactSheetsViewModel? = null,
) {
    ShiguangBottomSheet(onDismissRequest = onDismiss) {
        FactDetailSheet(
            fact = fact,
            block = block,
            onNavigateToBlock = onNavigateToBlock,
            onCopy = onCopy,
        )
    }
}

/** 未归属常驻折叠组（挂载 sheet 宿主 + 管理面板共用形态；N=0 隐藏）。 */
@Composable
fun UnattributedGroup(
    unattributed: List<Artifact>,
    onOpen: (Artifact) -> Unit,
    onRemount: (Artifact) -> Unit,
) {
    val palette = LocalStColors.current
    if (unattributed.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(StScale.radiusCard.dp)) {
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
                    "未归属 · ${unattributed.size} 条",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(palette.textSecondary),
                )
            }
            if (expanded) {
                for (f in unattributed) {
                    ListItem(
                        modifier = Modifier.clickable { onOpen(f) },
                        headlineContent = {
                            Text(f.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        supportingContent = {
                            Text(
                                if (f.state == Artifact.STATE_RAW) "原文待提炼" else factCategoryLabel(f.category),
                                fontSize = StUi.plansMetaTextSp.sp,
                                color = Color(palette.textSecondary),
                            )
                        },
                    )
                }
            }
        }
    }
}

/** 挂载 sheet（showFactMountSheet 对位）：常驻组 + 预览 + 计划列表 + 留池退场。 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun FactMountSheet(
    factId: String,
    vm: FactSheetsViewModel,
    onDismiss: () -> Unit,
    onOpenVoucher: (Artifact) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var data by remember { mutableStateOf<FactSheetsViewModel.FactMountData?>(null) }
    var missing by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var remountTarget by remember { mutableStateOf<Artifact?>(null) }

    LaunchedEffect(factId) {
        val d = vm.mountData(factId)
        if (d == null) missing = true else data = d
    }

    ShiguangBottomSheet(onDismissRequest = onDismiss) {
        Box(Modifier.fillMaxWidth()) {
            when {
                missing -> Text(
                    "这条凭证不存在了",
                    modifier = Modifier.fillMaxWidth().padding(StScale.insetXl.dp),
                )
                data == null -> Box(
                    Modifier.fillMaxWidth().height(StUi.factSheetLoadingDp.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
                else -> data?.let { d ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(start = StScale.insetXl.dp, end = StScale.insetXl.dp, bottom = StScale.insetXl.dp),
                ) {
                    UnattributedGroup(
                        unattributed = d.unattributed,
                        onOpen = onOpenVoucher,
                        onRemount = { remountTarget = it },
                    )
                    Spacer(Modifier.height(StScale.insetSm.dp))
                    SharePreview(d.fact)
                    Spacer(Modifier.height(StScale.insetSm.dp))
                    Text("归到哪条计划？", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(StScale.insetXs.dp))
                    for (p in d.plans) {
                        ListItem(
                            modifier = Modifier.clickable(enabled = !busy) {
                                scope.launch {
                                    busy = true
                                    val ok = vm.mountTo(d.fact.id!!, p.id!!)
                                    busy = false
                                    if (ok) onDismiss()
                                }
                            },
                            leadingContent = {
                                Icon(Icons.Outlined.FolderOpen, contentDescription = null, modifier = Modifier.size(StUi.iconSmDp.dp))
                            },
                            headlineContent = { Text(p.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        )
                    }
                    TextButton(
                        onClick = onDismiss,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("先记下，稍后归属") }
                }
                }
            }
        }
    }
    remountTarget?.let { f ->
        FactRemountSheet(fact = f, vm = vm, onDismiss = { remountTarget = null })
    }
}

/** 分享预览（raw 一字不解析逐字展示）。 */
@Composable
private fun SharePreview(fact: Artifact) {
    val palette = LocalStColors.current
    val raw = (fact.payload["raw_text"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull ?: ""
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color(palette.voucherBg), RoundedCornerShape(StScale.radiusBlock.dp))
            .padding(StScale.insetMd.dp),
    ) {
        Text(
            fact.title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.W600),
        )
        if (raw.isNotEmpty()) {
            Text(
                raw,
                modifier = Modifier.padding(top = StUi.compactGapDp.dp),
                style = MaterialTheme.typography.bodySmall,
                color = Color(palette.textSecondary),
            )
        }
    }
}

/** 改挂 sheet（常驻组/面板长按同源）：计划列表选其一。 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun FactRemountSheet(
    fact: Artifact,
    vm: FactSheetsViewModel,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var plans by remember { mutableStateOf<List<Plan>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(fact.id) {
        plans = vm.plans()
        loaded = true
    }
    ShiguangBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = StScale.insetXl.dp, end = StScale.insetXl.dp, bottom = StScale.insetXl.dp),
        ) {
            Text("把「${fact.title}」挂到哪条计划？", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(StScale.insetSm.dp))
            if (loaded && plans.isEmpty()) {
                Text(
                    "还没有计划——先建一条计划",
                    modifier = Modifier.fillMaxWidth().padding(StScale.insetMd.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            for (p in plans) {
                ListItem(
                    modifier = Modifier.clickable {
                        scope.launch {
                            val ok = vm.remountTo(fact.id!!, p.id!!)
                            if (ok) onDismiss()
                        }
                    },
                    headlineContent = { Text(p.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                )
            }
        }
    }
}

/** 未归属管理面板（showUnattributedFactsPanel 对位；计划列表页入口行唤起）。 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun UnattributedFactsPanel(
    vm: FactSheetsViewModel,
    onDismiss: () -> Unit,
    onOpenVoucher: (Artifact) -> Unit = {},
) {
    val palette = LocalStColors.current
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<Artifact>?>(null) }
    var confirmDelete by remember { mutableStateOf<Artifact?>(null) }
    var remountTarget by remember { mutableStateOf<Artifact?>(null) }

    LaunchedEffect(Unit) {
        vm.revision.collect {
            list = vm.unattributed()
        }
    }

    ShiguangBottomSheet(onDismissRequest = onDismiss) {
        when {
            list == null -> Box(
                Modifier.fillMaxWidth().height(StUi.factSheetLoadingDp.dp),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            list!!.isEmpty() -> Text(
                "未归属凭证都处理完了",
                modifier = Modifier.fillMaxWidth().padding(StScale.insetXl.dp),
                color = Color(palette.textSecondary),
            )
            else -> Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(start = StScale.insetMd.dp, end = StScale.insetMd.dp, bottom = StScale.insetXl.dp),
            ) {
                Text(
                    "未归属凭证",
                    modifier = Modifier.padding(start = StScale.insetMd.dp, top = StScale.insetSm.dp, end = StScale.insetMd.dp, bottom = StScale.insetXs.dp),
                    style = MaterialTheme.typography.titleSmall,
                )
                for (f in list!!) {
                    SwipeToDismissBox(
                        state = rememberSwipeToDismissBoxState(
                            confirmValueChange = { value ->
                                if (value == SwipeToDismissBoxValue.EndToStart) {
                                    confirmDelete = f
                                    false // 确认后才删——弹框承接（Dart confirmDismiss 同构）
                                } else {
                                    false
                                }
                            },
                        ),
                        enableDismissFromStartToEnd = false,
                        backgroundContent = {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .background(Color(palette.fixedSlotBg), RoundedCornerShape(StScale.radiusCard.dp))
                                    .padding(end = StScale.insetXl.dp),
                                contentAlignment = Alignment.CenterEnd,
                            ) {
                                Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(StUi.iconSmDp.dp))
                            }
                        },
                    ) {
                        FactCard(
                            fact = f,
                            modifier = Modifier.padding(vertical = StScale.insetXs.dp),
                            onTap = { onOpenVoucher(f) },
                            onLongPress = { remountTarget = f },
                        )
                    }
                }
            }
        }
    }
    confirmDelete?.let { f ->
        FactDeleteConfirmDialog(
            fact = f,
            onConfirm = {
                confirmDelete = null
                vm.deleteFact(f)
            },
            onDismiss = { confirmDelete = null },
        )
    }
    remountTarget?.let { f ->
        FactRemountSheet(fact = f, vm = vm, onDismiss = { remountTarget = null })
    }
}

/** 删除二次确认（§0.4：删除=DeleteArtifact 仅 human；删凭证绝不删块）。 */
@Composable
fun FactDeleteConfirmDialog(
    fact: Artifact,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除「${fact.title}」？") },
        text = { Text("删除后无法恢复。") },
        confirmButton = { Button(onClick = onConfirm) { Text("删除") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
