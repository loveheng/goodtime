package app.shiguang.core.model.collab.relay

import shiguang.relay.v1.RelayProtocol.ActionType

/** Wake Push 唤醒载荷（零明文路由字段，§10.3-2；lib/collab/wake_puller.dart 等价）。 */
data class WakePayload(
    val pushType: String,
    val bundleId: String,
    val latestSeqId: Long,
) {
    companion object {
        fun fromMap(m: Map<String, Any?>): WakePayload {
            val bundleId = (m["bundle_id"] as? String) ?: ""
            if (bundleId.isEmpty()) {
                throw IllegalArgumentException("Wake Push 载荷缺 bundle_id（§10.3-2）")
            }
            return WakePayload(
                pushType = (m["push_type"] as? String) ?: "",
                bundleId = bundleId,
                latestSeqId = (m["latest_seq_id"] as? Number)?.toLong() ?: 0L,
            )
        }
    }
}

/**
 * 唤醒拉取器：Wake Push → /sync 分页增量 → 单消费管道 HTTP 入口。
 *
 * Wake Push 载荷零明文（§2.2 v0.1.6 定盘）：仅 {push_type, bundle_id, latest_seq_id}
 * 三个无特征路由字段。端侧被系统静默推送唤醒后走本地管道精准拉取，与 WSS 实时
 * 推送汇入同一条串行 FIFO，由管道统一去重排序，弱网恢复瞬间两路同涌也绝不乱序/
 * 重复（§11.1 单消费铁律）。
 *
 * 本类只管「拉 + 喂」，不做解密与落库——那是管道下游（隔离加解密引擎 → FSM 回放
 * → 本地库）的职责；测试注入假 pull，生产注入 [fetchSyncPage] 偏特化。
 */
class WakePuller(
    private val pipeline: SingleConsumerPipeline,
    /** 拉页函数（生产注入 fetchSyncPage 偏特化；测试脚本化）。 */
    private val pull: suspend (bundleId: String, sinceSeq: Long) -> SyncPage,
    /** 本地 since 游标来源（已落库的 last_applied_seq；Phase 4 编排接 collab 表）。
     *  缺省用内部游标——进程内连续唤醒天然增量。 */
    private val sinceSeqOf: (suspend (String) -> Long)? = null,
) {
    private val cursor = mutableMapOf<String, Long>()

    /** 静默唤醒入口：解析零明文载荷 → 精准补拉。返回该包推进到的 seq。 */
    suspend fun onWake(wake: WakePayload): Long =
        pullBundle(wake.bundleId, latestSeqHint = wake.latestSeqId)

    /**
     * 补拉一个包到 [latestSeqHint]（或服务端无更多）为止。
     *
     * 事件全部喂管道 pushHttp——管道按 (bundleId, seqId) 去重 + 升序 flush，这里
     * **不做**任何本地状态假设，重复拉取（WSS 已收 / 游标回退）天然安全。
     */
    suspend fun pullBundle(bundleId: String, latestSeqHint: Long? = null): Long {
        var since = sinceSeqOf?.invoke(bundleId) ?: (cursor[bundleId] ?: 0L)
        while (latestSeqHint == null || since < latestSeqHint) {
            val page = pull(bundleId, since)
            for (e in page.events) {
                pipeline.pushHttp(
                    CollabInboundEvent(
                        bundleId = bundleId,
                        seqId = e.seqId,
                        action = parseAction(e.actionType),
                        senderHint = if (e.senderId.isEmpty()) null else e.senderId,
                        cipher = if (e.cipher.isEmpty()) null else e.cipher,
                        // sync 回包无 key_ver，解密方按 SealedPayload 自描述取版本
                        keyVer = null,
                        topologyRef = if (e.topologyRef.isEmpty()) null else e.topologyRef,
                        serverAtMs = e.serverAtSec * 1000, // 法定时钟（§5.2）
                        source = "http",
                    ),
                )
            }
            // 游标以服务端 latest_seq 为准推进（空页也推进，补齐跳号后的空洞窗口）
            if (page.latestSeq > since) since = page.latestSeq
            if (page.events.isEmpty() || !page.hasMore) break // 无进展/到顶，防死循环
        }
        cursor[bundleId] = since
        return since
    }

    companion object {
        /** javalite values() 含 UNRECOGNIZED，非 Dart 契约名——排除以镜像 Dart 行为。 */
        private val actionByName: Map<String, ActionType> =
            ActionType.values().filter { it != ActionType.UNRECOGNIZED }.associateBy { it.name }
    }

    private fun parseAction(name: String): ActionType {
        return actionByName[name] ?: throw IllegalArgumentException(
            "未知 action_type: $name（契约外枚举，拒绝入管道）",
        )
    }
}
