pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("com.android.application") version "9.2.0" apply false
    id("org.jetbrains.kotlin.android") version "2.4.0" apply false
    id("org.jetbrains.kotlin.jvm") version "2.4.0" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.0" apply false
    // P4 Compose 地基（晨会批准 2026-11-15）：Compose compiler 插件随 Kotlin 2.4.0 自带。
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.0" apply false
    id("com.google.protobuf") version "0.9.4" apply false
}

include(":app")
// P1 MCP 内核平移：pure JVM core 模块（黄金向量对拍执行器承载，§10 决定 5 core 层）。
include(":core:mcp")
include(":core:model")
include(":core:action")
// P4 地基：设计令牌层（pure JVM 零依赖，ui-design §1–§3 逐字直译，P4①-a）。
include(":core:tokens")

