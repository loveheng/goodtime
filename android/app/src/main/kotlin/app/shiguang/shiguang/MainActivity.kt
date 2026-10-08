package app.shiguang.shiguang

import android.content.Intent
import java.nio.ByteBuffer
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine

/**
 * 分享入口宿主（fact 草案 §2.3）：ACTION_SEND text/plain → 原生侧只做
 * "取 EXTRA_TEXT + 转投 Dart"，零解析（raw 入册一字不解析的纪律在 Dart
 * 命令层）。冷启动 onCreate 取 intent，热启动（singleTop）走 onNewIntent；
 * Dart 侧收到后 upsert_facts raw 入册（origin=shared）+ 开挂载 sheet。
 */
class MainActivity : FlutterActivity() {

    companion object {
        const val SHARE_CHANNEL = "app.shiguang/sharing"
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        forwardSharedText(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        forwardSharedText(intent)
    }

    private fun forwardSharedText(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        if (!"text/plain".equals(intent.type, ignoreCase = true)) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim() ?: return
        if (text.isEmpty()) return
        // UTF-8 JSON 串过 StandardMessageCodec（Dart 侧 json.decode 取 text 字段）
        val payload = org.json.JSONObject().put("text", text).toString().toByteArray()
        flutterEngine?.dartExecutor?.binaryMessenger?.send(SHARE_CHANNEL, ByteBuffer.wrap(payload))
    }
}
