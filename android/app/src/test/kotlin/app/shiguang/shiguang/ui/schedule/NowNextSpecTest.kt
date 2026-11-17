package app.shiguang.shiguang.ui.schedule

import app.shiguang.core.action.NativeSqlRepository
import app.shiguang.core.model.JdbcSqlStore
import app.shiguang.core.model.ScheduleBlock
import app.shiguang.core.model.ShiguangSchema
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** NowNext 行为对拍（Dart now_next_card.dart pickNowAndNext/_compute 镜像）。 */
class NowNextSpecTest {

    private fun block(id: String, start: Long, end: Long, status: String = "confirmed", date: String = "2026-11-15") =
        ScheduleBlock(
            id = id, date = date, startMin = start, endMin = end, planId = null,
            label = null, source = "ai", pinned = false, status = status,
            postponeCount = 0, executionQuality = "full", isDaySpark = false,
            isCelebration = false, createdAt = null, updatedAt = null, version = 0,
        )

    @Test
    fun `当前取最晚开始的罩住块 下一个取最早未来`() {
        val s = pickNowAndNext(
            listOf(block("a", 500, 600), block("b", 560, 700), block("c", 720, 780)),
            nowMin = 600,
        )
        assertEquals("b", s.current?.id, "罩住 now 的最晚开始者")
        assertEquals("c", s.next?.id, "不罩 now 且最早开始")
    }

    @Test
    fun `跨午夜溢出块罩住 now 算进行中`() {
        val s = pickNowAndNext(
            listOf(block("night", 1410, 450, date = "2026-11-14"), block("future", 600, 660)),
            nowMin = 430,
        )
        assertEquals("night", s.current?.id)
        assertEquals("future", s.next?.id)
    }

    @Test
    fun `终态块不参与判定`() {
        val s = pickNowAndNext(
            listOf(block("done", 400, 700, status = "done"), block("missed", 720, 780, status = "missed")),
            nowMin = 600,
        )
        assertNull(s.current)
        assertNull(s.next)
    }

    @Test
    fun `装载器 昨夜块今晨仍罩住才入候选 label 回落计划标题`() = runTest {
        val store = JdbcSqlStore.inMemory()
        ShiguangSchema.createAll(store)
        val repo = NativeSqlRepository(store)
        repo.settingsSet(buildJsonObject { put("wake_time", 420); put("sleep_time", 1380) })
        val plan = repo.addPlan(buildJsonObject { put("title", "写周报") })
        val planId = plan["id"]?.jsonPrimitive?.contentOrNull!!
        repo.addBlock(buildJsonObject {
            put("date", "2026-11-14"); put("start_min", 1410); put("end_min", 450); put("status", "confirmed")
        })
        repo.addBlock(buildJsonObject {
            put("date", "2026-11-15"); put("start_min", 600); put("end_min", 660)
            put("status", "confirmed"); put("plan_id", planId)
        })
        // 已结束的昨夜块（05:00 结束 < now）绝不顶成「下一个」
        repo.addBlock(buildJsonObject {
            put("date", "2026-11-14"); put("start_min", 1410); put("end_min", 300); put("status", "confirmed")
        })
        val s = computeNowNext(repo, LocalDateTime.of(2026, 11, 15, 7, 10))
        assertEquals(1410, s.current?.startMin, "昨夜块今晨未结束=进行中")
        assertEquals("写周报", s.nextLabel, "label 回落计划标题")
        assertEquals("(未命名)", s.currentLabel, "无 label 无计划 → 未命名")
    }
}
