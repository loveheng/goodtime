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
import app.shiguang.core.model.SettingsKeys
import app.shiguang.shiguang.ShiguangApplication
import app.shiguang.shiguang.service.McpController
import app.shiguang.shiguang.update.RemoteConfigStore
import app.shiguang.shiguang.ui.schedule.UiEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * 设置页轻 ViewModel（D15 同形，N8a）：双源重载（repo.revision 写面+suggestionBus
 * AI 建言推送——app_shell_sheets §A 总线）；命令面=update_settings（作息/外观/采纳）
 * +建议条目 KV 过滤回写；exportAll 数据出口透传（IO 分发）。
 */
class SettingsViewModel(
    /** 仓储读端口（K8 白名单族——读走 StateFlow 消费端，写=命令面 handler）。 */
    val repo: BridgeRepositoryPort,

    /** AI 建言推送总线（AppContainer.suggestionBus；测试无总线传 null→仅 revision 驱动）。 */
    suggestionBus: SharedFlow<JsonObject>? = null,

    /** 远程配置缓存（公告行快照源——N8a 挂账接线，随 N8c update 轮转正）。 */
    private val remoteConfig: RemoteConfigStore? = null,

    /** MCP 服务总控（调试菜单行运行态——N8a 静态 subtitle 挂账接线）。 */
    mcpController: McpController? = null,
) : ViewModel() {

    /** MCP 回环服务运行态（调试菜单行 subtitle 双态消费面；无控制器（测试）=恒假流）。 */
    private val mcpRunningFallback = MutableStateFlow(false)
    val mcpRunning: StateFlow<Boolean> = mcpController?.running ?: mcpRunningFallback

    private val handler = CommandHandler(repo)

    data class SuggestionItem(
        val id: String,
        val key: String,
        val reason: String,
        val suggestedValue: String?,
    )

    data class SettingsUiState(
        val settings: JsonObject? = null,
        val suggestions: List<SuggestionItem> = emptyList(),
        val loading: Boolean = true,
        val announcement: String? = null,
    )

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<UiEvent> = _events

    private suspend fun reload() {
        val settings = withContext(Dispatchers.IO) { ScheduleQueries(repo).getSettings() }
        val suggestions = withContext(Dispatchers.IO) { readSuggestions() }
        // 公告=RemoteConfig 快照现取（无监听需求——检查更新写缓存后随本页 reload 刷新）
        val announcement = remoteConfig?.current?.announcement
        _uiState.value = SettingsUiState(settings = settings, suggestions = suggestions, loading = false, announcement = announcement)
    }

    init {
        // 双源并行 collect（任一变化→重载；重载幂等）：revision（D16）+AI 建言总线（§A）
        // ——bus 无总线（测试 null）时仅 revision 驱动（combine 需双流首帧，空 SharedFlow
        // 恒挂起会饿死装载，故用并行 collect 而非 combine）。
        viewModelScope.launch {
            repo.revision.collectLatest { _ -> reload() }
        }
        suggestionBus?.let { bus ->
            viewModelScope.launch {
                bus.collectLatest { _ -> reload() }
            }
        }
    }

    private suspend fun readSuggestions(): List<SuggestionItem> {
        val raw = repo.settingsGet(SettingsKeys.AI_SETTING_SUGGESTIONS) ?: return emptyList()
        if (raw.isEmpty()) return emptyList()
        val arr = runCatching { Json.parseToJsonElement(raw) as? JsonArray }.getOrNull() ?: return emptyList()
        return arr.filterIsInstance<JsonObject>().map { e ->
            SuggestionItem(
                id = (e["id"] as? JsonPrimitive)?.contentOrNull ?: "",
                key = (e["key"] as? JsonPrimitive)?.contentOrNull ?: "",
                reason = (e["reason"] as? JsonPrimitive)?.contentOrNull ?: "",
                suggestedValue = (e["suggested_value"] as? JsonPrimitive)?.contentOrNull
                    ?.takeIf { it.isNotEmpty() },
            )
        }
    }

    // ── 命令面 ─────────────────────────────────────────────────────────────────

    /** 采纳（§B）：建议值写入对应设置键（命令层白名单/uiOnly 拦截）→ 按 key 移除 → 2s 事件。 */
    fun applySuggestion(item: SuggestionItem) = launchCommand {
        val value = item.suggestedValue ?: return@launchCommand
        handler.execute(
            ScheduleCommand.UpdateSettings(values = mapOf(item.key to JsonPrimitive(value))),
            actor = CommandActor.HUMAN,
        )
        removeSuggestionBy { (it["key"] as? JsonPrimitive)?.contentOrNull == item.key }
        _events.tryEmit(UiEvent(message = "已采纳：${item.key} = $value", durationMs = 2000))
    }

    /** 忽略：按 id 移除建议条目（无事件）。 */
    fun dismissSuggestion(item: SuggestionItem) = launchCommand {
        removeSuggestionBy { (it["id"] as? JsonPrimitive)?.contentOrNull == item.id }
    }

    /** 作息边界（TimePicker 即改即存）：分钟数直写。 */
    fun setTime(key: String, minutes: Int) = launchCommand {
        handler.execute(
            ScheduleCommand.UpdateSettings(values = mapOf(key to JsonPrimitive(minutes))),
            actor = CommandActor.HUMAN,
        )
    }

    /** 外观三档（theme_mode uiOnly 键——仅人可写）。 */
    fun setThemeMode(mode: String) = launchCommand {
        handler.execute(
            ScheduleCommand.UpdateSettings(values = mapOf(SettingsKeys.THEME_MODE to JsonPrimitive(mode))),
            actor = CommandActor.HUMAN,
        )
    }

    /** 下滑隐藏导航栏开关（nav_auto_hide uiOnly 键——默认 false=常驻）。 */
    fun setNavAutoHide(enabled: Boolean) = launchCommand {
        handler.execute(
            ScheduleCommand.UpdateSettings(values = mapOf(SettingsKeys.NAV_AUTO_HIDE to JsonPrimitive(enabled))),
            actor = CommandActor.HUMAN,
        )
    }

    /** 建议条目移除通道（§B：settingsGet→JSON 过滤→settingsSet 回写——采纳/忽略共用）。 */
    private suspend fun removeSuggestionBy(test: (JsonObject) -> Boolean) = withContext(Dispatchers.IO) {
        val raw = repo.settingsGet(SettingsKeys.AI_SETTING_SUGGESTIONS) ?: return@withContext
        val arr = runCatching { Json.parseToJsonElement(raw) as? JsonArray }.getOrNull() ?: return@withContext
        val kept = arr.filterIsInstance<JsonObject>().filterNot(test)
        repo.settingsSet(buildJsonObject {
            put(SettingsKeys.AI_SETTING_SUGGESTIONS, JsonArray(kept).toString())
        })
    }

    /** 复制成功反馈（§B「已复制到剪贴板」——Dart 默认 4s→原生 SEC3 归档注记）。 */
    fun notifyExportCopied() {
        _events.tryEmit(UiEvent(message = "已复制到剪贴板", durationMs = 3000))
    }

    /** 数据出口（§B）：全量导出 JSON 字符串（IO 分发；UI 弹框展示+复制）。 */
    suspend fun exportAllJson(): String = withContext(Dispatchers.IO) {
        repo.exportAll().toString()
    }

    private fun launchCommand(block: suspend () -> Unit) = viewModelScope.launch {
        try {
            withContext(DbWriteQueue.dispatcher) { block() }
        } catch (e: ActionException) {
            _events.tryEmit(UiEvent(message = e.message, durationMs = 3000))
        }
    }

    companion object {
        /** Manual DI Factory（AppContainer 纪律同 TodayViewModel；suggestionBus 由壳层注入）。 */
        val factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as ShiguangApplication
                SettingsViewModel(app.container.repo, app.container.suggestionBus, app.container.remoteConfig, app.container.mcpController)
            }
        }
    }
}
