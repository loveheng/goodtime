package app.shiguang.shiguang.ui.mcp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.shiguang.core.action.BridgeRepositoryPort
import app.shiguang.shiguang.ShiguangApplication
import app.shiguang.shiguang.service.McpController
import app.shiguang.shiguang.service.RelayKeys
import app.shiguang.shiguang.ui.schedule.UiEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * MCP 调试页轻 ViewModel（D15 同形，N8c；behavior/mcp_settings.md §A/§B 转正清单）：
 * 服务开关/连接地址/访问令牌走 McpController（服务面，非仓储）；relay 区四键走
 * settingsSet KV（内部键直写）。Dart ListenableBuilder(mcp) 对位=collect running 流。
 */
class McpViewModel(
    private val controller: McpController,
    private val repo: BridgeRepositoryPort,
) : ViewModel() {

    val running: StateFlow<Boolean> = controller.running
    val token: StateFlow<String?> = controller.token
    val endpoint: String = controller.endpoint

    data class RelayUiState(
        val loaded: Boolean = false,
        val enabled: Boolean = false,
        val endpoint: String = "",
        val username: String = "",
        val password: String = "",
    )

    private val _relay = MutableStateFlow(RelayUiState())
    val relay: StateFlow<RelayUiState> = _relay

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<UiEvent> = _events

    init {
        viewModelScope.launch {
            val ep = repo.settingsGet(RelayKeys.ENDPOINT).orEmpty()
            val user = repo.settingsGet(RelayKeys.USERNAME).orEmpty()
            val pass = repo.settingsGet(RelayKeys.PASSWORD).orEmpty()
            val enabled = repo.settingsGet(RelayKeys.ENABLED) == "1"
            _relay.value = RelayUiState(loaded = true, enabled = enabled, endpoint = ep, username = user, password = pass)
        }
    }

    /** 服务开关：开=enable（错误串 SnackBar 无底色——Dart 同）；关=disable（静默）。 */
    fun setRunning(on: Boolean) = viewModelScope.launch {
        if (on) {
            val error = controller.enable()
            if (error != null) {
                _events.tryEmit(UiEvent(message = error, durationMs = 3000))
            }
        } else {
            controller.disable()
        }
    }

    fun regenerateToken() = controller.regenerateToken()

    /**
     * relay 单键保存（onSubmitted 通道；endpoint/username trim、password 原文——
     * 密码含首尾空格合法语义，D23 过目照抄）+ 1s「已保存（下次打开服务生效）」。
     */
    fun saveRelay(key: String, raw: String) = viewModelScope.launch {
        val value = when (key) {
            RelayKeys.ENDPOINT, RelayKeys.USERNAME -> raw.trim()
            else -> raw
        }
        repo.settingsSet(buildRelayObject(key, value))
        when (key) {
            RelayKeys.ENDPOINT -> _relay.value = _relay.value.copy(endpoint = value)
            RelayKeys.USERNAME -> _relay.value = _relay.value.copy(username = value)
            RelayKeys.PASSWORD -> _relay.value = _relay.value.copy(password = value)
        }
        _events.tryEmit(UiEvent(message = "已保存（下次打开服务生效）", durationMs = 1000))
    }

    /** relay 开关（立即落盘 '1'/'0' + 1s 事件）。 */
    fun setRelayEnabled(on: Boolean) = viewModelScope.launch {
        _relay.value = _relay.value.copy(enabled = on)
        repo.settingsSet(buildRelayObject(RelayKeys.ENABLED, if (on) "1" else "0"))
        _events.tryEmit(UiEvent(message = "已保存（下次打开服务生效）", durationMs = 1000))
    }

    private fun buildRelayObject(key: String, value: String) = kotlinx.serialization.json.buildJsonObject {
        put(key, kotlinx.serialization.json.JsonPrimitive(value))
    }

    companion object {
        /** Manual DI Factory（AppContainer 纪律同 SettingsViewModel）。 */
        val factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as ShiguangApplication
                McpViewModel(app.container.mcpController, app.container.repo)
            }
        }
    }
}
