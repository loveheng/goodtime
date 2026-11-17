package app.shiguang.shiguang.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.io.File
import java.io.FileInputStream

/**
 * 系统 PackageInstaller 会话通道（lib/update/installer.dart + MainActivity 安装段
 * Kotlin 归一，D23 裁决 N8c 随行）：用会话 API 直接读本地 APK 写入安装会话，绕开
 * ACTION_VIEW + FileProvider 的跨应用 URI 授权在 Android 14+ 上不稳定导致
 * 「软件包无效」的坑。双通道共用：MainActivity MethodChannel（Dart UpdatePage）
 * 与原生 UpdatePage 直调——纯移动重构，语义零变化。
 *
 * 生命周期：一次 [install] 注册一个 receiver，终态回调后自动反注册；
 * 宿主销毁时调 [release] 兜底（防 PENDING_USER_ACTION 悬挂）。
 */
class ApkInstaller(private val context: Context) {

    private var receiver: BroadcastReceiver? = null

    /** 本应用是否已获「安装未知应用」授权（Android 14+ 自更新前置）。 */
    fun canRequestPackageInstalls(): Boolean = context.packageManager.canRequestPackageInstalls()

    /** 拉起系统「安装未知应用」授权开关页（直达本应用条目）。 */
    fun openUnknownSourceSettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /**
     * 拉起系统安装器安装 APK。[onResult] 收状态串（pending_user_action/installed）；
     * [onError] 收失败消息。成功/失败终态自动 [release]。
     */
    fun install(path: String, onResult: (String) -> Unit, onError: (String) -> Unit) {
        val file = File(path)
        if (!file.exists()) {
            onError("APK 文件不存在: $path")
            return
        }
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = installer.createSession(params)
        val session = installer.openSession(sessionId)

        try {
            val out = session.openWrite("shiguang-apk", 0, file.length())
            val buf = ByteArray(BUFFER_SIZE)
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
            onError("写入安装会话失败: ${e.message}")
            return
        }

        val statusIntent = Intent(INSTALL_ACTION)
        val piFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pending = PendingIntent.getBroadcast(context, 0, statusIntent, piFlags)

        receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                val status = intent?.getIntExtra(
                    PackageInstaller.EXTRA_STATUS,
                    PackageInstaller.STATUS_FAILURE,
                ) ?: PackageInstaller.STATUS_FAILURE
                val msg = intent?.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                when (status) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        // 需用户在系统安装器中确认：拉起确认 intent（授权回装）
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
                        confirm?.let { c?.startActivity(it) ?: context.startActivity(it) }
                        onResult("pending_user_action")
                        release()
                    }
                    PackageInstaller.STATUS_SUCCESS -> {
                        onResult("installed")
                        release()
                    }
                    else -> {
                        onError(msg ?: "安装失败 status=$status")
                        release()
                    }
                }
            }
        }
        val filter = IntentFilter(INSTALL_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }

        try {
            session.commit(pending.intentSender)
        } catch (e: Exception) {
            session.abandon()
            release()
            onError("提交安装会话失败: ${e.message}")
        }
    }

    /** 反注册悬挂 receiver（终态自动调用；宿主销毁兜底再调，幂等）。 */
    fun release() {
        receiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (_: Exception) {
            }
        }
        receiver = null
    }

    companion object {
        const val INSTALL_ACTION = "app.shiguang.INSTALL_APK_RESULT"
        const val BUFFER_SIZE = 8192
    }
}
