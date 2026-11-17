package app.shiguang.shiguang.ui.block

import app.shiguang.core.model.Plan
import app.shiguang.core.model.ScheduleBlock
import app.shiguang.core.tokens.StGesture
import app.shiguang.core.tokens.StScale
import app.shiguang.core.tokens.StUi
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 块渲染状态字符串（裸字符串惯例——Housekeeper/ScheduleQueries 同口径；常量收口于 UI 侧不扩 core）。
 */
object BlockStatus {
    const val PROPOSED = "proposed"
    const val CONFIRMED = "confirmed"
    const val DONE = "done"
    const val SKIPPED = "skipped"
    const val MISSED = "missed"
    const val ARCHIVED = "archived"
    const val MELTED = "melted"
}

/** 四形态（ui-design §5.1）。 */
enum class BlockForm { CELEBRATION, SPARK, DEEP, ROAM, HUMAN, DEFAULT }

/** 手势松手卡点判定（§5.4）。 */
enum class SwipeAction { MELT, SWAP, NONE }

/** 角标共存错位布局（§5.3：提案 > 庆祝 > pin 依次让位；📋/🎫 左侧栈）。 */
data class BlockBadgeLayout(
    val proposalChip: Boolean,
    val celebrationOffsetDp: Float,
    val pinOffsetDp: Float,
    val planBadge: Boolean,
    val voucherTopDp: Float,
)

/**
 * BlockRenderer 纯逻辑（behavior/block_renderer.md 已过目转正 2026-11-15，生成通道输入）。
 * 零 Compose/Android 依赖（JVM 可测）；呈现层零命令面触达，写路径经回调上抛。
 */
object BlockVisualSpec {

    // UNCERTAIN 已消（2026-11-16 真机对拍）：PlaceBlock 1410→90 跨午夜落库 → 开始日
    // chip 23:30 渲染至日界、次日页无尾巴——「按开始日归属不拆段」读法实证，span=
    // end+1440−start 维持（基线 schedule_page_S3 同态另有保护区提案三级对拍面）。
    fun spanMinutes(startMin: Long, endMin: Long): Long =
        if (endMin > startMin) endMin - startMin else endMin + 1440 - startMin

    /** 漫游态触发：例外日或时长 ≥180min（§5.1；`~` 仅渲染层，数据精确起止）。 */
    fun isRoam(roamDay: Boolean, block: ScheduleBlock): Boolean =
        roamDay || spanMinutes(block.startMin, block.endMin) >= 180

    /** 形态判定序：庆祝 > 火种 > 深度 > 漫游 > human > 默认（行为清单 precedence）。 */
    fun resolveForm(
        block: ScheduleBlock,
        plan: Plan?,
        energyLow: Boolean,
        roamDay: Boolean,
    ): BlockForm {
        if (block.isCelebration) return BlockForm.CELEBRATION
        if (block.executionQuality == ScheduleBlock.QUALITY_SPARK) return BlockForm.SPARK
        val deep = block.source != ScheduleBlock.SOURCE_HUMAN &&
            plan?.energyLevel == Plan.ENERGY_DEEP && !energyLow
        if (deep) return BlockForm.DEEP
        if (isRoam(roamDay, block)) return BlockForm.ROAM
        if (block.source == ScheduleBlock.SOURCE_HUMAN) return BlockForm.HUMAN
        return BlockForm.DEFAULT
    }

    /** 文案：火种切 min_viable_action（§4「只做 5 分钟」），回退链 label→title→(未命名)。 */
    fun labelText(block: ScheduleBlock, plan: Plan?): String {
        val fallback = block.label ?: plan?.title ?: "(未命名)"
        return if (block.executionQuality == ScheduleBlock.QUALITY_SPARK) {
            plan?.minViableAction ?: fallback
        } else {
            fallback
        }
    }

    fun clockOf(minute: Long): String = "%02d:%02d".format(minute / 60, minute % 60)

    /** 时间前缀：漫游起止以 ~ 模糊标记（仅渲染层）。 */
    fun timePrefix(block: ScheduleBlock, roam: Boolean): String =
        if (roam) "${clockOf(block.startMin)}~${clockOf(block.endMin)} " else "${clockOf(block.startMin)} "

    /** ARGB 降饱和（missed/skipped 叠加，D12 F2/F3：降 40% = 饱和度×0.6；灰度不动）。 */
    fun desaturate(argb: Long, factor: Float): Long {
        val a = (argb ushr 24) and 0xFFL
        val r = ((argb ushr 16) and 0xFFL) / 255f
        val g = ((argb ushr 8) and 0xFFL) / 255f
        val b = (argb and 0xFFL) / 255f
        val maxC = maxOf(r, g, b)
        val minC = minOf(r, g, b)
        val delta = maxC - minC
        if (delta <= 0f) return argb
        val s = delta / maxC
        val hRaw = when (maxC) {
            r -> (g - b) / delta
            g -> (b - r) / delta + 2f
            else -> (r - g) / delta + 4f
        } / 6f
        val h = if (hRaw < 0f) hRaw + 1f else hRaw
        val rgb = hsvToRgb(h, s * factor, maxC)
        return (a shl 24) or (rgb.first.toLong() shl 16) or (rgb.second.toLong() shl 8) or rgb.third.toLong()
    }

    private fun hsvToRgb(h: Float, s: Float, v: Float): Triple<Int, Int, Int> {
        val i = (h * 6f).toInt()
        val f = h * 6f - i
        val p = v * (1f - s)
        val q = v * (1f - f * s)
        val t = v * (1f - (1f - f) * s)
        fun c(x: Float): Int = (x * 255f + 0.5f).roundToInt().coerceIn(0, 255)
        return when (((i % 6) + 6) % 6) {
            0 -> Triple(c(v), c(t), c(p))
            1 -> Triple(c(q), c(v), c(p))
            2 -> Triple(c(p), c(v), c(t))
            3 -> Triple(c(p), c(q), c(v))
            4 -> Triple(c(t), c(p), c(v))
            else -> Triple(c(v), c(p), c(q))
        }
    }

    /** 角标错位（§5.3 行为清单）：提案存在时 🎂/📌 右移 40；庆祝存在时 📌 右移 16；🎫 在 📋 下方栈距 12。 */
    fun badgeLayout(block: ScheduleBlock): BlockBadgeLayout {
        val proposed = block.status == BlockStatus.PROPOSED
        val planBadge = block.planId != null && !block.pinned
        return BlockBadgeLayout(
            proposalChip = proposed,
            celebrationOffsetDp = if (proposed) StUi.badgeOffsetOverlapDp else 0f,
            pinOffsetDp = when {
                proposed -> StUi.badgeOffsetOverlapDp
                block.isCelebration -> StUi.badgeOffsetCelebrationDp
                else -> 0f
            },
            planBadge = planBadge,
            voucherTopDp = if (planBadge) StUi.voucherStackDp else 0f,
        )
    }

    /** 手势挂载闸：proposed/confirmed 才挂横滑（终态/archived 不挂）。 */
    fun canSwipe(block: ScheduleBlock): Boolean =
        block.status == BlockStatus.PROPOSED || block.status == BlockStatus.CONFIRMED

    /** human 块长按不可达 =「human 块不可坍缩」UI 闸（§5.4）。 */
    fun canDegrade(block: ScheduleBlock): Boolean =
        block.source == ScheduleBlock.SOURCE_AI && canSwipe(block)

    /** 手势锁轴（§3.4 逐字）：水平初位移 >20dp 且 |Δx/Δy|>2.0（dy<1 按 1 除防除零），否则交还垂直滚动。 */
    fun axisLocked(dxAbs: Float, dyAbs: Float): Boolean =
        dxAbs > StGesture.horizontalSlopDp && dxAbs / max(dyAbs, 1f) > StGesture.axisRatioMin

    /** 松手卡点：右滑过块宽 45% 融化 / 左滑过 35% 换乘 / 未过线弹回（px 口径，fraction 无量纲）。 */
    fun resolveSwipeAction(dxPx: Float, widthPx: Float): SwipeAction =
        if (widthPx <= 0f) {
            SwipeAction.NONE
        } else if (dxPx >= widthPx * StGesture.meltFraction) {
            SwipeAction.MELT
        } else if (-dxPx >= widthPx * StGesture.swapFraction) {
            SwipeAction.SWAP
        } else {
            SwipeAction.NONE
        }

    /** 意图提示层显隐：过卡点才出现（绝不显红，中性陈述去向）。 */
    fun meltHintVisible(dxPx: Float, widthPx: Float): Boolean =
        widthPx > 0f && dxPx >= widthPx * StGesture.meltFraction

    fun swapHintVisible(dxPx: Float, widthPx: Float): Boolean =
        widthPx > 0f && -dxPx >= widthPx * StGesture.swapFraction

    /** 触控热区钳制（§3.1 物理高度下限 44dp，火种 5min 物理高渲染钳制）。 */
    fun clampedHeightDp(naturalHeightDp: Float): Float = max(naturalHeightDp, StScale.blockMinHeightDp)
}
