plugins {
    id("com.android.application")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

android {
    namespace = "app.shiguang.shiguang"
    // permission_handler_android 要求 compileSdk ≥ 37；AGP 9.2.0 支持 37
    // （Flutter 3.47.5 支持 AGP 上限 9.2）。compileSdk 独立于 targetSdk/minSdk 上调。
    compileSdk = 37
    ndkVersion = flutter.ndkVersion

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
        // You can update the following values to match your application needs.
        // For more information, see: https://flutter.dev/to/review-gradle-config.
        // 产品决策：仅支持 Android 14+，勿回退 flutter.minSdkVersion
        minSdk = 34
        targetSdk = flutter.targetSdkVersion
        // Uses the version code from pubspec.yaml. When using split APKs, 1000 * ABI_VERSION
        // is added automatically by Flutter. (https://developer.android.com/studio/build/configure-apk-splits#configure-APK-versions)
        // You can force using the value of versionCode by specifying the `-P force-version-code-ignoring-abi=true`
        // flag during build.
        versionCode = flutter.versionCode
        versionName = flutter.versionName
    }

    buildTypes {
        release {
            // TODO: Add your own signing config for the release build.
            // Signing with the debug keys for now, so `flutter run --release` works.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

flutter {
    source = "../.."
}

dependencies {
    // 官方 SDK：goodhub 本地 Binder 插件核心。:plugin-core 以 api 依赖 :contract，
    // 契约类（IMcpChannel/IMcpCallback + 常量）随传递引入，无需单独声明 contract。
    implementation("com.zzh.goodhub.mcp:goodhub-mcp-plugin-core:0.1.1")
}
