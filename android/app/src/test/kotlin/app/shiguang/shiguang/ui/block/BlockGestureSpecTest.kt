package app.shiguang.shiguang.ui.block

import app.shiguang.core.model.Plan
import app.shiguang.core.model.ScheduleBlock
import app.shiguang.core.tokens.StScale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * BlockRenderer 手势纯逻辑对拍（锁轴/卡点/提示层/挂载闸/高度钳制——§3.4 + 行为清单）。
 * 指针事件状态机本体在 GestureBlock（Compose runtime），中间态经 S4 交互对拍断言验收。
 */
class BlockGestureSpecTest {

    private fun block(
        status: String = BlockStatus.PROPOSED,
        source: String = ScheduleBlock.SOURCE_AI,
        planId: String? = null,
        pinned: Boolean = false,
    ) = ScheduleBlock(
        id = "b1", date = "2026-11-15", startMin = 540, endMin = 600,
        planId = planId, label = "任务", source = source, pinned = pinned,
        status = status, postponeCount = 0, executionQuality = ScheduleBlock.QUALITY_FULL,
        isDaySpark = false, isCelebration = false,
        createdAt = null, updatedAt = null, version = 1,
    )

    // ── 手势挂载闸与锁轴/卡点 ──

    @Test
    fun `gesture gates matrix`() {
        assertTrue(BlockVisualSpec.canSwipe(block()))
        assertTrue(BlockVisualSpec.canSwipe(block(status = BlockStatus.CONFIRMED)))
        assertFalse(BlockVisualSpec.canSwipe(block(status = BlockStatus.DONE)))
        assertFalse(BlockVisualSpec.canSwipe(block(status = BlockStatus.MELTED)))
        assertFalse(BlockVisualSpec.canSwipe(block(status = BlockStatus.ARCHIVED)))
        assertTrue(BlockVisualSpec.canDegrade(block()))
        assertFalse(BlockVisualSpec.canDegrade(block(source = ScheduleBlock.SOURCE_HUMAN)))
    }

    @Test
    fun `axis lock thresholds are strict`() {
        assertFalse(BlockVisualSpec.axisLocked(20f, 1f))
        assertTrue(BlockVisualSpec.axisLocked(20.1f, 1f))
        assertFalse(BlockVisualSpec.axisLocked(30f, 20f))
        assertTrue(BlockVisualSpec.axisLocked(30f, 10f))
    }

    @Test
    fun `snap fractions inclusive boundaries`() {
        assertEquals(SwipeAction.MELT, BlockVisualSpec.resolveSwipeAction(90f, 200f))
        assertEquals(SwipeAction.NONE, BlockVisualSpec.resolveSwipeAction(89f, 200f))
        assertEquals(SwipeAction.SWAP, BlockVisualSpec.resolveSwipeAction(-70f, 200f))
        assertEquals(SwipeAction.NONE, BlockVisualSpec.resolveSwipeAction(-69f, 200f))
        assertEquals(SwipeAction.NONE, BlockVisualSpec.resolveSwipeAction(90f, 0f))
    }

    @Test
    fun `hint layers visible past snap only`() {
        assertTrue(BlockVisualSpec.meltHintVisible(90f, 200f))
        assertFalse(BlockVisualSpec.meltHintVisible(89f, 200f))
        assertTrue(BlockVisualSpec.swapHintVisible(-70f, 200f))
        assertFalse(BlockVisualSpec.swapHintVisible(-69f, 200f))
    }

    @Test
    fun `height clamped to 44dp floor`() {
        assertEquals(44f, BlockVisualSpec.clampedHeightDp(5f))
        assertEquals(50f, BlockVisualSpec.clampedHeightDp(50f))
        assertEquals(StScale.blockMinHeightDp, BlockVisualSpec.clampedHeightDp(0f))
    }
}
