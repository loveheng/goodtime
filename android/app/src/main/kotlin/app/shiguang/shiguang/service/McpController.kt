package app.shiguang.shiguang.service

import app.shiguang.core.action.BridgeRepositoryPort
import app.shiguang.core.mcp.McpServer
import app.shiguang.core.mcp.McpToolFace
import app.shiguang.core.model.SettingsKeys
import app.shiguang.shiguang.platform.ShiguangLog
import app.shiguang.shiguang.update.RemoteConfigStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.SecureRandom

/**
 * MCP 服务总控（lib/service/mcp_controller.dart Kotlin 等价；behavior/mcp_settings.md
 * §B 转正清单，D23 裁决=本轮随行全件）：token 与开关持久化 + 内嵌回环 HTTP server。
 *
 * 暴露面口径（2026-10-15 拍板，schedule-app §11「MCP 暴露面」行）：生产通道 =
 * Binder 宿主进程内嵌入（ShiguangMcpBinderService，不经本控）；本控的 HTTP 服务
 * 仅绑 127.0.0.1 回环供 adb reverse 内部调试——不广播 mDNS、不展示 LAN 地址。
 * token/开关走 app_settings KV（`mcp_token`/`mcp_enabled` 为内部键，不经
 * update_settings 命令白名单）；前台保活=ShiguangForegroundService（specialUse 型，
 * 拍板④：Android 15 dataSync 6h 配额规避；启动 fail-open——Dart DEGRADE
 * [fg_unavailable] 同形：仅 HTTP 无保活不阻断服务）。disable 不停 FGS（管家日切
 * 承载件常驻——Dart disable() 同样不 stopService，镜像口径）。
 *
 * relay 传输装配挂账：MCP 请求 relay 中转（轴 C）随 relay 传输族轮接线（D10 四批
 * 既有内核件）；本控先落回环 HTTP 单通道。
 */
class McpController(
    /** KV 持久化端口（mcp_token/mcp_enabled 内部键；app_settings 表承载）。 */
    private val kv: BridgeRepositoryPort,

    /** 工具面工厂（宿主装配业务工具面 + 天气等后端；内核不沾仓储面）。 */
    private val toolFaceFactory: () -> McpToolFace,

    /** 远程配置缓存（MCP instructions 热更入口；RemoteConfigStore 快照现取）。 */
    private val remoteConfig: RemoteConfigStore,

    /** 前台保活启动面（AppContainer 注入 FGS start——fail-open 由本控包裹）。 */
    private val startKeepalive: () -> Unit,

    /** AI 建言推送回调（suggest_user_setting → 壳层 SnackBar；AppContainer 总线注入）。 */
    private val onSuggestion: ((args: JsonObject) -> Unit)? = null,

    /** 启动恢复异步域（AppContainer.applicationScope）。 */
    private val scope: CoroutineScope,
    /** 绑定端口（默认 8765；测试注入 0 取临时端口）。 */
    private val bindPort: Int = DEFAULT_PORT,
) {

    private val mutex = Mutex()
    private var server: McpServer? = null
    private var transport: HttpTransport? = null

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running

    private val _token = MutableStateFlow<String?>(null)
    val token: StateFlow<String?> = _token

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError

    /** 实际端口（未运行=绑定端口；运行=传输实听端口——注入 0 时为临时端口）。 */
    val port: Int get() = transport?.port ?: bindPort

    /** 调试端点（仅回环可达；桌面经 `adb reverse tcp:8765 tcp:8765` 接入）。 */
    val endpoint: String get() = "http://127.0.0.1:$port/mcp"

    /** 冷启动装配：读/生成 token，并按上次开关状态自动恢复服务（首 Activity 段调用）。 */
    fun restoreIfNeeded() {
        scope.launch {
            val saved = kv.settingsGet(KEY_TOKEN)
            if (saved.isNullOrEmpty()) {
                _token.value = generateToken()
                kv.settingsSet(buildJsonObject { put(KEY_TOKEN, _token.value!!) })
            } else {
                _token.value = saved
            }
            if (kv.settingsGet(KEY_ENABLED) != "1") return@launch
            val error = enable()
            if (error != null) {
                ShiguangLog.w("mcp", "冷启动自动恢复失败: $error")
            }
        }
    }

    /** 重新生成访问令牌（24 位 hex Random.secure 落盘——Dart _generateToken 同形）。 */
    fun regenerateToken() {
        scope.launch {
            _token.value = generateToken()
            kv.settingsSet(buildJsonObject { put(KEY_TOKEN, _token.value!!) })
        }
    }

    /** 打开 MCP 服务。返回 null 表示成功，否则为错误信息。 */
    suspend fun enable(): String? = mutex.withLock {
        _lastError.value = null
        try {
            // 前台服务保活（Doze 下 MCP 应答须持 CPU/Wi-Fi 锁）；平台未就绪时跳过，
            // 仅启 HTTP（fail-open，不阻断服务——Dart [fg_unavailable] 同形）。
            try {
                startKeepalive()
            } catch (e: Exception) {
                // DEGRADE: [fg_unavailable] 前台服务不可用（平台未就绪/权限撤销）——仅 HTTP 无保活
                ShiguangLog.w("mcp", "[DEGRADE][fg_unavailable] 前台服务未启动: ${e.message}")
            }
            if (server == null) {
                server = McpServer(
                    toolFace = toolFaceFactory(),
                    instructionsProvider = ::instructions,
                    onSuggestion = onSuggestion,
                )
            }
            if (transport == null) transport = HttpTransport(server!!, tokenProvider = { _token.value })
            transport!!.start(scope, bindPort)
            // relay 传输装配挂账（轴 C）：随 relay 传输族轮接线，fail-open 单通道先行
            kv.settingsSet(buildJsonObject { put(KEY_ENABLED, "1") })
        } catch (e: Exception) {
            _lastError.value = "$e"
            shutdown()
            return _lastError.value
        }
        _running.value = true
        return null
    }

    suspend fun disable() {
        mutex.withLock {
            shutdown()
            kv.settingsSet(buildJsonObject { put(KEY_ENABLED, "0") })
            _running.value = false
        }
    }

    /** 全停回环 HTTP（FGS 不停——管家日切承载件常驻，见类注）。 */
    private fun shutdown() {
        try {
            transport?.stop()
        } catch (_: Exception) {
            // 服务可能已停止
        }
        transport = null
        server = null
    }

    /**
     * 系统提示词热更入口：远程下发的 mcpInstructions 可覆盖内置政策精华；
     * 用户自填身份画像（identity_prompt）始终拼接待尊重（即配即用，每次 initialize 现取）。
     */
    private suspend fun instructions(): String? {
        val remote = remoteConfig.current.mcpInstructions
        val base = if (!remote.isNullOrBlank()) remote.trim() else McpServer.BUILTIN_INSTRUCTIONS
        val prompt = kv.settingsGet(SettingsKeys.IDENTITY_PROMPT)
        if (!prompt.isNullOrBlank() && prompt.trim().isNotEmpty()) {
            return "$base\n\n" +
                "【用户身份画像】（由用户在 app 内填写，描述其自身身份与处境，排程时务必尊重）\n$prompt"
        }
        return base
    }

    private fun generateToken(): String {
        val rnd = SecureRandom()
        return buildString { repeat(TOKEN_LENGTH) { append(HEX[rnd.nextInt(16)]) } }
    }

    companion object {
        const val DEFAULT_PORT = 8765
        const val KEY_TOKEN = "mcp_token"
        const val KEY_ENABLED = "mcp_enabled"
        const val TOKEN_LENGTH = 24
        const val HEX = "0123456789abcdef"
    }
}

/** relay 配置四键（lib/service/relay_client.dart RelayKeys Kotlin 等价——本页/控制面共用）。 */
object RelayKeys {
    const val ENABLED = "relay_enabled"
    const val ENDPOINT = "relay_endpoint"
    const val USERNAME = "relay_username"
    const val PASSWORD = "relay_password"
}
