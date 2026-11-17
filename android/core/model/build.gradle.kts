import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.protobuf")
    `java-test-fixtures`
}

// :core:model —— 领域模型层（pure JVM，三层模块图 §10 决定 5）：实体 data class +
// DB 行映射（toMap/fromMap）+ payload v 序契约 + 存储接口（SqlStore/SqlTxn）与
// Schema 契约（ShiguangSchema：DDL + 七段幂等迁移）。零 Android SDK 依赖；
// 黄金向量 model-json 对拍承载。测试侧 sqlite-jdbc 驱动真跑 schema 向量
// （2026-11-14 拍板：SQLiteOpenHelper 生产零新依赖 + jdbc 仅测试驱动）。

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// relay_protocol.proto（冻结契约副本，源 = shiguang-relay 仓 contract 快照）；
// javalite 生成（proto3 隐式 presence——与 Dart has-语义的差异见向量 rules.default_scalars_semantics 拍板②）
protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.25.5"
    }
    generateProtoTasks {
        all().forEach {
            it.builtins {
                getByName("java") {
                    option("lite")
                }
            }
        }
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0") // KeyStore Mutex / 协程面
    // relay 契约 proto runtime（schedule-app §11 拍板：protobuf-javalite，与 Dart protobuf
    // proto3 wire/presence 同构；字节级对拍 = relay_proto_vectors.json 8 case）
    api("com.google.protobuf:protobuf-javalite:3.25.5")
    // relay 传输层（D10 拍板 2026-11-14：HTTP+WebSocket 双件同源 OkHttp；纯 JVM 不破三层铁律）
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Argon2id（recovery_vault KEK 派生）——设计稿 §依赖映射「Argon2id 明确用 BouncyCastle，
    // JCA/Tink 均无」；R8 keep 四条之 BouncyCastle Provider 同源（§10 决定 8）
    implementation("org.bouncycastle:bcprov-jdk18on:1.81")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    // SQLite 真跑测试驱动（仅测试；DROP COLUMN 需 SQLite ≥3.35——3.54 内核远超）
    testImplementation("org.xerial:sqlite-jdbc:3.54.1.0")
    testFixturesImplementation("org.xerial:sqlite-jdbc:3.54.1.0")
    testFixturesImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0") // JdbcSqlStore 串行 Mutex
    testFixturesImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}
