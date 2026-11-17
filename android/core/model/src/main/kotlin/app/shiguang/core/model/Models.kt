package app.shiguang.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * 领域模型三件（Plan/ScheduleBlock/Background 的 Kotlin 等价，P2 铺开）。
 *
 * 行为契约（golden models_vectors rules 节）：toMap 键序=DB 行序列化契约（JSON 串逐字
 * 对拍）；布尔列一律 int 0/1；JSON 数组字段（open_items/tags/applicable_dates）为 TEXT
 * 串形态；fromMap 缺省口径逐字段（energy=light/tool=anywhere/status=proposed/
 * quality=full/capturedBy=me/布尔 false/version=0）；applicable_dates null → **键缺省**；
 * ScheduleBlock 跨午夜 end<start 原样落列不折算。
 */
private val json = Json { encodeDefaults = false }

private fun enc(e: JsonElement): String = json.encodeToString(JsonElement.serializer(), e)

private fun strList(raw: String?): List<String> {
    if (raw == null || raw.isEmpty()) return emptyList()
    return try {
        (Json.parseToJsonElement(raw) as JsonArray).map { it.jsonPrimitive.content }
    } catch (_: Exception) {
        emptyList()
    }
}

// ---- Plan ---------------------------------------------------------------------

data class OpenItem(val question: String, val answer: String? = null)

data class Plan(
    val id: String?,
    val title: String,
    val spec: String?,
    val notes: String?,
    val openItems: List<OpenItem>,
    val minViableAction: String?,
    val energyLevel: String,
    val toolRequired: String,
    val rewardSpec: String?,
    val importance: Boolean,
    val deadline: String?,
    val estimate: Long?,
    val parentId: String?,
    val archived: Boolean,
    val createdAt: Long?,
    val updatedAt: Long?,
    val version: Long,
) {
    companion object {
        const val ENERGY_LIGHT = "light"
        const val ENERGY_DEEP = "deep"
        const val TOOL_ANYWHERE = "anywhere"
        const val TOOL_DESK = "desk"

        fun encodeOpenItems(items: List<OpenItem>): String = enc(
            JsonArray(items.map { o ->
                buildJsonObject {
                    put("question", o.question)
                    if (o.answer != null) put("answer", o.answer)
                }
            }),
        )

        fun decodeOpenItems(raw: String?): List<OpenItem> {
            if (raw == null || raw.isEmpty()) return emptyList()
            return (Json.parseToJsonElement(raw) as JsonArray).map { e ->
                val o = e.jsonObject
                OpenItem(
                    question = o["question"]!!.jsonPrimitive.content,
                    answer = o["answer"]?.jsonPrimitive?.contentOrNull,
                )
            }
        }

        fun fromMap(map: JsonObject): Plan {
            val p = map
            fun str(k: String): String? = p[k]?.jsonPrimitive?.contentOrNull
            fun long(k: String): Long? = p[k]?.jsonPrimitive?.longOrNull
            // 布尔双形态（同 ScheduleBlock.fromMap）：行内 0/1 与桥线 MCP JSON true/false 兼容
            fun boolOf(k: String): Boolean {
                val pr = p[k] as? JsonPrimitive ?: return false
                if (pr.isString) return pr.content == "true" || pr.content == "1"
                return pr.booleanOrNull ?: ((pr.longOrNull ?: 0L) != 0L)
            }
            return Plan(
                id = str("id")!!,
                title = str("title")!!,
                spec = str("spec"),
                notes = str("notes"),
                // 双形态：行内 open_items 为编码串、桥线 MCP JSON 为数组（FixedSlot weekdays 同款先例）
                openItems = decodeOpenItems(
                    when (val raw = p["open_items"]) {
                        is JsonArray -> raw.toString()
                        is JsonPrimitive -> if (raw.isString) raw.content else null
                        else -> null
                    },
                ),
                minViableAction = str("min_viable_action"),
                energyLevel = str("energy_level") ?: ENERGY_LIGHT,
                toolRequired = str("tool_required") ?: TOOL_ANYWHERE,
                rewardSpec = str("reward_spec"),
                importance = boolOf("importance"),
                deadline = str("deadline"),
                estimate = long("estimate"),
                parentId = str("parent_id"),
                archived = boolOf("archived"),
                createdAt = long("created_at"),
                updatedAt = long("updated_at"),
                version = long("version") ?: 0L,
            )
        }
    }

    fun toMap(): JsonObject = buildJsonObject {
        if (id != null) put("id", id)
        put("title", title)
        put("spec", spec)
        put("notes", notes)
        put("open_items", encodeOpenItems(openItems))
        put("min_viable_action", minViableAction)
        put("energy_level", energyLevel)
        put("tool_required", toolRequired)
        put("reward_spec", rewardSpec)
        put("importance", if (importance) 1 else 0)
        put("deadline", deadline)
        put("estimate", estimate)
        put("parent_id", parentId)
        put("archived", if (archived) 1 else 0)
        if (createdAt != null) put("created_at", createdAt)
        if (updatedAt != null) put("updated_at", updatedAt)
        put("version", version)
    }

    /** MCP 快照 JSON（commands.dart planToJson 逐字——快照与 list_plans 同形）。 */
    fun toMcpJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("title", title)
        put("spec", spec)
        put("notes", notes)
        put("open_items", JsonArray(openItems.map { o ->
            buildJsonObject {
                put("question", o.question)
                o.answer?.let { put("answer", it) }
            }
        }))
        put("min_viable_action", minViableAction)
        put("energy_level", energyLevel)
        put("tool_required", toolRequired)
        put("reward_spec", rewardSpec)
        put("importance", importance)
        put("deadline", deadline)
        put("estimate", estimate)
        put("parent_id", parentId)
        put("archived", archived)
        put("version", version)
    }
}

// ---- ScheduleBlock --------------------------------------------------------------

data class ScheduleBlock(
    val id: String?,
    val date: String,
    val startMin: Long,
    val endMin: Long,
    val planId: String?,
    val label: String?,
    val source: String,
    val pinned: Boolean,
    val status: String,
    val postponeCount: Long,
    val executionQuality: String,
    val isDaySpark: Boolean,
    val isCelebration: Boolean,
    val createdAt: Long?,
    val updatedAt: Long?,
    val version: Long,
) {
    companion object {
        const val SOURCE_HUMAN = "human"
        const val SOURCE_AI = "ai"
        const val STATUS_PROPOSED = "proposed"
        const val STATUS_CONFIRMED = "confirmed"
        const val QUALITY_FULL = "full"
        const val QUALITY_SPARK = "spark"

        fun fromMap(map: JsonObject): ScheduleBlock {
            fun str(k: String): String? = map[k]?.jsonPrimitive?.contentOrNull
            fun long(k: String): Long? = map[k]?.jsonPrimitive?.longOrNull
            // 布尔双形态（FixedSlot weekdays 双形态同款）：行内 0/1 与桥线 MCP JSON true/false 兼容
            fun bool(k: String): Boolean {
                val p = map[k] as? JsonPrimitive ?: return false
                if (p.isString) return p.content == "true" || p.content == "1"
                return p.booleanOrNull ?: ((p.longOrNull ?: 0L) != 0L)
            }
            return ScheduleBlock(
                id = str("id")!!,
                date = str("date")!!,
                startMin = long("start_min")!!,
                endMin = long("end_min")!!,
                planId = str("plan_id"),
                label = str("label"),
                source = str("source")!!,
                pinned = bool("pinned"),
                status = str("status") ?: STATUS_PROPOSED,
                postponeCount = long("postpone_count") ?: 0L,
                executionQuality = str("execution_quality") ?: QUALITY_FULL,
                isDaySpark = bool("is_day_spark"),
                isCelebration = bool("is_celebration"),
                createdAt = long("created_at"),
                updatedAt = long("updated_at"),
                version = long("version") ?: 0L,
            )
        }
    }

    fun toMap(): JsonObject = buildJsonObject {
        if (id != null) put("id", id)
        put("date", date)
        put("start_min", startMin)
        put("end_min", endMin)
        put("plan_id", planId)
        put("label", label)
        put("source", source)
        put("pinned", if (pinned) 1 else 0)
        put("status", status)
        put("postpone_count", postponeCount)
        put("execution_quality", executionQuality)
        put("is_day_spark", if (isDaySpark) 1 else 0)
        put("is_celebration", if (isCelebration) 1 else 0)
        if (createdAt != null) put("created_at", createdAt)
        if (updatedAt != null) put("updated_at", updatedAt)
        put("version", version)
    }

    /** MCP 快照 JSON（commands.dart blockToJson 逐字——含 start/end clockOf 与显式 null）。 */
    fun toMcpJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("date", date)
        put("start", ScheduleDay.clockOf(startMin.toInt()))
        put("end", ScheduleDay.clockOf(endMin.toInt()))
        put("start_min", startMin)
        put("end_min", endMin)
        put("plan_id", planId)
        put("label", label)
        put("source", source)
        put("pinned", pinned)
        put("status", status)
        put("postpone_count", postponeCount)
        put("execution_quality", executionQuality)
        put("is_day_spark", isDaySpark)
        put("is_celebration", isCelebration)
        put("version", version)
    }
}

// ---- Background -----------------------------------------------------------------

data class Background(
    val id: String?,
    val scope: String,
    val planId: String?,
    val content: String,
    val rawSourceText: String?,
    val tags: List<String>,
    val applicableDates: List<String>?,
    val source: String,
    val capturedBy: String,
    val createdAt: Long?,
    val updatedAt: Long?,
    val version: Long,
) {
    companion object {
        const val SCOPE_GLOBAL = "global"
        const val SCOPE_PLAN = "plan"
        const val SOURCE_USER = "user"
        const val SOURCE_AI_DERIVED = "ai_derived"
        const val CAPTURED_BY_ME = "me"

        fun fromMap(map: JsonObject): Background {
            fun str(k: String): String? = map[k]?.jsonPrimitive?.contentOrNull
            fun long(k: String): Long? = map[k]?.jsonPrimitive?.longOrNull
            val applicableRaw = map["applicable_dates"]
            // 双形态（Plan.fromMap 先例续）：行内 tags/applicable_dates 为编码串、桥线
            // MCP JSON 为数组（JsonNull=列空=长期，与 [] 空窗 fail-closed 有别）
            val tags: List<String> = when (val raw = map["tags"]) {
                is JsonArray -> raw.map { it.jsonPrimitive.content }
                is JsonPrimitive -> if (raw.isString) strList(raw.content) else emptyList()
                else -> emptyList()
            }
            val applicableDates: List<String>? = when (val raw = applicableRaw) {
                is JsonArray -> raw.map { it.jsonPrimitive.content }
                is JsonPrimitive -> if (raw.isString) strList(raw.content) else null
                else -> null
            }
            return Background(
                id = str("id")!!,
                scope = str("scope")!!,
                planId = str("plan_id"),
                content = str("content")!!,
                rawSourceText = str("raw_source_text"),
                tags = tags,
                applicableDates = applicableDates,
                source = str("source")!!,
                capturedBy = str("captured_by") ?: CAPTURED_BY_ME,
                createdAt = long("created_at"),
                updatedAt = long("updated_at"),
                version = long("version") ?: 0L,
            )
        }

        private fun strList(raw: String?): List<String> {
            if (raw == null || raw.isEmpty()) return emptyList()
            return try {
                (Json.parseToJsonElement(raw) as JsonArray).map { it.jsonPrimitive.content }
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    fun toMap(): JsonObject = buildJsonObject {
        if (id != null) put("id", id)
        put("scope", scope)
        put("plan_id", planId)
        put("content", content)
        put("raw_source_text", rawSourceText)
        put("tags", enc(JsonArray(tags.map { JsonPrimitive(it) })))
        if (applicableDates != null) {
            put("applicable_dates", enc(JsonArray(applicableDates.map { JsonPrimitive(it) })))
        }
        put("source", source)
        put("captured_by", capturedBy)
        if (createdAt != null) put("created_at", createdAt)
        if (updatedAt != null) put("updated_at", updatedAt)
        put("version", version)
    }

    /** MCP 快照 JSON（commands.dart backgroundToJson 逐字——tags/applicable_dates 数组、created/updated 不出）。 */
    fun toMcpJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("scope", scope)
        put("plan_id", planId)
        put("content", content)
        put("raw_source_text", rawSourceText)
        put("tags", JsonArray(tags.map { JsonPrimitive(it) }))
        put(
            "applicable_dates",
            applicableDates?.let { JsonArray(it.map { d -> JsonPrimitive(d) }) } ?: JsonNull,
        )
        put("source", source)
        put("captured_by", capturedBy)
        put("version", version)
    }
}
