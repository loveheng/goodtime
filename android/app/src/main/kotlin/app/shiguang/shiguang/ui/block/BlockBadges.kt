package app.shiguang.shiguang.ui.block

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.shiguang.core.model.ScheduleBlock
import app.shiguang.core.tokens.StScale
import app.shiguang.core.tokens.StUi

/**
 * 块角标层（§5.3 来源角标 + 行为清单错位链）：提案 chip 右上 → 🎂/📌 让位右移 →
 * 📋 来自清单左上 → 🎫 凭证微标左上栈（badge 96dp ellipsis——D12 F4）。
 */
@Composable
internal fun BlockBadges(
    block: ScheduleBlock,
    badge: BlockBadgeLayout,
    textColor: Color,
    voucherBadge: String?,
    modifier: Modifier = Modifier,
) {
    if (badge.proposalChip) {
        Text(
            "提案",
            modifier = modifier
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(StScale.radiusCapsule.dp))
                .padding(horizontal = StScale.insetXs.dp, vertical = StUi.chipPadVDp.dp),
            fontSize = StUi.chipTextSp.sp,
            color = Color.White,
        )
    }
    if (block.isCelebration) {
        Text(
            "🎂",
            modifier = modifier.offset(x = (-badge.celebrationOffsetDp).dp),
            fontSize = StUi.badgeGlyphSp.sp,
        )
    }
    if (block.pinned) {
        Text(
            "📌",
            modifier = modifier.offset(x = (-badge.pinOffsetDp).dp),
            fontSize = StUi.badgeGlyphSp.sp,
        )
    }
    if (badge.planBadge) {
        Text(
            "📋",
            modifier = modifier,
            fontSize = StUi.badgeGlyphSp.sp,
            color = textColor,
        )
    }
    // 🎫 凭证微标：blockId 有非 voided 凭证即挂（调用方经参数传入）；文本=badge（有则显），无 badge 只显票形
    if (voucherBadge != null) {
        Row(
            modifier = modifier.offset(y = badge.voucherTopDp.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("🎫", fontSize = StUi.badgeGlyphSp.sp)
            if (voucherBadge.isNotEmpty()) {
                Text(
                    voucherBadge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = StUi.voucherBadgeMaxWidthDp.dp),
                    fontSize = StUi.badgeGlyphSp.sp,
                    color = textColor,
                )
            }
        }
    }
}
