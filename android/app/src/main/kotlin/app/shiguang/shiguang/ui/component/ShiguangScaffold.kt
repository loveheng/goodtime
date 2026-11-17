package app.shiguang.shiguang.ui.component

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel

/** Snackbar 任意时长档位（丢失点 #1：现网显式时长 1s×1 / 2s×12 / 3s×11 / 8s×1，改写为原生档属节奏变更，禁默认归并）。 */
enum class ShiguangSnackbarDuration(val ms: Long) {
    SEC1(1_000),
    SEC2(2_000),
    SEC3(3_000),
    SEC8(8_000),
    ;

    /** 毫秒 → 档位（VM 事件面 UiEvent.durationMs 消费桥；未知值取最近档）。 */
    companion object {
        fun fromMs(ms: Long): ShiguangSnackbarDuration = when (ms) {
            in 0..1_499 -> SEC1
            in 1_500..2_499 -> SEC2
            in 2_500..5_000 -> SEC3
            else -> SEC8
        }
    }
}

/**
 * 原语外壳（P4①-c，ui_foundation 清单转正版）——丢失点一次性内建修复，新页面自动继承：
 * ① edge-to-edge + systemBars 安全区视觉避让（M3 Scaffold 默认 contentWindowInsets）；
 * ② SnackbarHost 任意时长四档（[showShiguangSnackbar]）；
 * ③ FAB 滚动净空：滚动体底部 padding 取 `StScale.fabClearanceDp`（88dp，末尾内容可滚出覆盖区）。
 * 组件只内建结构与度量，零业务逻辑——页面内容经参数槽注入（State Hoisting）。
 */
@Composable
fun ShiguangScaffold(
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    snackbarHost: @Composable () -> Unit = { ShiguangSnackbarHost(snackbarHostState) },
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = modifier,
        // Day-2 全屏修正（用户报「小一圈/大外边界」2026-11-17）：新版 M3 Scaffold
        // 会把 contentWindowInsets 应用到整个 Surface（表面随系统栏内缩，透明栏下
        // 露出黑底窗口背景）——清零后表面满窗，系统栏避让回归 TopAppBar/NavigationBar
        // 自带 insets（M3 默认行为）。
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = snackbarHost,
        topBar = topBar,
        bottomBar = bottomBar,
        floatingActionButton = floatingActionButton,
        content = content,
    )
}

/** SnackBar 承载（M3 SnackbarHost 包装，接口形状锚定任意时长档位；
 *  [containerColorOverride] =错误底色 override——Dart _toast error 口径（update_page
 *  转正清单 §A），null=M3 默认；取色由壳层状态提升驱动，与 floatingSnackbar 同法）。 */
@Composable
fun ShiguangSnackbarHost(
    hostState: SnackbarHostState,
    containerColorOverride: Color? = null,
) {
    SnackbarHost(hostState) { data ->
        if (containerColorOverride != null) {
            Snackbar(snackbarData = data, containerColor = containerColorOverride)
        } else {
            Snackbar(snackbarData = data)
        }
    }
}

/** 任意时长 Snackbar：show(Indefinite) + 定时 dismiss 恢复 1/2/3/8s 显式时长。 */
suspend fun SnackbarHostState.showShiguangSnackbar(
    message: String,
    duration: ShiguangSnackbarDuration = ShiguangSnackbarDuration.SEC3,
    actionLabel: String? = null,
): SnackbarResult = coroutineScope {
    val autoDismiss = launch {
        delay(duration.ms)
        currentSnackbarData?.dismiss()
    }
    try {
        showSnackbar(message, actionLabel = actionLabel, duration = SnackbarDuration.Indefinite)
    } finally {
        autoDismiss.cancel()
    }
}
