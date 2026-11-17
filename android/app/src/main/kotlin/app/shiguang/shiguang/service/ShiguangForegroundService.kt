package app.shiguang.shiguang.service

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import app.shiguang.core.action.Housekeeper
import app.shiguang.core.model.ScheduleDay
import app.shiguang.core.model.SettingsKeys
import app.shiguang.core.model.TimeSignal
import app.shiguang.shiguang.AppContainer
import app.shiguang.shiguang.ShiguangApplication
import app.shiguang.shiguang.platform.ShiguangLog
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 前台保活服务（flutter_foreground_task 平台替换件，行为清单 fgs_housekeeper.md
 * 过目 2026-11-15；P3 后台服务规格落地）。
 *
 * 职责=**承载件**（业务零逻辑）：CPU/Wi-Fi 锁全程持有（Doze 应答红线：无锁即假死
 * 超时——拾贝实测）+ 过夜日切精确闹钟排程 + LOW 通知；日切调容器内 Housekeeper。
 * specialUse 型（Android 15 dataSync 6h/24h 配额规避；subtype 说明挂 P5 发布清单）。
 * 启动入口=MCP 开关（P4 设置页接线）；POST_NOTIFICATIONS 运行时申请随 P4 Activity。
 */
class ShiguangForegroundService : Service() {

    private val container: AppContainer by lazy {
        (applicationContext as ShiguangApplication).container
    }
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var tickJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW,
            ).apply { description = CHANNEL_DESC },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(
            NOTIFICATION_ID, buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        acquireLocks()
        scheduleNextCut()
        observeClockSignals()
        // 服务拉起=保活路径冷启动点：幂等闸在 last_daily_cut（清单三重防护之一）
        container.applicationScope.launch {
            runCatching { Housekeeper(container.repo).dailyCutIfNeeded(container.time.now()) }
                .onFailure { ShiguangLog.e("housekeeper", it.javaClass.name) }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        tickJob?.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        wifiLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): android.os.IBinder? = null

    /** Doze 应答红线：CPU 锁防 accept/协程线程被挂起，Wi-Fi 锁防 Wi-Fi 休眠断链。 */
    private fun acquireLocks() {
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_TAG).apply { acquire() }
        val wm = applicationContext.getSystemService(WifiManager::class.java)
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, WAKE_TAG).apply { acquire() }
    }

    /**
     * 过夜日切精确闹钟（SCHEDULE_EXACT_ALARM，Android 13+ 用户授权——未授权降级
     * inexact set() 不崩）；触发 → [ShiguangAlarmReceiver] → dailyCutIfNeeded → 重排下一日。
     */
    private fun scheduleNextCut() {
        // 排程走根作用域（settingsAll 挂起面；服务存活下异步完成）
        container.applicationScope.launch { scheduleNextCutInner() }
    }

    private suspend fun scheduleNextCutInner() {
        val am = getSystemService(AlarmManager::class.java)
        val pi = alarmPendingIntent()
        val at = nextCutAt()
        try {
            if (am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.set(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (e: SecurityException) {
            // DEGRADE: [alarm_unavailable] 精确闹钟授权被撤——降级 inexact（日切仍有
            // 冷启动幂等闸与分钟 tick 兜底，不崩）
            ShiguangLog.w("housekeeper", "[DEGRADE][alarm_unavailable] ${e.message}")
            am.set(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    private suspend fun nextCutAt(): Long {
        val raw = container.repo.settingsAll()
        val wake = SettingsKeys.intOf(
            raw.keys.associateWith { (raw[it] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull ?: "" },
            SettingsKeys.WAKE_TIME,
        ) ?: 420
        val next = ScheduleDay.nextWakeBoundary(container.time.now(), wake)
        return next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    /** 用户改钟/改时区 → 重排（TIME_TICK 分钟信号不重排——消费面在 P4 UI）。 */
    private fun observeClockSignals() {
        tickJob = container.applicationScope.launch {
            container.time.ticks.collect { signal ->
                if (signal != TimeSignal.MINUTE_TICK) scheduleNextCut()
            }
        }
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        return builder
            .setContentTitle(NOTIFICATION_TITLE)
            .setContentText(NOTIFICATION_TEXT)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setOngoing(true)
            .build()
    }

    private fun alarmPendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        this, 0,
        Intent(this, ShiguangAlarmReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        const val CHANNEL_ID = "shiguang_fg"
        const val CHANNEL_NAME = "拾光后台任务"
        const val CHANNEL_DESC = "拾光后台处理（管家日切 / MCP 服务）通知"
        const val NOTIFICATION_ID = 1
        const val NOTIFICATION_TITLE = "拾光 · MCP 服务运行中"
        const val NOTIFICATION_TEXT =
            "仅本机回环（内部调试）；桌面先 adb reverse tcp:8765 tcp:8765"
        private const val WAKE_TAG = "shiguang:fg"

        /** 测试 seam：wake 边界毫秒换算（纯几何，容器时刻注入）。 */
        fun wakeBoundaryMillis(now: LocalDateTime, wakeMinute: Int, zone: ZoneId = ZoneId.systemDefault()): Long =
            ScheduleDay.nextWakeBoundary(now, wakeMinute).atZone(zone).toInstant().toEpochMilli()
    }
}
