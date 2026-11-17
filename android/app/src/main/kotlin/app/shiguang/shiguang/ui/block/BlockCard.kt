package app.shiguang.shiguang.ui.block

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.shiguang.core.model.Plan
import app.shiguang.core.model.ScheduleBlock
import app.shiguang.core.tokens.StColorsPalette
import app.shiguang.core.tokens.StScale
import app.shiguang.core.tokens.StUi
import app.shiguang.shiguang.ui.theme.LocalStColors

/**
 * BlockRenderer 块卡片（P4②，behavior/block_renderer.md 已过目转正 2026-11-15）：
 * 四形态判定序 + 七态叠加 + 角标错位 + D12 补实现（F1 虚线/F2 ⊘/F3 missed 层/F4 96dp/F8 橙线，
 * 绘制细节在 BlockCardVisual.blockDecorations）。输入全显式参数（State Hoisting，禁直调
 * 数据仓储面——Konsist）；手势竞技场见 GestureBlock，命令经回调上抛。
 */
@Composable
fun BlockCard(
    block: ScheduleBlock,
    plan: Plan?,
    energyLow: Boolean,
    roamDay: Boolean,
    voucherBadge: String?,
    isColliding: Boolean,
    modifier: Modifier = Modifier,
    onTap: () -> Unit = {},
) {
    val palette = LocalStColors.current
    val form = BlockVisualSpec.resolveForm(block, plan, energyLow, roamDay)
    val roam = BlockVisualSpec.isRoam(roamDay, block)
    val archived = block.status == BlockStatus.ARCHIVED
    val skipped = block.status == BlockStatus.SKIPPED
    val missed = block.status == BlockStatus.MISSED
    val colors = resolveBlockColors(form, skipped, missed)
    val proposed = block.status == BlockStatus.PROPOSED
    val badge = BlockVisualSpec.badgeLayout(block)
    val shape = RoundedCornerShape(StScale.radiusBlock.dp)
    val label = BlockVisualSpec.labelText(block, plan)
    val prefix = BlockVisualSpec.timePrefix(block, roam)

    Box(
        modifier
            .heightIn(min = StScale.blockMinHeightDp.dp)
            .clip(shape)
            .background(colors.bg, shape)
            .blockDecorations(palette, colors.stroke, proposed, isColliding, missed)
            .alpha(if (archived) StColorsPalette.archivedFadeAlpha else 1f)
            .clickable(enabled = !archived && block.status != BlockStatus.MELTED) { onTap() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            prefix + label,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.text,
            textDecoration = if (skipped) TextDecoration.LineThrough else null,
        )
        if (form == BlockForm.DEEP) {
            Text(
                "[+15m 呼吸留白]",
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = StScale.insetXs.dp, bottom = StScale.insetXs.dp),
                fontSize = StUi.footnoteSp.sp,
                color = colors.text.copy(alpha = 0.8f),
            )
        }
        if (missed) {
            Text(
                "未打卡",
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = StScale.insetXs.dp, bottom = StScale.insetXs.dp),
                fontSize = StUi.footnoteSp.sp,
                color = Color(palette.textSecondary),
            )
        }
        // ✓/⊘ 右下徽章（done 原色不灰化；skipped ⊘ 槽位换符——D12 F2）
        if (block.status == BlockStatus.DONE || skipped) {
            Text(
                if (block.status == BlockStatus.DONE) "✓" else "⊘",
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(StScale.insetXs.dp),
                color = if (block.status == BlockStatus.DONE) {
                    MaterialTheme.colorScheme.primary
                } else {
                    Color(palette.textSecondary)
                },
                fontSize = StUi.doneBadgeSizeDp.sp,
            )
        }
        BlockBadges(
            block = block,
            badge = badge,
            textColor = colors.text,
            voucherBadge = voucherBadge,
            modifier = Modifier.align(Alignment.TopStart),
        )
    }
}
