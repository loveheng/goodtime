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
import app.shiguang.core.action.OpenItemRef
import app.shiguang.core.action.ScheduleCommand
import app.shiguang.core.model.OpenItem
import app.shiguang.core.model.Plan
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.LocalDate

/**
 * 计划详情页轻 ViewModel（D15 同形，plan_detail.md 转正清单 §A/§C/§D）：
 * 读=collect repo.revision（D16 刷新面）→ planById 重载 → StateFlow（编辑态不
 * 打断；表单四字段仅首载回填——Dart controller 仅 _load 回填现状）；写=
 * update_plan（编辑保存/手答整表替换）+ place_block（排期快捷动作）。
 * F2（D24 裁决）：loading/content/missing 三态消除 Dart 首帧闪「不存在」。
 */
class PlanDetailViewModel(
    /** 仓储读端口（K8 白名单族——读走 StateFlow 消费端，写=命令面 handler）。 */
    val repo: BridgeRepositoryPort,
    private val time: TimeProvider,
    private val planId: String,
    /** 丢失点 #9：编辑暂存收敛（进程重建回填——标题/spec/备注/犒赏四字段）。 */
    private val savedState: androidx.lifecycle.SavedStateHandle,
) : ViewModel() {

    data class PlanDetailUiState(
        val plan: Plan? = null,
        val loading: Boolean = true,
        val editing: Boolean = false,
        val openItems: List<OpenItem> = emptyList(),
        val subtree: List<Pair<Int, Plan>> = emptyList(),
        val version: Long = 0,
    )

    private val _uiState = MutableStateFlow(PlanDetailUiState())
    val uiState: StateFlow<PlanDetailUiState> = _uiState

    // 编辑表单四字段（§C；仅首载回填，外部变更不重置——Dart controller 现状镜像；
    // Konsist：VM 只暴露只读 StateFlow，写入走 setter）
    private val _title = MutableStateFlow("")
    val title: StateFlow<String> = _title
    private val _spec = MutableStateFlow("")
    val spec: StateFlow<String> = _spec
    private val _notes = MutableStateFlow("")
    val notes: StateFlow<String> = _notes
    private val _reward = MutableStateFlow("")
    val reward: StateFlow<String> = _reward

    fun setTitle(v: String) { _title.value = v; savedState["edit_title"] = v }
    fun setSpec(v: String) { _spec.value = v; savedState["edit_spec"] = v }
    fun setNotes(v: String) { _notes.value = v; savedState["edit_notes"] = v }
    fun setReward(v: String) { _reward.value = v; savedState["edit_reward"] = v }

    private var loadedOnce = false

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<UiEvent> = _events

    private val handler = CommandHandler(repo)

    init {
        viewModelScope.launch {
            repo.revision.collectLatest {
                val loaded = withContext(Dispatchers.IO) { load() }
                _uiState.value = loaded
            }
        }
    }

    private suspend fun load(): PlanDetailUiState {
        val p = repo.planById(planId)?.let { Plan.fromMap(it) }
            ?: return PlanDetailUiState(loading = false, editing = _uiState.value.editing)
        if (!loadedOnce) {
            loadedOnce = true
            // 丢失点 #9：编辑暂存回填优先（进程重建时 handle 留有未保存草稿）
            _title.value = savedState.get<String>("edit_title") ?: p.title
            _spec.value = savedState.get<String>("edit_spec") ?: (p.spec ?: "")
            _notes.value = savedState.get<String>("edit_notes") ?: (p.notes ?: "")
            _reward.value = savedState.get<String>("edit_reward") ?: (p.rewardSpec ?: "")
        }
        return PlanDetailUiState(
            plan = p,
            loading = false,
            editing = _uiState.value.editing,
            openItems = p.openItems,
            subtree = subtree(p.id!!),
            version = p.version,
        )
    }

    /** 子树（§A：parentId 逐层 walk，depth 1 起 >3 return——≤3 级缩进）。 */
    private suspend fun subtree(rootId: String): List<Pair<Int, Plan>> {
        val out = mutableListOf<Pair<Int, Plan>>()
        suspend fun walk(parentId: String, depth: Int) {
            if (depth > 3) return
            val kids = (repo.listPlans(parentId = parentId) as? kotlinx.serialization.json.JsonArray)
                ?.filterIsInstance<kotlinx.serialization.json.JsonObject>()?.map { Plan.fromMap(it) }
                ?: emptyList()
            for (k in kids) {
                out.add(depth to k)
                walk(k.id!!, depth + 1)
            }
        }
        walk(rootId, 1)
        return out
    }

    /** 编辑/完成 toggle（§B AppBar；不保存退出=翻回查看态，无脏态确认——现状）。 */
    fun toggleEditing() {
        _uiState.value = _uiState.value.copy(editing = !_uiState.value.editing)
    }

    /**
     * 编辑态保存（§C）：四字段 trim 空传 null=不改动（清空字段在本表单不可达，
     * D24-F3 现状平移）；成功回查看态+「已保存」1s；version 快照回填（同会话
     * 连续操作不被乐观锁卡住）。
     */
    fun save() = launchCommand(failMs = 3000) {
        val s = _uiState.value
        val plan = s.plan ?: return@launchCommand
        val r = handler.execute(
            ScheduleCommand.UpdatePlan(
                id = plan.id!!,
                title = title.value.trim().ifEmpty { null },
                spec = spec.value.trim().ifEmpty { null },
                notes = notes.value.trim().ifEmpty { null },
                rewardSpec = reward.value.trim().ifEmpty { null },
                expectedVersion = s.version,
            ),
            actor = CommandActor.HUMAN,
        )
        val fresh = (r.snapshot?.get("version") as? JsonPrimitive)?.longOrNull
        _uiState.value = _uiState.value.copy(editing = false, version = fresh ?: (s.version + 1))
        _events.tryEmit(UiEvent(message = "已保存", durationMs = 1000))
    }

    /**
     * open_items 手答（人机澄清闭环 §B）：整表替换+答案落库转已答；答案 trim 空
     * 不提交；失败 message SnackBar 默认 4s（Dart 无 duration 同构）。
     */
    fun answer(index: Int, text: String) = launchCommand(failMs = 4000) {
        val answer = text.trim()
        if (answer.isEmpty()) return@launchCommand
        val s = _uiState.value
        val plan = s.plan ?: return@launchCommand
        val updated = s.openItems.mapIndexed { j, item ->
            if (j == index) OpenItem(question = item.question, answer = answer) else item
        }
        val r = handler.execute(
            ScheduleCommand.UpdatePlan(
                id = plan.id!!,
                openItems = updated.map { OpenItemRef(question = it.question, answer = it.answer) },
                expectedVersion = s.version,
            ),
            actor = CommandActor.HUMAN,
        )
        val fresh = (r.snapshot?.get("version") as? JsonPrimitive)?.longOrNull
        _uiState.value = _uiState.value.copy(openItems = updated, version = fresh ?: (s.version + 1))
    }

    /**
     * 排期快捷动作（§D）：日期/时间 picker 在页侧，VM 接结果落 place_block
     * （endMin=startMin+estimate??60）；成功不关页+SnackBar 2s（全页形态下用户
     * 常继续编辑/记背景）。
     */
    fun schedule(dateIso: String, startMin: Int) = launchCommand(failMs = 3000) {
        val plan = _uiState.value.plan ?: return@launchCommand
        handler.execute(
            ScheduleCommand.PlaceBlock(
                date = dateIso,
                startMin = startMin.toLong(),
                endMin = (startMin + (plan.estimate ?: 60)).toLong(),
                planId = plan.id,
            ),
            actor = CommandActor.HUMAN,
        )
        _events.tryEmit(
            UiEvent(message = "已排入 $dateIso ${ScheduleDay.clockOf(startMin)}，可在日程页调整", durationMs = 2000),
        )
    }

    /** 排期默认日（§A）：wake_time 解析失败回落 420 → scheduleDayOf(now, wake) 作息日口径。 */
    suspend fun scheduleDefaultDay(): LocalDate {
        val wake = repo.settingsGet("wake_time")?.toIntOrNull() ?: 420
        return ScheduleDay.scheduleDayOf(time.now(), wake)
    }

    private fun launchCommand(failMs: Long, block: suspend () -> Unit) = viewModelScope.launch {
        try {
            withContext(DbWriteQueue.dispatcher) { block() }
        } catch (e: ActionException) {
            _events.tryEmit(UiEvent(message = e.message, durationMs = failMs))
        }
    }

    companion object {
        /** Manual DI Factory（AppContainer 纪律同 PlansViewModel；planId 随路由注入）。 */
        fun factory(planId: String): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as ShiguangApplication
                PlanDetailViewModel(app.container.repo, app.container.time, planId, androidx.lifecycle.SavedStateHandle())
            }
        }
    }
}
