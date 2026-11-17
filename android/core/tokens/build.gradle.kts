import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
}

// :core:tokens —— 设计令牌层（P4①-a，pure JVM 零依赖）：ui-design §1–§3 的
// Kotlin 逐字直译（对照源 lib/theme/tokens.dart + behavior/design_tokens.md，
// 2026-10-05 拍板定稿；G2 后 Dart 侧只读，补缺一律落本模块 + ui-design 修订）。
// 纪律：零 Android/Compose 依赖——Color/Easing 实例由 :app 装配层构造
// （Long ARGB → Color、曲线控制点 → CubicBezierEasing）；值禁现推，
// StTokensValueTest 逐值对拍（撤改即红）。

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    testImplementation(kotlin("test"))
}
