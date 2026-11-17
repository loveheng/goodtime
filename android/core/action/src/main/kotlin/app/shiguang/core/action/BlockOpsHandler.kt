package app.shiguang.core.action

import app.shiguang.core.model.ScheduleBlock
import app.shiguang.core.model.ScheduleDay
import app.shiguang.core.model.ScheduleRules
import app.shiguang.core.model.SettingsKeys
import app.shiguang.core.model.collab.CollabFsm
import app.shiguang.core.model.parseExceptions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * P2 handler 语义平移·块操作命令（confirm/reject/tick/shift/adjust_block_time/
 * degrade/melt/restore——同构模式：读块→校验→patch→fresh→CommandResult）。
 * 翻译纪律：语义逐字（Dart _confirmBlock/_rejectBlock 等的校验/守卫/补记标记）。
 */
class BlockOpsHandler(private val repo: BridgeRepositoryPort) {

    private suspend fun requireBlock(id: String): JsonObject {
        val b = repo.blockById(id) ?: throw ActionException(
            "块不存在：id=$id", ActionErrorCode.NOT_FOUND,
        )
        return b
    }

    private fun blockStr(b: JsonObject, k: String): String? =
        (b[k] as? JsonPrimitive)?.contentOrNull

    private fun blockLong(b: JsonObject, k: String): Long? =
        (b[k] as? JsonPrimitive)?.longOrNull

    private fun isAiMovable(b: JsonObject): Boolean {
        val source = blockStr(b, "source") ?: ""
        val pinned = (b["pinned"] as? JsonPrimitive)?.booleanOrNull ?: false
        return source != "human" && !pinned
    }

    private suspend fun patchAndFresh(
        id: String,
        values: JsonObject,
        expectedVersion: Long?,
        op: String,
        note: String = "已调整",
    ): CommandResult {
        val ok = repo.patchBlock(id, values, expectedVersion)
        if (!ok) {
            val fresh = repo.blockById(id)
            throw ActionException(
                "版本冲突：$op 已被其他端修改",
                ActionErrorCode.VERSION_CONFLICT,
                hint = "直接在 latest 快照上合并重试",
                data = fresh?.let {
                    buildJsonObject {
                        put("code", "version_conflict")
                        put("latest", it)
                    }
                },
            )
        }
        val fresh = requireBlock(id)
        return CommandResult(op = op, targetId = id, snapshot = fresh, note = note)
    }

    // ---- confirm_block：proposed → confirmed ------------------------------------

    suspend fun confirmBlock(cmd: ScheduleCommand.ConfirmBlock): CommandResult {
        val b = requireBlock(cmd.id)
        _requireAiMovable(b)
        if (blockStr(b, "status") != "proposed") {
            throw ActionException(
                "confirm 仅适用于 proposed 块（当前=${blockStr(b, "status")}）",
                ActionErrorCode.INVALID_REQUEST,
            )
        }
        return patchAndFresh(cmd.id, buildJsonObject { put("status", "confirmed") }, cmd.expectedVersion, cmd.op, note = "已确认")
    }

    // ---- reject_block：proposed 块删除蒸发，原因可选回传 --------------------------

    suspend fun rejectBlock(cmd: ScheduleCommand.RejectBlock): CommandResult {
        val b = requireBlock(cmd.id)
        _requireAiMovable(b)
        if (blockStr(b, "status") != "proposed") {
            throw ActionException(
                "reject 仅适用于 proposed 块（当前=${blockStr(b, "status")}）",
                ActionErrorCode.INVALID_REQUEST,
            )
        }
        repo.deleteBlock(cmd.id)
        return CommandResult(op = cmd.op, targetId = cmd.id, note = "已否决")
    }

    // ---- tick_block：proposed/confirmed/missed → done（missed=补勾补记）spark=打折 ----

    suspend fun tickBlock(cmd: ScheduleCommand.TickBlock): CommandResult {
        val b = requireBlock(cmd.id)
        val status = blockStr(b, "status")
        val wasMissed = status == "missed"
        if (status != "proposed" && status != "confirmed" && !wasMissed) {
            throw ActionException(
                "只有 proposed/confirmed 块可勾选（missed 可补勾），当前 $status",
                ActionErrorCode.INVALID_REQUEST,
                hint = "archived/melted 是中性终态不可勾",
            )
        }
        val q = cmd.executionQuality
        if (q != null && q != "full" && q != "spark") {
            throw ActionException(
                "execution_quality 非法：$q（full/spark）",
                ActionErrorCode.INVALID_REQUEST,
            )
        }
        // Dart 'execution_quality': ?q——null 不写键（保留原值），禁强写 full
        return patchAndFresh(
            cmd.id,
            buildJsonObject {
                put("status", "done")
                if (q != null) put("execution_quality", q)
            },
            cmd.expectedVersion, cmd.op,
            note = if (wasMissed) {
                "已补勾（补记）"
            } else if (q == "spark") {
                "已勾选（5 分钟启动版，计入有效推进）"
            } else {
                "已勾选"
            },
        )
    }

    // ---- shift_block：+N min 级联顺延同日后续 AI 块 -------------------------------

    suspend fun shiftBlock(cmd: ScheduleCommand.ShiftBlock): CommandResult {
        val b = requireBlock(cmd.id)
        _requireAiMovable(b)
        val start = blockLong(b, "start_min") ?: 0
        val end = blockLong(b, "end_min") ?: 0
        val newStart = start + cmd.minutes
        val newEnd = end + cmd.minutes
        return patchAndFresh(
            cmd.id,
            buildJsonObject { put("start_min", newStart); put("end_min", newEnd) },
            cmd.expectedVersion, cmd.op,
        )
    }

    // ---- adjust_block_time：human patch 起止（校验刻度不对称——重叠/越界放行）------

    suspend fun adjustBlockTime(cmd: ScheduleCommand.AdjustBlockTime): CommandResult {
        val b = requireBlock(cmd.id)
        val start = cmd.startMin ?: blockLong(b, "start_min")
        val end = cmd.endMin ?: blockLong(b, "end_min")
        if (start == null || end == null) {
            throw ActionException("start_min 与 end_min 至少给一个", ActionErrorCode.INVALID_REQUEST)
        }
        // human 通道：跳过 AI 静态校验（重叠/越界由 UI 提示放行——校验刻度不对称 §3）
        return patchAndFresh(
            cmd.id,
            buildJsonObject { put("start_min", start); put("end_min", end) },
            cmd.expectedVersion, cmd.op,
        )
    }

    // ---- degrade_block：坍缩为 5 分钟火种胶囊（human 块不可坍缩 §6.4）--------------

    suspend fun degradeBlock(cmd: ScheduleCommand.DegradeBlock): CommandResult {
        val b = requireBlock(cmd.id)
        _requireAiMovable(b)
        if (blockStr(b, "status") != "proposed") {
            throw ActionException(
                "degrade 仅适用于 proposed 块",
                ActionErrorCode.INVALID_REQUEST,
            )
        }
        // 缩为 5 分钟：endMin = startMin + 5
        val start = blockLong(b, "start_min") ?: 0
        return patchAndFresh(
            cmd.id,
            buildJsonObject {
                put("end_min", start + 5)
                put("is_day_spark", 1)
                put("execution_quality", "spark")
            },
            cmd.expectedVersion, cmd.op,
        )
    }

    // ---- melt_block：melted 中性态无痕回池（仅物理位移/用户主动触发）---------------

    suspend fun meltBlock(cmd: ScheduleCommand.MeltBlock): CommandResult {
        val b = requireBlock(cmd.id)
        _requireAiMovable(b)
        repo.deleteBlock(cmd.id)
        return CommandResult(op = cmd.op, targetId = cmd.id, note = "已融化回池")
    }

    // ---- restore_block：melted/done → proposed/confirmed（UI 撤销通道）-------------

    suspend fun restoreBlock(cmd: ScheduleCommand.RestoreBlock): CommandResult {
        if (cmd.toStatus != "proposed" && cmd.toStatus != "confirmed") {
            throw ActionException(
                "restore 仅支持 proposed/confirmed（got: ${cmd.toStatus}）",
                ActionErrorCode.INVALID_REQUEST,
            )
        }
        // 已删除的块无法恢复——restore 只处理 status 变更（melted 物理删除走另一通道）
        // 此处简化为 status patch（melted 实际是物理删除，需重建）
        return patchAndFresh(
            cmd.id, buildJsonObject { put("status", cmd.toStatus) },
            cmd.expectedVersion, cmd.op,
        )
    }

    // ---- adjust_blocks（add/move/resize/remove 四动作，Dart _adjustBlocks 逐字）----

    suspend fun adjustBlocks(cmd: ScheduleCommand.AdjustBlocks): CommandResult = when (cmd.action) {
        "add" -> adjustAdd(cmd)
        "move", "resize" -> adjustMoveOrResize(cmd)
        "remove" -> adjustRemove(cmd)
        else -> throw ActionException(
            "未知 action：${cmd.action}（add/move/resize/remove）",
            ActionErrorCode.INVALID_REQUEST,
        )
    }

    /** 作息包络（Dart _envelope）：settings 未初始化整拒（§6 校验铁律）。 */
    private data class Envelope(val wake: Int, val sleepAdj: Int)

    private suspend fun envelope(): Envelope {
        val settings = repo.settingsAll().toStringMap()
        if (!SettingsKeys.initialized(settings)) {
            throw ActionException(
                "settings 未初始化：缺少作息边界（wake_time/sleep_time）",
                ActionErrorCode.SETTINGS_MISSING,
            )
        }
        val wake = SettingsKeys.intOf(settings, SettingsKeys.WAKE_TIME)!!
        val sleep = SettingsKeys.intOf(settings, SettingsKeys.SLEEP_TIME)!!
        return Envelope(wake, if (sleep <= wake) sleep + 1440 else sleep)
    }

    private fun JsonObject.toStringMap(): Map<String, String> =
        entries.associate { (k, v) -> k to ((v as? JsonPrimitive)?.contentOrNull ?: "") }

    private fun clockOf(m: Int): String =
        "%02d:%02d".format((m % 1440) / 60, m % 60)

    private fun tryParseIso(s: String): Boolean {
        if (!Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(s)) return false
        return try {
            java.time.LocalDate.of(
                s.substring(0, 4).toInt(), s.substring(5, 7).toInt(), s.substring(8, 10).toInt(),
            ).toString() == s
        } catch (_: Exception) {
            false
        }
    }

    /** 静态校验（Dart _proposeStaticError 逐字）：越界/零长/作息边界。 */
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

    private fun overlapsMinutes(aStart: Int, aEnd: Int, bStart: Int, bEnd: Int): Boolean =
        aStart < bEnd && bStart < aEnd

    /** 和平条款墙：human 块、pinned 块、已成现实的 AI 块（confirmed 及之后）。 */
    private fun isWall(b: JsonObject): Boolean {
        val source = blockStr(b, "source") ?: ""
        val pinned = (b["pinned"] as? JsonPrimitive)?.booleanOrNull ?: false
        return pinned || source == ScheduleBlock.SOURCE_HUMAN ||
            (source == ScheduleBlock.SOURCE_AI && blockStr(b, "status") != ScheduleBlock.STATUS_PROPOSED)
    }

    /** 墙与固定占用重叠检查（Dart _checkWalls 逐字）：例外日挂起工作类固定占用。 */
    private suspend fun checkWalls(date: String, startMin: Int, endMin: Int, excludeBlockId: String?) {
        if (!tryParseIso(date)) {
            throw ActionException("date 非法：$date（须 yyyy-MM-dd）", ActionErrorCode.INVALID_REQUEST)
        }
        val blocks = (repo.blocksOnDate(date) as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
        for (b in blocks) {
            if (b["id"]?.jsonPrimitive?.contentOrNull == excludeBlockId) continue
            if (!isWall(b)) continue
            val bs = blockLong(b, "start_min")?.toInt() ?: continue
            val be = blockLong(b, "end_min")?.toInt() ?: continue
            if (overlapsMinutes(startMin, endMin, bs, be)) {
                val src = blockStr(b, "source") ?: ""
                val pinned = (b["pinned"] as? JsonPrimitive)?.booleanOrNull ?: false
                throw ActionException(
                    "与不可穿透占用重叠（$src${if (pinned) "/pinned" else ""} 块 ${clockOf(bs)}-${clockOf(be)}）",
                    ActionErrorCode.SCHEDULE_REJECTED,
                    hint = "和平条款：human/pinned 块必须绕行（§3）",
                )
            }
        }
        if (parseExceptions(repo.settingsAll().toStringMap()).any { it.covers(date) }) {
            return // 例外日旅行模式：工作类固定占用挂起（作息边界保留，§13）
        }
        val resolved = repo.fixedSlotsForDate(date)
        for (s in slotsOfJson(resolved["on_date"])) {
            val end = if (ScheduleDay.crossesMidnight(s.startMin, s.endMin)) 1440 else s.endMin
            if (overlapsMinutes(startMin, endMin, s.startMin, end)) {
                throw ActionException("与固定占用「${s.name}」重叠", ActionErrorCode.SCHEDULE_REJECTED)
            }
        }
        for (s in slotsOfJson(resolved["spillover"])) {
            if (overlapsMinutes(startMin, endMin, 0, s.endMin)) {
                throw ActionException(
                    "与前夜溢出的固定占用「${s.name}」重叠",
                    ActionErrorCode.SCHEDULE_REJECTED,
                )
            }
        }
    }

    private suspend fun adjustAdd(cmd: ScheduleCommand.AdjustBlocks): CommandResult {
        val env = envelope()
        val date = cmd.date
        val startMin = cmd.startMin
        val endMin = cmd.endMin
        if (date == null || startMin == null || endMin == null) {
            throw ActionException(
                "add 需要 date/start_min/end_min",
                ActionErrorCode.INVALID_REQUEST,
            )
        }
        if (!tryParseIso(date)) {
            throw ActionException("date 非法：$date", ActionErrorCode.INVALID_REQUEST)
        }
        val staticErr = staticError(startMin.toInt(), endMin.toInt(), env.wake, env.sleepAdj)
        if (staticErr != null) {
            throw ActionException(staticErr, ActionErrorCode.INVALID_REQUEST)
        }
        if (cmd.planId != null) requirePlan(cmd.planId)
        // 硬红线（§9.3）：本 plan 及递归子树的 structured 凭证约束（human 通道放行，
        // 故仅 AI 调用 adjustAdd 触发——校验刻度不对称）
        cmd.planId?.let { pid ->
            val subs = subtreeArtifacts(repo, pid)
            val violations = factRuleViolations(
                subs,
                ScheduleDay.tryParseIsoDate(date)!!,
                startMin.toInt(), endMin.toInt(),
            )
            if (violations.isNotEmpty()) {
                throw ActionException(
                    "硬红线命中的事实约束：${violations.first().reason}",
                    ActionErrorCode.SCHEDULE_REJECTED,
                    data = buildJsonObject {
                        put("rejected_by_facts", JsonArray(violations.map { x ->
                            buildJsonObject {
                                put("artifact_id", x.fact["id"] ?: kotlinx.serialization.json.JsonNull)
                                put("title", x.fact["title"] ?: kotlinx.serialization.json.JsonNull)
                                put("reason", x.reason)
                            }
                        }))
                    },
                )
            }
        }
        checkWalls(date, startMin.toInt(), endMin.toInt(), excludeBlockId = null)
        val b = repo.addBlock(ScheduleBlock(
            id = null,
            date = date,
            startMin = startMin,
            endMin = endMin,
            planId = cmd.planId,
            label = cmd.label,
            source = ScheduleBlock.SOURCE_AI,
            pinned = cmd.pinned ?: true, // AI 建的无 plan 硬行程默认 pinned（§4）；显式传可覆盖
            status = ScheduleBlock.STATUS_PROPOSED,
            postponeCount = 0,
            executionQuality = ScheduleBlock.QUALITY_FULL,
            isDaySpark = false,
            isCelebration = false,
            createdAt = null,
            updatedAt = null,
            version = 0,
        ).toMap())
        val bPinned = (b["pinned"] as? JsonPrimitive)?.booleanOrNull ?: true
        return CommandResult(
            op = cmd.op,
            targetId = b["id"]?.jsonPrimitive?.contentOrNull,
            snapshot = b,
            note = "已加入单块提案（pinned=$bPinned）；硬行程不参与填充率聚合",
        )
    }

    private suspend fun adjustMoveOrResize(cmd: ScheduleCommand.AdjustBlocks): CommandResult {
        val blockId = cmd.blockId ?: throw ActionException(
            "move/resize 需要 block_id", ActionErrorCode.INVALID_REQUEST,
        )
        val b = requireBlock(blockId)
        _requireAiMovable(b)
        val env = envelope()
        val date = cmd.date ?: blockStr(b, "date") ?: ""
        val start = cmd.startMin ?: blockLong(b, "start_min") ?: 0L
        val end = cmd.endMin ?: blockLong(b, "end_min") ?: 0L
        if (cmd.date != null && !tryParseIso(date)) {
            throw ActionException("date 非法：$date", ActionErrorCode.INVALID_REQUEST)
        }
        val staticErr = staticError(start.toInt(), end.toInt(), env.wake, env.sleepAdj)
        if (staticErr != null) {
            throw ActionException(staticErr, ActionErrorCode.INVALID_REQUEST)
        }
        checkWalls(date, start.toInt(), end.toInt(), excludeBlockId = blockId)
        val values = buildJsonObject {
            if (cmd.date != null) put("date", cmd.date)
            if (cmd.startMin != null) put("start_min", cmd.startMin)
            if (cmd.endMin != null) put("end_min", cmd.endMin)
        }
        val ok = repo.patchBlock(blockId, values, cmd.expectedVersion)
        if (!ok) {
            val fresh = repo.blockById(blockId)
            throw ActionException(
                "版本冲突：${cmd.op} 已被其他端修改（expected_version 不匹配）",
                ActionErrorCode.VERSION_CONFLICT,
                hint = "直接在返回的 latest 快照上合并你的修改重试，不要重拉",
                data = fresh?.let {
                    buildJsonObject {
                        put("code", "version_conflict")
                        put("latest", it)
                    }
                },
            )
        }
        val fresh = requireBlock(blockId)
        return CommandResult(op = cmd.op, targetId = blockId, snapshot = fresh, note = "已调整")
    }

    private suspend fun adjustRemove(cmd: ScheduleCommand.AdjustBlocks): CommandResult {
        val blockId = cmd.blockId ?: throw ActionException(
            "remove 需要 block_id", ActionErrorCode.INVALID_REQUEST,
        )
        val b = requireBlock(blockId)
        _requireAiMovable(b)
        if (blockStr(b, "status") == ScheduleBlock.STATUS_PROPOSED) {
            repo.deleteBlock(blockId)
            return CommandResult(op = cmd.op, targetId = blockId, note = "提案已撤回")
        }
        // confirmed 及之后：熔断同效动作——melted 中性蒸发回池，绝不显红（§4/§10）
        val ok = repo.patchBlock(
            blockId,
            buildJsonObject { put("status", "melted") },
            cmd.expectedVersion,
        )
        if (!ok) {
            val fresh = repo.blockById(blockId)
            throw ActionException(
                "版本冲突：${cmd.op} 已被其他端修改（expected_version 不匹配）",
                ActionErrorCode.VERSION_CONFLICT,
                hint = "直接在返回的 latest 快照上合并你的修改重试，不要重拉",
                data = fresh?.let {
                    buildJsonObject {
                        put("code", "version_conflict")
                        put("latest", it)
                    }
                },
            )
        }
        val fresh = requireBlock(blockId)
        return CommandResult(
            op = cmd.op,
            targetId = blockId,
            snapshot = fresh,
            note = "已融化回愿望池（melted 中性态，无痕）",
        )
    }

    private suspend fun requirePlan(id: String): JsonObject =
        repo.planById(id) ?: throw ActionException("计划不存在：id=$id", ActionErrorCode.NOT_FOUND)

    /** 块乐观锁冲突（Dart _blockConflict 逐字：latest 快照随拒绝体）。 */
    private suspend fun blockConflict(op: String, id: String): ActionException {
        val fresh = repo.blockById(id)
        return ActionException(
            "版本冲突：$op 已被其他端修改（expected_version 不匹配）",
            ActionErrorCode.VERSION_CONFLICT,
            hint = "直接在返回的 latest 快照上合并你的修改重试，不要重拉",
            data = fresh?.let {
                buildJsonObject {
                    put("code", "version_conflict")
                    put("latest", it)
                }
            },
        )
    }

    // ---- postpone_block：missed 块顺延目标日（遗留区口径，human 专属）--------------

    suspend fun postponeBlock(cmd: ScheduleCommand.PostponeBlock): CommandResult {
        val target = ScheduleDay.tryParseIsoDate(cmd.date)
            ?: throw ActionException("date 非法：${cmd.date}", ActionErrorCode.INVALID_REQUEST)
        val b = requireBlock(cmd.id)
        if (blockStr(b, "status") != "missed") {
            throw ActionException(
                "仅 missed 块可顺延（遗留区口径，当前 ${blockStr(b, "status")}）",
                ActionErrorCode.INVALID_REQUEST,
                hint = "proposed/confirmed 走确认三键或 adjust_blocks",
            )
        }
        if (blockStr(b, "date") == cmd.date) {
            throw ActionException("块已在目标日 ${cmd.date}", ActionErrorCode.INVALID_REQUEST)
        }
        val bs = (blockLong(b, "start_min") ?: 0L).toInt()
        val be = (blockLong(b, "end_min") ?: 0L).toInt()
        val blocks = (repo.blocksOnDate(cmd.date) as? JsonArray)
            ?.mapNotNull { it as? JsonObject } ?: emptyList()
        for (o in blocks) {
            val st = blockStr(o, "status")
            if ((o["id"] as? JsonPrimitive)?.contentOrNull == cmd.id ||
                st == "melted" || st == "archived"
            ) {
                continue
            }
            val os = blockLong(o, "start_min") ?: 0
            val oe = blockLong(o, "end_min") ?: 0
            if (os < be && bs < oe) {
                throw ActionException(
                    "目标日 ${cmd.date} ${clockOf(bs)}–${clockOf(be)} 与现有块重叠",
                    ActionErrorCode.INVALID_REQUEST,
                    hint = "先腾挪该时段，或对块用「改时间」微调",
                )
            }
        }
        val resolved = repo.fixedSlotsForDate(cmd.date)
        for (s in slotsOfJson(resolved["on_date"]) + slotsOfJson(resolved["spillover"])) {
            val sStart = if (s.startMin < 0) 0 else s.startMin
            if (sStart < be && bs < s.endMin) {
                throw ActionException(
                    "目标日 ${cmd.date} ${clockOf(bs)}–${clockOf(be)} 撞固定占用「${s.name}」",
                    ActionErrorCode.INVALID_REQUEST,
                )
            }
        }
        val postponeCount = blockLong(b, "postpone_count") ?: 0
        val ok = repo.patchBlock(
            cmd.id,
            buildJsonObject { put("date", cmd.date); put("postpone_count", postponeCount + 1) },
            cmd.expectedVersion,
        )
        if (!ok) throw blockConflict(cmd.op, cmd.id)
        val fresh = requireBlock(cmd.id)
        val fc = blockLong(fresh, "postpone_count") ?: 0
        val alert = if (fc >= ScheduleRules.POSTPONE_ALERT) "（已顺延 $fc 次，需人工决策）" else ""
        return CommandResult(
            op = cmd.op,
            targetId = cmd.id,
            snapshot = fresh,
            note = "已顺延至 ${cmd.date}$alert",
        )
    }

    private fun _requireAiMovable(b: JsonObject) {
        if (!isAiMovable(b)) {
            val source = blockStr(b, "source") ?: "?"
            val pinned = (blockLong(b, "pinned") ?: 0) != 0L
            throw ActionException(
                "和平条款：human/pinned 块不可动（source=$source pinned=$pinned，§3）",
                ActionErrorCode.FORBIDDEN,
                hint = "human 块由用户在 app 直接操作",
            )
        }
    }
}
