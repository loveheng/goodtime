package app.shiguang.shiguang.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.shiguang.core.action.Housekeeper
import app.shiguang.core.model.ScheduleDay
import app.shiguang.core.model.SettingsKeys
import app.shiguang.shiguang.ShiguangApplication
import app.shiguang.shiguang.platform.ShiguangLog
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 过夜日切闹钟接收器（fgs_housekeeper.md 过目 2026-11-15）：wake 边界触发 →
 * dailyCutIfNeeded（幂等闸）→ 重排下一日。服务前台保活下进程存活，日切走容器
 * 根作用域（不阻塞主线程）；receiver 仅作闹钟触发面。
 */
class ShiguangAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val container = (context.applicationContext as? ShiguangApplication)?.container ?: return
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val appScope = container.applicationScope
        val time = container.time
        val repo = container.repo
        appScope.launch {
            try {
                Housekeeper(repo).dailyCutIfNeeded(time.now())
            } catch (e: Exception) {
                ShiguangLog.e("housekeeper", e.javaClass.name)
            }
            // 重排下一作息日 wake 边界
            try {
                val pi = PendingIntent.getBroadcast(
                    context, 0,
                    Intent(context, ShiguangAlarmReceiver::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                val raw = repo.settingsAll()
                val wake = SettingsKeys.intOf(
                    raw.keys.associateWith {
                        (raw[it] as? JsonPrimitive)?.contentOrNull ?: ""
                    },
                    SettingsKeys.WAKE_TIME,
                ) ?: 420
                val at = ScheduleDay.nextWakeBoundary(time.now(), wake)
                    .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                } else {
                    alarmManager.set(AlarmManager.RTC_WAKEUP, at, pi)
                }
            } catch (e: Exception) {
                ShiguangLog.w("housekeeper", "[DEGRADE][alarm_reschedule] ${e.message}")
            }
        }
    }
}
