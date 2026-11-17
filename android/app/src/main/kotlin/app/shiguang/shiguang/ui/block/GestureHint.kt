package app.shiguang.shiguang.ui.block

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.shiguang.core.tokens.StScale
import app.shiguang.core.tokens.StUi
import app.shiguang.shiguang.ui.theme.LocalStColors

/**
 * 手势意图提示层（§5.4/§6.5，behavior/block_renderer.md 已过目值）：过卡点才出现，
 * 中性陈述去向绝不显红；右滑=暂缓并收回清单（左对齐）/ 左滑=换件轻松的？（右对齐）。
 * matchParentSize=丢失点 #2 的 Positioned.fill 显式化（禁隐式尺寸语义）。
 */
@Composable
internal fun MeltHintOverlay(modifier: Modifier, shape: RoundedCornerShape) {
    val palette = LocalStColors.current
    Box(
        modifier
            
            .background(Color(palette.freeFlowBg), shape)
            .padding(start = StScale.insetMd.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text("暂缓并收回清单", fontSize = StUi.hintOverlaySp.sp, color = Color(palette.textPrimary))
    }
}

@Composable
internal fun SwapHintOverlay(modifier: Modifier, shape: RoundedCornerShape) {
    val palette = LocalStColors.current
    Box(
        modifier
            
            .background(Color(palette.supplyBandBg), shape)
            .padding(end = StScale.insetMd.dp),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Text("换件轻松的？", fontSize = StUi.hintOverlaySp.sp, color = Color(palette.textPrimary))
    }
}
