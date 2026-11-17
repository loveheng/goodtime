package app.shiguang.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P2 黄金向量对拍执行器——领域模型三件（Plan/ScheduleBlock/Background，10 case）。
 * 对拍口径：JSON 串逐字（键序=序列化契约）；fromMap 往返 + 缺省口径断言。
 */
class GoldenModelsTest {

    private val json = Json { encodeDefaults = false }

    private fun loadSet(): JsonObject {
        val f = File("../../../test/golden/models_vectors.json")
        check(f.exists()) { "向量文件不存在: ${f.absolutePath}" }
        return Json.parseToJsonElement(f.readText()).jsonObject
    }

    private fun enc(e: JsonElement): String = json.encodeToString(JsonElement.serializer(), e)

    private fun toMapOf(case: JsonObject, key: String): JsonObject =
        case[key]!!.jsonObject.let { filedOrMap ->
            // input.fields / expect.map 均为 JSON 对象（TEXT 串字段原样字符串）
            JsonObject(filedOrMap.mapValues { (_, v) -> v })
        }

    @Test
    fun `models 向量全量对拍`() {
        val set = loadSet()
        val cases = set["cases"] as? JsonArray ?: error("cases 缺失")
        for (c in cases) {
            val case = c.jsonObject
            val id = case["id"]!!.jsonPrimitive.content
            val op = case["op"]!!.jsonPrimitive.content
            val input = case["input"]!!.jsonObject
            val expected = case["expect"]!!.jsonObject
            when (op) {
                "plan_to_map" -> {
                    val plan = planOf(input["fields"]!!.jsonObject)
                    assertEquals(enc(expected["map"]!!.jsonObject), enc(plan.toMap()), id)
                }
                "plan_from_map" -> {
                    val plan = Plan.fromMap(input["map"]!!.jsonObject)
                    assertEquals(enc(expected["map"]!!.jsonObject), enc(plan.toMap()), id)
                    expected["open_items"]?.let { items ->
                        val questions = (items as JsonArray).map { it.jsonPrimitive.content }
                        assertEquals(questions, plan.openItems.map { it.question }, id)
                    }
                    expected["energy_level"]?.let {
                        assertEquals(it.jsonPrimitive.content, plan.energyLevel, id)
                    }
                    expected["tool_required"]?.let {
                        assertEquals(it.jsonPrimitive.content, plan.toolRequired, id)
                    }
                    expected["importance"]?.let {
                        assertEquals(it.jsonPrimitive.content == "true", plan.importance, id)
                    }
                    expected["archived"]?.let {
                        assertEquals(it.jsonPrimitive.content == "true", plan.archived, id)
                    }
                    expected["version"]?.let {
                        assertEquals(it.jsonPrimitive.content.toLong(), plan.version, id)
                    }
                    expected["open_items_count"]?.let {
                        assertEquals(it.jsonPrimitive.content.toInt(), plan.openItems.size, id)
                    }
                }
                "block_to_map" -> {
                    val block = blockOf(input["fields"]!!.jsonObject)
                    assertEquals(enc(expected["map"]!!.jsonObject), enc(block.toMap()), id)
                    expected["start_min"]?.let {
                        assertEquals(it.jsonPrimitive.content.toLong(), block.startMin, id)
                    }
                    expected["end_min"]?.let {
                        assertEquals(it.jsonPrimitive.content.toLong(), block.endMin, id)
                    }
                }
                "block_from_map" -> {
                    val block = ScheduleBlock.fromMap(input["map"]!!.jsonObject)
                    assertEquals(enc(expected["map"]!!.jsonObject), enc(block.toMap()), id)
                    expected["status"]?.let {
                        assertEquals(it.jsonPrimitive.content, block.status, id)
                    }
                    expected["execution_quality"]?.let {
                        assertEquals(it.jsonPrimitive.content, block.executionQuality, id)
                    }
                    expected["version"]?.let {
                        assertEquals(it.jsonPrimitive.content.toLong(), block.version, id)
                    }
                    expected["pinned"]?.let {
                        assertEquals(it.jsonPrimitive.content == "true", block.pinned, id)
                    }
                    expected["postpone_count"]?.let {
                        assertEquals(
                            it.jsonPrimitive.content.toLong(), block.postponeCount, id,
                        )
                    }
                    expected["is_day_spark"]?.let {
                        assertEquals(it.jsonPrimitive.content == "true", block.isDaySpark, id)
                    }
                }
                "background_to_map" -> {
                    val bg = bgOf(input["fields"]!!.jsonObject)
                    assertEquals(enc(expected["map"]!!.jsonObject), enc(bg.toMap()), id)
                }
                "background_from_map" -> {
                    val bg = Background.fromMap(input["map"]!!.jsonObject)
                    assertEquals(enc(expected["map"]!!.jsonObject), enc(bg.toMap()), id)
                    expected["captured_by"]?.let {
                        assertEquals(it.jsonPrimitive.content, bg.capturedBy, id)
                    }
                    expected["applicable_dates_present"]?.let {
                        assertEquals(
                            it.jsonPrimitive.content == "true",
                            bg.toMap().containsKey("applicable_dates"),
                            id,
                        )
                    }
                    expected["tags_count"]?.let {
                        assertEquals(it.jsonPrimitive.content.toInt(), bg.tags.size, id)
                    }
                    expected["tags"]?.let {
                        assertEquals(
                            strListOf(it),
                            bg.tags,
                            id,
                        )
                    }
                    expected["applicable_dates"]?.let {
                        assertEquals(
                            strListOf(it),
                            bg.applicableDates,
                            id,
                        )
                    }
                    expected["version"]?.let {
                        assertEquals(it.jsonPrimitive.content.toLong(), bg.version, id)
                    }
                }
                else -> error("未知 op: $op（向量集与执行器失配）")
            }
        }
        assertTrue(cases.size == 10, "case 数应 10，实际 ${cases.size}")
    }

    private fun planOf(f: JsonObject): Plan = Plan(
        id = f["id"]?.jsonPrimitive?.contentOrNull,
        title = f["title"]!!.jsonPrimitive.content,
        spec = f["spec"]?.jsonPrimitive?.contentOrNull,
        notes = f["notes"]?.jsonPrimitive?.contentOrNull,
        openItems = Plan.decodeOpenItems(f["open_items"]?.jsonPrimitive?.contentOrNull),
        minViableAction = f["min_viable_action"]?.jsonPrimitive?.contentOrNull,
        energyLevel = f["energy_level"]?.jsonPrimitive?.contentOrNull ?: Plan.ENERGY_LIGHT,
        toolRequired = f["tool_required"]?.jsonPrimitive?.contentOrNull ?: Plan.TOOL_ANYWHERE,
        rewardSpec = f["reward_spec"]?.jsonPrimitive?.contentOrNull,
        importance = (f["importance"]?.jsonPrimitive?.content) == "1",
        deadline = f["deadline"]?.jsonPrimitive?.contentOrNull,
        estimate = f["estimate"]?.jsonPrimitive?.longOrNull,
        parentId = f["parent_id"]?.jsonPrimitive?.contentOrNull,
        archived = (f["archived"]?.jsonPrimitive?.content) == "1",
        createdAt = f["created_at"]?.jsonPrimitive?.longOrNull,
        updatedAt = f["updated_at"]?.jsonPrimitive?.longOrNull,
        version = f["version"]?.jsonPrimitive?.longOrNull ?: 0,
    )

    private fun blockOf(f: JsonObject): ScheduleBlock = ScheduleBlock(
        id = f["id"]?.jsonPrimitive?.contentOrNull,
        date = f["date"]!!.jsonPrimitive.content,
        startMin = f["start_min"]!!.jsonPrimitive.long,
        endMin = f["end_min"]!!.jsonPrimitive.long,
        planId = f["plan_id"]?.jsonPrimitive?.contentOrNull,
        label = f["label"]?.jsonPrimitive?.contentOrNull,
        source = f["source"]!!.jsonPrimitive.content,
        pinned = (f["pinned"]?.jsonPrimitive?.longOrNull ?: 0) != 0L,
        status = f["status"]?.jsonPrimitive?.contentOrNull ?: ScheduleBlock.STATUS_PROPOSED,
        postponeCount = f["postpone_count"]?.jsonPrimitive?.longOrNull ?: 0,
        executionQuality = f["execution_quality"]?.jsonPrimitive?.contentOrNull
            ?: ScheduleBlock.QUALITY_FULL,
        isDaySpark = (f["is_day_spark"]?.jsonPrimitive?.longOrNull ?: 0) != 0L,
        isCelebration = (f["is_celebration"]?.jsonPrimitive?.longOrNull ?: 0) != 0L,
        createdAt = f["created_at"]?.jsonPrimitive?.longOrNull,
        updatedAt = f["updated_at"]?.jsonPrimitive?.longOrNull,
        version = f["version"]?.jsonPrimitive?.longOrNull ?: 0,
    )

    private fun bgOf(f: JsonObject): Background = Background(
        id = f["id"]?.jsonPrimitive?.contentOrNull,
        scope = f["scope"]!!.jsonPrimitive.content,
        planId = f["plan_id"]?.jsonPrimitive?.contentOrNull,
        content = f["content"]!!.jsonPrimitive.content,
        rawSourceText = f["raw_source_text"]?.jsonPrimitive?.contentOrNull,
        tags = strListOf(f["tags"]),
        applicableDates = f["applicable_dates"]?.let { strListOf(it) },
        source = f["source"]!!.jsonPrimitive.content,
        capturedBy = f["captured_by"]?.jsonPrimitive?.contentOrNull ?: Background.CAPTURED_BY_ME,
        createdAt = f["created_at"]?.jsonPrimitive?.longOrNull,
        updatedAt = f["updated_at"]?.jsonPrimitive?.longOrNull,
        version = f["version"]?.jsonPrimitive?.longOrNull ?: 0,
    )

    private fun strListOf(e: JsonElement?): List<String> = when (e) {
        null -> emptyList()
        is JsonArray -> e.map { it.jsonPrimitive.content }
        is JsonPrimitive -> strListOf(e.content)
        else -> emptyList()
    }

    private fun strListOf(raw: String): List<String> = try {
        (Json.parseToJsonElement(raw) as JsonArray).map { it.jsonPrimitive.content }
    } catch (_: Exception) {
        emptyList()
    }
}
