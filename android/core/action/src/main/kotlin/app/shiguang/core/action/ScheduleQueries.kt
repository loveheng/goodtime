package app.shiguang.core.action

import app.shiguang.core.model.Artifact
import app.shiguang.core.model.Background
import app.shiguang.core.model.FixedSlot
import app.shiguang.core.model.ScheduleBlock
import app.shiguang.core.model.ScheduleDay
import app.shiguang.core.model.ScheduleRules
import app.shiguang.core.model.SettingsKeys
import app.shiguang.core.model.parseExceptions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import kotlinx.serialization.json.jsonArray

/**
 * P2 读面聚合 Kotlin 平移（getSettings + listPlans + getHistory + globalBackgroundsPayload + getSchedule）。
 *
 * 行为契约 = queries_registry.md 详细清单（2026-11-14 抽取展开）+ toolface 读取族向量
 * （tf_get_settings_seeded / tf_get_schedule_clamp / tf_get_history_window / tf_list_plans_one）。
 * 桥线序列化口径 = MCP JSON（commands.dart toJson 族，键全列含显式 null）。
 */
class ScheduleQueries(private val repo: BridgeRepositoryPort) {

    private val json = Json { encodeDefaults = false }

    private fun JsonObject.toStringMap(): Map<String, String> =
        keys.associateWith { k -> (this[k] as? JsonPrimitive)?.contentOrNull ?: "" }

    /** JSON 字符串取值（缺键/非串 → JsonNull——MCP 口径显式 null）。 */
    private fun jsonStr(v: JsonElement?): JsonElement =
        ((v as? JsonPrimitive)?.takeIf { it.isString }?.content)
            ?.let { JsonPrimitive(it) } ?: JsonNull

    /** 服务端供时刻（§6）：null → 系统钟；非法串 → 拒绝，绝不静默回退。 */
    private fun serverNow(now: String?): LocalDateTime =
        if (now == null) LocalDateTime.now()
        else try {
            LocalDateTime.parse(now)
        } catch (_: Exception) {
            throw ActionException(
                "now 非法：$now（须 ISO 本地日期时间 yyyy-MM-ddTHH:mm[:ss]）",
                ActionErrorCode.INVALID_REQUEST,
            )
        }

    private fun spanOf(b: JsonObject): Int {
        val s = (b["start_min"] as? JsonPrimitive)?.longOrNull?.toInt() ?: 0
        val e = (b["end_min"] as? JsonPrimitive)?.longOrNull?.toInt() ?: 0
        return ScheduleDay.spanMinutes(s, e)
    }

    // ---- getSettings（§6 设置读面）------------------------------------------------

    suspend fun getSettings(): JsonObject {
        val raw = repo.settingsAll().toStringMap()
        val exceptionsRaw = raw["exceptions"]
        return buildJsonObject {
            put("initialized", raw.containsKey("wake_time") && raw.containsKey("sleep_time"))
            put("wake_time", raw["wake_time"]?.toIntOrNull()?.let { JsonPrimitive(it) } ?: JsonNull)
            put("sleep_time", raw["sleep_time"]?.toIntOrNull()?.let { JsonPrimitive(it) } ?: JsonNull)
            put("min_block_minutes", raw["min_block_minutes"]?.toIntOrNull()?.let { JsonPrimitive(it) } ?: JsonNull)
            put("daily_new_blocks_limit", raw["daily_new_blocks_limit"]?.toIntOrNull()?.let { JsonPrimitive(it) } ?: JsonNull)
            put("fill_rate_limit", (raw["fill_rate_limit"]?.toIntOrNull() ?: 60).let { JsonPrimitive(it) })
            put("today_energy", raw["today_energy"] ?: "normal")
            put("user_rules", raw["user_rules"]?.let { JsonPrimitive(it) } ?: JsonNull)
            put("weather_location", raw["weather_location"]?.let { JsonPrimitive(it) } ?: JsonNull)
            put("identity_prompt", raw["identity_prompt"]?.let { JsonPrimitive(it) } ?: JsonNull)
            put("exceptions", exceptionsRaw?.let { Json.parseToJsonElement(it) } ?: JsonArray(emptyList()))
            put("theme_mode", raw["theme_mode"] ?: "system")
        }
    }

    // ---- listPlans ----------------------------------------------------------------

    suspend fun listPlans(): JsonElement = repo.listPlans()

    // ---- getHistory（days 钳制 1..90 缺省 14；恒为聚合体、永不返回明细行 §6）---------

    private data class DayStat(
        val iso: String, val doneCount: Int, val missedCount: Int, val doneMinutes: Long,
    )

    suspend fun getHistory(now: String? = null, days: Int = 14): JsonObject {
        val raw = repo.settingsAll().toStringMap()
        val wake = SettingsKeys.intOf(raw, SettingsKeys.WAKE_TIME) ?: 0
        val sleep = SettingsKeys.intOf(raw, SettingsKeys.SLEEP_TIME) ?: 1380
        val sleepAdj = if (sleep <= wake) sleep + 1440 else sleep
        val today = ScheduleDay.scheduleDayOf(serverNow(now), wake)
        val n = if (days < 1) 1 else if (days > 90) 90 else days

        val all = (repo.blocksInRange(
            ScheduleDay.isoDate(today.minusDays((n - 1).toLong())), ScheduleDay.isoDate(today),
        ) as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
        val planIds = LinkedHashSet<String>()
        for (b in all) (b["plan_id"] as? JsonPrimitive)?.contentOrNull?.let { planIds.add(it) }
        val plans = HashMap<String, JsonObject?>()
        for (id in planIds) plans[id] = repo.planById(id)

        fun urgent(p: JsonObject?): Boolean {
            val d = (p?.get("deadline") as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                ?: return false
            val dd = ScheduleDay.tryParseIsoDate(d) ?: return false
            return dd.isBefore(today.plusDays(ScheduleRules.URGENT_DAYS + 1L))
        }

        // 按日统计（旧→新，供耐受阈值的相邻日判定）
        val stats = mutableListOf<DayStat>()
        for (i in (n - 1).toLong() downTo 0) {
            val iso = ScheduleDay.isoDate(today.minusDays(i))
            val day = all.filter { (it["date"] as? JsonPrimitive)?.contentOrNull == iso }
            val done = day.filter { (it["status"] as? JsonPrimitive)?.contentOrNull == "done" }
            val missed = day.count { (it["status"] as? JsonPrimitive)?.contentOrNull == "missed" }
            stats.add(DayStat(iso, done.size, missed, done.sumOf { b -> spanOf(b).toLong() }))
        }

        // 深潜净值 / 膨胀系数 / 分象限 / 能量回血（全期聚合）
        var deepMinutes = 0L
        var realSpan = 0L
        var estimateSpan = 0L
        val quadrant = mapOf(
            "Q1" to intArrayOf(0, 0), "Q2" to intArrayOf(0, 0),
            "Q3" to intArrayOf(0, 0), "Q4" to intArrayOf(0, 0),
        )
        var celebrationDone = 0
        var celebrationDoneMinutes = 0L
        var celebrationArchived = 0
        for (b in all) {
            val span = spanOf(b)
            val p = (b["plan_id"] as? JsonPrimitive)?.contentOrNull?.let { plans[it] }
            val isCele = (b["is_celebration"] as? JsonPrimitive)?.booleanOrNull ?: false
            val status = (b["status"] as? JsonPrimitive)?.contentOrNull
            if (isCele) {
                if (status == "done") {
                    celebrationDone++; celebrationDoneMinutes += span
                } else if (status == "archived") celebrationArchived++
            }
            if (status != "done" && status != "missed") continue
            val isDone = status == "done"
            val importance = (p?.get("importance") as? JsonPrimitive)?.booleanOrNull ?: false
            if (isDone && importance) deepMinutes += span
            if (isDone && p != null) {
                val est = (p["estimate"] as? JsonPrimitive)?.longOrNull
                if ((est ?: 0L) >= 1) {
                    realSpan += span
                    estimateSpan += est!!
                }
            }
            if (p != null) {
                val key = if (importance) (if (urgent(p)) "Q1" else "Q2") else (if (urgent(p)) "Q3" else "Q4")
                quadrant[key]!![if (isDone) 0 else 1] += 1
            }
        }

        // 时段完成率（块按开始时刻落段；跨午夜块归开始段）
        fun segRate(lo: Int, hi: Int): Double? {
            var done = 0
            var missed = 0
            for (b in all) {
                val s = (b["start_min"] as? JsonPrimitive)?.longOrNull?.toInt() ?: continue
                if (s < lo || s >= hi) continue
                when ((b["status"] as? JsonPrimitive)?.contentOrNull) {
                    "done" -> done++
                    "missed" -> missed++
                }
            }
            return if (done + missed == 0) null else done.toDouble() / (done + missed)
        }

        // 耐受阈值：次日 missed 率激增日的当日执行峰值；检测不出走回退默认
        var detected: Int? = null
        for (i in 0 until stats.size - 1) {
            val denom = stats[i + 1].doneCount + stats[i + 1].missedCount
            if (denom == 0) continue
            if (stats[i + 1].missedCount.toDouble() / denom >= ScheduleRules.TOLERANCE_MISSED_RATE_SPIKE) {
                val d = stats[i].doneMinutes
                val det = detected
                if (d > 0 && (det == null || d > det)) detected = d.toInt()
            }
        }
        val fallback = (SettingsKeys.intOf(raw, SettingsKeys.DAILY_NEW_BLOCKS_LIMIT)
            ?: ScheduleRules.TOLERANCE_FALLBACK_BLOCKS) *
            (SettingsKeys.intOf(raw, SettingsKeys.MIN_BLOCK_MINUTES)
                ?: ScheduleRules.TOLERANCE_FALLBACK_BLOCK_MIN)

        // 输出 newest first（today 在前）
        val perDay = (stats.size - 1 downTo 0).map { i ->
            val s = stats[i]
            buildJsonObject {
                put("date", s.iso)
                put("done_count", s.doneCount)
                put("missed_count", s.missedCount)
                put("done_minutes", s.doneMinutes)
                put("day_spark_done", all.count { b ->
                    (b["date"] as? JsonPrimitive)?.contentOrNull == s.iso &&
                        ((b["is_day_spark"] as? JsonPrimitive)?.booleanOrNull ?: false) &&
                        (b["status"] as? JsonPrimitive)?.contentOrNull == "done"
                })
            }
        }
        fun rate(q: Map<String, IntArray>, k: String): Double? {
            val v = q[k]!!
            return if (v[0] + v[1] == 0) null else v[0].toDouble() / (v[0] + v[1])
        }

        return buildJsonObject {
            put("days", JsonArray(perDay))
            put("totals", buildJsonObject {
                put("done_count", stats.sumOf { it.doneCount.toLong() })
                put("done_minutes", stats.sumOf { it.doneMinutes })
                put("day_spark_done", perDay.sumOf {
                    ((it["day_spark_done"] as? JsonPrimitive)?.longOrNull ?: 0L)
                })
            })
            put("calibration", buildJsonObject {
                put("deep_net_minutes", deepMinutes)
                put(
                    "expansion_factor",
                    if (estimateSpan == 0L) JsonNull else JsonPrimitive(realSpan.toDouble() / estimateSpan),
                )
                put("segment_done_rates", buildJsonObject {
                    put("morning", segRate(wake, ScheduleRules.MORNING_END_MIN)?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("afternoon", segRate(ScheduleRules.MORNING_END_MIN, ScheduleRules.AFTERNOON_END_MIN)?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("evening", segRate(ScheduleRules.AFTERNOON_END_MIN, sleepAdj)?.let { JsonPrimitive(it) } ?: JsonNull)
                })
                put("quadrant_done_rates", buildJsonObject {
                    put("Q1", rate(quadrant, "Q1")?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("Q2", rate(quadrant, "Q2")?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("Q3", rate(quadrant, "Q3")?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("Q4", rate(quadrant, "Q4")?.let { JsonPrimitive(it) } ?: JsonNull)
                })
                put("tolerance", buildJsonObject {
                    put("minutes", detected ?: fallback)
                    put("source", if (detected == null) "default" else "detected")
                })
                put("energy_recovery", buildJsonObject {
                    put("celebration_done_count", celebrationDone)
                    put("celebration_minutes", celebrationDoneMinutes)
                    put(
                        "fulfillment_rate",
                        if (celebrationDone + celebrationArchived == 0) JsonNull
                        else JsonPrimitive(celebrationDone.toDouble() / (celebrationDone + celebrationArchived)),
                    )
                })
            })
        }
    }

    // ---- globalBackgroundsPayload（全局背景治理装配）--------------------------------

    suspend fun globalBackgroundsPayload(today: String): JsonObject {
        val all = repo.listBackgrounds()
        val items = (all as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
        val globals = items.filter {
            (it["scope"] as? JsonPrimitive)?.contentOrNull == "global"
        }
        val chars = globals.sumOf { b ->
            (b["content"] as? JsonPrimitive)?.contentOrNull?.length ?: 0
        }
        val entries = globals.map { b ->
            buildJsonObject {
                for ((k, v) in b) put(k, v)
                val applicable = (b["applicable_dates"] as? JsonArray)
                    ?.mapNotNull { e -> (e as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                val expired = applicable != null &&
                    (applicable.isEmpty() || (applicable.last() < today))
                put("expired", expired)
            }
        }
        return buildJsonObject {
            put("entries", JsonArray(entries))
            put("budget", buildJsonObject {
                put("entries", globals.size)
                put("chars", chars)
                put("limit_entries", ScheduleRules.GLOBAL_BACKGROUNDS_MAX)
                put("limit_chars", ScheduleRules.GLOBAL_BACKGROUNDS_CHARS_MAX)
            })
        }
    }

    // ---- getSchedule（§6 读面核心：今天+明天，days ≤3 钳制；按作息日取「今天」）--------

    suspend fun getSchedule(now: String? = null, days: Int = 2): JsonObject {
        val raw = repo.settingsAll().toStringMap()
        val wake = SettingsKeys.intOf(raw, SettingsKeys.WAKE_TIME) ?: 0
        val sleep = SettingsKeys.intOf(raw, SettingsKeys.SLEEP_TIME) ?: 1380
        val sleepAdj = if (sleep <= wake) sleep + 1440 else sleep
        val exceptions = parseExceptions(raw)
        val today = ScheduleDay.scheduleDayOf(serverNow(now), wake)
        val n = if (days < 1) 1 else if (days > 3) 3 else days

        // 凭证摘要搭载（fact 草案 §1.6：窗口对齐实际返回视窗，state 白名单 structured）
        val structuredFacts = (repo.artifactsByState(Artifact.STATE_STRUCTURED) as? JsonArray)
            ?.mapNotNull { it as? JsonObject } ?: emptyList()
        val factPlanTitles = HashMap<String, String>()
        for (f in structuredFacts) {
            val pid = (f["plan_id"] as? JsonPrimitive)?.contentOrNull ?: continue
            if (!factPlanTitles.containsKey(pid)) {
                factPlanTitles[pid] = repo.planById(pid)
                    ?.get("title")?.let { t -> (t as? JsonPrimitive)?.contentOrNull }
                    ?: "未知计划"
            }
        }
        // 分区背景装配底表：全量一次取行本地过滤（纯读，与逐 scope 查询结果等价）
        val allBackgrounds = (repo.listBackgrounds() as? JsonArray)
            ?.mapNotNull { it as? JsonObject } ?: emptyList()

        val out = mutableListOf<JsonObject>()
        for (i in 0 until n) {
            val date = today.plusDays(i.toLong())
            val iso = ScheduleDay.isoDate(date)
            val blocks = (repo.blocksOnDate(iso) as? JsonArray)
                ?.mapNotNull { it as? JsonObject } ?: emptyList()
            val exception = exceptions.filter { it.covers(iso) }.map { it.label ?: "假期与出行" }
            val suspended = exception.isNotEmpty()
            val resolvedOnDate: List<FixedSlot>
            val resolvedSpillover: List<FixedSlot>
            if (suspended) {
                resolvedOnDate = emptyList()
                resolvedSpillover = emptyList()
            } else {
                val r = repo.fixedSlotsForDate(iso)
                resolvedOnDate = slotsOfJson(r["on_date"])
                resolvedSpillover = slotsOfJson(r["spillover"])
            }
            val planIds = LinkedHashSet<String>()
            for (b in blocks) (b["plan_id"] as? JsonPrimitive)?.contentOrNull?.let { planIds.add(it) }
            val plans = HashMap<String, JsonObject?>()
            for (id in planIds) plans[id] = repo.planById(id)

            // 水位线：空闲 = 作息窗 − 固定占用（旅行挂起日为 0）− human/pinned 墙
            val occupied = mutableListOf<Pair<Int, Int>>()
            for (b in blocks) {
                val src = (b["source"] as? JsonPrimitive)?.contentOrNull ?: ""
                val pinned = (b["pinned"] as? JsonPrimitive)?.booleanOrNull ?: false
                if (pinned || src == ScheduleBlock.SOURCE_HUMAN) {
                    val s = (b["start_min"] as? JsonPrimitive)?.longOrNull?.toInt() ?: 0
                    val e = (b["end_min"] as? JsonPrimitive)?.longOrNull?.toInt() ?: 0
                    occupied.add(Pair(s, s + ScheduleDay.spanMinutes(s, e)))
                }
            }
            for (s in resolvedOnDate) {
                occupied.add(
                    Pair(s.startMin, if (ScheduleDay.crossesMidnight(s.startMin, s.endMin)) 1440 else s.endMin),
                )
            }
            for (s in resolvedSpillover) occupied.add(Pair(0, s.endMin))

            val backgrounds = dayBackgroundPayload(allBackgrounds, iso, planIds, plans)
            val facts = dayFactsPayload(structuredFacts, factPlanTitles, iso)
            out.add(buildJsonObject {
                put("date", iso)
                put(
                    "exception",
                    if (suspended) JsonPrimitive(exception.joinToString("、")) else JsonNull,
                )
                put("free_minutes", ScheduleDay.freeMinutesIn(wake, sleepAdj, occupied))
                put("blocks", JsonArray(blocks.map { b -> blockWithEffectiveLabel(b, plans) }))
                put("fixed_slots", buildJsonObject {
                    put("on_date", JsonArray(resolvedOnDate.map { it.toMcpJson() }))
                    put("spillover", JsonArray(resolvedSpillover.map { it.toMcpJson() }))
                    put("suspended", suspended)
                })
                put("backgrounds", backgrounds)
                put("facts", facts)
            })
        }
        return buildJsonObject {
            put("today", ScheduleDay.isoDate(today))
            put("days", JsonArray(out))
            put("upcoming_facts", upcomingFactsPayload(structuredFacts, factPlanTitles, today, n))
        }
    }

    /** label 回落（§4）：块 label 缺省回落 plan 标题——再缺省键显式 null。 */
    private fun blockWithEffectiveLabel(b: JsonObject, plans: Map<String, JsonObject?>): JsonObject =
        buildJsonObject {
            for ((k, v) in b) put(k, v)
            val eff = (b["label"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                ?: (b["plan_id"] as? JsonPrimitive)?.contentOrNull?.let { pid ->
                    plans[pid]?.get("title")?.let { t -> (t as? JsonPrimitive)?.contentOrNull }
                }
            put("effective_label", eff?.let { JsonPrimitive(it) } ?: JsonNull)
        }

    // ---- 分区背景装配（背景草案 §4 排程通道）----------------------------------------

    /** 当日 ∈ applicable_dates（无窗=恒注入；空窗 fail-closed 不注入；一律日历日比对）。 */
    private fun backgroundApplies(b: JsonObject, iso: String): Boolean {
        val raw = b["applicable_dates"] ?: return true
        if (raw is JsonNull) return true
        val applicable = (raw as? JsonArray)
            ?.mapNotNull { e -> (e as? JsonPrimitive)?.takeIf { it.isString }?.content }
            ?: return true
        return applicable.contains(iso)
    }

    private suspend fun dayBackgroundPayload(
        all: List<JsonObject>,
        iso: String,
        planIds: Set<String>,
        plans: Map<String, JsonObject?>,
    ): JsonObject {
        val globals = all
            .filter {
                (it["scope"] as? JsonPrimitive)?.contentOrNull == Background.SCOPE_GLOBAL &&
                    backgroundApplies(it, iso)
            }
            .map { b -> buildJsonObject {
                put("id", jsonStr(b["id"]))
                put("content", jsonStr(b["content"]))
                put("tags", b["tags"] ?: JsonNull)
            } }
        val planSections = LinkedHashMap<String, JsonObject>()
        for (id in planIds) {
            val plan = plans[id] ?: continue // 孤儿引用防御（FK 兜底，理论不可达）
            val items = mutableListOf<JsonObject>()
            var hasInherited = false
            // 祖先链：自身（depth 0）+ ≤3 级上溯（与粒度定律子树深度一致）
            var cur = plan
            var depth = 0
            while (true) {
                val curId = (cur["id"] as? JsonPrimitive)?.contentOrNull ?: break
                val curTitle = (cur["title"] as? JsonPrimitive)?.contentOrNull
                for (b in all) {
                    if ((b["scope"] as? JsonPrimitive)?.contentOrNull != Background.SCOPE_PLAN) continue
                    if ((b["plan_id"] as? JsonPrimitive)?.contentOrNull != curId) continue
                    if (!backgroundApplies(b, iso)) continue
                    val inherited = depth > 0
                    if (inherited) hasInherited = true
                    items.add(buildJsonObject {
                        put("id", jsonStr(b["id"]))
                        put("content", jsonStr(b["content"]))
                        put("tags", b["tags"] ?: JsonNull)
                        if (inherited) put("from_plan", curTitle?.let { JsonPrimitive(it) } ?: JsonNull)
                    })
                }
                if (depth >= 3) break
                val parentId = (cur["parent_id"] as? JsonPrimitive)?.contentOrNull ?: break
                val parent = repo.planById(parentId) ?: break
                cur = parent
                depth++
            }
            if (items.isEmpty()) continue // 无背景的计划不出段（省 token）
            planSections[id] = buildJsonObject {
                put("title", jsonStr(plan["title"]))
                put("scope_note", "仅约束该计划下的活动块")
                if (hasInherited) {
                    put("inherit_note", "祖先背景为泛化默认；当前计划背景为具体细化，局部差异以当前计划为准")
                }
                put("items", JsonArray(items))
            }
        }
        return buildJsonObject {
            put("global", JsonArray(globals))
            put("plans", JsonObject(planSections))
        }
    }

    // ---- 当日凭证摘要装配（fact 草案 §1.6）------------------------------------------

    /** 锚点覆盖判定：任意锚 date 命中当日，或 span 跨日覆盖 [date, end_date] 闭区间。 */
    private fun factCoversDate(f: JsonObject, iso: String): Boolean {
        val anchors = (f["payload"] as? JsonObject)?.get("time_anchors") as? JsonArray ?: return false
        for (e0 in anchors) {
            val e = e0 as? JsonObject ?: continue
            val date = (e["date"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
            if (date == iso) return true
            val end = (e["end_date"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (end != null && date < iso && iso <= end) return true
        }
        return false
    }

    /** 最早 deadline_min：当日 moment.min 与 span.start_min 取最小；纯日期锚 → null。 */
    private fun earliestDeadlineMin(f: JsonObject, iso: String): Int? {
        val anchors = (f["payload"] as? JsonObject)?.get("time_anchors") as? JsonArray ?: return null
        var best: Int? = null
        for (e0 in anchors) {
            val e = e0 as? JsonObject ?: continue
            if ((e["date"] as? JsonPrimitive)?.takeIf { it.isString }?.content != iso) continue
            for (key in listOf("min", "start_min")) {
                val v = (e[key] as? JsonPrimitive)?.longOrNull?.toInt()
                if (v != null && (best == null || v < best)) best = v
            }
        }
        return best
    }

    /** 摘要四件套=category/title/badge/最早 deadline_min（≈100 token/日；null 键不携带）。 */
    private fun dayFactsPayload(
        facts: List<JsonObject>,
        titles: Map<String, String>,
        iso: String,
    ): JsonObject {
        val planItems = LinkedHashMap<String, MutableList<JsonObject>>()
        val unattributed = mutableListOf<JsonObject>()
        for (f in facts) {
            if (!factCoversDate(f, iso)) continue
            val item = buildJsonObject {
                put("id", jsonStr(f["id"]))
                put("category", jsonStr(f["category"]))
                put("title", jsonStr(f["title"]))
                val badge = (f["badge"] as? JsonPrimitive)?.contentOrNull
                if (badge != null) put("badge", badge)
                val dl = earliestDeadlineMin(f, iso)
                if (dl != null) put("deadline_min", dl)
            }
            val pid = (f["plan_id"] as? JsonPrimitive)?.contentOrNull
            if (pid == null) {
                unattributed.add(item)
            } else {
                planItems.getOrPut(pid) { mutableListOf() }.add(item)
            }
        }
        val planSections = LinkedHashMap<String, JsonObject>()
        for ((pid, items) in planItems) {
            planSections[pid] = buildJsonObject {
                put("title", titles[pid]?.let { JsonPrimitive(it) } ?: JsonNull)
                put("items", JsonArray(items))
            }
        }
        return buildJsonObject {
            put("plans", JsonObject(planSections))
            put("unattributed", JsonArray(unattributed))
        }
    }

    // ---- 视窗外凭证摘要（fact 草案 §1.6 边界补全，2026-10-08 拍板）--------------------

    /**
     * 视窗（n 天）之后 ~14 天内的 structured 凭证轻量投影：按最早锚 date 升序，
     * 每条 {date, title, deadline_min?, plan_title?}——轻字段防上下文膨胀。
     */
    private fun upcomingFactsPayload(
        facts: List<JsonObject>,
        titles: Map<String, String>,
        today: LocalDate,
        windowDays: Int,
    ): JsonArray {
        val windowEnd = today.plusDays((windowDays - 1).toLong())
        val horizon = today.plusDays((windowDays + 13).toLong())
        val rows = mutableListOf<Pair<LocalDate, JsonObject>>()
        for (f in facts) {
            val anchors = (f["payload"] as? JsonObject)?.get("time_anchors") as? JsonArray ?: continue
            var earliest: LocalDate? = null
            var deadlineMin: Int? = null
            for (e0 in anchors) {
                val e = e0 as? JsonObject ?: continue
                val dateStr = (e["date"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
                if (dateStr.length < 10) continue
                val d = ScheduleDay.tryParseIsoDate(dateStr.substring(0, 10)) ?: continue
                if (earliest == null || d.isBefore(earliest)) earliest = d
                // 若该锚恰在本实现关注的视窗外段，取其时刻/段首作 deadline
                if (d.isAfter(windowEnd) && !d.isAfter(horizon)) {
                    for (key in listOf("min", "start_min")) {
                        val v = (e[key] as? JsonPrimitive)?.longOrNull?.toInt()
                        if (v != null && (deadlineMin == null || v < deadlineMin)) deadlineMin = v
                    }
                }
            }
            val first = earliest ?: continue
            if (!first.isAfter(windowEnd)) continue // 窗内锚走 facts 正常搭载
            if (first.isAfter(horizon)) continue // 过远冷数据不拉
            val dl = deadlineMin
            val pid = (f["plan_id"] as? JsonPrimitive)?.contentOrNull
            rows.add(Pair(first, buildJsonObject {
                put("date", ScheduleDay.isoDate(first))
                put("title", jsonStr(f["title"]))
                if (dl != null) put("deadline_min", dl)
                if (pid != null) put("plan_title", titles[pid]?.let { JsonPrimitive(it) } ?: JsonNull)
            }))
        }
        rows.sortBy { it.first }
        return JsonArray(rows.map { it.second })
    }

    // ---- isBreakdown（第 6 件：Clean Slate 判定，fgs_housekeeper.md 过目 2026-11-15）--

    /**
     * Clean Slate 判定（lib/action/queries.dart isBreakdown Kotlin 等价）：
     * 30 天窗内 missed 总量 ≥[ScheduleRules.BREAKDOWN_MISSED_TOTAL] → true；最近活跃
     * （done|confirmed）距 today ≥[ScheduleRules.BREAKDOWN_DAYS] 天 → true；无活跃 → false。
     * ⚠ wake 缺省 ??0（与 Housekeeper.dailyCut 的 ??420 缺省差逐字保留）。
     */
    suspend fun isBreakdown(now: LocalDateTime? = null): Boolean {
        val raw = repo.settingsAll().toStringMap()
        val wake = SettingsKeys.intOf(raw, SettingsKeys.WAKE_TIME) ?: 0
        val today = ScheduleDay.scheduleDayOf(now ?: LocalDateTime.now(), wake)
        val history = repo.blocksInRange(
            ScheduleDay.isoDate(today.minusDays(30)),
            ScheduleDay.isoDate(today),
        ).jsonArray.mapNotNull { it as? JsonObject }
        val missedTotal =
            history.count { (it["status"] as? JsonPrimitive)?.contentOrNull == "missed" }
        if (missedTotal >= ScheduleRules.BREAKDOWN_MISSED_TOTAL) return true
        var lastActive: String? = null
        for (b in history) {
            val status = (b["status"] as? JsonPrimitive)?.contentOrNull
            if (status == "done" || status == "confirmed") {
                val date = (b["date"] as? JsonPrimitive)?.contentOrNull
                if (date != null && (lastActive == null || date > lastActive)) lastActive = date
            }
        }
        if (lastActive == null) return false
        val last = ScheduleDay.tryParseIsoDate(lastActive) ?: return false
        return ChronoUnit.DAYS.between(last, today) >= ScheduleRules.BREAKDOWN_DAYS
    }

    // ---- 周视图 / 简易月历投影（§4 三视图同源纪律：同一 blocksInRange 单查询派生）----

    /** 例外日标签（Dart `w.label ?? '假期与出行'` 逐字；多窗「、」连接）。 */
    private fun exceptionLabelOf(windows: List<app.shiguang.core.model.ExceptionWindow>, dateIso: String): String? {
        val labels = windows.filter { it.covers(dateIso) }.map { it.label ?: "假期与出行" }
        return if (labels.isEmpty()) null else labels.joinToString("、")
    }

    private fun blockDate(b: JsonObject): String? =
        (b["date"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun blockStatus(b: JsonObject): String? =
        (b["status"] as? JsonPrimitive)?.contentOrNull

    /** 简易月历数据（Dart queries.dart monthOverview 逐字镜像；单月只读摘要）。 */
    suspend fun monthOverview(year: Int, month: Int, now: LocalDateTime? = null): JsonObject {
        val first = LocalDate.of(year, month, 1)
        val last = first.plusMonths(1).minusDays(1) // 月末
        val blocks = (repo.blocksInRange(
            ScheduleDay.isoDate(first), ScheduleDay.isoDate(last),
        ) as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
        val raw = repo.settingsAll().toStringMap()
        val exceptions = parseExceptions(raw)
        val wake = SettingsKeys.intOf(raw, SettingsKeys.WAKE_TIME) ?: 0
        val todayIso = ScheduleDay.isoDate(ScheduleDay.scheduleDayOf(now ?: LocalDateTime.now(), wake))
        val byDate = LinkedHashMap<String, MutableList<JsonObject>>()
        for (b in blocks) {
            if (blockStatus(b) == "melted") continue // 无痕蒸发不进月历
            val iso = blockDate(b) ?: continue
            byDate.getOrPut(iso) { mutableListOf() }.add(b)
        }
        val days = mutableListOf<JsonObject>()
        var d = first
        while (!d.isAfter(last)) {
            val iso = ScheduleDay.isoDate(d)
            val list = byDate[iso] ?: emptyList()
            days.add(buildJsonObject {
                put("date", iso)
                put("total", list.size)
                put("done", list.count { blockStatus(it) == "done" })
                put("missed", list.count { blockStatus(it) == "missed" })
                put("proposed", list.count { blockStatus(it) == "proposed" })
                put("human", list.count { (it["source"] as? JsonPrimitive)?.contentOrNull == "human" })
                put(
                    "spark_done",
                    list.any { b ->
                        (b["is_day_spark"] as? JsonPrimitive)?.booleanOrNull == true &&
                            blockStatus(b) == "done"
                    },
                )
                put("celebration", list.any { (it["is_celebration"] as? JsonPrimitive)?.booleanOrNull == true })
                put("exception", exceptionLabelOf(exceptions, iso)?.let { JsonPrimitive(it) } ?: JsonNull)
            })
            d = d.plusDays(1)
        }
        return buildJsonObject {
            put("year", year)
            put("month", month)
            put("today", todayIso)
            put("days", JsonArray(days))
        }
    }

    /** 周视图数据（Dart queries.dart weekOverview 逐字镜像：周一..周日全量块+聚焦窗+固定占用投射）。 */
    suspend fun weekOverview(anchor: LocalDate, now: LocalDateTime? = null): JsonObject {
        val monday = anchor.minusDays((anchor.dayOfWeek.value - 1).toLong())
        val blocks = (repo.blocksInRange(
            ScheduleDay.isoDate(monday), ScheduleDay.isoDate(monday.plusDays(6)),
        ) as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
        val raw = repo.settingsAll().toStringMap()
        val wake = SettingsKeys.intOf(raw, SettingsKeys.WAKE_TIME) ?: 0
        val sleep = SettingsKeys.intOf(raw, SettingsKeys.SLEEP_TIME) ?: 1380
        val sleepAdj = if (sleep <= wake) sleep + 1440 else sleep
        val exceptions = parseExceptions(raw)
        val weekdayLabels = listOf("一", "二", "三", "四", "五", "六", "日")
        val days = mutableListOf<JsonObject>()
        for (i in 0..6) {
            val d = monday.plusDays(i.toLong())
            val iso = ScheduleDay.isoDate(d)
            val dayBlocks = blocks.filter { blockDate(it) == iso }
            val planIds = LinkedHashSet<String>()
            for (b in dayBlocks) {
                (b["plan_id"] as? JsonPrimitive)?.contentOrNull?.let { planIds.add(it) }
            }
            val plans = HashMap<String, JsonObject?>()
            for (id in planIds) plans[id] = repo.planById(id)
            val fixed = repo.fixedSlotsForDate(iso)
            days.add(buildJsonObject {
                put("date", iso)
                put("weekday", weekdayLabels[i])
                put("exception", exceptionLabelOf(exceptions, iso)?.let { JsonPrimitive(it) } ?: JsonNull)
                put("blocks", JsonArray(dayBlocks.map { blockWithEffectiveLabel(it, plans) }))
                put("fixed_on_date", fixed["on_date"] ?: JsonArray(emptyList()))
                put("fixed_spillover", fixed["spillover"] ?: JsonArray(emptyList()))
            })
        }
        return buildJsonObject {
            put("week_start", ScheduleDay.isoDate(monday))
            put("wake", wake)
            put("sleep_adj", sleepAdj)
            put("days", JsonArray(days))
        }
    }
}
