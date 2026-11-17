package app.shiguang.core.model

import kotlinx.coroutines.flow.SharedFlow
import java.time.LocalDateTime

/** 时钟事件（宿主运行时信号——消费方收流后自行 now() 重算作息日切/水位线）。 */
enum class TimeSignal { MINUTE_TICK, TIME_CHANGED, TIMEZONE_CHANGED }

/**
 * 作息时钟端口（P2 时钟基建，§9 时钟基建行）：唯一合法取时入口——UI 层与
 * ViewModel 禁直取 System.currentTimeMillis()/Instant.now()（§9.1.2 禁止清单）。
 *
 * 实现方=宿主平台层（:app AndroidTimeProvider）：运行时动态注册
 * ACTION_TIME_TICK（分钟级，Android 8 起禁静态注册）/ ACTION_TIME_CHANGED /
 * ACTION_TIMEZONE_CHANGED；ON_RESUME 前台恢复重算作息日由 P4 Activity 层接管。
 */
interface TimeProvider {
    /** 当前时刻（本地时区；作息日切割与分钟几何的唯一参考）。 */
    fun now(): LocalDateTime

    /** 时钟事件热流（无粘滞/无重放；进程内广播驱动）。 */
    val ticks: SharedFlow<TimeSignal>
}
