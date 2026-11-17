package app.shiguang.core.model.collab

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 网络连通性监听 + 发件箱常驻弹出（lib/collab/connectivity.dart Kotlin 等价，
 * Phase 4 编排收口项：Drainer 接网络监听常驻）。
 *
 * 弱网下本地操作只落 local_outbox_events，联网才能按序弹出。本模块把
 * [OutboxDrainer] 挂到「连通性变化」上——网络从断到通的瞬间自动重 drain，弱网
 * 恢复不丢操作；同时暴露 [CollabDrainScheduler.trigger] 供应用层（回到前台 /
 * 定时心跳）主动触发。
 *
 * 连通性抽象与具体探测实现解耦：测试注入脚本化假监听；真机实现归 :app
 * （D10③ 拍板：core 接口+逻辑本轮，Android NetworkCallback 真实现 P3 接线）。
 */

/** 连通性监听抽象：发射 true（在线）变化，[isOnline] 取当前态。 */
interface ConnectivityWatcher {
    /** 连通性状态流（仅在发生**变化**时发射；首值为当前态）。 */
    val onConnectivityChanged: SharedFlow<Boolean>

    /** 当前是否在线。 */
    suspend fun isOnline(): Boolean
}

/** 轮询式连通性监听（真实可用、零第三方依赖；Dart PollingConnectivityWatcher 等价）。
 *
 * 周期性调用 [probe]（默认 DNS 解析探测——真机注入由 :app 提供），比较前后态，仅在
 * 跃迁时发射——断→通触发发件箱重 drain，通→断静默忽略（离线本就不该 drain）。 */
class PollingConnectivityWatcher(
    private val probe: suspend () -> Boolean,
    private val intervalMs: Long = 15_000,
) : ConnectivityWatcher {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val changes = MutableSharedFlow<Boolean>(
        replay = 1, // 首值为当前态（镜像 Dart 首值发射语义）
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override val onConnectivityChanged: SharedFlow<Boolean> get() = changes

    override suspend fun isOnline(): Boolean = probe()

    private var pollJob: Job? = null
    private var last: Boolean? = null

    /** 启动轮询（幂等）。先立即探测一次并作为首值发射，随后周期探测。 */
    fun start() {
        if (pollJob != null) return
        pollJob = scope.launch {
            while (isActive) {
                tick()
                delay(intervalMs)
            }
        }
    }

    private suspend fun tick() {
        val now = try {
            probe()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false // 探针异常=按离线处理（不可达即离线语义）
        }
        if (last == null || last != now) {
            last = now
            changes.tryEmit(now)
        }
    }

    /** 释放轮询定时器。 */
    fun dispose() {
        pollJob?.cancel()
        pollJob = null
    }
}

/** 发件箱常驻弹出调度器：把 [OutboxDrainer] 接到连通性监听上。
 *
 * - 网络恢复（断→通）自动 drain；
 * - [trigger] 供应用层（回到前台 / 心跳）主动 drain；
 * - 持有 [OutboxDrainer] 引用，多源触发共享同一把锁，绝不并发弹出（§11.1 保序铁律
 *   ——否则 pending() 与 remove() 之间存在 TOCTOU 竞态，并发 drain 会双重发送同一条）。 */
class CollabDrainScheduler(
    private val drainer: OutboxDrainer,
    private val connectivity: ConnectivityWatcher,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val reportsFlow = MutableSharedFlow<DrainReport>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** 每轮弹出战报（sent/conflicts/stoppedByNetwork），供 UI 渲染同步状态。 */
    val reports: SharedFlow<DrainReport> get() = reportsFlow

    /** 弹出串行锁：所有触发（网络恢复 / 手动 / 心跳）经同一把锁排队，绝不并发弹出。 */
    private val drainMutex = Mutex()
    private var subJob: Job? = null

    /** 接入常驻监听（幂等）。当前若已在线也立即 drain 一次。 */
    suspend fun start() {
        if (subJob != null) return
        subJob = scope.launch {
            connectivity.onConnectivityChanged.collect { online ->
                if (online) trigger()
            }
        }
        if (connectivity.isOnline()) trigger()
    }

    /** 主动触发一轮弹出（应用层：回前台 / 心跳 / 用户手动）。与自动触发共享串行锁，
     *  多源并发触发亦安全。战报上抛 reports 流并返回给触发方（镜像 Dart 双路）。 */
    suspend fun trigger(): DrainReport = drainMutex.withLock {
        val report = drainer.drain()
        lastReport = report
        reportsFlow.tryEmit(report)
        report
    }

    @Volatile private var lastReport: DrainReport? = null

    /** 断开监听（应用生命周期销毁时）。 */
    suspend fun dispose() {
        subJob?.cancel()
        subJob = null
    }
}
