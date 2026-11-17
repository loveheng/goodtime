package app.shiguang.core.action

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 领域拒绝码（lib/action/commands.dart ActionErrorCode 的 Kotlin 等价，P2 节点 2）。
 *
 * 语义：MCP 层原样放进 JSON-RPC error.data——让大模型不只是「看到报错」，
 * 而是能读懂原因并自我纠正。**逐字契约**（golden toolface 领域码值级对拍）。
 */
object ActionErrorCode {
    const val INVALID_REQUEST = "invalid_request"
    const val NOT_FOUND = "not_found"
    const val FORBIDDEN = "forbidden"
    const val VERSION_CONFLICT = "version_conflict"

    /** settings 未初始化（缺作息边界）——propose_schedule 整单硬拒（§6 校验铁律）。 */
    const val SETTINGS_MISSING = "settings_missing"

    /** propose_schedule 机械校验未过（逐条原因 + available_free_windows）。 */
    const val SCHEDULE_REJECTED = "schedule_rejected"

    /** scope=global 背景注入预算超限（条数>8 或 content 总字数>400；仅拦 ai actor）。 */
    const val BUDGET_EXCEEDED = "budget_exceeded"
}

/**
 * 领域拒绝唯一载体（Dart ActionException 等价）：
 * MCP 层包装为 McpRpcError(-32602, message, {code, hint?, ...data})——HTTP 层仍 200。
 */
class ActionException(
    override val message: String,
    val code: String = ActionErrorCode.INVALID_REQUEST,
    val hint: String? = null,

    /** 结构化附加：version_conflict 回 latest 快照；schedule_rejected 回逐条明细+空窗。 */
    val data: JsonObject? = null,
) : Exception(message)

/**
 * 命令执行唯一成功载体（Dart CommandResult 等价）：
 * snapshot = 落库后最新快照（JSON 口径）；data = 命令专属附加
 * （propose 回填 date/blocks、shift 回联级清单）。
 */
data class CommandResult(
    val op: String,
    val targetId: String? = null,
    val snapshot: JsonObject? = null,
    val note: String? = null,
    val data: JsonObject? = null,
) {
    /**
     * MCP 返回体（Dart CommandResult.toJson 逐字）：ok 恒 true、
     * id/note/snapshot/data 缺省键不携带（键集=toolface 向量钉样）。
     */
    fun toJson(): JsonObject = buildJsonObject {
        put("ok", true)
        put("op", op)
        if (targetId != null) put("id", targetId)
        if (note != null) put("note", note)
        if (snapshot != null) put("snapshot", snapshot)
        if (data != null) put("data", data)
    }
}

/**
 * 执行单条命令的宿主端口（Dart CommandHandler 的 Kotlin 侧端口——P2 语义平移前，
 * 实现由双轨期桥回 Dart 提供；平移后切 Kotlin 原生）。actor 由传输层注入
 * （human/ai，不可由命令载荷伪造）。
 */
interface CommandHandlerPort {
    suspend fun execute(command: JsonObject, actor: String): CommandResult
}
