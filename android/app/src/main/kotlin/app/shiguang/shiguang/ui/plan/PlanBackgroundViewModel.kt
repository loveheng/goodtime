package app.shiguang.shiguang.ui.plan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.shiguang.core.action.ActionException
import app.shiguang.core.action.BackgroundMergeOp
import app.shiguang.core.action.BridgeRepositoryPort
import app.shiguang.core.action.CommandActor
import app.shiguang.core.action.CommandHandler
import app.shiguang.core.action.DbWriteQueue
import app.shiguang.core.action.ScheduleCommand
import app.shiguang.core.model.Background
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

/**
 * 计划「背景」子页轻 ViewModel（D15 同形，plan_background.md 转正清单 §A/§C/§D）：
 * 读=collect repo.revision → listBackgrounds(scope=plan, planId)（D24-G1 Expand）
 * → active/expired 分区派生 → StateFlow；写=upsert_background（human，source 自动
 * =user）+ merge_backgrounds delete ops（单条删与清空已过期共用，单事务）。
 */
class PlanBackgroundViewModel(
    /** 仓储读端口（K8 白名单族——读走 StateFlow 消费端，写=命令面 handler）。 */
    val repo: BridgeRepositoryPort,
    private val time: TimeProvider,
    private val planId: String,
) : ViewModel() {

    /** 分区派生视图（§A：过期=有窗且（空窗 fail-closed 或 last<今日）；无窗=长期）。 */
    data class BackgroundsView(
        val active: List<Background>,
        val expired: List<Background>,
    ) {
        companion object {
            /** 过期判定（派生渲染不入库——Dart _expired 逐字镜像）。 */
            fun expired(b: Background, todayIso: String): Boolean {
                val d = b.applicableDates ?: return false
                if (d.isEmpty()) return true
                return d.last() < todayIso
            }
        }
    }

    data class PlanBackgroundUiState(
        val view: BackgroundsView? = null,
        val loading: Boolean = true,
    )

    private val _uiState = MutableStateFlow(PlanBackgroundUiState())
    val uiState: StateFlow<PlanBackgroundUiState> = _uiState

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<UiEvent> = _events

    private val handler = CommandHandler(repo)

    init {
        viewModelScope.launch {
            repo.revision.collectLatest {
                val loaded = withContext(Dispatchers.IO) { load() }
                _uiState.value = PlanBackgroundUiState(view = loaded, loading = false)
            }
        }
    }

    private suspend fun load(): BackgroundsView {
        val all = (repo.listBackgrounds(scope = Background.SCOPE_PLAN, planId = planId) as? JsonArray)
            ?.filterIsInstance<JsonObject>()?.map { Background.fromMap(it) } ?: emptyList()
        val todayIso = ScheduleDay.isoDate(time.now().toLocalDate())
        return BackgroundsView(
            active = all.filterNot { BackgroundsView.expired(it, todayIso) },
            expired = all.filter { BackgroundsView.expired(it, todayIso) },
        )
    }

    /**
     * 背景编辑 sheet 保存（§C）：content trim 非空才提交；返回 true=关 sheet。
     * r.note 有值 SnackBar 3s（长窗劝改等命令层提示，关 sheet 前浮出）。
     */
    suspend fun save(
        existing: Background?,
        content: String,
        tags: List<String>,
        applicableDates: List<String>?,
    ): Boolean {
        val text = content.trim()
        if (text.isEmpty()) return false
        return try {
            val r = withContext(DbWriteQueue.dispatcher) {
                handler.execute(
                    ScheduleCommand.UpsertBackground(
                        id = existing?.id,
                        scope = Background.SCOPE_PLAN,
                        planId = planId,
                        content = text,
                        tags = tags,
                        applicableDates = applicableDates,
                        expectedVersion = existing?.version,
                    ),
                    actor = CommandActor.HUMAN,
                )
            }
            r.note?.let { _events.tryEmit(UiEvent(message = it, durationMs = 3000)) }
            true
        } catch (e: ActionException) {
            _events.tryEmit(UiEvent(message = e.message, durationMs = 3000))
            false
        }
    }

    /** 删除/清空已过期（§D）：merge_backgrounds delete ops 单事务；失败 message 4s。 */
    fun deleteBackgrounds(targets: List<Background>) = viewModelScope.launch {
        try {
            withContext(DbWriteQueue.dispatcher) {
                handler.execute(
                    ScheduleCommand.MergeBackgrounds(
                        ops = targets.map { BackgroundMergeOp(kind = "delete", id = it.id) },
                    ),
                    actor = CommandActor.HUMAN,
                )
            }
        } catch (e: ActionException) {
            _events.tryEmit(UiEvent(message = e.message, durationMs = 4000))
        }
    }

    companion object {
        /** Manual DI Factory（AppContainer 纪律同 PlansViewModel；planId 随路由注入）。 */
        fun factory(planId: String): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as ShiguangApplication
                PlanBackgroundViewModel(app.container.repo, app.container.time, planId)
            }
        }
    }
}
