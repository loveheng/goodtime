package app.shiguang.shiguang

import android.app.Application
import app.shiguang.shiguang.platform.ShiguangLog
import java.util.UUID

/**
 * 应用入口（§9「启动时序隔离」）：onCreate 只装配 Core（[container]——SQLite
 * 连接/命令面/时钟/AIDL 面）+ 崩溃护栏；图形/Compose 主题/页面预载推迟到首个
 * Activity.onCreate()。binder 服务与 UI 共用本容器（无 UI 独立构造）。
 *
 * P5 切包（2026-11-17）：FlutterApplication → 裸 Application（Dart 侧随 P5 前夜
 * 物理删除一并退场）。
 */
class ShiguangApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        ShiguangLog.init(this)
        installCrashGuard()
        container = AppContainer(this)
    }

    /** 崩溃护栏（不防崩）：记录错误码+事件 ID 后交还系统默认处理（Debug 附堆栈）。 */
    private fun installCrashGuard() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            val eventId = UUID.randomUUID().toString()
            ShiguangLog.e("crash", e.javaClass.name, eventId, e)
            previous?.uncaughtException(t, e)
        }
    }
}
