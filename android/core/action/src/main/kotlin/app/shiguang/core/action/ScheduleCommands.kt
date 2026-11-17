package app.shiguang.core.action

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * ScheduleCommand 命令族 Kotlin 等价（lib/action/commands.dart 26 类全量，P2 节点）。
 *
 * 行为契约（commands_registry.md 已过目 + fromJson 判别语义）：
 * - 三抽象：op / targetId（创建类 null）/ expectedVersion（乐观锁 CAS 断言，空=不校验）；
 * - fromJson：坏 op → invalid_request + hint 可选 op 全列；字段解析助手**写入口严进**
 *   （_reqStr 非空必填 / _objList 数组对象整组拒 / _strList 字符串数组整组拒）；
 * - 领域拒绝唯一载体 = [ActionException]（HTTP 层仍 200）。
 * 数据类字段逐一对应 Dart final 字段；解析助手语义逐字（_str 空串=null 等）。
 */
sealed interface ScheduleCommand {
    val op: String
    val targetId: String?
    val expectedVersion: Long?

    data class QuickCapture(
        val title: String,
        val importance: Boolean = false,
        val deadline: String? = null,
    ) : ScheduleCommand {
        override val op get() = "quick_capture"
        override val targetId get() = null
        override val expectedVersion get() = null
    }

    data class QuickNoteDraft(
        val text: String,
        val importance: Boolean = false,
        val deadline: String? = null,
    ) : ScheduleCommand {
        override val op get() = "quick_note_draft"
        override val targetId get() = null
        override val expectedVersion get() = null
    }

    data class UpsertPlan(
        val title: String,
        val spec: String? = null,
        val notes: String? = null,
        val minViableAction: String? = null,
        val energyLevel: String? = null,
        val toolRequired: String? = null,
        val rewardSpec: String? = null,
        val importance: Boolean = false,
        val deadline: String? = null,
        val estimate: Long? = null,
        val parentId: String? = null,
        val openItems: List<OpenItemRef>? = null,
    ) : ScheduleCommand {
        override val op get() = "upsert_plan"
        override val targetId get() = null
        override val expectedVersion get() = null
    }

    data class UpdatePlan(
        val id: String,
        val title: String? = null,
        val spec: String? = null,
        val notes: String? = null,
        val minViableAction: String? = null,
        val energyLevel: String? = null,
        val toolRequired: String? = null,
        val rewardSpec: String? = null,
        val importance: Boolean? = null,
        val deadline: String? = null,
        val estimate: Long? = null,
        val parentId: String? = null,
        val openItems: List<OpenItemRef>? = null,
        val archived: Boolean? = null,
        override val expectedVersion: Long? = null,
    ) : ScheduleCommand {
        override val op get() = "update_plan"
        override val targetId get() = id
    }

    data class ProposeSchedule(
        val date: String,
        val items: List<ProposedItemRef>,
    ) : ScheduleCommand {
        override val op get() = "propose_schedule"
        override val targetId get() = null
        override val expectedVersion get() = null
    }

    data class PlaceBlock(
        val date: String,
        val startMin: Long,
        val endMin: Long,
        val planId: String? = null,
        val label: String? = null,
    ) : ScheduleCommand {
        override val op get() = "place_block"
        override val targetId get() = null
        override val expectedVersion get() = null
    }

    data class DeletePlan(val id: String) : ScheduleCommand {
        override val op get() = "delete_plan"
        override val targetId get() = id
        override val expectedVersion get() = null
    }

    data class AdjustBlocks(
        val action: String, // add | move | resize | remove
        val blockId: String? = null,
        val date: String? = null,
        val startMin: Long? = null,
        val endMin: Long? = null,
        val planId: String? = null,
        val label: String? = null,
        val pinned: Boolean? = null,
        override val expectedVersion: Long? = null,
    ) : ScheduleCommand {
        override val op get() = "adjust_blocks"
        override val targetId get() = blockId
    }

    data class RetroLog(
        val date: String,
        val startMin: Long,
        val endMin: Long,
        val planId: String? = null,
        val label: String? = null,
        val executionQuality: String? = null, // full（默认）/ spark
    ) : ScheduleCommand {
        override val op get() = "retro_log"
        override val targetId get() = null
        override val expectedVersion get() = null
    }

    data class SwapBlock(
        val id: String,
        val targetPlanId: String? = null,
        override val expectedVersion: Long? = null,
    ) : ScheduleCommand {
        override val op get() = "swap_block"
        override val targetId get() = id
    }

    data class DegradeBlock(
        val id: String,
        override val expectedVersion: Long? = null,
    ) : ScheduleCommand {
        override val op get() = "degrade_block"
        override val targetId get() = id
    }

    data class MeltBlock(
        val id: String,
        override val expectedVersion: Long? = null,
    ) : ScheduleCommand {
        override val op get() = "melt_block"
        override val targetId get() = id
    }

    data class ReflowDay(val date: String? = null) : ScheduleCommand {
        override val op get() = "reflow_day"
        override val targetId get() = null
        override val expectedVersion get() = null
    }

    data class ConfirmBlock(
        val id: String,
        override val expectedVersion: Long? = null,
    ) : ScheduleCommand {
        override val op get() = "confirm_block"
        override val targetId get() = id
    }

    data class RejectBlock(
        val id: String,
        val reason: String? = null,
        override val expectedVersion: Long? = null,
    ) : ScheduleCommand {
        override val op get() = "reject_block"
        override val targetId get() = id
    }

    data class AdjustBlockTime(
        val id: String,
        val startMin: Long? = null,
        val endMin: Long? = null,
        override val expectedVersion: Long? = null,
    ) : ScheduleCommand {
        override val op get() = "adjust_block_time"
        override val targetId get() = id
    }

    data class ShiftBlock(
        val id: String,
        val minutes: Long = 30,
        override val expectedVersion: Long? = null,
    ) : ScheduleCommand {
        override val op get() = "shift_block"
        override val targetId get() = id
    }

    data class TickBlock(
        val id: String,
        val executionQuality: String? = null, // full（默认）/ spark
        override val expectedVersion: Long? = null,
    ) : ScheduleCommand {
        override val op get() = "tick_block"
        override val targetId get() = id
    }

    data class RestoreBlock(
        val id: String,
        val toStatus: String, // proposed/confirmed（其余命令层拒绝）
        override val expectedVersion: Long? = null,
    ) : ScheduleCommand {
        override val op get() = "restore_block"
        override val targetId get() = id
    }

    data class PostponeBlock(
        val id: String,
        val date: String,
        override val expectedVersion: Long? = null,
    ) : ScheduleCommand {
        override val op get() = "postpone_block"
        override val targetId get() = id
    }

    data class PanicClear(
        val mode: String, // clear_remaining | push_2h
        val nowMin: Long? = null,
        override val expectedVersion: Long? = null,
    ) : ScheduleCommand {
        override val op get() = "panic_clear"
        override val targetId get() = null
    }

    /**
     * values 值保留 JsonElement 原型（Dart values.cast<String, Object?> 逐字）：
     * JsonNull = 清键（传 null 清空对应键）；其余经 handler 键注册制校验后串化落库。
     */
    data class UpdateSettings(val values: Map<String, JsonElement>) : ScheduleCommand {
        override val op get() = "update_settings"
        override val targetId get() = null
        override val expectedVersion get() = null
    }

    data class UpdateFixedSlots(val slots: List<FixedSlotRef>) : ScheduleCommand {
        override val op get() = "update_fixed_slots"
        override val targetId get() = null
        override val expectedVersion get() = null
    }

    data class UpsertBackground(
        val id: String? = null,
        val scope: String, // global | plan（建档定死，改挂=删旧增新走 merge）
        val planId: String? = null,
        val content: String,
        val rawSourceText: String? = null,
        val tags: List<String>? = null,
        val applicableDates: List<String>? = null,
        override val expectedVersion: Long? = null,
    ) : ScheduleCommand {
        override val op get() = "upsert_background"
        override val targetId get() = id
    }

    data class MergeBackgrounds(val ops: List<BackgroundMergeOp>) : ScheduleCommand {
        override val op get() = "merge_backgrounds"
        override val targetId get() = null
        override val expectedVersion get() = null
    }

    data class UpsertFacts(
        val id: String? = null,
        val category: String? = null,
        val sourceKind: String? = null,
        val title: String? = null,
        val state: String? = null,
        val origin: String? = null,
        val badge: String? = null,
        val heroMetrics: List<JsonObject>? = null,
        val timeAnchors: List<JsonObject>? = null,
        val constraints: JsonObject? = null,
        val ruleScope: JsonObject? = null,
        val location: String? = null,
        val copyableCode: String? = null,
        val contactPhone: String? = null,
        val rawText: String? = null,
        val planId: String? = null,
        val blockId: String? = null,
        override val expectedVersion: Long? = null,
    ) : ScheduleCommand {
        override val op get() = "upsert_facts"
        override val targetId get() = id
    }

    data class DeleteArtifact(val id: String) : ScheduleCommand {
        override val op get() = "delete_artifact"
        override val targetId get() = id
        override val expectedVersion get() = null
    }
}

/** 引用类型（字段级类后续单元平移；JsonObject 透传保持解析保真）。 */
data class OpenItemRef(val question: String, val answer: String?)

data class ProposedItemRef(
    val planId: String?,
    val label: String?,
    val startMin: Long,
    val endMin: Long,
    val isDaySpark: Boolean,
    val isCelebration: Boolean,
)

data class FixedSlotRef(
    val id: String?,
    val name: String,
    val weekdays: List<Long>,
    val startMin: Long,
    val endMin: Long,
)

/** 归并批处理单项：delete={id} / upsert={id?, scope?, plan_id?, content, …}。 */
data class BackgroundMergeOp(
    val kind: String, // delete | upsert
    val id: String? = null,
    val scope: String? = null,
    val planId: String? = null,
    val content: String? = null,
    val rawSourceText: String? = null,
    val tags: List<String>? = null,
    val applicableDates: List<String>? = null,
)

/**
 * JSON → 命令判别（Dart ScheduleCommand.fromJson 语义逐字）：
 * 坏 op → invalid_request + hint 可选 op 全列；解析助手**写入口严进**。
 */
object ScheduleCommandCodec {

    val SUPPORTED_OPS = listOf(
        "quick_capture", "quick_note_draft", "upsert_plan", "update_plan",
        "delete_plan", "propose_schedule", "place_block", "adjust_blocks",
        "retro_log", "swap_block", "degrade_block", "melt_block", "reflow_day",
        "confirm_block", "reject_block", "adjust_block_time", "shift_block",
        "tick_block", "restore_block", "postpone_block", "panic_clear",
        "update_settings", "update_fixed_slots", "upsert_background",
        "merge_backgrounds", "upsert_facts", "delete_artifact",
    )

    private val json = Json { encodeDefaults = false }

    fun fromJson(json: JsonObject): ScheduleCommand {
        val op = json["op"]?.jsonPrimitive?.contentOrNull
        if (op.isNullOrEmpty()) {
            throw ActionException(
                "命令缺少 op 字段",
                ActionErrorCode.INVALID_REQUEST,
                hint = "可选 op：${SUPPORTED_OPS.joinToString(", ")}",
            )
        }
        val ev = intOf(json["expected_version"])
        return when (op) {
            "quick_capture" -> ScheduleCommand.QuickCapture(
                title = reqStr(json, "title", op),
                importance = json["importance"]?.jsonPrimitive?.contentOrNull == "true",
                deadline = strOf(json["deadline"]),
            )
            "quick_note_draft" -> ScheduleCommand.QuickNoteDraft(
                text = reqStr(json, "text", op),
                importance = json["importance"]?.jsonPrimitive?.contentOrNull == "true",
                deadline = strOf(json["deadline"]),
            )
            "upsert_plan" -> ScheduleCommand.UpsertPlan(
                title = reqStr(json, "title", op),
                spec = strOf(json["spec"]),
                notes = strOf(json["notes"]),
                minViableAction = strOf(json["min_viable_action"]),
                energyLevel = strOf(json["energy_level"]),
                toolRequired = strOf(json["tool_required"]),
                rewardSpec = strOf(json["reward_spec"]),
                importance = json["importance"]?.jsonPrimitive?.contentOrNull == "true",
                deadline = strOf(json["deadline"]),
                estimate = longOf(json["estimate"]),
                parentId = strOf(json["parent_id"]),
                openItems = openItems(json["open_items"]),
            )
            "update_plan" -> ScheduleCommand.UpdatePlan(
                id = strOf(json["id"]) ?: reqStr(json, "id", op),
                title = strOf(json["title"]),
                spec = strOf(json["spec"]),
                notes = strOf(json["notes"]),
                minViableAction = strOf(json["min_viable_action"]),
                energyLevel = strOf(json["energy_level"]),
                toolRequired = strOf(json["tool_required"]),
                rewardSpec = strOf(json["reward_spec"]),
                importance = boolOrNull(json["importance"]),
                deadline = strOf(json["deadline"]),
                estimate = longOf(json["estimate"]),
                parentId = strOf(json["parent_id"]),
                openItems = openItems(json["open_items"]),
                archived = boolOrNull(json["archived"]),
                expectedVersion = ev,
            )
            "propose_schedule" -> ScheduleCommand.ProposeSchedule(
                date = reqStr(json, "date", op),
                items = (json["items"] as? JsonArray ?: throw ActionException(
                    "propose_schedule 的 items 必须是数组",
                    ActionErrorCode.INVALID_REQUEST,
                )).map { e ->
                    val o = e.jsonObject
                    ProposedItemRef(
                        planId = strOf(o["plan_id"]),
                        label = strOf(o["label"]),
                        startMin = o["start_min"]?.jsonPrimitive?.longOrNull
                            ?: throw ActionException(
                                "propose_schedule 项缺 start_min",
                                ActionErrorCode.INVALID_REQUEST,
                            ),
                        endMin = o["end_min"]?.jsonPrimitive?.longOrNull
                            ?: throw ActionException(
                                "propose_schedule 项缺 end_min",
                                ActionErrorCode.INVALID_REQUEST,
                            ),
                        isDaySpark = o["is_day_spark"]?.jsonPrimitive?.contentOrNull == "true",
                        isCelebration = o["is_celebration"]?.jsonPrimitive?.contentOrNull == "true",
                    )
                },
            )
            "place_block" -> ScheduleCommand.PlaceBlock(
                date = reqStr(json, "date", op),
                startMin = longOf(json["start_min"]) ?: missingLong("place_block", "start_min"),
                endMin = longOf(json["end_min"]) ?: missingLong("place_block", "end_min"),
                planId = strOf(json["plan_id"]),
                label = strOf(json["label"]),
            )
            "delete_plan" -> ScheduleCommand.DeletePlan(
                strOf(json["id"]) ?: reqStr(json, "id", op),
            )
            "adjust_blocks" -> ScheduleCommand.AdjustBlocks(
                action = reqStr(json, "action", op),
                blockId = strOf(json["block_id"]),
                date = strOf(json["date"]),
                startMin = longOf(json["start_min"]),
                endMin = longOf(json["end_min"]),
                planId = strOf(json["plan_id"]),
                label = strOf(json["label"]),
                pinned = boolOrNull(json["pinned"]),
                expectedVersion = ev,
            )
            "retro_log" -> ScheduleCommand.RetroLog(
                date = reqStr(json, "date", op),
                startMin = longOf(json["start_min"]) ?: missingLong("retro_log", "start_min"),
                endMin = longOf(json["end_min"]) ?: missingLong("retro_log", "end_min"),
                planId = strOf(json["plan_id"]),
                label = strOf(json["label"]),
                executionQuality = strOf(json["execution_quality"]),
            )
            "swap_block" -> ScheduleCommand.SwapBlock(
                id = strOf(json["id"]) ?: reqStr(json, "id", op),
                targetPlanId = strOf(json["target_plan_id"]),
                expectedVersion = ev,
            )
            "degrade_block" -> ScheduleCommand.DegradeBlock(
                strOf(json["id"]) ?: reqStr(json, "id", op), ev,
            )
            "melt_block" -> ScheduleCommand.MeltBlock(
                strOf(json["id"]) ?: reqStr(json, "id", op), ev,
            )
            "reflow_day" -> ScheduleCommand.ReflowDay(date = strOf(json["date"]))
            "confirm_block" -> ScheduleCommand.ConfirmBlock(
                strOf(json["id"]) ?: reqStr(json, "id", op), ev,
            )
            "reject_block" -> ScheduleCommand.RejectBlock(
                strOf(json["id"]) ?: reqStr(json, "id", op),
                reason = strOf(json["reason"]),
                expectedVersion = ev,
            )
            "adjust_block_time" -> ScheduleCommand.AdjustBlockTime(
                id = strOf(json["id"]) ?: reqStr(json, "id", op),
                startMin = longOf(json["start_min"]),
                endMin = longOf(json["end_min"]),
                expectedVersion = ev,
            )
            "shift_block" -> ScheduleCommand.ShiftBlock(
                id = strOf(json["id"]) ?: reqStr(json, "id", op),
                minutes = longOf(json["minutes"]) ?: 30,
                expectedVersion = ev,
            )
            "tick_block" -> ScheduleCommand.TickBlock(
                id = strOf(json["id"]) ?: reqStr(json, "id", op),
                executionQuality = strOf(json["execution_quality"]),
                expectedVersion = ev,
            )
            "restore_block" -> ScheduleCommand.RestoreBlock(
                id = strOf(json["id"]) ?: reqStr(json, "id", op),
                toStatus = reqStr(json, "to_status", op),
                expectedVersion = ev,
            )
            "postpone_block" -> ScheduleCommand.PostponeBlock(
                id = strOf(json["id"]) ?: reqStr(json, "id", op),
                date = reqStr(json, "date", op),
                expectedVersion = ev,
            )
            "panic_clear" -> ScheduleCommand.PanicClear(
                mode = reqStr(json, "mode", op),
                nowMin = longOf(json["now_min"]),
            )
            "update_settings" -> ScheduleCommand.UpdateSettings(
                values = json["values"] as? JsonObject ?: throw ActionException(
                    "update_settings 需要 values 对象",
                    ActionErrorCode.INVALID_REQUEST,
                    hint = "合法键：wake_time/sleep_time/min_block_minutes/daily_new_blocks_limit/" +
                        "today_energy/user_rules/weather_location/exceptions",
                ),
            )
            "update_fixed_slots" -> ScheduleCommand.UpdateFixedSlots(
                slots = (json["slots"] as? JsonArray ?: JsonArray(emptyList())).map { e ->
                    val o = e.jsonObject
                    FixedSlotRef(
                        id = strOf(o["id"]),
                        name = o["name"]?.jsonPrimitive?.contentOrNull ?: "",
                        weekdays = (o["weekdays"] as? JsonArray)
                            ?.map { it.jsonPrimitive.longOrNull ?: 0 } ?: emptyList(),
                        startMin = o["start_min"]?.jsonPrimitive?.longOrNull ?: 0,
                        endMin = o["end_min"]?.jsonPrimitive?.longOrNull ?: 0,
                    )
                },
            )
            "upsert_background" -> ScheduleCommand.UpsertBackground(
                id = strOf(json["id"]),
                scope = reqStr(json, "scope", op),
                planId = strOf(json["plan_id"]),
                content = reqStr(json, "content", op),
                rawSourceText = strOf(json["raw_source_text"]),
                tags = strList(json["tags"], "tags", op),
                applicableDates = strList(json["applicable_dates"], "applicable_dates", op),
                expectedVersion = ev,
            )
            "merge_backgrounds" -> ScheduleCommand.MergeBackgrounds(
                ops = (json["ops"] as? JsonArray ?: throw ActionException(
                    "merge_backgrounds 的 ops 必须是数组",
                    ActionErrorCode.INVALID_REQUEST,
                )).mapIndexed { i, e ->
                    mergeOpOf(e.jsonObject, "ops[$i]", op)
                },
            )
            "upsert_facts" -> ScheduleCommand.UpsertFacts(
                id = strOf(json["id"]),
                category = strOf(json["category"]),
                sourceKind = strOf(json["source_kind"]),
                title = strOf(json["title"]),
                state = strOf(json["state"]),
                origin = strOf(json["origin"]),
                badge = strOf(json["badge"]),
                heroMetrics = objList(json["hero_metrics"], "hero_metrics", op),
                timeAnchors = objList(json["time_anchors"], "time_anchors", op),
                constraints = json["constraints"] as? JsonObject,
                ruleScope = json["rule_scope"] as? JsonObject,
                location = strOf(json["location"]),
                copyableCode = strOf(json["copyable_code"]),
                contactPhone = strOf(json["contact_phone"]),
                rawText = strOf(json["raw_text"]),
                planId = strOf(json["plan_id"]),
                blockId = strOf(json["block_id"]),
                expectedVersion = ev,
            )
            "delete_artifact" -> ScheduleCommand.DeleteArtifact(
                strOf(json["id"]) ?: reqStr(json, "id", op),
            )
            else -> throw ActionException(
                "未知 op：$op（支持清单见 SUPPORTED_OPS）",
                ActionErrorCode.INVALID_REQUEST,
                hint = "可选 op：${SUPPORTED_OPS.joinToString(", ")}",
            )
        }
    }

    private fun mergeOpOf(json: JsonObject, key: String, op: String): BackgroundMergeOp {
        val kind = json["kind"]?.jsonPrimitive?.contentOrNull
        return when (kind) {
            "delete" -> {
                val id = strOf(json["id"])
                    ?: throw ActionException("$op 的 $key（delete）需要非空 id", ActionErrorCode.INVALID_REQUEST)
                BackgroundMergeOp(kind = "delete", id = id)
            }
            "upsert" -> {
                val content = strOf(json["content"]) ?: throw ActionException(
                    "$op 的 $key（upsert）需要非空 content（归并后的归纳描述）",
                    ActionErrorCode.INVALID_REQUEST,
                )
                BackgroundMergeOp(
                    kind = "upsert",
                    id = strOf(json["id"]),
                    scope = strOf(json["scope"]),
                    planId = strOf(json["plan_id"]),
                    content = content,
                    rawSourceText = strOf(json["raw_source_text"]),
                    tags = strList(json["tags"], "$key.tags", op),
                    applicableDates = strList(json["applicable_dates"], "$key.applicable_dates", op),
                )
            }
            else -> throw ActionException(
                "$op 的 $key kind 非法：$kind（delete/upsert）",
                ActionErrorCode.INVALID_REQUEST,
            )
        }
    }

    // ---- 解析助手（语义逐字：写入口严进） ---------------------------------------

    private fun strOf(v: kotlinx.serialization.json.JsonElement?): String? =
        (v as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }

    private fun intOf(v: kotlinx.serialization.json.JsonElement?): Long? =
        (v as? JsonPrimitive)?.contentOrNull?.toLongOrNull()

    private fun longOf(v: kotlinx.serialization.json.JsonElement?): Long? = intOf(v)

    private fun boolOrNull(v: kotlinx.serialization.json.JsonElement?): Boolean? =
        (v as? JsonPrimitive)?.contentOrNull?.let { it == "true" }

    private fun missingLong(op: String, key: String): Long =
        throw ActionException("$op 需要整数字段 $key", ActionErrorCode.INVALID_REQUEST)

    private fun reqStr(json: JsonObject, key: String, op: String): String =
        strOf(json[key]) ?: throw ActionException(
            "$op 需要非空字符串字段 $key",
            ActionErrorCode.INVALID_REQUEST,
        )

    private fun objList(v: kotlinx.serialization.json.JsonElement?, key: String, op: String): List<JsonObject>? {
        if (v == null || v is JsonNull) return null
        val arr = v as? JsonArray ?: throw ActionException(
            "$op 的 $key 必须是数组", ActionErrorCode.INVALID_REQUEST,
        )
        return arr.mapIndexed { i, e ->
            e as? JsonObject ?: throw ActionException(
                "$op 的 $key[$i] 必须是对象", ActionErrorCode.INVALID_REQUEST,
            )
        }
    }

    private fun strList(v: kotlinx.serialization.json.JsonElement?, key: String, op: String): List<String>? {
        if (v == null || v is JsonNull) return null
        val arr = v as? JsonArray ?: throw ActionException(
            "$op 的 $key 必须是字符串数组", ActionErrorCode.INVALID_REQUEST,
        )
        return arr.map { e ->
            e.jsonPrimitive.contentOrNull ?: throw ActionException(
                "$op 的 $key 元素须为字符串（got: $e）", ActionErrorCode.INVALID_REQUEST,
            )
        }
    }

    private fun openItems(v: kotlinx.serialization.json.JsonElement?): List<OpenItemRef>? {
        if (v !is JsonArray) return null
        return v.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val q = o["question"] as? JsonPrimitive ?: return@mapNotNull null
            OpenItemRef(
                question = q.content,
                answer = o["answer"]?.jsonPrimitive?.contentOrNull,
            )
        }
    }
}
