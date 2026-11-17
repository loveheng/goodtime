package app.shiguang.shiguang.ui.collab

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.shiguang.core.tokens.StScale
import app.shiguang.core.tokens.StUi
import app.shiguang.shiguang.ui.component.ShiguangScaffold
import app.shiguang.shiguang.ui.theme.LocalStColors
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/**
 * 我的名片页（behavior/my_qr.md 转正清单 §B，T4.7）：端侧签发可扫码名片（TTL 须
 * 刷新）；QR 生成=ZXing QRCodeWriter（P3⑥ 换轨既定），240 白底 16 圆角容器。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyQrPage(modifier: Modifier = Modifier) {
    val vm: MyQrViewModel = viewModel(factory = MyQrViewModel.factory)
    val s by vm.uiState.collectAsState()
    val palette = LocalStColors.current
    LaunchedEffect(Unit) { vm.issue() }

    ShiguangScaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("我的名片") }) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(StUi.collabPageInsetDp.dp),
        ) {
            Text("让好友扫这张名片", fontSize = StUi.collabHeroSp.sp, fontWeight = FontWeight.SemiBold, color = Color(palette.textPrimary))
            Spacer(Modifier.height(StScale.insetXs.dp))
            Text(
                "对方会收到你的好友申请，确认后即互加成功。名片带有效期，过期重新点一下即可刷新。",
                color = Color(palette.textSecondary),
            )
            Spacer(Modifier.height(StUi.collabPageInsetDp.dp))
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                when {
                    s.busy -> CircularProgressIndicator()
                    s.qr != null -> QrImage(s.qr!!)
                    else -> Text(
                        s.error ?: "出错了",
                        color = Color(palette.textSecondary),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
            Spacer(Modifier.height(StUi.collabPageInsetDp.dp))
            if (s.error != null && !s.busy) {
                Button(onClick = { vm.issue() }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(StUi.collabActionIconDp.dp))
                    Spacer(Modifier.width(StScale.insetXs.dp))
                    Text("刷新名片")
                }
            }
        }
    }
}

/** QR 位图（§B：白底容器 16 圆角 padding 16 内 QrImageView 240 对位）。 */
@Composable
private fun QrImage(content: String) {
    val sizePx = 720 // 240dp × 3x DPR 位图栅格（显示端钳 240dp，同介质可复采）
    val bitmap = remember(content) {
        val matrix = QRCodeWriter().encode(
            content,
            BarcodeFormat.QR_CODE,
            sizePx,
            sizePx,
            mapOf(EncodeHintType.MARGIN to 0),
        )
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        for (x in 0 until sizePx) {
            for (y in 0 until sizePx) {
                bmp.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }
        bmp
    }
    Box(
        Modifier
            .background(Color.White, RoundedCornerShape(StUi.collabCardRadiusDp.dp))
            .padding(StScale.insetLg.dp),
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "我的名片二维码",
            modifier = Modifier.size(StUi.collabQrSizeDp.dp),
        )
    }
}
