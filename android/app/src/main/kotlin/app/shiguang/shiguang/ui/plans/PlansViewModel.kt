package app.shiguang.shiguang.ui.plans

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.shiguang.core.action.ActionException
import app.shiguang.core.action.BridgeRepositoryPort
import app.shiguang.core.action.CommandActor
import app.shiguang.core.action.CommandHandler
import app.shiguang.core.action.DbWriteQueue
import app.shiguang.core.action.ScheduleCommand
import app.shiguang.core.action.ScheduleQueries
import app.shiguang.core.model.Plan
import app.shiguang.core.model.ScheduleBlock
import app.shiguang.core.model.ScheduleDay
import app.shiguang.core.model.TimeProvider
import app.shiguang.shiguang.ShiguangApplication
import app.shiguang.shiguang.ui.schedule.UiEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * 清单页轻 ViewModel（D15 同形，N7）：StateFlow 单向流——读=collect repo.revision
 * （D16 刷新面）→ 重载 PlansView（Dart _load 逐字镜像：三分区分流/四象限数据/
 * 未归属计数/今日火种）→ StateFlow；写=命令面（archive/undo/restore/deletePlan）。
 * 事件面复用 UiEvent（撤销动作=actionLabel+undoPlanId/undoVersion 回传壳层）。
 */
class PlansViewModel(
    /** 仓储读端口（K8 白名单族——读走 StateFlow 消费端，写=命令面 handler）。 */
    val repo: BridgeRepositoryPort,
    private val time: TimeProvider,
) : ViewModel() {

    private val handler = CommandHandler(repo)

    /** 清单页装配视图（Dart _PlansView 镜像）。 */
    data class PlansView(
        val today: List<Plan>,
        val inProgress: List<Plan>,
        val waiting: List<Plan>,
        val archived: List<Plan>,
        val proposedByPlan: Map<String, Int>,
        val sparkPlanIds: Set<String>,
        val todayIso: String,
        val unattributedCount: Int,
    ) {
        /** 空池判定：四分区全空（冷藏池有货不算空池——§B）。 */
        val isEmpty: Boolean
            get() = today.isEmpty() && inProgress.isEmpty() && waiting.isEmpty() && archived.isEmpty()
    }

    data class PlansUiState(
        val view: PlansView? = null,
        val loading: Boolean = true,
    )

    private val _uiState = MutableStateFlow(PlansUiState())
    val uiState: StateFlow<PlansUiState> = _uiState

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<UiEvent> = _events

    init {
        viewModelScope.launch {
            repo.revision.collectLatest { _ ->
                val loaded = withContext(Dispatchers.IO) { load() }
                _uiState.value = PlansUiState(view = loaded, loading = false)
            }
        }
    }

    /** 搜索查询（N1）：空串=不过滤；trim 即滤——只影响可见条目不触发重载。 */
    fun setSearch(v: String) {
        _query.value = v.trim()
    }

    private suspend fun load(): PlansView {
        val queries = ScheduleQueries(repo)
        val settings = queries.getSettings()
        val wake = (settings["wake_time"] as? JsonPrimitive)?.longOrNull?.toInt() ?: 0
        val today = ScheduleDay.scheduleDayOf(time.now(), wake)
        val todayIso = ScheduleDay.isoDate(today)
        val plans = (repo.listPlans(includeArchived = true) as? JsonArray)
            ?.filterIsInstance<JsonObject>()?.map { Plan.fromMap(it) } ?: emptyList()
        val unattributedCount = (repo.artifactsUnattributed() as? JsonArray)?.size ?: 0
        val todayBlocks = jsonToBlocks(repo.blocksOnDate(todayIso))
        val planOfBlock = HashMap<String, String>()
        val proposedByPlan = HashMap<String, Int>()
        for (b in todayBlocks) {
            b.planId?.let { planOfBlock[it] = todayIso }
            if (b.status == ScheduleBlock.STATUS_PROPOSED) {
                b.planId?.let { proposedByPlan[it] = (proposedByPlan[it] ?: 0) + 1 }
            }
        }
        // 未来块窗口 30 天字面量（§A 单条范围 SQL 派生查询）
        val futureBlocks = jsonToBlocks(
            repo.blocksInRange(todayIso, ScheduleDay.isoDate(today.plusDays(30))),
        )
        for (b in futureBlocks) {
            val pid = b.planId ?: continue
            if (b.date != todayIso) planOfBlock.putIfAbsent(pid, b.date)
            if (b.status == ScheduleBlock.STATUS_PROPOSED) {
                proposedByPlan[pid] = (proposedByPlan[pid] ?: 0) + 1
            }
        }
        val sparkPlanIds = todayBlocks
            .filter { it.isDaySpark && it.planId != null }
            .mapNotNull { it.planId }
            .toSet()
        val todayPlans = mutableListOf<Plan>()
        val inProgress = mutableListOf<Plan>()
        val waiting = mutableListOf<Plan>()
        val archivedPlans = mutableListOf<Plan>()
        for (p in plans) {
            if (p.archived) {
                archivedPlans.add(p) // 冷藏池（§8）：归档条目折叠展示，可恢复
                continue
            }
            val blockDate = p.id?.let { planOfBlock[it] }
            when {
                blockDate == todayIso -> todayPlans.add(p)
                blockDate != null -> inProgress.add(p)
                else -> waiting.add(p)
            }
        }
        return PlansView(
            today = todayPlans,
            inProgress = inProgress,
            waiting = waiting,
            archived = archivedPlans,
            proposedByPlan = proposedByPlan,
            sparkPlanIds = sparkPlanIds,
            todayIso = todayIso,
            unattributedCount = unattributedCount,
        )
    }

    // ── 命令面（human actor；成功=revision 驱动刷新，失败=事件 3s） ─────────────

    /** 左滑归档 + 撤销链（§D）：归档 CAS 成功后回传 fresh version 供撤销钮。 */
    suspend fun archiveWithUndo(plan: Plan) {
        try {
            val r = withContext(DbWriteQueue.dispatcher) {
                handler.execute(
                    ScheduleCommand.UpdatePlan(
                        id = plan.id!!,
                        archived = true,
                        expectedVersion = plan.version,
                    ),
                    actor = CommandActor.HUMAN,
                )
            }
            val fresh = (r.snapshot?.get("version") as? JsonPrimitive)?.longOrNull ?: plan.version
            _events.tryEmit(
                UiEvent(
                    message = "已归档「${plan.title}」",
                    durationMs = 3000,
                    floating = true,
                    actionLabel = "撤销",
                    actionUndoPlanId = plan.id,
                    actionUndoVersion = fresh,
                ),
            )
        } catch (e: ActionException) {
            _events.tryEmit(UiEvent(message = e.message, durationMs = 3000))
        }
    }

    /** 撤销钮（归档在 UI 上暂不可逆必附撤销——减震器）。 */
    fun undoArchive(planId: String, version: Long) = launchCommand {
        handler.execute(
            ScheduleCommand.UpdatePlan(id = planId, archived = false, expectedVersion = version),
            actor = CommandActor.HUMAN,
        )
    }

    /** 冷藏池恢复（§C）：无 SnackBar（Dart 同构）。 */
    fun restore(plan: Plan) = launchCommand {
        handler.execute(
            ScheduleCommand.UpdatePlan(
                id = plan.id!!,
                archived = false,
                expectedVersion = plan.version,
            ),
            actor = CommandActor.HUMAN,
        )
    }

    /** 删除（长按 sheet 确认后）：失败=事件 3s。 */
    fun deletePlan(plan: Plan) = launchCommand {
        handler.execute(ScheduleCommand.DeletePlan(plan.id!!), actor = CommandActor.HUMAN)
    }

    private fun launchCommand(block: suspend () -> Unit) = viewModelScope.launch {
        try {
            withContext(DbWriteQueue.dispatcher) { block() }
        } catch (e: ActionException) {
            _events.tryEmit(UiEvent(message = e.message, durationMs = 3000))
        }
    }

    companion object {
        /** Manual DI Factory（AppContainer 纪律同 TodayViewModel）。 */
        val factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as ShiguangApplication
                PlansViewModel(app.container.repo, app.container.time)
            }
        }
    }
}

private fun jsonToBlocks(raw: kotlinx.serialization.json.JsonElement): List<ScheduleBlock> =
    (raw as? JsonArray)?.filterIsInstance<JsonObject>()?.map { ScheduleBlock.fromMap(it) } ?: emptyList()
