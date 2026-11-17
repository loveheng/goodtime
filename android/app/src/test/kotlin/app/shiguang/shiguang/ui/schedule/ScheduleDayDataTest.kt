package app.shiguang.shiguang.ui.schedule

import app.shiguang.core.action.NativeSqlRepository
import app.shiguang.core.model.JdbcSqlStore
import app.shiguang.core.model.ShiguangSchema
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** ScheduleDayData 装配行为测试（Dart _load/DayData 镜像；JdbcSqlStore 真库装配）。 */
class ScheduleDayDataTest {

    private suspend fun freshRepo(): NativeSqlRepository {
        val store = JdbcSqlStore.inMemory()
        ShiguangSchema.createAll(store)
        return NativeSqlRepository(store)
    }

    private suspend fun seedBlock(
        repo: NativeSqlRepository,
        date: String,
        startMin: Int,
        endMin: Int,
        status: String = "confirmed",
        isDaySpark: Boolean = false,
        planId: String? = null,
    ): JsonObject = repo.addBlock(buildJsonObject {
        put("date", date)
        put("start_min", startMin)
        put("end_min", endMin)
        put("status", status)
        put("is_day_spark", if (isDaySpark) 1 else 0)
        if (planId != null) put("plan_id", planId)
    })

    @Test
    fun `今日装配 单查询分流与派生 getter 族`() = runTest {
        val repo = freshRepo()
        repo.settingsSet(buildJsonObject {
            put("wake_time", 420)
            put("sleep_time", 1380)
            put("exceptions", """[{"start":"2026-11-14","end":"2026-11-16","label":"云南行"}]""")
        })
        val plan = repo.addPlan(buildJsonObject { put("title", "写周报") })
        val planId = plan["id"]?.jsonPrimitive?.contentOrNull!!
        val b1 = seedBlock(repo, "2026-11-15", 600, 660, planId = planId)
        val b2 = seedBlock(repo, "2026-11-15", 900, 1000, status = "proposed")
        val b3 = seedBlock(repo, "2026-11-15", 1000, 1010, status = "done", isDaySpark = true)
        val b4 = seedBlock(repo, "2026-11-15", 1100, 1130, status = "melted")
        val b5 = seedBlock(repo, "2026-11-14", 600, 660, status = "missed")
        repo.replaceFixedSlots(JsonArray(listOf(
            buildJsonObject { put("name", "晨会"); putJsonArray("weekdays") { add(7) }; put("start_min", 540); put("end_min", 600) },
            buildJsonObject { put("name", "夜班"); putJsonArray("weekdays") { add(6) }; put("start_min", 1410); put("end_min", 450) },
        )))
        repo.addArtifact(
            buildJsonObject { put("title", "通关卡"); put("badge", "票根"); put("state", "active") },
            bindBlockId = b1["id"]?.jsonPrimitive?.contentOrNull!!,
        )

        val d = loadScheduleDay(repo, LocalDate.of(2026, 11, 15), LocalDateTime.of(2026, 11, 15, 12, 0))
        assertEquals(420, d.wake)
        assertEquals(1380, d.sleepAdj)
        assertTrue(d.isToday)
        assertEquals(4, d.blocks.size, "昨日+今日单查询分流：今日 4 块")
        assertEquals(3, d.visibleBlocks.size, "melted 不渲染")
        assertEquals(b3["id"]?.jsonPrimitive?.contentOrNull, d.spark?.id)
        assertTrue(d.safelineLit, "火种 done → 底线守住")
        assertEquals(
            listOf(b2["id"]?.jsonPrimitive?.contentOrNull),
            d.proposedAi.map { it.id },
        )
        assertEquals(
            listOf(b5["id"]?.jsonPrimitive?.contentOrNull),
            d.leftovers.map { it.id },
            "昨日 missed = 遗留区处置面",
        )
        assertEquals(1, d.fixedOnDate.size)
        assertEquals("晨会", d.fixedOnDate[0].name)
        assertEquals(1, d.fixedSpillover.size, "周六跨午夜 slot 溢入周日")
        val occ = d.occupiedIntervals()
        assertTrue(occ.contains(600 to 660))
        assertTrue(occ.contains(540 to 600), "固定占用投射")
        assertTrue(occ.contains(0 to 450), "前夜溢出段 (0,endMin)")
        assertFalse(occ.contains(1100 to 1130), "melted 不占窗")
        assertEquals("写周报", d.plans[planId]?.title)
        assertEquals(
            "票根",
            d.artifactsByBlock[b1["id"]?.jsonPrimitive?.contentOrNull!!]
                ?.get("badge")?.jsonPrimitive?.contentOrNull,
        )
        assertTrue(d.roam, "例外日覆盖 → 漫游态触发之一")
        assertFalse(d.cleanSlate, "missed 未达断流阈值")
        assertEquals("normal", d.energy)
    }

    @Test
    fun `历史日回看 区间倒挂块恒空（P6 quirk 镜像现状）`() = runTest {
        val repo = freshRepo()
        repo.settingsSet(buildJsonObject {
            put("wake_time", 420)
            put("sleep_time", 1380)
        })
        seedBlock(repo, "2026-11-10", 600, 660)
        // Dart blocksInRange(today-1, selected)：selected 早于昨日 → 区间倒挂 → 空（现状逐字镜像）
        val d = loadScheduleDay(repo, LocalDate.of(2026, 11, 10), LocalDateTime.of(2026, 11, 15, 12, 0))
        assertTrue(d.blocks.isEmpty())
        assertFalse(d.isToday)
    }
}
