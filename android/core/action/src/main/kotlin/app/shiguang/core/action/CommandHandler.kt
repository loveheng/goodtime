package app.shiguang.core.action

import app.shiguang.core.model.SettingsKeys
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * CommandHandler Kotlin 语义平移（P2 核心，行为清单已过目）。
 *
 * 双轨期架构：语义层（本文件）← BridgeRepositoryPort ← DartBridgeRepository →
 * channel → Dart Repository（FIFO）→ SQLite。SQL 落地后端口切原生，语义层零改。
 *
 * 平移纪律：
 * - 行为 = behavior/commands_registry.md 七字段清单（已过目）；
 * - 越权门（human-only / ai-only）+ 乐观锁 CAS + 校验函数语义逐字；
 * - 静默丢弃不翻译（Kotlin when 穷尽性编译器强制显式 else）。
 */
enum class CommandActor { HUMAN, AI }

class CommandHandler(private val repo: BridgeRepositoryPort) {

    /** 分域 handler（Dart 单类 dispatch 的 Kotlin 四件拆分——统一入口在本类）。 */
    private val blockOps = BlockOpsHandler(repo)
    private val proposeHandler = ProposeHandler(repo)
    private val residual = ResidualHandler(repo)

    /** 执行单条命令——UI 与 AI 唯一的落库路径。写路径串行（FIFO 在 Dart 侧）。 */
    suspend fun execute(cmd: ScheduleCommand, actor: CommandActor): CommandResult {
        gate(cmd, actor)
        return dispatch(cmd, actor)
    }

    private fun gate(cmd: ScheduleCommand, actor: CommandActor) {
        val humanOnly = when (cmd) {
            is ScheduleCommand.QuickCapture,
            is ScheduleCommand.QuickNoteDraft,
            is ScheduleCommand.PlaceBlock,
            is ScheduleCommand.RetroLog,
            is ScheduleCommand.SwapBlock,
            is ScheduleCommand.DegradeBlock,
            is ScheduleCommand.MeltBlock,
            is ScheduleCommand.RestoreBlock,
            is ScheduleCommand.ReflowDay,
            is ScheduleCommand.DeletePlan,
            is ScheduleCommand.ConfirmBlock,
            is ScheduleCommand.RejectBlock,
            is ScheduleCommand.AdjustBlockTime,
            is ScheduleCommand.ShiftBlock,
            is ScheduleCommand.TickBlock,
            is ScheduleCommand.PostponeBlock,
            is ScheduleCommand.DeleteArtifact,
            is ScheduleCommand.PanicClear,
            -> true
            else -> false
        }
        if (humanOnly && actor != CommandActor.HUMAN) {
            throw ActionException(
                "越权：${cmd.op} 仅限人类执行（人=终审，§3 三方职责契约）",
                ActionErrorCode.FORBIDDEN,
                hint = "AI 侧请走对应工具（add_plan/update_plan/propose_schedule）",
            )
        }
        val aiOnly = cmd is ScheduleCommand.ProposeSchedule || cmd is ScheduleCommand.AdjustBlocks
        if (aiOnly && actor != CommandActor.AI) {
            throw ActionException(
                "越权：${cmd.op} 仅限 AI（校验刻度不对称，§3：人直接放块走 place_block/app 通道）",
                ActionErrorCode.FORBIDDEN,
            )
        }
        // uiOnly 键：外观/感官偏好仅人可写，AI 经 update_settings 触碰即拒
        // （Human-AI 对称性：人的感官偏好 AI 无权代拨，2026-10-06 拍板）。
        if (cmd is ScheduleCommand.UpdateSettings && actor == CommandActor.AI) {
            for (key in cmd.values.keys) {
                if (key == SettingsKeys.THEME_MODE || key == SettingsKeys.USER_RULES ||
                    key == SettingsKeys.WEATHER_LOCATION || key == SettingsKeys.EXCEPTIONS ||
                    key == SettingsKeys.IDENTITY_PROMPT || key == SettingsKeys.NAV_AUTO_HIDE
                ) {
                    throw ActionException(
                        "越权：设置键 $key 仅限用户本人在设置页修改",
                        ActionErrorCode.FORBIDDEN,
                        hint = "外观偏好（theme_mode）由用户自选，AI 请勿代拨",
                    )
                }
            }
        }
    }

    /** 统一分发（Dart CommandHandler.dispatch 全 26 命令等价）：分域路由，
     *  swap/reflow/panic 三件双轨期桩自抛（ResidualHandler），postpone 待平移。 */
    private suspend fun dispatch(cmd: ScheduleCommand, actor: CommandActor): CommandResult = when (cmd) {
        is ScheduleCommand.QuickCapture -> quickCapture(cmd)
        is ScheduleCommand.UpsertPlan -> upsertPlan(cmd)
        is ScheduleCommand.UpdatePlan -> updatePlan(cmd)
        is ScheduleCommand.ProposeSchedule -> proposeHandler.propose(cmd)
        is ScheduleCommand.AdjustBlocks -> blockOps.adjustBlocks(cmd)
        is ScheduleCommand.ConfirmBlock -> blockOps.confirmBlock(cmd)
        is ScheduleCommand.RejectBlock -> blockOps.rejectBlock(cmd)
        is ScheduleCommand.TickBlock -> blockOps.tickBlock(cmd)
        is ScheduleCommand.ShiftBlock -> blockOps.shiftBlock(cmd)
        is ScheduleCommand.AdjustBlockTime -> blockOps.adjustBlockTime(cmd)
        is ScheduleCommand.DegradeBlock -> blockOps.degradeBlock(cmd)
        is ScheduleCommand.MeltBlock -> blockOps.meltBlock(cmd)
        is ScheduleCommand.RestoreBlock -> blockOps.restoreBlock(cmd)
        is ScheduleCommand.QuickNoteDraft -> residual.quickNoteDraft(cmd)
        is ScheduleCommand.PlaceBlock -> residual.placeBlock(cmd)
        is ScheduleCommand.RetroLog -> residual.retroLog(cmd)
        is ScheduleCommand.DeletePlan -> residual.deletePlan(cmd)
        is ScheduleCommand.DeleteArtifact -> residual.deleteArtifact(cmd)
        is ScheduleCommand.UpdateSettings -> residual.updateSettings(cmd)
        is ScheduleCommand.UpdateFixedSlots -> residual.updateFixedSlots(cmd)
        is ScheduleCommand.UpsertBackground -> residual.upsertBackground(cmd, actor)
        is ScheduleCommand.MergeBackgrounds -> residual.mergeBackgrounds(cmd, actor)
        is ScheduleCommand.UpsertFacts -> residual.upsertFacts(cmd, actor)
        is ScheduleCommand.SwapBlock -> residual.swapBlock(cmd)
        is ScheduleCommand.ReflowDay -> residual.reflowDay(cmd)
        is ScheduleCommand.PanicClear -> residual.panicClear(cmd)
        is ScheduleCommand.PostponeBlock -> blockOps.postponeBlock(cmd)
    }

    // ---- 核心辅助 ---------------------------------------------------------------

    private fun validateDeadline(deadline: String?) {
        if (deadline != null && tryParseIsoDate(deadline) == null) {
            throw ActionException(
                "deadline 非法：$deadline（须 yyyy-MM-dd）",
                ActionErrorCode.INVALID_REQUEST,
            )
        }
    }

    private fun validatePlanFields(
        energyLevel: String?,
        toolRequired: String?,
        deadline: String?,
        estimate: Long?,
    ) {
        if (energyLevel != null && energyLevel != "deep" && energyLevel != "light") {
            throw ActionException(
                "energy_level 非法：$energyLevel（deep/light）",
                ActionErrorCode.INVALID_REQUEST,
            )
        }
        if (toolRequired != null && toolRequired != "desk" && toolRequired != "anywhere") {
            throw ActionException(
                "tool_required 非法：$toolRequired（desk/anywhere）",
                ActionErrorCode.INVALID_REQUEST,
            )
        }
        validateDeadline(deadline)
        if (estimate != null && estimate < 1) {
            throw ActionException(
                "estimate 须为正分钟数",
                ActionErrorCode.INVALID_REQUEST,
            )
        }
    }

    private suspend fun requirePlan(id: String): JsonObject {
        val p = repo.planById(id) ?: throw ActionException(
            "计划不存在：id=$id",
            ActionErrorCode.NOT_FOUND,
        )
        return p
    }

    private fun conflict(op: String, latest: JsonObject?): ActionException = ActionException(
        "版本冲突：$op 已被其他端修改（expected_version 不匹配）",
        ActionErrorCode.VERSION_CONFLICT,
        hint = "直接在返回的 latest 快照上合并你的修改重试，不要重拉",
        data = latest?.let { buildJsonObject { put("code", "version_conflict"); put("latest", it) } },
    )

    private fun tryParseIsoDate(s: String): Boolean {
        if (!Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(s)) return false
        val parts = s.split("-")
        val y = parts[0].toInt()
        val m = parts[1].toInt()
        val d = parts[2].toInt()
        if (m < 1 || m > 12 || d < 1 || d > 31) return false
        // 简化日历校验（与 Dart DateTime 构造验证等价——溢出日返回 false）
        return try {
            java.time.LocalDate.of(y, m, d).toString().substring(0, 10) == s
        } catch (_: Exception) {
            false
        }
    }

    // ---- planToJson（§11 snapshot 序列化口径） ------------------------------------

    private fun planToJson(
        id: String?,
        title: String,
        spec: String?,
        notes: String?,
        openItems: JsonElement?,
        minViableAction: String?,
        energyLevel: String,
        toolRequired: String,
        rewardSpec: String?,
        importance: Boolean,
        deadline: String?,
        estimate: Long?,
        parentId: String?,
        archived: Boolean,
        version: Long,
    ): JsonObject = buildJsonObject {
        put("id", id)
        put("title", title)
        put("spec", spec)
        put("notes", notes)
        put("open_items", openItems ?: JsonArray(emptyList()))
        put("min_viable_action", minViableAction)
        put("energy_level", energyLevel)
        put("tool_required", toolRequired)
        put("reward_spec", rewardSpec)
        put("importance", importance)
        put("deadline", deadline)
        put("estimate", estimate)
        put("parent_id", parentId)
        put("archived", archived)
        put("version", version)
    }

    // ---- 命令语义（quick_capture / upsert_plan / update_plan） --------------------

    private suspend fun quickCapture(cmd: ScheduleCommand.QuickCapture): CommandResult {
        validateDeadline(cmd.deadline)
        val values = buildJsonObject {
            put("title", cmd.title)
            put("importance", cmd.importance)
            if (cmd.deadline != null) put("deadline", cmd.deadline)
        }
        val plan = repo.addPlan(values)
        return CommandResult(
            op = cmd.op,
            targetId = plan["id"]?.jsonPrimitive?.contentOrNull,
            snapshot = plan,
            note = "已收入清单（默认轻松/随手可做，被排期即升格）",
        )
    }

    private suspend fun upsertPlan(cmd: ScheduleCommand.UpsertPlan): CommandResult {
        validatePlanFields(cmd.energyLevel, cmd.toolRequired, cmd.deadline, cmd.estimate)
        if (cmd.parentId != null) requirePlan(cmd.parentId)
        val values = buildJsonObject {
            put("title", cmd.title)
            cmd.spec?.let { put("spec", it) }
            cmd.notes?.let { put("notes", it) }
            cmd.minViableAction?.let { put("min_viable_action", it) }
            cmd.energyLevel?.let { put("energy_level", it) }
            cmd.toolRequired?.let { put("tool_required", it) }
            cmd.rewardSpec?.let { put("reward_spec", it) }
            put("importance", cmd.importance)
            cmd.deadline?.let { put("deadline", it) }
            cmd.estimate?.let { put("estimate", it) }
            cmd.parentId?.let { put("parent_id", it) }
        }
        val plan = repo.addPlan(values)
        return CommandResult(
            op = cmd.op,
            targetId = plan["id"]?.jsonPrimitive?.contentOrNull,
            snapshot = plan,
            note = "计划已创建",
        )
    }

    private suspend fun updatePlan(cmd: ScheduleCommand.UpdatePlan): CommandResult {
        requirePlan(cmd.id)
        validatePlanFields(cmd.energyLevel, cmd.toolRequired, cmd.deadline, cmd.estimate)
        val values = buildJsonObject {
            cmd.title?.let { put("title", it) }
            cmd.spec?.let { put("spec", it) }
            cmd.notes?.let { put("notes", it) }
            cmd.minViableAction?.let { put("min_viable_action", it) }
            cmd.energyLevel?.let { put("energy_level", it) }
            cmd.toolRequired?.let { put("tool_required", it) }
            cmd.rewardSpec?.let { put("reward_spec", it) }
            cmd.importance?.let { put("importance", it) }
            cmd.deadline?.let { put("deadline", it) }
            cmd.estimate?.let { put("estimate", it) }
            cmd.parentId?.let { put("parent_id", it) }
            // open_items 整表替换写库（Dart `if (cmd.openItems != null)` 逐字——手答
            // 闭环的落库通道；此前平移缺此行，plan_detail 施工轮 2026-11-15 抓修）
            cmd.openItems?.let { put("open_items", app.shiguang.core.model.Plan.encodeOpenItems(it.map { o -> app.shiguang.core.model.OpenItem(o.question, o.answer) })) }
            cmd.archived?.let { put("archived", it) }
        }
        if (values.isEmpty()) {
            throw ActionException(
                "没有可更新的字段",
                ActionErrorCode.INVALID_REQUEST,
                hint = "update_plan 只发要改的字段（§11 并发契约）",
            )
        }
        val ok = repo.patchPlan(cmd.id, values, cmd.expectedVersion)
        if (!ok) {
            val fresh = repo.planById(cmd.id)
            throw conflict(cmd.op, fresh)
        }
        val fresh = requirePlan(cmd.id)
        return CommandResult(
            op = cmd.op,
            targetId = cmd.id,
            snapshot = fresh,
            note = "已更新",
        )
    }
}

private fun tryParseIsoDate(s: String): Boolean {
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
