package app.shiguang.shiguang

import android.os.Handler
import android.os.Looper
import com.zzh.goodhub.mcp.IMcpCallback
import com.zzh.goodhub.mcp.plugin.GoodHubPluginService
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
 * 2026-10-20 迁移：从「手搓 AIDL + 手写 IMcpChannel.Stub」切换到官方 SDK
 * `goodhub-mcp-plugin-core`，本类作为 SDK 的 Dart 业务层使用方——
 * 继承 [GoodHubPluginService]（由 SDK 承载 AIDL 语义），重写 [handleJsonRpc] 接管
 * 全部 JSON-RPC 路由（保留 shiguang 自己的 Dart 通道与 F1 切片）。
 * AIDL 的唯一物理副本现归 SDK 仓 :contract（com.zzh.goodhub.mcp），shiguang 不再自拷。
 *
 * 角色边界（§11.3·1）：仅重写路由口子；不 bindService、不持 DeathRecipient
 * （连接生命周期 / 挂起队列状态机归 goodhub 宿主 BinderTransport）；不 override onBind
 * （基类返回其内部持有的 Stub，私有不可见，亦不该自己再造一个）。
 *
 * 触达 Dart：持有独立 headless [FlutterEngine]（BIND_AUTO_CREATE 随调随起，满足
 * §1.2.5 随调随起——app 被杀也能被拉起跑 MCP），经 MethodChannel `shiguang/mcp_binder`
 * 调 Dart 后台 entrypoint（[mcpBinderMain]）→ [com.zzh.goodhub.mcp] 通道 →
 * McpServer.handleJsonRpc。模式照抄 goodshare McpBinderService（已验证范本），
 * 与其差异仅：通道名/entrypoint 名/包名；且 shiguang 对 **所有 tools/call**（回调已
 * 注册时）走 `_async`+onEvent 终帧切片（F1，修并行排队撞 B1 超时），goodshare 是
 * 按工具白名单；answerReverse 通道保留契约位（当前无反向请求，沿用基类默认处理）。
 *
 * 线程：AIDL `call` 由 goodhub 在 Binder 线程触发，基类 `IMcpChannel.Stub.call` 直接转发到
 * [handleJsonRpc] 同步处理、内部阻塞等待 Dart 回传不触发 ANR（§1.2.3）；首调前经 [ready]
 * 信号解除引擎未就绪竞态。
 */
class ShiguangMcpBinderService : GoodHubPluginService(SERVER_ID, SERVER_TITLE) {

    private var flutterEngine: FlutterEngine? = null
    @Volatile
    private var channel: MethodChannel? = null

    /** 引擎主线程 Handler：MethodChannel.invokeMethod 必须在其上执行（@UiThread），
     *  handleJsonRpc/onHostCallback 均在 Binder 线程触发，须 post 过来。 */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 宿主回注的回调（F1 后 onEvent 承载 _async 终帧转发；volatile 防跨线程可见性）。
     *  与 SDK 基类内部持有的回调是同一份（基类那份 private，供 [emitEvent] 推送用）；
     *  本引用供 F1 分流判定与 [DEGRADE] 可观测日志使用（刻意的双份，不要试图统一）。 */
    @Volatile
    private var callback: IMcpCallback? = null

    /** Dart 后台 entrypoint 就绪信号：注册完 MethodChannel 处理器后放行 handleJsonRpc。 */
    private val ready = CompletableFuture<Boolean>()

    /**
     * JSON-RPC 请求路由入口：接管全部 AIDL `call`（SDK 基类 `IMcpChannel.Stub.call`
     * 直接转发到这里），等价原手写 Stub.call 的全部逻辑。
     */
    override fun handleJsonRpc(jsonRpcRequest: String): String {
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
        // Result 回调经 emitEvent 推同 id 终帧——并行调用不再排队撞 B1 超时。
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
                                        errorJson(reqId, -32603, "Dart 返回空响应"),
                                    )
                                } else {
                                    emitTerminalFrame(frame)
                                }
                            }

                            override fun error(code: String, msg: String?, details: Any?) {
                                emitTerminalFrame(
                                    errorJson(reqId, -32603, "MCP Binder 调用失败: ${msg ?: code}"),
                                )
                            }

                            override fun notImplemented() {
                                emitTerminalFrame(
                                    errorJson(reqId, -32603, "Dart 未实现 dispatch"),
                                )
                            }
                        },
                    )
                } catch (e: Exception) {
                    emitTerminalFrame(
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

    /**
     * 宿主注册 / 注销回调时触发（等价原手写 `registerCallback`）。
     * 存下回调供 F1 分流判定与 [DEGRADE] 日志，并通知 Dart `hostCallbackReady`。
     */
    override fun onHostCallback(cb: IMcpCallback?) {
        callback = cb
        // 通知 Dart 侧回调通道已建立（注册后 tools/call 即走 _async 切片）。
        // invokeMethod 须引擎主线程执行。
        mainHandler.post { runCatching { channel?.invokeMethod("hostCallbackReady", cb != null) } }
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

    /**
     * 终帧经 SDK [emitEvent]（= IMcpCallback.onEvent）推给宿主（F1 义务②）。
     * 保留 shiguang 自己的 [DEGRADE] 可观测日志：宿主回调未注册 → 无处可推（用本类自己存的
     * [callback] 引用判定，与基类 private 那份并列）；SDK 的 emitEvent 内部静默吞异常，故
     * 「推帧失败」分支通常静默，仅作防御性留痕。
     */
    private fun emitTerminalFrame(frame: String) {
        val target = callback
        if (target == null) {
            android.util.Log.w(TAG, "[DEGRADE] 终帧无处可推（回调已注销）: ${frame.take(200)}")
            return
        }
        try {
            emitEvent(frame)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "[DEGRADE] onEvent 推帧失败（宿主回调断连？）: ${e.message}")
        }
        android.util.Log.i(TAG, "[F1] terminal emitted: ${frame.take(80)}")
    }

    companion object {
        const val CHANNEL = "shiguang/mcp_binder"
        const val ENTRYPOINT = "mcpBinderMain"
        // 取值与 Manifest meta-data（com.zzh.goodhub.SERVER_ID / SERVER_TITLE）一致，抽常量防漂移。
        const val SERVER_ID = "shiguang"
        const val SERVER_TITLE = "拾光"

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
