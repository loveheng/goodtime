package app.shiguang.shiguang.ui.plan

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.ConfirmationNumber
import androidx.compose.material.icons.outlined.Hotel
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Museum
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Train
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.shiguang.core.model.Artifact
import app.shiguang.core.model.ArtifactRules
import app.shiguang.shiguang.ui.factsheet.FactCard
import app.shiguang.core.tokens.StScale
import app.shiguang.core.tokens.StUi
import app.shiguang.shiguang.ui.component.ShiguangScaffold
import app.shiguang.shiguang.ui.component.ShiguangSnackbarDuration
import app.shiguang.shiguang.ui.component.ShiguangSnackbarHost
import app.shiguang.shiguang.ui.component.showShiguangSnackbar
import app.shiguang.shiguang.ui.theme.LocalStColors

/**
 * 计划「随行凭证」子页（ui-pages §9.5；behavior/plan_facts.md 已过目转正 D24）：
 * 子树派生聚合（根计划=行程凭证总览页）——raw 琥珀聚合卡「N 条原文待提炼」+
 * FactCard 列表 +「过去的凭证」「已作废」两折叠组；本区无 human 添加钮；voided
 * 不进主卡面沉底折叠（§1.2 双通道）。删除仅 human 长按（§0.4）。
 * 通关 sheet（showVoucherSheet）点按回调已接（D25 浮层族轮消标）。
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun PlanFactsPage(
    vm: PlanFactsViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ui by vm.uiState.collectAsState()
    val palette = LocalStColors.current
    val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }
    var errorToast by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<Artifact?>(null) }
    // 通关卡宿主（fact_sheet.md §C——D25 轮接线：点按直开/多条开列表 sheet；UNCERTAIN 消标）
    var voucherFact by remember { mutableStateOf<Artifact?>(null) }
    var rawList by remember { mutableStateOf<List<Artifact>?>(null) }
    // 丢失点 #8：raw 列表→通关卡/确认弹框逐层返回
    androidx.activity.compose.BackHandler(enabled = rawList != null) { rawList = null }
    androidx.activity.compose.BackHandler(enabled = confirmDelete != null) { confirmDelete = null }
    androidx.activity.compose.BackHandler(enabled = voucherFact != null) { voucherFact = null }

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
                title = { Text("随行凭证") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        val view = ui.view
        val allEmpty = view == null || view.isEmpty
        if (allEmpty) {
            // 空态（§B：居中）
            Box(
                modifier.padding(padding).fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "还没有凭证——车票/门票/预订信息分享给拾光就会出现在这",
                    modifier = Modifier.padding(horizontal = StScale.insetLg.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(palette.textSecondary),
                )
            }
        } else {
            Column(
                modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(StScale.insetLg.dp),
            ) {
                // raw 聚合琥珀卡（§B：1 条直开/多条开列表 sheet——列表 sheet 随浮层族轮）
                val raw = view.raw
                if (raw.isNotEmpty()) {
                    RawFactsCard(
                        raw = raw,
                        onTap = {
                            if (raw.size == 1) voucherFact = raw.single() else rawList = raw
                        },
                    )
                }
                for (f in view.structured) {
                    FactCard(
                        fact = f,
                        onTap = { voucherFact = f },
                        onLongPress = { confirmDelete = f },
                    )
                }
                if (view.expired.isNotEmpty()) {
                    Spacer(Modifier.height(StScale.insetXs.dp))
                    PastGroup(
                        title = "过去的凭证",
                        count = view.expired.size,
                        rows = {
                            for (f in view.expired) {
                                FactCard(
                                    fact = f,
                                    onTap = { voucherFact = f },
                                    onLongPress = { confirmDelete = f },
                                )
                            }
                        },
                    )
                }
                if (view.voided.isNotEmpty()) {
                    Spacer(Modifier.height(StScale.insetXs.dp))
                    PastGroup(
                        title = "已作废",
                        count = view.voided.size,
                        rows = {
                            for (f in view.voided) {
                                FactCard(
                                    fact = f,
                                    onTap = { voucherFact = f },
                                    onLongPress = { confirmDelete = f },
                                )
                            }
                        },
                    )
                }
            }
        }
    }

    // 删除二次确认（§D：仅 human；删凭证绝不删块）
    confirmDelete?.let { fact ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("删除「${fact.title}」？") },
            text = { Text("删除后无法恢复。") },
            confirmButton = {
                Button(onClick = {
                    confirmDelete = null
                    vm.deleteArtifact(fact)
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("取消") } },
        )
    }
    // 通关卡宿主（fact_sheet.md §C 同源装配；复制事件经本页 VM 2s）
    voucherFact?.let { f ->
        app.shiguang.shiguang.ui.factsheet.FactVoucherSheet(
            fact = f,
            onDismiss = { voucherFact = null },
            onCopy = { code -> vm.notifyFactCopied(code) },
        )
    }
    // raw 列表 sheet（聚合卡多条：待提炼的原文——pop 后直开通关卡，金样本 A1）
    rawList?.let { raws ->
        app.shiguang.shiguang.ui.component.ShiguangBottomSheet(onDismissRequest = { rawList = null }) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(start = StScale.insetMd.dp, end = StScale.insetMd.dp, bottom = StScale.insetXl.dp),
            ) {
                Text(
                    "待提炼的原文",
                    modifier = Modifier.padding(start = StScale.insetMd.dp, top = StScale.insetSm.dp, end = StScale.insetMd.dp, bottom = StScale.insetXs.dp),
                    style = MaterialTheme.typography.titleSmall,
                )
                for (f in raws) {
                    app.shiguang.shiguang.ui.factsheet.FactCard(
                        fact = f,
                        onTap = {
                            rawList = null
                            voucherFact = f
                        },
                    )
                }
            }
        }
    }
}

/** raw 聚合琥珀卡（金样本 A1：多条开列表 sheet 随浮层族轮——本页先聚合入口）。 */
@Composable
private fun RawFactsCard(raw: List<Artifact>, onTap: () -> Unit) {
    val palette = LocalStColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color(palette.voucherBg), RoundedCornerShape(StScale.radiusCard.dp))
            .clickable(onClick = onTap)
            .padding(horizontal = StScale.insetMd.dp, vertical = StUi.planGroupHeadVDp.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.ReceiptLong, contentDescription = null, modifier = Modifier.size(StUi.iconSmDp.dp))
        Spacer(Modifier.size(StScale.insetSm.dp))
        Text("${raw.size} 条原文待提炼", style = MaterialTheme.typography.bodyMedium)
    }
}

