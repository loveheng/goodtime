package app.shiguang.shiguang.ui.block

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import app.shiguang.core.model.ScheduleBlock
import app.shiguang.core.tokens.StGesture
import app.shiguang.core.tokens.StMotion
import app.shiguang.core.tokens.StScale
import app.shiguang.shiguang.ui.theme.ShiguangMotion
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/**
 * 手势竞技场（§3.4 + §5.4，behavior/block_renderer.md 已过目转正）：单 pointerInput
 * awaitEachGesture 显式抢占——长按窗口 400ms（按住期间 0.98 呼吸态=D12 F6，触发后 300ms
 * 坍缩动画再上抛 onDegrade）；锁轴 20dp·|Δx/Δy|>2.0（未锁不消费=交还垂直滚动）；跟手
 * graphicsLayer 平移不重排；卡点右滑 45% 融化（触觉+400ms 消散动画后上抛 onMelt）/
 * 左滑 35% 换乘（触觉+抽屉随浮层族④）；未过线瞬时弹回（镜像 Flutter 无回弹动画）；
 * 重入闸 busy。意图提示层过卡点才出现（matchParentSize=丢失点 #2 Positioned.fill 显式化）。
 * 屏幕边缘 24dp 系统手势保留区与页级 BackHandler 属主干页③（丢失点 #8）。
 *
 * UNCERTAIN(2026-11-15): melt 失败回弹通道——融化命令落库失败时页面须保证块仍在树内
 * （回调后 600ms 观察窗内成功卸载则协程随组合取消；失败则 alpha 自动回弹重显），③主干页
 * 接线时按 getSchedule 流实证消标。
 */
@Composable
fun GestureBlock(
    block: ScheduleBlock,
    modifier: Modifier = Modifier,
    onDegrade: () -> Unit = {},
    onMelt: () -> Unit = {},
    onSwap: () -> Unit = {},
    content: @Composable BoxScope.() -> Unit,
) {
    val canSwipe = BlockVisualSpec.canSwipe(block)
    val canDegrade = BlockVisualSpec.canDegrade(block)
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    var busy by remember { mutableStateOf(false) }
    var dxPx by remember { mutableFloatStateOf(0f) }
    var locked by remember { mutableStateOf(false) }
    var holding by remember { mutableStateOf(false) }
    var boxWidthPx by remember { mutableIntStateOf(0) }

    val breath by animateFloatAsState(
        targetValue = if (holding) StGesture.longPressScale else 1f,
        label = "blockBreath",
    )
    val meltAlpha = remember { Animatable(1f) }
    val collapseScale = remember { Animatable(1f) }

    fun resetDrag() {
        dxPx = 0f
        locked = false
        holding = false
    }

    if (!canSwipe && !canDegrade) {
        Box(modifier.onSizeChanged { boxWidthPx = it.width }, content = content)
        return
    }

    val shape = RoundedCornerShape(StScale.radiusBlock.dp)
    val meltHint = BlockVisualSpec.meltHintVisible(dxPx, boxWidthPx.toFloat())
    val swapHint = BlockVisualSpec.swapHintVisible(dxPx, boxWidthPx.toFloat())

    Box(
        modifier
            .onSizeChanged { boxWidthPx = it.width }
            .graphicsLayer {
                translationX = dxPx
                scaleX = breath * collapseScale.value
                scaleY = breath * collapseScale.value
                alpha = meltAlpha.value
            }
            .pointerInput(canSwipe, canDegrade) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    resetDrag()
                    if (!busy) holding = true
                    var released = false
                    // 阶段 1：长按窗口 400ms——未锁轴到时仍按住 = 长按触发（human 块闸在 canDegrade）
                    val upInWindow = withTimeoutOrNull(StGesture.longPressMs.toLong()) {
                        while (!locked) {
                            val ev = awaitPointerEvent()
                            val c = ev.changes.firstOrNull() ?: continue
                            if (!c.pressed) {
                                released = true
                                break
                            }
                            val ndx = c.position.x - down.position.x
                            val ndy = c.position.y - down.position.y
                            if (BlockVisualSpec.axisLocked(abs(ndx), abs(ndy))) {
                                locked = true
                                holding = false
                            }
                        }
                        released
                    }
                    if (upInWindow == null && !locked && canDegrade && !busy) {
                        // 长按触发：消费剩余事件（认领，禁透传滚动）→ 坍缩动画 → 上抛
                        while (true) {
                            val ev = awaitPointerEvent()
                            ev.changes.forEach { if (it.pressed) it.consume() }
                            if (ev.changes.none { it.pressed }) break
                        }
                        busy = true
                        scope.launch {
                            collapseScale.animateTo(0.9f, tween(StMotion.collapseMs, easing = ShiguangMotion.easeOut))
                            onDegrade()
                            collapseScale.snapTo(1f)
                            busy = false
                        }
                        return@awaitEachGesture
                    }
                    holding = false
                    if (locked && canSwipe && !busy) {
                        // 阶段 2：锁轴跟手直到抬起，松手按卡点分派
                        while (true) {
                            val ev = awaitPointerEvent()
                            val c = ev.changes.firstOrNull() ?: break
                            if (!c.pressed) {
                                when (BlockVisualSpec.resolveSwipeAction(dxPx, size.width.toFloat())) {
                                    SwipeAction.MELT -> {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        busy = true
                                        scope.launch {
                                            meltAlpha.animateTo(0f, tween(StMotion.meltMs, easing = ShiguangMotion.easeOut))
                                            onMelt()
                                            delay(600)
                                            meltAlpha.snapTo(1f)
                                            busy = false
                                        }
                                    }
                                    SwipeAction.SWAP -> {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        onSwap()
                                    }
                                    SwipeAction.NONE -> Unit
                                }
                                resetDrag()
                                break
                            }
                            dxPx = c.position.x - down.position.x
                            c.consume()
                        }
                    }
                }
            },
    ) {
        // 意图提示层：过卡点才出现（实现见 GestureHint）
        if (meltHint) MeltHintOverlay(Modifier.matchParentSize(), shape)
        if (swapHint) SwapHintOverlay(Modifier.matchParentSize(), shape)
        content()
    }
}
