package app.shiguang.shiguang

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer

/**
 * 分享入口宿主（fact 草案 §2.3）：ACTION_SEND text/plain → 原生侧只做
 * "取 EXTRA_TEXT + 转投 Dart"，零解析（raw 入册一字不解析的纪律在 Dart
 * 命令层）。冷启动 onCreate 取 intent，热启动（singleTop）走 onNewIntent；
 * Dart 侧收到后 upsert_facts raw 入册（origin=shared）+ 开挂载 sheet。
 *
 * 另承载应用内自更新安装通道（app.shiguang/installer）：用系统 PackageInstaller
 * 会话 API 直接把本地 APK 写入安装会话，绕开 ACTION_VIEW + FileProvider 在
 * Android 14+ 上跨应用 URI 授权不稳定而导致「软件包无效」的坑。
 */
class MainActivity : FlutterActivity() {

    companion object {
        const val SHARE_CHANNEL = "app.shiguang/sharing"
        const val INSTALL_CHANNEL = "app.shiguang/installer"
        const val INSTALL_ACTION = "app.shiguang.INSTALL_APK_RESULT"
    }

    private var installChannel: MethodChannel? = null
    private var installReceiver: BroadcastReceiver? = null
    private var installResult: MethodChannel.Result? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        forwardSharedText(intent)
        installChannel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, INSTALL_CHANNEL)
        installChannel?.setMethodCallHandler { call, result ->
            if (call.method == "installApk") {
                val path = call.argument<String>("path")
                if (path == null) {
                    result.error("INVALID_PATH", "path 为空", null)
                } else {
                    startInstall(path, result)
                }
            } else {
                result.notImplemented()
            }
        }
    }

    private fun startInstall(path: String, result: MethodChannel.Result) {
        val file = File(path)
        if (!file.exists()) {
            result.error("NO_FILE", "APK 文件不存在: $path", null)
            return
        }
        installResult = result

        val installer = packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = installer.createSession(params)
        val session = installer.openSession(sessionId)

        try {
            val out = session.openWrite("shiguang-apk", 0, file.length())
            val buf = ByteArray(8192)
            FileInputStream(file).use { input ->
                var read: Int
                while (input.read(buf).also { read = it } != -1) {
                    out.write(buf, 0, read)
                }
            }
            session.fsync(out)
            out.close()
        } catch (e: Exception) {
            session.abandon()
            installResult?.error("WRITE_FAILED", "写入安装会话失败: ${e.message}", null)
            installResult = null
            return
        }

        val statusIntent = Intent(INSTALL_ACTION)
        val piFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pending = PendingIntent.getBroadcast(this, 0, statusIntent, piFlags)

        installReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val status = intent?.getIntExtra(
                    PackageInstaller.EXTRA_STATUS,
                    PackageInstaller.STATUS_FAILURE,
                ) ?: PackageInstaller.STATUS_FAILURE
                val msg = intent?.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                when (status) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        // 需用户在系统安装器中确认：拉起确认 intent，并先回 success
                        val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent?.getParcelableExtra(
                                "android.intent.extra.INTENT",
                                Intent::class.java,
                            )
                        } else {
                            @Suppress("DEPRECATION")
                            intent?.getParcelableExtra<Intent>("android.intent.extra.INTENT")
                        }
                        confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        confirm?.let { startActivity(it) }
                        installResult?.success("pending_user_action")
                        installResult = null
                    }
                    PackageInstaller.STATUS_SUCCESS -> {
                        installResult?.success("installed")
                        cleanupInstall()
                    }
                    else -> {
                        installResult?.error("INSTALL_FAILED", msg ?: "安装失败 status=$status", null)
                        cleanupInstall()
                    }
                }
            }
        }
        val filter = IntentFilter(INSTALL_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(installReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(installReceiver, filter)
        }

        try {
            session.commit(pending.intentSender)
        } catch (e: Exception) {
            session.abandon()
            installResult?.error("COMMIT_FAILED", "提交安装会话失败: ${e.message}", null)
            cleanupInstall()
        }
    }

    private fun cleanupInstall() {
        installReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (_: Exception) {
            }
        }
        installReceiver = null
        installResult = null
    }

    override fun onDestroy() {
        cleanupInstall()
        super.onDestroy()
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
