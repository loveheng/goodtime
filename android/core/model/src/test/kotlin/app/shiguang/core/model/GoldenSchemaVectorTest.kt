package app.shiguang.core.model

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P2 原生存储层黄金向量对拍（sqlite_schema_vectors.json 6 case）：
 * 新装 v7 全结构快照 / v4→v7 升级同构 / 约束四钉（UNIQUE·复合 PK·FK 悬空）。
 * 快照抽取口径 = 向量 rules.extraction_rule（表/列/索引按名字典序，index_info 按
 * seqno，FK 自然序）——与 Dart 基准执行器 golden_schema_test 同构。
 */
class GoldenSchemaVectorTest {

    private val vectors: JsonObject by lazy {
        Json.parseToJsonElement(
            File("../../../test/golden/sqlite_schema_vectors.json").readText(),
        ).jsonObject
    }

    private fun case(id: String): JsonObject =
        vectors["cases"]!!.jsonArray.first { it.jsonObject["id"]!!.jsonPrimitive.content == id }.jsonObject

    /** PRAGMA 全结构快照（镜像 gen_schema_vectors.dart snapshotOf）。 */
    private suspend fun snapshot(store: SqlStore): JsonObject {
        val version = store.rawQuery("PRAGMA user_version").single()["user_version"] as Long
        val tableNames = store.rawQuery(
            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'",
        ).map { it["name"] as String }.sorted()
        // 先收集（suspend 面）再组装 JSON（builder lambda 非 suspend）
        val tables = LinkedHashMap<String, JsonObject>()
        for (t in tableNames) {
            val colRows = store.rawQuery("PRAGMA table_info($t)").sortedBy { it["name"] as String }
            val idxRows = store.rawQuery("PRAGMA index_list($t)").sortedBy { it["name"] as String }
            val idxColLists: List<List<String>> = idxRows.map { row ->
                store.rawQuery("PRAGMA index_info(${row["name"] as String})")
                    .map { it["name"] as String }
            }
            val fkRows = store.rawQuery("PRAGMA foreign_key_list($t)")

            val columns = buildJsonArray {
                for (c in colRows) {
                    add(buildJsonObject {
                        put("name", c["name"] as String)
                        put("type", c["type"] as String)
                        put("notnull", c["notnull"] as Long)
                        val d = c["dflt_value"]
                        put("dflt", (d as? String)?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("pk", c["pk"] as Long)
                    })
                }
            }
            val indexes = buildJsonArray {
                idxRows.forEachIndexed { i, row ->
                    add(buildJsonObject {
                        put("name", row["name"] as String)
                        put("unique", row["unique"] as Long)
                        put("origin", row["origin"] as String)
                        put("columns", JsonArray(idxColLists[i].map { JsonPrimitive(it) }))
                    })
                }
            }
            val fks = buildJsonArray {
                for (f in fkRows) {
                    add(buildJsonObject {
                        put("table", f["table"] as String)
                        put("from", f["from"] as String)
                        put("to", f["to"] as String)
                        put("on_update", f["on_update"] as String)
                        put("on_delete", f["on_delete"] as String)
                    })
                }
            }
            tables[t] = buildJsonObject {
                put("columns", columns)
                put("indexes", indexes)
                put("foreign_keys", fks)
            }
        }
        return buildJsonObject {
            put("version", version)
            put("tables", JsonObject(tables))
        }
    }

    @Test
    fun `schema_fresh_v7 全结构逐字段对拍`() = runTest {
        val store = JdbcSqlStore.inMemory()
        ShiguangSchema.createAll(store)
        val snap = snapshot(store)
        val expect = case("schema_fresh_v7")["expect"]!!.jsonObject
        assertEquals(expect, snap, "新装 v7 快照与黄金向量逐字段失配")
    }

    @Test
    fun `upgrade_isomorphic_v4_to_v7 升级同构对拍`() = runTest {
        val store = JdbcSqlStore.inMemory()
        val legacy = case("upgrade_isomorphic_v4_to_v7")["input"]!!.jsonObject["legacy_ddl"]!!.jsonArray
        for (ddl in legacy) store.execute(ddl.jsonPrimitive.content)
        store.execute("PRAGMA user_version = 4")
        ShiguangSchema.upgrade(store, 4)
        val snap = snapshot(store)
        val expect = case("upgrade_isomorphic_v4_to_v7")["expect"]!!.jsonObject
        assertEquals(expect, snap, "v4→v7 升级终态与黄金向量失配（新装≡升级不变量）")
    }

    @Test
    fun `约束四钉 全部拒绝`() = runTest {
        for (id in listOf(
            "reject_events_unique_seq", "reject_keyring_same_ver",
            "reject_fk_dangling", "reject_block_refs_pk",
        )) {
            val c = case(id)
            val store = JdbcSqlStore.inMemory()
            ShiguangSchema.createAll(store)
            if (c["input"]!!.jsonObject["fk_on"]?.jsonPrimitive?.content == "true") {
                store.execute("PRAGMA foreign_keys = ON")
            }
            for (seed in c["input"]!!.jsonObject["seed"]!!.jsonArray) {
                val s = seed.jsonObject
                store.insert(s["table"]!!.jsonPrimitive.content, s["row"]!!.jsonObject)
            }
            val probe = c["input"]!!.jsonObject["probe"]!!.jsonObject
            val result = runCatching {
                store.insert(probe["table"]!!.jsonPrimitive.content, probe["row"]!!.jsonObject)
            }
            assertTrue(result.isFailure, "$id 约束钉应拒绝（UNIQUE/PK/FK 未生效）")
            assertEquals("reject", c["expect"]!!.jsonObject["status"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `64位列映射 毫秒时间戳无 Int 截断`() = runTest {
        val store = JdbcSqlStore.inMemory()
        ShiguangSchema.createAll(store)
        val ts = 1760000000000L // ≈1.76e12 毫秒时间戳——Int 必溢
        val row = buildJsonObject {
            put("id", "p_ts")
            put("title", "64位断言")
            put("created_at", ts)
            put("updated_at", ts + 1)
            put("version", 0)
        }
        store.insert("plans", row)
        val back = store.query("plans", where = "id = ?", args = listOf("p_ts")).single()
        assertEquals(ts, back["created_at"], "created_at 必须 Long 保真（64 位列铁律）")
        assertEquals(ts + 1, back["updated_at"])
        val plan = Plan.fromMap(rowToJson(back))
        assertEquals(ts, plan.createdAt)
    }

    @Test
    fun `升级幂等 upgrade 重跑零副作用`() = runTest {
        val store = JdbcSqlStore.inMemory()
        val legacy = case("upgrade_isomorphic_v4_to_v7")["input"]!!.jsonObject["legacy_ddl"]!!.jsonArray
        for (ddl in legacy) store.execute(ddl.jsonPrimitive.content)
        store.execute("PRAGMA user_version = 4")
        ShiguangSchema.upgrade(store, 4)
        val first = snapshot(store)
        ShiguangSchema.upgrade(store, 4) // 重跑（幂等，零副作用）
        assertEquals(first, snapshot(store), "迁移幂等性破坏（重跑不应改变结构）")
    }
}
