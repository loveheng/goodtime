package app.shiguang.shiguang.ui.settings

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
import app.shiguang.core.action.FixedSlotRef
import app.shiguang.core.model.ExceptionWindow
import app.shiguang.core.model.FixedSlot
import app.shiguang.core.model.SettingsKeys
import app.shiguang.core.model.TimeProvider
import app.shiguang.core.model.parseExceptions
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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 设置编辑器三子页轻 ViewModel（D15 同形，N8b）：revision 驱动三组状态重载
 * （fixed_slots/例外日/settings 快照——三 destination 各自消费自己段）；命令面=
 * update_fixed_slots 整单批量替换/update_settings{exceptions} 结构化值直传（R2：
 * 编解码归命令层）/update_settings 字段级（改一个存一个）。
 */
class SubpagesViewModel(
    /** 仓储读端口（K8 白名单族——读走 StateFlow 消费端，写=命令面 handler）。 */
    val repo: BridgeRepositoryPort,
    private val time: TimeProvider,
) : ViewModel() {

    private val handler = CommandHandler(repo)

    data class SubUiState(
        val slots: List<FixedSlot> = emptyList(),
        val exceptions: List<ExceptionWindow> = emptyList(),
        val settings: JsonObject? = null,
        val loading: Boolean = true,
    )

    private val _uiState = MutableStateFlow(SubUiState())
    val uiState: StateFlow<SubUiState> = _uiState

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<UiEvent> = _events

    init {
        viewModelScope.launch {
            repo.revision.collectLatest { _ -> reload() }
        }
    }

    private suspend fun reload() {
        val slots = (repo.listFixedSlots() as? JsonArray)
            ?.filterIsInstance<JsonObject>()?.map { FixedSlot.fromMap(it) } ?: emptyList()
        val raw = repo.settingsAll().toStringMap()
        val exceptions = parseExceptions(raw)
        val settings = ScheduleQueries(repo).getSettings()
        _uiState.value = SubUiState(slots = slots, exceptions = exceptions, settings = settings, loading = false)
    }

    /** 编辑 sheet 内联校验提示（Dart 同构：SnackBar 后不关 sheet）。 */
    fun notifyValidation(message: String) {
        _events.tryEmit(UiEvent(message = message, durationMs = 3000))
    }

    // ── 一周节奏（§A：整单批量替换，条目独立） ─────────────────────────────────

    /** 编辑/添加保存：current−替换项+新项→update_fixed_slots 整单替换。 */
    fun saveFixedSlot(slot: FixedSlot) = launchCommand {
        val current = (repo.listFixedSlots() as? JsonArray)
            ?.filterIsInstance<JsonObject>()?.map { FixedSlot.fromMap(it) } ?: emptyList()
        val next = current.filter { it.id != slot.id } + slot
        handler.execute(ScheduleCommand.UpdateFixedSlots(slots = next.map { it.toRef() }), actor = CommandActor.HUMAN)
    }

    /** 删除：整单 rest→事件「已删除「name」」。 */
    fun deleteFixedSlot(slot: FixedSlot) = launchCommand {
        val current = (repo.listFixedSlots() as? JsonArray)
            ?.filterIsInstance<JsonObject>()?.map { FixedSlot.fromMap(it) } ?: emptyList()
        val rest = current.filter { it.id != slot.id }
        handler.execute(ScheduleCommand.UpdateFixedSlots(slots = rest.map { it.toRef() }), actor = CommandActor.HUMAN)
        _events.tryEmit(UiEvent(message = "已删除「${slot.name}」", durationMs = 3000))
    }

    // ── 假期与出行（§B：结构化值直传，编解码归命令层 R2） ──────────────────────

    /** 整列保存：[{start,end,label?}] 直传 update_settings（命令层编解码拦截）。 */
    fun saveExceptions(list: List<ExceptionWindow>) = launchCommand {
        val arr = JsonArray(list.map { w ->
            buildJsonObject {
                put("start", w.start)
                put("end", w.end)
                if (w.label != null) put("label", w.label)
            }
        })
        handler.execute(
            ScheduleCommand.UpdateSettings(values = mapOf(SettingsKeys.EXCEPTIONS to arr)),
            actor = CommandActor.HUMAN,
        )
    }

    /** 删除：整列 rest→事件「已删除该例外日」。 */
    fun deleteException(window: ExceptionWindow) = launchCommand {
        val raw = repo.settingsAll().toStringMap()
        val rest = parseExceptions(raw).filter { it != window }
        val arr = JsonArray(rest.map { w ->
            buildJsonObject {
                put("start", w.start)
                put("end", w.end)
                if (w.label != null) put("label", w.label)
            }
        })
        handler.execute(
            ScheduleCommand.UpdateSettings(values = mapOf(SettingsKeys.EXCEPTIONS to arr)),
            actor = CommandActor.HUMAN,
        )
        _events.tryEmit(UiEvent(message = "已删除该例外日", durationMs = 3000))
    }

    // ── 排程偏好（§C：字段级独立提交，改一个存一个） ───────────────────────────

    /** 单字段保存→事件 1s「已保存」（失败由 launchCommand catch→3s message）。 */
    fun savePrefs(values: Map<String, JsonElement>) = launchCommand {
        handler.execute(ScheduleCommand.UpdateSettings(values = values), actor = CommandActor.HUMAN)
        _events.tryEmit(UiEvent(message = "已保存", durationMs = 1000))
    }

    /** prefs 数字校验（intField UI 内联镜像）。 */
    fun notifyNotNumber() {
        _events.tryEmit(UiEvent(message = "请填数字", durationMs = 1000))
    }

    private fun launchCommand(block: suspend () -> Unit) = viewModelScope.launch {
        try {
            withContext(DbWriteQueue.dispatcher) { block() }
        } catch (e: ActionException) {
            _events.tryEmit(UiEvent(message = e.message, durationMs = 3000))
        }
    }

    private fun FixedSlot.toRef() = FixedSlotRef(
        id = id,
        name = name,
        weekdays = weekdays.map { it.toLong() },
        startMin = startMin.toLong(),
        endMin = endMin.toLong(),
    )

    private fun JsonObject.toStringMap(): Map<String, String> =
        entries.associate { (k, v) -> k to ((v as? JsonPrimitive)?.contentOrNull ?: "") }

    companion object {
        /** Manual DI Factory（AppContainer 纪律同 TodayViewModel）。 */
        val factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as ShiguangApplication
                SubpagesViewModel(app.container.repo, app.container.time)
            }
        }
    }
}
