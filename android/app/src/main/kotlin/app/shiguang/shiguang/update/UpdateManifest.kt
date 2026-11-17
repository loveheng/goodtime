package app.shiguang.shiguang.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * 远程下发的功能配置（随 shiguang-update.json 的 config 段；lib/update/update_manifest.dart
 * Kotlin 等价，behavior/update_page.md §A/§B 转正清单 D23 2026-11-15）。
 */
data class RemoteConfig(
    val announcement: String? = null,
    val announcementId: String? = null,
    /** 非空时覆盖 MCP initialize 返回的 instructions（配置热更入口）。 */
    val mcpInstructions: String? = null,
    val flags: Map<String, JsonElement> = emptyMap(),
) {
    companion object {
        val EMPTY = RemoteConfig()

        fun fromJson(o: JsonObject): RemoteConfig = RemoteConfig(
            announcement = o.str("announcement"),
            announcementId = o.str("announcementId"),
            mcpInstructions = o.str("mcpInstructions"),
            // flags 值面=Dart cast<String, Object?> 任意值镜像（JsonElement 原样保真）
            flags = (o["flags"] as? JsonObject)?.entries?.associate { it.toPair() } ?: emptyMap(),
        )

        private fun JsonObject.str(k: String): String? =
            (this[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
    }

    fun toJson(): JsonObject = buildJsonObject {
        announcement?.let { put("announcement", it) }
        announcementId?.let { put("announcementId", it) }
        mcpInstructions?.let { put("mcpInstructions", it) }
        if (flags.isNotEmpty()) put("flags", buildJsonObject { flags.forEach { (k, v) -> put(k, v) } })
    }
}

/**
 * 更新清单：应用内自更新 + 配置热更共用一份 shiguang-update.json（托管格式见
 * docs/guide/self-update.md）。解析强校验逐字（versionCode 必须整数 / versionName·
 * apkUrl 字符串必填——缺失或类型错抛 [UpdateException]）。
 */
data class UpdateManifest(
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val sha256: String? = null,
    val applicationId: String? = null,
    val changelog: String? = null,
    val config: RemoteConfig = RemoteConfig.EMPTY,
) {
    companion object {
        fun parse(raw: String): UpdateManifest {
            val o = try {
                Json.parseToJsonElement(raw) as? JsonObject
            } catch (e: Exception) {
                throw UpdateException("清单格式错误：${e.message ?: "JSON 解析失败"}")
            } ?: throw UpdateException("清单格式错误：顶层必须是对象")
            val code = (o["versionCode"] as? JsonPrimitive)?.longOrNullSafed()
                ?: throw UpdateException("清单格式错误：versionCode 必须是整数")
            val name = o.str("versionName")
                ?: throw UpdateException("清单格式错误：versionName / apkUrl 缺失或类型错误")
            val apk = o.str("apkUrl")
                ?: throw UpdateException("清单格式错误：versionName / apkUrl 缺失或类型错误")
            return UpdateManifest(
                versionCode = code,
                versionName = name,
                apkUrl = apk,
                sha256 = o.str("sha256"),
                applicationId = o.str("applicationId"),
                changelog = o.str("changelog"),
                config = (o["config"] as? JsonObject)?.let { RemoteConfig.fromJson(it) } ?: RemoteConfig.EMPTY,
            )
        }

        private fun JsonPrimitive.longOrNullSafed(): Long? = content.toLongOrNull()

        private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull
    }

    fun isNewerThan(currentVersionCode: Long): Boolean = versionCode > currentVersionCode
}

/** 更新链路领域异常（toString=message 逐字——Dart UpdateException 同形）。 */
class UpdateException(override val message: String) : Exception(message) {
    override fun toString(): String = message
}
