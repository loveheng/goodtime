import com.android.build.api.dsl.LibraryExtension

allprojects {
    repositories {
        google()
        mavenCentral()
    }
}

val newBuildDir: Directory =
    rootProject.layout.buildDirectory
        .dir("../../build")
        .get()
rootProject.layout.buildDirectory.value(newBuildDir)

subprojects {
    val newSubprojectBuildDir: Directory = newBuildDir.dir(project.name)
    project.layout.buildDirectory.value(newSubprojectBuildDir)
}
subprojects {
    project.evaluationDependsOn(":app")
}

// 陈旧插件（如 bonsoir_android 5.1.6 硬编码 compileSdk 33）低于其 androidx 依赖要求的
// minCompileSdk 时 AGP 编译期校验直接失败；compileSdk 可独立于 targetSdk/minSdk 上调
// （AGP 官方口径），统一钳到 ≥36（= flutter.compileSdkVersion 默认值）。
// 注意：根脚本执行期个别子项目可能已被评估（实测：对已评估项目注册 afterEvaluate
// 抛 "Cannot run Project.afterEvaluate(Action) when the project is already evaluated"），
// 故分两路：已评估者立即钳制，未评估者注册回调。
subprojects {
    val clampCompileSdk = {
        if (plugins.hasPlugin("com.android.library")) {
            val androidExt = extensions.findByName("android")
            if (androidExt is LibraryExtension && (androidExt.compileSdk ?: 0) < 36) {
                androidExt.compileSdk = 36
            }
        }
    }
    if (state.executed) {
        clampCompileSdk()
    } else {
        afterEvaluate { clampCompileSdk() }
    }
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
