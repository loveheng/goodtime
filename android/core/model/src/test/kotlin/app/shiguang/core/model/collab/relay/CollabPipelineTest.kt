package app.shiguang.core.model.collab.relay

import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import shiguang.relay.v1.RelayProtocol.Envelope
import shiguang.relay.v1.RelayProtocol.ServerFrame

/** test/collab_pipeline_test.dart Kotlin 移植（单消费管道：去重/升序/缺号缓冲/attachWss）。 */

private fun ev(bundle: String, seq: Long, source: String) = CollabInboundEvent(
    bundleId = bundle,
    seqId = seq,
    action = shiguang.relay.v1.RelayProtocol.ActionType.PING,
    source = source,
)

private suspend fun waitUntil(timeoutMs: Long = 8000, cond: () -> Boolean) {
    val end = System.currentTimeMillis() + timeoutMs
    while (!cond()) {
        if (System.currentTimeMillis() > end) {
            throw IllegalStateException("条件未在 ${timeoutMs}ms 内满足")
        }
        kotlinx.coroutines.delay(10) // 挂起让路：runBlocking 事件循环得已处理收集协程
    }
}

private fun frame(bundle: String, seq: Long, cipher: List<Int>): ServerFrame =
    ServerFrame.newBuilder()
        .setSeqId(seq)
        .setSchedule(
            Envelope.newBuilder()
                .setAction(shiguang.relay.v1.RelayProtocol.ActionType.PING)
                .setBundleId(bundle)
                .setSeqId(seq)
                .setSenderHint("relay")
                .setCipher(com.google.protobuf.ByteString.copyFrom(cipher.map { it.toByte() }.toByteArray())),
        )
        .build()

/** 仅用于测试：frames 流直接由给定序列提供（不建真连接；镜像 Dart _FakeWss）。 */
private class FakeWss(frames: List<ServerFrame>) : RelayWssClient(
    wsUri = URI("ws://localhost:0/ws"),
    getToken = { "x" },
    keepaliveIntervalMs = 3_600_000,
) {
    override val frames: SharedFlow<ServerFrame> = kotlinx.coroutines.flow.MutableSharedFlow<ServerFrame>().also { f -> kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch { frames.asFlow().collect { f.emit(it) } } }
}

/** 订阅落地后再返回（SharedFlow 无 replay：订阅完成前的事件会丢——镜像 Dart 同步 listen）。 */
private suspend fun CoroutineScope.collectApplied(
    p: SingleConsumerPipeline,
    sink: MutableList<CollabInboundEvent>,
): Job {
    val subscribed = CompletableDeferred<Unit>()
    val job = launch {
        p.applied
            .onSubscription { subscribed.complete(Unit) }
            .collect { sink.add(it) }
    }
    subscribed.await()
    return job
}

class CollabPipelineTest {

    @Test
    fun twoConcurrentSourcesDedupeAndSortStrictly() = runBlocking {
        val p = SingleConsumerPipeline()
        val applied = mutableListOf<CollabInboundEvent>()
        val collector = collectApplied(p, applied)

        // WSS 推 1..60，HTTP /sync 补拉 41..100（重叠 41..60 必须被去重）
        val wss = launch(Dispatchers.Default) {
            for (i in 1..60) {
                p.pushWss(ev("b1", i.toLong(), "wss"))
                delay(1)
            }
        }
        val http = launch(Dispatchers.Default) {
            for (i in 41..100) {
                p.pushHttp(ev("b1", i.toLong(), "http"))
                delay(1)
            }
        }
        wss.join()
        http.join()
        waitUntil { applied.size >= 100 }

        // 单一消费者：峰值并发恒为 1
        assertEquals(1, p.peakConcurrent)
        // 去重后恰 100 条，严格升序、无缺口、无重复
        assertEquals(100, applied.size)
        val seqs = applied.map { it.seqId }
        assertEquals((1L..100L).toList(), seqs)
        // 来源混合（证明两路都汇入同一条管道）
        assertTrue(applied.any { it.source == "wss" })
        assertTrue(applied.any { it.source == "http" })
        p.close()
        collector.cancel()
        Unit
    }

    @Test
    fun distinctBundlesOrderIndependently() = runBlocking {
        val p = SingleConsumerPipeline()
        val applied = mutableListOf<CollabInboundEvent>()
        val collector = collectApplied(p, applied)

        val wss = launch(Dispatchers.Default) {
            for (i in 1..30) {
                p.pushWss(ev("b1", i.toLong(), "wss"))
                delay(1)
            }
        }
        val http = launch(Dispatchers.Default) {
            for (i in 1..30) {
                p.pushHttp(ev("b2", i.toLong(), "http"))
                delay(1)
            }
        }
        wss.join()
        http.join()
        waitUntil { applied.size >= 60 }

        assertEquals(1, p.peakConcurrent)
        val b1 = applied.filter { it.bundleId == "b1" }.map { it.seqId }
        val b2 = applied.filter { it.bundleId == "b2" }.map { it.seqId }
        assertEquals((1L..30L).toList(), b1)
        assertEquals((1L..30L).toList(), b2)
        p.close()
        collector.cancel()
        Unit
    }

    @Test
    fun replayedEventsDedupedAppliedOnce() = runBlocking {
        val p = SingleConsumerPipeline()
        val applied = mutableListOf<CollabInboundEvent>()
        val collector = collectApplied(p, applied)

        for (i in 1..10) {
            p.pushWss(ev("b1", i.toLong(), "wss"))
        }
        // 重放其中 5、7（模拟弱网重推）
        p.pushWss(ev("b1", 5, "wss"))
        p.pushHttp(ev("b1", 7, "http"))

        waitUntil { applied.size >= 10 }
        assertEquals(10, applied.size)
        assertEquals((1L..10L).toList(), applied.map { it.seqId })
        p.close()
        collector.cancel()
        Unit
    }

    @Test
    fun attachWssTranslatesServerFrames() = runBlocking {
        val p = SingleConsumerPipeline()
        val applied = mutableListOf<CollabInboundEvent>()
        val collector = collectApplied(p, applied)

        // 模拟 WssClient 帧（无需真连接）：连续 seq 1..3 才能触发 contiguous flush
        val frames = listOf(
            frame("b9", 1, listOf(1, 2, 3)),
            frame("b9", 2, listOf(4, 5, 6)),
            frame("b9", 3, listOf(7, 8, 9)),
        )
        p.attachWss(FakeWss(frames))
        waitUntil { applied.size >= 3 }

        assertEquals(listOf(1L, 2L, 3L), applied.map { it.seqId })
        assertEquals("b9", applied.first().bundleId)
        assertEquals("wss", applied.first().source)
        assertTrue(applied.first().cipher!!.contentEquals(byteArrayOf(1, 2, 3)))
        p.close()
        collector.cancel()
        Unit
    }
}
