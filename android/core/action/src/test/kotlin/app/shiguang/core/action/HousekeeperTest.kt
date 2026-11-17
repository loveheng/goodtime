package app.shiguang.core.action

import app.shiguang.core.model.JdbcSqlStore
import app.shiguang.core.model.ShiguangSchema
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDateTime
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Housekeeper/isBreakdown 行为单测（行为清单 fgs_housekeeper.md 过目 2026-11-15）：
 * dailyCut 三分派/Clean Slate 保护期/幂等闸/回扫窗 + isBreakdown 阈值边界。
 * 时钟钉死：now=2026-11-15T10:00（wake 未设 → dailyCut 缺省 420=07:00 → today=11-15）。
 */
class HousekeeperTest {

    private suspend fun newRepo(): NativeSqlRepository {
        val store = JdbcSqlStore.inMemory()
        ShiguangSchema.createAll(store)
        return NativeSqlRepository(store)
    }

    private val now = LocalDateTime.parse("2026-11-15T10:00:00")
    private val yesterday = "2026-11-14"

    /** 播种块（布尔位 0/1 行口径——lesson：禁 JSON overlay 直传布尔）。 */
    private suspend fun NativeSqlRepository.seedBlock(
        date: String,
        status: String,
        celebration: Boolean = false,
    ): String {
        val b = addBlock(buildJsonObject {
            put("date", date)
            put("start_min", 600)
            put("end_min", 660)
            put("status", status)
            put("source", "ai")
            put("is_celebration", if (celebration) 1 else 0)
        })
        return b["id"]!!.let { (it as JsonPrimitive).content }
    }

    private suspend fun NativeSqlRepository.statusOf(id: String): String? {
        val b = blockById(id) ?: return null
        return (b["status"] as JsonPrimitive).content
    }

    // ---- dailyCut 三分派 -----------------------------------------------------------

    @Test
    fun confirmedMissesAndProposedVoids() = runTest {
        val repo = newRepo()
        val hk = Housekeeper(repo)
        val confirmed = repo.seedBlock(yesterday, "confirmed")
        val proposed = repo.seedBlock(yesterday, "proposed")
        val stats = hk.dailyCut(now)
        assertEquals(1, stats.missed)
        assertEquals(1, stats.voided)
        assertEquals(0, stats.archived)
        assertEquals(false, stats.protected)
        assertEquals("missed", repo.statusOf(confirmed))
        assertEquals(null, repo.statusOf(proposed)) // 物理删除
    }

    @Test
    fun celebrationNeutralizesBothPaths() = runTest {
        val repo = newRepo()
        val hk = Housekeeper(repo)
        val confirmedCele = repo.seedBlock(yesterday, "confirmed", celebration = true)
        val proposedCele = repo.seedBlock(yesterday, "proposed", celebration = true)
        val stats = hk.dailyCut(now)
        assertEquals(0, stats.missed)
        assertEquals(0, stats.voided)
        assertEquals(2, stats.archived)
        assertEquals("archived", repo.statusOf(confirmedCele))
        assertEquals("archived", repo.statusOf(proposedCele)) // 庆祝提案不删，中性淡出
    }

    @Test
    fun cleanSlateProtectionArchivesConfirmed() = runTest {
        val repo = newRepo()
        val hk = Housekeeper(repo)
        // 5 个 missed → isBreakdown=true（保护期）
        repeat(5) { repo.seedBlock(yesterday, "missed") }
        val confirmed = repo.seedBlock(yesterday, "confirmed")
        val stats = hk.dailyCut(now)
        assertEquals(true, stats.protected)
        assertEquals("archived", repo.statusOf(confirmed)) // 保护期：非庆祝确认块也归档
        assertEquals(0, stats.missed)
        assertEquals(1, stats.archived)
    }

    @Test
    fun lookbackWindowBounds() = runTest {
        val repo = newRepo()
        val hk = Housekeeper(repo)
        val tooOld = repo.seedBlock("2026-09-14", "confirmed") // 61 天前（窗界 2026-09-16）
        val inWindow = repo.seedBlock("2026-09-16", "confirmed") // 60 天前（窗内）
        val stats = hk.dailyCut(now)
        assertEquals(1, stats.missed)
        assertEquals("confirmed", repo.statusOf(tooOld)) // 窗外不动
        assertEquals("missed", repo.statusOf(inWindow))
    }

    @Test
    fun dailyCutIfNeededIdempotentPerWakeDay() = runTest {
        val repo = newRepo()
        val hk = Housekeeper(repo)
        val a = repo.seedBlock(yesterday, "confirmed")
        val b = repo.seedBlock(yesterday, "confirmed")
        hk.dailyCutIfNeeded(now)
        assertEquals("missed", repo.statusOf(a))
        assertEquals("missed", repo.statusOf(b))
        // 同作息日二次调用：幂等闸生效——首跑后新播种的昨日块不再处理
        val c = repo.seedBlock(yesterday, "confirmed")
        hk.dailyCutIfNeeded(now)
        assertEquals("confirmed", repo.statusOf(c))
    }

    // ---- isBreakdown 边界 ----------------------------------------------------------

    @Test
    fun breakdownMissedThreshold() = runTest {
        val repo = newRepo()
        val q = ScheduleQueries(repo)
        repeat(4) { repo.seedBlock(yesterday, "missed") }
        assertFalse(q.isBreakdown(now))
        repo.seedBlock(yesterday, "missed") // 第 5 个
        assertTrue(q.isBreakdown(now))
    }

    @Test
    fun breakdownDaysSinceActive() = runTest {
        val repo = newRepo()
        val q = ScheduleQueries(repo)
        repo.seedBlock("2026-11-12", "done") // 3 天前
        assertTrue(q.isBreakdown(now))
        repo.seedBlock("2026-11-13", "done") // 2 天前（更晚活跃）
        assertFalse(q.isBreakdown(now))
    }

    @Test
    fun breakdownNoActivityFalse() = runTest {
        val repo = newRepo()
        val q = ScheduleQueries(repo)
        repo.seedBlock(yesterday, "proposed") // 提案不算活跃
        assertFalse(q.isBreakdown(now))
    }
}
