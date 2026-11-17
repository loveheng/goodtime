package app.shiguang.core.action

import app.shiguang.core.model.Artifact
import app.shiguang.core.model.ArtifactRules
import app.shiguang.core.model.ScheduleBlock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 临场通关条族 + 一致性派生对拍（D25 转正清单 §A：behavior/{action_pass_bar,fact_sheet}.md）：
 * normalizeRole/roleLabel/computeActionPasses/factInconsistentWithBlock——口径=
 * lib/action/fact_rules.dart + lib/ui/fact_sheet.dart 逐字镜像（本地物理时钟注入式）。
 */
class FactRulesPassTest {

    private fun fact(
        title: String = "去车站",
        state: String = Artifact.STATE_STRUCTURED,
        anchors: List<JsonObject> = emptyList(),
        constraints: JsonObject? = null,
    ): JsonObject = buildJsonObject {
        put("state", state)
        put("title", title)
        put("payload", buildJsonObject {
            put("v", 1)
            put("time_anchors", JsonArray(anchors))
            if (constraints != null) put("constraints", constraints)
        })
    }

    private fun moment(role: String?, date: String, min: Int): JsonObject = buildJsonObject {
        put("kind", "moment")
        if (role != null) put("role", role)
        put("date", date)
        put("min", min)
    }

    private fun span(date: String, startMin: Int, endDate: String? = null, endMin: Int? = null): JsonObject = buildJsonObject {
        put("kind", "span")
        put("date", date)
        put("start_min", startMin)
        if (endDate != null) put("end_date", endDate)
        if (endMin != null) put("end_min", endMin)
    }

    private fun block(date: String, startMin: Long, endMin: Long): ScheduleBlock = ScheduleBlock(
        id = "b1", date = date, startMin = startMin, endMin = endMin, planId = null,
        label = null, source = ScheduleBlock.SOURCE_AI, pinned = false,
        status = ScheduleBlock.STATUS_CONFIRMED, postponeCount = 0,
        version = 0, createdAt = null, updatedAt = null,
        executionQuality = "", isDaySpark = false, isCelebration = false,
    )

    // ── role 表 ─────────────────────────────────────────────────────────────────

    @Test
    fun `normalizeRole 行动类与信息类直传未知回落`() {
        for (r in listOf("departure", "deadline", "checkin", "checkout", "arrival", "reference")) {
            assertEquals(r, FactRules.normalizeRole(r))
        }
        assertEquals("reference", FactRules.normalizeRole("检票")) // 存量中文容错不迁移
        assertEquals("reference", FactRules.normalizeRole(null))
    }

    @Test
    fun `roleLabel 白话表全行`() {
        assertEquals("发车/起飞", FactRules.roleLabel("departure"))
        assertEquals("停止检票", FactRules.roleLabel("deadline"))
        assertEquals("开放入住", FactRules.roleLabel("checkin"))
        assertEquals("退房", FactRules.roleLabel("checkout"))
        assertEquals("到达", FactRules.roleLabel("arrival"))
        assertEquals("参考", FactRules.roleLabel("reference"))
        assertEquals("参考", FactRules.roleLabel("junk"))
    }

    @Test
    fun `通关条常量 定案值`() {
        assertEquals(120, ArtifactRules.PASS_LEAD_MINUTES)
        assertEquals(20, ArtifactRules.PASS_DISMISS_AFTER_MINUTES)
    }

    // ── computeActionPasses 窗口 ───────────────────────────────────────────────

    private val actionDay = LocalDate.of(2026, 11, 20)

    /** action=2026-11-20 10:00 → 无 advance：trigger=08:00 / dismiss=10:20。 */
    private val baseFact = fact(anchors = listOf(moment("departure", "2026-11-20", 600)))

    @Test
    fun `窗口闭区间 边界恰含与恰外`() {
        val inTrigger = LocalDateTime.of(2026, 11, 20, 8, 0)
        val beforeTrigger = LocalDateTime.of(2026, 11, 20, 7, 59)
        val inDismiss = LocalDateTime.of(2026, 11, 20, 10, 20)
        val afterDismiss = LocalDateTime.of(2026, 11, 20, 10, 21)
        assertEquals(1, computeActionPasses(listOf(baseFact), inTrigger).size)
        assertEquals(0, computeActionPasses(listOf(baseFact), beforeTrigger).size)
        assertEquals(1, computeActionPasses(listOf(baseFact), inDismiss).size)
        assertEquals(0, computeActionPasses(listOf(baseFact), afterDismiss).size)
    }

    @Test
    fun `advance 抬前 T_target 与建议到站源`() {
        val f = fact(
            anchors = listOf(moment("checkin", "2026-11-20", 600)),
            constraints = buildJsonObject { put("advance_arrival_minutes", 30) },
        )
        val now = LocalDateTime.of(2026, 11, 20, 7, 29) // trigger=(600−30)−120=450=07:30 之前一刻
        assertEquals(0, computeActionPasses(listOf(f), now).size)
        val now2 = LocalDateTime.of(2026, 11, 20, 7, 31) // 已进窗
        val passes = computeActionPasses(listOf(f), now2)
        assertEquals(1, passes.size)
        assertEquals(LocalDateTime.of(2026, 11, 20, 10, 0), passes[0].actionTime)
        assertEquals(LocalDateTime.of(2026, 11, 20, 7, 30), passes[0].triggerTime)
        // advance 缺省=0（constraints 无键 → 窗口=action−120）：now=trigger 恰含
        assertNull(advanceArrivalMinutes(fact()))
        assertEquals(
            1,
            computeActionPasses(
                listOf(fact(anchors = listOf(moment("departure", "2026-11-20", 600)))),
                LocalDateTime.of(2026, 11, 20, 8, 0),
            ).size,
        )
    }

    @Test
    fun `过滤面 非行动类与非moment与非structured不触发`() {
        val now = LocalDateTime.of(2026, 11, 20, 9, 0)
        assertEquals(0, computeActionPasses(listOf(fact(anchors = listOf(moment("arrival", "2026-11-20", 600)))), now).size, "arrival=信息类不触发")
        assertEquals(0, computeActionPasses(listOf(fact(anchors = listOf(moment(null, "2026-11-20", 600)))), now).size, "role 缺=reference 不触发")
        assertEquals(0, computeActionPasses(listOf(fact(anchors = listOf(span("2026-11-20", 600)))), now).size, "非 moment 锚跳过")
        assertEquals(
            0,
            computeActionPasses(
                listOf(fact(state = Artifact.STATE_RAW, anchors = listOf(moment("departure", "2026-11-20", 600)))),
                now,
            ).size,
            "raw 未解析不触发",
        )
        // min 缺 → 锚跳过不抛
        val noMin = buildJsonObject { put("kind", "moment"); put("role", "departure"); put("date", "2026-11-20"); put("min", JsonNull) }
        assertEquals(0, computeActionPasses(listOf(fact(anchors = listOf(noMin))), now).size)
    }

    @Test
    fun `多条按 actionTime 升序`() {
        val f = fact(
            title = "双锚",
            anchors = listOf(moment("departure", "2026-11-20", 660), moment("checkin", "2026-11-20", 600)),
        )
        val now = LocalDateTime.of(2026, 11, 20, 9, 30) // 两锚均在窗（600: 08:00–10:20 / 660: 09:00–11:20）
        val passes = computeActionPasses(listOf(f), now)
        assertEquals(listOf(600, 660), passes.map { it.actionTime.hour * 60 + it.actionTime.minute })
    }

    // ── 一致性派生（贪心选锚）───────────────────────────────────────────────────

    @Test
    fun `块空静默不检`() {
        assertFalse(factInconsistentWithBlock(baseFact, null))
    }

    @Test
    fun `一致 span 不亮琥珀条`() {
        val f = fact(anchors = listOf(span("2026-11-20", 600, endMin = 700)))
        assertFalse(factInconsistentWithBlock(f, block("2026-11-20", 600, 700)))
    }

    @Test
    fun `span 偏移亮琥珀条`() {
        val f = fact(anchors = listOf(span("2026-11-20", 600, endMin = 690)))
        assertTrue(factInconsistentWithBlock(f, block("2026-11-20", 600, 700)))
    }

    @Test
    fun `贪心选锚 重叠最大者优先`() {
        val f = fact(
            anchors = listOf(
                span("2026-11-19", 600, endMin = 660), // 与块重叠 0（前一晚，不在窗）
                span("2026-11-20", 590, endMin = 680), // 重叠 80 分钟（590–680 vs 600–700）
                span("2026-11-20", 620, endMin = 640), // 重叠 20 分钟
            ),
        )
        // 选中 590–680 与块 600–700 不一致 → true（若误选 620–640 同样 true；此处钉选中态：
        // 把块改成与 620–640 相同 → 贪心仍选重叠最大的 590–680 → 不一致）
        assertTrue(factInconsistentWithBlock(f, block("2026-11-20", 620, 640)))
        // 块=重叠最大锚原样 → 一致（排除起始最早者 590–680 被错误选中的可能）
        assertFalse(factInconsistentWithBlock(f, block("2026-11-20", 590, 680)))
    }

    @Test
    fun `均不重叠取起始最早`() {
        val f = fact(
            anchors = listOf(
                span("2026-11-20", 200, endMin = 300),
                span("2026-11-20", 500, endMin = 560),
            ),
        )
        // 块 600–700 与两锚均不重叠 → 选起始最早 200–300 → 不一致
        assertTrue(factInconsistentWithBlock(f, block("2026-11-20", 600, 700)))
    }

    @Test
    fun `跨午夜块与 span 按绝对时刻折算`() {
        val f = fact(anchors = listOf(span("2026-11-20", 1410, endDate = "2026-11-21", endMin = 90)))
        // 块 23:30 → 次日 01:30（endMin=90 < b0 → +1 天折算）与 span 完全一致
        assertFalse(factInconsistentWithBlock(f, block("2026-11-20", 1410, 90)))
    }
}
