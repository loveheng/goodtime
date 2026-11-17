package app.shiguang.core.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P1 黄金向量对拍执行器——mcp-rejection 集（17 case）Kotlin 侧逐 case 对拍。
 *
 * 对拍口径（rules.message_policy）：**error.message 为参考文案不比**（剔除后深比），
 * code / data / id / result 逐字比对（instructions 政策精华整段逐字契约）。
 * fake 工具面按向量 input 语义构造（echo / reject_domain / boom，同 Dart 执行器形态）。
 */
class GoldenMcpRejectionTest {

    private fun loadSet(): JsonObject {
        // 模块工作目录 = android/core/mcp → 仓库根 = ../../../
        val f = File("../../../test/golden/mcp_rejection_vectors.json")
        check(f.exists()) { "向量文件不存在: ${f.absolutePath}（工作目录须为 :core:mcp 模块根）" }
        return Json.parseToJsonElement(f.readText()).jsonObject
    }

    /** fake 工具面（与 Dart 执行器同形；行为语义见向量集 rules 节）。 */
    private class FakeToolFace : McpToolFace {
        override fun schemas(): List<JsonObject> = listOf(
            buildJsonObject {
                put("name", "fake_tool_a")
                put("description", "alpha")
                put("inputSchema", buildJsonObject { put("type", "object") })
            },
            buildJsonObject {
                put("name", "fake_tool_b")
                put("description", "beta")
                put("inputSchema", buildJsonObject { put("type", "object") })
            },
        )

        override suspend fun call(name: String, args: JsonObject): JsonElement = when (name) {
            "echo" -> JsonArray(listOf(buildJsonObject {
                put("type", "text")
                put("text", "echo:${(args["msg"] as? JsonPrimitive)?.contentOrNull ?: ""}")
            }))
            "reject_domain" -> throw McpRpcError(
                "提案被拒", Err.INVALID_PARAMS,
                buildJsonObject {
                    put("code", "schedule_rejected")
                    put(
                        "available_free_windows",
                        Json.parseToJsonElement("[{\"start_min\":660,\"end_min\":720}]"),
                    )
                },
            )
            "boom" -> throw IllegalStateException("boom")
            else -> throw McpRpcError("Unknown tool: $name", Err.INVALID_PARAMS)
        }
    }

    private fun normalize(resp: String): JsonObject =
        if (resp.isEmpty()) buildJsonObject { put("empty", true) }
        else Json.parseToJsonElement(resp).jsonObject

    /** rules.message_policy：剔除 error.message 后深比。 */
    private fun stripMessage(frame: JsonObject): JsonObject {
        val err = frame["error"] as? JsonObject ?: return frame
        val clone = frame.toMutableMap()
        clone["error"] = JsonObject(err.toMutableMap().apply { remove("message") })
        return JsonObject(clone)
    }

    @Test
    fun `mcp-rejection 向量全量对拍`() {
        val set = loadSet()
        val cases = set["cases"] as? JsonArray ?: error("cases 缺失")
        for (c in cases) {
            val case = c.jsonObject
            val id = case["id"]!!.jsonPrimitive.content
            val op = case["op"]!!.jsonPrimitive.content
            val input = case["input"]!!.jsonObject
            val expected = case["expect"]!!.jsonObject
            when (op) {
                "negotiate" -> {
                    val got = McpProtocol.negotiate(input["requested"])
                    assertEquals(expected["version"]!!.jsonPrimitive.content, got, id)
                }
                "handle" -> {
                    val resp = runBlocking {
                        McpServer(toolFace = FakeToolFace())
                            .handleJsonRpc(input["raw_body"]!!.jsonPrimitive.content)
                    }
                    if (expected.containsKey("empty")) {
                        assertTrue(resp.isEmpty(), "$id: 通知必须返回空串（传输层回 202）")
                        continue
                    }
                    val actual = normalize(resp)
                    assertEquals(
                        stripMessage(Json.parseToJsonElement(Json.encodeToString(
                            JsonObject.serializer(), expected)).jsonObject),
                        stripMessage(actual),
                        id,
                    )
                }
                else -> error("未知 op: $op（向量集与执行器失配）")
            }
        }
    }
}
