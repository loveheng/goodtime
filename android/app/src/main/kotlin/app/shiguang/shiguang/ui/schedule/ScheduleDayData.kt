package app.shiguang.shiguang.ui.schedule

import app.shiguang.core.action.BridgeRepositoryPort
import app.shiguang.core.action.ScheduleQueries
import app.shiguang.core.model.FixedSlot
import app.shiguang.core.model.Plan
import app.shiguang.core.model.ScheduleBlock
import app.shiguang.core.model.ScheduleDay
import app.shiguang.shiguang.ui.block.BlockStatus
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 今日页装配数据（P4③，behavior/schedule_page.md §B 已过目转正；Dart DayData 逐字镜像）：
 * 单查询分流（昨日+今日 blocksInRange）+ 作息窗换算 + 关联凭证 + 派生 getter 族
 * （spark/proposedAi/safelineLit/visibleBlocks/occupiedIntervals）。
 * 纯数据+纯逻辑，可 JVM 真库对拍。
 */
data class ScheduleDayData(
    val wake: Int,
    val sleepAdj: Int,
    val date: LocalDate,
    val isToday: Boolean,
    val blocks: List<ScheduleBlock>,
    val leftovers: List<ScheduleBlock>,
    val fixedOnDate: List<FixedSlot>,
    val fixedSpillover: List<FixedSlot>,
    val plans: Map<String, Plan?>,
    val settings: JsonObject,
    val cleanSlate: Boolean,
    val energy: String,
    val roam: Boolean,
    val artifactsByBlock: Map<String, JsonObject>,
) {
    /** 当日黄金火种块（首个 isDaySpark 且非 melted；§0.4）。 */
    val spark: ScheduleBlock?
        get() = blocks.firstOrNull { it.isDaySpark && it.status != BlockStatus.MELTED }

    /** AI 未确认提案（分级确认卡观众；§3.4）。 */
    val proposedAi: List<ScheduleBlock>
        get() = blocks.filter { it.source == ScheduleBlock.SOURCE_AI && it.status == BlockStatus.PROPOSED }

    /** 安全线：火种 done（含 spark 打折完成）即「今日底线守住」（§10 八轮）。 */
    val safelineLit: Boolean
        get() = blocks.any { it.isDaySpark && it.status == BlockStatus.DONE }

    /** 可见块（melted 不渲染 §6.2；archived 淡出仍占位）。 */
    val visibleBlocks: List<ScheduleBlock>
        get() = blocks.filter { it.status != BlockStatus.MELTED }

    /** 占用区间（全部可见块 + 固定占用投射），派生保护区/自由留白共用。 */
    fun occupiedIntervals(): List<Pair<Int, Int>> = buildList {
        for (b in visibleBlocks) {
            val s = b.startMin.toInt()
            add(Pair(s, s + ScheduleDay.spanMinutes(s, b.endMin.toInt())))
        }
        for (s in fixedOnDate) {
            add(Pair(s.startMin, if (ScheduleDay.crossesMidnight(s.startMin, s.endMin)) 1440 else s.endMin))
        }
        for (s in fixedSpillover) add(Pair(0, s.endMin))
    }
}

private fun JsonObject.strOf(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

private fun JsonObject.intOf(k: String): Int? = (this[k] as? JsonPrimitive)?.longOrNull?.toInt()

/**
 * 今日页装载（Dart _load 逐字镜像）：getSettings → 作息窗换算 → 昨日+今日单查询分流
 * （昨日 missed=遗留区处置面）→ plan/凭证关联 → cleanSlate/roam/energy 派生。
 * [selected] 由页面态传入（日期参数化：周/月跳日回看历史）。
 */
suspend fun loadScheduleDay(
    repo: BridgeRepositoryPort,
    selected: LocalDate,
    now: LocalDateTime,
): ScheduleDayData {
    val queries = ScheduleQueries(repo)
    val settings = queries.getSettings()
    val wake = settings.intOf("wake_time") ?: 420
    val sleep = settings.intOf("sleep_time") ?: 1380
    val sleepAdj = if (sleep <= wake) sleep + 1440 else sleep
    val today = ScheduleDay.scheduleDayOf(now, wake)
    val iso = ScheduleDay.isoDate(selected)
    // 昨日+今日单查询分流（昨日 missed = 遗留区处置面，§3.2）
    val twoDays = (repo.blocksInRange(
        ScheduleDay.isoDate(today.minusDays(1)), iso,
    ) as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
    val blocks = twoDays.filter { it.strOf("date") == iso }.map { ScheduleBlock.fromMap(it) }
    val leftovers = twoDays
        .filter { it.strOf("date") != iso && it.strOf("status") == BlockStatus.MISSED }
        .map { ScheduleBlock.fromMap(it) }
    val fixed = repo.fixedSlotsForDate(iso)
    val fixedOnDate = (fixed["on_date"] as? JsonArray).orEmpty()
        .mapNotNull { it as? JsonObject }.map { FixedSlot.fromMap(it) }
    val fixedSpillover = (fixed["spillover"] as? JsonArray).orEmpty()
        .mapNotNull { it as? JsonObject }.map { FixedSlot.fromMap(it) }
    val planIds = LinkedHashSet<String>()
    for (b in blocks + leftovers) b.planId?.let { planIds.add(it) }
    val plans = LinkedHashMap<String, Plan?>()
    for (id in planIds) plans[id] = repo.planById(id)?.let { Plan.fromMap(it) }
    // 关联凭证（§2.1 入口 2）：blockId → artifact 映射（最多一枚/块），🎫 微标与通关卡同源
    val artifactsByBlock = LinkedHashMap<String, JsonObject>()
    for (b in blocks) {
        b.id?.let { repo.artifactForBlock(it)?.let { a -> artifactsByBlock[it] = a } }
    }
    val cleanSlate = if ((settings["initialized"] as? JsonPrimitive)?.booleanOrNull == true) {
        queries.isBreakdown(now)
    } else {
        false
    }
    // 例外日（假期与出行）：ISO 日期字符串直接字典序比较
    val exceptions = (settings["exceptions"] as? JsonArray).orEmpty()
    val roam = exceptions.any { e ->
        val o = e as? JsonObject
        val start = o?.strOf("start")
        val end = o?.strOf("end")
        start != null && end != null && start <= iso && end >= iso
    }
    return ScheduleDayData(
        wake = wake,
        sleepAdj = sleepAdj,
        date = selected,
        isToday = iso == ScheduleDay.isoDate(today),
        blocks = blocks,
        leftovers = leftovers,
        fixedOnDate = fixedOnDate,
        fixedSpillover = fixedSpillover,
        plans = plans,
        settings = settings,
        cleanSlate = cleanSlate,
        energy = settings.strOf("today_energy") ?: "normal",
        roam = roam,
        artifactsByBlock = artifactsByBlock,
    )
}
