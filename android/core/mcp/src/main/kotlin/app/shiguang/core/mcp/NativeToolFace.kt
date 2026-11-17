package app.shiguang.core.mcp

import app.shiguang.core.action.ActionException
import app.shiguang.core.action.BridgeRepositoryPort
import app.shiguang.core.action.CommandActor
import app.shiguang.core.action.CommandHandler
import app.shiguang.core.action.CommandResult
import app.shiguang.core.action.ScheduleCommandCodec
import app.shiguang.core.action.ScheduleQueries
import app.shiguang.core.model.Artifact
import app.shiguang.core.model.Background
import app.shiguang.core.model.ScheduleDay
import app.shiguang.core.model.SettingsKeys
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.time.LocalDateTime

/**
 * 原生工具面（lib/mcp/tools.dart 的 Kotlin 等价，P2 工具面原生切换件）。
 *
 * 三职责同 Dart（防呆全在命令层，本层零业务判断）：
 * ① 大模型 JSON → [ScheduleCommandCodec.fromJson] 单一入口（与 UI 同一命令类）；
 * ② 以 [CommandActor.AI] 调命令层（越权门在 CommandHandler.gate）；
 * ③ [CommandResult.toJson] 序列化回 JSON——AI 上下文与库对齐。
 * 领域拒绝统一 ActionException → McpRpcError(-32602, message, {code, hint?, ...data})。
 *
 * 切换形态（D8 收官形态）：:app 把 `McpServer(toolFace = dartBridge)` 换成本类
 * （repo = NativeSqlRepository）；schemas() 暂沿桥通道（工具描述文案未平移）。
 *
 * 动态值注入：[now] 供测试钉时刻（生产=系统钟）；[weather] 天气投影挂载钩子
 * （Dart WeatherProvider 端口——:core 纯 JVM 不接实现，:app 注入，null=不搭载）。
 */
class NativeToolFace(
    private val repo: BridgeRepositoryPort,
    private val now: () -> LocalDateTime = { LocalDateTime.now() },
    private val weather: (suspend (location: String) -> JsonObject?)? = null,
) : McpToolFace {

    private val handler = CommandHandler(repo)
    private val queries = ScheduleQueries(repo)

    // 工具 schemas：lib/mcp/tools.dart toolSchemas() 逐字直译（P5 Dart 退役平移件），
    // 类路径资源装载（:core 纯 JVM，测试同源可用）；装载失败属工程装配缺陷，直接抛。
    private val schemas: List<JsonObject> by lazy {
        val text = NativeToolFace::class.java.getResourceAsStream("/tool_schemas.json")
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: error("tool_schemas.json 资源缺失（:core:mcp 装配面）")
        (Json.parseToJsonElement(text) as JsonArray).map { it as JsonObject }
    }

    override fun schemas(): List<JsonObject> = schemas

    override suspend fun call(name: String, args: JsonObject): JsonElement = when (name) {
        "list_plans" -> listPlans(args)
        "add_plan" -> guarded("upsert_plan", args)
        "update_plan" -> guarded("update_plan", args)
        "get_settings" -> getSettings(args)
        "update_settings" -> guardedValues("update_settings", args)
        "update_fixed_slots" -> guarded("update_fixed_slots", args)
        "propose_schedule" -> guarded("propose_schedule", args)
        "adjust_blocks" -> guarded("adjust_blocks", args)
        "get_schedule" -> getSchedule(args)
        "get_history" -> getHistory(args)
        "suggest_user_setting" -> suggestUserSetting(args)
        "upsert_background" -> guarded("upsert_background", args)
        "merge_backgrounds" -> guarded("merge_backgrounds", args)
        "upsert_facts" -> guarded("upsert_facts", args)
        else -> throw McpRpcError("Unknown tool: $name", Err.INVALID_PARAMS)
    }

    // ---- 写工具（命令层唯一入口；领域拒绝 → McpRpcError 透传）----------------------

    private suspend fun guarded(op: String, args: JsonObject): JsonArray {
        val cmd = ScheduleCommandCodec.fromJson(buildJsonObject {
            put("op", op)
            for ((k, v) in args) put(k, v)
        })
        return try {
            textJson(handler.execute(cmd, CommandActor.AI).toJson())
        } catch (e: ActionException) {
            throw toMcpError(e)
        }
    }

    /** update_settings：args 整体即 values（Dart 'values': args 逐字）。 */
    private suspend fun guardedValues(op: String, args: JsonObject): JsonArray {
        val cmd = ScheduleCommandCodec.fromJson(buildJsonObject {
            put("op", op)
            put("values", args)
        })
        return try {
            textJson(handler.execute(cmd, CommandActor.AI).toJson())
        } catch (e: ActionException) {
            throw toMcpError(e)
        }
    }

    /** 命令层异常 → MCP 错误（Dart _guarded 逐字：code/hint/结构化 data 全量透传）。 */
    private fun toMcpError(e: ActionException): McpRpcError = McpRpcError(
        e.message, Err.INVALID_PARAMS,
        buildJsonObject {
            put("code", e.code)
            e.hint?.let { put("hint", it) }
            e.data?.let { d -> for ((k, v) in d) put(k, v) }
        },
    )

    private fun textJson(payload: JsonObject): JsonArray = JsonArray(listOf(
        buildJsonObject {
            put("type", "text")
            put("text", payload.toString())
        },
    ))

    // ---- 读工具（queries 聚合 + 搭载装配）-------------------------------------------

    /** list_plans：count + raw 凭证搭载 + plan 快照携 plan 背景（背景草案 §4 搭载）。 */
    private suspend fun listPlans(args: JsonObject): JsonArray {
        val includeArchived = (args["include_archived"] as? JsonPrimitive)?.booleanOrNull == true
        val parentId = strOf(args["parent_id"])
        val plans = (repo.listPlans(includeArchived, parentId) as? JsonArray)
            ?.mapNotNull { it as? JsonObject } ?: emptyList()
        // raw 凭证摘要搭载（fact 草案 §1.6/§1.7 消化 SOP）：「N 条原文待提炼」
        val rawFacts = (repo.artifactsByState(Artifact.STATE_RAW) as? JsonArray)
            ?.mapNotNull { it as? JsonObject } ?: emptyList()
        val allBackgrounds = (repo.listBackgrounds() as? JsonArray)
            ?.mapNotNull { it as? JsonObject } ?: emptyList()
        val payload = buildJsonObject {
            put("count", plans.size)
            put("raw_facts", buildJsonObject {
                put("count", rawFacts.size)
                put("items", JsonArray(rawFacts.map { f ->
                    buildJsonObject {
                        put("id", f["id"] ?: kotlinx.serialization.json.JsonNull)
                        put("title", f["title"] ?: kotlinx.serialization.json.JsonNull)
                        val pid = (f["plan_id"] as? JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }
                        if (pid != null) put("plan_id", pid)
                        val rawText = (f["payload"] as? JsonObject)?.get("raw_text")
                        if (rawText != null && rawText !is kotlinx.serialization.json.JsonNull) put("raw_text", rawText)
                    }
                }))
            })
            put("plans", JsonArray(plans.map { p ->
                buildJsonObject {
                    for ((k, v) in p) put(k, v)
                    put("backgrounds", JsonArray(allBackgrounds.filter { b ->
                        (b["scope"] as? JsonPrimitive)?.contentOrNull == Background.SCOPE_PLAN &&
                            (b["plan_id"] as? JsonPrimitive)?.contentOrNull ==
                            (p["id"] as? JsonPrimitive)?.contentOrNull
                    }))
                }
            }))
        }
        return textJson(payload)
    }

    /** get_settings：设置体 + today（作息日切割）+ fixed_slots + 全局背景治理装配。 */
    private suspend fun getSettings(args: JsonObject): JsonArray {
        val settings = queries.getSettings()
        val fixedSlots = repo.listFixedSlots()
        val wake = (settings["wake_time"] as? JsonPrimitive)?.longOrNull?.toInt() ?: 0
        val today = ScheduleDay.isoDate(ScheduleDay.scheduleDayOf(now(), wake))
        // 全局背景治理装配：Dart 侧 globalBackgroundsPayload 内部取 DateTime.now() 日历日
        val backgrounds = queries.globalBackgroundsPayload(ScheduleDay.isoDate(now().toLocalDate()))
        val payload = buildJsonObject {
            for ((k, v) in settings) put(k, v)
            put("today", today)
            put("fixed_slots", fixedSlots)
            put("global_backgrounds", backgrounds)
        }
        return textJson(payload)
    }

    /** get_schedule：days 钳制 + 天气投影搭载（获取失败静默降级——返回体不携带）。 */
    private suspend fun getSchedule(args: JsonObject): JsonArray {
        val days = clampInt(args["days"], 2, 1, 3)
        val schedule = queries.getSchedule(now = null, days = days)
        val location = (queries.getSettings()["weather_location"] as? JsonPrimitive)
            ?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.contentOrNull
        val payload = if (location != null && location.isNotEmpty() && weather != null) {
            val wf = weather?.invoke(location)
            if (wf != null) {
                buildJsonObject {
                    for ((k, v) in schedule) put(k, v)
                    put("weather", wf)
                }
            } else schedule
        } else schedule
        return textJson(payload)
    }

    /** get_history：days 钳制（恒聚合体，永不返回明细行）。 */
    private suspend fun getHistory(args: JsonObject): JsonArray {
        val days = clampInt(args["days"], 14, 1, 90)
        return textJson(queries.getHistory(now = null, days = days))
    }

    // ---- suggest_user_setting（建言通道：仅用户自设键五枚；直写 app_settings）-------

    private suspend fun suggestUserSetting(args: JsonObject): JsonArray {
        val key = strOf(args["key"])
        val reason = strOf(args["reason"])
        if (key == null || reason == null) {
            throw McpRpcError("suggest_user_setting 需要 key 与 reason", Err.INVALID_PARAMS)
        }
        val allowed = listOf(
            "user_rules", "weather_location", "exceptions", "identity_prompt", "theme_mode",
        )
        if (key !in allowed) {
            throw McpRpcError("key 必须为仅用户自设键之一：$allowed", Err.INVALID_PARAMS)
        }
        val raw = repo.settingsGet(SettingsKeys.AI_SETTING_SUGGESTIONS)
        val list = if (raw == null) {
            mutableListOf<JsonElement>()
        } else {
            (Json.parseToJsonElement(raw) as? JsonArray)?.toMutableList() ?: mutableListOf()
        }
        list.add(buildJsonObject {
            put("id", System.nanoTime().toString())
            put("key", key)
            val sv = strOf(args["suggested_value"])
            put("suggested_value", sv?.let { JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull)
            put("reason", reason)
            put("severity", strOf(args["severity"]) ?: "normal")
            put("created_at", LocalDateTime.now().toString())
            put("status", "pending")
        })
        repo.settingsSet(buildJsonObject {
            put(SettingsKeys.AI_SETTING_SUGGESTIONS, JsonArray(list).toString())
        })
        return textJson(buildJsonObject {
            put("ok", true)
            put("key", key)
        })
    }

    // ---- 助手 -----------------------------------------------------------------------

    private fun strOf(v: JsonElement?): String? =
        (v as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }

    private fun clampInt(v: JsonElement?, dflt: Int, min: Int, max: Int): Int {
        val n = (v as? JsonPrimitive)?.longOrNull?.toInt() ?: return dflt
        return if (n < min) min else if (n > max) max else n
    }
}
