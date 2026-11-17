package app.shiguang.shiguang.ui.collab

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.toRoute
import app.shiguang.core.tokens.StScale
import app.shiguang.core.tokens.StUi
import app.shiguang.shiguang.ui.component.ShiguangScaffold
import app.shiguang.shiguang.ui.theme.LocalStColors

/**
 * 核对安全码页（behavior/safety_number.md 转正清单 §B，T4.7）：双方身份公钥哈希
 * 指纹 6 组 5 位——当面或微信核对一致即确认身份未被中间人替换（端侧可验证）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SafetyNumberPage(
    friendUserId: String,
    modifier: Modifier = Modifier,
) {
    val vm: SafetyNumberViewModel = viewModel(
        key = "safety_$friendUserId",
        factory = SafetyNumberViewModel.factory(friendUserId),
    )
    val s by vm.uiState.collectAsState()
    val palette = LocalStColors.current
    LaunchedEffect(Unit) { vm.load() }

    ShiguangScaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("核对安全码") }) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(StUi.collabPageInsetDp.dp),
        ) {
            Text("和 $friendUserId 当面或微信核对以下数字", fontSize = StUi.collabHeroSp.sp, fontWeight = FontWeight.SemiBold, color = Color(palette.textPrimary))
            Spacer(Modifier.height(StScale.insetXs.dp))
            Text(
                "六组数字完全一致，即确认对方身份未被中间人替换；不一致请勿继续协同。",
                color = Color(palette.textSecondary),
            )
            Spacer(Modifier.height(StUi.collabPageInsetDp.dp))
            when {
                s.busy -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                s.error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Text(s.error!!, color = Color(palette.textSecondary))
                    Spacer(Modifier.height(StScale.insetLg.dp))
                    Button(onClick = { vm.load() }) {
                        Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(StUi.collabActionIconDp.dp))
                        Spacer(Modifier.width(StScale.insetXs.dp))
                        Text("重试")
                    }
                }
                s.digits != null -> DigitsGrid(s.digits!!)
            }
        }
    }
}

/** 数字网格（§B：两列行序，奇数总长末位空）。 */
@Composable
private fun DigitsGrid(digits: List<String>) {
    Column {
        var i = 0
        while (i < digits.size) {
            Row(Modifier.fillMaxWidth().padding(bottom = StUi.collabRowGapDp.dp + StUi.collabChipPadVDp.dp)) {
                DigitCell(label = "${i + 1}", value = digits[i], modifier = Modifier.weight(1f))
                Spacer(Modifier.width(StUi.collabFieldGapDp.dp))
                if (i + 1 < digits.size) {
                    DigitCell(label = "${i + 2}", value = digits[i + 1], modifier = Modifier.weight(1f))
                } else {
                    Spacer(Modifier.weight(1f))
                }
            }
            i += 2
        }
    }
}

/** 数字格（§B：sparkBg 圆角 12 padding(16,12)——label 13sp textSecondary + 数值 22sp w700 字距 2）。 */
@Composable
private fun DigitCell(label: String, value: String, modifier: Modifier = Modifier) {
    val palette = LocalStColors.current
    Row(
        modifier
            .background(Color(palette.sparkBg), RoundedCornerShape(StUi.collabCardRadiusDp.dp))
            .padding(horizontal = StUi.collabPageInsetDp.dp, vertical = StUi.collabDimPadDp.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Color(palette.textSecondary), fontSize = StUi.collabFootnoteSp.sp)
        Spacer(Modifier.weight(1f))
        Text(
            value,
            fontSize = StUi.collabSafetyValueSp.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = StUi.collabSafetyLetterSpacingSp.sp,
            color = Color(palette.textPrimary),
        )
    }
}
