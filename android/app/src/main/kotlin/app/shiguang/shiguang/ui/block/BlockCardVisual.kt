package app.shiguang.shiguang.ui.block

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import app.shiguang.core.tokens.StColorsPalette
import app.shiguang.core.tokens.StScale
import app.shiguang.core.tokens.StUi
import app.shiguang.shiguang.ui.theme.LocalStColors

/** 形态取色产物（bg 已按 skipped/missed 降饱和——D12 F2/F3）。 */
internal data class BlockColors(
    val bg: Color,
    val stroke: Color?,
    val text: Color,
)

/** 形态取色（判定序；DEFAULT 取 M3 primaryContainer 语义对）。 */
@Composable
internal fun resolveBlockColors(
    form: BlockForm,
    skipped: Boolean,
    missed: Boolean,
): BlockColors {
    val palette = LocalStColors.current
    val scheme = MaterialTheme.colorScheme
    val base = when (form) {
        BlockForm.CELEBRATION -> BlockColors(Color(palette.celebrationBg), Color(palette.celebrationStroke), Color(palette.textPrimary))
        BlockForm.SPARK -> BlockColors(Color(palette.sparkBg), Color(palette.sparkStroke), Color(palette.textPrimary))
        BlockForm.DEEP -> BlockColors(Color(palette.deepFocusBg), null, Color(palette.deepFocusText))
        BlockForm.ROAM -> BlockColors(Color(palette.roamBg), Color(palette.roamStroke), Color(palette.textPrimary))
        BlockForm.HUMAN -> BlockColors(Color(palette.humanBlockBg), null, Color(palette.textPrimary))
        BlockForm.DEFAULT -> BlockColors(scheme.primaryContainer, null, scheme.onPrimaryContainer)
    }
    // D12 F2/F3：skipped/missed 原形态色降饱和 40%（done 原色不灰化不庆祝）
    val bg = if (skipped || missed) {
        Color(BlockVisualSpec.desaturate(base.bg.toArgb().toLong() and 0xFFFFFFFFL, StUi.missDesaturation))
    } else {
        base.bg
    }
    return BlockColors(bg, base.stroke, base.text)
}

/**
 * 卡片装饰绘制层：D12 F1 proposed 虚线边框（节距 StUi 常规留痕）+ D12 F8 重叠碰撞
 * 亮橙 1dp 内衬线 + D12 F3 missed 左侧米黄 4dp 标（中性不红）。
 */
internal fun Modifier.blockDecorations(
    palette: StColorsPalette,
    strokeColor: Color?,
    proposed: Boolean,
    isColliding: Boolean,
    missed: Boolean,
): Modifier = drawBehind {
    val sw = StUi.proposedBorderWidthDp.dp.toPx()
    val corner = CornerRadius(StScale.radiusBlock.dp.toPx())
    val effect = if (proposed) {
        PathEffect.dashPathEffect(
            floatArrayOf(StUi.dashLenDp.dp.toPx(), StUi.dashGapDp.dp.toPx()),
        )
    } else {
        null
    }
    strokeColor?.let {
        drawRoundRect(it, style = Stroke(sw, pathEffect = effect), cornerRadius = corner)
    }
    if (isColliding) {
        val cw = StUi.collisionHintWidthDp.dp.toPx()
        val inset = sw / 2f + cw / 2f
        drawRoundRect(
            Color(palette.overlapHint),
            topLeft = Offset(inset, inset),
            size = Size(size.width - inset * 2f, size.height - inset * 2f),
            style = Stroke(cw),
            cornerRadius = corner,
        )
    }
    if (missed) {
        drawRect(
            Color(palette.missedAccent),
            topLeft = Offset.Zero,
            size = Size(StScale.insetXs.dp.toPx(), size.height),
        )
    }
}
