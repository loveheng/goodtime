# ============ R8 keep 规则（§9「构建与混淆」四条分区，2026-11-15 配死）============
# 验收纪律（§9）：基线回归与 AIDL 跨端调用一律用 Release 构建跑（真机轮执行）。
# 纠偏注记：本项目 release 此前实际未开 R8（flutter 插件不设 minifyEnabled，AGP 默认关，
# 设计稿「R8 默认开启」前提失真）——本文件随 release 块显式开启首次生效，真机 release
# 冒烟验收通过前不发布（UNCERTAIN 见 release 块注记）。

# ① AIDL 跨进程面（goodhub 契约 §1.2.2 冻结：com.zzh.goodhub.mcp 包名与方法签名永不改名）
-keep class com.zzh.goodhub.mcp.** { *; }

# ② protobuf 消息（javalite 官方 consumer rules 随包；生成码=GeneratedMessageLite 子类
#    显式兜底防裁切——relay_protocol.proto 唯一生成源，:core:model）
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }

# ③ BouncyCastle（Argon2id 依赖 Provider 反射加载；规则缺失即 NoSuchAlgorithmException
#    静默失败——bcprov-jdk18on:1.81，:core:model）
-keep class org.bouncycastle.** { *; }

# ④ kotlinx.serialization（P4 路由 @Serializable 目的地前置于 P4；当前 :core 零实体）
-keepclassmembers @kotlinx.serialization.Serializable class * {
    *** Companion;
    *** serializer();
}
-keepclasseswithmembers class **$$serializer {
    *** INSTANCE;
}

# ⑤ errorprone 仅编译期注解（P5 Gradle-only：flutter 插件传递依赖退役后，
#    Tink 引用的 com.google.errorprone.annotations 不再在运行类路径——R8 缺类
#    报告生成，纯警告抑制，无行为影响；2026-11-17 实测 missing_rules.txt）
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.CheckReturnValue
-dontwarn com.google.errorprone.annotations.Immutable
-dontwarn com.google.errorprone.annotations.RestrictedApi
