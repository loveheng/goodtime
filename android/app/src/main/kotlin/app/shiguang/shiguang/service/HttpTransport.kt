package app.shiguang.shiguang.service

import app.shiguang.core.mcp.McpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * HTTP 传输层（在线 epic 轴 A 接缝，lib/service/http_transport.dart Kotlin 等价；
 * behavior/mcp_settings.md §C 转正清单 D23=本轮随行）：极简 HTTP/1.1 面上承载
 * [McpServer]——HTTP 专属逻辑（origin/DNS-rebinding 校验、x-api-key 鉴权、/mcp 与
 * POST 检查、202 通知、错误兜底）从内核剥离。内核保持纯 JVM，经
 * [McpServer.handleJsonRpc] 被本层驱动。
 *
 * 仅绑回环（2026-10-15 拍板，§11「MCP 暴露面」）：生产通道走 Binder 宿主，本传输
 * 只服务 adb reverse 内部调试，不暴露局域网。
 *
 * 实现口径：Android 无 JDK com.sun.net.httpserver（android.jar 不含）——零依赖
 * ServerSocket 手写 HTTP/1.1 单请求会话（Connection: close；MCP 客户端 POST 带
 * Content-Length 标准帧）；一 accept 一次 launch 逐请求处理 + runBlocking 桥接挂起
 * 内核——回环调试面与 Dart 单 isolate 事件循环同形，并发吞吐非本通道目标。
 */
class HttpTransport(
    private val server: McpServer,
    /** 传输层鉴权（-32001 通道码不属内核；Dart McpServer.tokenProvider 对位）。 */
    private val tokenProvider: () -> String? = { null },
) {

    private var serverSocket: ServerSocket? = null
    private var requestScope: CoroutineScope? = null

    val running: Boolean get() = serverSocket != null
    val port: Int get() = serverSocket?.localPort ?: 0

    /** 绑定回环 [port]（占用冲突抛异常由 McpController.enable 捕获转错误串）；
     *  [scope] =请求处理派生域（AppContainer.applicationScope——禁裸建全局作用域）。
     *  显式 IPv4 127.0.0.1（getLoopbackAddress 在部分设备解析 ::1——仅 IPv6 监听
     *  会被 IPv4 回环 ping/adb reverse 拒连，2026-11-15 真机实证；Dart
     *  InternetAddress.loopbackIPv4 同口径）。 */
    fun start(scope: CoroutineScope, port: Int = DEFAULT_PORT) {
        if (running) return
        val ss = ServerSocket(port, BACKLOG, InetAddress.getByName("127.0.0.1"))
        serverSocket = ss
        requestScope = scope
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                val client = try {
                    ss.accept()
                } catch (e: IOException) {
                    break // stop() 关闭 server socket 的正常退出
                }
                handle(client)
            }
        }
    }

    fun stop() {
        try {
            serverSocket?.close()
        } catch (_: IOException) {
            // 已关
        }
        serverSocket = null
        requestScope = null
    }

    private fun handle(client: Socket) {
        // 逐请求独立处理（backlog 排队；异常兜底 -32603，连接级失败静默丢弃）
        val scope = requestScope ?: return
        scope.launch(Dispatchers.IO) {
            try {
                client.use { serve(it) }
            } catch (_: Exception) {
                // 客户端断开/半包——回环调试面不放大
            }
        }
    }

    private fun serve(client: Socket) {
        val reader = BufferedReader(InputStreamReader(client.inputStream, Charsets.UTF_8))
        // 请求行：POST /mcp HTTP/1.1
        val requestLine = reader.readLine() ?: return
        val parts = requestLine.split(" ")
        if (parts.size < 3) {
            respondJson(client, 400, rpcError(ERR_INVALID_REQUEST, "请求行格式错误"))
            return
        }
        val method = parts[0]
        val path = parts[1]
        // 头部（小写键）
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = reader.readLine() ?: return
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) headers[line.take(idx).trim().lowercase()] = line.substring(idx + 1).trim()
        }
        // DNS-rebinding 防护（MCP 规范要求）：浏览器发起的请求必带 Origin，恶意网页/
        // DNS rebinding 场景借此被拒；桌面客户端（stdio 桥/直连）不带 Origin，不受影响。
        val origin = headers["origin"]
        if (origin != null && !isAllowedOrigin(origin)) {
            respondJson(client, 403, rpcError(ERR_INVALID_REQUEST, "Origin 不被允许: $origin"))
            return
        }
        // 传输层鉴权（-32001 通道码不属内核）
        val token = tokenProvider()
        if (!token.isNullOrEmpty() && headers["x-api-key"] != token) {
            respondJson(
                client, 401,
                rpcError(TRANSPORT_AUTH_ERROR, "X-Api-Key 校验失败（app「MCP 服务」页可查看 token）"),
            )
            return
        }
        if (path.substringBefore('?') != PATH_MCP) {
            respondJson(client, 404, rpcError(ERR_INVALID_REQUEST, "未知路径，MCP 端点为 /mcp"))
            return
        }
        if (method != "POST") {
            respondJson(client, 405, rpcError(ERR_INVALID_REQUEST, "仅支持 POST /mcp（本服务不提供 SSE 长连接）"))
            return
        }
        val body = readBody(reader, headers["content-length"])
        if (body == null) {
            respondJson(client, 400, rpcError(ERR_PARSE, "缺少 Content-Length 的请求体"))
            return
        }
        val resp = runBlocking { server.handleJsonRpc(body) }
        if (resp.isEmpty()) {
            // 通知（id==null）：无需应答，回 202（与原 _onRequest 行为一致）
            respond(client, 202, "")
            return
        }
        respond(client, 200, resp)
    }

    /** 读 Content-Length 定长体（MCP 客户端标准帧；chunked 不支持——显式 400 拒）。 */
    private fun readBody(reader: BufferedReader, contentLength: String?): String? {
        val length = contentLength?.toIntOrNull() ?: return null
        if (length <= 0) return ""
        val buf = CharArray(length)
        var read = 0
        while (read < length) {
            val n = reader.read(buf, read, length - read)
            if (n == -1) break
            read += n
        }
        return String(buf, 0, read)
    }

    /** 仅放行本机来源（localhost / 127.0.0.1 / [::1]，任意端口）。
     *  app 内如出现 WebView 类浏览器客户端需直连时，在此扩展白名单。 */
    private fun isAllowedOrigin(origin: String): Boolean {
        val host = runCatching { URI(origin).host }.getOrNull() ?: return false
        if (host.isEmpty()) return false
        return host.lowercase() in LOCAL_HOSTS
    }

    private fun respondJson(client: Socket, status: Int, payload: JsonObject) =
        respond(client, status, payload.toString())

    private fun respond(client: Socket, status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val writer = BufferedWriter(OutputStreamWriter(client.outputStream, Charsets.UTF_8))
        writer.write("HTTP/1.1 $status ${reasonOf(status)}\r\n")
        writer.write("Content-Type: application/json; charset=utf-8\r\n")
        writer.write("Content-Length: ${bytes.size}\r\n")
        writer.write("Connection: close\r\n\r\n")
        writer.write(body)
        writer.flush()
        client.close()
    }

    private fun reasonOf(status: Int): String = when (status) {
        200 -> "OK"
        202 -> "Accepted"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        else -> "Internal Server Error"
    }

    private fun rpcError(code: Int, message: String): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", JsonNull)
        put("error", buildJsonObject {
            put("code", code)
            put("message", message)
        })
    }

    companion object {
        const val DEFAULT_PORT = 8765
        const val PATH_MCP = "/mcp"
        const val BACKLOG = 8
        const val ERR_PARSE = -32700
        const val ERR_INVALID_REQUEST = -32600

        /** 传输层鉴权通道码（Dart -32001 同值；内核 Err 族不含）。 */
        const val TRANSPORT_AUTH_ERROR = -32001

        private val LOCAL_HOSTS = setOf("localhost", "127.0.0.1", "[::1]", "::1")
    }
}
