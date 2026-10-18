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
 * 与其差异仅：通道名/entrypoint 名/包名；且 shiguang 对 **所有 tools/call**（回调已
 * 注册时）走 `_async`+onEvent 终帧切片（F1，修并行排队撞 B1 超时），goodshare 是
 * 按工具白名单；answerReverse 通道保留契约位。
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

    /** 宿主回注的回调（F1 后 onEvent 承载 _async 终帧转发；volatile 防跨线程可见性）。 */
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
            // F1（_async+onEvent 终帧切片，契约 §1.2.2）：回调已注册且为 tools/call 时，
            // call 立即回 _async 占位帧（不占 5s Binder 预算），真实结果由 dispatch 的
            // Result 回调经 callback.onEvent 推同 id 终帧——并行调用不再排队撞 B1 超时。
            // 非 tools/call（initialize/tools/list，均 <1s）与回调缺失时维持同步路径（兼容旧宿主）。
            if (callback != null && extractMethod(jsonRpcRequest) == "tools/call") {
                android.util.Log.i(TAG, "[F1] async dispatch posted: id=$reqId")
                mainHandler.post {
                    try {
                        ch.invokeMethod(
                            "dispatch",
                            jsonRpcRequest,
                            object : MethodChannel.Result {
                                override fun success(result: Any?) {
                                    val frame = result as? String
                                    android.util.Log.i(
                                        TAG,
                                        "[F1] dispatch done: id=$reqId emit=${frame != null}",
                                    )
                                    if (frame == null) {
                                        emitTerminalFrame(
                                            callback,
                                            errorJson(reqId, -32603, "Dart 返回空响应"),
                                        )
                                    } else {
                                        emitTerminalFrame(callback, frame)
                                    }
                                }

                                override fun error(code: String, msg: String?, details: Any?) {
                                    emitTerminalFrame(
                                        callback,
                                        errorJson(reqId, -32603, "MCP Binder 调用失败: ${msg ?: code}"),
                                    )
                                }

                                override fun notImplemented() {
                                    emitTerminalFrame(
                                        callback,
                                        errorJson(reqId, -32603, "Dart 未实现 dispatch"),
                                    )
                                }
                            },
                        )
                    } catch (e: Exception) {
                        emitTerminalFrame(
                            callback,
                            errorJson(reqId, -32603, "MCP Binder 调用失败: ${e.message}"),
                        )
                    }
                }
                return asyncResultJson(reqId)
            }
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
            // 通知 Dart 侧回调通道已建立（注册后 tools/call 即走 _async 切片）。
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

        private const val TAG = "ShiguangMcpBinder"

        /** _async 占位帧（§1.2.2）：call 立即返回，真实终帧经 onEvent 推送。 */
        private fun asyncResultJson(id: Any?): String = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", if (id == null) JSONObject.NULL else id)
            put("result", JSONObject().apply { put("_async", true) })
        }.toString()

        /** 从请求 JSON 解析 method（缺失/解析失败返回 null），供 _async 分流判定。 */
        private fun extractMethod(request: String): String? = try {
            JSONObject(request).optString("method", null)
        } catch (_: Exception) {
            null
        }

        /**
         * 终帧经 [IMcpCallback.onEvent] 推给宿主（F1 义务②：推失败打 [DEGRADE] 日志，
         * 防悬挂可观测——goodhub 侧 _pendingTerminal 无终帧即悬挂，靠其 idle 超时兜底）。
         */
        private fun emitTerminalFrame(cb: IMcpCallback?, frame: String) {
            val target = cb
            if (target == null) {
                android.util.Log.w(TAG, "[DEGRADE] 终帧无处可推（回调已注销）: ${frame.take(200)}")
                return
            }
            try {
                target.onEvent(frame)
            } catch (e: Exception) {
                android.util.Log.w(TAG, "[DEGRADE] onEvent 推帧失败（宿主回调断连？）: ${e.message}")
            }
            android.util.Log.i(TAG, "[F1] terminal emitted: ${frame.take(80)}")
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
