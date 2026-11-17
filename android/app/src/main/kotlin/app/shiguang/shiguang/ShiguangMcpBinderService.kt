package app.shiguang.shiguang

import app.shiguang.core.mcp.McpRpcError
import app.shiguang.core.mcp.McpServer
import app.shiguang.core.mcp.McpToolFace
import com.zzh.goodhub.mcp.IMcpCallback
import com.zzh.goodhub.mcp.plugin.GoodHubPluginService
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * 拾光本地 Binder 插件服务（server 端，goodhub 契约 thirdconnect.md §1.2.2）。
 *
 * P5 切包收口（2026-11-17，Dart 退役）：协议面 Kotlin + 业务面 tools/call 与
 * tools/list schemas 全走 [NativeToolFace] 原生内核（工具描述文案已平移
 * tool_schemas.json 资源；P2 双轨期的 headless Dart 桥退役）。ready / F1 切片 /
 * 错误码三红线逐位不变。
 *
 * 线程模型：AIDL `call` 在 Binder 线程触发 →
 * - 协议面请求（initialize/ping/resources/prompts/未知方法）：内核就地应答；
 * - tools/call：原生内核（写经单线程写队列，读就地）；
 * - F1：tools/call 且回调已注册 → call 立回 `_async` 占位帧，内核在**专用 worker 线程**
 *   执行，终帧经 emitEvent 推送。
 *
 * 红线（schedule-app §11 逐位不变）：ready 5s 竞态（超时 -32000）/ per-call 5s 超时
 * （-32000）/ dispatch 异常兜底（-32603）/ Binder 同签名零鉴权。
 */
class ShiguangMcpBinderService : GoodHubPluginService(SERVER_ID, SERVER_TITLE) {

    @Volatile
    private var callback: IMcpCallback? = null

    /** F1 后台执行线程（单线程串行，与 C3 语义对齐）。 */
    private val f1Executor = Executors.newSingleThreadExecutor { r -> Thread(r, "mcp-f1-worker") }

    /** 组合根（§9 启动时序隔离：Application.onCreate 只 Core 装配）：binder 服务
     *  直接消费容器——无 UI 独立构造，写经容器内单线程写队列（R3 双写者口径）。 */
    private val container: AppContainer by lazy {
        (applicationContext as ShiguangApplication).container
    }

    /** P5 切包形态：schemas 与 call 全走容器内原生工具面；onSuggestion 挂容器总线（壳层 SnackBar 8s）。 */
    private val kotlinServer: McpServer by lazy {
        McpServer(toolFace = container.toolFace).apply {
            onSuggestion = { args -> container.suggestionBus.tryEmit(args) }
        }
    }

    /**
     * JSON-RPC 请求路由入口：全部请求交 [kotlinServer]——协议面就地应答，
     * tools/list 与 tools/call 原生内核；F1 切片判定与红线在路径中保持。
     */
    override fun handleJsonRpc(jsonRpcRequest: String): String {
        val reqId = extractId(jsonRpcRequest)
        val method = extractMethod(jsonRpcRequest)
        // F1（契约 §1.2.2 逐位）：回调已注册且为 tools/call → call 立回 _async 占位帧
        //（不占 5s Binder 预算），内核在专用 worker 线程执行（挂起桥不可在主线程 latch），
        // 终帧经 emitEvent 推同 id；非 tools/call 与回调缺失维持同步路径（兼容旧宿主）。
        if (callback != null && method == "tools/call") {
            android.util.Log.i(TAG, "[F1] kotlin hybrid async dispatch: id=$reqId")
            f1Executor.execute {
                try {
                    val frame = kotlinx.coroutines.runBlocking {
                        kotlinServer.handleJsonRpc(jsonRpcRequest)
                    }
                    emitTerminalFrame(frame)
                } catch (e: Exception) {
                    emitTerminalFrame(errorJson(reqId, -32603, "MCP Binder 调用失败: ${e.message}"))
                }
            }
            return asyncResultJson(reqId)
        }
        // 同步路径：Binder 线程阻塞等（业务面 latch ≤5s -32000 兜底；协议面即时）。
        return try {
            val frame = kotlinx.coroutines.runBlocking {
                kotlinServer.handleJsonRpc(jsonRpcRequest)
            }
            frame
        } catch (e: McpRpcError) {
            errorJson(reqId, e.code, e.message)
        } catch (e: Exception) {
            errorJson(reqId, -32603, "MCP Binder 调用失败: ${e.message}")
        }
    }

    override fun onHostCallback(cb: IMcpCallback?) {
        callback = cb
    }

    override fun onDestroy() {
        super.onDestroy()
        callback = null
    }

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
        const val SERVER_ID = "shiguang"
        const val SERVER_TITLE = "拾光"

        private const val TAG = "ShiguangMcpBinder"

        private fun errorJson(id: Any?, code: Int, message: String): String =
            JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", if (id == null) JSONObject.NULL else id)
                put("error", JSONObject().apply {
                    put("code", code)
                    put("message", message)
                })
            }.toString()

        private fun extractId(request: String): Any? = try {
            JSONObject(request).opt("id")
        } catch (_: Exception) {
            null
        }

        private fun asyncResultJson(id: Any?): String = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", if (id == null) JSONObject.NULL else id)
            put("result", JSONObject().apply { put("_async", true) })
        }.toString()

        private fun extractMethod(request: String): String? = try {
            JSONObject(request).optString("method", null)
        } catch (_: Exception) {
            null
        }
    }
}
