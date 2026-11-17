package app.shiguang.shiguang.ui.factsheet

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.ConfirmationNumber
import androidx.compose.material.icons.outlined.Hotel
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Museum
import androidx.compose.material.icons.outlined.Train
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
import app.shiguang.core.model.Artifact
import app.shiguang.core.model.ArtifactRules
import app.shiguang.core.tokens.StScale
import app.shiguang.core.tokens.StUi
import app.shiguang.shiguang.ui.theme.LocalStColors

/**
 * 事实凭证派生 helper 与紧凑凭证卡（fact_sheet.md 转正清单组件归位件，D25）：
 * 白话类别=ui-pages §9.5（黑话不上屏）；派生口径一律不入库。三消费方统一
 * （plan_facts 凭证子页/未归属面板/挂载 sheet 常驻组）。
 */

/** category 五类 → 白话（§0.4）：交通/门票/住宿/须知/口头信息。 */
fun factCategoryLabel(category: String): String = when (category) {
    Artifact.CATEGORY_TRANSIT -> "交通"
    Artifact.CATEGORY_TICKET -> "门票"
    Artifact.CATEGORY_HOTEL -> "住宿"
    Artifact.CATEGORY_VENUE -> "须知"
    Artifact.CATEGORY_VERBAL -> "口头信息"
    else -> "信息"
}

/** category → 图标（卡面/通关卡 Header 同源，不为任何门类硬编码）。 */
fun factCategoryIcon(category: String) = when (category) {
    Artifact.CATEGORY_TRANSIT -> Icons.Outlined.Train
    Artifact.CATEGORY_TICKET -> Icons.Outlined.ConfirmationNumber
    Artifact.CATEGORY_HOTEL -> Icons.Outlined.Hotel
    Artifact.CATEGORY_VENUE -> Icons.Outlined.Museum
    Artifact.CATEGORY_VERBAL -> Icons.Outlined.ChatBubbleOutline
    else -> Icons.Outlined.Info
}

/**
 * 紧凑凭证卡（计划详情「随行凭证」区/未归属面板/常驻组共用）：类别图标+title+
 * badge+日期。raw=琥珀卡（原文待提炼，未解析无日期）；verbal=弱化+「口头信息 ·
 * 待核实」角标；voided=弱化+「已作废」标（仅出现在凭证子页「已作废」折叠组内；
 * 排程读侧仍全静默，§1.2 双通道口径）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FactCard(
    fact: Artifact,
    onTap: () -> Unit,
    onLongPress: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val palette = LocalStColors.current
    val isRaw = fact.state == Artifact.STATE_RAW
    val isVerbal = fact.category == Artifact.CATEGORY_VERBAL
    val isVoided = fact.state == Artifact.STATE_VOIDED
    val badge = ArtifactRules.factDateBadge(fact)
    Row(
        modifier
            .fillMaxWidth()
            .background(
                if (isRaw) Color(palette.voucherBg) else Color.Transparent,
                RoundedCornerShape(StScale.radiusCard.dp),
            )
            .combinedClickable(onClick = onTap, onLongClick = onLongPress)
            .padding(horizontal = StScale.insetMd.dp, vertical = StUi.planGroupHeadVDp.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            factCategoryIcon(fact.category),
            contentDescription = null,
            modifier = Modifier.size(StUi.iconSmDp.dp),
            tint = if (isVerbal) Color(palette.sparkStroke) else Color(palette.textSecondary),
        )
        androidx.compose.foundation.layout.Spacer(Modifier.size(StScale.insetSm.dp))
        Column(Modifier.weight(1f)) {
            Text(
                fact.title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = if (isVerbal) {
                    MaterialTheme.typography.bodySmall.copy(color = Color(palette.textSecondary))
                } else {
                    MaterialTheme.typography.bodyMedium
                },
            )
            if (isRaw) {
                Text("原文待提炼", fontSize = StUi.planFactStateTextSp.sp, color = Color(palette.sparkStroke))
            } else if (isVoided) {
                Text("已作废", fontSize = StUi.planFactStateTextSp.sp, color = Color(palette.textSecondary))
            } else if (isVerbal) {
                Text("口头信息 · 待核实", fontSize = StUi.planFactStateTextSp.sp, color = Color(palette.sparkStroke))
            }
        }
        if (!fact.badge.isNullOrEmpty()) {
            Text(
                fact.badge!!,
                modifier = Modifier
                    .padding(start = StUi.compactGapDp.dp)
                    .widthIn(max = StUi.voucherBadgeMaxWidthDp.dp),
                fontSize = StUi.planFactStateTextSp.sp,
                color = Color(palette.textSecondary),
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (badge != null) {
            Text(
                badge,
                modifier = Modifier.padding(start = StUi.compactGapDp.dp),
                fontSize = StUi.plansMetaTextSp.sp,
                color = Color(palette.textSecondary),
            )
        }
    }
}
