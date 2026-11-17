package app.shiguang.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P2 试点对拍执行器——model-json-artifact 集（14 case）。
 *
 * 对拍口径：**JSON 串逐字（键序=序列化契约）**——actual 经 encodeToString（紧凑序）
 * 与文件 expect 的再序列化串比对（kotlinx JsonObject 迭代序=解析序，两侧同源保序）。
 */
class GoldenModelJsonTest {

    private val json = Json { encodeDefaults = false }

    private fun loadSet(): JsonObject {
        val f = File("../../../test/golden/model_json_vectors.json")
        check(f.exists()) { "向量文件不存在: ${f.absolutePath}" }
        return Json.parseToJsonElement(f.readText()).jsonObject
    }

    private fun encoded(e: JsonElement): String =
        json.encodeToString(JsonElement.serializer(), e)

    private fun artifactOf(fields: JsonObject): Artifact = Artifact(
        id = fields["id"]?.jsonPrimitive?.contentOrNull,
        category = fields["category"]!!.jsonPrimitive.content,
        sourceKind = fields["source_kind"]!!.jsonPrimitive.content,
        state = fields["state"]!!.jsonPrimitive.content,
        origin = fields["origin"]!!.jsonPrimitive.content,
        title = fields["title"]!!.jsonPrimitive.content,
        badge = fields["badge"]?.jsonPrimitive?.contentOrNull,
        payload = fields["payload"]!!.jsonObject,
        attachments = (fields["attachments"] as? JsonArray) ?: JsonArray(emptyList()),
        capturedBy = fields["captured_by"]?.jsonPrimitive?.contentOrNull ?: Artifact.CAPTURED_BY_ME,
        planId = fields["plan_id"]?.jsonPrimitive?.contentOrNull,
        createdAt = fields["created_at"]?.jsonPrimitive?.content?.toLongOrNull(),
        updatedAt = fields["updated_at"]?.jsonPrimitive?.content?.toLongOrNull(),
        version = fields["version"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0,
    )

    @Test
    fun `model-json 向量全量对拍`() {
        val set = loadSet()
        val cases = set["cases"] as? JsonArray ?: error("cases 缺失")
        for (c in cases) {
            val case = c.jsonObject
            val id = case["id"]!!.jsonPrimitive.content
            val op = case["op"]!!.jsonPrimitive.content
            val input = case["input"]!!.jsonObject
            val expected = case["expect"]!!.jsonObject
            when (op) {
                "payload_encode" -> {
                    val payload = input["payload"]!!.jsonObject
                    val got = Artifact.encodePayload(payload.toMutableMap())
                    assertEquals(expected["json"]!!.jsonPrimitive.content, got, id)
                }
                "payload_decode" -> {
                    val raw = input["raw"] as? JsonPrimitive
                    val got = Artifact.decodePayload(raw?.contentOrNull)
                    assertEquals(expected["payload"], got, id)
                }
                "payload_with_refs" -> {
                    val payload = input["payload"]!!.jsonObject
                    val planIdEl = input["plan_id"]
                    val arg = when (planIdEl) {
                        null -> PlanIdArg.Keep
                        is JsonNull -> PlanIdArg.Remove
                        else -> PlanIdArg.Set(planIdEl.jsonPrimitive.content)
                    }
                    val got = Artifact.payloadWithRefs(payload, arg)
                    assertEquals(expected["payload"], got, id)
                }
                "artifact_to_map" -> {
                    val got = artifactOf(input["fields"]!!.jsonObject).toMap()
                    // 键序敏感：JSON 串级比对（双方紧凑序；expect 的 map 解析序=文件序=Dart toMap 序）
                    assertEquals(
                        encoded(Json.parseToJsonElement(json.encodeToString(
                            JsonElement.serializer(), expected["map"]!!)).jsonObject),
                        encoded(got),
                        id,
                    )
                }
                "artifact_from_map" -> {
                    val entity = Artifact.fromMap(input["map"]!!.jsonObject)
                    val roundMap = entity.toMap()
                    // 对拍 Dart 产出钉样（expected.map）——fromMap 缺省补全后 toMap 比输入
                    // 多缺省键（badge:null/captured_by/version）是正常语义，勿与输入比幂等。
                    expected["title"]?.let { assertEquals(it.jsonPrimitive.content, entity.title, id) }
                    expected["payload"]?.let { assertEquals(it, entity.payload, id) }
                    expected["badge"]?.let {
                        assertEquals(it.jsonPrimitive.contentOrNull, entity.badge, id)
                    }
                    expected["captured_by"]?.let {
                        assertEquals(it.jsonPrimitive.content, entity.capturedBy, id)
                    }
                    expected["version"]?.let {
                        assertEquals(it.jsonPrimitive.content.toLong(), entity.version, id)
                    }
                    expected["plan_id"]?.let {
                        assertEquals(it.jsonPrimitive.contentOrNull, entity.planId, id)
                    }
                    expected["map"]?.let {
                        assertEquals(encoded(it.jsonObject), encoded(roundMap), "$id(map)")
                    }
                }
                else -> error("未知 op: $op（向量集与执行器失配）")
            }
        }
        assertTrue(cases.size == 14, "case 数应 14，实际 ${cases.size}")
    }
}
