package app.shiguang.shiguang

import android.content.Context
import app.shiguang.core.action.NativeSqlRepository
import app.shiguang.core.model.SqlStore
import app.shiguang.core.model.TimeProvider
import app.shiguang.core.model.collab.AccountSession
import app.shiguang.core.model.collab.AccountStatus
import app.shiguang.core.model.collab.FriendshipTicket
import app.shiguang.core.model.collab.IdentityStore
import app.shiguang.core.model.collab.KeyStore
import app.shiguang.core.model.collab.SecretStore
import app.shiguang.core.model.collab.relay.BundleComposeKit
import app.shiguang.core.model.collab.relay.RelayConfig
import app.shiguang.core.model.collab.relay.RelaySession
import app.shiguang.core.model.collab.relay.buildBundleComposeKit
import app.shiguang.core.model.collab.relay.buildFriendsSession
import app.shiguang.core.model.collab.relay.relayHttpClient
import app.shiguang.core.mcp.NativeToolFace
import app.shiguang.shiguang.data.AndroidIdentityStore
import app.shiguang.shiguang.data.AndroidSecretStore
import app.shiguang.shiguang.data.AndroidSqlStore
import app.shiguang.shiguang.platform.AndroidTimeProvider
import app.shiguang.shiguang.platform.OkWeatherProvider
import app.shiguang.shiguang.platform.ShiguangLog
import app.shiguang.shiguang.service.McpController
import app.shiguang.shiguang.service.ShiguangForegroundService
import app.shiguang.shiguang.ui.nav.ShiguangRoute
import app.shiguang.shiguang.update.RemoteConfigStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient

/**
 * Manual DI 容器（§9「对象图与 DI」+「启动时序隔离」）：Application.onCreate
 * 构造的组合根单例——只装配 Core（SQLite 连接/命令面/时钟/AIDL 面）；图形、
 * Compose 主题与页面预载推迟到首个 Activity.onCreate()。无 UI 进程可独立
 * 构造（binder 服务直接消费本容器，不经任何 UI 初始化路径）。
 *
 * 协程域：根作用域 SupervisorJob + 统一异常 handler（§9 异常纪律）；子域一律
 * 经本作用域派生，禁裸建全局作用域。ViewModel 一律经统一 Factory 接入 [repo]
 * 与命令面，禁无参默认构造与 Composable 内 new 单例（P4 接入）。
 */
class AppContainer(context: Context) {

    /** 根协程域：SupervisorJob + 统一 handler（异常→日志门面，只记录不崩进程）。 */
    val applicationScope: CoroutineScope = CoroutineScope(
        SupervisorJob() + ShiguangLog.coroutineHandler(),
    )

    /** 作息时钟端口（UI/VM 禁直取系统钟，一律经此）。 */
    val time: TimeProvider = AndroidTimeProvider(context.applicationContext)

    /** 既有 sqflite 同名库（R1 双写者：与 Dart 主 isolate 共文件，WAL+busy_timeout=5000）。
     *  内部 dogfood 构建（applicationId .dogfood 后缀）切独立库文件——严禁与正式环境库共写
     *  （todo P5 拍板；flavor 定义见 app/build.gradle.kts channel 维度）。 */
    private val store: SqlStore = AndroidSqlStore(
        context.applicationContext,
        dbName = if (context.applicationContext.packageName.endsWith(".dogfood"))
            "shiguang_dogfood.db" else "shiguang.db",
    )

    /** 读面/命令面唯一仓储（BridgeRepositoryPort；写经单线程写队列 dbWriteDispatcher）。 */
    val repo: NativeSqlRepository = NativeSqlRepository(store)

    /** 天气投影（单例保 TTL 缓存跨调用存续）。 */
    private val weatherProvider = OkWeatherProvider()

    /** 原生工具面（weather=OkWeatherProvider——§11 天气投影：只进提案推理与态势句，
     *  机械层无感；失败静默降级；lambda 适配 hook 契约）。 */
    val toolFace: NativeToolFace = NativeToolFace(repo, weather = { city -> weatherProvider(city) })

    /** 密钥安全区（MSK/身份私钥承载；collab KeyStore/IdentityStore 消费方=D27 装配）。 */
    val secretStore: SecretStore = AndroidSecretStore(context.applicationContext)

    /**
     * AI 建言推送总线（app_shell_sheets.md §A 转正清单）：suggest_user_setting 调用
     * 经 McpServer.onSuggestion 钩子（binder 服务挂接）广播给壳层——SnackBar 8s+
     * 「去设置」。同进程共享（binder 服务与 UI 同容器单例）。
     */
    val suggestionBus: MutableSharedFlow<JsonObject> = MutableSharedFlow(extraBufferCapacity = 8)

    /** 分享入册完成总线（浮层族轮 D25：Gate 消费 → 壳层唤起挂载 sheet——factId）。 */
    val mountedFactId: MutableSharedFlow<String> = MutableSharedFlow(extraBufferCapacity = 4)

    /**
     * 基线采集浮层总线（capture stage3/4 `open_overlay`：name[:id]——像素 diff 验收轮）。
     * 壳层 collect 唤起对应浮层合成态（block_sheet/block_sheet_edit/fact_sheet）。
     */
    val baselineOverlayBus: MutableSharedFlow<String> = MutableSharedFlow(extraBufferCapacity = 4)

    /**
     * 扫码页邀请提交口（scan.md §A 消标，D27）：真实会话提交——未登录经
     * ensureAccessToken 抛 IllegalStateException，ScanViewModel 映射 401 文案
     * （「登录态已过期…重新登录」路径，Dart 装配语义同源）。
     */
    fun collabInviteSubmitter(): suspend (FriendshipTicket) -> Unit = { ticket ->
        relaySession.submitInvite(ticket)
    }

    // ---- 协同栈（D27 裁决施工解冻：lib/app_services.dart 对位——构造不触网
    // 不读安全区，真实读写发生在登录后/首消费时，守启动时序分层⑤） ----

    /** relay 基址（编译期覆盖 > 模拟器回环兜底；Dart dart-define>relay.env>兜底
     *  三层的原生等价=BuildConfig 注入+兜底，relay.env 文件层属 dotenv 机制不复制）。 */
    val relayConfig: RelayConfig by lazy {
        RelayConfig(BuildConfig.RELAY_BASE_URL.ifEmpty { "http://10.0.2.2:8080" })
    }

    /** relay 单例 HTTP 客户端（幂等双头拦截器 X-Client-Msg-Id/X-Client-Ts）。 */
    private val relayClient: OkHttpClient by lazy { relayHttpClient() }

    /** 密钥托管（KeyStore(SecretStore)；构造不读安全区——ensureReady 登录后幂等触发）。 */
    val collabKeys: KeyStore by lazy { KeyStore(secretStore) }

    /** 身份记录存储（Dart SecureIdentityStore 对位；生产=安全区适配）。 */
    val identityStore: IdentityStore by lazy { AndroidIdentityStore(secretStore) }

    /** 协同账号会话（登录态单一来源；Dart AppServices.account 对位，惰性构造不触网）。 */
    val accountSession: AccountSession by lazy {
        AccountSession(relayClient, relayConfig.baseUrl, collabKeys, identityStore, secretStore)
    }

    /** 中继会话单例（好友域/邀请提交共用；无会话内状态可安全复用）。 */
    private val relaySession: RelaySession by lazy {
        RelaySession(relayClient, relayConfig.baseUrl, identityStore, accountSession)
    }

    /**
     * 发起套件装配（member_calendar/compose 页消费；null=本机未注册协同身份——
     * UI 转注册引导不降级，登录态由 UI 先行判定）。
     */
    suspend fun loadBundleComposeKit(): BundleComposeKit? =
        buildBundleComposeKit(store, accountSession, identityStore, relayConfig.baseUrl, relayClient)

    /** 好友域会话装配（friends 页消费；null 同口径）。 */
    suspend fun loadFriendsSession(): RelaySession? =
        buildFriendsSession(accountSession, identityStore, relayConfig.baseUrl, relayClient)

    /** 协同仓储读面（member_calendar/decision/audit/canvas 消费；既有库直开）。 */
    val collabRepository: app.shiguang.core.model.collab.CollabBundleRepository by lazy {
        app.shiguang.core.model.collab.CollabBundleRepository(store)
    }

    /** 协同离线发件箱（响应/闭环动作落库面；联网弹出=常驻调度）。 */
    val outboxRepository: app.shiguang.core.model.collab.OutboxRepository by lazy {
        app.shiguang.core.model.collab.OutboxRepository(store)
    }

    /** K_bundle 取钥源（决策矩阵回放/发起套件消费；vault 未解锁=半盲 null）。 */
    val bundleKeySource: app.shiguang.core.model.collab.BundleKeySource by lazy {
        app.shiguang.core.model.collab.BundleKeySource(store, collabKeys)
    }

    init {
        // 登录即备钥（Dart attachCollabLifecycle._onAccountChange 对位）：ensureReady
        // 幂等（安全区已有即载入，没有即生成落安全区）；登出无动作（常驻回路未挂）。
        applicationScope.launch {
            accountSession.status.collect { status ->
                if (status == AccountStatus.LOGGED_IN) {
                    runCatching { collabKeys.ensureReady() }
                        .onFailure {
                            ShiguangLog.w("AppContainer", "collab keys ensureReady failed: ${it.message}")
                        }
                }
            }
        }
    }

    /**
     * 远程配置缓存（update_page.md §B 转正清单，D23 裁决=容器装配注入）：公告/
     * MCP instructions 热更项；load 时机=首 Activity 装配段（ Dart bootstrap 对位）。
     */
    val remoteConfig: RemoteConfigStore = RemoteConfigStore(context.applicationContext)

    /**
     * 分享转投池（app_gate.md §B 转正清单，D23 裁决 §D.1=进程内直投）：
     * ACTION_SEND EXTRA_TEXT 原文挂此（零解析纪律在命令层），Gate 首帧后消费
     * （Dart AppServices.pendingShare ValueNotifier 对位）。写入面收口
     * [postPendingShare]/[clearPendingShare]——对外只读 StateFlow（K5）。
     */
    private val _pendingShare = MutableStateFlow<String?>(null)
    val pendingShare: StateFlow<String?> = _pendingShare

    /** 分享入池（Activity ACTION_SEND 直投；空文本由消费端丢弃）。 */
    fun postPendingShare(text: String) {
        _pendingShare.value = text
    }

    /** 消费完成清池（GateViewModel 消费 finally 调——挂起语义留池）。 */
    fun clearPendingShare() {
        _pendingShare.value = null
    }

    /**
     * 导航请求总线（原生调试走查 nav 动作通道；壳层消费——release 无发射方零行为）。
     */
    val navRequests = MutableSharedFlow<ShiguangRoute>(extraBufferCapacity = 4)

    /**
     * MCP 回环调试服务总控（mcp_settings.md §B 转正清单，D23 裁决=本轮随行全件）：
     * HTTP 仅绑 127.0.0.1 供 adb reverse 内部调试；restore 时机=首 Activity 装配段
     * （D23 裁决 §D.2——Application.onCreate 只做 Core，守启动分层⑤）。
     */
    val mcpController: McpController = McpController(
        kv = repo,
        toolFaceFactory = { toolFace },
        remoteConfig = remoteConfig,
        startKeepalive = {
            context.applicationContext.startForegroundService(
                android.content.Intent(context.applicationContext, ShiguangForegroundService::class.java),
            )
        },
        onSuggestion = { suggestionBus.tryEmit(it) },
        scope = applicationScope,
    )
}
