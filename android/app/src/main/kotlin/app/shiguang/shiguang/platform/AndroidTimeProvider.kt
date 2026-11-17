package app.shiguang.shiguang.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import app.shiguang.core.model.TimeProvider
import app.shiguang.core.model.TimeSignal
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.time.LocalDateTime

/**
 * TimeProvider 平台实现（§9 时钟基建行）：运行时动态注册系统广播——
 * ACTION_TIME_TICK 分钟级（Android 8 起禁静态注册）、ACTION_TIME_CHANGED
 * （用户改时钟）、ACTION_TIMEZONE_CHANGED（时区切换）。消费方收流后 now()
 * 重算作息日；ON_RESUME 前台恢复重算由 P4 Activity 层接管。
 */
// 真机实证（2026-11-15 仪器测试）：TIME_TICK 分钟边界实时送达（≤75s 观测窗）——
// 回归钉=AndroidTimeProviderDeviceTest。TIME_CHANGED/TIMEZONE_CHANGED 同 receiver
// 注册路径（受保护广播不可仪器伪造触发）；Doze 深睡延迟面→ (风险) 观察项挂 todos。
class AndroidTimeProvider(context: Context) : TimeProvider {

    private val _ticks = MutableSharedFlow<TimeSignal>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val ticks: SharedFlow<TimeSignal> = _ticks

    override fun now(): LocalDateTime = LocalDateTime.now()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_TIME_TICK -> _ticks.tryEmit(TimeSignal.MINUTE_TICK)
                Intent.ACTION_TIME_CHANGED -> _ticks.tryEmit(TimeSignal.TIME_CHANGED)
                Intent.ACTION_TIMEZONE_CHANGED -> _ticks.tryEmit(TimeSignal.TIMEZONE_CHANGED)
            }
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        // 三者均为受保护系统广播——NOT_EXPORTED 仍可接收，同时阻断伪冒源；
        // 进程级存活（与 Application 同生命周期，无配对注销面）
        context.applicationContext.registerReceiver(
            receiver, filter, Context.RECEIVER_NOT_EXPORTED,
        )
    }
}
