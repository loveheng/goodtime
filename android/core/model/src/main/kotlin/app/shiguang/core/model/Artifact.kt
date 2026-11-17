package app.shiguang.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Artifact 实体（lib/models/artifact.dart 的 Kotlin 等价，P2 试点）。
 *
 * 行为契约（golden model-json-artifact rules 节，逐条对拍）：
 * - **payload v 序契约**：encodePayload 的 v 恒首位（值取 payload["v"] ?: 1，spread
 *   覆盖不改位）——契约内嵌版本，反序列化按 v 分支；
 * - **toMap 键序** = DB 行序列化契约（逐字对拍）；payload/attachments 为 TEXT 串形态
 *   （encodePayload / jsonEncode 落库）；
 * - **读侧防御**：decodePayload 的 raw 为 null/空/畸形/非对象 → {"v":1} 不抛
 *   （DEGRADE 日志旁路，不参与对拍）；
 * - **fromMap 缺省**：badge=null、capturedBy="me"、version=0、planId=null（未归属池 D2）；
 * - **payloadWithRefs**：planId 三态（显式值=置键 / null=移除 / 缺省=保持）。
 */
data class Artifact(
    val id: String?,
    val category: String,
    val sourceKind: String,
    val state: String,
    val origin: String,
    val title: String,
    val badge: String?,
    val payload: JsonObject,
    val attachments: List<JsonElement>,
    val capturedBy: String,
    val planId: String?,
    val createdAt: Long?,
    val updatedAt: Long?,
    val version: Long,
) {
    /** DB 行映射（键序与 null 口径=DB 行序列化契约，逐字对拍）：payload/attachments 为
     *  TEXT 串形态；badge/plan_id **恒输出**（null → JSON null，Dart 字面量同构）；
     *  id/created_at/updated_at 条件输出（Dart if ≠ null 同构）。 */
    fun toMap(): JsonObject = buildJsonObject {
        if (id != null) put("id", id)
        put("category", category)
        put("source_kind", sourceKind)
        put("state", state)
        put("origin", origin)
        put("title", title)
        put("badge", badge?.let { JsonPrimitive(it) } ?: JsonNull)
        put("payload", encodePayload(payload))
        put("attachments", json.encodeToString(JsonArray.serializer(), JsonArray(attachments)))
        put("captured_by", capturedBy)
        put("plan_id", planId?.let { JsonPrimitive(it) } ?: JsonNull)
        if (createdAt != null) put("created_at", createdAt)
        if (updatedAt != null) put("updated_at", updatedAt)
        put("version", version)
    }

    /** MCP 快照 JSON（commands.dart artifactToJson 逐字——payload 契约 JSON 原样透出，
     *  槽位不再拍平复制；不含 block_id（块侧挂载走 schedule_blocks.artifact_id）。 */
    fun toMcpJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("category", category)
        put("source_kind", sourceKind)
        put("state", state)
        put("origin", origin)
        put("title", title)
        put("badge", badge)
        put("payload", payload)
        put("captured_by", capturedBy)
        put("plan_id", planId)
        put("version", version)
    }

    companion object {
        const val CATEGORY_TRANSIT = "transit"
        const val CATEGORY_TICKET = "ticket"
        const val CATEGORY_HOTEL = "hotel"
        const val CATEGORY_VENUE = "venue"
        const val CATEGORY_VERBAL = "verbal"

        const val SOURCE_BOOKING = "booking"
        const val SOURCE_ANNOUNCEMENT = "announcement"
        const val SOURCE_VERBAL = "verbal"

        const val STATE_RAW = "raw"
        const val STATE_STRUCTURED = "structured"
        const val STATE_VOIDED = "voided"

        const val ORIGIN_SHARED = "shared"
        const val ORIGIN_QUICKNOTE = "quicknote"
        const val ORIGIN_AI = "ai"
        const val ORIGIN_MANUAL = "manual"

        /** 多人预留钉子（主草案 §3.4）：roster 落地后回填真实身份 */
        const val CAPTURED_BY_ME = "me"

        private val json = Json { encodeDefaults = false }

        /** payload 编码单点：v 恒首位（LinkedHashMap 保序——Dart 字面量同构）。 */
        fun encodePayload(payload: Map<String, JsonElement>): String {
            val ordered = LinkedHashMap<String, JsonElement>()
            ordered["v"] = payload["v"] ?: JsonPrimitive(1)
            for ((k, v) in payload) {
                if (k == "v" && ordered.containsKey("v")) {
                    ordered["v"] = v // spread 覆盖值不改位
                } else {
                    ordered[k] = v
                }
            }
            return json.encodeToString(JsonObject.serializer(), JsonObject(ordered))
        }

        /** payload 读侧防御：null/空/畸形/非对象 → {"v":1} 不抛。 */
        fun decodePayload(raw: String?): JsonObject {
            if (raw == null || raw.isEmpty()) return JsonObject(LinkedHashMap(mapOf("v" to JsonPrimitive(1))))
            try {
                val decoded = Json.parseToJsonElement(raw)
                if (decoded is JsonObject) return decoded
                logDegrade("payload 非 JSON 对象已丢弃: $raw")
            } catch (_: Exception) {
                logDegrade("payload 整列畸形已丢弃: $raw")
            }
            return JsonObject(LinkedHashMap(mapOf("v" to JsonPrimitive(1))))
        }

        /** 双写铁律同步单点（§1.1）：planId 三态。 */
        fun payloadWithRefs(
            payload: JsonObject,
            planId: PlanIdArg = PlanIdArg.Keep,
        ): JsonObject {
            val out = LinkedHashMap(payload)
            when (planId) {
                is PlanIdArg.Set -> out["plan_id"] = JsonPrimitive(planId.value)
                PlanIdArg.Remove -> out.remove("plan_id")
                PlanIdArg.Keep -> {}
            }
            return JsonObject(out)
        }

        private fun logDegrade(detail: String) {
            println("[DEGRADE][artifacts_read_malformed] $detail")
        }

        /** DB 行 → 实体（缺省口径：badge=null、capturedBy="me"、version=0、planId=null）。 */
        fun fromMap(map: JsonObject): Artifact = Artifact(
            id = map["id"]?.jsonPrimitive?.contentOrNull,
            category = map["category"]?.jsonPrimitive?.contentOrNull ?: "",
            sourceKind = map["source_kind"]?.jsonPrimitive?.contentOrNull ?: "",
            state = map["state"]?.jsonPrimitive?.contentOrNull ?: "",
            origin = map["origin"]?.jsonPrimitive?.contentOrNull ?: "",
            title = map["title"]?.jsonPrimitive?.contentOrNull ?: "",
            badge = map["badge"]?.jsonPrimitive?.contentOrNull,
            // 双形态（Plan.fromMap 先例续）：行内 payload/attachments 为编码串、桥线 MCP JSON 为对象/数组
            payload = when (val raw = map["payload"]) {
                is JsonPrimitive -> decodePayload(if (raw.isString) raw.content else null)
                is JsonObject -> raw
                else -> decodePayload(null)
            },
            attachments = when (val raw = map["attachments"]) {
                is JsonArray -> raw
                is JsonPrimitive -> decodeStringList(if (raw.isString) raw.content else null)
                else -> emptyList()
            },
            capturedBy = map["captured_by"]?.jsonPrimitive?.contentOrNull ?: CAPTURED_BY_ME,
            planId = map["plan_id"]?.jsonPrimitive?.contentOrNull,
            createdAt = (map["created_at"] as? JsonPrimitive)?.content?.toLongOrNull(),
            updatedAt = (map["updated_at"] as? JsonPrimitive)?.content?.toLongOrNull(),
            version = (map["version"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0,
        )

        /** JSON 数组读侧防御：非 JSON/非数组 → 空列表。 */
        private fun decodeStringList(raw: String?): List<JsonElement> {
            if (raw == null || raw.isEmpty()) return emptyList()
            return try {
                val decoded = Json.parseToJsonElement(raw)
                if (decoded is JsonArray) decoded else {
                    println("[DEGRADE] attachments 非 JSON 数组已丢弃")
                    emptyList()
                }
            } catch (_: Exception) {
                println("[DEGRADE] attachments 畸形已丢弃")
                emptyList()
            }
        }
    }
}

/** payloadWithRefs 的 planId 三态（Dart `_keep` 哨兵的显式化）。 */
sealed interface PlanIdArg {
    data class Set(val value: String) : PlanIdArg
    data object Remove : PlanIdArg
    data object Keep : PlanIdArg
}
