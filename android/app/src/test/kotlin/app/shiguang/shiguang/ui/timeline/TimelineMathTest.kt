package app.shiguang.shiguang.ui.timeline

import kotlin.test.Test
import kotlin.test.assertEquals

/** TimelineMath 行为对拍（丢失点 #3：goNow 像素换算与钳制公式 JVM 钉死）。 */
class TimelineMathTest {

    // ---- goNowOffsetDp：现在居中 + 双侧钳制 ----

    @Test
    fun `窗内时刻居中换算`() {
        // now=15:00(900) wake=07:00(420) 窗 960min viewport 400 → 480−200=280
        assertEquals(280f, TimelineMath.goNowOffsetDp(900, 420, 960, 400f))
    }

    @Test
    fun `目标为负钳到窗首`() {
        // now=10:00(600) → 180−200=−20 → 0
        assertEquals(0f, TimelineMath.goNowOffsetDp(600, 420, 960, 400f))
    }

    @Test
    fun `目标超尾钳到最大滚动`() {
        // now=22:59(1379) → 959−200=759 > 960−400=560 → 560
        assertEquals(560f, TimelineMath.goNowOffsetDp(1379, 420, 960, 400f))
    }

    @Test
    fun `凌晨时刻钳到窗首`() {
        assertEquals(0f, TimelineMath.goNowOffsetDp(180, 420, 960, 400f))
    }

    @Test
    fun `窗比视口短不滚动`() {
        // 窗 60min(60dp) < viewport 400 → maxScroll=0 恒 0
        assertEquals(0f, TimelineMath.goNowOffsetDp(440, 420, 60, 400f))
    }

    @Test
    fun `窗与视口等高不滚动`() {
        assertEquals(0f, TimelineMath.goNowOffsetDp(600, 420, 400, 400f))
    }

    // ---- topOfDp：分钟 → 顶偏移（窗外钳窗沿）----

    @Test
    fun `窗内分钟线性换算`() {
        assertEquals(180f, TimelineMath.topOfDp(600, 420, 960))
    }

    @Test
    fun `wake 前分钟钳到零`() {
        assertEquals(0f, TimelineMath.topOfDp(300, 420, 960))
    }

    @Test
    fun `窗尾后分钟钳到窗高`() {
        assertEquals(960f, TimelineMath.topOfDp(1500, 420, 960))
    }

    // ---- heightOfDp：跨午夜折返 + 最小块高钳制 ----

    @Test
    fun `普通块按时长换算`() {
        assertEquals(60f, TimelineMath.heightOfDp(600, 660))
    }

    @Test
    fun `跨午夜块经折返计时长`() {
        // 23:30→07:30 = 480min
        assertEquals(480f, TimelineMath.heightOfDp(1410, 450))
    }

    @Test
    fun `过短块钳最小块高`() {
        assertEquals(44f, TimelineMath.heightOfDp(600, 610))
    }
}
