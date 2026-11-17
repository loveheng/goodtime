package app.shiguang.shiguang

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import app.shiguang.shiguang.ui.gate.AppGate
import app.shiguang.shiguang.ui.shell.ShiguangAppShell
import app.shiguang.shiguang.ui.theme.ShiguangTheme

/**
 * P5 主入口（双轨灰度切流，D14 裁决的「P5 切包时并入/替换主入口」落地）：
 * 原生 Compose 宿主转正——AppGate 闸门（引导流→主框架；startDestination 由
 * 闸门 initialized 流驱动）+ 分享转投进程内直投（ACTION_SEND→pendingShare，
 * D23 裁决 §D.1）+ RemoteConfig 载入与 MCP 服务恢复（首 Activity 装配段，
 * D23 §D.2）。
 *
 * debug 变体由 DebugNativeMainActivity 子类覆写 [attachDebugBridge] 挂原生
 * 调试桥（SDK app-debug-bridge——语义树/自定义数据动作 + PixelCopy 截图；
 * port/token 进 logcat，release 零编译进包）。
 */
open class NativeMainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // edge-to-edge（M3 标准适配，WindowCompat 版——activity 库版本未到 1.8 的
        // enableEdgeToEdge）：状态栏/导航栏由 Compose 主题色连续承接，消窗口层
        // 黑带（Theme.Black 窗背景与顶栏 surface 断层）；图标亮色=深色主题基线
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightStatusBars = false
        val container = (applicationContext as ShiguangApplication).container
        // 首 Activity 装配段（启动分层⑤）：远程配置缓存预读 + MCP 服务恢复
        container.remoteConfig.load()
        container.mcpController.restoreIfNeeded()
        // 真机 AI 调试桥挂载点（空实现=release 不装配）；src/debug 变体覆写。
        attachDebugBridge()
        forwardSharedText(intent)
        setContent {
            ShiguangTheme {
                AppGate(shell = { ShiguangAppShell() })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        forwardSharedText(intent)
    }

    /** 分享直投（ACTION_SEND→pendingShare，零解析纪律在命令层——app_gate.md §B）。 */
    private fun forwardSharedText(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        if (!"text/plain".equals(intent.type, ignoreCase = true)) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim() ?: return
        if (text.isEmpty()) return
        (applicationContext as ShiguangApplication).container.postPendingShare(text)
    }

    /// 真机 AI 调试桥挂载点（debug-only）：main 源集空钩子，src/debug 变体
    /// DebugNativeMainActivity 覆写装配（release 零编译进产物）。
    protected open fun attachDebugBridge() {}
}
