package app.shiguang.shiguang.ui.collab

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import app.shiguang.core.tokens.StScale
import app.shiguang.core.tokens.StUi
import app.shiguang.shiguang.ui.theme.LocalStColors
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors

/**
 * 扫一扫建联（behavior/scan.md 转正清单 §B，D26）：相机预览（CameraX PreviewView
 * + ZXing ImageAnalysis 逐帧解码）+ 权限态 + 手动兜底 + 错误条三态。零落库。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanPage(
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm: ScanViewModel = viewModel(factory = ScanViewModel.factory)
    val s by vm.uiState.collectAsState()
    val context = LocalContext.current

    // 权限请求（ActivityResult 原生；不引 Accompanist）
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        vm.setPermission(granted)
    }
    LaunchedEffect(Unit) {
        val granted = context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (granted) vm.setPermission(true) else launcher.launch(Manifest.permission.CAMERA)
    }
    LaunchedEffect(vm) {
        vm.events.collect { e ->
            if (e is ScanEvent.InviteSent) onDone()
        }
    }

    Column(modifier.fillMaxSize()) {
        TopAppBar(title = { Text("扫一扫建联") })
        Box(Modifier.fillMaxSize().weight(1f)) {
            when (s.permissionGranted) {
                null, false -> PermissionArea(onRequest = { launcher.launch(Manifest.permission.CAMERA) })
                else -> ScannerArea(
                    submitting = s.submitting,
                    error = s.error,
                    onCode = { vm.handle(it) },
                )
            }
        }
        ManualArea(
            busy = s.submitting,
            onSubmit = { vm.submitManual(it) },
        )
    }
}

/** 权限态（§B：图标+文案+授予钮，居中列）。 */
@Composable
private fun PermissionArea(onRequest: () -> Unit) {
    val palette = LocalStColors.current
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = StUi.scanInsetDp.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        Icon(
            Icons.Outlined.PhotoCamera,
            contentDescription = null,
            modifier = Modifier.size(StUi.scanCameraIconDp.dp),
            tint = Color(palette.textSecondary),
        )
        Spacer(Modifier.height(StScale.insetLg.dp))
        Text(
            "需要相机权限才能扫码",
            fontSize = StUi.scanBodyTextSp.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(StScale.insetMd.dp))
        Button(onClick = onRequest) { Text("授予相机权限") }
        Spacer(Modifier.weight(1f))
    }
}

/** 相机预览态（§B：全幅预览+submitting 转圈+错误条/提示）。 */
@Composable
private fun ScannerArea(
    submitting: Boolean,
    error: String?,
    onCode: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val reader = remember {
        MultiFormatReader().apply {
            setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true))
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            executor.shutdown()
            reader.reset()
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val providerFuture = ProcessCameraProvider.getInstance(ctx)
            providerFuture.addListener({
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor) { proxy ->
                    // 防连发由 VM done/submitting 双闸承载（Dart _handle 同构）——解码器恒跑
                    val text = decodeQr(reader, proxy)
                    if (text != null) {
                        previewView.post { onCode(text) }
                    }
                }
                runCatching {
                    provider.unbindAll()
                    provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                }
            }, ctx.mainExecutor)
            previewView
        },
    )

    if (submitting) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .padding(bottom = StUi.scanInsetDp.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        if (error != null) {
            Text(
                error,
                modifier = Modifier
                    .padding(horizontal = StUi.scanInsetDp.dp)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.87f), androidx.compose.foundation.shape.RoundedCornerShape(StScale.radiusBlock.dp))
                    .padding(StScale.insetMd.dp),
                color = Color.White,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        } else {
            Text("把对方名片放进框里", color = Color.White.copy(alpha = 0.7f))
        }
    }
}

/** YUV → ZXing 解码（QR only；失败回 null 静默续帧）。 */
private fun decodeQr(reader: MultiFormatReader, proxy: ImageProxy): String? = try {
    val plane = proxy.planes[0]
    val bytes = ByteArray(plane.buffer.remaining()).also { plane.buffer.get(it) }
    val source = PlanarYUVLuminanceSource(
        bytes, plane.rowStride, proxy.height, 0, 0, proxy.width.coerceAtMost(plane.rowStride), proxy.height, false,
    )
    val result = reader.decodeWithState(BinaryBitmap(HybridBinarizer(source)))
    val text = result.text
    reader.reset()
    text
} catch (e: Exception) {
    reader.reset()
    null
} finally {
    proxy.close()
}

/** 手动兜底区（§B：Divider+标题+输入框+提交钮；暂存 rememberSaveable——丢失点 #9）。 */
@Composable
private fun ManualArea(busy: Boolean, onSubmit: (String) -> Unit) {
    val palette = LocalStColors.current
    var text by rememberSaveable { mutableStateOf("") }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = StScale.insetLg.dp, top = StScale.insetMd.dp, end = StScale.insetLg.dp, bottom = StUi.scanInsetDp.dp),
    ) {
        androidx.compose.material3.HorizontalDivider()
        Spacer(Modifier.height(StScale.insetXs.dp))
        Text(
            "手动填入名片串",
            fontSize = StUi.scanHintTextSp.sp,
            color = Color(palette.textSecondary),
        )
        Spacer(Modifier.height(StUi.compactGapDp.dp))
        Row {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f),
                singleLine = false,
                enabled = !busy,
                placeholder = { Text("粘贴对方名片二维码内容") },
            )
            Spacer(Modifier.width(StScale.insetSm.dp))
            Button(onClick = { onSubmit(text) }, enabled = !busy) { Text("提交") }
        }
    }
}
