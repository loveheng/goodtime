package app.shiguang.core.model.collab.relay

import app.shiguang.core.model.JdbcSqlStore
import app.shiguang.core.model.ShiguangSchema
import app.shiguang.core.model.collab.AccountSession
import app.shiguang.core.model.collab.AccountStatus
import app.shiguang.core.model.collab.BundleKeySource
import app.shiguang.core.model.collab.CollabBundleRepository
import app.shiguang.core.model.collab.CollabDrainScheduler
import app.shiguang.core.model.collab.CollabEngine
import app.shiguang.core.model.collab.ConnectivityWatcher
import app.shiguang.core.model.collab.DrainReport
import app.shiguang.core.model.collab.KeyStore
import app.shiguang.core.model.collab.MemoryIdentityStore
import app.shiguang.core.model.collab.MemorySecretStore
import app.shiguang.core.model.collab.OutboxDrainer
import app.shiguang.core.model.collab.OutboxRepository
import app.shiguang.core.model.collab.OutboxTransport
import app.shiguang.core.model.collab.SendAccepted
import app.shiguang.core.model.collab.SendOutcome
import app.shiguang.core.model.collab.relay.WakePayload
import app.shiguang.core.model.collab.wireCollabSystem
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import shiguang.relay.v1.RelayProtocol.ActionType

/** test/collab_engine_test.dart Kotlin 移植（编排收口集成）：三件套收口 + RELAY_BASE_URL
 *  串联 + D9 REKEY 旗标贯通 + 调度器四例（collab_drain_scheduler_test）。 */

/** 脚本化连通性监听（复用模式）。 */
private class FakeConnectivityWatcher : ConnectivityWatcher {
    private val flow = MutableSharedFlow<Boolean>(replay = 1, extraBufferCapacity = 16)
    override val onConnectivityChanged: MutableSharedFlow<Boolean> = flow
    private var online = false
    override suspend fun isOnline(): Boolean = online
    fun emit(v: Boolean) {
        online = v
        flow.tryEmit(v)
    }
}

private class FakeTransport : OutboxTransport {
    var sends = 0
    override suspend fun send(entry: app.shiguang.core.model.collab.OutboxEntry): SendOutcome {
        sends++
        return SendAccepted
    }
}

/** 脚本化 /sync 拉页：服务器有 seq 1..10；返回 since+1 直至到顶。 */
private fun scriptedPull(bundleId: String, since: Long): SyncPage {
    if (since >= 10) {
        return SyncPage(bundleId = bundleId, events = emptyList(), latestSeq = 10, hasMore = false)
    }
    val seq = since + 1
    return SyncPage(
        bundleId = bundleId,
        events = listOf(
            SyncEvent(
                seqId = seq,
                actionType = "ACCEPT",
                senderId = "u1",
                cipher = byteArrayOf(seq.toByte()),
                topologyRef = "",
                serverAtSec = 1,
            ),
        ),
        latestSeq = 10,
        hasMore = true,
    )
}

private suspend fun waitUntil(timeoutMs: Long = 5000, cond: suspend () -> Boolean) {
    val end = System.currentTimeMillis() + timeoutMs
    while (!cond()) {
        if (System.currentTimeMillis() > end) {
            throw IllegalStateException("条件未在 ${timeoutMs}ms 内满足")
        }
        kotlinx.coroutines.delay(10) // 挂起让路：runBlocking 事件循环得已处理收集协程
    }
}

private fun freshDb(): JdbcSqlStore = kotlinx.coroutines.runBlocking {
    val store = JdbcSqlStore.inMemory()
    ShiguangSchema.createAll(store)
    store
}

class CollabEngineTest {

    /** 标准装配（override 避网）。 */
    private class Harness {
        val store = kotlinx.coroutines.runBlocking { freshDb() }
        val repo = CollabBundleRepository(store, nowMs = { 1 })
        val pipeline = SingleConsumerPipeline()
        val watcher = FakeConnectivityWatcher()
        val transport = FakeTransport()
        val drainer = OutboxDrainer(OutboxRepository(store, nowMs = { 1 }), transport)
        val pullSinceCalls = mutableListOf<Long>()
        val wakePuller = WakePuller(
            pipeline = pipeline,
            pull = { b, since ->
                pullSinceCalls.add(since)
                scriptedPull(b, since)
            },
            sinceSeqOf = { bundleId -> repo.maxSeqId(bundleId) },
        )
        val engine = CollabEngine(
            pipeline = pipeline,
            repo = repo,
            wakePuller = wakePuller,
            drainScheduler = CollabDrainScheduler(drainer, watcher),
        )
    }

    @Test
    fun appliedEventsPersistAndAdvanceWatermark() = runBlocking {
        val h = Harness()
        h.engine.start()
        // 模拟实时流收到 seq 1..5
        for (i in 1..5) {
            h.pipeline.pushWss(
                CollabInboundEvent(bundleId = "b1", seqId = i.toLong(), action = ActionType.ACCEPT, senderHint = "u1", cipher = byteArrayOf(i.toByte()), clientMsgId = "m$i"),
            )
        }
        waitUntil { h.repo.maxSeqId("b1") >= 5 }
        assertEquals(5L, h.repo.maxSeqId("b1"))
        h.engine.dispose()
        Unit
    }

    @Test
    fun offlineEntriesDrainNowClears() = runBlocking {
        val h = Harness()
        val repo = OutboxRepository(h.store, nowMs = { 1 }, newId = { "ob-${System.nanoTime()}" })
        repo.enqueue("b-1", "ACCEPT", buildJsonObject { put("step_id", "s1") })
        repo.enqueue("b-1", "PATCH_TIME", buildJsonObject { put("step_id", "s2") })

        val report = h.engine.drainNow()
        assertEquals(2, report.sent)
        assertEquals(0, repo.pending().size) // 常驻弹出清空
        h.engine.dispose()
        Unit
    }

    @Test
    fun wireCollabSystemWiresThreeTransportsWithoutNetwork() = runBlocking {
        val store = freshDb()
        val watcher = FakeConnectivityWatcher()
        val transport = FakeTransport()
        val relay = MockWebServer()
        relay.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(200).setBody("""{"seq_id":1}""")
        }
        relay.start()
        val config = RelayConfig(baseUrl = relay.url("/").toString().trimEnd('/'))
        val engine = wireCollabSystem(
            config = config,
            db = store,
            client = okhttp3.OkHttpClient(),
            tokenProvider = { "tok" },
            bundleKey = { _, _ -> ByteArray(32) },
            connectivity = watcher,
            transportOverride = transport,
            pullOverride = { b, since -> scriptedPull(b, since) },
            wssOverride = RelayWssClient(URI("ws://localhost:0/ws"), { "x" }),
        )
        engine.start()
        // WSS 实时路推 2 条 + /sync 补拉重叠 → 去重排序落库
        engine.pipeline.pushWss(CollabInboundEvent(bundleId = "b9", seqId = 1, action = ActionType.ACCEPT, source = "wss"))
        engine.pipeline.pushWss(CollabInboundEvent(bundleId = "b9", seqId = 2, action = ActionType.VOTE, source = "wss"))
        val reached = engine.onWake(WakePayload(pushType = "BUNDLE_EVENT_WAKE", bundleId = "b9", latestSeqId = 10))
        assertEquals(10L, reached) // scripted 页 latest_seq=10：游标一页跳满（Dart 同款脚本语义）
        // WSS 两路落库 + 补拉 seq1 与 WSS 重叠被去重（只应用一次）
        waitUntil { engine.repo.maxSeqId("b9") >= 2 }
        assertEquals(2L, engine.repo.maxSeqId("b9"))
        engine.dispose()
        relay.close()
        Unit
    }

    @Test
    fun onWakeSilentWakeDrivesPullFromLocalWatermark() = runBlocking {
        val h = Harness()
        h.engine.start()
        val reached = h.engine.onWake(WakePayload(pushType = "schedule", bundleId = "b1", latestSeqId = 10))
        waitUntil { h.pullSinceCalls.isNotEmpty() }
        // 无本地水位（b1 未落过）→ 从 0 起拉到 10
        assertEquals(0L, h.pullSinceCalls.first())
        assertEquals(10L, reached)
        h.engine.dispose()
        Unit
    }

    @Test
    fun rekeyEventAdvancesHeaderKeyVersionViaFlag() = runBlocking {
        val h = Harness()
        h.repo.ensureBundle("b1", "DIRECTIVE", "u1", listOf("u1"), 9)
        h.engine.start()
        h.pipeline.pushWss(
            CollabInboundEvent(bundleId = "b1", seqId = 1, action = ActionType.REKEY, senderHint = "u1", cipher = byteArrayOf(1), keyVer = 1, clientMsgId = "m1"),
        )
        waitUntil { h.repo.bundleHeader("b1")?.keyVersion == 2 }
        assertEquals(2, h.repo.bundleHeader("b1")!!.keyVersion)
        assertEquals(1L, h.repo.maxSeqId("b1"))
        h.engine.dispose()
        Unit
    }
}

/** 调度器四例（test/collab_drain_scheduler_test.dart 移植）。 */
class CollabDrainSchedulerTest {

    private suspend fun schedulerWithEntries(): Triple<CollabDrainScheduler, FakeConnectivityWatcher, OutboxRepository> {
        val store = freshDb()
        val watcher = FakeConnectivityWatcher()
        val repo = OutboxRepository(store, nowMs = { 1 }, newId = { "ob-${System.nanoTime()}" })
        val drainer = OutboxDrainer(repo, object : OutboxTransport {
            override suspend fun send(entry: app.shiguang.core.model.collab.OutboxEntry): SendOutcome = SendAccepted
        })
        val scheduler = CollabDrainScheduler(drainer, watcher)
        repo.enqueue("b-1", "ACCEPT", buildJsonObject { put("step_id", "s1") })
        repo.enqueue("b-1", "PATCH_TIME", buildJsonObject { put("step_id", "s2") })
        return Triple(scheduler, watcher, repo)
    }

    @Test
    fun startWhileOfflineDoesNotDrain() = runBlocking {
        val (scheduler, watcher, repo) = schedulerWithEntries()
        watcher.emit(false)
        scheduler.start()
        delay(50)
        assertEquals(2, repo.pending().size) // 常离线不主动 drain
        scheduler.dispose()
        Unit
    }

    @Test
    fun offlineToOnlineTransitionAutoDrains() = runBlocking {
        val (scheduler, watcher, repo) = schedulerWithEntries()
        watcher.emit(false)
        scheduler.start()
        watcher.emit(true) // 断→通跃迁
        waitUntil { repo.pending().isEmpty() }
        scheduler.dispose()
        Unit
    }

    @Test
    fun manualTriggerEmitsReport() = runBlocking {
        val (scheduler, watcher, repo) = schedulerWithEntries()
        val reports = mutableListOf<DrainReport>()
        val subscribed = CompletableDeferred<Unit>()
        val sub = launch { scheduler.reports.onSubscription { subscribed.complete(Unit) }.collect { reports.add(it) } }
        subscribed.await()
        val report = scheduler.trigger()
        assertEquals(2, report.sent)
        waitUntil { reports.isNotEmpty() }
        sub.cancel()
        scheduler.dispose()
        Unit
    }

    @Test
    fun twoTransitionsTriggerTwoRounds() = runBlocking {
        val (scheduler, watcher, repo) = schedulerWithEntries()
        watcher.emit(false)
        scheduler.start()
        watcher.emit(true)
        waitUntil { repo.pending().size <= 1 }
        watcher.emit(false)
        watcher.emit(true) // 第二次跃迁（箱内已空也 drain，战报 sent=0）
        waitUntil { repo.pending().isEmpty() }
        scheduler.dispose()
        Unit
    }
}
