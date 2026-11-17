package app.shiguang.core.action

import app.shiguang.core.model.Artifact
import app.shiguang.core.model.JdbcSqlStore
import app.shiguang.core.model.ShiguangSchema
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P2 原生仓储行为测试（storage_registry.md 验收节）：行级 SQL 语义与 Dart
 * Repository 逐字——缺省垫底/CAS 白名单/绑定事务/批量原子/64 位保真。
 */
class NativeSqlRepositoryTest {

    private suspend fun freshRepo(): Pair<JdbcSqlStore, NativeSqlRepository> {
        val store = JdbcSqlStore.inMemory()
        ShiguangSchema.createAll(store)
        return store to NativeSqlRepository(store)
    }

    private fun planRow(title: String): JsonObject = buildJsonObject { put("title", title) }

    @Test
    fun `addPlan 缺省垫底与 MCP 快照形`() = runTest {
        val (store, repo) = freshRepo()
        val p = repo.addPlan(planRow("写周报"))
        assertEquals("写周报", p["title"]?.jsonPrimitive?.contentOrNull)
        assertEquals("light", p["energy_level"]?.jsonPrimitive?.contentOrNull)
        assertEquals("anywhere", p["tool_required"]?.jsonPrimitive?.contentOrNull)
        assertEquals(0L, p["version"]?.jsonPrimitive?.longOrNull)
        assertEquals(false, p["importance"]?.jsonPrimitive?.contentOrNull == "true")
        // created_at 64 位保真（毫秒时间戳 ≈1.76e12 必溢 Int）——行级读取（MCP 快照不含时间列）
        val raw = store.query("plans", where = "id = ?", args = listOf(p["id"]?.jsonPrimitive?.contentOrNull!!)).single()
        val createdAt = raw["created_at"] as Long
        assertTrue(createdAt > 1_700_000_000_000L, "created_at 必须 Long 毫秒时间戳（64 位列铁律）")
    }

    @Test
    fun `patchPlan CAS 白名单与幂等`() = runTest {
        val (_, repo) = freshRepo()
        val p = repo.addPlan(buildJsonObject {
            put("title", "A")
            put("importance", true) // JSON bool → 行内 0/1
        })
        val id = p["id"]?.jsonPrimitive?.contentOrNull!!
        val v0 = p["version"]?.jsonPrimitive?.longOrNull ?: 0L
        // 空 patch 幂等 true
        assertTrue(repo.patchPlan(id, buildJsonObject { }, null))
        // 白名单拒
        assertFailsWith<IllegalArgumentException> {
            repo.patchPlan(id, buildJsonObject { put("hack", 1) }, null)
        }
        // CAS 成功：version+1、importance bool→1
        assertTrue(repo.patchPlan(id, buildJsonObject {
            put("title", "B")
            put("importance", true)
        }, v0))
        val fresh = repo.planById(id)!!
        assertEquals("B", fresh["title"]?.jsonPrimitive?.contentOrNull)
        assertEquals(v0 + 1, fresh["version"]?.jsonPrimitive?.longOrNull)
        // CAS 冲突：旧版本再写 → false
        assertTrue(!repo.patchPlan(id, buildJsonObject { put("title", "C") }, v0))
        assertEquals("B", repo.planById(id)!!["title"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `addArtifact 建档绑定事务 块在两态`() = runTest {
        val (store, repo) = freshRepo()
        val blk = repo.addBlock(buildJsonObject {
            put("date", "2026-11-14")
            put("start_min", 540)
            put("end_min", 600)
            put("source", "human")
        })
        val blockId = blk["id"]?.jsonPrimitive?.contentOrNull!!
        val art = repo.addArtifact(buildJsonObject {
            put("category", "ticket")
            put("source_kind", "booking")
            put("state", "structured")
            put("origin", "shared")
            put("title", "高铁票")
            put("payload", Artifact.encodePayload(buildJsonObject { put("v", 1) }))
            put("plan_id", JsonNull)
        }, blockId)
        val artId = art["id"]?.jsonPrimitive?.contentOrNull!!
        // 块侧绑定走行级断言（block MCP 快照无 artifact_id 键；version 在快照为 CAS 计数）
        val boundRow = store.query("schedule_blocks", where = "id = ?", args = listOf(blockId)).single()
        assertEquals(artId, boundRow["artifact_id"] as? String, "绑定后 blocks.artifact_id = 凭证 id")
        assertEquals(1L, boundRow["version"] as? Long, "绑定 version+1")
        // 悬空绑定：块已删 → 静默跳过不抛
        repo.deleteBlock(blockId)
        val art2 = repo.addArtifact(buildJsonObject {
            put("category", "verbal")
            put("source_kind", "verbal")
            put("state", "raw")
            put("origin", "quicknote")
            put("title", "备忘")
            put("payload", Artifact.encodePayload(buildJsonObject { put("v", 1) }))
        }, blockId) // 不抛即通过
        assertTrue(art2["id"]?.jsonPrimitive?.contentOrNull != artId)
    }

    @Test
    fun `upsertFacts 全语义 raw建档→提炼回填→作废demote`() = runTest {
        val (store, repo) = freshRepo()
        val handler = ResidualHandler(repo)
        val plan = repo.addPlan(planRow("出差"))
        val planId = plan["id"]?.jsonPrimitive?.contentOrNull!!
        val blk = repo.addBlock(buildJsonObject {
            put("date", "2026-11-14")
            put("start_min", 540)
            put("end_min", 600)
            put("source", "human")
        })
        val blockId = blk["id"]?.jsonPrimitive?.contentOrNull!!

        // ① raw 建档（verbal 占位）+ 块绑定
        val rawCmd = ScheduleCommand.UpsertFacts(
            title = "口头改签通知", origin = "quicknote", state = "raw",
            category = "verbal", sourceKind = "verbal",
            rawText = "第一段原文", planId = planId, blockId = blockId,
        )
        val rawResult = handler.upsertFacts(rawCmd, CommandActor.AI)
        assertTrue(rawResult.note?.contains("已记下原文") == true, "raw 建档 note：${rawResult.note}")
        val factId = rawResult.targetId!!
        val rawSnap = repo.artifactById(factId)!!
        assertEquals("verbal", rawSnap["category"]?.jsonPrimitive?.contentOrNull)
        // 绑定断言走行级（MCP 快照无 artifact_id）：blocks.artifact_id = 凭证 id
        assertEquals(factId, store.query("schedule_blocks", where = "id = ?", args = listOf(blockId)).single()["artifact_id"] as? String)

        // ② 出生确认严进：raw→structured 缺 category/source_kind 即拒
        val birthReject = assertFailsWith<ActionException> {
            handler.upsertFacts(
                ScheduleCommand.UpsertFacts(id = factId, state = "structured"), CommandActor.AI,
            )
        }
        assertTrue(birthReject.message?.contains("出生确认") == true, birthReject.message)

        // ③ 提炼回填（birth）：category/source_kind 终值 + payload 合并 + raw_text 追加
        val filled = handler.upsertFacts(
            ScheduleCommand.UpsertFacts(
                id = factId, state = "structured", category = "ticket", sourceKind = "booking",
                title = "改签后车票", badge = "改签凭证超长微标需要截断到十二",
                timeAnchors = listOf(buildJsonObject {
                    put("kind", "moment")
                    put("date", "2026-11-15")
                    put("min", 540)
                }),
                rawText = "第二段原文",
            ),
            CommandActor.AI,
        )
        assertTrue(filled.note?.contains("原文已追加存档") == true, "raw_text 追加 note：${filled.note}")
        assertTrue(filled.note?.contains("badge 超 12 字符已截断") == true, "badge 截断 note：${filled.note}")
        val fresh = repo.artifactById(factId)!!
        assertEquals("ticket", fresh["category"]?.jsonPrimitive?.contentOrNull)
        assertEquals("改签凭证超长微标需要截断", fresh["badge"]?.jsonPrimitive?.contentOrNull, "badge 12 字符截断")
        val payload = fresh["payload"] as? JsonObject ?: JsonObject(emptyMap())
        assertEquals("第一段原文\n第二段原文", payload["raw_text"]?.jsonPrimitive?.contentOrNull)
        // 双写铁律：payload.plan_id 与列同源（提取回填不改挂 → 保留）
        assertEquals(planId, payload["plan_id"]?.jsonPrimitive?.contentOrNull)
        assertEquals(1, payload["v"]?.jsonPrimitive?.longOrNull?.toInt(), "payload v 序契约：v 恒首位")

        // ④ 作废：demote 反转关联（块 pinned=0、artifact_id=NULL）
        val voided = handler.upsertFacts(
            ScheduleCommand.UpsertFacts(id = factId, state = "voided"), CommandActor.AI,
        )
        assertTrue(voided.note?.contains("已标记作废") == true, voided.note)
        val demotedBlock = repo.blockById(blockId)!!
        assertTrue(demotedBlock["pinned"]?.jsonPrimitive?.booleanOrNull == false, "作废后引用块降格 pinned")
    }

    @Test
    fun `upsertFacts rule 锚漏生效窗即拒`() = runTest {
        val (_, repo) = freshRepo()
        val handler = ResidualHandler(repo)
        val e = assertFailsWith<ActionException> {
            handler.upsertFacts(
                ScheduleCommand.UpsertFacts(
                    title = "红线", origin = "ai", state = "structured",
                    category = "venue", sourceKind = "announcement",
                    timeAnchors = listOf(buildJsonObject { put("kind", "rule"); put("date", "2026-11-15") }),
                ),
                CommandActor.AI,
            )
        }
        assertTrue(e.message?.contains("rule_scope") == true, e.message)
    }

    @Test
    fun `deleteArtifact demote 事务与 note 分叉`() = runTest {
        val (_, repo) = freshRepo()
        val handler = ResidualHandler(repo)
        val blk = repo.addBlock(buildJsonObject {
            put("date", "2026-11-14"); put("start_min", 540); put("end_min", 600); put("source", "human")
        })
        val blockId = blk["id"]?.jsonPrimitive?.contentOrNull!!
        val created = handler.upsertFacts(
            ScheduleCommand.UpsertFacts(
                title = "票", origin = "shared", state = "structured",
                category = "ticket", sourceKind = "booking", blockId = blockId,
            ),
            CommandActor.AI,
        )
        val result = handler.deleteArtifact(ScheduleCommand.DeleteArtifact(created.targetId!!))
        assertTrue(result.note?.contains("关联块已解除钉定") == true, result.note)
        val b = repo.blockById(blockId)!!
        assertTrue(b["pinned"]?.jsonPrimitive?.booleanOrNull == false, "删除后引用块降格 pinned")
        // notFound
        val e = assertFailsWith<ActionException> {
            handler.deleteArtifact(ScheduleCommand.DeleteArtifact(created.targetId!!))
        }
        assertTrue(e.message.contains("凭证不存在"))
    }

    @Test
    fun `replaceFixedSlots 原子性与 fixedSlotsForDate 派生`() = runTest {
        val (store, repo) = freshRepo()
        // 坏批（重复 id → 第二行 PK 冲突）→ 整批回滚
        val bad = Json.parseToJsonElement(
            """[{"id":"fx1","name":"睡眠","weekdays":[1,2,3],"start_min":1410,"end_min":450},
                {"id":"fx1","name":"通勤","weekdays":[6,7],"start_min":600,"end_min":660}]""",
        ) as JsonArray
        assertFailsWith<Exception> { repo.replaceFixedSlots(bad) }
        assertEquals(0, (repo.listFixedSlots() as JsonArray).size, "整批回滚：坏行不得部分落库")
        // 好批：跨午夜槽 + 周三(3) 在位 → 11-14(周六) 无 onDate、11-18(周三) onDate
        repo.replaceFixedSlots(Json.parseToJsonElement(
            """[{"name":"睡眠","weekdays":[3],"start_min":1410,"end_min":450}]""",
        ) as JsonArray)
        val wed = repo.fixedSlotsForDate("2026-11-18") // 2026-11-18 = 周三
        assertEquals(1, (wed["on_date"] as JsonArray).size)
        val thu = repo.fixedSlotsForDate("2026-11-19") // 周四：onDate 无、前夜跨午夜溢出在位
        assertEquals(0, (thu["on_date"] as JsonArray).size)
        assertEquals(1, (thu["spillover"] as JsonArray).size)
    }

    @Test
    fun `settingsSet upsert 与批量清键`() = runTest {
        val (_, repo) = freshRepo()
        repo.settingsSet(buildJsonObject {
            put("wake_time", "420")
            put("theme_mode", "dark")
        })
        repo.settingsSet(buildJsonObject { put("theme_mode", "light") }) // upsert 覆盖
        val all = repo.settingsAll()
        assertEquals("420", all["wake_time"]?.jsonPrimitive?.contentOrNull)
        assertEquals("light", all["theme_mode"]?.jsonPrimitive?.contentOrNull)
        assertEquals("420", repo.settingsGet("wake_time"))
        repo.settingsClearAll(listOf("wake_time", "theme_mode"))
        assertTrue(!repo.settingsAll().containsKey("wake_time"), "清键后应无行")
        assertEquals(null, repo.settingsGet("theme_mode"))
    }

    @Test
    fun `blocksInRange 排序与 deleteRow 缺失即异常`() = runTest {
        val (_, repo) = freshRepo()
        repo.addBlock(buildJsonObject {
            put("date", "2026-11-15"); put("start_min", 600); put("end_min", 660); put("source", "human")
        })
        repo.addBlock(buildJsonObject {
            put("date", "2026-11-14"); put("start_min", 700); put("end_min", 760); put("source", "human")
        })
        repo.addBlock(buildJsonObject {
            put("date", "2026-11-14"); put("start_min", 540); put("end_min", 600); put("source", "human")
        })
        val range = repo.blocksInRange("2026-11-14", "2026-11-15") as JsonArray
        assertEquals(3, range.size)
        val seq = range.map { b ->
            (b as JsonObject).let { it["date"]?.jsonPrimitive?.contentOrNull to it["start_min"]?.jsonPrimitive?.longOrNull }
        }
        assertEquals(listOf("2026-11-14" to 540L, "2026-11-14" to 700L, "2026-11-15" to 600L), seq)
        // 范围外不入
        val single = repo.blocksInRange("2026-11-15", "2026-11-15") as JsonArray
        assertEquals(1, single.size)
        // 删除不存在 → 异常
        assertFailsWith<IllegalStateException> { repo.deleteBlock("ghost") }
    }

    @Test
    fun `transaction 回滚语义 异常即整体回滚`() = runTest {
        val (store, repo) = freshRepo()
        repo.addPlan(planRow("幸存者"))
        val e = runCatching {
            repo.transaction { txn ->
                txn.insert("plans", buildJsonObject {
                    put("id", "p_tmp"); put("title", "临时"); put("created_at", 0L); put("updated_at", 0L)
                })
                throw IllegalStateException("中途炸")
            }
        }
        assertTrue(e.isFailure, "事务体异常应向上传播")
        assertEquals(1, store.query("plans").size, "事务内插入应整体回滚（幸存者仍在、临时行不在）")
    }

    @Test
    fun `artifactForBlock 绑定凭证 非 voided 过滤`() = runTest {
        val (_, repo) = freshRepo()
        val b = repo.addBlock(buildJsonObject { put("date", "2026-11-15"); put("start_min", 600); put("end_min", 660) })
        val blockId = b["id"]?.jsonPrimitive?.contentOrNull!!
        assertNull(repo.artifactForBlock(blockId), "未绑定凭证 → null")
        val a = repo.addArtifact(
            buildJsonObject { put("title", "通关卡"); put("badge", "票根"); put("state", "active") },
            bindBlockId = blockId,
        )
        val got = repo.artifactForBlock(blockId)
        assertEquals("通关卡", got?.get("title")?.jsonPrimitive?.contentOrNull)
        assertEquals("票根", got?.get("badge")?.jsonPrimitive?.contentOrNull)
        // voided 后过滤为空（Dart state != voided 同口径）
        repo.patchArtifact(
            a["id"]?.jsonPrimitive?.contentOrNull!!,
            buildJsonObject { put("state", "voided") },
            expectedVersion = a["version"]?.jsonPrimitive?.longOrNull,
        )
        assertNull(repo.artifactForBlock(blockId))
    }
}
