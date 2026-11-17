package app.shiguang.core.action

import app.shiguang.core.model.Artifact
import app.shiguang.core.model.ArtifactRules
import app.shiguang.core.model.Background
import app.shiguang.core.model.PlanIdArg
import app.shiguang.core.model.ScheduleDay
import app.shiguang.core.model.ScheduleBlock
import app.shiguang.core.model.ScheduleRules
import app.shiguang.core.model.SettingsKeys
import app.shiguang.core.model.rowToJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** P2 handler 语义平移·残余 13 命令（quick_note_draft/place_block/retro_log/delete_plan/
 *  delete_artifact/update_settings/update_fixed_slots/upsert_background/merge_backgrounds/
 *  upsert_facts/swap_block/reflow_day/panic_clear——N5.5 起 13 命令全原生（swap/reflow/
 *  panic 桥桩退役）；dispatch 27/27 全量原生语义。 */
class ResidualHandler(private val repo: BridgeRepositoryPort) {

    private suspend fun requireBlock(id: String): JsonObject =
        repo.blockById(id) ?: throw ActionException("块不存在：id=$id", ActionErrorCode.NOT_FOUND)

    private suspend fun requirePlan(id: String): JsonObject =
        repo.planById(id) ?: throw ActionException("计划不存在：id=$id", ActionErrorCode.NOT_FOUND)

    private fun strOf(v: kotlinx.serialization.json.JsonElement?): String? =
        (v as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }

    private fun validateDeadline(d: String?) {
        if (d != null && tryIso(d) == null) {
            throw ActionException("deadline 非法：$d（须 yyyy-MM-dd）", ActionErrorCode.INVALID_REQUEST)
        }
    }

    private fun validatePlanFields(energy: String?, tool: String?, deadline: String?, estimate: Long?) {
        if (energy != null && energy != "deep" && energy != "light") {
            throw ActionException("energy_level 非法：$energy（deep/light）", ActionErrorCode.INVALID_REQUEST)
        }
        if (tool != null && tool != "desk" && tool != "anywhere") {
            throw ActionException("tool_required 非法：$tool（desk/anywhere）", ActionErrorCode.INVALID_REQUEST)
        }
        validateDeadline(deadline)
        if (estimate != null && estimate < 1) {
            throw ActionException("estimate 须为正分钟数", ActionErrorCode.INVALID_REQUEST)
        }
    }

    private fun tryIso(s: String): Boolean {
        if (!Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(s)) return false
        return try {
            java.time.LocalDate.of(s.substring(0, 4).toInt(), s.substring(5, 7).toInt(),
                s.substring(8, 10).toInt()).toString() == s
        } catch (_: Exception) { false }
    }

    // ---- quick_note_draft（human-only，三字段整体覆写；text 空=清除）----------------

    suspend fun quickNoteDraft(cmd: ScheduleCommand.QuickNoteDraft): CommandResult {
        val text = cmd.text.trim()
        if (text.isBlank()) {
            repo.settingsClear("quick_note_draft_text")
            repo.settingsClear("quick_note_draft_important")
            repo.settingsClear("quick_note_draft_deadline")
            return CommandResult(op = cmd.op, note = "草稿已清除")
        }
        repo.settingsSet(buildJsonObject {
            put("quick_note_draft_text", text)
            put("quick_note_draft_important", if (cmd.importance) "1" else "0")
        })
        if (cmd.deadline == null) {
            repo.settingsClear("quick_note_draft_deadline")
        } else {
            repo.settingsSet(buildJsonObject { put("quick_note_draft_deadline", cmd.deadline) })
        }
        return CommandResult(op = cmd.op, note = "草稿已暂存")
    }

    // ---- place_block（human 放行通道：校验刻度不对称——只拦起止非法）---------------

    suspend fun placeBlock(cmd: ScheduleCommand.PlaceBlock): CommandResult {
        if (!tryIso(cmd.date)) {
            throw ActionException("date 非法：${cmd.date}（须 yyyy-MM-dd）", ActionErrorCode.INVALID_REQUEST)
        }
        if (cmd.startMin < 0 || cmd.startMin > 1439 || cmd.endMin < 1 || cmd.endMin > 1440) {
            throw ActionException("起止越界（start 0..1439 / end 1..1440）", ActionErrorCode.INVALID_REQUEST)
        }
        if (cmd.startMin == cmd.endMin) {
            throw ActionException("零长块：start == end", ActionErrorCode.INVALID_REQUEST)
        }
        if (cmd.planId != null) requirePlan(cmd.planId)
        val values = buildJsonObject {
            put("date", cmd.date)
            put("start_min", cmd.startMin)
            put("end_min", cmd.endMin)
            cmd.planId?.let { put("plan_id", it) }
            cmd.label?.let { put("label", it) }
            put("source", "human")
            put("status", "confirmed")
        }
        val b = repo.addBlock(values)
        return CommandResult(op = cmd.op, targetId = b["id"]?.jsonPrimitive?.contentOrNull,
            snapshot = b, note = "已放入日程（自己排的事项，AI 会绕行）")
    }

    // ---- retro_log（逆向记账：事实通道豁免排程校验，凡真实专注全部承认）------------

    suspend fun retroLog(cmd: ScheduleCommand.RetroLog): CommandResult {
        if (!tryIso(cmd.date)) {
            throw ActionException("date 非法：${cmd.date}", ActionErrorCode.INVALID_REQUEST)
        }
        if (cmd.startMin < 0 || cmd.startMin > 1439 || cmd.endMin < 1 || cmd.endMin > 1440) {
            throw ActionException("起止越界", ActionErrorCode.INVALID_REQUEST)
        }
        if (cmd.startMin == cmd.endMin) {
            throw ActionException("零长块", ActionErrorCode.INVALID_REQUEST)
        }
        if (cmd.planId != null) requirePlan(cmd.planId)
        val q = cmd.executionQuality
        if (q != null && q != "full" && q != "spark") {
            throw ActionException("execution_quality 非法：$q（full/spark）", ActionErrorCode.INVALID_REQUEST)
        }
        val values = buildJsonObject {
            put("date", cmd.date); put("start_min", cmd.startMin); put("end_min", cmd.endMin)
            cmd.planId?.let { put("plan_id", it) }
            cmd.label?.let { put("label", it) }
            put("source", "human"); put("status", "done")
            put("execution_quality", q ?: "full")
        }
        val b = repo.addBlock(values)
        return CommandResult(op = cmd.op, targetId = b["id"]?.jsonPrimitive?.contentOrNull,
            snapshot = b, note = "已补记入账（事实通道，豁免排程校验；凡真实专注全部承认）")
    }

    // ---- delete_plan（human 专属不可逆；FK 转可读；级联=背景销毁+artifacts detach）--

    suspend fun deletePlan(cmd: ScheduleCommand.DeletePlan): CommandResult {
        requirePlan(cmd.id)
        repo.deletePlan(cmd.id)
        return CommandResult(op = cmd.op, targetId = cmd.id, note = "计划已删除")
    }

    // ---- delete_artifact（human 专属；AI 无删除权；反向销毁防护）--------------------

    suspend fun deleteArtifact(cmd: ScheduleCommand.DeleteArtifact): CommandResult {
        val existing = repo.artifactById(cmd.id)
            ?: throw ActionException("凭证不存在: ${cmd.id}", ActionErrorCode.NOT_FOUND)
        var demoted = false
        repo.transaction { txn ->
            txn.delete("artifacts", "id = ?", listOf(cmd.id))
            // 反转关联（fact §9.5-6）：置空**所有引用块**的挂载并降格 pinned
            // （1:N 软引用，绝不物理删块——§1.4 反向销毁防护）
            val n = txn.rawUpdate(
                "UPDATE schedule_blocks SET pinned = 0, artifact_id = NULL, version = version + 1, updated_at = ? WHERE artifact_id = ?",
                listOf(System.currentTimeMillis(), cmd.id),
            )
            demoted = n > 0
        }
        return CommandResult(
            op = cmd.op,
            targetId = cmd.id,
            note = if (demoted) "已删除凭证（关联块已解除钉定，日程块保留）" else "已删除凭证",
        )
    }

    // ---- update_settings（键注册制：未知键拒 / 分键校验 / null=清键；uiOnly 五键边界在 gate）----

    suspend fun updateSettings(cmd: ScheduleCommand.UpdateSettings): CommandResult {
        val toSet = LinkedHashMap<String, String>()
        val toClear = mutableListOf<String>()
        for ((key, v) in cmd.values) {
            if (!SettingsKeys.ALL.contains(key)) {
                throw ActionException(
                    "未知设置键：$key",
                    ActionErrorCode.INVALID_REQUEST,
                    hint = "合法键：${SettingsKeys.ALL.joinToString(" / ")}",
                )
            }
            if (v is JsonNull) {
                toClear.add(key)
                continue
            }
            when (key) {
                SettingsKeys.WAKE_TIME, SettingsKeys.SLEEP_TIME -> {
                    val n = v.jsonPrimitive.longOrNull
                    if (n == null || n < 0 || n > 1439) {
                        throw ActionException("$key 须为分钟数 0..1439", ActionErrorCode.INVALID_REQUEST)
                    }
                    toSet[key] = n.toString()
                }
                SettingsKeys.MIN_BLOCK_MINUTES, SettingsKeys.DAILY_NEW_BLOCKS_LIMIT -> {
                    val n = v.jsonPrimitive.longOrNull
                    if (n == null || n < 1) {
                        throw ActionException("$key 须为正整数", ActionErrorCode.INVALID_REQUEST)
                    }
                    toSet[key] = n.toString()
                }
                SettingsKeys.FILL_RATE_LIMIT -> {
                    val n = v.jsonPrimitive.longOrNull
                    if (n == null || n < 5 || n > 100) {
                        throw ActionException(
                            "fill_rate_limit 须为百分比 5..100（默认 60，§6「默认可调」）",
                            ActionErrorCode.INVALID_REQUEST,
                        )
                    }
                    toSet[key] = n.toString()
                }
                SettingsKeys.TODAY_ENERGY -> {
                    val s = v.jsonPrimitive.content
                    if (s != "high" && s != "normal" && s != "low") {
                        throw ActionException(
                            "today_energy 须为 high/normal/low（三档手动点选，永不接传感器 §6）",
                            ActionErrorCode.INVALID_REQUEST,
                        )
                    }
                    toSet[key] = s
                }
                SettingsKeys.USER_RULES, SettingsKeys.WEATHER_LOCATION, SettingsKeys.IDENTITY_PROMPT ->
                    toSet[key] = v.jsonPrimitive.content
                SettingsKeys.THEME_MODE -> {
                    val s = v.jsonPrimitive.content
                    if (s != "system" && s != "light" && s != "dark") {
                        throw ActionException(
                            "theme_mode 须为 system/light/dark（外观三档，ui-spec §0.3/§0.5）",
                            ActionErrorCode.INVALID_REQUEST,
                        )
                    }
                    toSet[key] = s
                }
                SettingsKeys.EXCEPTIONS -> {
                    val decoded = if (v is JsonPrimitive && v.isString) Json.parseToJsonElement(v.content) else v
                    if (decoded !is JsonArray) {
                        throw ActionException(
                            "exceptions 须为 JSON 数组（[{start,end,label?}]，§13 旅行模式）",
                            ActionErrorCode.INVALID_REQUEST,
                        )
                    }
                    toSet[key] = decoded.toString()
                }
            }
        }
        repo.settingsSet(buildJsonObject { toSet.forEach { (k, s) -> put(k, s) } })
        for (k in toClear) repo.settingsClear(k)
        val fresh = repo.settingsAll()
        return CommandResult(op = cmd.op, note = "设置已更新", data = buildJsonObject { put("settings", fresh) })
    }

    // ---- update_fixed_slots（整批原子替换；name/weekdays/起止三检，Dart 逐字）--------

    suspend fun updateFixedSlots(cmd: ScheduleCommand.UpdateFixedSlots): CommandResult {
        for (s in cmd.slots) {
            if (s.name.isBlank()) {
                throw ActionException("fixed_slot name 不能为空", ActionErrorCode.INVALID_REQUEST)
            }
            if (s.weekdays.isEmpty() || s.weekdays.any { it < 1 || it > 7 }) {
                throw ActionException(
                    "「${s.name}」weekdays 非法：须为 1..7 的 ISO 星期（1=周一）",
                    ActionErrorCode.INVALID_REQUEST,
                    hint = "工作日/周末两套模板 = 不同 weekday 集合的行并存（§4）",
                )
            }
            if (s.startMin < 0 || s.startMin > 1439 || s.endMin < 1 || s.endMin > 1440 || s.startMin == s.endMin) {
                throw ActionException(
                    "「${s.name}」起止非法（start 0..1439 / end 1..1440 / 不可零长）",
                    ActionErrorCode.INVALID_REQUEST,
                )
            }
        }
        repo.replaceFixedSlots(JsonArray(cmd.slots.map { s ->
            buildJsonObject {
                s.id?.let { put("id", it) }
                put("name", s.name)
                put("weekdays", JsonArray(s.weekdays.map { JsonPrimitive(it) }))
                put("start_min", s.startMin)
                put("end_min", s.endMin)
            }
        }))
        val slots = repo.listFixedSlots()
        return CommandResult(
            op = cmd.op,
            data = buildJsonObject { put("slots", slots) },
            note = "一周节奏已整单替换（${(slots as? JsonArray)?.size ?: 0} 项）",
        )
    }



    // ---- upsert_background（手编契约四不可变；预算双指标仅拦 ai）--------------------

    suspend fun upsertBackground(cmd: ScheduleCommand.UpsertBackground, actor: CommandActor): CommandResult {
        if (cmd.scope == Background.SCOPE_PLAN) {
            if (cmd.planId == null) {
                throw ActionException(
                    "scope=plan 需要 plan_id（行程背景挂在计划上）",
                    ActionErrorCode.INVALID_REQUEST,
                )
            }
            requirePlan(cmd.planId)
        } else if (cmd.planId != null) {
            throw ActionException(
                "scope=global 不应携带 plan_id（常驻画像不挂计划）",
                ActionErrorCode.INVALID_REQUEST,
            )
        }
        validateApplicableDates(cmd.op, cmd.applicableDates)

        val existing = cmd.id?.let { repo.backgroundById(it) }
        if (existing == null) {
            val b = Background(
                id = cmd.id,
                scope = cmd.scope,
                planId = cmd.planId,
                content = cmd.content,
                rawSourceText = cmd.rawSourceText,
                tags = cmd.tags ?: emptyList(),
                applicableDates = cmd.applicableDates,
                source = backgroundSource(actor),
                capturedBy = Background.CAPTURED_BY_ME,
                createdAt = null,
                updatedAt = null,
                version = 0,
            )
            if (cmd.scope == Background.SCOPE_GLOBAL && actor == CommandActor.AI) {
                requireGlobalBudget(cmd.op, additions = listOf(b))
            }
            val created = repo.addBackground(b.toMap())
            var note = backgroundNote(
                "已记录背景",
                mcpStringList(created["applicable_dates"]),
            )
            if (cmd.scope == Background.SCOPE_GLOBAL && actor == CommandActor.HUMAN) {
                note = appendBudgetWarning(note)
            }
            return CommandResult(
                op = cmd.op,
                targetId = created["id"]?.jsonPrimitive?.contentOrNull,
                snapshot = created,
                note = note,
            )
        }

        // 编辑路径：手编契约——scope/plan_id 出生定死，raw 永不变（忽略传入新原话）
        val existingScope = existing["scope"]?.jsonPrimitive?.contentOrNull
        val existingPlanId = existing["plan_id"]?.jsonPrimitive?.contentOrNull
        if (existingScope != cmd.scope || existingPlanId != cmd.planId) {
            throw ActionException(
                "背景 ${cmd.id} 已存在，scope/plan_id 不可变（现 scope=$existingScope）",
                ActionErrorCode.INVALID_REQUEST,
                hint = "改挂=删旧增新，走 merge_backgrounds 批命令",
            )
        }
        if (cmd.scope == Background.SCOPE_GLOBAL && actor == CommandActor.AI) {
            requireGlobalBudget(cmd.op, replace = Pair(cmd.id!!, cmd.content))
        }
        val patch = buildJsonObject {
            put("content", cmd.content)
            if (cmd.tags != null) put("tags", encStrList(cmd.tags))
            if (cmd.applicableDates != null) {
                put(
                    "applicable_dates",
                    cmd.applicableDates?.let { encStrList(it) } ?: JsonNull,
                )
            }
        }
        val ok = repo.patchBackground(cmd.id!!, patch, cmd.expectedVersion)
        if (!ok) throw backgroundConflict(cmd.op, cmd.id!!)
        val fresh = repo.backgroundById(cmd.id!!)!!
        var note = "已更新背景"
        val rawIn = cmd.rawSourceText
        val existingRaw = existing["raw_source_text"]?.jsonPrimitive?.contentOrNull
        if (rawIn != null && rawIn.trim().isNotEmpty() && rawIn != existingRaw) {
            note += "；raw_source_text 永不变，传入的新原话已忽略"
        }
        note = backgroundNote(note, mcpStringList(fresh["applicable_dates"]))
        if (existingScope == Background.SCOPE_GLOBAL && actor == CommandActor.HUMAN) {
            note = appendBudgetWarning(note)
        }
        return CommandResult(
            op = cmd.op,
            targetId = fresh["id"]?.jsonPrimitive?.contentOrNull,
            snapshot = fresh,
            note = note,
        )
    }

    // ---- merge_backgrounds（原子批处理：模拟终态→终态校验→落库，背景草案 §2）---------

    suspend fun mergeBackgrounds(cmd: ScheduleCommand.MergeBackgrounds, actor: CommandActor): CommandResult {
        for (o in cmd.ops) {
            if (o.kind != "upsert") continue
            if (o.content?.trim().isNullOrEmpty()) {
                throw ActionException(
                    "merge_backgrounds upsert 需要非空 content（归并后的归纳描述）",
                    ActionErrorCode.INVALID_REQUEST,
                )
            }
            if (o.id == null && o.scope == null) {
                throw ActionException(
                    "merge_backgrounds 建档项（无 id）需要 scope",
                    ActionErrorCode.INVALID_REQUEST,
                    hint = "global=常驻画像 / plan=行程背景（须带 plan_id）",
                )
            }
            validateApplicableDates(cmd.op, o.applicableDates)
        }

        val now = System.currentTimeMillis()
        val source = backgroundSource(actor)
        val createdIds = mutableListOf<String>()
        val updatedIds = mutableListOf<String>()
        var deletedCount = 0

        repo.transaction { txn ->
            val all = LinkedHashMap<String, Background>()
            for (r in txn.query("backgrounds")) {
                val b = Background.fromMap(rowToJson(r))
                all[b.id!!] = b
            }
            val currentGlobals = all.values.filter { it.scope == Background.SCOPE_GLOBAL }

            // 同一事务快照上模拟终态（不落库）
            val sim = LinkedHashMap(all)
            val toDelete = mutableListOf<String>()
            val toInsert = mutableListOf<Background>()
            val toUpdate = mutableListOf<Background>()
            for (o in cmd.ops) {
                when (o.kind) {
                    "delete" -> {
                        val hit = sim.remove(o.id)
                        if (hit == null) {
                            throw ActionException(
                                "背景不存在：${o.id}（整批拒绝，未落库）",
                                ActionErrorCode.NOT_FOUND,
                                hint = "以拒绝体 data 携带的当前现场为准重发 ops",
                            )
                        }
                        toDelete.add(o.id!!)
                    }
                    "upsert" -> {
                        val existing = o.id?.let { sim[it] }
                        if (existing != null) {
                            if ((o.scope != null && o.scope != existing.scope) ||
                                (o.planId != null && o.planId != existing.planId)
                            ) {
                                throw ActionException(
                                    "背景 ${existing.id} 的 scope/plan_id 不可变（编辑载荷与现值不符）",
                                    ActionErrorCode.INVALID_REQUEST,
                                    hint = "改挂=删旧增新（拆成 delete + 建档 upsert）",
                                )
                            }
                            val updated = existing.copy(
                                content = o.content!!,
                                tags = o.tags ?: existing.tags,
                                applicableDates = o.applicableDates ?: existing.applicableDates,
                            )
                            sim[updated.id!!] = updated
                            toUpdate.add(updated)
                        } else {
                            val scope = o.scope ?: throw ActionException(
                                "背景 ${o.id} 不存在，按建档处理需要 scope",
                                ActionErrorCode.INVALID_REQUEST,
                                hint = "global=常驻画像 / plan=行程背景（须带 plan_id）",
                            )
                            if (scope != Background.SCOPE_GLOBAL && scope != Background.SCOPE_PLAN) {
                                throw ActionException(
                                    "scope 非法：$scope（global/plan）",
                                    ActionErrorCode.INVALID_REQUEST,
                                )
                            }
                            if (scope == Background.SCOPE_PLAN) {
                                if (o.planId == null) {
                                    throw ActionException(
                                        "scope=plan 需要 plan_id", ActionErrorCode.INVALID_REQUEST,
                                    )
                                }
                                val planHit = txn.query(
                                    "plans", columns = listOf("id"),
                                    where = "id = ?", args = listOf(o.planId), limit = 1,
                                )
                                if (planHit.isEmpty()) {
                                    throw ActionException(
                                        "计划不存在：${o.planId}",
                                        ActionErrorCode.NOT_FOUND,
                                        hint = "先 list_plans 确认 id",
                                    )
                                }
                            }
                            val id = o.id ?: repo.newId()
                            val b = Background(
                                id = id,
                                scope = scope,
                                planId = o.planId,
                                content = o.content!!,
                                rawSourceText = o.rawSourceText,
                                tags = o.tags ?: emptyList(),
                                applicableDates = o.applicableDates,
                                source = source,
                                capturedBy = Background.CAPTURED_BY_ME,
                                createdAt = now,
                                updatedAt = now,
                                version = 0,
                            )
                            sim[id] = b
                            toInsert.add(b)
                            createdIds.add(id)
                        }
                    }
                    else -> throw ActionException(
                        "未知 op kind：${o.kind}（delete/upsert）",
                        ActionErrorCode.INVALID_REQUEST,
                    )
                }
            }

            // 终态校验（§2 统一式）：仅拦 ai actor；不过=异常令事务整体回滚
            val finalGlobals = sim.values.filter { it.scope == Background.SCOPE_GLOBAL }
            val count = finalGlobals.size
            val chars = finalGlobals.sumOf { it.content.length }
            if (actor == CommandActor.AI &&
                (count > ScheduleRules.GLOBAL_BACKGROUNDS_MAX ||
                    chars > ScheduleRules.GLOBAL_BACKGROUNDS_CHARS_MAX)
            ) {
                throw ActionException(
                    "归并后终态仍超预算：$count 条（上限 ${ScheduleRules.GLOBAL_BACKGROUNDS_MAX}）、" +
                        "$chars 字（上限 ${ScheduleRules.GLOBAL_BACKGROUNDS_CHARS_MAX}）",
                    ActionErrorCode.BUDGET_EXCEEDED,
                    hint = "把更多旧条目并进来或再删几条冗余项，使终态回到预算内后重试",
                    data = buildJsonObject {
                        put("limit", buildJsonObject {
                            put("entries", ScheduleRules.GLOBAL_BACKGROUNDS_MAX)
                            put("chars", ScheduleRules.GLOBAL_BACKGROUNDS_CHARS_MAX)
                        })
                        put("final_state", buildJsonObject {
                            put("entries", count)
                            put("chars", chars)
                        })
                        put("current", JsonArray(currentGlobals.map { it.toMcpJson() }))
                    },
                )
            }

            // 落库（同事务）：先删后增，避免同 id 先插后删的死键序
            for (id in toDelete) txn.delete("backgrounds", where = "id = ?", args = listOf(id))
            deletedCount = toDelete.size
            for (b in toUpdate) {
                txn.update(
                    "backgrounds",
                    buildJsonObject {
                        put("content", b.content)
                        put("tags", encStrList(b.tags))
                        put(
                            "applicable_dates",
                            b.applicableDates?.let { encStrList(it) } ?: JsonNull,
                        )
                        put("updated_at", now)
                        put("version", b.version + 1)
                    },
                    where = "id = ?", args = listOf(b.id!!),
                )
                updatedIds.add(b.id!!)
            }
            for (b in toInsert) txn.insert("backgrounds", b.toMap())
        }

        var note = "已原子归并：删 $deletedCount / 改 ${updatedIds.size} / 增 ${createdIds.size}"
        if (actor == CommandActor.HUMAN) note = appendBudgetWarning(note)
        return CommandResult(
            op = cmd.op,
            note = note,
            data = buildJsonObject {
                put("deleted_count", deletedCount)
                put("updated", JsonArray(updatedIds.map { JsonPrimitive(it) }))
                put("created", JsonArray(createdIds.map { JsonPrimitive(it) }))
            },
        )
    }

    // ---- 背景族共享辅助（Dart _requireGlobalBudget/_validateApplicableDates 等价）----

    private fun backgroundSource(actor: CommandActor): String =
        if (actor == CommandActor.AI) Background.SOURCE_AI_DERIVED else Background.SOURCE_USER

    /** applicable_dates 逐元素双检（写入口校验单点）：畸形=拒绝而非静默丢。 */
    private fun validateApplicableDates(op: String, dates: List<String>?) {
        if (dates == null) return
        for (d in dates) {
            if (!tryIso(d)) {
                throw ActionException(
                    "$op applicable_dates 含非法日期「$d」",
                    ActionErrorCode.INVALID_REQUEST,
                    hint = "单日 ISO 日期字符串数组（如 [\"2026-10-08\",\"2026-10-09\"]），一律日历日；" +
                        "瞬态窗本就该短，超过 ${ScheduleRules.BACKGROUND_DATE_WINDOW_ADVISE_DAYS} 天请改为长期背景（不带日期窗）",
                )
            }
        }
    }

    /** 长窗劝改（§2）：日期窗超过阈值提示改长期——劝改非拦截。 */
    private fun backgroundNote(prefix: String, dates: List<String>?): String {
        if (dates != null && dates.size > ScheduleRules.BACKGROUND_DATE_WINDOW_ADVISE_DAYS) {
            return "$prefix；日期窗 ${dates.size} 天偏长，建议改为长期背景（去掉日期窗）"
        }
        return prefix
    }

    /** scope=global 注入预算终态校验：落库前评估最终状态（additions=新建、replace=编辑改写）。 */
    private suspend fun requireGlobalBudget(
        op: String,
        additions: List<Background> = emptyList(),
        replace: Pair<String, String>? = null,
    ) {
        val globals = globalBackgroundRows()
        var count = globals.size
        var chars = globals.sumOf { (it["content"] as? JsonPrimitive)?.contentOrNull?.length ?: 0 }
        if (replace != null) {
            val (id, content) = replace
            for (b in globals) {
                if (b["id"]?.jsonPrimitive?.contentOrNull == id) {
                    chars += content.length -
                        ((b["content"] as? JsonPrimitive)?.contentOrNull?.length ?: 0)
                    break
                }
            }
        }
        count += additions.size
        chars += additions.sumOf { it.content.length }
        if (count <= ScheduleRules.GLOBAL_BACKGROUNDS_MAX &&
            chars <= ScheduleRules.GLOBAL_BACKGROUNDS_CHARS_MAX
        ) {
            return
        }
        throw ActionException(
            "全局背景注入预算超限：终态 $count 条（上限 ${ScheduleRules.GLOBAL_BACKGROUNDS_MAX}）、" +
                "$chars 字（上限 ${ScheduleRules.GLOBAL_BACKGROUNDS_CHARS_MAX}）",
            ActionErrorCode.BUDGET_EXCEEDED,
            hint = "发起归并询问后重试：给出具体归并方案（哪几条并成哪条），经用户确认后用 " +
                "merge_backgrounds 执行；或建议用户在 app 内手动处理（human 通道不受预算拦截）",
            data = buildJsonObject {
                put("limit", buildJsonObject {
                    put("entries", ScheduleRules.GLOBAL_BACKGROUNDS_MAX)
                    put("chars", ScheduleRules.GLOBAL_BACKGROUNDS_CHARS_MAX)
                })
                put("final_state", buildJsonObject {
                    put("entries", count)
                    put("chars", chars)
                })
                put("current", JsonArray(globals))
            },
        )
    }

    /** human 通道温和提示（§2：人可以任性，AI 必须讲理——写后超刻度不拦、只提示）。 */
    private suspend fun appendBudgetWarning(note: String): String {
        val globals = globalBackgroundRows()
        val chars = globals.sumOf { (it["content"] as? JsonPrimitive)?.contentOrNull?.length ?: 0 }
        if (globals.size <= ScheduleRules.GLOBAL_BACKGROUNDS_MAX &&
            chars <= ScheduleRules.GLOBAL_BACKGROUNDS_CHARS_MAX
        ) {
            return note
        }
        return "$note；全局背景 ${globals.size} 条/$chars 字已超预算刻度" +
            "（${ScheduleRules.GLOBAL_BACKGROUNDS_MAX} 条/${ScheduleRules.GLOBAL_BACKGROUNDS_CHARS_MAX} 字），" +
            "建议在方便时归并"
    }

    private suspend fun backgroundConflict(op: String, id: String): ActionException {
        val b = repo.backgroundById(id)
        return ActionException(
            "版本冲突：$op 已被其他端修改（expected_version 不匹配）",
            ActionErrorCode.VERSION_CONFLICT,
            hint = "直接在返回的 latest 快照上合并你的修改重试，不要重拉",
            data = b?.let {
                buildJsonObject {
                    put("code", "version_conflict")
                    put("latest", it)
                }
            },
        )
    }

    private suspend fun globalBackgroundRows(): List<JsonObject> =
        ((repo.listBackgrounds() as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList())
            .filter { (it["scope"] as? JsonPrimitive)?.contentOrNull == Background.SCOPE_GLOBAL }

    /** MCP JSON 的字符串数组取值（tags/applicable_dates——toMcpJson 数组形）。 */
    private fun mcpStringList(v: kotlinx.serialization.json.JsonElement?): List<String>? {
        if (v == null || v is JsonNull) return null
        return (v as? JsonArray)?.mapNotNull { e -> (e as? JsonPrimitive)?.takeIf { it.isString }?.content }
    }

    /** JSON 串化单点（Dart jsonEncode(List<String>) 同构——DB TEXT 列与 patch 载荷共用）。 */
    private fun encStrList(list: List<String>): JsonPrimitive =
        JsonPrimitive(JsonArray(list.map { JsonPrimitive(it) }).toString())

    // ---- upsert_facts（raw 占位出生 + 编辑追加只增 + 出生确认）-----------------------

    suspend fun upsertFacts(cmd: ScheduleCommand.UpsertFacts, actor: CommandActor): CommandResult {
        validateFactsEnums(cmd)
        if (cmd.planId != null) requirePlan(cmd.planId)
        val notes = mutableListOf<String>()
        val badge = clipBadge(cmd.badge, notes)
        val heroMetrics = clipHeroMetrics(cmd.heroMetrics, notes)

        // ---- 建档（分享原文入册 raw 一字不解析 / AI 对话投喂直接结构化）----
        if (cmd.id == null) {
            if (cmd.title == null) {
                throw ActionException("upsert_facts 建档需要 title", ActionErrorCode.INVALID_REQUEST)
            }
            if (cmd.origin == null) {
                throw ActionException(
                    "upsert_facts 建档需要 origin（shared/quicknote/ai/manual）",
                    ActionErrorCode.INVALID_REQUEST,
                )
            }
            if (cmd.category == null || cmd.sourceKind == null) {
                throw ActionException(
                    "upsert_facts 建档需要 category/source_kind（编辑路径缺省=不变）",
                    ActionErrorCode.INVALID_REQUEST,
                )
            }
            // 出生确认（§1.2）：raw 建档零解析定不了正确类别——猜即第二真相，
            // 命令层强制 verbal/verbal 占位；终值由首次 structured 回填定。
            if ((cmd.state ?: Artifact.STATE_RAW) == Artifact.STATE_RAW &&
                (cmd.category != Artifact.CATEGORY_VERBAL || cmd.sourceKind != Artifact.SOURCE_VERBAL)
            ) {
                throw ActionException(
                    "raw 建档（state 缺省或=raw）只接受 category=verbal + source_kind=verbal；" +
                        "AI 对话投喂结构化凭证请显式传 state=\"structured\" 并给出正确的 category/source_kind",
                    ActionErrorCode.INVALID_REQUEST,
                )
            }
            val payload = Artifact.payloadWithRefs(
                buildJsonObject {
                    heroMetrics?.let { put("hero_metrics", JsonArray(it)) }
                    cmd.timeAnchors?.let { put("time_anchors", JsonArray(it)) }
                    cmd.constraints?.let { put("constraints", it) }
                    cmd.ruleScope?.let { put("rule_scope", it) }
                    cmd.location?.let { put("location", it) }
                    cmd.copyableCode?.let { put("copyable_code", it) }
                    cmd.contactPhone?.let { put("contact_phone", it) }
                    cmd.rawText?.let { put("raw_text", it) }
                },
                if (cmd.planId != null) PlanIdArg.Set(cmd.planId) else PlanIdArg.Remove,
            )
            validateRuleScope(payload) // §9.2 Q-A：kind=rule 必须显式给出生效窗
            val art = Artifact(
                id = null,
                category = cmd.category,
                sourceKind = cmd.sourceKind,
                state = cmd.state ?: Artifact.STATE_RAW,
                origin = cmd.origin,
                title = cmd.title,
                badge = badge,
                payload = payload,
                attachments = emptyList(),
                capturedBy = Artifact.CAPTURED_BY_ME,
                planId = cmd.planId,
                createdAt = null,
                updatedAt = null,
                version = 0,
            )
            // 升格流（fact §9.5-6 v5 反转）：block_id 走块侧单点写——凭证插行与
            // 块回填同事务原子，凭证侧零绑定；块已日切删除=绑定静默跳过
            val created = repo.addArtifact(art.toMap(), cmd.blockId)
            val baseNote = if (created["state"]?.jsonPrimitive?.contentOrNull == Artifact.STATE_RAW) {
                "已记下原文（待 AI 提炼）"
            } else {
                "已记录凭证"
            }
            return CommandResult(
                op = cmd.op,
                targetId = created["id"]?.jsonPrimitive?.contentOrNull,
                snapshot = created,
                note = (listOf(baseNote) + notes).joinToString("；"),
            )
        }

        // ---- 编辑（提炼回填 / 改挂 / 作废）：字段级 merge，只覆盖传入槽位 ----
        val existing = repo.artifactById(cmd.id)
            ?: throw ActionException("凭证不存在: ${cmd.id}", ActionErrorCode.NOT_FOUND)
        val existingVersion = existing["version"]?.jsonPrimitive?.longOrNull ?: 0L
        if (cmd.expectedVersion != null && cmd.expectedVersion != existingVersion) {
            throw ActionException(
                "版本冲突：凭证已被他人更新",
                ActionErrorCode.VERSION_CONFLICT,
                data = buildJsonObject {
                    put("code", "version_conflict")
                    put("latest", existing)
                },
            )
        }
        val existingPayload = (existing["payload"] as? JsonObject) ?: JsonObject(emptyMap())
        val existingPlanId = (existing["plan_id"] as? JsonPrimitive)?.contentOrNull

        // raw_text 追加双段只增不清（改签 SOP：改签前后两段短信都要留）
        var rawText = (existingPayload["raw_text"] as? JsonPrimitive)?.contentOrNull
        if (cmd.rawText != null && cmd.rawText != rawText) {
            val appended = rawText != null && rawText.isNotEmpty()
            rawText = if (appended) "$rawText\n${cmd.rawText}" else cmd.rawText
            if (appended) notes.add("原文已追加存档")
        }

        val mergedPayload = Artifact.payloadWithRefs(
            buildJsonObject {
                for ((k, v) in existingPayload) put(k, v)
                heroMetrics?.let { put("hero_metrics", JsonArray(it)) }
                cmd.timeAnchors?.let { put("time_anchors", JsonArray(it)) }
                cmd.constraints?.let { put("constraints", it) }
                cmd.ruleScope?.let { put("rule_scope", it) }
                cmd.location?.let { put("location", it) }
                cmd.copyableCode?.let { put("copyable_code", it) }
                cmd.contactPhone?.let { put("contact_phone", it) }
                rawText?.let { put("raw_text", it) }
            },
            run {
                val mergedPlanId = cmd.planId ?: existingPlanId
                if (mergedPlanId != null) PlanIdArg.Set(mergedPlanId) else PlanIdArg.Remove
            },
        )
        validateRuleScope(mergedPayload) // §9.2 Q-A：kind=rule 必须显式给出生效窗
        // 出生确认（§1.2 唯一例外）：raw 占位行首次转 structured 时允许定
        // category/source_kind 终值（单事务同写列）；structured 后二值恒不可变。
        val birth = (existing["state"]?.jsonPrimitive?.contentOrNull == Artifact.STATE_RAW) &&
            (cmd.state == Artifact.STATE_STRUCTURED)
        // 出生确认严进（§9.5-7）：raw→structured 必须显式给出 category/source_kind——
        // 缺失即拒，严禁静默沿用 verbal/verbal 占位。防漏填不防填错。
        if (birth && (cmd.category == null || cmd.sourceKind == null)) {
            throw ActionException(
                "raw 提炼回填（state=structured）需要显式给出 category 与 source_kind（出生确认，§9.5-7）",
                ActionErrorCode.INVALID_REQUEST,
                hint = "category: transit/ticket/hotel/venue/verbal；" +
                    "source_kind: booking/announcement/verbal；拿不准请先按最贴切值填写并在回显中请用户确认——缺失不再静默沿用 verbal 占位",
            )
        }
        val now = System.currentTimeMillis()
        val bindBlock = cmd.blockId != null
        val demote = cmd.state == Artifact.STATE_VOIDED &&
            (existing["state"]?.jsonPrimitive?.contentOrNull != Artifact.STATE_VOIDED)
        repo.transaction { txn ->
            txn.update(
                "artifacts",
                buildJsonObject {
                    cmd.title?.let { put("title", it) }
                    badge?.let { put("badge", it) }
                    cmd.state?.let { put("state", it) }
                    if (birth) put("category", cmd.category ?: (existing["category"]?.jsonPrimitive?.contentOrNull ?: Artifact.CATEGORY_VERBAL))
                    if (birth) put("source_kind", cmd.sourceKind ?: (existing["source_kind"]?.jsonPrimitive?.contentOrNull ?: Artifact.SOURCE_VERBAL))
                    put("payload", Artifact.encodePayload(mergedPayload))
                    cmd.planId?.let { put("plan_id", it) }
                    put("updated_at", now)
                    put("version", existingVersion + 1)
                },
                "id = ? AND version = ?",
                listOf(cmd.id, existingVersion),
            )
            if (bindBlock) {
                // 编辑路径升格回填（fact §9.5-6）：block_id 入参走块侧单点写（同事务，幂等）
                txn.rawUpdate(
                    "UPDATE schedule_blocks SET artifact_id = ?, version = version + 1, updated_at = ? WHERE id = ?",
                    listOf(cmd.id, now, cmd.blockId),
                )
            }
            if (demote) {
                // 反转关联：作废后**所有引用块**置空挂载并降格 pinned（1:N 软引用，绝不删块）
                txn.rawUpdate(
                    "UPDATE schedule_blocks SET pinned = 0, artifact_id = NULL, version = version + 1, updated_at = ? WHERE artifact_id = ?",
                    listOf(now, cmd.id),
                )
            }
        }
        val fresh = repo.artifactById(cmd.id)!!
        val editNote = when {
            cmd.state == Artifact.STATE_VOIDED -> "已标记作废（存证保留，关联块已解除钉定）"
            cmd.planId != null && cmd.planId != existingPlanId -> "已改挂凭证"
            else -> "已更新凭证"
        }
        return CommandResult(
            op = cmd.op,
            targetId = fresh["id"]?.jsonPrimitive?.contentOrNull,
            snapshot = fresh,
            note = (listOf(editNote) + notes).joinToString("；"),
        )
    }

    // ---- 凭证写语义件（枚举严进/截断优于拒绝/rule 生效窗）--------------------------

    /** 枚举严进（写入口拒畸形；读侧容错防御是另一道，见 Artifact.decodePayload）。 */
    private fun validateFactsEnums(cmd: ScheduleCommand.UpsertFacts) {
        val categories = setOf(
            Artifact.CATEGORY_TRANSIT, Artifact.CATEGORY_TICKET, Artifact.CATEGORY_HOTEL,
            Artifact.CATEGORY_VENUE, Artifact.CATEGORY_VERBAL,
        )
        if (cmd.category != null && cmd.category !in categories) {
            throw ActionException(
                "upsert_facts category 非法：${cmd.category}",
                ActionErrorCode.INVALID_REQUEST,
                hint = "transit/ticket/hotel/venue/verbal",
            )
        }
        val sourceKinds = setOf(
            Artifact.SOURCE_BOOKING, Artifact.SOURCE_ANNOUNCEMENT, Artifact.SOURCE_VERBAL,
        )
        if (cmd.sourceKind != null && cmd.sourceKind !in sourceKinds) {
            throw ActionException(
                "upsert_facts source_kind 非法：${cmd.sourceKind}",
                ActionErrorCode.INVALID_REQUEST,
                hint = "booking/announcement/verbal",
            )
        }
        val states = setOf(Artifact.STATE_RAW, Artifact.STATE_STRUCTURED, Artifact.STATE_VOIDED)
        if (cmd.state != null && cmd.state !in states) {
            throw ActionException(
                "upsert_facts state 非法：${cmd.state}",
                ActionErrorCode.INVALID_REQUEST,
                hint = "raw/structured/voided",
            )
        }
        val origins = setOf(
            Artifact.ORIGIN_SHARED, Artifact.ORIGIN_QUICKNOTE, Artifact.ORIGIN_AI, Artifact.ORIGIN_MANUAL,
        )
        if (cmd.origin != null && cmd.origin !in origins) {
            throw ActionException(
                "upsert_facts origin 非法：${cmd.origin}",
                ActionErrorCode.INVALID_REQUEST,
                hint = "shared/quicknote/ai/manual",
            )
        }
        validateTimeAnchors(cmd.op, cmd.timeAnchors)
    }

    /** time_anchors 写入口严进：kind ∈ moment/span/rule；date/end_date 合法日历日（§1.3）。 */
    private fun validateTimeAnchors(op: String, anchors: List<JsonObject>?) {
        if (anchors == null) return
        anchors.forEachIndexed { i, a ->
            val kind = (a["kind"] as? JsonPrimitive)?.contentOrNull
            if (kind == null || kind !in setOf("moment", "span", "rule")) {
                throw ActionException(
                    "$op time_anchors[$i] kind 非法：$kind（moment/span/rule）",
                    ActionErrorCode.INVALID_REQUEST,
                )
            }
            for (key in listOf("date", "end_date")) {
                val v = (a[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                if (v != null && ScheduleDay.tryParseIsoDate(v) == null) {
                    throw ActionException(
                        "$op time_anchors[$i].$key 非法日期：$v（须为 YYYY-MM-DD 合法日历日）",
                        ActionErrorCode.INVALID_REQUEST,
                    )
                }
            }
        }
    }

    /** 截断优于拒绝（§1.2）：badge 超 12 字符截断并记 note。 */
    private fun clipBadge(badge: String?, notes: MutableList<String>): String? {
        if (badge == null) return null
        if (badge.length <= ArtifactRules.BADGE_MAX_CHARS) return badge
        notes.add("badge 超 ${ArtifactRules.BADGE_MAX_CHARS} 字符已截断")
        return badge.substring(0, ArtifactRules.BADGE_MAX_CHARS)
    }

    /** hero_metrics 超 3 项截前 3 落库（截断优于拒绝，不阻断不抛错）。 */
    private fun clipHeroMetrics(raw: List<JsonObject>?, notes: MutableList<String>): List<JsonObject>? {
        if (raw == null) return null
        if (raw.size <= ArtifactRules.HERO_METRICS_MAX) return raw
        notes.add("hero_metrics 超 ${ArtifactRules.HERO_METRICS_MAX} 项已截前 ${ArtifactRules.HERO_METRICS_MAX}")
        return raw.subList(0, ArtifactRules.HERO_METRICS_MAX)
    }

    /** §9.2 Q-A：kind=rule 的凭证建档必须显式给出生效窗，漏填即拒（杜绝永久生效误杀红线）。 */
    private fun validateRuleScope(payload: JsonObject) {
        val anchors = payload["time_anchors"] as? JsonArray ?: return
        val hasRule = anchors.any { e ->
            (e as? JsonObject)?.get("kind")?.jsonPrimitive?.contentOrNull == "rule"
        }
        if (!hasRule) return
        if (!FactRules.ruleScopeComplete(payload["rule_scope"] as? JsonObject)) {
            throw ActionException(
                "kind=rule 的凭证建档必须显式给出 rule_scope.valid_from 与 valid_to（§9.2 Q-A）",
                ActionErrorCode.INVALID_REQUEST,
                hint = "单日规则两者相等；plans 无日期字段，缺省继承行程窗已作废，缺失即拒避免红线永久生效误杀",
            )
        }
    }

    // ---- swap_block（换乘——候选池=light/anywhere 未排期计划，时长相近优先）-----------

    suspend fun swapBlock(cmd: ScheduleCommand.SwapBlock): CommandResult {
        val b = requireBlock(cmd.id)
        val pinned = (b["pinned"] as? JsonPrimitive)?.booleanOrNull ?: false
        if (pinned) {
            throw ActionException("pinned 块不可换乘（硬行程不是疲劳问题）", ActionErrorCode.FORBIDDEN)
        }
        val status = (b["status"] as? JsonPrimitive)?.contentOrNull ?: ""
        if (status in setOf("done", "archived", "melted")) {
            throw ActionException("终态块不可换乘（当前 $status）", ActionErrorCode.INVALID_REQUEST)
        }
        val candidates = swapCandidatesFor(b)
        if (cmd.targetPlanId != null) {
            val picked = candidates.filter { it.id == cmd.targetPlanId }
            if (picked.isEmpty()) {
                throw ActionException(
                    "指定候选不在可换乘池中（须为 light/anywhere 且未被排期）",
                    ActionErrorCode.INVALID_REQUEST,
                )
            }
            return applySwap(b, picked.first())
        }
        if (candidates.isEmpty()) {
            throw ActionException(
                "愿望池没有可换乘的轻松事（light/anywhere 且未被排期）",
                ActionErrorCode.INVALID_REQUEST,
                hint = "先往清单里存几件 5 分钟就能做的琐事，疲惫时才有得换",
            )
        }
        return applySwap(b, candidates.first())
    }

    private fun jsonToPlans(raw: kotlinx.serialization.json.JsonElement): List<app.shiguang.core.model.Plan> =
        (raw as? JsonArray)?.filterIsInstance<JsonObject>()?.map { app.shiguang.core.model.Plan.fromMap(it) }
            ?: emptyList()

    /** 换乘候选（Dart swapCandidatesFor 逐字）：待安排池中 light/anywhere 的计划，时长相近优先。 */
    private suspend fun swapCandidatesFor(b: JsonObject): List<app.shiguang.core.model.Plan> {
        val block = ScheduleBlock.fromMap(b)
        val span = ScheduleDay.spanMinutes(block.startMin.toInt(), block.endMin.toInt())
        val anchor = ScheduleDay.tryParseIsoDate(block.date)!!
        val busyPlans = jsonToBlocks(
            repo.blocksInRange(
                ScheduleDay.isoDate(anchor.minusDays(365)),
                ScheduleDay.isoDate(anchor.plusDays(30)),
            ),
        ).mapNotNull { it.planId }.toSet()
        return jsonToPlans(repo.listPlans())
            .filter { it.id != null && it.id !in busyPlans }
            .filter { it.energyLevel == app.shiguang.core.model.Plan.ENERGY_LIGHT }
            .filter { it.toolRequired == app.shiguang.core.model.Plan.TOOL_ANYWHERE }
            .sortedBy { it.estimate?.let { e -> kotlin.math.abs(e - span) } ?: Long.MAX_VALUE }
    }

    private suspend fun applySwap(b: JsonObject, candidate: app.shiguang.core.model.Plan): CommandResult {
        val id = (b["id"] as? JsonPrimitive)?.contentOrNull!!
        repo.deleteBlock(id)
        val nb = repo.addBlock(
            buildJsonObject {
                put("date", (b["date"] as? JsonPrimitive)?.contentOrNull!!)
                put("start_min", (b["start_min"] as? JsonPrimitive)?.longOrNull!!)
                put("end_min", (b["end_min"] as? JsonPrimitive)?.longOrNull!!)
                candidate.id?.let { put("plan_id", it) }
                (b["source"] as? JsonPrimitive)?.contentOrNull?.let { put("source", it) }
                (b["status"] as? JsonPrimitive)?.contentOrNull?.let { put("status", it) }
            },
        )
        return CommandResult(
            op = "swap_block",
            targetId = nb["id"]?.jsonPrimitive?.contentOrNull,
            snapshot = nb,
            data = buildJsonObject {
                put("swapped_out", id)
                put("swapped_in", candidate.id!!)
            },
            note = "已换乘「${candidate.title}」，原任务已放回清单待安排",
        )
    }

    // ---- reflow_day / panic_clear（水流重算与熔断——双轨期桥回 Dart）----------------

    // ---- reflow_day（水流模型确定性重算，human 专属；N5.5 原生化——D18 清单转正）----

    suspend fun reflowDay(cmd: ScheduleCommand.ReflowDay): CommandResult {
        val raw = repo.settingsAll().toStringMap()
        if (!SettingsKeys.initialized(raw)) {
            throw ActionException(
                "settings 未初始化：缺少作息边界（wake_time/sleep_time）",
                ActionErrorCode.SETTINGS_MISSING,
            )
        }
        val wake = SettingsKeys.intOf(raw, SettingsKeys.WAKE_TIME)!!
        val sleep = SettingsKeys.intOf(raw, SettingsKeys.SLEEP_TIME)!!
        val sleepAdj = if (sleep <= wake) sleep + 1440 else sleep
        val energy = raw["today_energy"] ?: "normal"
        if (energy != "low") {
            return CommandResult(op = cmd.op, note = "当前电量非低档，无需降档重算")
        }
        val date = cmd.date ?: ScheduleDay.isoDate(ScheduleDay.scheduleDayOf(java.time.LocalDateTime.now(), wake))
        val parsedDate = ScheduleDay.tryParseIsoDate(date)!!
        // 例外日（漫游）：fixed 全空（无固定占用墙；Dart suspended 分支镜像）
        val suspended = SettingsKeys.coversException(raw, date)
        val fixedOnDate = if (suspended) emptyList() else slotsOfJson(repo.fixedSlotsForDate(date)["on_date"])
        val fixedSpillover = if (suspended) emptyList() else slotsOfJson(repo.fixedSlotsForDate(date)["spillover"])
        val blocks = jsonToBlocks(repo.blocksOnDate(date))
        val occupied = buildList {
            for (b in blocks) {
                if (b.pinned || b.source == ScheduleBlock.SOURCE_HUMAN) {
                    val s = b.startMin.toInt()
                    add(Pair(s, s + ScheduleDay.spanMinutes(s, b.endMin.toInt())))
                }
            }
            for (s in fixedOnDate) {
                add(Pair(s.startMin, if (ScheduleDay.crossesMidnight(s.startMin, s.endMin)) 1440 else s.endMin))
            }
            for (s in fixedSpillover) add(Pair(0, s.endMin))
        }
        val available = ScheduleDay.freeMinutesIn(wake, sleepAdj, occupied)
        val budget = available * FILL_RATE_LOW_ENERGY_PERCENT / 100

        // 可降档 AI 块（非 pinned、非庆祝、活跃态）；牺牲顺序=开始时间靠后者最先
        val plans = HashMap<String, JsonObject?>()
        val movable = blocks.filter {
            it.source == ScheduleBlock.SOURCE_AI && !it.pinned && !it.isCelebration &&
                (it.status == ScheduleBlock.STATUS_PROPOSED || it.status == ScheduleBlock.STATUS_CONFIRMED)
        }.sortedByDescending { it.startMin }
        for (b in movable) {
            b.planId?.takeIf { it !in plans }?.let { plans[it] = repo.planById(it) }
        }

        var sparked = 0
        var meltedCount = 0
        val sparkedIds = mutableListOf<String>()
        val meltedIds = mutableListOf<String>()
        val movableArr = movable.toMutableList()
        fun totalOf(list: List<ScheduleBlock>): Int =
            list.sumOf { ScheduleDay.spanMinutes(it.startMin.toInt(), it.endMin.toInt()) }

        for (i in movableArr.indices) {
            val alive = movableArr.filter { it.status != "melted" }
            if (totalOf(alive) <= budget) break
            val b = movableArr[i]
            val plan = b.planId?.let { plans[it] }
            val minViable = plan?.get("min_viable_action")?.let { (it as? JsonPrimitive)?.contentOrNull }
            val span = ScheduleDay.spanMinutes(b.startMin.toInt(), b.endMin.toInt())
            if (span > SPARK_MAX_MINUTES && !minViable.isNullOrEmpty()) {
                // 二级阻尼：压为火种（min_viable_action 核心动作，15 分钟刻度）
                val ok = repo.patchBlock(
                    b.id!!,
                    buildJsonObject {
                        put("end_min", normEnd(b.startMin.toInt() + SPARK_MAX_MINUTES))
                        put("execution_quality", ScheduleBlock.QUALITY_SPARK)
                    },
                    null,
                )
                if (ok) {
                    sparked++
                    sparkedIds.add(b.id!!)
                    movableArr[i] = withSpan(b, SPARK_MAX_MINUTES, melted = false)
                }
            } else {
                // 三级阻尼：溢出 melted 蒸发回池（非核心最先；火种靠后兜底）
                val ok = repo.patchBlock(
                    b.id!!,
                    buildJsonObject { put("status", "melted") },
                    null,
                )
                if (ok) {
                    meltedCount++
                    meltedIds.add(b.id!!)
                    movableArr[i] = withSpan(b, 0, melted = true)
                }
            }
        }
        return CommandResult(
            op = cmd.op,
            data = buildJsonObject {
                put("date", date)
                put("budget_minutes", budget)
                put("sparked", JsonArray(sparkedIds.map { JsonPrimitive(it) }))
                put("melted", JsonArray(meltedIds.map { JsonPrimitive(it) }))
            },
            note = "降档重算完成：$sparked 项切 5 分钟启动版，$meltedCount 项暂缓放回清单" +
                "（检测到身体电量低，今日任务自动顺延，不产生任何惩罚）",
        )
    }

    // ---- panic_clear（现实熔断，human 专属批量原子；N5.5 原生化——D18 清单转正）----

    suspend fun panicClear(cmd: ScheduleCommand.PanicClear): CommandResult {
        val raw = repo.settingsAll().toStringMap()
        if (!SettingsKeys.initialized(raw)) {
            throw ActionException(
                "settings 未初始化：缺少作息边界（wake_time/sleep_time）",
                ActionErrorCode.SETTINGS_MISSING,
            )
        }
        val wake = SettingsKeys.intOf(raw, SettingsKeys.WAKE_TIME)!!
        val sleep = SettingsKeys.intOf(raw, SettingsKeys.SLEEP_TIME)!!
        val sleepAdj = if (sleep <= wake) sleep + 1440 else sleep
        val date = ScheduleDay.isoDate(ScheduleDay.scheduleDayOf(java.time.LocalDateTime.now(), wake))
        val nowMin = cmd.nowMin?.toInt() ?: ScheduleDay.minutesOfDay(java.time.LocalDateTime.now())
        val targets = jsonToBlocks(repo.blocksOnDate(date))
            .filter {
                it.source == ScheduleBlock.SOURCE_AI && !it.pinned &&
                    (it.status == ScheduleBlock.STATUS_PROPOSED || it.status == ScheduleBlock.STATUS_CONFIRMED) &&
                    it.startMin > nowMin
            }
            .sortedBy { it.startMin }
        if (targets.isEmpty()) {
            return CommandResult(op = cmd.op, note = "今天剩余没有需要熔断的 AI 安排")
        }
        when (cmd.mode) {
            "clear_remaining" -> {
                val melted = mutableListOf<String>()
                for (b in targets) {
                    val ok = repo.patchBlock(
                        b.id!!,
                        buildJsonObject { put("status", "melted") },
                        b.version,
                    )
                    if (!ok) throw blockConflict(cmd.op, b.id!!)
                    melted.add(b.id!!)
                }
                return CommandResult(
                    op = cmd.op,
                    note = "已把 ${targets.size} 项放回清单待安排（无痕，零心理负债）",
                    data = buildJsonObject { put("melted", JsonArray(melted.map { JsonPrimitive(it) })) },
                )
            }
            "push_2h" -> {
                val fixed = repo.fixedSlotsForDate(date)
                val walls = buildList {
                    for (b in jsonToBlocks(repo.blocksOnDate(date))) {
                        if ((b.source == ScheduleBlock.SOURCE_HUMAN || b.pinned) &&
                            b.status != "melted" &&
                            b.status != "archived"
                        ) {
                            add(Pair(b.startMin.toInt(), b.endMin.toInt()))
                        }
                    }
                    for (s in slotsOfJson(fixed["on_date"])) add(Pair(s.startMin, s.endMin))
                    for (s in slotsOfJson(fixed["spillover"])) add(Pair(kotlin.math.max(0, s.startMin), s.endMin))
                }
                for (b in targets) {
                    val ns = b.startMin.toInt() + 120
                    val ne = b.endMin.toInt() + 120
                    if (ns < wake || ne > sleepAdj) {
                        throw ActionException(
                            "后移 2 小时会越出今日作息边界（${clockOf(ns)}–${clockOf(ne)}），试试「全清空」",
                            ActionErrorCode.INVALID_REQUEST,
                        )
                    }
                    for ((ws, we) in walls) {
                        if (ws < ne && ns < we) {
                            throw ActionException(
                                "后移 2 小时会撞 ${clockOf(ws)}–${clockOf(we)} 的手动安排或固定占用",
                                ActionErrorCode.INVALID_REQUEST,
                                hint = "可先对单项用「改时间」微调",
                            )
                        }
                    }
                }
                val moved = mutableListOf<String>()
                for (b in targets) {
                    val ok = repo.patchBlock(
                        b.id!!,
                        buildJsonObject {
                            put("start_min", b.startMin + 120)
                            put("end_min", b.endMin + 120)
                            put("postpone_count", b.postponeCount + 1)
                        },
                        b.version,
                    )
                    if (!ok) throw blockConflict(cmd.op, b.id!!)
                    moved.add(b.id!!)
                }
                return CommandResult(
                    op = cmd.op,
                    note = "已把 ${targets.size} 项整体后移 2 小时",
                    data = buildJsonObject { put("moved", JsonArray(moved.map { JsonPrimitive(it) })) },
                )
            }
            else -> throw ActionException(
                "未知 mode：${cmd.mode}",
                ActionErrorCode.INVALID_REQUEST,
                hint = "可选 clear_remaining / push_2h",
            )
        }
    }

    // ---- reflow/panic 内部件（N5.5） ──────────────────────────────────────────

    /** 两命令共用的 envelope 常量（Dart rules.dart 10/21 逐值）。 */
    private val FILL_RATE_LOW_ENERGY_PERCENT = 25
    private val SPARK_MAX_MINUTES = 15

    private fun clockOf(min: Int): String = "%02d:%02d".format(min / 60, min % 60)

    private fun normEnd(end: Int): Int = if (end > 1440) end - 1440 else end

    private fun jsonToBlocks(raw: kotlinx.serialization.json.JsonElement): List<ScheduleBlock> =
        (raw as? JsonArray)?.filterIsInstance<JsonObject>()?.map { ScheduleBlock.fromMap(it) } ?: emptyList()

    private fun withSpan(b: ScheduleBlock, span: Int, melted: Boolean): ScheduleBlock = b.copy(
        endMin = b.startMin + span,
        status = if (melted) "melted" else b.status,
        executionQuality = ScheduleBlock.QUALITY_SPARK,
        version = b.version + 1,
    )

    private suspend fun blockConflict(op: String, id: String): ActionException {
        val fresh = repo.blockById(id)
        return ActionException(
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

    private fun JsonObject.toStringMap(): Map<String, String> =
        entries.associate { (k, v) -> k to (v as? JsonPrimitive)?.contentOrNull.orEmpty() }
}
