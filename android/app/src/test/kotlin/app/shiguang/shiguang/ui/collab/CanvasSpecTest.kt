package app.shiguang.shiguang.ui.collab

import app.shiguang.core.model.collab.quorumDensity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 排期画板规格对拍（behavior/collab_canvas.md §A 逐字，T4.11）：quorumDensity
 * 桶级强度统计（越绿人越多）+长度不齐容错（取最长防越界）+空掩码降级。
 */
class CanvasSpecTest {

    @Test
    fun `共振密度桶级计数`() {
        assertEquals(listOf(2, 1, 1), quorumDensity(listOf("110", "101")))
        assertEquals(listOf(3, 1), quorumDensity(listOf("10", "10", "11")))
        assertEquals(listOf(1), quorumDensity(listOf("1")))
    }

    @Test
    fun `长度不齐取最长防越界`() {
        assertEquals(listOf(2, 1), quorumDensity(listOf("11", "1")))
        assertEquals(listOf(2, 2, 2, 1, 1, 1), quorumDensity(listOf("111", "111111")))
    }

    @Test
    fun `空掩码降级空集`() {
        assertEquals(emptyList<Int>(), quorumDensity(emptyList<String>()))
    }
}
