package app.shiguang.core.model.collab.relay

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/** 单条增量事件（服务端 team_schedule_events 行投影，sync_api.dart 等价）。 */
data class SyncEvent(
    val seqId: Long,
    /** ActionType 枚举名（明文骨架，服务端盲仲裁所需）。 */
    val actionType: String,
    val senderId: String,
    /** base64 解码后原始字节（SealedPayload JSON utf8）。 */
    val cipher: ByteArray,
    val topologyRef: String,
    /** 服务端落盘时间（§5.2 法定时钟，秒）。 */
    val serverAtSec: Long,
) {
    override fun equals(other: Any?): Boolean =
        other is SyncEvent && seqId == other.seqId && actionType == other.actionType &&
            senderId == other.senderId && cipher.contentEquals(other.cipher) &&
            topologyRef == other.topologyRef && serverAtSec == other.serverAtSec

    override fun hashCode(): Int = seqId.hashCode() * 31 + actionType.hashCode()
}

/** 一页拉取结果。 */
data class SyncPage(
    val bundleId: String,
    val events: List<SyncEvent>,
    /** 服务端该包当前最大 seq。 */
    val latestSeq: Long,
    val hasMore: Boolean,
)

private fun jStr(o: JsonObject, k: String): String? = o[k]?.let {
    (it as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { p -> p.isString }?.contentOrNull
}

/**
 * 拉一页增量（GET /v1/schedule/sync，对齐 Go relay internal/httpapi/sync.go，冻结
 * tag v0.1.6-contract）。增量补拉是换机冷启动 / Wake Push 唤醒 / WSS 断线恢复三条
 * 链路的公共取数口：服务端按 (bundle_id, since_seq) 流式吐事件（单页 ≤500，
 * has_more 分页），事件 cipher 是 base64 的自描述 SealedPayload JSON 字节（与上行
 * 同构）。契约注记：sync 回包**不含 key_ver**——解密方按本地 KeyRing 从
 * SealedPayload 自描述字段取 key_ver 选钥（§5.3.1 密钥环回放），服务端零参与。
 *
 * 非 200 抛 [RelayAuthException]（由调用方归一化 Offline/重试）。
 */
suspend fun fetchSyncPage(
    client: OkHttpClient,
    baseUrl: String,
    accessToken: String,
    bundleId: String,
    sinceSeq: Long,
    limit: Int? = null,
): SyncPage = withContext(Dispatchers.IO) {
    val url = buildString {
        append(baseUrl.trimEnd('/'))
        append("/v1/schedule/sync?bundle_id=")
        append(java.net.URLEncoder.encode(bundleId, "UTF-8"))
        append("&since_seq=")
        append(sinceSeq)
        if (limit != null) {
            append("&limit=")
            append(limit)
        }
    }
    val request = Request.Builder()
        .url(url)
        .header("Authorization", "Bearer $accessToken")
        .get()
        .build()
    val resp = client.newCall(request).execute()
    resp.use { r ->
        val bodyStr = r.body?.string() ?: ""
        if (r.code != 200) {
            throw RelayAuthException(r.code, bodyStr)
        }
        val j = Json.parseToJsonElement(bodyStr).jsonObject
        val rawEvents = (j["events"] as? JsonArray) ?: JsonArray(emptyList())
        SyncPage(
            bundleId = jStr(j, "bundle_id") ?: bundleId,
            events = rawEvents.map { el ->
                val e = el.jsonObject
                SyncEvent(
                    seqId = e["seq_id"]!!.jsonPrimitive.long,
                    actionType = e["action_type"]!!.jsonPrimitive.content,
                    senderId = jStr(e, "sender_id") ?: "",
                    cipher = java.util.Base64.getDecoder()
                        .decode((e["cipher"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""),
                    topologyRef = jStr(e, "topology_ref") ?: "",
                    serverAtSec = e["created_at"]?.jsonPrimitive?.longOrNull ?: 0L,
                )
            },
            latestSeq = j["latest_seq"]?.jsonPrimitive?.longOrNull ?: sinceSeq,
            hasMore = j["has_more"]?.jsonPrimitive?.booleanOrNull ?: false,
        )
    }
}
