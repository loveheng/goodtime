package app.shiguang.shiguang.ui.schedule

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test
import java.time.LocalDate
import kotlin.test.assertEquals

/**
 * N6 周月视图纯逻辑对拍：月历网格（前导空位=weekday−1/翻月保留几号钳月末——
 * Dart _shiftMonth 语义：从原 day 对目标月末 clamp，非 plusMonths 链式）+ 周列
 * 车道 greedy（Dart _layout 镜像：单数组 laneEnds/跨午夜 endAbs+1440/laneWidth=宽/maxLane）。
 */
class WeekMonthGridTest {

    @Test
    fun `月历前导空位 周一起始`() {
        assertEquals(0, MonthGrid.leadingBlanks(LocalDate.of(2026, 11, 16))) // 周一
        assertEquals(6, MonthGrid.leadingBlanks(LocalDate.of(2026, 11, 1))) // 周日
        assertEquals(2, MonthGrid.leadingBlanks(LocalDate.of(2026, 11, 4))) // 周三（前 2 空位）
    }

    @Test
    fun `翻月保留几号 大月钳小月末`() {
        // 1/31 → 2 月钳 2/28（2026 非闰年）
        assertEquals(LocalDate.of(2026, 2, 28), MonthGrid.shiftMonthPreservingDay(LocalDate.of(2026, 1, 31), 1))
        // 1/31 → 3 月=3/31（非链式：从原 day 对目标月末 clamp）
        assertEquals(LocalDate.of(2026, 3, 31), MonthGrid.shiftMonthPreservingDay(LocalDate.of(2026, 1, 31), 2))
        // 1/31 → 上年 12/31
        assertEquals(LocalDate.of(2025, 12, 31), MonthGrid.shiftMonthPreservingDay(LocalDate.of(2026, 1, 31), -1))
        // 2/28 → 3/28（几号 28 未超月末不钳）
        assertEquals(LocalDate.of(2026, 3, 28), MonthGrid.shiftMonthPreservingDay(LocalDate.of(2026, 2, 28), 1))
    }

    private fun blockJson(id: String, startMin: Int, endMin: Int): kotlinx.serialization.json.JsonObject =
        buildJsonObject {
            put("id", id)
            put("start_min", startMin)
            put("end_min", endMin)
        }

    private fun laneOf(slots: List<WeekLaneSlot>, id: String): Int =
        slots.first { (it.block["id"] as JsonPrimitive).content == id }.lane

    @Test
    fun `周列车道 不重叠单车道`() {
        val slots = layoutBlocks(listOf(blockJson("a", 600, 660), blockJson("b", 700, 760)), 112f)
        assertEquals(0, laneOf(slots, "a"))
        assertEquals(0, laneOf(slots, "b"))
        assertEquals(112f, slots[0].laneWidth)
    }

    @Test
    fun `周列车道 重叠切双车道 laneWidth 减半`() {
        val slots = layoutBlocks(listOf(blockJson("a", 600, 700), blockJson("b", 650, 750)), 112f)
        assertEquals(0, laneOf(slots, "a"))
        assertEquals(1, laneOf(slots, "b"))
        assertEquals(56f, slots[0].laneWidth)
    }

    @Test
    fun `周列车道 greedy 复用释放车道`() {
        // a(600-700) b(650-750) c(700-800)：a 结束 700≤c 开始 → c 复用 a 车道（lane 0）
        val slots = layoutBlocks(listOf(blockJson("a", 600, 700), blockJson("b", 650, 750), blockJson("c", 700, 800)), 112f)
        assertEquals(0, laneOf(slots, "a"))
        assertEquals(1, laneOf(slots, "b"))
        assertEquals(0, laneOf(slots, "c"))
        assertEquals(56f, slots[0].laneWidth)
    }

    @Test
    fun `周列车道 跨午夜 endAbs 加 1440`() {
        // a 跨午夜 1400-100（endAbs=1540）与 b 1440-1500 起点重叠 a 尾 → b 进车道 1
        val slots = layoutBlocks(listOf(blockJson("a", 1400, 100), blockJson("b", 1440, 1500)), 112f)
        assertEquals(0, laneOf(slots, "a"))
        assertEquals(1, laneOf(slots, "b"))
    }
}
