package app.shiguang.shiguang.ui.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ConfirmationNumber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.shiguang.core.action.ActionPass
import app.shiguang.core.action.FactRules
import app.shiguang.core.action.advanceArrivalMinutes
import app.shiguang.core.model.ScheduleDay
import app.shiguang.core.tokens.StScale
import app.shiguang.core.tokens.StUi
import app.shiguang.shiguang.ui.theme.LocalStColors

/**
 * 临场通关条（behavior/action_pass_bar.md 转正清单 §C，D25 过目）：插在系统
 * Banner 下、遗留区上，不挤掉双轨仪表盘。多条并存按行动时刻最早者展开，其余
 * 折叠「+N」胶囊。窗口判定在 VM 装配段（computeActionPasses 本地物理时钟）。
 */
@Composable
fun ActionPassBar(
    passes: List<ActionPass>,
    onTap: (ActionPass) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalStColors.current
    val scheme = MaterialTheme.colorScheme
    if (passes.isEmpty()) return
    val primary = passes.first()
    val extra = passes.size - 1
    val advance = advanceArrivalMinutes(primary.fact)
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = StScale.insetMd.dp, vertical = StUi.passBarVDp.dp)
            .background(Color(palette.voucherBg), RoundedCornerShape(StScale.radiusCard.dp))
            .clickable { onTap(primary) }
            .padding(horizontal = StUi.passBarHeadHDp.dp, vertical = StUi.planGroupHeadVDp.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.ConfirmationNumber, contentDescription = null, modifier = Modifier.size(StUi.iconSmDp.dp))
            Spacer(Modifier.width(StScale.insetSm.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "${ScheduleDay.clockOf(primary.actionTime.hour * 60 + primary.actionTime.minute)} ${FactRules.roleLabel(primary.role)} · ${primary.title}",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (advance != null && advance > 0) {
                    val target = primary.actionTime.minusMinutes(advance.toLong())
                    Text(
                        "建议 ${ScheduleDay.clockOf(target.hour * 60 + target.minute)} 前到站",
                        fontSize = StUi.plansMetaTextSp.sp,
                        color = Color(palette.textSecondary),
                    )
                }
            }
            if (extra > 0) {
                Spacer(Modifier.width(StScale.insetSm.dp))
                Text(
                    "+$extra",
                    modifier = Modifier
                        .background(scheme.primaryContainer, RoundedCornerShape(StScale.radiusCapsule.dp))
                        .padding(horizontal = StScale.insetSm.dp, vertical = StUi.compactPadDp.dp),
                    fontSize = StUi.plansMetaTextSp.sp,
                    color = scheme.onPrimaryContainer,
                )
            }
        }
    }
}
