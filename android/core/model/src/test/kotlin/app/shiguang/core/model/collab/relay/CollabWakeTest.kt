package app.shiguang.core.model.collab.relay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import shiguang.relay.v1.RelayProtocol.ActionType

/**
 * test/collab_wake_test.dart Kotlin 移植（Phase 3.4 唤醒拉取验收）：Wake Push 零明文
 * 载荷唤醒 → /sync 分页增量 → 喂入 SingleConsumerPipeline 的 HTTP 入口，与 WSS 实时
 * 推送共用去重排序——弱网恢复两路同涌不乱序不重复。
 */

/** 复刻 Go relay internal/httpapi/sync.go：增量分页（limit 切页）+ has_more=events 非空，可注入 500 故障。 */
private class MockSyncRelay {
    val server = MockWebServer()
    val bundles = mutableMapOf<String, MutableList<Map<String, Any?>>>()
    var limit = 2 // 强制多页
    var failNext = 0 // >0：接下来 N 次回 500
    var requests = 0

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests++
                if (failNext > 0) {
                    failNext--
                    return MockResponse().setResponseCode(500)
                        .setBody("""{"error_code":"INTERNAL","message":"boom"}""")
                }
                val url = request.requestUrl!!
                val bundle = url.queryParameter("bundle_id") ?: ""
                val since = url.queryParameter("since_seq")?.toLongOrNull() ?: 0L
                val lim = url.queryParameter("limit")?.toIntOrNull() ?: limit
                val all = bundles[bundle] ?: emptyList()
                val page = all.filter { (it["seq_id"] as Long) > since }.take(lim)
                // latest_seq 语义对齐真 sync.go：本页事件内的最大 seq（非全量最大）
                var latest = 0L
                for (e in page) {
                    if ((e["seq_id"] as Long) > latest) latest = e["seq_id"] as Long
                }
                val body = buildJsonObject {
                    put("bundle_id", bundle)
                    put(
                        "events",
                        buildJsonArray {
                            page.forEach { e ->
                                add(
                                    buildJsonObject {
                                        put("seq_id", e["seq_id"] as Long)
                                        put("action_type", e["action_type"] as String)
                                        put("sender_id", e["sender_id"] as String)
                                        put("cipher", e["cipher"] as String)
                                        put("created_at", e["created_at"] as Long)
                                    },
                                )
                            }
                        },
                    )
                    put("latest_seq", latest)
                    put("has_more", page.isNotEmpty())
                }.toString()
                return MockResponse().setResponseCode(200).setBody(body)
            }
        }
        server.start()
    }

    val baseUrl: String get() = server.url("/").toString().trimEnd('/')

    fun addEvents(bundle: String, from: Long, to: Long) {
        val rows = bundles.getOrPut(bundle) { mutableListOf() }
        for (s in from..to) {
            rows.add(
                mapOf(
                    "seq_id" to s,
                    "action_type" to if (s % 2 == 0L) "ACCEPT" else "VOTE",
                    "sender_id" to "peer-$s",
                    "cipher" to java.util.Base64.getEncoder()
                        .encodeToString("pl-$s".toByteArray(Charsets.UTF_8)),
                    "topology_ref" to "",
                    "created_at" to 1700000000L + s,
                ),
            )
        }
    }
}

private suspend fun waitUntil(timeoutMs: Long = 8000, cond: () -> Boolean) {
    val end = System.currentTimeMillis() + timeoutMs
    while (!cond()) {
        if (System.currentTimeMillis() > end) throw IllegalStateException("wake test 超时")
        kotlinx.coroutines.delay(20) // 挂起让路：runBlocking 事件循环得已处理收集协程
    }
}

/** 订阅落地后再返回（SharedFlow 无 replay：订阅完成前的事件会丢——镜像 Dart 同步 listen）。 */
private suspend fun CoroutineScope.collectApplied(
    p: SingleConsumerPipeline,
    sink: MutableList<Long>,
): Job {
    val subscribed = CompletableDeferred<Unit>()
    val job = launch {
        p.applied
            .onSubscription { subscribed.complete(Unit) }
            .collect { e -> sink.add(e.seqId) }
    }
    subscribed.await()
    return job
}

class CollabWakeTest {

    private fun puller(relay: MockSyncRelay, pipeline: SingleConsumerPipeline): WakePuller =
        WakePuller(
            pipeline = pipeline,
            pull = { bundle, since ->
                fetchSyncPage(OkHttpClient(), relay.baseUrl, "tok-1", bundle, since, limit = relay.limit)
            },
        )

    @Test
    fun zeroPlaintextWakePaginatesThroughSinglePipeline() = runBlocking {
        val relay = MockSyncRelay()
        val pipeline = SingleConsumerPipeline()
        relay.addEvents("b1", 1, 5)
        assertTrue(relay.limit == 2) // 5 事件 → 至少 3 页

        val got = mutableListOf<Long>()
        val collector = collectApplied(pipeline, got)

        val pushed = puller(relay, pipeline).onWake(
            WakePayload(pushType = "BUNDLE_EVENT_WAKE", bundleId = "b1", latestSeqId = 5),
        )

        assertEquals(5L, pushed)
        waitUntil { got.size == 5 }
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), got) // 单管道去重排序后连续 flush
        pipeline.close()
        collector.cancel()
        relay.server.close()
        Unit
    }

    @Test
    fun dualSourceOverlapDeduped() = runBlocking {
        val relay = MockSyncRelay()
        val pipeline = SingleConsumerPipeline()
        relay.addEvents("b1", 1, 5)

        val got = mutableListOf<Long>()
        val collector = collectApplied(pipeline, got)

        // WSS 实时路先到 seq 1、2（弱网恢复瞬间典型时序）
        pipeline.pushWss(
            CollabInboundEvent(bundleId = "b1", seqId = 1, action = ActionType.ACCEPT, source = "wss"),
        )
        pipeline.pushWss(
            CollabInboundEvent(bundleId = "b1", seqId = 2, action = ActionType.VOTE, source = "wss"),
        )
        waitUntil { got.size == 2 }

        // 唤醒拉取与 WSS 完全重叠（游标缺失场景）
        puller(relay, pipeline).onWake(
            WakePayload(pushType = "BUNDLE_EVENT_WAKE", bundleId = "b1", latestSeqId = 5),
        )

        waitUntil { got.size == 5 }
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), got) // 重叠的 1、2 被管道去重，绝不重复应用
        pipeline.close()
        collector.cancel()
        relay.server.close()
        Unit
    }

    @Test
    fun secondWakePullsOnlyNewEvents() = runBlocking {
        val relay = MockSyncRelay()
        val pipeline = SingleConsumerPipeline()
        val got = mutableListOf<Long>()
        val collector = collectApplied(pipeline, got)

        relay.addEvents("b1", 1, 5)
        val wake = puller(relay, pipeline)
        val pushed1 = wake.onWake(WakePayload("BUNDLE_EVENT_WAKE", "b1", 5))
        assertEquals(5L, pushed1)
        waitUntil { got.size == 5 }
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), got)

        relay.addEvents("b1", 6, 7)
        val pushed2 = wake.onWake(WakePayload("BUNDLE_EVENT_WAKE", "b1", 7))
        assertEquals(7L, pushed2)
        waitUntil { got.size == 7 }
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 6L, 7L), got) // 全程无重复：二次唤醒只补 6、7
        pipeline.close()
        collector.cancel()
        relay.server.close()
        Unit
    }

    @Test
    fun failedPullDoesNotPollutePipeline() = runBlocking {
        val relay = MockSyncRelay()
        val pipeline = SingleConsumerPipeline()
        relay.addEvents("b1", 1, 3)
        relay.failNext = 1

        val got = mutableListOf<Long>()
        val collector = collectApplied(pipeline, got)

        assertFailsWith<RelayAuthException> {
            puller(relay, pipeline).onWake(WakePayload("BUNDLE_EVENT_WAKE", "b1", 3))
        }
        kotlinx.coroutines.delay(50)
        assertTrue(got.isEmpty()) // 故障轮零污染

        val pushed = puller(relay, pipeline).onWake(WakePayload("BUNDLE_EVENT_WAKE", "b1", 3))
        assertEquals(3L, pushed)
        waitUntil { got.size == 3 }
        assertEquals(listOf(1L, 2L, 3L), got)
        pipeline.close()
        collector.cancel()
        relay.server.close()
        Unit
    }

    @Test
    fun wakePayloadMissingBundleIdRejected() {
        assertFailsWith<IllegalArgumentException> {
            WakePayload.fromMap(mapOf("push_type" to "BUNDLE_EVENT_WAKE"))
        }
    }
}
