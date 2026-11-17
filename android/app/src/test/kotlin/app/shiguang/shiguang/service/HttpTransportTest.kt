package app.shiguang.shiguang.service

import app.shiguang.core.mcp.McpServer
import app.shiguang.core.mcp.McpToolFace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Socket

/**
 * 回环 HTTP 传输层对拍（N8c，mcp_settings.md §C 转正清单；内核=McpServer 真件）：
 * 顺序闸（origin→token→path→method）+ POST 200 / 通知 202 / 坏 JSON -32700 /
 * ping 命令面。错误串逐字（Dart http_transport.dart 镜像）。
 */
class HttpTransportTest {

    private val toolFace = object : McpToolFace {
        override fun schemas(): List<JsonObject> = emptyList()
        override suspend fun call(name: String, args: JsonObject): JsonElement = JsonPrimitive("ok:$name")
    }

    private fun newServer(token: String?): Pair<HttpTransport, Int> {
        val server = McpServer(toolFace)
        val transport = HttpTransport(server, tokenProvider = { token })
        val scope = CoroutineScope(Dispatchers.IO)
        transport.start(scope, port = 0) // 临时端口
        return transport to transport.port
    }

    /** 极简 HTTP 客户端（单请求会话，Connection: close 同形）。 */
    private fun http(
        port: Int,
        method: String,
        path: String,
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): Triple<Int, Map<String, String>, String> {
        val socket = Socket("127.0.0.1", port)
        socket.use { s ->
            val writer = OutputStreamWriter(s.outputStream, Charsets.UTF_8)
            writer.write("$method $path HTTP/1.1\r\n")
            writer.write("Host: 127.0.0.1\r\n")
            headers.forEach { (k, v) -> writer.write("$k: $v\r\n") }
            if (body != null) writer.write("Content-Length: ${body.toByteArray().size}\r\n")
            writer.write("Connection: close\r\n\r\n")
            if (body != null) writer.write(body)
            writer.flush()
            val reader = BufferedReader(InputStreamReader(s.inputStream, Charsets.UTF_8))
            val status = reader.readLine()!!.split(" ")[1].toInt()
            val respHeaders = mutableMapOf<String, String>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) respHeaders[line.take(idx).trim().lowercase()] = line.substring(idx + 1).trim()
            }
            val len = respHeaders["content-length"]?.toInt() ?: 0
            val buf = CharArray(len)
            var read = 0
            while (read < len) {
                val n = reader.read(buf, read, len - read)
                if (n == -1) break
                read += n
            }
            return Triple(status, respHeaders, String(buf, 0, read))
        }
    }

    @Test
    fun postMcpAnswersAndPingDispatches() {
        val (transport, port) = newServer(token = null)
        try {
            val (status, headers, body) = http(
                port, "POST", "/mcp",
                body = """{"jsonrpc":"2.0","id":1,"method":"ping"}""",
            )
            assertEquals(200, status)
            assertTrue(headers["content-type"]!!.startsWith("application/json"))
            val obj = Json.parseToJsonElement(body).jsonObject
            assertEquals(1, (obj["id"] as? JsonPrimitive)?.content?.toInt())
        } finally {
            transport.stop()
        }
    }

    @Test
    fun notificationAnswers202() {
        val (transport, port) = newServer(token = null)
        try {
            // id 缺席=通知 → 202 无体
            val (status, _, body) = http(
                port, "POST", "/mcp",
                body = """{"jsonrpc":"2.0","method":"ping"}""",
            )
            assertEquals(202, status)
            assertEquals("", body)
        } finally {
            transport.stop()
        }
    }

    @Test
    fun tokenGateReturns401Verbatim() {
        val (transport, port) = newServer(token = "abc123")
        try {
            val (status, _, body) = http(port, "POST", "/mcp", body = "{}")
            assertEquals(401, status)
            assertTrue(body.contains("-32001"))
            assertTrue(body.contains("X-Api-Key 校验失败"))
            // 带对 token → 200（坏 JSON 体回 -32700 但 HTTP 仍 200）
            val (status2, _, body2) = http(
                port, "POST", "/mcp", body = "{\"id\":1,",
                headers = mapOf("x-api-key" to "abc123"),
            )
            assertEquals(200, status2)
            assertTrue("body2=$body2", body2.contains("-32700"))
        } finally {
            transport.stop()
        }
    }

    @Test
    fun foreignOriginRejected403() {
        val (transport, port) = newServer(token = null)
        try {
            val (status, _, body) = http(
                port, "POST", "/mcp", body = "{}",
                headers = mapOf("Origin" to "http://evil.example.com"),
            )
            assertEquals(403, status)
            assertTrue(body.contains("Origin 不被允许"))
            // 本机 origin 放行（localhost 任意端口）
            val (status2, _, _) = http(
                port, "POST", "/mcp", body = """{"jsonrpc":"2.0","id":2,"method":"ping"}""",
                headers = mapOf("Origin" to "http://localhost:3000"),
            )
            assertEquals(200, status2)
        } finally {
            transport.stop()
        }
    }

    @Test
    fun unknownPathAndMethodRejected() {
        val (transport, port) = newServer(token = null)
        try {
            val (status1, _, body1) = http(port, "POST", "/other", body = "{}")
            assertEquals(404, status1)
            assertTrue(body1.contains("未知路径"))
            val (status2, _, body2) = http(port, "GET", "/mcp")
            assertEquals(405, status2)
            assertTrue(body2.contains("仅支持 POST"))
        } finally {
            transport.stop()
        }
    }

    @Test
    fun toolsCallRoundTripThroughKernel() {
        val (transport, port) = newServer(token = null)
        try {
            val (status, _, body) = http(
                port, "POST", "/mcp",
                body = """{"jsonrpc":"2.0","id":9,"method":"tools/call","params":{"name":"get_schedule","arguments":{}}}""",
            )
            assertEquals(200, status)
            val result = Json.parseToJsonElement(body).jsonObject["result"]?.jsonObject
            assertEquals("ok:get_schedule", ((result?.get("content")) as? JsonPrimitive)?.content)
        } finally {
            transport.stop()
        }
    }

    @Test
    fun startStopRebindsEphemeral() {
        val (transport, port) = newServer(token = null)
        try {
            assertTrue(transport.running)
            assertTrue(port > 0)
        } finally {
            transport.stop()
        }
        assertTrue(!transport.running)
        assertEquals(0, transport.port)
    }

    @Test
    fun runningGuardsDoubleStart() {
        val (transport, port) = newServer(token = null)
        try {
            val scope = CoroutineScope(Dispatchers.IO)
            transport.start(scope, port = port) // 已运行=no-op
            assertEquals(port, transport.port)
        } finally {
            transport.stop()
        }
    }
}
