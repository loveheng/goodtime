package app.shiguang.shiguang.ui.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.shiguang.core.action.BridgeRepositoryPort
import app.shiguang.core.model.ScheduleDay
import app.shiguang.core.tokens.StColorsPalette
import app.shiguang.core.tokens.StUi
import app.shiguang.core.tokens.StScale
import app.shiguang.shiguang.ui.theme.LocalStColors
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

@Composable
fun NowNextCard(
    repo: BridgeRepositoryPort,
    now: LocalDateTime,
    modifier: Modifier = Modifier,
) {
    val palette = LocalStColors.current
    var snapshot by remember { mutableStateOf<NowNextSnapshot?>(null) }
    LaunchedEffect(now.truncatedTo(ChronoUnit.DAYS), now.minute) {
        snapshot = computeNowNext(repo, now)
    }
    val s = snapshot ?: return
    if (s.current == null && s.next == null) return
    Column(
        modifier
            .fillMaxWidth()
            .padding(start = StScale.insetLg.dp, end = StScale.insetLg.dp, top = StScale.insetXs.dp, bottom = StScale.insetXs.dp)
            .background(
                MaterialTheme.colorScheme.surfaceContainerHighest,
                RoundedCornerShape(StScale.radiusCard.dp),
            )
            .padding(StScale.insetMd.dp),
    ) {
        s.current?.let { b ->
            NowNextRow(
                leading = "进行中",
                tint = MaterialTheme.colorScheme.primary,
                label = s.currentLabel ?: "(未命名)",
                time = "${ScheduleDay.clockOf(b.startMin.toInt())}–${ScheduleDay.clockOf(b.endMin.toInt())}",
                palette = palette,
            )
        }
        if (s.current != null && s.next != null) Spacer(Modifier.padding(StScale.insetXs.dp))
        s.next?.let { b ->
            NowNextRow(
                leading = "下一个",
                tint = Color(palette.textSecondary),
                label = s.nextLabel ?: "(未命名)",
                time = ScheduleDay.clockOf(b.startMin.toInt()),
                palette = palette,
            )
        }
    }
}

@Composable
private fun NowNextRow(leading: String, tint: Color, label: String, time: String, palette: StColorsPalette) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            leading,
            modifier = Modifier
                .background(tint.copy(alpha = 0.16f), RoundedCornerShape(StScale.radiusCapsule.dp))
                .padding(horizontal = StScale.insetSm.dp, vertical = StUi.compactPadDp.dp),
            fontSize = StUi.compactTextSp.sp,
            fontWeight = FontWeight.SemiBold,
            color = tint,
        )
        Spacer(Modifier.width(StUi.compactGapLgDp.dp))
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = Color(palette.textPrimary),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(StScale.insetSm.dp))
        Text(
            time,
            style = MaterialTheme.typography.bodySmall,
            color = Color(palette.textSecondary),
        )
    }
}
