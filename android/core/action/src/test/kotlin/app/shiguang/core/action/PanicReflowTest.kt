package app.shiguang.core.action

import app.shiguang.core.model.JdbcSqlStore
import app.shiguang.core.model.ScheduleDay
import app.shiguang.core.model.ShiguangSchema
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * panic_clear/reflow_day 原生化对拍（N5.5，D18 清单转正；JdbcSqlStore 真库）：
 * clear_remaining 融化族与和平条款 / push_2h 整单拒两分支（越作息/撞墙）与成功位
 * 移 / 空集短句 / 未知 mode 拒 / reflow 非低档短句·三级阻尼预算收敛·庆祝豁免·
 * 例外日 fixed 全空。块 seed 日期/nowMin 取真时钟（与实现同构造，跨午夜稳健）。
 */
class PanicReflowTest {

    private suspend fun freshRepo(): NativeSqlRepository {
        val store = JdbcSqlStore.inMemory()
        ShiguangSchema.createAll(store)
        return NativeSqlRepository(store)
    }

    /** 与实现同构造的作息日今日（wake=420 默认 envelope 前提）。 */
    private fun todayIso(): String = ScheduleDay.isoDate(ScheduleDay.scheduleDayOf(LocalDateTime.now(), 420))

    private suspend fun fixture(): Pair<NativeSqlRepository, ResidualHandler> {
        val repo = freshRepo()
        // envelope 前提：作息边界已初始化（wake=420/sleep=1380）
        repo.settingsSet(buildJsonObject {
            put("wake_time", JsonPrimitive("420"))
            put("sleep_time", JsonPrimitive("1380"))
        })
        return Pair(repo, ResidualHandler(repo))
    }

    private suspend fun seedBlock(
        repo: NativeSqlRepository,
        startMin: Int,
        endMin: Int,
        status: String = "proposed",
        source: String = "ai",
        pinned: Boolean = false,
        isCelebration: Boolean = false,
        planId: String? = null,
    ): JsonObject = repo.addBlock(buildJsonObject {
        put("date", todayIso())
        put("start_min", startMin)
        put("end_min", endMin)
        put("status", status)
        put("source", source)
        put("pinned", if (pinned) 1 else 0)
        put("is_celebration", if (isCelebration) 1 else 0)
        if (planId != null) put("plan_id", planId)
    })

    private suspend fun seedPlan(repo: NativeSqlRepository, minViable: String?): JsonObject = repo.addPlan(buildJsonObject {
        put("title", "计划")
        if (minViable != null) put("min_viable_action", minViable)
    })

    private fun idOf(row: JsonObject): String = (row["id"] as JsonPrimitive).content

    @Test
    // nowMin 定钟（2026-11-15 抓修）：原 LocalDateTime.now() 使播种窗跨昼夜抖动
    // （夜跑 nowMin+360>1440 越界→拒绝原因变形，白绿夜红）；panicClear 本就注入
    // nowMin，改固定值保语义除时钟耦合。越作息用例需 nowMin>1080 且块未开始→1200。
    fun `clear_remaining 融化族与和平条款`() = runTest {
        val (repo, residual) = fixture()
        val nowMin = 600

        val p1 = seedBlock(repo, nowMin + 60, nowMin + 120, status = "proposed")
        val p2 = seedBlock(repo, nowMin + 180, nowMin + 240, status = "confirmed")
        seedBlock(repo, nowMin + 300, nowMin + 360, status = "proposed", source = "human") // 和平条款排除
        seedBlock(repo, nowMin + 360, nowMin + 420, status = "proposed", pinned = true) // pinned 排除
        seedBlock(repo, nowMin - 120, nowMin - 60, status = "proposed") // 已开始排除
        val r = residual.panicClear(ScheduleCommand.PanicClear(mode = "clear_remaining", nowMin = nowMin.toLong()))
        assertEquals("已把 2 项放回清单待安排（无痕，零心理负债）", r.note)
        val meltedIds = (r.data!!["melted"] as JsonArray).map { (it as JsonPrimitive).content }
        assertEquals(listOf(idOf(p1), idOf(p2)), meltedIds)
        assertTrue(repo.blockById(idOf(p1))!!.toString().contains("melted"))
        // 和平条款：human/pinned/已开始块原样（status 未动）——human 块 seed 时 proposed
        val rows = repo.blocksOnDate(todayIso()).toString()
        assertTrue(rows.contains("melted"))
    }

    @Test
    fun `push_2h 撞墙整单拒`() = runTest {
        val (repo, residual) = fixture()
        val nowMin = 600
        seedBlock(repo, nowMin + 240, nowMin + 360, status = "confirmed", source = "human") // 墙：human 块 (240,360)
        val target = seedBlock(repo, nowMin + 120, nowMin + 180, status = "proposed") // +120 后 (240,300) 与墙相交
        val e = assertFailsWith<ActionException> {
            residual.panicClear(ScheduleCommand.PanicClear(mode = "push_2h", nowMin = nowMin.toLong()))
        }
        assertTrue(e.message.startsWith("后移 2 小时会撞"))
        // 整单拒：目标块未动
        assertEquals(nowMin + 120L, (repo.blockById(idOf(target))!!["start_min"] as JsonPrimitive).longOrNull)
    }

    @Test
    fun `push_2h 越作息整单拒`() = runTest {
        val (repo, residual) = fixture()
        val nowMin = 1200 // 块 1280 未开始（>nowMin）且 +120 后越 sleepAdj=1380
        // sleep=1380（23:00）；块 end=nowMin+360 若 +120 超 sleepAdj 即拒——取深夜块
        seedBlock(repo, 1280, 1340, status = "proposed")
        val e = assertFailsWith<ActionException> {
            residual.panicClear(ScheduleCommand.PanicClear(mode = "push_2h", nowMin = nowMin.toLong()))
        }
        assertTrue(e.message.startsWith("后移 2 小时会越出今日作息边界"))
    }

    @Test
    fun `push_2h 无墙成功移动与 postpone_count`() = runTest {
        val (repo, residual) = fixture()
        val nowMin = 600
        val b1 = seedBlock(repo, nowMin + 60, nowMin + 120, status = "proposed")
        val b2 = seedBlock(repo, nowMin + 180, nowMin + 240, status = "confirmed")
        val r = residual.panicClear(ScheduleCommand.PanicClear(mode = "push_2h", nowMin = nowMin.toLong()))
        assertEquals("已把 2 项整体后移 2 小时", r.note)
        val movedIds = (r.data!!["moved"] as JsonArray).map { (it as JsonPrimitive).content }
        assertEquals(listOf(idOf(b1), idOf(b2)), movedIds)
        val fresh = repo.blockById(idOf(b1))!!
        assertEquals(nowMin + 180L, (fresh["start_min"] as JsonPrimitive).longOrNull)
        assertEquals(1L, (fresh["postpone_count"] as JsonPrimitive).longOrNull)
    }

    @Test
    fun `空集短句与未知 mode 拒`() = runTest {
        val (repo, residual) = fixture()
        val nowMin = 600
        val r = residual.panicClear(ScheduleCommand.PanicClear(mode = "clear_remaining", nowMin = nowMin.toLong()))
        assertEquals("今天剩余没有需要熔断的 AI 安排", r.note)
        // 空集短句先于 mode 校验（Dart 语义）——未知 mode 拒需有 targets 才走到 switch
        seedBlock(repo, nowMin + 60, nowMin + 120)
        val e = assertFailsWith<ActionException> {
            residual.panicClear(ScheduleCommand.PanicClear(mode = "nope", nowMin = nowMin.toLong()))
        }
        assertTrue(e.message.startsWith("未知 mode：nope"))
    }

    @Test
    fun `reflow 非低档幂等短句零写`() = runTest {
        val (repo, residual) = fixture()
        val p = seedBlock(repo, 600, 660)
        val r = residual.reflowDay(ScheduleCommand.ReflowDay())
        assertEquals("当前电量非低档，无需降档重算", r.note)
        assertTrue(repo.blockById(idOf(p))!!.toString().contains("proposed"))
    }

    @Test
    fun `reflow 三级阻尼与预算收敛与庆祝豁免`() = runTest {
        val (repo, residual) = fixture()
        repo.settingsSet(buildJsonObject {
            put("wake_time", JsonPrimitive("420"))
            put("sleep_time", JsonPrimitive("1380"))
            put("today_energy", JsonPrimitive("low"))
        })
        val planWithMin = seedPlan(repo, "只做提纲")
        seedPlan(repo, null)
        seedBlock(repo, 420, 720, status = "confirmed", source = "human") // 占用墙 300min
        val a = seedBlock(repo, 720, 840, planId = idOf(planWithMin)) // spark 候选（min_viable 非空）
        val b = seedBlock(repo, 840, 960) // 无 min_viable → 融化
        val c = seedBlock(repo, 960, 1080, isCelebration = true) // 庆祝豁免
        val d = seedBlock(repo, 1080, 1200, planId = idOf(planWithMin)) // spark 候选
        val r = residual.reflowDay(ScheduleCommand.ReflowDay())
        // 预算收敛序（movable 降序 [D,B,A]）：D spark→total 255>165→B melted→total 135≤165→
        // i=2 处理 A 前提前 break（A 原样 proposed）——sparked=1/melted=1
        assertEquals(
            "降档重算完成：1 项切 5 分钟启动版，1 项暂缓放回清单" +
                "（检测到身体电量低，今日任务自动顺延，不产生任何惩罚）",
            r.note,
        )
        // budget = (1380−420−300)×25/100 = 165
        assertEquals(165L, (r.data!!["budget_minutes"] as JsonPrimitive).longOrNull)
        // 降序牺牲：D 压火种（end=1095）/B 融化/A 压火种（end=735）；C 庆祝原样
        val fd = repo.blockById(idOf(d))!!
        assertEquals(1095L, (fd["end_min"] as JsonPrimitive).longOrNull)
        assertEquals("spark", (fd["execution_quality"] as JsonPrimitive).content)
        assertTrue(repo.blockById(idOf(b))!!.toString().contains("melted"))
        val fa = repo.blockById(idOf(a))!!
        assertEquals(840L, (fa["end_min"] as JsonPrimitive).longOrNull) // 预算收敛 break：A 原样
        val fc = repo.blockById(idOf(c))!!
        assertEquals(1080L, (fc["end_min"] as JsonPrimitive).longOrNull)
        val sparkedIds = (r.data["sparked"] as JsonArray).map { (it as JsonPrimitive).content }
        assertEquals(listOf(idOf(d)), sparkedIds)
    }

    @Test
    fun `reflow 例外日 fixed 全空预算抬升`() = runTest {
        val (repo, residual) = fixture()
        repo.settingsSet(buildJsonObject {
            put("wake_time", JsonPrimitive("420"))
            put("sleep_time", JsonPrimitive("1380"))
            put("today_energy", JsonPrimitive("low"))
        })
        repo.replaceFixedSlots(JsonArray(listOf(buildJsonObject {
            put("name", "午休")
            put("weekdays", JsonArray(listOf(JsonPrimitive(java.time.LocalDate.now().dayOfWeek.value))))
            put("start_min", 720)
            put("end_min", 780)
        })))
        seedBlock(repo, 420, 720, status = "confirmed", source = "human")
        seedBlock(repo, 720, 840) // 无 min_viable → 融化
        // exceptions 覆盖今日 → fixed 墙忽略：budget=(1380−420−300)×25/100=165 而非 (600)×25/100=150
        repo.settingsSet(buildJsonObject {
            put("exceptions", JsonPrimitive("""[{"start":"${todayIso()}","end":"${todayIso()}","label":"漫游"}]"""))
        })
        val r = residual.reflowDay(ScheduleCommand.ReflowDay())
        assertEquals(165L, (r.data!!["budget_minutes"] as JsonPrimitive).longOrNull)
    }
}
