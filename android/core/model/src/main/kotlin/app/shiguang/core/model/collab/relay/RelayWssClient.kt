package app.shiguang.core.model.collab.relay

import java.net.URI
import java.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import shiguang.relay.v1.RelayProtocol.ClientFrame
import shiguang.relay.v1.RelayProtocol.Envelope
import shiguang.relay.v1.RelayProtocol.ServerFrame

/** WSS 客户端连接状态（仅做 UI/编排可见性，不承载业务）。 */
enum class WssConnectionState { DISCONNECTED, CONNECTING, OPEN, RECONNECTING }

/**
 * 中继 WSS 客户端（lib/collab/relay/wss_client.dart Kotlin 等价，OkHttp WebSocket
 * 承载——D10 拍板 2026-11-14；relay internal/wss/hub.go，冻结 tag v0.1.6-contract）。
 *
 * 三条硬约束逐条对齐：
 * 1. **纯路径建连** ws(s)://host/ws——token 绝不进 URL（反代 Access Log 会明文泄露）；
 * 2. **首帧 AUTH**：连接后第一帧必须是 {"action":"AUTH","token":"<access>"} JSON，
 *    relay 侧 3s 未收到即掐线（非法 token 立即关）；
 * 3. **握手后一律 protobuf 二进制帧** ServerFrame/ClientFrame（文本帧忽略——镜像
 *    Dart 仅处理 List<int>）。
 *
 * 断线后**指数退避自动重连**（1s → 2s → 4s … 封顶 30s），重连成功归零计数。
 * OkHttp 无 Dart 版 keepalive 对应物，心跳同样靠**应用层保活**：每 keepaliveInterval
 * 发一个被 relay 忽略的 CLIENT_ACK(seqId:0)，重置其 60s 读截止。
 *
 * open class 仅供测试替身覆写 [frames]（镜像 Dart _FakeWss 模式）。
 */
open class RelayWssClient(
    val wsUri: URI,
    private val getToken: suspend () -> String,
    private val initialBackoffMs: Long = 1_000,
    private val maxBackoffMs: Long = 30_000,
    private val keepaliveIntervalMs: Long = 30_000,
    baseClient: OkHttpClient = OkHttpClient(),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val framesFlow = MutableSharedFlow<ServerFrame>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val stateFlow = MutableSharedFlow<WssConnectionState>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** 入站 ServerFrame 流（已解码）。 */
    open val frames: SharedFlow<ServerFrame> get() = framesFlow

    /** 连接状态变化流。 */
    val state: SharedFlow<WssConnectionState> get() = stateFlow

    // OkHttp WS 建连走 http(s) URL（升级协议由 OkHttp 处理）；读超时关闭——
    // 空闲存活靠应用层 CLIENT_ACK 保活，协议级静默不得掐线。
    private val http: OkHttpClient = baseClient.newBuilder()
        .readTimeout(Duration.ZERO) // 空闲存活靠应用层 CLIENT_ACK 保活，协议级静默不掐线
        .build()

    private var socket: WebSocket? = null
    private var connJob: Job? = null
    private var backoffJob: Job? = null
    private var keepaliveJob: Job? = null

    @Volatile private var attempt = 0
    @Volatile private var disposed = false
    @Volatile private var started = false
    @Volatile private var reconnecting = false

    /** 启动连接（幂等：重复调用无效）。 */
    fun connect() {
        if (started || disposed) return
        started = true
        connectOnce()
    }

    private fun connectOnce() {
        if (disposed) return
        emitState(WssConnectionState.CONNECTING)
        val opened = CompletableDeferred<Boolean>()
        // ws(s):// → http(s)://（OkHttp 建连 URL 形态；纯路径不变，token 不进 URL）
        val httpUrl = wsUri.toString()
            .replaceFirst("ws://", "http://")
            .replaceFirst("wss://", "https://")
        val request = Request.Builder().url(httpUrl).build()
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                opened.complete(true)
            }

            override fun onMessage(ws: WebSocket, text: String) {
                // 仅处理二进制 protobuf 帧（镜像 Dart：msg is! List<int> → 忽略）
            }

            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                onBinary(bytes.toByteArray())
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                // 对端发起关闭（relay 掐线/正常关）：回应关闭帧完成握手 + 按断线重连处理。
                // （OkHttp 客户端不自动回 close——不回应则连接悬挂到 EOF。）
                try {
                    ws.close(1000, null)
                } catch (_: Exception) {
                }
                scheduleReconnect()
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                scheduleReconnect()
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                opened.complete(false)
                scheduleReconnect()
            }
        })
        // 镜像 Dart 时序：channel.ready（=onOpen）→ getToken → 首帧 AUTH → OPEN
        connJob = scope.launch {
            val ok = try {
                opened.await()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            if (disposed) return@launch
            if (!ok) {
                scheduleReconnect()
                return@launch
            }
            val token = try {
                getToken()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                scheduleReconnect()
                return@launch
            }
            // 首帧 AUTH（JSON 文本帧；token 不进 URL）
            try {
                val auth = buildJsonObject {
                    put("action", "AUTH")
                    put("token", token)
                }.toString()
                if (socket?.send(auth) != true) {
                    scheduleReconnect()
                    return@launch
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                scheduleReconnect()
                return@launch
            }
            emitState(WssConnectionState.OPEN)
            attempt = 0
            startKeepalive()
        }
    }

    private fun onBinary(msg: ByteArray) {
        try {
            framesFlow.tryEmit(ServerFrame.parseFrom(msg))
        } catch (_: Exception) {
            // 无法解析的控制帧，忽略
        }
    }

    /** 发送任意 ClientFrame（握手后使用）。连接已断时静默丢弃（重连后自然续上）。 */
    fun send(frame: ClientFrame) {
        try {
            socket?.send(ByteString.of(*frame.toByteArray()))
        } catch (_: Exception) {
        }
    }

    /** 发送 CLIENT_ACK 推进游标（relay 按 last_acked_seq 判定落后者才推送）。 */
    fun sendClientAck(bundleId: String, seqId: Long) {
        send(
            ClientFrame.newBuilder()
                .setSchedule(
                    Envelope.newBuilder()
                        .setAction(shiguang.relay.v1.RelayProtocol.ActionType.CLIENT_ACK)
                        .setBundleId(bundleId)
                        .setSeqId(seqId),
                )
                .build(),
        )
    }

    private fun startKeepalive() {
        keepaliveJob?.cancel()
        keepaliveJob = scope.launch {
            while (isActive) {
                delay(keepaliveIntervalMs)
                if (socket == null) continue
                // 被 relay 忽略的保活帧：重置其 60s 读截止，避免空闲连接被掐。
                send(
                    ClientFrame.newBuilder()
                        .setSchedule(
                            Envelope.newBuilder()
                                .setAction(shiguang.relay.v1.RelayProtocol.ActionType.CLIENT_ACK)
                                .setSeqId(0),
                        )
                        .build(),
                )
            }
        }
    }

    private fun stopKeepalive() {
        keepaliveJob?.cancel()
        keepaliveJob = null
    }

    private fun backoffFor(a: Int): Long {
        val exp = if (a > 24) Long.MAX_VALUE else initialBackoffMs shl (a - 1) // 1,2,4,8…
        return if (exp > maxBackoffMs) maxBackoffMs else exp
    }

    private fun scheduleReconnect() {
        if (disposed || reconnecting) return
        reconnecting = true
        attempt++
        val delayMs = backoffFor(attempt)
        emitState(WssConnectionState.RECONNECTING)
        stopKeepalive()
        backoffJob?.cancel()
        backoffJob = scope.launch {
            delay(delayMs)
            reconnecting = false
            connectOnce()
        }
    }

    private fun emitState(s: WssConnectionState) {
        stateFlow.tryEmit(s)
    }

    /** 释放全部资源。 */
    fun dispose() {
        disposed = true
        backoffJob?.cancel()
        connJob?.cancel()
        stopKeepalive()
        try {
            socket?.cancel() // 终态 abrupt 释放（优雅 close 需对端回应，dispose 不等待）
        } catch (_: Exception) {
        }
        socket = null
        scope.cancel()
    }
}
