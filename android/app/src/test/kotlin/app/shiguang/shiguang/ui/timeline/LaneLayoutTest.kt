package app.shiguang.shiguang.ui.timeline

import app.shiguang.core.model.ScheduleBlock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** LaneLayout 行为对拍（Dart _layoutLanes/_assignLanes 镜像；等宽分栏 N≥2）。 */
class LaneLayoutTest {

    private fun block(id: String, start: Long, end: Long) = ScheduleBlock(
        id = id, date = "2026-11-15", startMin = start, endMin = end, planId = null,
        label = null, source = "ai", pinned = false, status = "confirmed",
        postponeCount = 0, executionQuality = "full", isDaySpark = false,
        isCelebration = false, createdAt = null, updatedAt = null, version = 0,
    )

    @Test
    fun `无重叠单车道整宽`() {
        val slots = LaneLayout.layout(listOf(block("a", 600, 660), block("b", 700, 760)), 300f)
        assertEquals(2, slots.size)
        assertTrue(slots.all { it.lane == 0 }, "不重叠全排 lane 0")
        assertEquals(300f, slots[0].laneWidthDp)
    }

    @Test
    fun `重叠双块两车道等宽`() {
        val slots = LaneLayout.layout(listOf(block("a", 600, 660), block("b", 630, 690)), 300f)
        assertEquals(2, slots.size)
        assertEquals(0, slots.first { it.block.id == "a" }.lane)
        assertEquals(1, slots.first { it.block.id == "b" }.lane)
        assertEquals(150f, slots[0].laneWidthDp, "等宽分栏 N≥2")
    }

    @Test
    fun `首尾相接切簇各自整宽`() {
        // startMin >= clusterEnd（600 >= 600）→ 切簇，两块各 lane 0 整宽
        val slots = LaneLayout.layout(listOf(block("a", 540, 600), block("b", 600, 660)), 300f)
        assertTrue(slots.all { it.lane == 0 && it.laneWidthDp == 300f })
    }

    @Test
    fun `greedy 车道复用最先空闲车道`() {
        // a 540-600 b 570-630；c 615 开始 → lane0 末 600 ≤ 615 复用 lane0
        val slots = LaneLayout.layout(
            listOf(block("a", 540, 600), block("b", 570, 630), block("c", 615, 660)), 300f,
        )
        assertEquals(0, slots.first { it.block.id == "a" }.lane)
        assertEquals(1, slots.first { it.block.id == "b" }.lane)
        assertEquals(0, slots.first { it.block.id == "c" }.lane, "greedy 复用最先空闲车道")
        assertEquals(150f, slots[0].laneWidthDp)
    }

    @Test
    fun `跨午夜块经折返计车道占用`() {
        // 两个跨午夜块折返后重叠：23:30–07:30(endAbs 1890) × 23:40–06:40(endAbs 1840) 同簇两车道
        val slots = LaneLayout.layout(listOf(block("night1", 1410, 450), block("night2", 1420, 400)), 300f)
        assertEquals(0, slots.first { it.block.id == "night1" }.lane)
        assertEquals(1, slots.first { it.block.id == "night2" }.lane)
        assertEquals(150f, slots[0].laneWidthDp)
    }

    @Test
    fun `跨午夜块与白天块不相交切簇各自整宽`() {
        // 23:30–07:30 折返 endAbs=1890，但 02:00 块画布位在窗顶不重叠 → 切簇各 lane 0
        val slots = LaneLayout.layout(listOf(block("morning", 120, 180), block("night", 1410, 450)), 300f)
        assertTrue(slots.all { it.lane == 0 && it.laneWidthDp == 300f })
    }
}
