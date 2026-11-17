package app.shiguang.shiguang.ui.plan

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
import app.shiguang.core.model.Artifact
import app.shiguang.core.model.ArtifactRules
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
import kotlinx.serialization.json.contentOrNull

/**
 * 计划「随行凭证」子页轻 ViewModel（D15 同形，plan_facts.md 转正清单 §A/§D）：
 * 读=collect repo.revision → 子树派生聚合（根+全部子孙 ≤3 级）+ artifactsForPlans
 * （includeVoided=true，D24-G2 Expand）→ 四分区派生（raw/structured/expired/voided）
 * → StateFlow；写=delete_artifact（仅 human 长按入口；删凭证绝不删块）。
 * 过期口径=ArtifactRules.factExpired（D24 拍板落点 :core:model）。
 */
class PlanFactsViewModel(
    /** 仓储读端口（K8 白名单族——读走 StateFlow 消费端，写=命令面 handler）。 */
    val repo: BridgeRepositoryPort,
    private val time: TimeProvider,
    private val planId: String,
) : ViewModel() {

    /** 分区派生视图（§A：voided 单列；live→active（createdAt 升序）+expired；raw/structured）。 */
    data class FactsView(
        val raw: List<Artifact>,
        val structured: List<Artifact>,
        val expired: List<Artifact>,
        val voided: List<Artifact>,
    ) {
        val isEmpty: Boolean
            get() = raw.isEmpty() && structured.isEmpty() && expired.isEmpty() && voided.isEmpty()
    }

    data class PlanFactsUiState(
        val view: FactsView? = null,
        val loading: Boolean = true,
    )

    private val _uiState = MutableStateFlow(PlanFactsUiState())
    val uiState: StateFlow<PlanFactsUiState> = _uiState

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<UiEvent> = _events

    private val handler = CommandHandler(repo)

    init {
        viewModelScope.launch {
            repo.revision.collectLatest {
                val loaded = withContext(Dispatchers.IO) { load() }
                _uiState.value = PlanFactsUiState(view = loaded, loading = false)
            }
        }
    }

    /** 子树 id 集（root+全部子孙，≤3 级——与详情页「子计划」同口径）。 */
    private suspend fun subtreeIds(): List<String> {
        val out = mutableListOf<String>()
        suspend fun walk(parentId: String, depth: Int) {
            if (depth > 3) return
            val kids = (repo.listPlans(parentId = parentId) as? JsonArray)
                ?.filterIsInstance<JsonObject>()
                ?.mapNotNull { row -> (row["id"] as? JsonPrimitive)?.contentOrNull }
                ?: emptyList()
            for (k in kids) {
                out.add(k)
                walk(k, depth + 1)
            }
        }
        out.add(planId)
        walk(planId, 1)
        return out
    }

    private suspend fun load(): FactsView {
        val ids = subtreeIds()
        val all = (repo.artifactsForPlans(ids, includeVoided = true) as? JsonArray)
            ?.filterIsInstance<JsonObject>()?.map { Artifact.fromMap(it) } ?: emptyList()
        if (all.isEmpty()) return FactsView(emptyList(), emptyList(), emptyList(), emptyList())
        val todayIso = ScheduleDay.isoDate(time.now().toLocalDate())
        val voided = all.filter { it.state == Artifact.STATE_VOIDED }
        val live = all.filterNot { it.state == Artifact.STATE_VOIDED }
        val active = live.filterNot { ArtifactRules.factExpired(it, todayIso) }
            .sortedBy { it.createdAt ?: 0L }
        val expired = live.filter { ArtifactRules.factExpired(it, todayIso) }
        return FactsView(
            raw = active.filter { it.state == Artifact.STATE_RAW },
            structured = active.filterNot { it.state == Artifact.STATE_RAW },
            expired = expired,
            voided = voided,
        )
    }

    /** 凭证删除（§D：二次确认后；仅 human；删凭证绝不删块）。成功 note??「已删除」2s。 */
    fun deleteArtifact(fact: Artifact) = viewModelScope.launch {
        try {
            val r = withContext(DbWriteQueue.dispatcher) {
                handler.execute(ScheduleCommand.DeleteArtifact(fact.id!!), actor = CommandActor.HUMAN)
            }
            _events.tryEmit(UiEvent(message = r.note ?: "已删除", durationMs = 2000))
        } catch (e: ActionException) {
            _events.tryEmit(UiEvent(message = e.message, durationMs = 4000))
        }
    }

    /** 通关卡复制动作事件（2s「已复制 {code}」）。 */
    fun notifyFactCopied(code: String) {
        _events.tryEmit(UiEvent(message = "已复制 $code", durationMs = 2000))
    }

    companion object {
        /** Manual DI Factory（AppContainer 纪律同 PlansViewModel；planId 随路由注入）。 */
        fun factory(planId: String): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as ShiguangApplication
                PlanFactsViewModel(app.container.repo, app.container.time, planId)
            }
        }
    }
}
