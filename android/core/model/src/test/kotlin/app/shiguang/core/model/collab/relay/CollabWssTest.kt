package app.shiguang.core.model.collab.relay

import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import shiguang.relay.v1.RelayProtocol.ActionType
import shiguang.relay.v1.RelayProtocol.ClientFrame
import shiguang.relay.v1.RelayProtocol.Envelope
import shiguang.relay.v1.RelayProtocol.ServerFrame

/**
 * test/collab_wss_test.dart Kotlin 移植：复刻 relay internal/wss/hub.go 的最小服务端
 * （MockWebServer WebSocket 升级）：纯路径 /ws 建连 → 首帧 JSON AUTH(token) 校验
 * （错则掐线）→ 之后二进制 ServerFrame 收发。
 */

/** hub.go 最小监听器：AUTH 校验 + 已鉴权后 CLIENT_ACK 回推 schedule 帧。 */
private class HubListener(
    private val expectedToken: String,
    private val conns: MutableList<WebSocket>?,
) : WebSocketListener() {
    private var authed = false

    override fun onOpen(ws: WebSocket, response: Response) {
        conns?.add(ws)
    }

    override fun onMessage(ws: WebSocket, text: String) {
        if (authed) return
        var ok = false
        try {
            val j = Json.parseToJsonElement(text).jsonObject
            ok = j["action"]?.jsonPrimitive?.content == "AUTH" &&
                j["token"]?.jsonPrimitive?.content == expectedToken
        } catch (_: Exception) {
        }
        if (!ok) {
            ws.close(1008, "expected AUTH")
            return
        }
        authed = true
    }

    override fun onMessage(ws: WebSocket, bytes: ByteString) {
        if (!authed) return
        try {
            val cf = ClientFrame.parseFrom(bytes.toByteArray())
            if (cf.hasSchedule() && cf.schedule.action == ActionType.CLIENT_ACK) {
                val sf = ServerFrame.newBuilder()
                    .setSeqId(7)
                    .setSchedule(
                        Envelope.newBuilder()
                            .setAction(ActionType.PING)
                            .setBundleId(cf.schedule.bundleId)
                            .setSeqId(7)
                            .setSenderHint("relay"),
                    )
                    .build()
                ws.send(ByteString.of(*sf.toByteArray()))
            }
        } catch (_: Exception) {
        }
    }
}

/** 轮询等待条件成立，超时抛 [TimeoutException]。 */
private suspend fun waitUntil(timeoutMs: Long = 5000, cond: () -> Boolean) {
    val end = System.currentTimeMillis() + timeoutMs
    while (!cond()) {
        if (System.currentTimeMillis() > end) {
            throw IllegalStateException("条件未在 ${timeoutMs}ms 内满足")
        }
        kotlinx.coroutines.delay(20) // 挂起让路：runBlocking 事件循环得已处理收集协程
    }
}

/** 订阅落地后再返回（SharedFlow 无 replay：订阅完成前的事件会丢——镜像 Dart 同步 listen）。 */
private fun kotlinx.coroutines.CoroutineScope.collectInto(
    flow: kotlinx.coroutines.flow.SharedFlow<ServerFrame>,
    sink: MutableList<ServerFrame>,
): Pair<Job, kotlinx.coroutines.CompletableDeferred<Unit>> {
    val subscribed = CompletableDeferred<Unit>()
    val job = launch { flow.onSubscription { subscribed.complete(Unit) }.collect { sink.add(it) } }
    return job to subscribed
}

private fun kotlinx.coroutines.CoroutineScope.collectStates(
    flow: kotlinx.coroutines.flow.SharedFlow<WssConnectionState>,
    sink: MutableList<WssConnectionState>,
): Pair<Job, kotlinx.coroutines.CompletableDeferred<Unit>> {
    val subscribed = CompletableDeferred<Unit>()
    val job = launch { flow.onSubscription { subscribed.complete(Unit) }.collect { sink.add(it) } }
    return job to subscribed
}

class CollabWssTest {

    private fun hubServer(expectedToken: String, conns: MutableList<WebSocket>? = null): MockWebServer {
        val server = MockWebServer()
        // 每次升级连接用独立监听器（authed 状态按连接隔离）
        server.enqueue(MockResponse().withWebSocketUpgrade(HubListener(expectedToken, conns)))
        server.start()
        return server
    }

    @Test
    fun authHandshakeAndFrameDispatch() = runBlocking {
        val server = hubServer("tok-A")
        val frames = mutableListOf<ServerFrame>()
        val client = RelayWssClient(
            wsUri = URI(server.url("/ws").toString().replaceFirst("http", "ws")),
            getToken = { "tok-A" },
            keepaliveIntervalMs = 3_600_000, // 关心跳，避免干扰
        )
        val (collector, framesSub) = collectInto(client.frames, frames)
        framesSub.await()
        client.connect()

        // 等 open 后持续发 ack，直至收到 relay 回推
        waitUntil {
            client.sendClientAck("b1", 5)
            frames.isNotEmpty()
        }
        assertEquals(7L, frames.first().seqId)
        assertEquals("b1", frames.first().schedule.bundleId)
        assertEquals("relay", frames.first().schedule.senderHint)
        client.dispose()
        collector.cancel()
        server.close()
        Unit
    }

    @Test
    fun reconnectWithExponentialBackoffAfterDisconnect() = runBlocking {
        val conns = mutableListOf<WebSocket>()
        val server = hubServer("tok-A", conns)
        // 预备第二条升级响应：断线重连时 MockWebServer 依次弹出
        server.enqueue(MockResponse().withWebSocketUpgrade(HubListener("tok-A", null)))
        val frames = mutableListOf<ServerFrame>()
        val states = mutableListOf<WssConnectionState>()
        val client = RelayWssClient(
            wsUri = URI(server.url("/ws").toString().replaceFirst("http", "ws")),
            getToken = { "tok-A" },
            keepaliveIntervalMs = 3_600_000,
            initialBackoffMs = 100,
            maxBackoffMs = 500,
        )
        val (c1, fSub) = collectInto(client.frames, frames)
        val (c2, sSub) = collectStates(client.state, states)
        fSub.await()
        sSub.await()
        client.connect()

        // 首帧往返
        waitUntil {
            client.sendClientAck("b1", 5)
            frames.isNotEmpty()
        }
        assertEquals(1, frames.size)

        // 模拟断网：服务端主动掐掉这条连接
        conns.first().close(1000, "server cut")

        // 客户端退避后自动重连，再次收到帧
        waitUntil {
            client.sendClientAck("b1", 5)
            frames.size >= 2
        }
        assertTrue(frames.size >= 2)
        waitUntil { states.contains(WssConnectionState.RECONNECTING) } // 容忍重连时序
        client.dispose()
        c1.cancel()
        c2.cancel()
        server.close()
        Unit
    }

    @Test
    fun invalidTokenKickedThenReconnecting() = runBlocking {
        val server = hubServer("right")
        val states = mutableListOf<WssConnectionState>()
        val client = RelayWssClient(
            wsUri = URI(server.url("/ws").toString().replaceFirst("http", "ws")),
            getToken = { "wrong" },
            keepaliveIntervalMs = 3_600_000,
            initialBackoffMs = 80,
            maxBackoffMs = 300,
        )
        val (c2, sSub) = collectStates(client.state, states)
        sSub.await()
        client.connect()

        // 应在短时间内检测到被关并进入重连（而非稳定 open）
        waitUntil(timeoutMs = 4000) { states.contains(WssConnectionState.RECONNECTING) }
        client.dispose()
        c2.cancel()
        server.close()
        Unit
    }

    @Test
    fun clientAckDeliveredEndToEnd() = runBlocking {
        val server = hubServer("tok-A")
        val frames = mutableListOf<ServerFrame>()
        val client = RelayWssClient(
            wsUri = URI(server.url("/ws").toString().replaceFirst("http", "ws")),
            getToken = { "tok-A" },
            keepaliveIntervalMs = 3_600_000,
        )
        val (collector, framesSub) = collectInto(client.frames, frames)
        framesSub.await()
        client.connect()
        waitUntil {
            client.sendClientAck("bundle-x", 9)
            frames.isNotEmpty()
        }
        // 回推帧的 bundleId 取自已发 ack，证明 ack 端到端送达
        assertEquals("bundle-x", frames.first().schedule.bundleId)
        client.dispose()
        collector.cancel()
        server.close()
        Unit
    }
}
