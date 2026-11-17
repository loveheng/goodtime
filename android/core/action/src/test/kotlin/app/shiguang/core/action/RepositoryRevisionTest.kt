package app.shiguang.core.action

import app.shiguang.core.model.JdbcSqlStore
import app.shiguang.core.model.ShiguangSchema
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 变更刷新面（D16 裁决①，N5）：revision 单调递增语义——写路径成功 bump、读路径
 * 不 bump、乐观锁失败 0 行不 bump（未落库）。JdbcSqlStore 真库装配（先例同款）。
 */
class RepositoryRevisionTest {

    private suspend fun freshRepo(): NativeSqlRepository {
        val store = JdbcSqlStore.inMemory()
        ShiguangSchema.createAll(store)
        return NativeSqlRepository(store)
    }

    @Test
    fun `初始 revision 为零且读面不 bump`() = runTest {
        val repo = freshRepo()
        assertEquals(0L, repo.revision.value)
        repo.blocksOnDate("2026-11-15")
        repo.planById("nope")
        repo.settingsAll()
        repo.listPlans()
        repo.fixedSlotsForDate("2026-11-15")
        assertEquals(0L, repo.revision.value, "纯读面不得 bump")
    }

    @Test
    fun `写路径逐点 bump（plans settings blocks fixed）`() = runTest {
        val repo = freshRepo()
        val plan = repo.addPlan(buildJsonObject { put("title", "t1") })
        assertEquals(1L, repo.revision.value, "addPlan bump")
        val pid = plan["id"]!!.let { (it as kotlinx.serialization.json.JsonPrimitive).content }
        repo.patchPlan(pid, buildJsonObject { put("title", "t2") }, null)
        assertEquals(2L, repo.revision.value, "patchPlan 命中 bump")
        repo.settingsSet(buildJsonObject { put("k1", kotlinx.serialization.json.JsonPrimitive("v1")) })
        assertEquals(3L, repo.revision.value, "settingsSet bump")
        repo.settingsClear("k1")
        assertEquals(4L, repo.revision.value, "settingsClear bump")
        repo.settingsClearAll(listOf("k2", "k3"))
        assertEquals(5L, repo.revision.value, "settingsClearAll bump（空键组也计入写面）")
        val block = repo.addBlock(buildJsonObject {
            put("date", "2026-11-15")
            put("start_min", 600)
            put("end_min", 660)
            put("status", "confirmed")
        })
        assertEquals(6L, repo.revision.value, "addBlock bump")
        val bid = block["id"]!!.let { (it as kotlinx.serialization.json.JsonPrimitive).content }
        repo.replaceFixedSlots(kotlinx.serialization.json.JsonArray(emptyList()))
        assertEquals(7L, repo.revision.value, "replaceFixedSlots bump")
        repo.deleteBlock(bid)
        assertEquals(8L, repo.revision.value, "deleteBlock bump")
        repo.deletePlan(pid)
        assertEquals(9L, repo.revision.value, "deletePlan bump")
    }

    @Test
    fun `乐观锁失败零行不 bump 而命中 bump`() = runTest {
        val repo = freshRepo()
        val block = repo.addBlock(buildJsonObject {
            put("date", "2026-11-15")
            put("start_min", 600)
            put("end_min", 660)
            put("status", "proposed")
        })
        val bid = block["id"]!!.let { (it as kotlinx.serialization.json.JsonPrimitive).content }
        val base = repo.revision.value
        val ok = repo.patchBlock(bid, buildJsonObject { put("status", "confirmed") }, 99L)
        assertFalse(ok)
        assertEquals(base, repo.revision.value, "CAS 0 行未落库不 bump")
        assertTrue(repo.patchBlock(bid, buildJsonObject { put("status", "confirmed") }, block["version"]!!.let { (it as kotlinx.serialization.json.JsonPrimitive).longOrNull }!!))
        assertEquals(base + 1, repo.revision.value, "CAS 命中 bump")
    }

    @Test
    fun `transaction 复合写成功 bump`() = runTest {
        val repo = freshRepo()
        val base = repo.revision.value
        repo.transaction { txn ->
            txn.insert("plans", buildJsonObject {
                put("id", "tx-plan-1")
                put("title", "复合写")
                put("energy_level", "light")
                put("tool_required", "anywhere")
                put("importance", 0)
                put("archived", 0)
                put("open_items", "[]")
                put("created_at", 0L)
                put("updated_at", 0L)
                put("version", 0L)
            })
        }
        assertEquals(base + 1, repo.revision.value, "transaction 成功 bump")
    }
}
