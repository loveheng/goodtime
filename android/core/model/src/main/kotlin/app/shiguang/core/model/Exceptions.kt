package app.shiguang.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** 例外日（§13 旅行模式）：日期范围 + 标签；期间工作类 fixed_slots 挂起（作息边界保留）。 */
data class ExceptionWindow(
    val start: String, // 'yyyy-MM-dd'（含）
    val end: String, // 'yyyy-MM-dd'（含）
    val label: String?,
) {
    /** 日历日闭区间覆盖判定；起止任一非法 → false（fail-closed，与 Dart 同口径）。 */
    fun covers(dateIso: String): Boolean {
        val s = ScheduleDay.tryParseIsoDate(start) ?: return false
        val e = ScheduleDay.tryParseIsoDate(end) ?: return false
        val d = ScheduleDay.tryParseIsoDate(dateIso) ?: return false
        return !d.isBefore(s) && !d.isAfter(e)
    }
}

/**
 * 解析 settings.exceptions（JSON 数组 [{start,end,label?}]）；坏条目静默跳过，
 * 整体解析失败回空表（Dart parseExceptions 同口径——start/end 非 JSON 字符串即弃）。
 */
fun parseExceptions(raw: Map<String, String>): List<ExceptionWindow> {
    val v = raw["exceptions"]
    if (v == null || v.isEmpty()) return emptyList()
    return try {
        val list = Json.parseToJsonElement(v) as? JsonArray ?: return emptyList()
        list.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            fun str(k: String): String? =
                ((o[k] as? JsonPrimitive)?.takeIf { it.isString })?.contentOrNull
            val start = str("start") ?: return@mapNotNull null
            val end = str("end") ?: return@mapNotNull null
            ExceptionWindow(start, end, str("label"))
        }
    } catch (_: Exception) {
        emptyList()
    }
}
