package app.shiguang.shiguang.ui.schedule

import app.shiguang.core.action.BridgeRepositoryPort
import app.shiguang.core.action.ScheduleQueries
import app.shiguang.core.model.ScheduleBlock
import app.shiguang.core.model.ScheduleDay
import app.shiguang.shiguang.ui.block.BlockStatus
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.time.LocalDateTime

/** 终态：不再作为「进行中/待办」参与当前/下一个判定（§4 状态机七态）。 */
private val TERMINAL_STATUSES = setOf(
    BlockStatus.DONE, BlockStatus.SKIPPED, BlockStatus.MISSED,
    BlockStatus.ARCHIVED, BlockStatus.MELTED,
)

/** 卡片展示快照（已解析 label，便于纯渲染）。 */
data class NowNextSnapshot(
    val current: ScheduleBlock?,
    val next: ScheduleBlock?,
    val currentLabel: String?,
    val nextLabel: String?,
)

/** 块时间窗是否罩住 [nowMin]（含跨午夜：23:30–07:30 在 06:00 仍为进行中）。 */
fun containsMinute(b: ScheduleBlock, nowMin: Int): Boolean =
    if (ScheduleDay.crossesMidnight(b.startMin.toInt(), b.endMin.toInt())) {
        nowMin >= b.startMin || nowMin < b.endMin
    } else {
        nowMin >= b.startMin && nowMin < b.endMin
    }

/**
 * 纯函数：从候选块中挑「当前」与「下一个」（Dart pickNowAndNext 逐字镜像）。
 * 约定（功能契约，单测据此覆盖）：仅看未终态（pending）块；current=时间窗罩住
 * [nowMin] 的块取最晚开始者；next=不罩 now 且 startMin>nowMin 的最早开始者
 * （跨午夜溢出块若罩住 now 算 current，绝不误判成 next）；任一侧缺失返回 null。
 */
fun pickNowAndNext(
    blocks: List<ScheduleBlock>,
    nowMin: Int,
): NowNextSnapshot {
    val pending = blocks.filter { it.status !in TERMINAL_STATUSES }.sortedBy { it.startMin }
    var current: ScheduleBlock? = null
    for (b in pending) {
        if (containsMinute(b, nowMin)) current = b // 排序后后者即更晚开始
    }
    var next: ScheduleBlock? = null
    for (b in pending) {
        if (containsMinute(b, nowMin)) continue // 进行中不算「下一个」
        if (b.startMin > nowMin) {
            next = b // pending 已按 startMin 升序，首命中即最早
            break
        }
    }
    return NowNextSnapshot(current, next, null, null)
}

private fun JsonObject.intField(k: String): Int? =
    (this[k] as? JsonPrimitive)?.longOrNull?.toInt()

private fun JsonObject.strField(k: String): String? =
    (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

/**
 * NowNext 装载（Dart _compute 逐字镜像）：昨日+今日单查询——承载跨午夜溢出块
 * （昨夜起、今晨仍在进行），昨夜块仅当仍罩住 now 才入候选；label 回落 plan.title→(未命名)。
 */
suspend fun computeNowNext(repo: BridgeRepositoryPort, now: LocalDateTime): NowNextSnapshot {
    val settings = ScheduleQueries(repo).getSettings()
    val wake = settings.intField("wake_time") ?: 420
    val today = ScheduleDay.scheduleDayOf(now, wake)
    val todayIso = ScheduleDay.isoDate(today)
    val yIso = ScheduleDay.isoDate(today.minusDays(1))
    val nowMin = ScheduleDay.minutesOfDay(now)
    val range = (repo.blocksInRange(yIso, todayIso) as? JsonArray)
        ?.mapNotNull { it as? JsonObject } ?: emptyList()
    val candidates = range.filter { row ->
        val date = row.strField("date")
        date == todayIso ||
            (
                date == yIso &&
                    ScheduleDay.crossesMidnight(
                        row.intField("start_min") ?: 0,
                        row.intField("end_min") ?: 0,
                    ) && containsMinute(ScheduleBlock.fromMap(row), nowMin)
                )
    }.map { ScheduleBlock.fromMap(it) }
    val picked = pickNowAndNext(candidates, nowMin)
    suspend fun labelOf(b: ScheduleBlock?): String? {
        if (b == null) return null
        if (!b.label.isNullOrEmpty()) return b.label
        if (b.planId != null) {
            val title = repo.planById(b.planId!!)?.strField("title")
            if (!title.isNullOrEmpty()) return title
        }
        return "(未命名)"
    }
    return NowNextSnapshot(
        current = picked.current,
        next = picked.next,
        currentLabel = labelOf(picked.current),
        nextLabel = labelOf(picked.next),
    )
}

/**
 * 当前/下一个任务卡片（ui-pages §3 派生 glance，P4③ N4）：`进行中`（primary 调）/
 * `下一个`（中性调）两行；两侧全空 → 不渲染。装载粒度=分钟 tick + 翻日（repo 写路径
 * 广播面挂账，随命令接线轮补）。
 */
