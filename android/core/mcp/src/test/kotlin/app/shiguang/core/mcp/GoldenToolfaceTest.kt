package app.shiguang.core.mcp

import app.shiguang.core.action.NativeSqlRepository
import app.shiguang.core.model.JdbcSqlStore
import app.shiguang.core.model.ShiguangSchema
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P2 黄金向量对拍执行器——mcp-toolface 集（23 case）Kotlin 侧逐 case 对拍
 * （工具面原生切换件 NativeToolFace 的行为闸门）。
 *
 * 对拍口径（向量集 rules 节，同 Dart 基准执行器 golden_toolface_test）：
 * - tool_call = 值泛化结构深比（uuid/date/iso_datetime/timestamp_ms 泛化为类型标记——
 *   两侧动态值天然不同，值级对拍仅对枚举/计数/领域码）；
 * - tool_reject = code + data.code + data 键集值级钉样（message 参考文案不比）；
 * - args 叶子 {"$ref": "<seed索引>.<点路径>"} 解引用到 seed 返回 JSON（独立副本，
 *   规则=rules.ref_rule）。
 * 向量文件为只读契约（dev-loop 测试写保护），实现漂移唯一出路修实现。
 */
class GoldenToolfaceTest {

    private val vectors: JsonObject by lazy {
        // 模块工作目录 = android/core/mcp → 仓库根 = ../../../
        val f = File("../../../test/golden/mcp_toolface_vectors.json")
        check(f.exists()) { "向量文件不存在: ${f.absolutePath}（工作目录须为 :core:mcp 模块根）" }
        Json.parseToJsonElement(f.readText()).jsonObject
    }

    private fun case(id: String): JsonObject =
        vectors["cases"]!!.jsonArray.first { it.jsonObject["id"]!!.jsonPrimitive.content == id }.jsonObject

    // ---- 值泛化（独立副本；规则=rules.generalize_rule） ------------------------------

    private val uuidRe =
        Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    private val dateRe = Regex("^\\d{4}-\\d{2}-\\d{2}$")
    private val isoDtRe = Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}")

    private fun generalize(v: JsonElement): JsonElement = when (v) {
        is JsonPrimitive -> when {
            v.isString -> when {
                uuidRe.matches(v.content) -> JsonPrimitive("uuid")
                isoDtRe.matches(v.content) -> JsonPrimitive("iso_datetime")
                dateRe.matches(v.content) -> JsonPrimitive("date")
                else -> v
            }
            v.booleanOrNull != null -> v
            else -> {
                val n = v.longOrNull
                if (n != null && n > 1_000_000_000_000) JsonPrimitive("timestamp_ms") else v
            }
        }
        is JsonArray -> JsonArray(v.map { generalize(it) })
        is JsonObject -> JsonObject(v.mapValues { (_, e) -> generalize(e) })
    }

    /** args 叶子 $ref 解引用（独立副本；规则=rules.ref_rule）。 */
    private fun deref(v: JsonElement, seedResults: List<JsonElement>): JsonElement {
        if (v is JsonObject && v.size == 1 && v["\$ref"] is JsonPrimitive) {
            val parts = (v["\$ref"] as JsonPrimitive).content.split(".").toMutableList()
            val idx = parts.removeAt(0).toInt()
            var cur: JsonElement = seedResults[idx]
            for (seg in parts) {
                cur = (cur as? JsonObject)?.get(seg) ?: kotlinx.serialization.json.JsonNull
            }
            return cur
        }
        if (v is JsonArray) return JsonArray(v.map { deref(it, seedResults) })
        if (v is JsonObject) return JsonObject(v.mapValues { (_, e) -> deref(e, seedResults) })
        return v
    }

    /** 每 case 新库隔离（Dart 执行器 setUp 清库 5 表的同效口径）。 */
    private suspend fun freshFace(): NativeToolFace {
        val store = JdbcSqlStore.inMemory()
        ShiguangSchema.createAll(store)
        val repo = NativeSqlRepository(store)
        // 钉时刻（生产=系统钟）：向量对日期值泛化，时刻只影响确定性
        return NativeToolFace(repo, now = { LocalDateTime.parse("2026-11-14T09:00:00") })
    }

    private suspend fun runSeed(face: NativeToolFace, seed: JsonElement?): List<JsonElement> {
        val results = mutableListOf<JsonElement>()
        for (s in (seed as? JsonArray) ?: JsonArray(emptyList())) {
            val o = s.jsonObject
            val content = face.call(
                o["tool"]!!.jsonPrimitive.content,
                deref(o["args"] ?: JsonObject(emptyMap()), results) as JsonObject,
            )
            results.add(parseText(content))
        }
        return results
    }

    /** content=[{type:text,text:<json>}] → 解码 JSON 载荷（rules.isError：本集只钉 text 内载荷）。 */
    private fun parseText(content: JsonElement): JsonElement {
        val text = ((content as JsonArray)[0] as JsonObject)["text"]!!.jsonPrimitive.content
        return Json.parseToJsonElement(text)
    }

    private suspend fun callTool(face: NativeToolFace, input: JsonObject, seeds: List<JsonElement>): JsonElement =
        face.call(
            input["tool"]!!.jsonPrimitive.content,
            deref(input["args"] ?: JsonObject(emptyMap()), seeds) as JsonObject,
        )

    private fun assertToolCall(id: String) = runTest {
        val c = case(id)
        val face = freshFace()
        val seeds = runSeed(face, c["input"]!!.jsonObject["seed"])
        val payload = parseText(callTool(face, c["input"]!!.jsonObject, seeds))
        val expected = c["expect"]!!.jsonObject["payload"]!!
        assertEquals(generalize(expected), generalize(payload), "tool_call $id 值泛化结构失配")
    }

    private fun assertToolReject(id: String) = runTest {
        val c = case(id)
        val face = freshFace()
        val seeds = runSeed(face, c["input"]!!.jsonObject["seed"])
        val e = runCatching { callTool(face, c["input"]!!.jsonObject, seeds) }.exceptionOrNull()
        assertTrue(e is McpRpcError, "tool_reject $id 预期 McpRpcError，got: $e")
        val expect = c["expect"]!!.jsonObject
        assertEquals(expect["code"]!!.jsonPrimitive.longOrNull, (e as McpRpcError).code.toLong(), "code 失配")
        val data = e.data
        val wantDataCode = expect["data_code"]?.jsonPrimitive?.contentOrNull
        if (wantDataCode != null) {
            assertEquals(
                wantDataCode,
                (data as? JsonObject)?.get("code")?.jsonPrimitive?.contentOrNull,
                "data.code 失配",
            )
        }
        val wantKeys = expect["data_keys"] as? JsonArray
        if (wantKeys != null) {
            assertTrue(data is JsonObject, "data 应为对象: $data")
            assertEquals(
                wantKeys.map { it.jsonPrimitive.content }.sorted(),
                (data as JsonObject).keys.sorted(),
                "data 键集失配",
            )
        }
    }

    // ---- tool_call（15） ------------------------------------------------------------

    @Test fun `tf_list_plans_empty`() = assertToolCall("tf_list_plans_empty")
    @Test fun `tf_add_plan_ok`() = assertToolCall("tf_add_plan_ok")
    @Test fun `tf_list_plans_one`() = assertToolCall("tf_list_plans_one")
    @Test fun `tf_update_plan_ok`() = assertToolCall("tf_update_plan_ok")
    @Test fun `tf_get_settings_seeded`() = assertToolCall("tf_get_settings_seeded")
    @Test fun `tf_update_fixed_slots_ok`() = assertToolCall("tf_update_fixed_slots_ok")
    @Test fun `tf_propose_ok`() = assertToolCall("tf_propose_ok")
    @Test fun `tf_adjust_add_pinned`() = assertToolCall("tf_adjust_add_pinned")
    @Test fun `tf_adjust_move_ok`() = assertToolCall("tf_adjust_move_ok")
    @Test fun `tf_get_schedule_clamp`() = assertToolCall("tf_get_schedule_clamp")
    @Test fun `tf_get_history_window`() = assertToolCall("tf_get_history_window")
    @Test fun `tf_suggest_ok`() = assertToolCall("tf_suggest_ok")
    @Test fun `tf_upsert_background_ok`() = assertToolCall("tf_upsert_background_ok")
    @Test fun `tf_merge_backgrounds`() = assertToolCall("tf_merge_backgrounds")
    @Test fun `tf_upsert_facts_ok`() = assertToolCall("tf_upsert_facts_ok")

    // ---- tool_reject（8） ------------------------------------------------------------

    @Test fun `tj_version_conflict`() = assertToolReject("tj_version_conflict")
    @Test fun `tj_invalid_request`() = assertToolReject("tj_invalid_request")
    @Test fun `tj_fixed_slots_invalid`() = assertToolReject("tj_fixed_slots_invalid")
    @Test fun `tj_schedule_rejected`() = assertToolReject("tj_schedule_rejected")
    @Test fun `tj_forbidden`() = assertToolReject("tj_forbidden")
    @Test fun `tj_suggest_bad_key`() = assertToolReject("tj_suggest_bad_key")
    @Test fun `tj_suggest_missing`() = assertToolReject("tj_suggest_missing")
    @Test fun `tj_budget_exceeded`() = assertToolReject("tj_budget_exceeded")
}
