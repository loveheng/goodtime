package app.shiguang.core.action

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P2 黄金向量对拍执行器——command-parse 集（12 case）Kotlin 侧对拍。
 * 对拍口径：成功 case 断言 op/target_id/关键字段；拒绝 case 断言抛 ActionException
 * 且 message/hint 含锚文本（异常类非契约——拒绝行为是）。
 */
class GoldenCommandParseTest {

    private fun loadSet(): JsonObject {
        val f = File("../../../test/golden/command_parse_vectors.json")
        check(f.exists()) { "向量文件不存在: ${f.absolutePath}" }
        return Json.parseToJsonElement(f.readText()).jsonObject
    }

    @Test
    fun `command-parse 向量全量对拍`() {
        val set = loadSet()
        val cases = set["cases"] as? JsonArray ?: error("cases 缺失")
        for (c in cases) {
            val case = c.jsonObject
            val id = case["id"]!!.jsonPrimitive.content
            val input = case["input"]!!.jsonObject
            val expected = case["expect"]!!.jsonObject
            val body = input["json"]!!.jsonObject
            if (expected["status"]?.jsonPrimitive?.content == "reject_argument") {
                val e = try {
                    ScheduleCommandCodec.fromJson(body)
                    error("$id: 预期拒绝但成功")
                } catch (ae: ActionException) {
                    ae
                }
                expected["error_contains"]?.let {
                    assertTrue(e.message.contains(it.jsonPrimitive.content), "$id: ${e.message}")
                }
                expected["error_hint_contains"]?.let {
                    assertTrue(e.hint?.contains(it.jsonPrimitive.content) == true, "$id hint=${e.hint}")
                }
                continue
            }
            val cmd = ScheduleCommandCodec.fromJson(body)
            assertEquals(expected["op"]!!.jsonPrimitive.content, cmd.op, id)
            expected["target_id"]?.let {
                assertEquals(it.jsonPrimitive.contentOrNull, cmd.targetId, id)
            }
            expected["expected_version"]?.let {
                assertEquals(it.jsonPrimitive.longOrNull, cmd.expectedVersion, id)
            }
            when (cmd) {
                is ScheduleCommand.QuickCapture -> {
                    expected["title"]?.let {
                        assertEquals(it.jsonPrimitive.content, cmd.title, id)
                    }
                    expected["importance"]?.let {
                        assertEquals(it.jsonPrimitive.content == "true", cmd.importance, id)
                    }
                    expected["deadline"]?.let {
                        assertEquals(it.jsonPrimitive.contentOrNull, cmd.deadline, id)
                    }
                }
                is ScheduleCommand.UpdatePlan -> {
                    expected["title"]?.let {
                        assertEquals(it.jsonPrimitive.content, cmd.title, id)
                    }
                    assertEquals(
                        (expected["importance"] as? JsonPrimitive)?.contentOrNull == "true",
                        cmd.importance ?: false,
                        id,
                    )
                    assertEquals(
                        (expected["archived"] as? JsonPrimitive)?.contentOrNull == "true",
                        cmd.archived ?: false,
                        id,
                    )
                }
                is ScheduleCommand.AdjustBlocks -> {
                    expected["action"]?.let {
                        assertEquals(it.jsonPrimitive.content, cmd.action, id)
                    }
                    expected["start_min"]?.let {
                        assertEquals(it.jsonPrimitive.longOrNull, cmd.startMin, id)
                    }
                    expected["end_min"]?.let {
                        assertEquals(it.jsonPrimitive.longOrNull, cmd.endMin, id)
                    }
                    expected["pinned"]?.let {
                        assertEquals(it.jsonPrimitive.content == "true", cmd.pinned, id)
                    }
                }
                is ScheduleCommand.UpsertBackground -> {
                    expected["scope"]?.let {
                        assertEquals(it.jsonPrimitive.content, cmd.scope, id)
                    }
                    expected["content"]?.let {
                        assertEquals(it.jsonPrimitive.content, cmd.content, id)
                    }
                }
                is ScheduleCommand.ShiftBlock -> {
                    expected["minutes"]?.let {
                        assertEquals(it.jsonPrimitive.longOrNull, cmd.minutes, id)
                    }
                }
                else -> {}
            }
        }
        assertTrue(cases.size == 12, "case 数应 12，实际 ${cases.size}")
    }
}
