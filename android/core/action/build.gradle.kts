import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// :core:action —— 命令层类型骨架（pure JVM，P2 节点 2）：领域拒绝码/ActionException/
// CommandResult/命令宿主端口。**语义平移门槛**：各命令校验规则的行为清单过目后
// 才进本模块（§9.1.6）；当前仅类型层（Dart commands.dart 头部三件等价）。

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
    api(project(":core:model"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    // 仓储行为测试用 :core:model 的 JdbcSqlStore 测试驱动（testFixtures 单源）
    testImplementation(testFixtures(project(":core:model")))
}
