package app.shiguang.shiguang.ui.collab

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.toRoute
import app.shiguang.core.model.collab.CollabStep
import app.shiguang.core.model.collab.freeMaskBitwiseAnd
import app.shiguang.core.model.collab.quorumDensity
import app.shiguang.core.tokens.StScale
import app.shiguang.core.tokens.StUi
import app.shiguang.shiguang.ui.component.ShiguangScaffold
import app.shiguang.shiguang.ui.theme.LocalStColors

/**
 * 发起人排期画板页（behavior/collab_canvas.md 转正清单 §B，T4.11——T4 拆解漏项
 * 由走查 ⑩ 崩溃暴露补齐）：双轨切换（团队大盘/个人总览）+全员共振条（quorumDensity
 * 绿 alpha 梯度+交集桶描边）+私事幽灵琥珀斜纹图层（弱占位不断言冲突）+步骤块列表。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollabCanvasPage(
    bundleId: String,
    windowStartMs: Long,
    windowEndMs: Long,
    modifier: Modifier = Modifier,
) {
    val vm: CollabCanvasViewModel = viewModel(
        key = "canvas_$bundleId",
        factory = CollabCanvasViewModel.factory(bundleId),
    )
    val s by vm.uiState.collectAsState()
    // 双轨切换（false=团队大盘，true=个人总览）
    var personal by rememberSaveable { mutableStateOf(false) }
    // Day-2 入口接线（决策矩阵页「画板」钮传 0,0）：按已载步骤时段自适配窗口
    //（无时段步骤回落当前时刻 ±12h）；显式窗口参数照旧。
    val effectiveWindow = remember(s.steps) {
        val times = s.steps.mapNotNull { it.startsAtMs } + s.steps.mapNotNull { it.endsAtMs }
        if (windowStartMs == 0L && windowEndMs == 0L) {
            if (times.isNotEmpty()) {
                (times.min() - 3600_000L) to (times.max() + 3600_000L)
            } else {
                val now = System.currentTimeMillis()
                (now - 12 * 3600_000L) to (now + 12 * 3600_000L)
            }
        } else {
            windowStartMs to windowEndMs
        }
    }
    LaunchedEffect(Unit) { vm.load() }

    ShiguangScaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("排期画板") }) },
    ) { padding ->
        if (s.loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            Column(Modifier.fillMaxSize().padding(padding)) {
                Row(Modifier.padding(start = StScale.insetLg.dp, top = StScale.insetMd.dp, end = StScale.insetLg.dp)) {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = !personal,
                            onClick = { personal = false },
                            shape = SegmentedButtonDefaults.itemShape(0, 2),
                        ) { Text("团队大盘") }
                        SegmentedButton(
                            selected = personal,
                            onClick = { personal = true },
                            shape = SegmentedButtonDefaults.itemShape(1, 2),
                        ) { Text("个人总览") }
                    }
                }
                Spacer(Modifier.height(StUi.collabRowGapDp.dp))
                Text(
                    "全员共振条",
                    modifier = Modifier.padding(horizontal = StScale.insetLg.dp),
                    fontSize = StUi.collabFootnoteSp.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(LocalStColors.current.textSecondary),
                )
                Spacer(Modifier.height(StScale.insetXs.dp))
                QuorumBar(
                    masks = s.masks,
                    modifier = Modifier.padding(horizontal = StScale.insetLg.dp),
                )
                Spacer(Modifier.height(StUi.collabFieldGapDp.dp))
                CanvasBody(
                    steps = s.steps.filter { it.startsAtMs != null && it.endsAtMs != null },
                    privateBusy = s.privateBusy,
                    windowStartMs = effectiveWindow.first,
                    windowEndMs = effectiveWindow.second,
                    showGhost = personal,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/** 全员共振条（§B：逐桶绿 alpha 梯度+交集桶 sparkStroke 描边；空掩码=灰底降级条）。 */
@Composable
private fun QuorumBar(masks: List<String>, modifier: Modifier = Modifier) {
    val palette = LocalStColors.current
    if (masks.isEmpty()) {
        Box(
            modifier
                .fillMaxWidth()
                .height(StUi.collabQuorumBarDp.dp)
                .background(Color(palette.trackGrey), RoundedCornerShape(StUi.collabBadgeRadiusDp.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text("暂无成员空闲数据", fontSize = StUi.collabHintSp.sp, color = Color(palette.textSecondary))
        }
        return
    }
    val density = quorumDensity(masks)
    val max = masks.size.coerceAtLeast(1)
    Row(modifier.fillMaxWidth().height(StUi.collabQuorumBarDp.dp)) {
        // 交集桶=freeMaskBitwiseAnd（掩码长度不齐降级为不描边——§A 容错口径）
        val allFree = runCatching {
            freeMaskBitwiseAnd(masks)
        }.getOrDefault("")
        val shape = RoundedCornerShape(StUi.collabBucketRadiusDp.dp)
        density.forEachIndexed { i, d ->
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .padding(horizontal = StUi.collabBarGapDp.dp)
                    .background(
                        Color(palette.voteAccepted).copy(alpha = StUi.quorumAlphaBase + StUi.quorumAlphaSpan * (d.toFloat() / max)),
                        shape,
                    )
                    .then(
                        if (i < allFree.length && allFree[i] == '1') {
                            Modifier.border(StUi.collabSelectedBorderDp.dp, Color(palette.sparkStroke), shape)
                        } else {
                            Modifier
                        },
                    ),
            )
        }
    }
}

/** 画布（§B：步骤块列表+个人总览轨幽灵琥珀斜纹投影——只投影不拦交互）。 */
@Composable
private fun CanvasBody(
    steps: List<CollabStep>,
    privateBusy: List<Pair<Long, Long>>,
    windowStartMs: Long,
    windowEndMs: Long,
    showGhost: Boolean,
    modifier: Modifier = Modifier,
) {
    val palette = LocalStColors.current
    val span = (windowEndMs - windowStartMs).coerceAtLeast(1)
    Box(modifier) {
        if (showGhost && privateBusy.isNotEmpty()) {
            // 幽灵图层（D27 F2 token 落点：琥珀 0x55E8A33D 填充+斜纹 0x33B26A00 宽 2 步长 8 斜率 12）
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                privateBusy.forEach { (start, end) ->
                    val x0 = (((start - windowStartMs).toFloat() / span).coerceIn(0f, 1f)) * w
                    val x1 = (((end - windowStartMs).toFloat() / span).coerceIn(0f, 1f)) * w
                    if (x1 > x0) {
                        drawRect(color = Color(palette.ghostBusyFill), topLeft = androidx.compose.ui.geometry.Offset(x0, 0f), size = androidx.compose.ui.geometry.Size(x1 - x0, h))
                        var x = x0
                        while (x < x1) {
                            drawLine(
                                color = Color(palette.ghostBusyStripe),
                                start = androidx.compose.ui.geometry.Offset(x, h),
                                end = androidx.compose.ui.geometry.Offset(x + StUi.ghostStripeSlopeDp.dp.toPx(), 0f),
                                strokeWidth = StUi.ghostStripeWidthDp.dp.toPx(),
                            )
                            x += StUi.ghostStripeStepDp.dp.toPx()
                        }
                    }
                }
            }
        }
        if (steps.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无已回填时段的步骤", color = Color(palette.textSecondary))
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = StScale.insetLg.dp),
                verticalArrangement = Arrangement.spacedBy(StScale.insetSm.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(steps.size) { i ->
                    StepBlock(steps[i])
                }
            }
        }
    }
}

/** 步骤块（§B：event 图标 READY=sparkStroke 否则灰+时段 HH:mm–HH:mm+状态 tag 11sp）。 */
@Composable
private fun StepBlock(step: CollabStep) {
    val palette = LocalStColors.current
    val ready = step.status == "READY"
    val accent = if (ready) palette.sparkStroke else palette.textSecondary
    Card {
        ListItem(
            headlineContent = { Text("步骤 ${step.id}", fontSize = StUi.collabSectionSp.sp, fontWeight = FontWeight.SemiBold) },
            leadingContent = {
                Icon(Icons.Outlined.Event, contentDescription = null, modifier = Modifier.size(StUi.collabActionIconDp.dp), tint = Color(accent))
            },
            supportingContent = {
                Text("${hhmm(step.startsAtMs!!)}–${hhmm(step.endsAtMs!!)}", fontSize = StUi.collabHintSp.sp, color = Color(palette.textSecondary))
            },
            trailingContent = {
                Box(
                    Modifier
                        .background(Color(accent).copy(alpha = StUi.collabBadgeAlpha), RoundedCornerShape(StUi.collabBadgeRadiusDp.dp))
                        .padding(horizontal = StScale.insetSm.dp, vertical = StUi.collabChipPadVDp.dp),
                ) {
                    Text(step.status, fontSize = StUi.collabTagSp.sp, fontWeight = FontWeight.SemiBold, color = Color(accent))
                }
            },
        )
    }
}

/** 时刻展示（§A：HH:mm 双位补零）。 */
private fun hhmm(ms: Long): String {
    val t = java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(ms), java.time.ZoneId.systemDefault())
    return "%02d:%02d".format(t.hour, t.minute)
}
