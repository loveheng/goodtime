package app.shiguang.shiguang.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import app.shiguang.core.tokens.StColorsPalette
import app.shiguang.core.tokens.StMotion
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.spring

/** 外观三档（ui-design §3.1：跟随系统/浅色/深色，默认跟随系统；theme_mode 为 uiOnly 设置键）。 */
enum class ShiguangThemeMode { FOLLOW_SYSTEM, LIGHT, DARK }

/**
 * 语义色板激活面板（ui-design §1.1 浅色基线 + §1.2 深色映射）。
 *
 * 设计稿既定结构差：Dart 侧 mutable static `applyBrightness` 不复制——Kotlin 侧
 * 双面板 immutable（:core:tokens），主题切换 = ShiguangTheme 换提供的实例。
 * 业务取色唯一范式 = `LocalStColors.current`，禁组件内直写 ARGB。
 */
val LocalStColors = staticCompositionLocalOf<StColorsPalette> {
    error("StColorsPalette 未由 ShiguangTheme 提供——业务取色必须经 LocalStColors.current")
}

/**
 * 主题包裹器（P4①-b，design_tokens/ui_foundation 清单转正版）：把 :core:tokens
 * 双面板绑进 CompositionLocal，锁定三档外观与排版基线。seed = deepFocus 底色
 * #283593（ui-design §3.1 拍板行，浅/深同 seed 换 brightness）。
 *
 * ⚠ 过渡态（留痕）：M3 `ColorScheme.fromSeed` 未进 material3 稳定版（1.4.0 实查
 * 无此 API）——现以 light/darkColorScheme + primary/onPrimary 直取语义 deepFocus 对
 * 承载品牌色，其余槽位 M3 中性基线；material3 稳定版提供 fromSeed 后切换（禁现推色值，
 * 过渡槽位仅引 :core:tokens）。终态口径 = ui-design §3.1 不变。
 */
@Composable
fun ShiguangTheme(
    themeMode: ShiguangThemeMode = ShiguangThemeMode.FOLLOW_SYSTEM,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ShiguangThemeMode.FOLLOW_SYSTEM -> isSystemInDarkTheme()
        ShiguangThemeMode.LIGHT -> false
        ShiguangThemeMode.DARK -> true
    }
    val palette = if (dark) StColorsPalette.DARK else StColorsPalette.LIGHT
    val scheme = if (dark) {
        darkColorScheme(
            primary = Color(palette.deepFocusBg),
            onPrimary = Color(palette.deepFocusText),
        )
    } else {
        lightColorScheme(
            primary = Color(palette.deepFocusBg),
            onPrimary = Color(palette.deepFocusText),
        )
    }
    CompositionLocalProvider(LocalStColors provides palette) {
        MaterialTheme(colorScheme = scheme, typography = ShiguangTypography, content = content)
    }
}

/** 排版基线（ui-design §2 + 丢失点 #7）：全库 includeFontPadding=false，M3 尺度档原值保留。 */
private fun TextStyle.withoutFontPadding(): TextStyle =
    copy(platformStyle = PlatformTextStyle(includeFontPadding = false))

private val M3Base = Typography()

val ShiguangTypography: Typography = Typography(
    displayLarge = M3Base.displayLarge.withoutFontPadding(),
    displayMedium = M3Base.displayMedium.withoutFontPadding(),
    displaySmall = M3Base.displaySmall.withoutFontPadding(),
    headlineLarge = M3Base.headlineLarge.withoutFontPadding(),
    headlineMedium = M3Base.headlineMedium.withoutFontPadding(),
    headlineSmall = M3Base.headlineSmall.withoutFontPadding(),
    titleLarge = M3Base.titleLarge.withoutFontPadding(),
    titleMedium = M3Base.titleMedium.withoutFontPadding(),
    titleSmall = M3Base.titleSmall.withoutFontPadding(),
    bodyLarge = M3Base.bodyLarge.withoutFontPadding(),
    bodyMedium = M3Base.bodyMedium.withoutFontPadding(),
    bodySmall = M3Base.bodySmall.withoutFontPadding(),
    labelLarge = M3Base.labelLarge.withoutFontPadding(),
    labelMedium = M3Base.labelMedium.withoutFontPadding(),
    labelSmall = M3Base.labelSmall.withoutFontPadding(),
)

/**
 * 动效承载（ui-design §3.3）：三组曲线控制点与弹簧物理参数逐字取 :core:tokens
 * StMotion——严禁现推参数或以「原生手感更佳」覆盖。手势期间 transform 不重排
 * 的约束由消费侧遵守（graphicsLayer，禁 animateDpAsState 布局属性动画）。
 */
object ShiguangMotion {
    /** ease-out = Cubic(0, 0, 0.58, 1)：长按坍缩等。 */
    val easeOut = CubicBezierEasing(StMotion.easeOutX1, StMotion.easeOutY1, StMotion.easeOutX2, StMotion.easeOutY2)

    /** ease-in-out = Cubic(0.42, 0, 0.58, 1)（不是 FastOutSlowIn）。 */
    val easeInOut = CubicBezierEasing(StMotion.easeInOutX1, StMotion.easeInOutY1, StMotion.easeInOutX2, StMotion.easeInOutY2)

    /** fast-out-slow-in = Cubic(0.4, 0, 0.2, 1)（Material 标准曲线）。 */
    val fastOutSlowIn = CubicBezierEasing(StMotion.fastOutSlowInX1, StMotion.fastOutSlowInY1, StMotion.fastOutSlowInX2, StMotion.fastOutSlowInY2)

    /** 安全线点亮 spring 单次（阻尼 0.75 / 刚度 400，名义感知 ≈250ms 为窗口径非时长参数）。 */
    fun safelineSpring(): AnimationSpec<Float> =
        spring(dampingRatio = StMotion.springDampingRatio, stiffness = StMotion.springStiffness)
}
