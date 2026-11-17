package app.shiguang.core.model.collab.relay

import app.shiguang.core.model.collab.OutboxEntry
import app.shiguang.core.model.collab.OutboxTransport
import app.shiguang.core.model.collab.SendAccepted
import app.shiguang.core.model.collab.SendOffline
import app.shiguang.core.model.collab.SendOutcome
import app.shiguang.core.model.collab.SendProposed
import app.shiguang.core.model.collab.SendRejected
import app.shiguang.core.model.collab.SealedPayload
import app.shiguang.core.model.collab.sealPayload
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** 载荷字段取用（Dart 动态语义镜像，与 CollabPropose.kt 同口径）。 */
private fun jsonStrOrNull(e: JsonElement?): String? = when (e) {
    null, is JsonNull -> null
    is JsonPrimitive -> if (e.isString) e.content else throw IllegalArgumentException("须为字符串: $e")
    else -> throw IllegalArgumentException("须为字符串: $e")
}

private fun jsonLongOrNull(e: JsonElement?): Long? = when (e) {
    null, is JsonNull -> null
    is JsonPrimitive -> e.longOrNull ?: throw IllegalArgumentException("须为整型: $e")
    else -> throw IllegalArgumentException("须为整型: $e")
}

/**
 * OutboxTransport 真实实现（lib/collab/relay/action_api.dart Kotlin 等价，
 * collab-implementation §3.4 装配 + 发起补齐；OkHttp 承载——D10 拍板 2026-11-14）。
 *
 * 对齐 Go relay internal/httpapi/action.go 冻结契约（tag v0.1.6-contract）：
 * - POST /v1/schedule/action，Bearer access token + X-Client-Msg-Id 幂等头；
 *   幂等键与 OutboxEntry.id 同源——弱网重发同 id，服务端 1.1 幂等中间件原样回放
 *   缓存响应，重试天然安全；
 * - body {bundle_id, action, cipher}：payload 明文经 sealPayload 加密（512B 填充 +
 *   AES-256-GCM + key_ver 标注），既有约定 **cipher = base64(SealedPayload JSON utf8)**，
 *   服务端仅盲存字节零明文；
 * - **「内容加密、拓扑明文」（§4.2 半盲骨架投影）**：从 payload 抬出服务端仲裁
 *   所需明文元数据：proposal_type / participant_uids / expires_at / topology /
 *   quorum / member_key_envelopes，及结构变更动作的 expected_revision、
 *   add_member_uids——缺了它们服务端认不出谁参与、取不到门限与初态；
 * - **本地发起（PROPOSE 建包）**：bundle_id 由服务端发号，请求体 bundle_id 留空
 *   （action.go 建包分支判据），201 回执的 bundle_id/seq_id 经 [SendProposed]
 *   带回做认祖归宗。
 *
 * 结局三分类映射：
 *   - 2xx → SendAccepted（含幂等回放 200）；PROPOSE 走 201 发号 → SendProposed；
 *   - 4xx 确定性打回（400 配额族/非法请求、403 ACL、404 无包、409 STALE_REVISION /
 *     BUNDLE_CONFIRMED_IMMUTABLE）→ SendRejected(error_code)，终态清箱绝不重试；
 *   - 5xx / 401 / 传输层异常（IOException）→ SendOffline 非终态保留重试。
 *     401 归 Offline：token 过期是凭证问题不是操作与本地状态的矛盾，清箱会丢操作
 *     ——留给上层刷新 token 后重 drain（attempts 自然累加）。
 */
class HttpOutboxTransport(
    private val client: OkHttpClient,
    private val baseUrl: String,
    private val accessToken: suspend () -> String,
    /** 从本地 KeyRing 按 (bundleId, keyVer) 取 K_bundle——取不到钥（离线收到 REKEY
     *  信封未补拉等）抛出的异常由 OutboxDrainer 归一化为 Offline 保留重试。 */
    private val bundleKey: suspend (bundleId: String, keyVer: Int) -> ByteArray,
) : OutboxTransport {

    override suspend fun send(entry: OutboxEntry): SendOutcome {
        val key = bundleKey(entry.bundleId, entry.keyVer)
        val sealed = sealPayload(
            key,
            entry.payload.toString().toByteArray(Charsets.UTF_8),
            entry.keyVer,
        )

        // 本地发起：bundle_id 空 + 抬出服务端仲裁所需的明文元数据（§4.2 拓扑明文）。
        val create = isBundleCreation(entry)
        val body = buildJsonObject {
            put("bundle_id", if (create) "" else entry.bundleId)
            put("action", entry.actionType)
            put("cipher", app.shiguang.core.model.collab.b64Encode(
                sealed.encode().toByteArray(Charsets.UTF_8)))
            if (create) {
                val p = entry.payload
                put("proposal_type", jsonStrOrNull(p["proposal_type"]) ?: "CONSENSUS")
                putJsonArray("participant_uids") {
                    val raw = p["participants"]
                    val list = when (raw) {
                        null, is JsonNull -> emptyList()
                        is JsonArray -> raw.map { it.jsonPrimitiveStr() }
                        else -> throw IllegalArgumentException("participants 须为数组")
                    }
                    list.forEach { add(it) }
                }
                val exp = jsonLongOrNull(p["expires_at_ms"])
                if (exp != null) put("expires_at", exp / 1000) // 服务端收 Unix 秒
                val topo = p["topology"]
                if (topo != null) put("topology", topo)
                val quorum = jsonLongOrNull(p["quorum"])
                if (quorum != null) put("quorum", quorum)
                putJsonArray("member_key_envelopes") {
                    val raw = p["envelopes"]
                    val list = when (raw) {
                        null, is JsonNull -> emptyList()
                        is JsonArray -> raw.toList()
                        else -> throw IllegalArgumentException("envelopes 须为数组")
                    }
                    list.forEach { add(it) }
                }
            } else {
                // 结构变更类动作同包携带新版骨架 / 因果 revision（§4.2 末：同事务原子更新）。
                val topo = entry.payload["topology"]
                if (topo != null) put("topology", topo)
                val rev = jsonLongOrNull(entry.payload["expected_revision"])
                if (rev != null) put("expected_revision", rev)
                val add = entry.payload["add_member_uids"]
                if (add is JsonArray) {
                    putJsonArray("add_member_uids") { add.forEach { a -> add(a.jsonPrimitiveStr()) } }
                }
            }
        }

        val token = accessToken()
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/v1/schedule/action")
            .header("Authorization", "Bearer $token")
            .header("X-Client-Msg-Id", entry.id)
            // 服务端幂等时间窗对齐Unix 秒（协议头，非 UI 时钟；镜像 Dart DateTime.now()）
            .header("X-Client-Ts", (System.currentTimeMillis() / 1000).toString())
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val resp = try {
            client.newCall(request).execute()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            return SendOffline(e)
        }

        resp.use { r ->
            val status = r.code
            val bodyStr = r.body?.string() ?: ""
            val map: JsonObject = try {
                Json.parseToJsonElement(bodyStr).let { if (it is JsonObject) it else buildJsonObject { } }
            } catch (_: Exception) {
                buildJsonObject { }
            }
            if (create && status in 200..299) {
                val id = jsonStrOrNull(map["bundle_id"])
                if (id.isNullOrEmpty()) {
                    // 建包成功却没发号=缺少认祖归宗所必需的信息，按 Offline 保留重试，
                    // 绝不把临时 id 当正式 id 用（后续操作会全部 404）。
                    return SendOffline("missing bundle_id in propose ack")
                }
                return SendProposed(
                    bundleId = id,
                    seqId = jsonLongOrNull(map["seq_id"]) ?: 1L,
                )
            }
            if (status in 200..299) return SendAccepted
            if (status >= 500 || status == 401 || status == 0) {
                return SendOffline("HTTP $status")
            }
            // 4xx：确定性打回——error_code 承载精确阻断原因（§10.4 全局错误码字典）
            val code = jsonStrOrNull(map["error_code"]) ?: "HTTP_$status"
            return SendRejected(code)
        }
    }
}

private fun JsonElement.jsonPrimitiveStr(): String = (this as JsonPrimitive).content

/** 是否属于「本地发起 bundle」= 服务端发号的 PROPOSE（action.go 建包分支）。 */
fun isBundleCreation(entry: OutboxEntry): Boolean =
    entry.actionType == "PROPOSE" && entry.payload["participants"] is JsonArray
