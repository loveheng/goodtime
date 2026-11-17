package app.shiguang.shiguang.ui.schedule

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.shiguang.core.model.ScheduleDay
import app.shiguang.core.tokens.StMotion
import app.shiguang.core.tokens.StScale
import app.shiguang.core.tokens.StUi
import app.shiguang.shiguang.ui.block.BlockStatus
import app.shiguang.shiguang.ui.theme.LocalStColors

/**
 * 双轨仪表盘条（ui-pages §3.1，P4③ N4，D13/D12 处置落地）：🔥 今日核心 N 项 ｜
 * 🛡️ 自由留白 X.Xh（freeMinutesIn 同源口径）｜ 电量三档紧凑横排（D13-b 修规范
 * 对齐实现）｜ 🏅 安全线徽章——未点亮灰 outline/点亮金色填充+点亮单次 spring 微动效
 * （StMotion.springDampingRatio 0.75/springStiffness 400；safelineMs=250 是感知窗
 * 口径非时长参数）。窄屏各段 ellipsis 有界，信息完整优先于两端对齐。
 * 电量点选命令接线（update_settings+reflow_day）随 N5；本件只上抛回调。
 */
@Composable
fun Dashboard(
    data: ScheduleDayData,
    modifier: Modifier = Modifier,
    onEnergySelect: (String) -> Unit = {},
) {
    val palette = LocalStColors.current
    val sparkCount = data.blocks.count { it.isDaySpark && it.status != BlockStatus.MELTED }
    val free = ScheduleDay.freeMinutesIn(data.wake, data.sleepAdj, data.occupiedIntervals())
    Row(
        modifier.padding(start = StScale.insetLg.dp, end = StScale.insetLg.dp, top = StUi.compactGapDp.dp, bottom = StUi.compactGapDp.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 三段并列（无 spacer 语义）：窄屏各段可缩（ellipsis 有界）
        Text(
            "🔥 今日核心 $sparkCount 项",
            modifier = Modifier.weight(1f, fill = false),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.width(StUi.compactGapLgDp.dp))
        Text(
            "🛡️ 自由留白 ${"%.1f".format(free / 60f)}h",
            modifier = Modifier.weight(1f, fill = false),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.width(StScale.insetSm.dp))
        // 电量三档（§6 十轮：手动点选、永不接传感器；低电量触发当日水流降档重算）
        EnergyToggle(current = data.energy, onSelect = onEnergySelect)
        Spacer(Modifier.width(StUi.compactGapDp.dp))
        SafelineBadge(lit = data.safelineLit)
    }
}

/** 晨间生理电量三档紧凑横排（⚡满血/🔋平稳/🪫低电量）——D13-b 拍板形态。 */
@Composable
private fun EnergyToggle(current: String, onSelect: (String) -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    @Composable
    fun tier(level: String, icon: String, label: String) {
        val selected = current == level
        Text(
            icon,
            modifier = Modifier
                .padding(horizontal = StUi.compactPadDp.dp)
                .semantics { contentDescription = label }
                .clickable(enabled = !selected) { onSelect(level) },
            fontSize = StUi.energyGlyphSp.sp,
            color = if (selected) primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Row {
        tier("high", "⚡", "满血")
        tier("normal", "🔋", "平稳")
        tier("low", "🪫", "低电量（自动降档）")
    }
}

/**
 * 安全线徽章（ui-design §4.1/§3.3）：未点亮灰 outline、点亮金色填充；点亮瞬间
 * spring 单次微动效（阻尼 0.75/刚度 400 逐字取 StMotion，无固定时长）。
 */
@Composable
private fun SafelineBadge(lit: Boolean) {
    val palette = LocalStColors.current
    val fillFraction by animateFloatAsState(
        targetValue = if (lit) 1f else 0f,
        animationSpec = spring(
            dampingRatio = StMotion.springDampingRatio,
            stiffness = StMotion.springStiffness,
        ),
        label = "safelineSpring",
    )
    val borderColor = androidx.compose.ui.graphics.lerp(
        Color(palette.safelineOff), Color(palette.safelineOn), fillFraction,
    )
    val fillColor = androidx.compose.ui.graphics.lerp(
        Color.Transparent, Color(palette.safelineOn), fillFraction,
    )
    Box(
        modifier = Modifier
            .border(
                width = StUi.dividerWidthDp.dp,
                color = borderColor,
                shape = RoundedCornerShape(50),
            )
            .background(fillColor, RoundedCornerShape(50))
            .padding(horizontal = StScale.insetSm.dp, vertical = StUi.compactPadDp.dp),
    ) {
        Text(
            if (lit) "今日底线守住啦" else "底线待守",
            style = MaterialTheme.typography.labelSmall,
            color = if (lit) Color(palette.textPrimary) else Color(palette.textSecondary),
        )
    }
}
