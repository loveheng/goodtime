package app.shiguang.core.model.collab.relay

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.util.TreeMap
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import shiguang.relay.v1.RelayProtocol.Envelope
import shiguang.relay.v1.RelayProtocol.ServerFrame

/** 归一化入管道的统一协同事件载荷（lib/collab/pipeline.dart Kotlin 等价）。 */
data class CollabInboundEvent(
    val bundleId: String,
    val seqId: Long,
    val action: shiguang.relay.v1.RelayProtocol.ActionType,
    val senderHint: String? = null,
    val cipher: ByteArray? = null,
    val keyVer: Int? = null,
    val clientMsgId: String? = null,
    val topologyRef: String? = null,
    /** 法定时钟（§5.2）：服务端落盘时间，毫秒；WSS 不可得时为 0。 */
    val serverAtMs: Long = 0,
    /** 'wss' | 'http' —— 仅可观测，不参与去重/排序。 */
    val source: String = "wss",
) {
    companion object {
        /** 从 WSS 的 ServerFrame（含 Envelope）翻译。 */
        fun fromServerFrame(
            frame: ServerFrame,
            source: String = "wss",
            serverAtMs: Long = 0,
        ): CollabInboundEvent {
            val env = frame.schedule
            return CollabInboundEvent(
                bundleId = env.bundleId,
                seqId = env.seqId,
                action = env.action,
                senderHint = if (env.senderHint.isEmpty()) null else env.senderHint,
                cipher = if (env.cipher.isEmpty) null else env.cipher.toByteArray(),
                // javalite 无 implicit-presence has 法——「非零=在场」镜像 Dart hasKeyVer
                // 语义（拍板②同家族：key_ver 实际恒 ≥1，0=未上 wire）。
                keyVer = if (env.keyVer != 0) env.keyVer else null,
                clientMsgId = if (env.clientMsgId.isEmpty()) null else env.clientMsgId,
                topologyRef = if (env.topologyRef.isEmpty()) null else env.topologyRef,
                serverAtMs = serverAtMs,
                source = source,
            )
        }
    }
}

/**
 * 单消费管道（Single Consumer Pipeline）：WSS 与 HTTP /sync 两路统一串行入库
 * （协同设计 §11.1 / collab-implementation §2.6）。
 *
 * - 两路事件都汇入**同一条** Channel（FIFO 无界缓冲），由**单一消费者**协程逐条
 *   取出——同一时刻只处理一条，**从结构上消灭并发重放竞态**（Channel/协程仅作
 *   实现载体，FIFO 语义是契约，禁止改有序并发）。
 * - 取出后按 (bundleId, seqId) **去重**；并按 seq 升序**排序**驱动（同包内缺号则
 *   暂存于有序缓冲，待补拉补齐后连续 flush），保证弱网恢复瞬时两路同时涌入也
 *   不乱序/不重复。
 */
class SingleConsumerPipeline {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val inbox = Channel<CollabInboundEvent>(Channel.UNLIMITED)
    private val appliedFlow = MutableSharedFlow<CollabInboundEvent>(
        replay = 0,
        extraBufferCapacity = 1024,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private val seen = mutableSetOf<String>() // 'bundle:seq' 已应用去重
    private val lastApplied = mutableMapOf<String, Long>() // bundleId → 已应用最大 seq
    private val pending = mutableMapOf<String, TreeMap<Long, CollabInboundEvent>>()

    private var active = 0
    private var peak = 0

    private val consumerJob = scope.launch {
        for (e in inbox) { // 关闭且排干后退出（镜像 Dart StreamQueue StateError break）
            active++
            if (peak < active) peak = active
            assert(active == 1) { "单消费管道出现并发消费（active=$active）" }
            dispatch(e)
            active--
        }
    }

    /** 已正确去重、排序并「应用」（落库）的事件流——上层 UI/存储订阅此流。 */
    val applied: SharedFlow<CollabInboundEvent> get() = appliedFlow

    /** 并发峰值（诊断用；单一消费者应恒为 1）。 */
    val peakConcurrent: Int get() = peak

    /** WSS 实时推送入口。 */
    fun pushWss(e: CollabInboundEvent) {
        inbox.trySend(e)
    }

    /** HTTP /sync 补拉入口。 */
    fun pushHttp(e: CollabInboundEvent) {
        inbox.trySend(e)
    }

    /** 便捷：直接灌一批（来自 /sync 的分页结果）。 */
    fun pushSyncBatch(batch: List<CollabInboundEvent>) {
        batch.forEach { inbox.trySend(it) }
    }

    /** 便捷：把 WssClient 的入站帧串入 WSS 入口（2.7 编排用）。 */
    fun attachWss(client: RelayWssClient) = scope.launch {
        client.frames.collect { f -> inbox.trySend(CollabInboundEvent.fromServerFrame(f, source = "wss")) }
    }

    private fun dispatch(e: CollabInboundEvent) {
        val key = "${e.bundleId}:${e.seqId}"
        if (seen.contains(key)) return // 去重（含 replay）
        val last = lastApplied[e.bundleId] ?: 0L
        if (e.seqId <= last) return // 旧/重复，丢弃
        pending
            .getOrPut(e.bundleId) { TreeMap() }
            .getOrPut(e.seqId) { e }
        // 从期望的下一个 seq 起，连续 flush 已就位的事件（缺号则停在缺口等补拉）
        val pend = pending[e.bundleId]!!
        var next = (lastApplied[e.bundleId] ?: 0L) + 1
        while (pend.isNotEmpty() && pend.firstKey() == next) {
            val seq = pend.firstKey()
            val ev = pend.remove(seq)!!
            seen.add("${ev.bundleId}:$seq")
            lastApplied[ev.bundleId] = seq
            appliedFlow.tryEmit(ev)
            next++
        }
    }

    /** 关闭入站并等待存量排干（镜像 Dart 控制器 close：已入队事件仍会被消费完）。 */
    suspend fun close() {
        inbox.close()
        consumerJob.join()
    }
}
