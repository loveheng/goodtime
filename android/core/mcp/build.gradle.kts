import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// :core:mcp —— MCP 内核（pure JVM，三层模块图 §10 决定 5 的 core 层延伸）：
// jsonrpc 协议常量 + 版本协商 + 传输无关内核 handleJsonRpc。
// 纪律：不依赖 Android SDK（纯 JVM 单测承载黄金向量对拍）；JSON 经
// kotlinx-serialization JsonElement（无 schema 树模型，对拍向量零样板）。

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
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    // 工具面原生切换件（P2）：内核保持零业务耦合——仅本工具面文件消费 action 层
    api(project(":core:action"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    // toolface 对拍执行器：仓储行为测试用 :core:model 的 JdbcSqlStore 测试驱动（testFixtures 单源）
    testImplementation(testFixtures(project(":core:model")))
}
