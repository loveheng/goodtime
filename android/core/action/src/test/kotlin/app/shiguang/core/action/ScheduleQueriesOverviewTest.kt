package app.shiguang.core.action

import app.shiguang.core.model.JdbcSqlStore
import app.shiguang.core.model.ShiguangSchema
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 周视图/简易月历投影行为测试（Dart queries.dart weekOverview/monthOverview 对拍；
 * 三视图同源纪律=同一 blocksInRange 单查询派生 §4）。JdbcSqlStore 真库装配。
 */
class ScheduleQueriesOverviewTest {

    private suspend fun fresh(): Pair<NativeSqlRepository, ScheduleQueries> {
        val store = JdbcSqlStore.inMemory()
        ShiguangSchema.createAll(store)
        val repo = NativeSqlRepository(store)
        return repo to ScheduleQueries(repo)
    }

    private suspend fun seedSettings(
        repo: NativeSqlRepository,
        wake: Int = 420,
        sleep: Int = 1380,
        exceptions: JsonArray? = null,
    ) {
        val values = buildJsonObject {
            put("wake_time", wake)
            put("sleep_time", sleep)
            if (exceptions != null) put("exceptions", exceptions.toString())
        }
        repo.settingsSet(values)
    }

    private suspend fun seedBlock(
        repo: NativeSqlRepository,
        date: String,
        startMin: Int,
        endMin: Int,
        status: String = "confirmed",
        source: String = "ai",
        isDaySpark: Boolean = false,
        isCelebration: Boolean = false,
        label: String? = null,
        planId: String? = null,
    ): JsonObject = repo.addBlock(buildJsonObject {
        put("date", date)
        put("start_min", startMin)
        put("end_min", endMin)
        put("status", status)
        put("source", source)
        put("is_day_spark", if (isDaySpark) 1 else 0)
        put("is_celebration", if (isCelebration) 1 else 0)
        if (label != null) put("label", label)
        if (planId != null) put("plan_id", planId)
    })

    // ---- monthOverview ------------------------------------------------------------

    @Test
    fun `月历按日聚合计数与例外标签`() = runTest {
        val (repo, queries) = fresh()
        seedSettings(
            repo,
            exceptions = JsonArray(listOf(
                buildJsonObject {
                    put("start", "2026-11-06"); put("end", "2026-11-07"); put("label", "云南行")
                },
            )),
        )
        seedBlock(repo, "2026-11-05", 600, 660, status = "done")
        seedBlock(repo, "2026-11-05", 700, 730, status = "missed")
        seedBlock(repo, "2026-11-05", 800, 830, source = "human")
        seedBlock(repo, "2026-11-06", 900, 930, status = "proposed")
        seedBlock(repo, "2026-11-06", 960, 990, status = "done", isDaySpark = true)
        seedBlock(repo, "2026-11-06", 1000, 1030, isCelebration = true)
        seedBlock(repo, "2026-11-07", 1100, 1130, status = "melted") // 无痕蒸发不进月历
        seedBlock(repo, "2026-12-01", 600, 660) // 出月窗不进单月投影

        val m = queries.monthOverview(2026, 11, now = LocalDateTime.of(2026, 11, 10, 12, 0))
        assertEquals(2026L, m["year"]?.jsonPrimitive?.longOrNull)
        assertEquals(11L, m["month"]?.jsonPrimitive?.longOrNull)
        assertEquals("2026-11-10", m["today"]?.jsonPrimitive?.contentOrNull)
        val days = (m["days"] as JsonArray).map { it as JsonObject }
        assertEquals(30, days.size)

        val d05 = days.first { it["date"]?.jsonPrimitive?.contentOrNull == "2026-11-05" }
        assertEquals(3L, d05["total"]?.jsonPrimitive?.longOrNull)
        assertEquals(1L, d05["done"]?.jsonPrimitive?.longOrNull)
        assertEquals(1L, d05["missed"]?.jsonPrimitive?.longOrNull)
        assertEquals(0L, d05["proposed"]?.jsonPrimitive?.longOrNull)
        assertEquals(1L, d05["human"]?.jsonPrimitive?.longOrNull)
        assertEquals(false, d05["spark_done"]?.jsonPrimitive?.booleanOrNull)
        assertNull(d05["exception"]?.jsonPrimitive?.contentOrNull)

        val d06 = days.first { it["date"]?.jsonPrimitive?.contentOrNull == "2026-11-06" }
        assertEquals(3L, d06["total"]?.jsonPrimitive?.longOrNull)
        assertEquals(true, d06["spark_done"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(true, d06["celebration"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("云南行", d06["exception"]?.jsonPrimitive?.contentOrNull)

        val d07 = days.first { it["date"]?.jsonPrimitive?.contentOrNull == "2026-11-07" }
        assertEquals(0L, d07["total"]?.jsonPrimitive?.longOrNull) // melted 被排除
        assertEquals("云南行", d07["exception"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `月历例外日缺标签回落通用措辞`() = runTest {
        val (repo, queries) = fresh()
        seedSettings(repo, exceptions = JsonArray(listOf(
            buildJsonObject { put("start", "2026-11-02"); put("end", "2026-11-02") },
        )))
        val m = queries.monthOverview(2026, 11, now = LocalDateTime.of(2026, 11, 10, 12, 0))
        val days = (m["days"] as JsonArray).map { it as JsonObject }
        val d02 = days.first { it["date"]?.jsonPrimitive?.contentOrNull == "2026-11-02" }
        assertEquals("假期与出行", d02["exception"]?.jsonPrimitive?.contentOrNull)
    }

    // ---- weekOverview -------------------------------------------------------------

    @Test
    fun `周视图周一锚定与块投影有效标签回落`() = runTest {
        val (repo, queries) = fresh()
        seedSettings(repo)
        val plan = repo.addPlan(buildJsonObject { put("title", "写周报") })
        val planId = plan["id"]?.jsonPrimitive?.contentOrNull!!
        // 2026-11-11 为周三 → 本周周一=2026-11-09
        seedBlock(repo, "2026-11-09", 600, 660, planId = planId)
        seedBlock(repo, "2026-11-10", 420, 480, source = "human", label = "跑步")

        val w = queries.weekOverview(LocalDate.of(2026, 11, 11))
        assertEquals("2026-11-09", w["week_start"]?.jsonPrimitive?.contentOrNull)
        assertEquals(420L, w["wake"]?.jsonPrimitive?.longOrNull)
        assertEquals(1380L, w["sleep_adj"]?.jsonPrimitive?.longOrNull)
        val days = (w["days"] as JsonArray).map { it as JsonObject }
        assertEquals(7, days.size)
        assertEquals("一", days[0]["weekday"]?.jsonPrimitive?.contentOrNull)
        assertEquals("日", days[6]["weekday"]?.jsonPrimitive?.contentOrNull)

        val d09 = days.first { it["date"]?.jsonPrimitive?.contentOrNull == "2026-11-09" }
        val blocks = (d09["blocks"] as JsonArray).map { it as JsonObject }
        assertEquals(1, blocks.size)
        assertEquals("写周报", blocks[0]["effective_label"]?.jsonPrimitive?.contentOrNull)
        assertEquals(
            0, (d09["fixed_on_date"] as JsonArray).size,
        ) // 未配固定节奏

        val d10 = days.first { it["date"]?.jsonPrimitive?.contentOrNull == "2026-11-10" }
        val humanBlocks = (d10["blocks"] as JsonArray).map { it as JsonObject }
        assertEquals("跑步", humanBlocks[0]["effective_label"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `周视图固定占用投射与睡眠跨午夜窗`() = runTest {
        val (repo, queries) = fresh()
        seedSettings(repo, wake = 420, sleep = 300) // sleep<=wake → sleepAdj=300+1440=1740
        repo.replaceFixedSlots(JsonArray(listOf(
            buildJsonObject {
                put("name", "晨会")
                putJsonArray("weekdays") { add(1) } // 周一
                put("start_min", 540)
                put("end_min", 600)
            },
        )))
        val w = queries.weekOverview(LocalDate.of(2026, 11, 11))
        assertEquals(1740L, w["sleep_adj"]?.jsonPrimitive?.longOrNull)
        val days = (w["days"] as JsonArray).map { it as JsonObject }
        val d09 = days.first { it["date"]?.jsonPrimitive?.contentOrNull == "2026-11-09" }
        val fixed = (d09["fixed_on_date"] as JsonArray).map { it as JsonObject }
        assertEquals(1, fixed.size)
        assertEquals("晨会", fixed[0]["name"]?.jsonPrimitive?.contentOrNull)
        val d10 = days.first { it["date"]?.jsonPrimitive?.contentOrNull == "2026-11-10" }
        assertEquals(0, (d10["fixed_on_date"] as JsonArray).size)
        assertEquals(0, (d10["fixed_spillover"] as JsonArray).size)
    }

    @Test
    fun `周视图例外日标签入列头`() = runTest {
        val (repo, queries) = fresh()
        seedSettings(repo, exceptions = JsonArray(listOf(
            buildJsonObject {
                put("start", "2026-11-09"); put("end", "2026-11-09"); put("label", "假期与出行")
            },
        )))
        val w = queries.weekOverview(LocalDate.of(2026, 11, 11))
        val days = (w["days"] as JsonArray).map { it as JsonObject }
        assertEquals("假期与出行", days[0]["exception"]?.jsonPrimitive?.contentOrNull)
        assertNull(days[1]["exception"]?.jsonPrimitive?.contentOrNull)
    }
}
