package app.shiguang.shiguang.ui.schedule

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.shiguang.core.action.ActionException
import app.shiguang.core.action.ActionPass
import app.shiguang.core.action.BridgeRepositoryPort
import app.shiguang.core.action.computeActionPasses
import app.shiguang.core.action.CommandActor
import app.shiguang.core.action.factRuleViolations
import app.shiguang.core.action.CommandHandler
import app.shiguang.core.action.DbWriteQueue
import app.shiguang.core.action.ScheduleCommand
import app.shiguang.core.action.ScheduleQueries
import app.shiguang.core.model.Artifact
import app.shiguang.core.model.Plan
import app.shiguang.core.model.ScheduleBlock
import app.shiguang.core.model.ScheduleDay
import app.shiguang.core.model.SettingsKeys
import app.shiguang.core.model.TimeProvider
import app.shiguang.shiguang.ShiguangApplication
import app.shiguang.shiguang.ui.block.BlockStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 今日页轻 ViewModel（D15 裁决①，N5）：StateFlow 单向流承载层——
 * 读 = collect repo.revision（D16 刷新面）+ selectedIso → 重载 → StateFlow&lt;DayUiState&gt;
 * （§执行定位开刀区「只读 StateFlow+禁反向拉取」；读面走 Dispatchers.IO，写经
 * DbWriteQueue 单线程写队列）；写 = 命令面 CommandHandler（human actor）。
 * 反馈面 = events SharedFlow（SnackBar 四档+浮动），壳层 collect 消费——组件零直调。
 * 快记草稿三字段即写即存（300ms 文本防抖/星死线即改即存/中途收起冲刷尾巴）。
 */
data class DayUiState(
    val data: ScheduleDayData? = null,
    val loading: Boolean = true,
)

/** SnackBar 反馈事件（时长=ShiguangScaffold 四档毫秒；floating=FAB 净空浮动；撤销动作回传壳层）。 */
data class UiEvent(
    val message: String,
    val durationMs: Long = 3000,
    val floating: Boolean = false,
    val error: Boolean = false,
    val actionLabel: String? = null,
    val actionUndoPlanId: String? = null,
    val actionUndoVersion: Long? = null,
    val actionUndoBlockId: String? = null,
    val actionUndoBlockToStatus: String? = null,
    val actionRetroDate: String? = null,
    val actionRetroStartMin: Long? = null,
    val actionRetroEndMin: Long? = null,
    val actionRetroPlanId: String? = null,
    val actionRetroLabel: String? = null,
)

/** 快记草稿（Dart QuickNoteDraft 三字段镜像；quick_note_draft 命令整体覆写）。 */
data class QuickNoteDraft(
    val text: String = "",
    val important: Boolean = false,
    val deadline: LocalDate? = null,
) {
    val isEmpty: Boolean get() = text.trim().isEmpty()
}

/** 放工守卫明日概要（count=0=明日无安排；app_shell_sheets.md §G 转正口径）。 */
data class TomorrowSummary(val count: Int, val firstTime: String?, val firstLabel: String?)

class TodayViewModel(
    /** 仓储读端口（NowNextCard 装配消费；读面走 StateFlow/repo 读函数，K8 白名单族）。 */
    val repo: BridgeRepositoryPort,
    private val time: TimeProvider,
) : ViewModel() {

    private val handler = CommandHandler(repo)

    private val _selectedIso = MutableStateFlow(
        ScheduleDay.isoDate(ScheduleDay.scheduleDayOf(time.now(), DEFAULT_WAKE)),
    )
    val selectedIso: StateFlow<String> = _selectedIso

    private val _now = MutableStateFlow(time.now())
    val now: StateFlow<LocalDateTime> = _now

    private val _uiState = MutableStateFlow(DayUiState())
    val uiState: StateFlow<DayUiState> = _uiState

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<UiEvent> = _events

    private val _hasDraft = MutableStateFlow(false)
    val hasDraft: StateFlow<Boolean> = _hasDraft

    /** 临场通关条候选（action_pass_bar.md §A：仅今日派生，actionTime 升序；revision 驱动重算）。 */
    private val _actionPasses = MutableStateFlow<List<ActionPass>>(emptyList())
    val actionPasses: StateFlow<List<ActionPass>> = _actionPasses

    private var draftDebounce: Job? = null
    private var draftCurrent = QuickNoteDraft()
    private var draftSubmitted = false

    init {
        // 分钟墙钟 tick（时刻线/goNow/守卫窗 2h 字面量语义源）
        viewModelScope.launch { time.ticks.collect { _now.value = time.now() } }
        // 刷新面（D16）：revision 或选中日任一变化 → 重载 → StateFlow（单向流首帧即装载）
        viewModelScope.launch {
            combine(repo.revision, _selectedIso) { _, iso -> iso }.collectLatest { iso ->
                _uiState.value = DayUiState(data = null, loading = true)
                val day = ScheduleDay.tryParseIsoDate(iso) ?: return@collectLatest
                val loaded = withContext(Dispatchers.IO) { loadScheduleDay(repo, day, time.now()) }
                _uiState.value = DayUiState(data = loaded, loading = false)
                // 临场通关条（Dart FutureBuilder dailyActionPasses 镜像：仅今日派生）
                _actionPasses.value = if (loaded.isToday) withContext(Dispatchers.IO) { loadActionPasses() } else emptyList()
            }
        }
        // 冷启动回读草稿在盘态（FAB 琥珀点；回读失败静默=无草稿，DEGRADE 尽力而为）
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { refreshHasDraft() }
        }
    }

    // ── 页面态（日期参数化：周/月跳日回看历史） ────────────────────────────────

    fun selectDate(iso: String) {
        _selectedIso.value = iso
    }

    fun shiftDate(delta: Int) {
        val d = ScheduleDay.tryParseIsoDate(_selectedIso.value) ?: return
        selectDate(ScheduleDay.isoDate(d.plusDays(delta.toLong())))
    }

    fun pickToday() {
        selectDate(ScheduleDay.isoDate(ScheduleDay.scheduleDayOf(time.now(), DEFAULT_WAKE)))
    }

    // ── 命令面（human actor；成功=revision 驱动自动刷新，失败=事件 3s message） ──

    /** 遗留区三键·补勾（自动带补记标=命令层语义）。 */
    fun tickBlock(b: ScheduleBlock) = launchCommand {
        handler.execute(ScheduleCommand.TickBlock(id = b.id!!, expectedVersion = b.version), actor = CommandActor.HUMAN)
    }

    /** 遗留区三键·顺延今日。 */
    fun postponeBlock(b: ScheduleBlock, dateIso: String) = launchCommand {
        handler.execute(ScheduleCommand.PostponeBlock(id = b.id!!, date = dateIso, expectedVersion = b.version), actor = CommandActor.HUMAN)
    }

    /** 遗留区三键·退回愿望池（§5.4 Day-2 补记附问：绑 plan 块融化成功后附
     *  「已花 X 分钟未入账，补记吗」——SnackBar 补记动作回传壳层走 retro_log）。 */
    fun meltBlock(b: ScheduleBlock) = launchCommand {
        val r = handler.execute(ScheduleCommand.MeltBlock(id = b.id!!, expectedVersion = b.version), actor = CommandActor.HUMAN)
        if (b.planId != null) {
            val x = ScheduleDay.spanMinutes(b.startMin.toInt(), b.endMin.toInt())
            _events.tryEmit(
                UiEvent(
                    message = "${r.note ?: "已暂缓，放回清单待安排"}  已花 $x 分钟未入账，补记吗",
                    durationMs = 8000,
                    actionLabel = "补记",
                    actionRetroDate = b.date,
                    actionRetroStartMin = b.startMin,
                    actionRetroEndMin = b.endMin,
                    actionRetroPlanId = b.planId,
                    actionRetroLabel = b.label,
                ),
            )
        } else {
            _events.tryEmit(UiEvent(message = r.note ?: "已暂缓，放回清单待安排", durationMs = 2000))
        }
    }

    /** 融化补记（§5.4：按原块时段逆向记账，事实通道豁免排程校验）。 */
    fun retroLogMelted(date: String, startMin: Long, endMin: Long, planId: String?, label: String?) = launchCommand {
        handler.execute(
            ScheduleCommand.RetroLog(date = date, startMin = startMin, endMin = endMin, planId = planId, label = label),
            actor = CommandActor.HUMAN,
        )
        _events.tryEmit(UiEvent(message = "已补记", durationMs = 2000))
    }

    /** 手势·长按降档（AI 块坍缩 5 分钟火种胶囊；human 块命令层拒）。 */
    fun degradeBlock(b: ScheduleBlock) = launchCommand {
        handler.execute(ScheduleCommand.DegradeBlock(id = b.id!!, expectedVersion = b.version), actor = CommandActor.HUMAN)
    }

    /** 手势·换乘（候选已由 [swapCandidates] 挑选后带 targetPlanId 换入）。 */
    fun swapBlock(b: ScheduleBlock, targetPlanId: String) = launchCommand {
        val r = handler.execute(
            ScheduleCommand.SwapBlock(id = b.id!!, targetPlanId = targetPlanId, expectedVersion = b.version),
            actor = CommandActor.HUMAN,
        )
        _events.tryEmit(UiEvent(message = r.note ?: "已换乘", durationMs = 2000))
    }

    /** 手势·换乘候选（轻 ViewModel 直查：候选列表属 UI 选择面，非命令）。 */
    suspend fun swapCandidates(b: ScheduleBlock): List<Plan> = withContext(Dispatchers.IO) {
        val span = ScheduleDay.spanMinutes(b.startMin.toInt(), b.endMin.toInt())
        val anchor = ScheduleDay.tryParseIsoDate(b.date)!!
        val busyPlans = (repo.blocksInRange(
            ScheduleDay.isoDate(anchor.minusDays(365)),
            ScheduleDay.isoDate(anchor.plusDays(30)),
        ) as? JsonArray)?.filterIsInstance<JsonObject>()
            ?.mapNotNull { (it["plan_id"] as? JsonPrimitive)?.contentOrNull }
            ?.toSet() ?: emptySet()
        (repo.listPlans() as? JsonArray)?.filterIsInstance<JsonObject>()
            ?.map { Plan.fromMap(it) }
            .orEmpty()
            .filter { it.id != null && it.id !in busyPlans }
            .filter { it.energyLevel == Plan.ENERGY_LIGHT && it.toolRequired == Plan.TOOL_ANYWHERE }
            .sortedBy { it.estimate?.let { e -> kotlin.math.abs(e - span) } ?: Long.MAX_VALUE }
    }

    /** 确认卡批量确认（§E 批量语义：单块 ActionException 吞错不中断，失败块留提案态）。 */
    suspend fun confirmAllSuspend(proposed: List<ScheduleBlock>) {
        withContext(DbWriteQueue.dispatcher) {
            for (b in proposed) {
                runCatching {
                    handler.execute(ScheduleCommand.ConfirmBlock(id = b.id!!, expectedVersion = b.version), actor = CommandActor.HUMAN)
                }
            }
        }
    }

    /** 确认卡批量否决（[reason] 可选回传 AI 下轮参考；null=无原因否决）。 */
    suspend fun rejectAllSuspend(proposed: List<ScheduleBlock>, reason: String?) {
        withContext(DbWriteQueue.dispatcher) {
            for (b in proposed) {
                runCatching {
                    handler.execute(ScheduleCommand.RejectBlock(id = b.id!!, reason = reason), actor = CommandActor.HUMAN)
                }
            }
        }
    }

    /** 电量三档（§D）：update_settings；low → reflow_day 水流重算 + 事件 3s（r.note ?? 已降档）。 */
    fun setEnergy(level: String) = launchCommand {
        handler.execute(ScheduleCommand.UpdateSettings(values = mapOf("today_energy" to JsonPrimitive(level))), actor = CommandActor.HUMAN)
        if (level == "low") {
            val r = handler.execute(ScheduleCommand.ReflowDay(), actor = CommandActor.HUMAN)
            _events.tryEmit(UiEvent(message = r.note ?: "已降档", durationMs = 3000))
        }
    }

    /** 全局动作（§B 两段式确认后执行）：panic_clear 单命令；成功 2s（r.note ?? 完成）。 */
    fun panicClear(mode: String, nowMin: Int) = launchCommand {
        val r = handler.execute(ScheduleCommand.PanicClear(mode = mode, nowMin = nowMin.toLong()), actor = CommandActor.HUMAN)
        _events.tryEmit(UiEvent(message = r.note ?: "完成", durationMs = 2000))
    }

    // ── 块弹层命令面（block_sheet.md 转正清单；成功=true→页 pop + 事件 note 2s 浮动）──

    private suspend fun blockSheetRun(b: ScheduleBlock, cmd: suspend () -> app.shiguang.core.action.CommandResult): Boolean = try {
        val r = withContext(DbWriteQueue.dispatcher) { cmd() }
        _events.tryEmit(UiEvent(message = r.note ?: "完成", durationMs = 2000, floating = true))
        true
    } catch (e: ActionException) {
        _events.tryEmit(UiEvent(message = e.message, durationMs = 3000))
        false
    }

    suspend fun blockConfirm(b: ScheduleBlock): Boolean = blockSheetRun(b) {
        handler.execute(ScheduleCommand.ConfirmBlock(id = b.id!!, expectedVersion = b.version), actor = CommandActor.HUMAN)
    }

    suspend fun blockReject(b: ScheduleBlock, reason: String?): Boolean = blockSheetRun(b) {
        handler.execute(ScheduleCommand.RejectBlock(id = b.id!!, reason = reason), actor = CommandActor.HUMAN)
    }

    /** 打卡（误触撤销链：restore_block toStatus=打卡前快照；5s 附「撤销」）。 */
    suspend fun blockTick(b: ScheduleBlock): Boolean {
        val prevStatus = b.status
        return try {
            val r = withContext(DbWriteQueue.dispatcher) {
                handler.execute(ScheduleCommand.TickBlock(id = b.id!!, expectedVersion = b.version), actor = CommandActor.HUMAN)
            }
            _events.tryEmit(
                UiEvent(
                    message = r.note ?: "完成", durationMs = 5000, floating = true, actionLabel = "撤销",
                    actionUndoBlockId = b.id, actionUndoBlockToStatus = prevStatus,
                ),
            )
            true
        } catch (e: ActionException) {
            _events.tryEmit(UiEvent(message = e.message, durationMs = 3000))
            false
        }
    }

    /** 撤销钮（壳层事件面回传）：restore_block 成功 note 2s / 失败默认 4s。 */
    fun blockRestore(blockId: String, toStatus: String) = launchCommand {
        val r = handler.execute(ScheduleCommand.RestoreBlock(id = blockId, toStatus = toStatus), actor = CommandActor.HUMAN)
        _events.tryEmit(UiEvent(message = r.note ?: "已恢复", durationMs = 2000))
    }

    suspend fun blockShift(b: ScheduleBlock): Boolean = blockSheetRun(b) {
        handler.execute(ScheduleCommand.ShiftBlock(id = b.id!!, minutes = 30, expectedVersion = b.version), actor = CommandActor.HUMAN)
    }

    suspend fun blockAdjust(b: ScheduleBlock, startMin: Int, endMin: Int): Boolean = blockSheetRun(b) {
        handler.execute(ScheduleCommand.AdjustBlockTime(id = b.id!!, startMin = startMin.toLong(), endMin = endMin.toLong(), expectedVersion = b.version), actor = CommandActor.HUMAN)
    }

    // ── 块弹层/新建 sheet 装载面（VM 承载 repo 读——Konsist K3） ─────────────────

    suspend fun sheetPlan(b: ScheduleBlock): Plan? = withContext(Dispatchers.IO) {
        b.planId?.let { repo.planById(it)?.let(Plan::fromMap) }
    }

    suspend fun factForBlock(b: ScheduleBlock): Artifact? = withContext(Dispatchers.IO) {
        repo.artifactForBlock(b.id!!)?.let { Artifact.fromMap(it) }
    }

    /** 硬红线 human 通道角标（§9.3：绝不阻断仅提醒）——reason 按「：」切尾段。 */
    suspend fun ruleWarnings(b: ScheduleBlock): List<String> = withContext(Dispatchers.IO) {
        val root = b.planId ?: return@withContext emptyList()
        val ids = mutableListOf(root)
        suspend fun walk(parentId: String, depth: Int) {
            if (depth > 3) return
            val kids = (repo.listPlans(parentId = parentId) as? JsonArray)
                ?.filterIsInstance<JsonObject>() ?: emptyList()
            for (k in kids) {
                val id = (k["id"] as? JsonPrimitive)?.contentOrNull ?: continue
                ids.add(id)
                walk(id, depth + 1)
            }
        }
        walk(root, 1)
        val facts = (repo.artifactsForPlans(ids) as? JsonArray)?.filterIsInstance<JsonObject>() ?: emptyList()
        val date = ScheduleDay.tryParseIsoDate(b.date) ?: return@withContext emptyList()
        factRuleViolations(facts, date, b.startMin.toInt(), b.endMin.toInt()).map { "规则提醒：" + it.reason.substringAfterLast('：') }
    }

    suspend fun sheetPlans(): List<Plan> = withContext(Dispatchers.IO) {
        (repo.listPlans() as? JsonArray)?.filterIsInstance<JsonObject>()?.map { Plan.fromMap(it) } ?: emptyList()
    }

    /**
     * 新建事项/逆向记账提交（new_block_sheets.md §B/§C）：past=true → retro_log
     * （事实通道落 done 豁免校验）；成功静默 pop（无 toast——revision 刷新自现）。
     * DEGRADE: [new_block_submit] 失败静默留窗——镜像 Dart _submit 无 catch 现状（无反馈）。
     */
    suspend fun newBlockSubmit(dateIso: String, startMin: Int, endMin: Int, planId: String?, label: String?, past: Boolean): Boolean = try {
        withContext(DbWriteQueue.dispatcher) {
            if (past) {
                handler.execute(
                    ScheduleCommand.RetroLog(date = dateIso, startMin = startMin.toLong(), endMin = endMin.toLong(), planId = planId, label = label),
                    actor = CommandActor.HUMAN,
                )
            } else {
                handler.execute(
                    ScheduleCommand.PlaceBlock(date = dateIso, startMin = startMin.toLong(), endMin = endMin.toLong(), planId = planId, label = label),
                    actor = CommandActor.HUMAN,
                )
            }
        }
        true
    } catch (e: ActionException) {
        false
    }

    /** 新建/逆向提交回调桥（NewBlockSheet）：newBlockSubmit 协程化——成功 true→页 pop。 */
    fun submitNewBlock(
        dateIso: String,
        startMin: Int,
        endMin: Int,
        planId: String?,
        label: String?,
        past: Boolean,
        onDone: (Boolean) -> Unit,
    ) = viewModelScope.launch {
        onDone(newBlockSubmit(dateIso, startMin, endMin, planId, label, past))
    }

    /** 通关卡复制动作（fact_sheet.md §B 功能动作行）：剪贴板在页侧，事件 2s。 */
    fun notifyFactCopied(code: String) {
        _events.tryEmit(UiEvent(message = "已复制 $code", durationMs = 2000))
    }

    private suspend fun loadActionPasses(): List<ActionPass> {
        val ids = (repo.listPlans() as? JsonArray)
            ?.filterIsInstance<JsonObject>()
            ?.mapNotNull { (it["id"] as? JsonPrimitive)?.contentOrNull } ?: emptyList()
        if (ids.isEmpty()) return emptyList()
        val facts = (repo.artifactsForPlans(ids) as? JsonArray)?.filterIsInstance<JsonObject>() ?: emptyList()
        return computeActionPasses(facts, time.now())
    }

    // ── 快记条族（app_shell_sheets.md §C） ──────────────────────────────────────

    /** FAB tap 预载（防异步回填覆盖输入；面板拿到快照后初始化）。 */
    suspend fun loadDraft(): QuickNoteDraft = withContext(Dispatchers.IO) {
        val text = repo.settingsGet(SettingsKeys.QUICK_NOTE_DRAFT_TEXT) ?: ""
        val important = repo.settingsGet(SettingsKeys.QUICK_NOTE_DRAFT_IMPORTANT) == "1"
        val dl = repo.settingsGet(SettingsKeys.QUICK_NOTE_DRAFT_DEADLINE)
        QuickNoteDraft(text, important, dl?.let { ScheduleDay.tryParseIsoDate(it) })
    }

    /** 文本变化：300ms 防抖即写即存。 */
    fun onDraftTextChanged(text: String) {
        draftDebounce?.cancel()
        draftCurrent = draftCurrent.copy(text = text)
        _hasDraft.value = !draftCurrent.isEmpty
        draftDebounce = viewModelScope.launch {
            delay(300)
            persistDraft()
        }
    }

    /** 星标/截止日变化：即改即存（无防抖）。 */
    fun onDraftMetaChanged(important: Boolean? = null, deadline: LocalDate? = null) {
        draftDebounce?.cancel()
        draftCurrent = draftCurrent.copy(
            important = important ?: draftCurrent.important,
            deadline = deadline,
        )
        viewModelScope.launch { persistDraft() }
    }

    /** 抽屉收起（下滑/遮罩/返回）：未提交时冲刷防抖尾巴；已提交=草稿已清零。 */
    fun onSheetClosed() {
        draftDebounce?.cancel()
        if (!draftSubmitted) {
            viewModelScope.launch { persistDraft() }
        }
    }

    /** 提交（「收入清单」）：quick_capture → 清草稿 → 事件 1s「已收入清单」浮动+FAB 净空。 */
    suspend fun submitQuickNote(text: String, important: Boolean, deadline: LocalDate?): Boolean {
        val title = text.trim()
        if (title.isEmpty()) return false
        try {
            withContext(DbWriteQueue.dispatcher) {
                handler.execute(
                    ScheduleCommand.QuickCapture(
                        title = title,
                        importance = important,
                        deadline = deadline?.let { ScheduleDay.isoDate(it) },
                    ),
                    actor = CommandActor.HUMAN,
                )
                handler.execute(
                    ScheduleCommand.QuickNoteDraft(text = "", importance = false, deadline = null),
                    actor = CommandActor.HUMAN,
                )
            }
        } catch (e: ActionException) {
            _events.tryEmit(UiEvent(message = e.message, durationMs = 3000))
            return false
        }
        draftSubmitted = true
        draftCurrent = QuickNoteDraft()
        _hasDraft.value = false
        _events.tryEmit(UiEvent(message = "已收入清单", durationMs = 1000, floating = true))
        return true
    }

    private suspend fun persistDraft() {
        try {
            withContext(DbWriteQueue.dispatcher) {
                handler.execute(
                    ScheduleCommand.QuickNoteDraft(
                        text = draftCurrent.text,
                        importance = draftCurrent.important,
                        deadline = draftCurrent.deadline?.let { ScheduleDay.isoDate(it) },
                    ),
                    actor = CommandActor.HUMAN,
                )
            }
        } catch (_: ActionException) {
            // DEGRADE: [draft_persist] 草稿落盘失败静默——零丢失语义尽力而为，不挡快记主路径
        }
        _hasDraft.value = !draftCurrent.isEmpty
    }

    private suspend fun refreshHasDraft() {
        val text = repo.settingsGet(SettingsKeys.QUICK_NOTE_DRAFT_TEXT)
        _hasDraft.value = !text.isNullOrBlank()
    }

    // ── 周月视图读面透传（N6；周月只读无命令——anchor 变化重取，Dart FutureBuilder 同构） ──

    suspend fun weekOverview(anchor: java.time.LocalDate): JsonObject = withContext(Dispatchers.IO) {
        ScheduleQueries(repo).weekOverview(anchor)
    }

    suspend fun monthOverview(year: Int, month: Int): JsonObject = withContext(Dispatchers.IO) {
        ScheduleQueries(repo).monthOverview(year, month)
    }

    /**
     * 翻月：preserveDay=true 保留几号钳月末（翻月钮，Dart _shiftMonth 镜像——从原
     * day 对目标月末 clamp，非 plusMonths 链式钳制）；false=目标月 1 号（横滑，Dart
     * setState DateTime(y, m±1, 1) 镜像）。
     */
    fun shiftMonth(delta: Int, preserveDay: Boolean) {
        val d = ScheduleDay.tryParseIsoDate(_selectedIso.value) ?: return
        val target = d.plusMonths(delta.toLong())
        val nd = if (preserveDay) {
            val monthLen = java.time.LocalDate.of(target.year, target.month, 1).lengthOfMonth()
            java.time.LocalDate.of(target.year, target.month, d.dayOfMonth.coerceAtMost(monthLen))
        } else {
            java.time.LocalDate.of(target.year, target.month, 1)
        }
        selectDate(ScheduleDay.isoDate(nd))
    }

    // ── 守卫态明日概要（§G WindDown；label 解析同块浮层口径 label→plan.title） ──

    suspend fun tomorrowSummary(): TomorrowSummary = withContext(Dispatchers.IO) {
        val today = ScheduleDay.tryParseIsoDate(_selectedIso.value)
            ?: return@withContext TomorrowSummary(0, null, null)
        val rows = (repo.blocksOnDate(ScheduleDay.isoDate(today.plusDays(1))) as? JsonArray)
            ?.filterIsInstance<JsonObject>() ?: emptyList()
        val blocks = rows.map { ScheduleBlock.fromMap(it) }
            .filter { it.status != BlockStatus.MELTED }
            .sortedBy { it.startMin }
        if (blocks.isEmpty()) return@withContext TomorrowSummary(0, null, null)
        val first = blocks.first()
        var label = first.label ?: ""
        if (label.isEmpty() && first.planId != null) {
            label = repo.planById(first.planId!!)?.let { Plan.fromMap(it) }?.title ?: ""
        }
        TomorrowSummary(
            count = blocks.size,
            firstTime = ScheduleDay.clockOf(first.startMin.toInt()),
            firstLabel = label.ifEmpty { "(未命名)" },
        )
    }

    /** 今日 done 块数与完成分钟（WindDown 首行）。 */
    fun doneStats(data: ScheduleDayData): Pair<Int, Int> {
        val done = data.blocks.filter { it.status == BlockStatus.DONE }
        val minutes = done.sumOf { ScheduleDay.spanMinutes(it.startMin.toInt(), it.endMin.toInt()) }
        return Pair(done.size, minutes)
    }

    private fun launchCommand(block: suspend () -> Unit) = viewModelScope.launch {
        try {
            withContext(DbWriteQueue.dispatcher) { block() }
        } catch (e: ActionException) {
            _events.tryEmit(UiEvent(message = e.message, durationMs = 3000))
        }
    }

    companion object {
        private const val DEFAULT_WAKE = 420

        /** Manual DI Factory（AppContainer 纪律：ViewModel 经统一 Factory 接入，禁无参构造）。 */
        val factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as ShiguangApplication
                TodayViewModel(app.container.repo, app.container.time)
            }
        }
    }
}
