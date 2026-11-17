package app.shiguang.core.model.collab

import app.shiguang.core.model.SqlRow
import app.shiguang.core.model.SqlStore
import app.shiguang.core.model.collab.relay.RelayAuthException
import app.shiguang.core.model.collab.relay.RelayPeersException
import java.io.IOException
import java.security.SecureRandom
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * 本地 Outbox（lib/collab/outbox.dart Kotlin 等价，协同设计 §11.1「离线发件箱」，
 * collab-implementation §3.3）。
 *
 * 弱网不丢操作的三段式：
 * 1. **离线操作立即落库**——local_outbox_events 写入 PENDING_SEND，先于任何 UI
 *    反馈；操作不存内存、不直写正式状态（被 409 打回即污染本地），杀进程零丢失；
 * 2. **联网按序弹出**——按 (created_at, id) 全序逐条发往服务端。保序是硬约束：
 *    服务端按到达序行锁取号（§5.1），跳过前序乱序发出必被 409 STALE_REVISION
 *    打回，故一旦遇网络失败立即停止本轮（后续条目因果依赖前序）；
 * 3. **三种出口**：
 *    - **Accepted**：确认即物理删除（「收到确认后删 Outbox 项」，正式流水由
 *      响应/同步通道入本地 WAL）；
 *    - **Rejected**（服务端确定性打回：409 STALE_REVISION / BUNDLE_CONFIRMED_
 *      IMMUTABLE / 403 ACL 等）：终态——清该条并把冲突上抛 [DrainReport.conflicts]，
 *      UI 据此弹「内容已更新，请重新确认」引导；**不阻塞后续**（被打回的是该
 *      操作与本地状态的矛盾，独立操作仍有效）；
 *    - **Offline**（网络不可达/超时）：**非终态**——条目保留、attempts+1、保序
 *      停止，弱网恢复后重 drain。
 */

/** 发件箱条目（local_outbox_events 行投影）。 */
data class OutboxEntry(
    /** 幂等键：与服务端 client_msg_id 同源——弱网重发同 id，服务端幂等中间件
     *  原样回放缓存响应（Phase 1.1），重试天然安全。 */
    val id: String,
    val bundleId: String,
    /** ActionType 枚举名（明文，服务端盲仲裁所需）。 */
    val actionType: String,
    /** 待发明文（发送时经 sealing 加密上链）。 */
    val payload: JsonObject,
    val keyVer: Int,
    /** 离线重试计数（≥3 可打「需人工关注」标，§8 口径复用）。 */
    val attempts: Int,
    /** 毫秒；弹出序 = (created_at, id) 全序。 */
    val createdAt: Long,
) {
    fun toRow(): JsonObject = buildJsonObject {
        put("id", id)
        put("bundle_id", bundleId)
        put("action_type", actionType)
        put("payload", payload.toString())
        put("key_ver", keyVer.toLong())
        put("status", "PENDING_SEND")
        put("attempts", attempts.toLong())
        put("created_at", createdAt)
        put("sent_at", JsonNull)
    }

    companion object {
        fun fromRow(m: SqlRow): OutboxEntry = OutboxEntry(
            id = m["id"] as String,
            bundleId = m["bundle_id"] as String,
            actionType = m["action_type"] as String,
            payload = Json.parseToJsonElement(m["payload"] as String).jsonObject,
            keyVer = ((m["key_ver"] as Long?) ?: 1L).toInt(),
            attempts = ((m["attempts"] as Long?) ?: 0L).toInt(),
            createdAt = m["created_at"] as Long,
        )
    }
}

// ---- 弹出结局 -----------------------------------------------------------------

/** 单条弹出结局：服务端确认 / 确定性打回 / 没摸到网。 */
sealed interface SendOutcome

/** 服务端已确认（含幂等回放：同 client_msg_id 重发原样 200）。 */
data object SendAccepted : SendOutcome

/** 服务端确定性打回——终态，清箱 + 引导，绝不重试。 */
data class SendRejected(val errorCode: String) : SendOutcome

/** 网络不可达/超时——非终态，保留重试。 */
data class SendOffline(val cause: Any? = null) : SendOutcome

/**
 * **服务端已建包**（PROPOSE 受理：201 + 服务端发号）。
 *
 * 与 [SendAccepted] 的区别：纯追加动作已经有 bundle_id，无需回执；而本地发起的
 * bundle 在服务端发号前只有**临时本地 id**——窗口期内（入队到弹出之间）后续随
 * 手入队的操作仍挂在临时 id 上，故必须把服务端返回的 bundle_id/seq_id 带回
 * 本地做「认祖归宗」（见 propose.dart 的 PendingProposalReconciler）。
 */
data class SendProposed(
    val bundleId: String,
    val seqId: Long = 1,
) : SendOutcome

/**
 * 传输口。真实实现接 POST /v1/schedule/action（Bearer + Envelope：payload 经
 * sealPayload 加密、512B 填充、key_ver 标注——3.4 装配时接线）；弱网单测注入
 * 脚本化假传输。
 */
interface OutboxTransport {
    suspend fun send(entry: OutboxEntry): SendOutcome
}

/** 409 清箱引导数据源（UI：条目级「内容已更新，请重新确认」提示）。 */
data class OutboxConflict(
    val id: String,
    val bundleId: String,
    val actionType: String,
    val errorCode: String,
)

/** 本轮弹出里「服务端已发号」的本地发起包回执。 */
data class CreatedBundle(
    /** 幂等键（OutboxEntry.id，与 client_msg_id 同源）。 */
    val entryId: String,
    /** 发号前的临时本地 id。 */
    val localBundleId: String,
    /** 服务端发号。 */
    val bundleId: String,
    /** 首条 PROPOSE 事件的 seq（恒为 1）。 */
    val seqId: Long,
)

/** 一轮弹出战报：UI 据此渲染「N 条已同步 / M 条冲突待处置 / 仍有积压」。 */
data class DrainReport(
    val sent: Int,
    val conflicts: List<OutboxConflict>,
    /** true = 因离线保序停止，箱内仍有积压。 */
    val stoppedByNetwork: Boolean,
    /** 本轮里被服务端正式发号的本地发起包（认祖归宗数据源）。 */
    val created: List<CreatedBundle> = emptyList(),
)

class OutboxDrainer(
    private val repo: OutboxRepository,
    private val transport: OutboxTransport,
    /**
     * 本地发起包的认祖归宗钩子：临时本地 id → 服务端 bundle_id。
     *
     * 钩子在条目出箱**之后**、带着完整 [OutboxEntry] 调用——此时尚有原始 payload
     * 可查（例如成员清单与拓扑），出箱后再补就晚了。服务端重放 client_msg_id 会
     * 再次回到这里，故认祖归宗必须幂等（见 propose.dart）。
     */
    private val onBundleCreated: (suspend (OutboxEntry, CreatedBundle) -> Unit)? = null,
) {
    /**
     * 按 (created_at, id) 全序逐条弹出。传输层裸异常（IOException/超时…）一律
     * 归一化为 Offline——坏网络绝不炸弹出循环。
     *
     * **每发一条都重新 [OutboxRepository.pending] 拉首条**，而不是开局缓存整批：
     * 本地发起包的认祖归宗会 UPDATE local_outbox_events SET bundle_id = 服务端号，
     * 缓存下来的后续条目仍带着临时本地 id，照发必 404（服务端只认自己发的号）。
     * 多一次查询换来「改绑立刻生效」，全序由 pending() 的 ORDER BY 保证。
     */
    suspend fun drain(): DrainReport {
        val conflicts = mutableListOf<OutboxConflict>()
        val created = mutableListOf<CreatedBundle>()
        var sent = 0
        while (true) {
            val pending = repo.pending()
            if (pending.isEmpty()) break
            val entry = pending.first()
            val outcome: SendOutcome = try {
                transport.send(entry)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                repo.markAttempt(entry.id)
                return DrainReport(sent = sent, conflicts = conflicts, stoppedByNetwork = true)
            }
            when (outcome) {
                is SendProposed -> {
                    sent++
                    val ack = CreatedBundle(
                        entryId = entry.id,
                        localBundleId = entry.bundleId,
                        bundleId = outcome.bundleId,
                        seqId = outcome.seqId,
                    )
                    created.add(ack)
                    // 认祖归宗失败绝不炸弹出循环：幂等键由服务端兜底，下次重连可再补。
                    if (onBundleCreated != null) {
                        try {
                            onBundleCreated.invoke(entry, ack)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                        }
                    }
                    repo.remove(entry.id) // 确认即删（§11.1）
                }
                is SendAccepted -> {
                    repo.remove(entry.id) // 确认即删（§11.1）
                    sent++
                }
                is SendRejected -> {
                    repo.remove(entry.id) // 409 清箱（§11.1）
                    conflicts.add(
                        OutboxConflict(
                            id = entry.id,
                            bundleId = entry.bundleId,
                            actionType = entry.actionType,
                            errorCode = outcome.errorCode,
                        ),
                    )
                }
                is SendOffline -> {
                    repo.markAttempt(entry.id) // 非终态保留
                    return DrainReport(sent = sent, conflicts = conflicts, stoppedByNetwork = true)
                }
            }
        }
        return DrainReport(
            sent = sent,
            conflicts = conflicts,
            stoppedByNetwork = false,
            created = created,
        )
    }
}

/** 幂等键缺省生成（与 client_msg_id 同源形态：ob-<36 进制毫秒>-<8 hex>）。 */
private fun defaultOutboxId(): String {
    val rand = (0 until 8).map { SecureRandom().nextInt(16) }.joinToString("") { it.toString(16) }
    return "ob-${System.currentTimeMillis().toString(36)}-$rand"
}

/** local_outbox_events 仓储（SqlStore 句柄注入；测试 = sqlite-jdbc + ShiguangSchema）。 */
class OutboxRepository(
    private val db: SqlStore,
    /** 时钟注入（测试钉时间控全序）。 */
    private val nowMs: () -> Long = System::currentTimeMillis,
    /** 与 client_msg_id 同源的幂等键生成。 */
    private val newId: () -> String = ::defaultOutboxId,
) {
    /** 入队即落盘（§11.1：离线操作立即写入，先于任何 UI 反馈）。 */
    suspend fun enqueue(
        bundleId: String,
        actionType: String,
        payload: JsonObject,
        keyVer: Int = 1,
    ): OutboxEntry {
        val entry = OutboxEntry(
            id = newId(),
            bundleId = bundleId,
            actionType = actionType,
            payload = payload,
            keyVer = keyVer,
            attempts = 0,
            createdAt = nowMs(),
        )
        db.insert("local_outbox_events", entry.toRow())
        return entry
    }

    /** 待发条目：(created_at, id) 升序全序——同毫秒兜底按 id 排，弹出序稳定。 */
    suspend fun pending(): List<OutboxEntry> = db.query(
        "local_outbox_events",
        where = "status = 'PENDING_SEND'",
        orderBy = "created_at, id",
    ).map { OutboxEntry.fromRow(it) }

    /** 确认/清箱：物理删除，无墓碑——幂等由服务端 client_msg_id 兜底。 */
    suspend fun remove(id: String) {
        db.delete("local_outbox_events", where = "id = ?", args = listOf(id))
    }

    /**
     * **认祖归宗**：本地发起包的临时 id → 服务端发号，把尚未弹出的随手操作
     * （窗口期内入队的后续动作）整体改绑到真实 bundle_id。
     *
     * 为什么必须改绑：/v1/schedule/action 的 bundle_id 由服务端行锁定位，临时
     * 本地 id 在服务端不存在——不改绑则这批操作弹出时恒 404。幂等：重复改绑零行。
     */
    suspend fun rebindBundle(fromBundleId: String, toBundleId: String): Int {
        if (fromBundleId == toBundleId) return 0
        return db.rawUpdate(
            "UPDATE local_outbox_events SET bundle_id = ? WHERE bundle_id = ?",
            listOf(toBundleId, fromBundleId),
        )
    }

    /** 离线保留：attempts+1，条目原样待重试。 */
    suspend fun markAttempt(id: String) {
        db.rawUpdate(
            "UPDATE local_outbox_events SET attempts = attempts + 1 WHERE id = ?",
            listOf(id),
        )
    }
}

/**
 * 弹出战报 → 是否「已发出」：本轮没有因离线保序停止、且确实有包被服务端发号
 * （behavior/compose_bundle.md §A 回执判定；Dart composeSentOut 对位，T4.5 补课）。
 */
fun composeSentOut(report: DrainReport?): Boolean =
    report != null && !report.stoppedByNetwork && report.created.isNotEmpty()

/**
 * 装箱前失败的原因白话翻译（ui-spec §0.4「发起失败原因」；真机 release 无日志可捞，
 * 屏面上那句话就是唯一的现场证据——异常摘要必须带上）。
 */
fun composeFailureText(e: Throwable): String = when (e) {
    is RelayAuthException -> relayFailureText(e.statusCode)
    is RelayPeersException -> relayFailureText(e.statusCode)
    is IOException -> "没发出去：暂时连不上中继，联网后再试一次"
    else -> "没发出去：${e::class.simpleName} $e"
}

/** relay 状态码失败文案（§A 逐字）。 */
private fun relayFailureText(code: Int): String = when (code) {
    401 -> "没发出去：登录态已过期，请到「设置 → 协同账号」重新登录"
    404 -> "没发出去：取不到对方的通信公钥，让对方重新登录一次再约"
    429 -> "没发出去：操作太频繁，稍后再试"
    else -> "没发出去：中继打回了这次邀请（$code），稍后再试"
}
