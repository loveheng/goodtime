package app.shiguang.shiguang.ui.block

import app.shiguang.core.model.Plan
import app.shiguang.core.model.ScheduleBlock
import app.shiguang.core.tokens.StScale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * BlockRenderer 纯逻辑对拍（behavior/block_renderer.md 已过目值；呈现层 JVM 可测面——
 * 形态判定序/文案链/角标错位/降饱和/锁轴/卡点/挂载闸）。Compose 视觉本体经页面轮
 * 像素基线勾稽验收（P4 像素 diff 管线），S4 手势中间态=交互对拍断言（真机）。
 */
class BlockVisualSpecTest {

    private fun block(
        status: String = BlockStatus.PROPOSED,
        source: String = ScheduleBlock.SOURCE_AI,
        quality: String = ScheduleBlock.QUALITY_FULL,
        celebration: Boolean = false,
        pinned: Boolean = false,
        planId: String? = null,
        label: String? = "任务",
        startMin: Long = 540,
        endMin: Long = 600,
    ) = ScheduleBlock(
        id = "b1", date = "2026-11-15", startMin = startMin, endMin = endMin,
        planId = planId, label = label, source = source, pinned = pinned,
        status = status, postponeCount = 0, executionQuality = quality,
        isDaySpark = false, isCelebration = celebration,
        createdAt = null, updatedAt = null, version = 1,
    )

    private fun plan(energyLevel: String = Plan.ENERGY_LIGHT, minViable: String? = null) = Plan(
        id = "p1", title = "计划", spec = null, notes = null, openItems = emptyList(),
        minViableAction = minViable, energyLevel = energyLevel, toolRequired = "",
        rewardSpec = null, importance = false, deadline = null, estimate = null,
        parentId = null, archived = false, createdAt = null, updatedAt = null, version = 1,
    )

    // ── 形态判定序：庆祝 > 火种 > 深度 > 漫游 > human > 默认 ──

    @Test
    fun `celebration wins over spark`() {
        assertEquals(
            BlockForm.CELEBRATION,
            BlockVisualSpec.resolveForm(block(celebration = true, quality = ScheduleBlock.QUALITY_SPARK), plan(), false, false),
        )
    }

    @Test
    fun `spark wins over deep`() {
        assertEquals(
            BlockForm.SPARK,
            BlockVisualSpec.resolveForm(block(quality = ScheduleBlock.QUALITY_SPARK), plan(Plan.ENERGY_DEEP), false, false),
        )
    }

    @Test
    fun `deep requires ai plan deep energy and not low`() {
        assertEquals(
            BlockForm.DEEP,
            BlockVisualSpec.resolveForm(block(), plan(Plan.ENERGY_DEEP), false, false),
        )
        assertEquals(
            BlockForm.DEFAULT,
            BlockVisualSpec.resolveForm(block(), plan(Plan.ENERGY_DEEP), true, false),
        )
        assertEquals(
            BlockForm.HUMAN,
            BlockVisualSpec.resolveForm(block(source = ScheduleBlock.SOURCE_HUMAN), plan(Plan.ENERGY_DEEP), false, false),
        )
        assertEquals(
            BlockForm.DEFAULT,
            BlockVisualSpec.resolveForm(block(), plan(Plan.ENERGY_LIGHT), false, false),
        )
    }

    @Test
    fun `roam triggers by day flag or span 180`() {
        assertEquals(BlockForm.ROAM, BlockVisualSpec.resolveForm(block(), plan(), false, true))
        assertEquals(
            BlockForm.ROAM,
            BlockVisualSpec.resolveForm(block(startMin = 480, endMin = 660), plan(), false, false),
        )
        // 漫游在判定序中先于 human：长时 human 块也落漫游形态
        assertEquals(
            BlockForm.ROAM,
            BlockVisualSpec.resolveForm(block(source = ScheduleBlock.SOURCE_HUMAN, startMin = 480, endMin = 660), plan(), false, false),
        )
    }

    @Test
    fun `roam checked after deep per precedence`() {
        // 深度 + 180min：深度优先（判定序），不落漫游
        assertEquals(
            BlockForm.DEEP,
            BlockVisualSpec.resolveForm(block(startMin = 480, endMin = 660), plan(Plan.ENERGY_DEEP), false, false),
        )
    }

    // ── span 与跨午夜口径 ──

    @Test
    fun `span normal and cross midnight`() {
        assertEquals(60L, BlockVisualSpec.spanMinutes(540, 600))
        assertEquals(120L, BlockVisualSpec.spanMinutes(1410, 90))
    }

    // ── 文案链 ──

    @Test
    fun `spark uses min viable action with fallback chain`() {
        assertEquals("只做 5 分钟", BlockVisualSpec.labelText(block(quality = ScheduleBlock.QUALITY_SPARK), plan(minViable = "只做 5 分钟")))
        assertEquals("任务", BlockVisualSpec.labelText(block(quality = ScheduleBlock.QUALITY_SPARK), plan(minViable = null)))
        assertEquals("(未命名)", BlockVisualSpec.labelText(block(label = null), null))
        assertEquals("计划", BlockVisualSpec.labelText(block(label = null), plan()))
    }

    @Test
    fun `time prefix tilde only for roam`() {
        val b = block(startMin = 540, endMin = 600)
        assertEquals("09:00 ", BlockVisualSpec.timePrefix(b, false))
        assertEquals("09:00~10:00 ", BlockVisualSpec.timePrefix(b, true))
    }

    // ── 降饱和（D12 F2/F3）──

    @Test
    fun `desaturate gray unchanged and saturated reduced`() {
        assertEquals(0xFF808080L, BlockVisualSpec.desaturate(0xFF808080L, 0.6f))
        assertEquals(0xFFFFFFFFL, BlockVisualSpec.desaturate(0xFFFFFFFFL, 0.6f))
        val red = BlockVisualSpec.desaturate(0xFFFF0000L, 0.6f)
        // 纯红降饱和 40% 后 r-g 间距收窄（仍红但不艳）
        val r = (red ushr 16) and 0xFF; val g = (red ushr 8) and 0xFF
        assertTrue(r > g && r - g < 255)
    }

    // ── 角标错位（§5.3）──

    @Test
    fun `badge offsets shift by proposal and celebration`() {
        val proposed = BlockVisualSpec.badgeLayout(block())
        assertEquals(true, proposed.proposalChip)
        assertEquals(40f, proposed.celebrationOffsetDp)
        val celebration = BlockVisualSpec.badgeLayout(block(status = BlockStatus.CONFIRMED, celebration = true))
        assertEquals(0f, celebration.celebrationOffsetDp)
        assertEquals(16f, celebration.pinOffsetDp)
        val plain = BlockVisualSpec.badgeLayout(block(status = BlockStatus.CONFIRMED))
        assertEquals(0f, plain.pinOffsetDp)
        val pinnedWithPlan = BlockVisualSpec.badgeLayout(block(status = BlockStatus.CONFIRMED, pinned = true, planId = "p1"))
        assertEquals(false, pinnedWithPlan.planBadge)
        assertEquals(0f, pinnedWithPlan.voucherTopDp)
        val withPlan = BlockVisualSpec.badgeLayout(block(status = BlockStatus.CONFIRMED, planId = "p1"))
        assertEquals(true, withPlan.planBadge)
        assertEquals(12f, withPlan.voucherTopDp)
    }
}
