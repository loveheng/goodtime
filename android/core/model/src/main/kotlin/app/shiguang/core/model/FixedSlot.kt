package app.shiguang.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** FixedSlot 实体（lib/models/fixed_slot.dart 的 Kotlin 等价）。 */
data class FixedSlot(
    val id: String?,
    val name: String,
    val weekdays: List<Int>,
    val startMin: Int,
    val endMin: Int,
) {
    companion object {
        fun fromMap(map: JsonObject): FixedSlot = FixedSlot(
            id = map["id"]?.jsonPrimitive?.contentOrNull,
            name = map["name"]?.jsonPrimitive?.contentOrNull ?: "",
            weekdays = parseWeekdays(map["weekdays"]),
            startMin = map["start_min"]?.jsonPrimitive?.longOrNull?.toInt() ?: 0,
            endMin = map["end_min"]?.jsonPrimitive?.longOrNull?.toInt() ?: 0,
        )

        /** 双形态解析（镜像 Dart _parseWeekdays）：DB 行=CSV TEXT、命令/工具载荷=JSON 数组。 */
        private fun parseWeekdays(raw: kotlinx.serialization.json.JsonElement?): List<Int> = when (raw) {
            is JsonPrimitive -> raw.content.split(",")
                .filter { it.isNotBlank() }
                .mapNotNull { it.trim().toIntOrNull() }
            is JsonArray -> raw.map { it.jsonPrimitive.longOrNull?.toInt() ?: 0 }
            else -> emptyList()
        }

        fun encode(items: List<FixedSlot>): JsonArray =
            JsonArray(items.map { s ->
                buildJsonObject {
                    if (s.id != null) put("id", s.id)
                    put("name", s.name)
                    put("weekdays", JsonArray(s.weekdays.map { JsonPrimitive(it) }))
                    put("start_min", s.startMin)
                    put("end_min", s.endMin)
                }
            })

        fun decode(raw: String?): List<FixedSlot> {
            if (raw == null || raw.isEmpty()) return emptyList()
            return try {
                (Json.parseToJsonElement(raw) as JsonArray).map { e ->
                    fromMap(e.jsonObject)
                }
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    /** DB 行映射（lib/models/fixed_slot.dart toMap 逐字——weekdays CSV TEXT）。 */
    fun toMap(): JsonObject = buildJsonObject {
        if (id != null) put("id", id)
        put("name", name)
        put("weekdays", weekdays.joinToString(","))
        put("start_min", startMin)
        put("end_min", endMin)
    }

    /** MCP 快照 JSON（commands.dart slotToJson 逐字——weekdays 数组形）。 */
    fun toMcpJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("name", name)
        put("weekdays", JsonArray(weekdays.map { JsonPrimitive(it) }))
        put("start_min", startMin)
        put("end_min", endMin)
    }
}

/**
 * ScheduleRules 常量族（lib/action/rules.dart 的 Kotlin 等价）。
 * 禁飞区：数值逐字——Kotlin 严禁现推参数（§9.1 手势阈值与曲线）。
 */
object ScheduleRules {
    const val FILL_RATE_DEFAULT_PERCENT = 60
    const val FILL_RATE_LOW_ENERGY_PERCENT = 25
    const val FILL_RATE_EXCEPTIONS_PERCENT = 35
    const val BREATH_MIN = 45
    const val BREATH_BUFFER_MIN = 15
    const val SPARK_MAX_MINUTES = 15
    const val URGENT_DAYS = 3

    /** 时段分区边界（§6「早/中/晚」，按当日分钟）：早 <720、午 720..1079、晚 ≥1080。 */
    const val MORNING_END_MIN = 720
    const val AFTERNOON_END_MIN = 1080

    /** 耐受阈值激增判定线（§6）：次日 missed 率 ≥50% 视为「激增」。 */
    const val TOLERANCE_MISSED_RATE_SPIKE = 0.5

    /** 耐受阈值回退默认换算：单日新增排量上限 × 最小块粒度。 */
    const val TOLERANCE_FALLBACK_BLOCKS = 8
    const val TOLERANCE_FALLBACK_BLOCK_MIN = 30

    /** 顺延告警阈值（§8：≥3 次顺延提示需人工决策）。 */
    const val POSTPONE_ALERT = 3

    /** 全局背景注入预算（背景草案 §2）：scope=global ≤8 条、content 总字数 ≤400。 */
    const val GLOBAL_BACKGROUNDS_MAX = 8
    const val GLOBAL_BACKGROUNDS_CHARS_MAX = 400

    /** 长窗劝改阈值（背景草案 §2：劝改非拦截——日期窗超此值提示改长期）。 */
    const val BACKGROUND_DATE_WINDOW_ADVISE_DAYS = 14

    /** 管家日切回扫窗（fgs_housekeeper.md：设备数日未开机也能补齐）。 */
    const val DAILY_CUT_LOOKBACK_DAYS = 60

    /** Clean Slate 判定（ScheduleRules 既有口径）：断流天数阈值与 missed 总量阈值。 */
    const val BREAKDOWN_DAYS = 3
    const val BREAKDOWN_MISSED_TOTAL = 5
}

/**
 * 事实凭证常量（fact-user-relay-draft.md §1.2，2026-10-07 拍板）——同源单一出处。
 * 临场通关条时间常量（PASS_LEAD_MINUTES/PASS_DISMISS_AFTER_MINUTES）已补全（D25 轮 2026-11-15）。
 */
object ArtifactRules {
    /** badge 微标 12 字符命令层截断（截断优于拒绝）。 */
    const val BADGE_MAX_CHARS = 12

    /** hero_metrics 通关区 ≤3 组（超 3 项截前 3 落库）。 */
    const val HERO_METRICS_MAX = 3

    /** 临场通关条触发提前量（§9.4：T_trigger=T_target−120；2026-10-09 全角色统一）。 */
    const val PASS_LEAD_MINUTES = 120

    /** 临场通关条条目消隐窗（T_dismiss=T_action+20）。 */
    const val PASS_DISMISS_AFTER_MINUTES = 20

    /** payload 锚点数组键（fact_sheet _anchorList 口径；逐条 {date, end_date}）。 */
    private const val ANCHOR_KEY = "time_anchors"

    /** 锚点原始行列表（非对象行跳过——Dart `raw is! Map continue` 镜像）。 */
    private fun anchorsOf(a: Artifact): List<JsonObject> =
        (a.payload[ANCHOR_KEY] as? JsonArray)
            ?.filterIsInstance<JsonObject>()
            ?: emptyList()

    /**
     * 全部锚点最晚日历日（span 取 end_date??date，字典序最大；无锚→null——raw
     * 未解析恒 null。Dart factLatestAnchorDate 逐字镜像；plan_facts 过期派生源，
     * D24 拍板落点=本伴生对象）。
     */
    fun factLatestAnchorDate(a: Artifact): String? {
        var latest: String? = null
        for (raw in anchorsOf(a)) {
            val e = (raw["end_date"] as? JsonPrimitive)?.contentOrNull
            val d = (raw["date"] as? JsonPrimitive)?.contentOrNull
            val s = e ?: d ?: continue
            if (latest == null || s > latest) latest = s
        }
        return latest
    }

    /**
     * 过期=全部锚点最晚日历日早于今日（派生不入库；无锚=长期恒不过期——Dart
     * factExpired 口径；[todayIso] 注入便于 JVM 对拍，页侧传 isoDate(now)）。
     */
    fun factExpired(a: Artifact, todayIso: String): Boolean {
        val latest = factLatestAnchorDate(a) ?: return false
        return latest < todayIso
    }

    /**
     * 锚点日期角标（§0.4）：[10.20] 或 [10.17–10.19]（date+end_date 全集排序
     * 首末；无锚不显示。Dart factDateBadge 逐字镜像）。
     */
    fun factDateBadge(a: Artifact): String? {
        val days = sortedSetOf<String>()
        for (raw in anchorsOf(a)) {
            (raw["date"] as? JsonPrimitive)?.contentOrNull?.let { days.add(it) }
            (raw["end_date"] as? JsonPrimitive)?.contentOrNull?.let { days.add(it) }
        }
        if (days.isEmpty()) return null
        val first = days.first()
        val last = days.last()
        return if (first == last) "[${md(first)}]" else "[${md(first)}–${md(last)}]"
    }

    /** MM.dd 两位补零（解析失败原文回落——Dart _md 的 tryParseIsoDate null 分支）。 */
    private fun md(iso: String): String =
        Regex("^(\\d{4})-(\\d{2})-(\\d{2})$").find(iso)?.let { m ->
            "${m.groupValues[2]}.${m.groupValues[3]}"
        } ?: iso
}

/** SettingsKeys 常量子集（lib/data/settings.dart 的 Kotlin 等价——handler 消费面）。 */
object SettingsKeys {
    const val WAKE_TIME = "wake_time"
    const val SLEEP_TIME = "sleep_time"
    const val TODAY_ENERGY = "today_energy"
    const val FILL_RATE_LIMIT = "fill_rate_limit"
    const val MIN_BLOCK_MINUTES = "min_block_minutes"
    const val DAILY_NEW_BLOCKS_LIMIT = "daily_new_blocks_limit"
    const val USER_RULES = "user_rules"
    const val WEATHER_LOCATION = "weather_location"
    const val EXCEPTIONS = "exceptions"
    const val IDENTITY_PROMPT = "identity_prompt"
    const val THEME_MODE = "theme_mode"

    /** 合法键全列（Dart SettingsKeys.all 同序——未知键拒绝 hint 的逐字文案源）。 */
    val ALL = listOf(
        WAKE_TIME, SLEEP_TIME, MIN_BLOCK_MINUTES, DAILY_NEW_BLOCKS_LIMIT,
        TODAY_ENERGY, USER_RULES, WEATHER_LOCATION, EXCEPTIONS,
        FILL_RATE_LIMIT, THEME_MODE, IDENTITY_PROMPT,
    )

    /** suggest_user_setting 建言落键（app_settings 内部键，不入 ALL 白名单）。 */
    const val AI_SETTING_SUGGESTIONS = "ai_setting_suggestions"

    // 快记草稿三键（human-only 内部键，AI 不可达；app_shell_sheets.md §C 转正清单）。
    const val QUICK_NOTE_DRAFT_TEXT = "quick_note_draft_text"
    const val QUICK_NOTE_DRAFT_IMPORTANT = "quick_note_draft_important"
    const val QUICK_NOTE_DRAFT_DEADLINE = "quick_note_draft_deadline"

    fun initialized(settings: Map<String, String>): Boolean =
        settings.containsKey(WAKE_TIME) && settings.containsKey(SLEEP_TIME)

    fun intOf(settings: Map<String, String>, key: String): Int? =
        settings[key]?.toIntOrNull()

    /** 例外日覆盖判定：parseExceptions 后逐窗 covers（§13）。 */
    fun coversException(settings: Map<String, String>, date: String): Boolean =
        parseExceptions(settings).any { it.covers(date) }
}
