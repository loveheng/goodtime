package app.shiguang.shiguang.ui.factsheet

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.shiguang.core.model.Artifact
import app.shiguang.core.model.ArtifactRules
import app.shiguang.core.model.ScheduleBlock
import app.shiguang.core.model.ScheduleDay
import app.shiguang.core.model.ScheduleDay.tryParseIsoDate
import app.shiguang.core.tokens.StScale
import app.shiguang.core.tokens.StUi
import app.shiguang.shiguang.ui.theme.LocalStColors
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * 通关卡（Slot Assembler，fact_sheet.md 转正清单 §B 六层装配，D25 过目）：单一
 * Composable 按序装配，空层自动消失——verbal 头 → 一致性琥珀条 → Header 药丸 →
 * Hero 通关区（hero_metrics ≤3 组 KV）→ 时空网格 → 约束 Chip 流（🚫 禁止=中性灰，
 * 绝不用红）→ 功能动作行（零权限全 intent）→ 溯源手风琴。块浮层与独立入口同源装配。
 */

@Composable
fun FactDetailSheet(
    fact: Artifact,
    block: ScheduleBlock? = null,
    onNavigateToBlock: (() -> Unit)? = null,
    onCopy: (String) -> Unit = {},
    contentPadding: PaddingValues = PaddingValues(start = StScale.insetXl.dp, end = StScale.insetXl.dp, bottom = StScale.insetXl.dp),
) {
    val palette = LocalStColors.current
    val isVerbal = fact.category == Artifact.CATEGORY_VERBAL
    val inconsistent = app.shiguang.core.action.factInconsistentWithBlock(fact, block)
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding),
    ) {
        if (isVerbal) {
            SheetHeader(fact, "💬 口头信息 · 待核实")
            Spacer(Modifier.height(StScale.insetMd.dp))
        }
        if (inconsistent && onNavigateToBlock != null) {
            AmberBar(text = "与日程时间不一致，点此核对", onTap = onNavigateToBlock)
            Spacer(Modifier.height(StScale.insetMd.dp))
        }
        SheetHeader(fact, fact.title)
        HeroSection(fact)
        AnchorGrid(fact)
        ConstraintChips(fact)
        ActionRow(fact, onCopy)
        RawTextAccordion(fact)
    }
}

/** Header：类别药丸 + title + 日期角标（[titleOverride]=verbal 头文案）。 */
@Composable
private fun SheetHeader(fact: Artifact, titleOverride: String? = null) {
    val palette = LocalStColors.current
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill(icon = factCategoryIcon(fact.category), label = factCategoryLabel(fact.category))
            Spacer(Modifier.width(StScale.insetSm.dp))
            Text(
                titleOverride ?: fact.title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
        val badge = ArtifactRules.factDateBadge(fact)
        if (badge != null) {
            Text(
                badge,
                modifier = Modifier.padding(top = StScale.insetXs.dp),
                fontSize = StUi.plansMetaTextSp.sp,
                color = Color(palette.textSecondary),
            )
        }
    }
}

/** Hero 通关区：voucherBg 琥珀面 + sparkStroke 1dp 边，hero_metrics ≤3 组 KV（空层消失）。 */
@Composable
private fun HeroSection(fact: Artifact) {
    val palette = LocalStColors.current
    val metrics = (fact.payload["hero_metrics"] as? JsonArray)
        ?.filterIsInstance<JsonObject>() ?: emptyList()
    if (metrics.isEmpty()) return
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = StScale.insetMd.dp)
            .background(Color(palette.voucherBg), RoundedCornerShape(StScale.radiusCard.dp))
            .padding(StScale.insetMd.dp),
    ) {
        for (m in metrics) {
            Column(Modifier.weight(1f)) {
                Text(
                    (m["k"] as? JsonPrimitive)?.contentOrNull ?: "",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(palette.textSecondary),
                )
                Spacer(Modifier.height(StUi.compactPadDp.dp))
                Text((m["v"] as? JsonPrimitive)?.contentOrNull ?: "", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

/** MM.dd（解析失败原文回落——Dart _md null 分支）。 */
private fun md(iso: String?): String {
    if (iso == null) return ""
    val d = tryParseIsoDate(iso) ?: return iso
    return "%02d.%02d".format(d.monthValue, d.dayOfMonth)
}

/** 时空网格：锚点 label 化行（span/moment/长期三格式；role 含「停止」前缀 ⚠）+ 📍 location。 */
@Composable
private fun AnchorGrid(fact: Artifact) {
    val palette = LocalStColors.current
    val anchors = (fact.payload["time_anchors"] as? JsonArray)
        ?.filterIsInstance<JsonObject>() ?: emptyList()
    val location = (fact.payload["location"] as? JsonPrimitive)?.contentOrNull
    if (anchors.isEmpty() && location.isNullOrEmpty()) return
    Column(Modifier.padding(top = StScale.insetMd.dp)) {
        for (a in anchors) {
            val kind = (a["kind"] as? JsonPrimitive)?.contentOrNull ?: ""
            val role = (a["role"] as? JsonPrimitive)?.contentOrNull ?: ""
            val deadline = role.contains("停止")
            val dateRaw = (a["date"] as? JsonPrimitive)?.contentOrNull
            val endRaw = (a["end_date"] as? JsonPrimitive)?.contentOrNull
            val sm = (a["start_min"] as? JsonPrimitive)?.longOrNull?.toInt()
            val em = (a["end_min"] as? JsonPrimitive)?.longOrNull?.toInt()
            val min = (a["min"] as? JsonPrimitive)?.longOrNull?.toInt()
            val whenText = when (kind) {
                "span" -> {
                    val d = md(dateRaw)
                    when {
                        endRaw != null && endRaw != dateRaw && sm != null && em != null ->
                            "$d ${ScheduleDay.clockOf(sm)} → ${md(endRaw)} ${ScheduleDay.clockOf(em)}"
                        endRaw != null && endRaw != dateRaw -> "${md(dateRaw)} → ${md(endRaw)}"
                        sm != null && em != null -> "$d ${ScheduleDay.clockOf(sm)}–${ScheduleDay.clockOf(em)}"
                        else -> d
                    }
                }
                "moment" -> if (min == null) md(dateRaw) else "${md(dateRaw)} ${ScheduleDay.clockOf(min)}"
                else -> if (role.isNotEmpty()) role else "长期"
            }
            Row(Modifier.padding(vertical = StUi.factAnchorRowVDp.dp)) {
                if (deadline) {
                    Text("⚠", fontSize = StUi.plansMetaTextSp.sp, modifier = Modifier.padding(end = StScale.insetXs.dp))
                }
                Text(
                    if (role.isNotEmpty()) "$role · $whenText" else whenText,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (!location.isNullOrEmpty()) {
            Text(
                "📍 $location",
                modifier = Modifier.padding(vertical = StUi.factAnchorRowVDp.dp),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** 行动约束：三数组 Chip 流（零任务压力——不打勾不计数；🚫 禁止=中性灰）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConstraintChips(fact: Artifact) {
    val palette = LocalStColors.current
    val c = fact.payload["constraints"] as? JsonObject ?: return
    val strList: (JsonObject, String) -> List<String> = { obj, key ->
        (obj[key] as? JsonArray)?.filterIsInstance<JsonPrimitive>()?.mapNotNull { it.contentOrNull } ?: emptyList()
    }
    val requiredItems = strList(c, "required_items")
    val rules = strList(c, "rules")
    val notices = strList(c, "notices")
    if (requiredItems.isEmpty() && rules.isEmpty() && notices.isEmpty()) return
    FlowRow(
        Modifier.padding(top = StScale.insetMd.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(StUi.compactGapDp.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(StUi.compactGapDp.dp),
    ) {
        for (r in requiredItems) ConstraintChip(Icons.Outlined.CheckBoxOutlineBlank, r, Color(palette.supplyBandBg))
        for (r in rules) ConstraintChip(Icons.Outlined.Block, r, Color(palette.fixedSlotBg))
        for (n in notices) ConstraintChip(Icons.Outlined.Info, n, Color(palette.sparkBg))
    }
}

@Composable
private fun ConstraintChip(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, bg: Color) {
    val palette = LocalStColors.current
    Row(
        Modifier
            .background(bg, RoundedCornerShape(StScale.radiusCapsule.dp))
            .padding(horizontal = StScale.insetSm.dp, vertical = StScale.insetXs.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(StUi.factChipIconDp.dp), tint = Color(palette.textSecondary))
        Spacer(Modifier.width(StScale.insetXs.dp))
        Text(text, fontSize = StUi.plansMetaTextSp.sp)
    }
}

/** 功能动作行：复制（页侧事件）/拨号 tel/导航 geo（零权限全 intent；空层消失）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActionRow(fact: Artifact, onCopy: (String) -> Unit) {
    val context = LocalContext.current
    val code = (fact.payload["copyable_code"] as? JsonPrimitive)?.contentOrNull
    val phone = (fact.payload["contact_phone"] as? JsonPrimitive)?.contentOrNull
    val location = (fact.payload["location"] as? JsonPrimitive)?.contentOrNull
    FlowRow(
        Modifier.padding(top = StScale.insetMd.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(StScale.insetSm.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(StScale.insetSm.dp),
    ) {
        if (!code.isNullOrEmpty()) {
            OutlinedButton(onClick = { onCopy(code) }, enabled = true) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(StUi.planAddIconDp.dp))
                Spacer(Modifier.width(StScale.insetXs.dp))
                Text("复制")
            }
        }
        if (!phone.isNullOrEmpty()) {
            OutlinedButton(onClick = {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")))
                }
            }) {
                Icon(Icons.Outlined.Phone, contentDescription = null, modifier = Modifier.size(StUi.planAddIconDp.dp))
                Spacer(Modifier.width(StScale.insetXs.dp))
                Text("拨号 $phone")
            }
        }
        if (!location.isNullOrEmpty()) {
            OutlinedButton(onClick = {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(location)}")))
                }
            }) {
                Icon(Icons.Outlined.Place, contentDescription = null, modifier = Modifier.size(StUi.planAddIconDp.dp))
                Spacer(Modifier.width(StScale.insetXs.dp))
                Text("导航")
            }
        }
    }
}

/** 溯源手风琴（默认折叠；raw_text 永存原文）。 */
@Composable
private fun RawTextAccordion(fact: Artifact) {
    val palette = LocalStColors.current
    val raw = (fact.payload["raw_text"] as? JsonPrimitive)?.contentOrNull
    if (raw.isNullOrEmpty()) return
    var open by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.padding(top = StScale.insetMd.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { open = !open }
                .padding(vertical = StScale.insetXs.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(StUi.iconSmDp.dp),
                tint = Color(palette.textSecondary),
            )
            Spacer(Modifier.width(StScale.insetXs.dp))
            Text("查看原文", style = MaterialTheme.typography.bodySmall, color = Color(palette.textSecondary))
        }
        if (open) {
            Text(
                raw,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(palette.projectionBg), RoundedCornerShape(StScale.radiusBlock.dp))
                    .padding(StScale.insetMd.dp),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** 类别药丸微标（Header 层）。 */
@Composable
private fun Pill(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String) {
    val palette = LocalStColors.current
    Row(
        Modifier
            .background(Color(palette.fixedSlotBg), RoundedCornerShape(StScale.radiusCapsule.dp))
            .padding(horizontal = StScale.insetSm.dp, vertical = StUi.factAnchorRowVDp.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(StUi.factChipIconDp.dp), tint = Color(palette.textSecondary))
        Spacer(Modifier.width(StUi.compactPadDp.dp))
        Text(label, fontSize = StUi.plansMetaTextSp.sp, color = Color(palette.textSecondary))
    }
}

/** 琥珀常驻条（一致性提示，派生不入库）。 */
@Composable
internal fun AmberBar(text: String, onTap: () -> Unit) {
    val palette = LocalStColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color(palette.voucherBg), RoundedCornerShape(StScale.radiusBlock.dp))
            .clickable(onClick = onTap)
            .padding(horizontal = StScale.insetMd.dp, vertical = StScale.insetSm.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.WarningAmber, contentDescription = null, modifier = Modifier.size(StUi.planAddIconDp.dp))
        Spacer(Modifier.width(StUi.compactGapDp.dp))
        Text(text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
    }
}
