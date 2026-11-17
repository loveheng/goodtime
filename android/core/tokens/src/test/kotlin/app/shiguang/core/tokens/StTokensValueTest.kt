package app.shiguang.core.tokens

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 逐值对拍断言：本测试即 behavior/design_tokens.md 承诺的「Kotlin↔tokens.dart
 * 逐值 diff 断言」——期望值逐字转录自 lib/theme/tokens.dart（2026-10-05 拍板定稿），
 * 改常量不先改 ui-design/tokens.dart 此处即红（回归钉）。
 */
class StTokensValueTest {
    @Test
    fun `light palette mirrors tokens dart §1_1`() {
        val c = StColorsPalette.LIGHT
        assertEquals(0xFF283593L, c.deepFocusBg)
        assertEquals(0xFFFFFFFFL, c.deepFocusText)
        assertEquals(0xFFFFB300L, c.sparkStroke)
        assertEquals(0xFFFFF8E1L, c.sparkBg)
        assertEquals(0x1FE3F2FDL, c.roamBg)
        assertEquals(0x6690CAF9L, c.roamStroke)
        assertEquals(0xFFFFF3D6L, c.celebrationBg)
        assertEquals(0xFFD4AF37L, c.celebrationStroke)
        assertEquals(0xFFFFECB3L, c.voucherBg)
        assertEquals(0xFFE8F5E9L, c.supplyBandBg)
        assertEquals(0xFF81C784L, c.supplyBandStroke)
        assertEquals(0xFFF1F8E9L, c.freeFlowBg)
        assertEquals(0x6678909CL, c.humanBlockBg)
        assertEquals(0xFFFF9800L, c.overlapHint)
        assertEquals(0xFFECEFF1L, c.fixedSlotBg)
        assertEquals(0xFFF5F5F5L, c.projectionBg)
        assertEquals(0xFFFFF3E0L, c.missedAccent)
        assertEquals(0xFFBDBDBDL, c.safelineOff)
        assertEquals(0xFFD4AF37L, c.safelineOn)
        assertEquals(0xFF1A1C1EL, c.textPrimary)
        assertEquals(0xFF606468L, c.textSecondary)
    }

    @Test
    fun `dark palette mirrors tokens dart §1_2`() {
        val c = StColorsPalette.DARK
        assertEquals(0xFF283593L, c.deepFocusBg)
        assertEquals(0xFFFFFFFFL, c.deepFocusText)
        assertEquals(0xFFFFB300L, c.sparkStroke)
        assertEquals(0xFF332C10L, c.sparkBg)
        assertEquals(0x1F90CAF9L, c.roamBg)
        assertEquals(0x6690CAF9L, c.roamStroke)
        assertEquals(0xFF3B3323L, c.celebrationBg)
        assertEquals(0xFFD4AF37L, c.celebrationStroke)
        assertEquals(0xFF3A2F12L, c.voucherBg)
        assertEquals(0xFF1E3324L, c.supplyBandBg)
        assertEquals(0xFF81C784L, c.supplyBandStroke)
        assertEquals(0xFF1F2E1BL, c.freeFlowBg)
        assertEquals(0x5478909CL, c.humanBlockBg)
        assertEquals(0xFFFF9800L, c.overlapHint)
        assertEquals(0xFF26292BL, c.fixedSlotBg)
        assertEquals(0xFF2A2D2FL, c.projectionBg)
        assertEquals(0xFF3A2F1BL, c.missedAccent)
        assertEquals(0xFF616161L, c.safelineOff)
        assertEquals(0xFFD4AF37L, c.safelineOn)
        assertEquals(0xFFE3E2E0L, c.textPrimary)
        assertEquals(0xFF9CA0A5L, c.textSecondary)
    }

    @Test
    fun `archived fade alpha mirrors ui-design §1_1 15 percent`() {
        assertEquals(0.15f, StColorsPalette.archivedFadeAlpha)
    }

    @Test
    fun `scale tokens mirror tokens dart`() {
        assertEquals(1.0f, StScale.dpPerMinute)
        assertEquals(44f, StScale.blockMinHeightDp)
        assertEquals(12f, StScale.radiusBlock)
        assertEquals(16f, StScale.radiusCard)
        assertEquals(999f, StScale.radiusCapsule)
        assertEquals(4f, StScale.insetXs)
        assertEquals(8f, StScale.insetSm)
        assertEquals(12f, StScale.insetMd)
        assertEquals(16f, StScale.insetLg)
        assertEquals(20f, StScale.insetXl)
        assertEquals(88f, StScale.fabClearanceDp)
    }

    @Test
    fun `motion tokens mirror tokens dart`() {
        assertEquals(300, StMotion.collapseMs)
        assertEquals(400, StMotion.meltMs)
        assertEquals(250, StMotion.safelineMs)
        assertEquals(0.75f, StMotion.springDampingRatio)
        assertEquals(400f, StMotion.springStiffness)
        assertEquals(0f, StMotion.easeOutX1)
        assertEquals(0f, StMotion.easeOutY1)
        assertEquals(0.58f, StMotion.easeOutX2)
        assertEquals(1f, StMotion.easeOutY2)
        assertEquals(0.42f, StMotion.easeInOutX1)
        assertEquals(0f, StMotion.easeInOutY1)
        assertEquals(0.58f, StMotion.easeInOutX2)
        assertEquals(1f, StMotion.easeInOutY2)
        assertEquals(0.4f, StMotion.fastOutSlowInX1)
        assertEquals(0f, StMotion.fastOutSlowInY1)
        assertEquals(0.2f, StMotion.fastOutSlowInX2)
        assertEquals(1f, StMotion.fastOutSlowInY2)
    }

    @Test
    fun `gesture tokens mirror tokens dart`() {
        assertEquals(20f, StGesture.horizontalSlopDp)
        assertEquals(2.0f, StGesture.axisRatioMin)
        assertEquals(0.45f, StGesture.meltFraction)
        assertEquals(0.35f, StGesture.swapFraction)
        assertEquals(400, StGesture.longPressMs)
        assertEquals(0.98f, StGesture.longPressScale)
        assertEquals(60f, StGesture.pageSwipeDp)
        assertEquals(30, StGesture.bandFullMin)
        assertEquals(15, StGesture.bandIconMin)
    }

    @Test
    fun `block renderer ui metrics mirror behavior list`() {
        // D12 补实现件（behavior/block_renderer.md 过目值；虚线节距=常规留痕非规范值）
        assertEquals(1.2f, StUi.proposedBorderWidthDp)
        assertEquals(1f, StUi.collisionHintWidthDp)
        assertEquals(14f, StUi.doneBadgeSizeDp)
        assertEquals(10f, StUi.badgeGlyphSp)
        assertEquals(9f, StUi.chipTextSp)
        assertEquals(11f, StUi.hintOverlaySp)
        assertEquals(8f, StUi.footnoteSp)
        assertEquals(0.6f, StUi.missDesaturation)
        assertEquals(4f, StUi.dashLenDp)
        assertEquals(3f, StUi.dashGapDp)
        assertEquals(40f, StUi.badgeOffsetOverlapDp)
        assertEquals(16f, StUi.badgeOffsetCelebrationDp)
        assertEquals(12f, StUi.voucherStackDp)
        assertEquals(96f, StUi.voucherBadgeMaxWidthDp)
        assertEquals(1f, StUi.chipPadVDp)
    }
}
