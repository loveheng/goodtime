package app.shiguang.core.action

import app.shiguang.core.model.FixedSlot
import app.shiguang.core.model.ScheduleBlock
import app.shiguang.core.model.ScheduleDay
import app.shiguang.core.model.parseExceptions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * P2 propose_schedule 语义平移（最大单体，~230 行 Dart → ~300 行 Kotlin）。
 *
 * 校验管线（§6 逐项+全量，全部合法才落库——schedule_rejected 整单拒）：
 * ① settings 初始化检查 → ② 逐项静态校验（起止/作息边界）→ ③ 火种至多一个 →
 * ④ 内部两两重叠 → ⑤ plan 存在性 → ⑥ 撞墙（human/pinned/confirmed）→
 * ⑦ 撞 fixed_slots → ⑧ 撞前夜溢出 → ⑨ 硬红线 → ⑩ 呼吸律 →
 * ⑪ 低电量 deep 禁排 → ⑫ 填充率红线 → ⑬ 整单原子写。
 *
 * 双轨期：blocksOnDate/fixedSlotsForDate/addPlan 经 BridgeRepositoryPort 桥回 Dart；
 * transaction 简化为逐个 delete + insert（桥调用天然串行，原子性由 Dart FIFO 保证）。
 */
class ProposeHandler(private val repo: BridgeRepositoryPort) {

    private val json = Json { encodeDefaults = false }

    // ---- 常量（= ScheduleRules，禁飞区逐字） --------------------------------------

    private val fillRateDefaultPercent = 60
    private val fillRateLowEnergyPercent = 25
    private val fillRateExceptionsPercent = 35
    private val breathMin = 45
    private val breathBufferMin = 15
    private val sparkMaxMinutes = 15

    // ---- 校验辅助 -----------------------------------------------------------------

    private fun spanMinutes(start: Int, end: Int): Int =
        if (end < start) 1440 - start + end else end - start

    private fun overlaps(aStart: Int, aEnd: Int, bStart: Int, bEnd: Int): Boolean {
        val a0 = aStart
        val a1 = aStart + spanMinutes(aStart, aEnd)
        val b0 = bStart
        val b1 = bStart + spanMinutes(bStart, bEnd)
        return a0 < b1 && b0 < a1
    }

    private fun clockOf(m: Int): String {
        val v = m % 1440
        return "%02d:%02d".format(v / 60, v % 60)
    }

    private fun slotPortion(s: FixedSlot, startedHere: Boolean): Pair<Int, Int> =
        if (startedHere) {
            val end = if (s.endMin < s.startMin) 1440 else s.endMin
            Pair(s.startMin, end)
        } else {
            Pair(0, s.endMin)
        }

    private val rejections = mutableListOf<JsonObject>()

    private fun reject(index: Int, reason: String, hint: String? = null, data: JsonObject? = null) {
        val m = buildJsonObject {
            put("index", index)
            put("reason", reason)
            if (hint != null) put("hint", hint)
            if (data != null) put("data", data)
        }
        rejections.add(m)
    }

    private fun staticError(startMin: Int, endMin: Int, wake: Int, sleepAdj: Int): String? {
        if (startMin < 0 || startMin > 1439) return "start_min 越界（0..1439）"
        if (endMin < 1 || endMin > 1440) return "end_min 越界（1..1440）"
        if (startMin == endMin) return "零长块：start_min == end_min（一切块必须有起止）"
        if (startMin < wake) return "越作息边界：start 早于起床（${clockOf(wake)}）"
        val endAbs = if (endMin <= startMin) endMin + 1440 else endMin
        if (endAbs > sleepAdj) {
            return "越作息边界：end 晚于睡觉（${clockOf(sleepAdj % 1440)}）"
        }
        return null
    }

    private fun mergeIntervals(intervals: List<Pair<Int, Int>>): List<Pair<Int, Int>> {
        if (intervals.isEmpty()) return emptyList()
        val sorted = intervals.sortedBy { it.first }
        val out = mutableListOf(sorted.first())
        for ((s, e) in sorted.drop(1)) {
            val last = out.last()
            if (s <= last.second) {
                if (e > last.second) out[out.lastIndex] = Pair(last.first, e)
            } else {
                out.add(Pair(s, e))
            }
        }
        return out
    }

    private fun freeMinutes(windowStart: Int, windowEnd: Int, occupied: List<Pair<Int, Int>>): Int {
        var busy = 0
        for ((s, e) in mergeIntervals(occupied)) {
            val lo = maxOf(s, windowStart)
            val hi = minOf(e, windowEnd)
            if (hi > lo) busy += hi - lo
        }
        return windowEnd - windowStart - busy
    }

    private fun freeWindows(
        wake: Int, sleepAdj: Int,
        walls: List<Pair<Int, Int>>,
        fixedOnDate: List<Pair<Int, Int>>,
        spillover: List<Pair<Int, Int>>,
    ): JsonArray {
        val occupied = walls + fixedOnDate + spillover
        val merged = mergeIntervals(occupied)
        val gaps = mutableListOf<Pair<Int, Int>>()
        var cursor = wake
        for ((s, e) in merged) {
            if (s > cursor) gaps.add(Pair(cursor, minOf(s, sleepAdj)))
            if (e > cursor) cursor = e
        }
        if (cursor < sleepAdj) gaps.add(Pair(cursor, sleepAdj))
        return JsonArray(gaps.filter { it.second > it.first }.map { (s, e) ->
            buildJsonObject { put("start_min", s); put("end_min", e) }
        })
    }

    // ---- 主流程 -------------------------------------------------------------------

    suspend fun propose(cmd: ScheduleCommand.ProposeSchedule): CommandResult {
        val settings = repo.settingsAll().let { obj ->
            obj.keys.associateWith { k -> (obj[k] as? JsonPrimitive)?.contentOrNull ?: "" }
        }
        val wake = settings["wake_time"]?.toIntOrNull()
            ?: throw ActionException(
                "settings 未初始化：缺少作息边界（wake_time/sleep_time）",
                ActionErrorCode.SETTINGS_MISSING,
                hint = "先经 update_settings 写入作息边界再 propose——绝不静默按 24h 空闲排（§6 校验铁律）",
            )
        val sleep = settings["sleep_time"]?.toIntOrNull() ?: 1380
        val sleepAdj = if (sleep <= wake) sleep + 1440 else sleep

        if (!tryParseIso(cmd.date)) {
            throw ActionException(
                "date 非法：${cmd.date}（须 yyyy-MM-dd）",
                ActionErrorCode.INVALID_REQUEST,
            )
        }

        val existing = repo.blocksOnDate(cmd.date)
        val existingBlocks = (existing as? JsonArray)?.map { it.jsonObject } ?: emptyList()
        val suspended = parseExceptions(settings).any { it.covers(cmd.date) }
        val resolved = if (suspended) {
            Pair(emptyList<FixedSlot>(), emptyList<FixedSlot>())
        } else {
            val r = repo.fixedSlotsForDate(cmd.date)
            Pair(slotsOfJson(r["on_date"]), slotsOfJson(r["spillover"]))
        }
        val fixedOnDate = resolved.first
        val spillover = resolved.second

        // 和平条款墙（human/pinned 是固定占用；confirmed+ AI 块同等绕行）
        val walls = existingBlocks.filter { b ->
            val src = (b["source"] as? JsonPrimitive)?.contentOrNull ?: ""
            val pinned = (b["pinned"] as? JsonPrimitive)?.booleanOrNull ?: false
            src == "human" || pinned ||
                ((b["source"] as? JsonPrimitive)?.contentOrNull == "ai" &&
                    (b["status"] as? JsonPrimitive)?.contentOrNull != "proposed")
        }
        val replaceable = existingBlocks.filter { b ->
            (b["source"] as? JsonPrimitive)?.contentOrNull == "ai" &&
                (b["status"] as? JsonPrimitive)?.contentOrNull == "proposed" &&
                !((b["pinned"] as? JsonPrimitive)?.booleanOrNull ?: false)
        }

        rejections.clear()
        val items = cmd.items
        val rejectedIdx = mutableSetOf<Int>()

        // ① 火种至多一个
        val sparks = items.withIndex().filter { it.value.isDaySpark }.map { it.index }
        for (i in sparks.drop(1)) {
            reject(i, "当日黄金火种至多一个", hint = "is_day_spark 指定当天最重要的那件事")
        }
        // ② 内部两两重叠
        for (i in items.indices) {
            for (j in i + 1 until items.size) {
                if (overlaps(items[i].startMin.toInt(), items[i].endMin.toInt(),
                        items[j].startMin.toInt(), items[j].endMin.toInt())
                ) {
                    reject(j, "与提案内第 $i 项时间重叠", hint = "先消内部重叠再整单重试")
                }
            }
        }
        // ③ 逐项静态校验 + plan 存在性 + 撞墙 + 撞 fixed_slots
        val planCache = mutableMapOf<String, JsonObject?>()
        for ((i, it) in items.withIndex()) {
            val staticError = staticError(it.startMin.toInt(), it.endMin.toInt(), wake, sleepAdj)
            if (staticError != null) reject(i, staticError)
            if (it.planId != null) {
                if (it.planId !in planCache) planCache[it.planId] = repo.planById(it.planId)
                if (planCache[it.planId] == null) {
                    reject(i, "plan_id=${it.planId} 不存在",
                        hint = "先 list_plans 确认 id（默认只拉未归档计划）")
                }
            }
            if (rejections.any { it["index"]?.jsonPrimitive?.longOrNull?.toInt() == i }) continue
            for (b in walls) {
                val bs = (b["start_min"] as? JsonPrimitive)?.longOrNull?.toInt() ?: 0
                val be = (b["end_min"] as? JsonPrimitive)?.longOrNull?.toInt() ?: 0
                val bsrc = (b["source"] as? JsonPrimitive)?.contentOrNull ?: ""
                val bpinned = (b["pinned"] as? JsonPrimitive)?.booleanOrNull ?: false
                if (overlaps(it.startMin.toInt(), it.endMin.toInt(), bs, be)) {
                    reject(i, "与当天不可穿透占用重叠（$bsrc${if (bpinned) "/pinned" else ""} 块 ${clockOf(bs)}-${clockOf(be)}）",
                        hint = "和平条款：human/pinned 块必须绕行（§3）")
                    break
                }
            }
            if (rejections.any { it["index"]?.jsonPrimitive?.longOrNull?.toInt() == i }) continue
            for (s in fixedOnDate) {
                val p = slotPortion(s, startedHere = true)
                if (overlaps(it.startMin.toInt(), it.endMin.toInt(), p.first, p.second)) {
                    reject(i, "与固定占用「${s.name}」重叠", hint = "固定占用=约束不是愿望（§4）")
                    break
                }
            }
            if (rejections.any { it["index"]?.jsonPrimitive?.longOrNull?.toInt() == i }) continue
            for (s in spillover) {
                val p = slotPortion(s, startedHere = false)
                if (overlaps(it.startMin.toInt(), it.endMin.toInt(), p.first, p.second)) {
                    reject(i, "与前夜溢出的固定占用「${s.name}」重叠（${clockOf(p.first)}-${clockOf(p.second)}）",
                        hint = "跨午夜占用按开始日归属，溢出段同样不可穿透（§4）")
                    break
                }
            }
            if (rejections.any { it["index"]?.jsonPrimitive?.longOrNull?.toInt() == i }) continue
            // 硬红线（§9.3）：本 plan 及递归子树的 structured 凭证约束；刻度不对称——
            // human 通道（place_block）放行，仅 AI 提案命中才拒（rejected_by_facts 逐条原因）
            val pid = it.planId
            if (pid != null) {
                val subs = subtreeArtifacts(repo, pid)
                val v = factRuleViolations(
                    subs,
                    ScheduleDay.tryParseIsoDate(cmd.date)!!,
                    it.startMin.toInt(), it.endMin.toInt(),
                )
                if (v.isNotEmpty()) {
                    reject(i, "硬红线命中的事实约束：${v.first().reason}",
                        data = buildJsonObject {
                            put("rejected_by_facts", JsonArray(v.map { x ->
                                buildJsonObject {
                                    put("artifact_id", x.fact["id"] ?: kotlinx.serialization.json.JsonNull)
                                    put("title", x.fact["title"] ?: kotlinx.serialization.json.JsonNull)
                                    put("reason", x.reason)
                                }
                            }))
                        })
                }
            }
        }

        // ④ 呼吸律（>45min 块后须留 ≥15min 空白）
        val order = items.withIndex().filter { it.index !in rejectedIdx }
            .map { it.index }.sortedBy { items[it].startMin }
        for (k in 0 until order.size - 1) {
            val a = items[order[k]]
            val aSpan = spanMinutes(a.startMin.toInt(), a.endMin.toInt())
            val aEndAbs = a.startMin.toInt() + aSpan
            if (aSpan > breathMin && items[order[k + 1]].startMin < aEndAbs + breathBufferMin) {
                reject(order[k + 1],
                    "呼吸律：$aSpan 分钟块后须留 ≥$breathBufferMin 分钟空白",
                    hint = "削减任务总量、扩大留白（减震器原则）")
            }
        }

        // ⑤ 低电量 deep 禁排（火种豁免：is_day_spark ≤15min 且取 min_viable_action）
        val energy = settings["today_energy"] ?: "normal"
        if (energy == "low") {
            for ((i, it) in items.withIndex()) {
                if (i in rejectedIdx) continue
                val plan = it.planId?.let { pid -> planCache[pid] }
                val planEnergy = (plan?.get("energy_level") as? JsonPrimitive)?.contentOrNull
                if (planEnergy != "deep") continue
                val span = spanMinutes(it.startMin.toInt(), it.endMin.toInt())
                val exempt = it.isDaySpark && span <= sparkMaxMinutes &&
                    !(plan?.get("min_viable_action") as? JsonPrimitive)?.content.isNullOrEmpty()
                if (!exempt) {
                    reject(i, "低电量日禁排深度任务（today_energy=low）",
                        hint = "火种豁免刻度：is_day_spark 块 ≤${sparkMaxMinutes}min 且执行内容取 min_viable_action")
                }
            }
        }

        // ⑥ 填充率红线
        val rate = minOf(
            if (energy == "low") fillRateLowEnergyPercent
            else (settings["fill_rate_limit"]?.toIntOrNull() ?: fillRateDefaultPercent),
            if (suspended) fillRateExceptionsPercent else 100,
        )
        val occupied = walls.map { b ->
            val bs = (b["start_min"] as? JsonPrimitive)?.longOrNull?.toInt() ?: 0
            val be = (b["end_min"] as? JsonPrimitive)?.longOrNull?.toInt() ?: 0
            Pair(bs, bs + spanMinutes(bs, be))
        } + fixedOnDate.map { slotPortion(it, startedHere = true) } +
            spillover.map { slotPortion(it, startedHere = false) }
        val free = freeMinutes(wake, sleepAdj, occupied)
        val aiTotal = items.withIndex()
            .filter { it.index !in rejectedIdx }
            .sumOf { spanMinutes(it.value.startMin.toInt(), it.value.endMin.toInt()) }
        if (aiTotal > free * rate / 100) {
            reject(-1, "填充率红线：AI 块总时长 $aiTotal 分钟超过当日可用 $free 分钟的 $rate%",
                hint = "削减任务总量、扩大留白——被拒不是因为排得差，是因为排得满（§6 减震器原则）")
        }

        // ⑦ 整单拒
        if (rejections.isNotEmpty()) {
            throw ActionException(
                "propose_schedule 整单拒绝：${rejections.size} 项不合法（全部合法才落库）",
                ActionErrorCode.SCHEDULE_REJECTED,
                data = buildJsonObject {
                    put("date", cmd.date)
                    put("rejections", JsonArray(rejections))
                    put("available_free_windows",
                        freeWindows(wake, sleepAdj,
                            walls.map { b ->
                                val bs = (b["start_min"] as? JsonPrimitive)?.longOrNull?.toInt() ?: 0
                                val be = (b["end_min"] as? JsonPrimitive)?.longOrNull?.toInt() ?: 0
                                Pair(bs, bs + spanMinutes(bs, be))
                            },
                            fixedOnDate.map { slotPortion(it, startedHere = true) },
                            spillover.map { slotPortion(it, startedHere = false) },
                        ))
                },
                hint = "按逐条 reason 修正后整单重试；填空优先使用 available_free_windows",
            )
        }

        // ⑧ 整单原子写（单事务：删 replaceable + 插块——Dart _repo.transaction 逐字；
        // 块走模型构造，JSON overlay 会因 fromMap 0/1 口径误读布尔）
        val inserted = mutableListOf<JsonObject>()
        repo.transaction { txn ->
            for (b in replaceable) {
                val bid = (b["id"] as? JsonPrimitive)?.contentOrNull
                if (bid != null) txn.delete("schedule_blocks", where = "id = ?", args = listOf(bid))
            }
            val now = System.currentTimeMillis()
            for (it in items) {
                val b = ScheduleBlock(
                    id = repo.newId(),
                    date = cmd.date,
                    startMin = it.startMin,
                    endMin = it.endMin,
                    planId = it.planId,
                    label = it.label,
                    source = ScheduleBlock.SOURCE_AI,
                    pinned = false,
                    status = ScheduleBlock.STATUS_PROPOSED,
                    postponeCount = 0,
                    executionQuality = ScheduleBlock.QUALITY_FULL,
                    isDaySpark = it.isDaySpark,
                    isCelebration = it.isCelebration,
                    createdAt = now,
                    updatedAt = now,
                    version = 0,
                )
                txn.insert("schedule_blocks", b.toMap())
                inserted.add(b.toMcpJson())
            }
        }

        // ⑨ 护航摘要（§6 protection_manifesto）
        val freeAfter = free - aiTotal
        val waiting = (repo.listPlans() as? JsonArray)?.size ?: 0
        var suppressed = waiting - items.mapNotNull { it.planId }.toSet().size
        if (suppressed < 0) suppressed = 0
        val slack = if (free <= 0) 0.0 else if (freeAfter < 0) 0.0 else freeAfter.toDouble() / free
        val sparkCount = items.count { it.isDaySpark }
        val manifesto = buildJsonObject {
            put("shielded_free_minutes", if (freeAfter < 0) 0 else freeAfter)
            put("slack_ratio", slack)
            put("suppressed_tasks_count", suppressed)
            put("narrative", "本次拦截 $suppressed 件未排入今天；守住了 " +
                "${if (freeAfter < 0) 0 else freeAfter} 分钟自由流动" +
                "（留白比 ${kotlin.math.round(slack * 100).toInt()}%）；当日核心 $sparkCount 件。" +
                "排程不是为了占满时间，而是为了捞起一颗珍珠。")
        }

        return CommandResult(
            op = cmd.op,
            data = buildJsonObject {
                put("date", cmd.date)
                put("blocks", JsonArray(inserted))
                put("replaced", replaceable.size)
                put("protection_manifesto", manifesto)
            },
            note = "提案已写入 ${cmd.date}：${items.size} 块待确认（proposed）",
        )
    }

    private fun tryParseIso(s: String): Boolean {
        if (!Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(s)) return false
        return try {
            java.time.LocalDate.of(
                s.substring(0, 4).toInt(),
                s.substring(5, 7).toInt(),
                s.substring(8, 10).toInt(),
            ).toString() == s
        } catch (_: Exception) {
            false
        }
    }
}
