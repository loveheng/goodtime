package app.shiguang.shiguang

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.zzh.goodhub.mcp.IMcpCallback
import com.zzh.goodhub.mcp.IMcpChannel
import io.flutter.FlutterInjector
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.dart.DartExecutor
import io.flutter.embedding.engine.loader.FlutterLoader
import io.flutter.plugins.GeneratedPluginRegistrant
import io.flutter.plugin.common.MethodChannel
import org.json.JSONObject
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * 拾光本地 Binder 插件服务（server 端，goodhub 契约 thirdconnect.md §1.2.2）。
 *
 * 角色边界（§11.3·1）：仅实现 [IMcpChannel.Stub]；不 bindService、不持 DeathRecipient
 * （连接生命周期 / 挂起队列状态机归 goodhub 宿主 BinderTransport）。
 *
 * 触达 Dart：持有独立 headless [FlutterEngine]（BIND_AUTO_CREATE 随调随起，满足
 * §1.2.5 随调随起——app 被杀也能被拉起跑 MCP），经 MethodChannel `shiguang/mcp_binder`
 * 调 Dart 后台 entrypoint（[mcpBinderMain]）→ [com.zzh.goodhub.mcp] 通道 →
 * McpServer.handleJsonRpc。模式照抄 goodshare McpBinderService（已验证范本），
 * 与其差异仅：通道名/entrypoint 名/包名（shiguang 工具面无 >5s 长任务，MVP 不走
 * `_async`+onEvent 终帧切片，answerReverse 通道保留契约位）。
 *
 * 线程：AIDL `call` 由 goodhub 在 Binder 线程触发（非主线程），直接阻塞等待 Dart 回传
 * 不触发 ANR（§1.2.3）；首调前经 [ready] 信号解除引擎未就绪竞态。
 */
class ShiguangMcpBinderService : Service() {

    private var flutterEngine: FlutterEngine? = null
    @Volatile
    private var channel: MethodChannel? = null

    /** 引擎主线程 Handler：MethodChannel.invokeMethod 必须在其上执行（@UiThread），
     *  call()/registerCallback() 均在 Binder 线程触发，须 post 过来。 */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 宿主回注的回调（MVP 未用 onEvent 推帧，保留契约位；volatile 防跨线程可见性）。 */
    @Volatile
    private var callback: IMcpCallback? = null

    /** Dart 后台 entrypoint 就绪信号：注册完 MethodChannel 处理器后放行 call。 */
    private val ready = CompletableFuture<Boolean>()

    private val binder = object : IMcpChannel.Stub() {
        override fun call(jsonRpcRequest: String): String {
            val reqId = extractId(jsonRpcRequest) // B3：先取 id，供错误帧回显
            // 等 Dart entrypoint 注册完处理器（上限 5s，对齐 goodhub 冷启 5s，R2）。
            if (!ready.completeFutureOrDefault(false, 5, TimeUnit.SECONDS)) {
                return errorJson(reqId, -32000, "MCP Binder 引擎未就绪（Dart entrypoint 超时）")
            }
            // 本 Flutter 版 MethodChannel.invokeMethod 返回 void，结果经 Result 回调投递；
            // 用 CompletableFuture 在 Binder 线程阻塞等待 Dart 回传（§1.2.3：Binder 线程非主线程，不 ANR）。
            // invokeMethod 必须在引擎主线程执行（@UiThread），故 post 到 mainHandler。
            val ch = channel
            if (ch == null) return errorJson(reqId, -32603, "MCP Binder 通道未建立")
            val resultFuture = CompletableFuture<String?>()
            mainHandler.post {
                try {
                    ch.invokeMethod(
                        "dispatch",
                        jsonRpcRequest,
                        object : MethodChannel.Result {
                            override fun success(result: Any?) {
                                resultFuture.complete(result as? String)
                            }
                            override fun error(code: String, msg: String?, details: Any?) {
                                resultFuture.completeExceptionally(Exception(msg ?: code))
                            }
                            override fun notImplemented() {
                                resultFuture.completeExceptionally(Exception("Dart 未实现 dispatch"))
                            }
                        },
                    )
                } catch (e: Exception) {
                    resultFuture.completeExceptionally(e)
                }
            }
            // B1：加 per-call ≤5s 超时，避免 Dart 不回（工具死锁/引擎异常）时 Binder 线程永久阻塞（致命）。
            return try {
                val result = resultFuture.get(5, TimeUnit.SECONDS)
                result ?: errorJson(reqId, -32603, "Dart 返回空响应")
            } catch (e: TimeoutException) {
                errorJson(reqId, -32000, "MCP Binder call timeout")
            } catch (e: Exception) {
                errorJson(reqId, -32603, "MCP Binder 调用失败: ${e.message}")
            }
        }

        override fun registerCallback(cb: IMcpCallback?) {
            callback = cb
            // 通知 Dart 侧回调通道已建立（onEvent 推帧为后续切片，当前仅可观测标记）。
            // invokeMethod 须引擎主线程执行。
            mainHandler.post { runCatching { channel?.invokeMethod("hostCallbackReady", cb != null) } }
        }

        override fun answerReverse(jsonRpcResponse: String) {
            // MVP 未发反向请求，宿主不应回程；兜底静默（不崩插件进程）。
        }
    }

    override fun onCreate() {
        super.onCreate()
        initEngine()
    }

    private fun initEngine() {
        if (flutterEngine != null) return
        // 独立 headless 引擎（不挂 UI renderer）；与主 isolate 共用 App 进程，随 Binder 拉起。
        // FlutterEngine 构造器内部已通过 FlutterInjector 初始化 FlutterLoader（含资源抽取），
        // 故路径须取「引擎所用的同一个 loader」，绝不可 new FlutterLoader()——新实例未 init →
        // findAppBundlePath 读 flutterApplicationInfo=null → NPE（随调随起全新进程必现）。
        val engine = FlutterEngine(this)
        GeneratedPluginRegistrant.registerWith(engine) // sqflite/path_provider 等插件注入
        flutterEngine = engine
        val loader = FlutterInjector.instance().flutterLoader()
        if (!loader.initialized()) {
            // 兜底：若引擎构造未触发初始化（跨 Flutter 版本差异），显式与 FlutterActivity 同款配对。
            loader.startInitialization(applicationContext)
            loader.ensureInitializationComplete(applicationContext, null)
        }
        val ch = MethodChannel(engine.dartExecutor.binaryMessenger, CHANNEL)
        channel = ch
        ch.setMethodCallHandler { call, result ->
            when (call.method) {
                "ready" -> {
                    ready.complete(true)
                    result.success(null)
                }
                else -> result.notImplemented()
            }
        }
        engine.dartExecutor.executeDartEntrypoint(
            DartExecutor.DartEntrypoint(
                loader.findAppBundlePath(),
                "package:shiguang/mcp_binder_entrypoint.dart",
                ENTRYPOINT,
            ),
        )
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        try {
            flutterEngine?.destroy()
        } catch (_: Exception) {
            // 引擎销毁异常忽略。
        }
        flutterEngine = null
        channel = null
        callback = null
    }

    companion object {
        const val CHANNEL = "shiguang/mcp_binder"
        const val ENTRYPOINT = "mcpBinderMain"

        /** JSON-RPC error 帧（Binder call 需返回字符串；回显请求 id，见 B3）。 */
        private fun errorJson(id: Any?, code: Int, message: String): String =
            JSONObject().apply {
                put("jsonrpc", "2.0")
                // id 为 null / JSONObject.NULL → 写 JSON null；number/string 原样（自动加引号）
                put("id", if (id == null) JSONObject.NULL else id)
                put(
                    "error",
                    JSONObject().apply {
                        put("code", code)
                        put("message", message)
                    },
                )
            }.toString()

        /** 从请求 JSON 解析 id（number/string/null），供错误帧回显（B3）。 */
        private fun extractId(request: String): Any? = try {
            JSONObject(request).opt("id")
        } catch (_: Exception) {
            null
        }

        /**
         * [CompletableFuture.get] 超时兜底（Kotlin 1.8 无 `completeOnTimeout`，1.9+ 才有）：
         * 等 [ready] 至多 [timeout]；到点未就绪返回 [default]（永不抛，避免 call 挂死）。
         */
        private fun CompletableFuture<Boolean>.completeFutureOrDefault(
            default: Boolean,
            timeout: Long,
            unit: TimeUnit,
        ): Boolean = try {
            get(timeout, unit)
        } catch (_: Exception) {
            default
        }
    }
}
