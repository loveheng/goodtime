package app.shiguang.shiguang.platform

import android.content.pm.ApplicationInfo
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler

/**
 * 日志门面（§9「异常与日志纪律」：移植期不引入 Timber，android.util.Log + 门面收敛）。
 *
 * 隔离口径：Debug 完整；Release 只留错误码与事件 ID——严禁输出日程正文、命令
 * 载荷与 E2EE 密钥材料。调用方纪律：传入门面即视为已按调用点红线收敛（门面
 * 不做内容脱敏）。
 *
 * [release] 为进程级只写一次的基建配置位（init 由 Application.onCreate 注入），
 * 非领域状态——不属 K6 禁的可变全局业务单例；未 init 时按 Release 收敛（保守默认）。
 */
object ShiguangLog {
    private const val PREFIX = "Shiguang/"

    @Volatile
    private var release: Boolean = true

    /** Application.onCreate 注入一次（按系统 FLAG_DEBUGGABLE 判定，不依赖 BuildConfig）。 */
    fun init(context: Context) {
        release = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0
    }

    fun d(tag: String, msg: String) {
        if (!release) Log.d(PREFIX + tag, msg)
    }

    fun i(tag: String, msg: String) {
        if (!release) Log.i(PREFIX + tag, msg)
    }

    fun w(tag: String, msg: String) {
        Log.w(PREFIX + tag, msg)
    }

    /** 错误：Release 只留错误码与事件 ID（code 须为码/枚举名，不得含正文）。 */
    fun e(tag: String, code: String, eventId: String? = null, t: Throwable? = null) {
        if (release) {
            Log.e(PREFIX + tag, "code=$code event=$eventId")
        } else {
            Log.e(PREFIX + tag, "code=$code event=$eventId", t)
        }
    }

    /** 统一协程异常 handler（根作用域装配点 §9 协程纪律：只记录，不崩进程）。 */
    fun coroutineHandler(): CoroutineExceptionHandler =
        CoroutineExceptionHandler { _, e ->
            e("coroutine", e.javaClass.name, null, e)
        }
}
