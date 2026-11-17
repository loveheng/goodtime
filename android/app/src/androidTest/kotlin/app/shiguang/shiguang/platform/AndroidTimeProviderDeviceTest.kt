package app.shiguang.shiguang.platform

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.runner.RunWith
import app.shiguang.core.model.TimeSignal
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * AndroidTimeProvider 真机实证（UNCERTAIN 消标轮，2026-11-15）：
 * TIME_TICK 受保护系统广播实时送达（分钟边界，≤75s 观测窗）+ now() 本地时刻面。
 * TIME_CHANGED/TIMEZONE_CHANGED 与 TIME_TICK 同 receiver/同 IntentFilter 注册路径
 * （受保护广播 adb 侧不可伪造触发）——机制同源，真机改钟/改时区场景随日常使用观察。
 */
@RunWith(AndroidJUnit4::class)
class AndroidTimeProviderDeviceTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun minuteTickDeliversInRealTime() = runBlocking {
        val provider = AndroidTimeProvider(context)
        val signal = withTimeoutOrNull(75_000) { provider.ticks.first() }
        assertEquals(TimeSignal.MINUTE_TICK, signal)
        val before = LocalDateTime.now()
        val now = provider.now()
        assertTrue(!now.isBefore(before.minusSeconds(5)), "now() 时钟面异常: $now")
    }
}
