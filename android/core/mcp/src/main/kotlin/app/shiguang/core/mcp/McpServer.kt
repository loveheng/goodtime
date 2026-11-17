package app.shiguang.core.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * JSON-RPC 2.0 / MCP 协议常量与版本协商（迁移契约：schedule-app §12，
 * golden mcp-rejection rules.initialize）。
 *
 * 协商语义：请求版本 ∈ 支持列表 → 原样回显；否则（未知/非字符串）→ 回最新版。
 */
object McpProtocol {
    const val LATEST_VERSION = "2025-06-18"
    val SUPPORTED_VERSIONS = listOf("2025-06-18", "2025-03-26", "2024-11-05")

    fun negotiate(requested: JsonElement?): String {
        val v = (requested as? JsonPrimitive)?.contentOrNull
        return if (v != null && v in SUPPORTED_VERSIONS) v else LATEST_VERSION
    }
}

/** JSON-RPC 错误码（rules.error_codes：标准码族；-32001 传输鉴权与 -32000 Binder 层不属内核）。 */
object Err {
    const val PARSE = -32700
    const val INVALID_REQUEST = -32600
    const val METHOD_NOT_FOUND = -32601
    const val INVALID_PARAMS = -32602
    const val INTERNAL = -32603
}

/** 领域拒绝异常（rules.handle_rules：toolFace 抛出 → error{code,message,data} 原样透传）。 */
class McpRpcError(override val message: String, val code: Int, val data: JsonObject? = null) :
    Exception(message)

/** 工具面端口：tools/list schema 与 tools/call 执行由宿主注入（内核零业务耦合）。 */
interface McpToolFace {
    fun schemas(): List<JsonObject>
    suspend fun call(name: String, args: JsonObject): JsonElement
}

/**
 * 传输无关 MCP 内核：输入 JSON-RPC 请求体字符串，输出响应体字符串。
 *
 * 行为契约（golden mcp-rejection rules.handle_rules，逐条对拍向量）：
 * - 坏 JSON → error(-32700, id=null)；
 * - 非 JSON-RPC 对象消息（数组/标量）→ -32600(id=null)；id 在场但 method 非 String → -32600(id 回显)；
 * - id==null 视为通知 → 返回空串（传输层回 202；method 是否 String 不影响通知判定）；
 * - 未知方法 → -32601；
 * - tools/call 缺 tool name → -32602；
 * - toolFace.call 抛 [McpRpcError] → error{code,message,data} 原样透传；抛其它异常 → -32603 兜底；
 * - 成功 → {jsonrpc, id, result}；错误帧一律回显请求 id。
 * - initialize：negotiate + capabilities + serverInfo + instructions（政策精华整段为逐字契约）；
 *   instructionsProvider 非空时以其返回覆盖（null 回落内置）。
 */
class McpServer(
    private val toolFace: McpToolFace,
    private val instructionsProvider: (suspend () -> String?)? = null,

    /**
     * AI 建言推送回调（app_shell_sheets.md §A 转正清单）：suggest_user_setting
     * 工具调用成功后以原始 args 回调宿主（App 壳层弹 SnackBar 8s+「去设置」）。
     * 只读钩子——handleJsonRpc 返回语义零变化（P0 向量不受影响）。
     */
    var onSuggestion: ((args: JsonObject) -> Unit)? = null,
) {

    companion object {
        /** 政策精华（§7 行为层第一层）：每次 initialize 必读——整段逐字契约，Kotlin 逐字平移。 */
        const val BUILTIN_INSTRUCTIONS =
            "你是「拾光」的排程师。拾光是用户本地持有的日程台账：App=台账+裁判（存储/状态机/机械校验），" +
                "你=排程师（读数据、权衡、提案），人=终审（确认/否决/微调）。\n" +
                "政策精华：\n" +
                "1. 用户输入的是概念，日程装的是行动——只排叶子行动（动词开头、一次能做完、时长明确），不排概念；" +
                "父计划被要求排期时先下钻/先拆解。\n" +
                "2. 排程前必须到达明确态（spec 非空且无未决 open_items）；有未决问题先澄清" +
                "（单轮最多 2–3 问、先时间后细节；≤30 分钟或琐事类免澄清，直接给叶子行动）。\n" +
                "3. 和平条款：human 块与 pinned 块是不可穿透的固定占用，必须绕行；" +
                "propose_schedule 只替换你自己的未确认提案块。\n" +
                "4. 先读后排：propose_schedule 前必须先 get_schedule 读当天 human/pinned 分布与空窗；" +
                "被拒时按逐条 reason 修正整单重试，或把块放进返回的 available_free_windows。\n" +
                "5. update_plan 等 version 冲突时，直接在错误返回的 latest 快照上合并你的修改重试，不要重拉。\n" +
                "6. MCP 第一定律：永远假设用户的生活处于布朗运动中。排程不是为了占满时间，" +
                "而是为了在混乱的洪流中，用最低的认知摩擦，为用户捞起一颗珍珠。\n" +
                "7. 设置修改权限边界：你可经 update_settings 改动数值类键与 today_energy（今日电量档）；" +
                "但 user_rules（显式偏好）、weather_location（城市）、exceptions（例外日）、identity_prompt" +
                "（身份画像）、theme_mode（外观）仅用户本人在 app「设置」页自设，你无权写入（会被拒）。" +
                "当你判断这些用户自设键确实需要调整时，不要在回复里含糊带过，也不要尝试写入——" +
                "请明确、具体地建议用户：「建议你在 app 设置页修改 XX（原因：…）」，由用户决断。"
    }

    private val json = Json { encodeDefaults = false }

    /** 通知判定（handle_rules：只看 id==null——method 类型不影响）。 */
    private fun isNotification(msg: JsonObject): Boolean = msg["id"] == null

    suspend fun handleJsonRpc(rawBody: String): String {
        val msg: JsonElement = try {
            Json.parseToJsonElement(rawBody)
        } catch (_: Exception) {
            return jsonEncode(rpcError(null, Err.PARSE, "JSON 解析失败"))
        }
        if (msg !is JsonObject) {
            return jsonEncode(rpcError(null, Err.INVALID_REQUEST, "仅支持单条 JSON-RPC 消息"))
        }
        val id = msg["id"]
        if (id == null) return "" // 通知：无需应答
        val method = msg["method"]
        if (method !is JsonPrimitive || method.contentOrNull == null) {
            return jsonEncode(rpcError(id, Err.INVALID_REQUEST, "缺少 method"))
        }
        return try {
            val result = dispatch(method.contentOrNull!!, msg["params"])
            jsonEncode(buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("result", result)
            })
        } catch (e: McpRpcError) {
            jsonEncode(buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("error", errorOf(e.code, e.message, e.data))
            })
        } catch (e: Exception) {
            jsonEncode(rpcError(id, Err.INTERNAL, "工具执行失败: $e"))
        }
    }

    private suspend fun dispatch(method: String, params: JsonElement?): JsonElement = when (method) {
        "initialize" -> {
            val p = params as? JsonObject
            buildJsonObject {
                put("protocolVersion", McpProtocol.negotiate(p?.get("protocolVersion")))
                put("capabilities", buildJsonObject {
                    put("tools", buildJsonObject { put("listChanged", false) })
                })
                put("serverInfo", buildJsonObject {
                    put("name", "shiguang")
                    put("version", "1.0.0")
                })
                val instructions = instructionsProvider?.invoke() ?: BUILTIN_INSTRUCTIONS
                put("instructions", instructions)
            }
        }
        "ping" -> buildJsonObject { }
        "tools/list" -> buildJsonObject {
            put("tools", JsonArray(toolFace.schemas()))
        }
        "tools/call" -> {
            val p = params as? JsonObject
            val name = p?.get("name") as? JsonPrimitive
            if (name?.contentOrNull == null) {
                throw McpRpcError("缺少 tool name", Err.INVALID_PARAMS)
            }
            val args = (p?.get("arguments") as? JsonObject) ?: buildJsonObject { }
            val content = toolFace.call(name.contentOrNull!!, args)
            if (name.contentOrNull == "suggest_user_setting") onSuggestion?.invoke(args)
            buildJsonObject {
                put("content", content)
                put("isError", false)
            }
        }
        "resources/list" -> buildJsonObject { put("resources", EMPTY_ARRAY) }
        "prompts/list" -> buildJsonObject { put("prompts", EMPTY_ARRAY) }
        else -> throw McpRpcError("未知方法: $method", Err.METHOD_NOT_FOUND)
    }

    private fun rpcError(id: JsonElement?, code: Int, message: String): JsonObject =
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id ?: JsonNull)
            put("error", errorOf(code, message, null))
        }

    private fun errorOf(code: Int, message: String, data: JsonObject?): JsonObject =
        buildJsonObject {
            put("code", code)
            put("message", message)
            if (data != null) put("data", data)
        }

    private fun jsonEncode(e: JsonElement): String = json.encodeToString(JsonElement.serializer(), e)
}

private val EMPTY_ARRAY: JsonElement = Json.parseToJsonElement("[]")
