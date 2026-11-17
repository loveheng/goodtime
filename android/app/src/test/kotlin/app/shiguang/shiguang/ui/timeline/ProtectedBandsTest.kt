package app.shiguang.shiguang.ui.timeline

import app.shiguang.core.model.ScheduleBlock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** ProtectedBands 行为对拍（Dart _protectedBands 镜像 + §6.5 三级过滤）。 */
class ProtectedBandsTest {

    private fun block(start: Long, end: Long, source: String = "ai") = ScheduleBlock(
        id = "b-$start", date = "2026-11-15", startMin = start, endMin = end, planId = null,
        label = null, source = source, pinned = false, status = "confirmed",
        postponeCount = 0, executionQuality = "full", isDaySpark = false,
        isCelebration = false, createdAt = null, updatedAt = null, version = 0,
    )

    private val window = 420 to 1380

    @Test
    fun `AI 长块尾部出补给带 余隙为自由流动区`() {
        val occupied = listOf(600 to 690)
        val bands = ProtectedBands.derive(listOf(block(600, 690)), occupied, window.first, window.second)
        val supply = bands.filter { it.supply }
        assertEquals(listOf(690 to 705), supply.map { it.startMin to it.endMin })
        val free = bands.filter { !it.supply }.map { it.startMin to it.endMin }
        assertEquals(listOf(420 to 600, 705 to 1380), free)
    }

    @Test
    fun `human 块不给补给带`() {
        val occupied = listOf(600 to 690)
        val bands = ProtectedBands.derive(listOf(block(600, 690, source = "human")), occupied, window.first, window.second)
        assertTrue(bands.none { it.supply }, "human 块无呼吸位")
    }

    @Test
    fun `AI 短块不超呼吸阈值不出补给带`() {
        val bands = ProtectedBands.derive(listOf(block(600, 630)), listOf(600 to 630), window.first, window.second)
        assertTrue(bands.none { it.supply }, "span ≤ BREATH_MIN 跳过")
    }

    @Test
    fun `现实挤进缓冲只渲染自由部分`() {
        // AI 块 600-690 缓冲 (690,705)，human 块 695-750 挤进：补给带只剩 (690,695)
        val occupied = listOf(600 to 690, 695 to 750)
        val bands = ProtectedBands.derive(
            listOf(block(600, 690), block(695, 750, source = "human")), occupied, window.first, window.second,
        )
        val supply = bands.filter { it.supply }.map { it.startMin to it.endMin }
        assertEquals(listOf(690 to 695), supply)
    }

    @Test
    fun `补给带尾部越窗钳剪`() {
        // 块 1300-1370（span 70）缓冲 (1370,1385) 越窗尾 1380 → 只剩 (1370,1380)
        val bands = ProtectedBands.derive(listOf(block(1300, 1370)), listOf(1300 to 1370), window.first, window.second)
        val supply = bands.filter { it.supply }.map { it.startMin to it.endMin }
        assertEquals(listOf(1370 to 1380), supply)
    }

    @Test
    fun `三级绘制过滤 30_15 阈值`() {
        assertEquals(ProtectedBands.RenderTier.FULL, ProtectedBands.tierOf(ProtectedBand(0, 30, supply = true)))
        assertEquals(ProtectedBands.RenderTier.ICON_ONLY, ProtectedBands.tierOf(ProtectedBand(0, 29, supply = true)))
        assertEquals(ProtectedBands.RenderTier.ICON_ONLY, ProtectedBands.tierOf(ProtectedBand(0, 15, supply = false)))
        assertEquals(ProtectedBands.RenderTier.SILENT, ProtectedBands.tierOf(ProtectedBand(0, 14, supply = false)))
    }
}
