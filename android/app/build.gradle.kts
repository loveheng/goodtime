plugins {
    id("com.android.application")
    // Kotlin Android（P5 切包前由 flutter-gradle-plugin 隐式带入，摘除后显式声明）。
    id("org.jetbrains.kotlin.android")
    // Compose compiler 插件（随 Kotlin 2.4.0；AGP 9 内建 Kotlin）。
    id("org.jetbrains.kotlin.plugin.compose")
    // Type-Safe 路由序列化插件（ShiguangRoute @Serializable 生成器；core 三模块同款，
    //  :app 此前漏配——真机走查实证 NativeMainActivity 首帧即崩 2026-11-15，补配）。
    id("org.jetbrains.kotlin.plugin.serialization")
    // P5 切包：dev.flutter.flutter-gradle-plugin 随 Dart 侧物理删除一并退役。
}

android {
    namespace = "app.shiguang.shiguang"

    // P4 Compose 地基（晨会批准 2026-11-15）：Compose 开关 + 稳定性基建报告（todo 既定）。
    testOptions {
        // JVM 测试 android.* 桩返回默认值（android.util.Log 等平台面——DEGRADE 注记路径）
        unitTests.isReturnDefaultValues = true
    }
    buildFeatures {
        compose = true
        // 默认更新源编译期注入（D23 裁决：--dart-define=UPDATE_SOURCE_URL 的 gradle
        // 等价物——值源=环境变量或 -P 属性，缺省空串=「尚未配置更新源」）
        buildConfig = true
    }
    // permission_handler_android 要求 compileSdk ≥ 37；AGP 9.2.0 支持 37
    // （Flutter 3.47.5 支持 AGP 上限 9.2）。compileSdk 独立于 targetSdk/minSdk 上调。
    compileSdk = 37

    // 本地 Binder 插件契约（com.zzh.goodhub.mcp.IMcpChannel/IMcpCallback）由官方 SDK
    // goodhub-mcp-plugin-core（传递 goodhub-mcp-contract AAR）提供；AIDL 唯一物理副本
    // 归 SDK 仓 :contract，shiguang 不再自拷 AIDL，故无需 buildFeatures.aidl。

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        // TODO: Specify your own unique Application ID (https://developer.android.com/studio/build/application-id.html).
        applicationId = "app.shiguang.shiguang"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // You can update the following values to match your application needs.
        // For more information, see: https://flutter.dev/to/review-gradle-config.
        // 产品决策：仅支持 Android 14+
        minSdk = 34
        // P5 切包：flutter.targetSdkVersion/versionCode/versionName 固化
        //（targetSdk=Flutter 3.47 插件默认 36；version 取自退役 pubspec 1.3.0+44066）
        targetSdk = 36
        versionCode = 44066
        versionName = "1.3.0"
        val updateSourceUrl = (System.getenv("UPDATE_SOURCE_URL")
            ?: (project.findProperty("UPDATE_SOURCE_URL") as String?)).orEmpty()
        buildConfigField("String", "UPDATE_SOURCE_URL", "\"$updateSourceUrl\"")
        // relay 基址编译期覆盖（Dart dart-define RELAY_BASE_URL 原生对位）：env >
        // -P 属性 > 空（代码侧回落模拟器回环 http://10.0.2.2:8080）；Dart relay.env
        // 文件层属 flutter_dotenv 机制，原生不复制。
        val relayBaseUrl = (System.getenv("RELAY_BASE_URL")
            ?: (project.findProperty("RELAY_BASE_URL") as String?)).orEmpty()
        buildConfigField("String", "RELAY_BASE_URL", "\"$relayBaseUrl\"")
    }

    // 内部 dogfood 构建隔离（todo P5 拍板）：独立 applicationId（.dogfood 后缀）
    // + 独立 SQLite 文件（AppContainer 按 packageName 后缀切库名），严禁与正式环境库共写。
    flavorDimensions += "channel"
    productFlavors {
        // 命名注记：不可叫 "main"——与 AGP 内建 main 源集任务映射撞键
        //（实测 Multiple entries with same key: main=[]）
        create("prod") { dimension = "channel" }
        create("dogfood") {
            dimension = "channel"
            applicationIdSuffix = ".dogfood"
        }
    }

    buildTypes {
        release {
            // TODO: Add your own signing config for the release build.
            // Signing with the debug keys for now, so `flutter run --release` works.
            signingConfig = signingConfigs.getByName("debug")
            // P5 切流（D14 裁决落地）：原生 Compose 宿主转正主入口，Flutter 宿主退役。
            manifestPlaceholders["activityName"] = ".NativeMainActivity"
            // R8 配死（§9「构建与混淆」纠偏：flutter 插件不设 minifyEnabled，release
            // 此前实际未缩减——本轮显式开启；四条 keep 分区见 proguard-rules.pro）。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            // 真机 AI 调试桥（SDK app-debug-bridge 0.5.0，纯 Android 库，仅 debug 变体）：
            // 主 activity 换 DebugNativeMainActivity（覆写挂载钩子），release 仍 .NativeMainActivity。
            manifestPlaceholders["activityName"] = ".DebugNativeMainActivity"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

// 稳定性基建（todo P4）：Compose Compiler 报告——重组/skippability 可观测。
composeCompiler {
    reportsDestination.set(layout.buildDirectory.dir("compose-compiler-reports"))
}

dependencies {
    // 官方 SDK：goodhub 本地 Binder 插件核心。:plugin-core 以 api 依赖 :contract，
    // 契约类（IMcpChannel/IMcpCallback + 常量）随传递引入，无需单独声明 contract。
    implementation("com.zzh.goodhub.mcp:goodhub-mcp-plugin-core:0.2.0")

    // 真机 AI 调试桥（0.5.0 纯 Android 库）：坐标依赖（mavenLocal 产物，SDK 仓
    // publishToMavenLocal 分发）；仅 debug 变体（release 产物零编译）。
    debugImplementation("com.appdebugbridge:app-debug-bridge:0.5.2")

    // P1 MCP 内核（Kotlin 化协议面，D8 混合态）：:core:mcp pure JVM 模块。
    implementation(project(":core:mcp"))

    // P2 原生存储驱动（SqlStore 平台实现；Schema 契约在 :core:model）。
    implementation(project(":core:model"))

    // 设计令牌层（P4①-a）：LocalStColors 双面板与原语组件度量全取此源。
    implementation(project(":core:tokens"))

    // 协程域（AppContainer 根作用域/AndroidTimeProvider；版本与 :core 对齐）。
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    // 密钥安全区（P3 拍板 B3：Keystore 主密钥 + EncryptedSharedPreferences 承载 MSK）。
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // 天气投影 HTTP 面（OkWeatherProvider；:core:model 的 okhttp 为 implementation 不传递）。
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // 仪器测试（UNCERTAIN 消标轮：真机实证 AndroidSqlStore/AndroidSecretStore/TimeProvider）
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation(kotlin("test"))
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    // JVM 单测（OkWeatherProvider：mockwebserver 脚本化 fetch 替身）
    testImplementation(kotlin("test"))
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    // P4③ 今日页装配测试：JdbcSqlStore testFixtures 真库对拍（版本随 :core:model 既有）
    testImplementation(testFixtures(project(":core:model")))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")

    // P4③ N5 命令接线：轻 ViewModel 层（D15 裁决①——viewModel()/viewModelScope）。
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")

    // 图标全集（导航三 Tab outline/快记 EditNote/WarningAmber/Star/Event——core 集不足）。
    implementation("androidx.compose.material:material-icons-extended")
    testImplementation("org.xerial:sqlite-jdbc:3.54.1.0")

    // P4 Compose 地基（晨会批准 2026-11-15：BOM 统一版本，material3 携 ui/foundation）。
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    // P4③ 调试宿主（D14 裁决①）：ComponentActivity+setContent；版本随 BOM。
    implementation("androidx.activity:activity-compose")
    // Type-Safe 路由（@Serializable 目的地；serialization 插件随 Kotlin 2.4.0）。
    implementation("androidx.navigation:navigation-compose:2.10.2")
    // 稳定性基建（todo P4）：集合字段不可变化。
    implementation("org.jetbrains.kotlinx:kotlinx-collections-immutable:0.3.8")
    // 扫码页相机栈（behavior/scan.md §C，D26：Flutter mobile_scanner 同层能力——无替代面）
    val camerax = "1.5.1"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
    implementation("com.google.zxing:core:3.5.3")
    // Konsist 四条类型级护栏（todo P4 既定；测试域专用，主源码零依赖）。
    testImplementation("com.lemonappdev:konsist:0.17.0")
}
