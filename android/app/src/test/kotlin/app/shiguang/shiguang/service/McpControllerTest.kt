package app.shiguang.shiguang.service

import app.shiguang.core.action.NativeSqlRepository
import app.shiguang.core.mcp.McpToolFace
import app.shiguang.core.model.JdbcSqlStore
import app.shiguang.core.model.ShiguangSchema
import app.shiguang.shiguang.update.RemoteConfig
import app.shiguang.shiguang.update.RemoteConfigStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * MCP 服务总控对拍（N8c，mcp_settings.md §B 转正清单；JdbcSqlStore 真库）：
 * enable（running+token 落盘+mcp_enabled=1+回环端口真听）/disable（全停+enabled=0）/
 * regenerateToken/restoreIfNeeded（缺 token 生成+enabled 恢复）/instructionsProvider
 * （远程覆盖+identity 拼接）。FGS 面=startKeepalive lambda 注入（JVM 零 Context）。
 */
class McpControllerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var repo: NativeSqlRepository
    private lateinit var remoteConfig: RemoteConfigStore
    private lateinit var controller: McpController
    private lateinit var scope: CoroutineScope
    private var keepaliveStarted = 0

    private val toolFace = object : McpToolFace {
        override fun schemas(): List<JsonObject> = emptyList()
        override suspend fun call(name: String, args: JsonObject): JsonElement = JsonPrimitive("ok")
    }

    @Before
    fun setUp() {
        runBlocking {
            val store = JdbcSqlStore.inMemory()
            ShiguangSchema.createAll(store)
            repo = NativeSqlRepository(store)
            remoteConfig = RemoteConfigStore(tmp.newFolder())
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            keepaliveStarted = 0
            controller = McpController(
                kv = repo,
                toolFaceFactory = { toolFace },
                remoteConfig = remoteConfig,
                startKeepalive = { keepaliveStarted++ },
                scope = scope,
                bindPort = 0, // 临时端口（并发安全）
            )
            // 冷启动装配（token 生成）——enable 断言前置；等待落盘完成
            controller.restoreIfNeeded()
            var tries = 0
            while (repo.settingsGet(McpController.KEY_TOKEN) == null && tries++ < 100) Thread.sleep(20)
        }
    }

    @After
    fun tearDown() {
        runBlocking { controller.disable() }
        scope.cancel()
    }

    @Test
    fun enableStartsLoopbackAndPersistsToken() = runBlocking {
        // setUp restoreIfNeeded 已生成 24 位 token
        assertTrue(controller.token.value?.length == 24)
        val error = controller.enable()
        assertNull(error)
        assertTrue(controller.running.value)
        assertTrue(controller.token.value!!.length == 24)
        assertEquals("1", repo.settingsGet(McpController.KEY_ENABLED))
        assertEquals(1, keepaliveStarted)
        // 真听面：ping 经回环 HTTP 200
        val resp = rawPost(controller.endpoint, """{"jsonrpc":"2.0","id":1,"method":"ping"}""", controller.token.value)
        assertTrue("ping resp=$resp", resp.contains("\"result\""))
        Unit
    }

    @Test
    fun disableStopsAndPersistsZero() = runBlocking {
        controller.enable()
        controller.disable()
        assertTrue(!controller.running.value)
        assertEquals("0", repo.settingsGet(McpController.KEY_ENABLED))
        Unit
    }

    @Test
    fun regenerateTokenPersistsNewValue() = runBlocking {
        controller.enable()
        val old = controller.token.value!!
        controller.regenerateToken()
        // regenerate 走 scope.launch 异步——轮询等变更
        var tries = 0
        while (controller.token.value == old && tries++ < 100) Thread.sleep(20)
        val now = controller.token.value!!
        assertTrue(now.length == 24 && now != old)
        assertEquals(now, repo.settingsGet(McpController.KEY_TOKEN))
        Unit
    }

    @Test
    fun restoreGeneratesMissingTokenAndResumesEnabled() = runBlocking {
        // setUp 已跑一次 restore（token 在库）——本测验证 enabled=1 恢复面
        assertTrue(repo.settingsGet(McpController.KEY_TOKEN) != null)
        controller.restoreIfNeeded()
        var tries = 0
        while (repo.settingsGet(McpController.KEY_TOKEN) == null && tries++ < 100) Thread.sleep(20)
        // 未开过服务（mcp_enabled 缺失）→ 不自动启动
        assertTrue(!controller.running.value)
        assertNotNull(repo.settingsGet(McpController.KEY_TOKEN))
        // 预置 enabled=1 → restore 自动恢复
        controller.disable()
        repo.settingsSet(buildJsonObject {
            put(McpController.KEY_ENABLED, JsonPrimitive("1"))
        })
        controller.restoreIfNeeded()
        tries = 0
        while (!controller.running.value && tries++ < 100) Thread.sleep(20)
        assertTrue(controller.running.value)
        Unit
    }

    @Test
    fun instructionsRemoteOverridesThenIdentityAppends() = runBlocking {
        // 无 remote → 内置政策精华
        controller.enable()
        val builtin = callInstructions()
        assertTrue(builtin.startsWith("你是「拾光」的排程师"))
        // remote 非空 → 覆盖
        remoteConfig.save(RemoteConfig(mcpInstructions = "远程指令版"))
        assertTrue(callInstructions().startsWith("远程指令版"))
        // identity_prompt 非空 → 拼接待尊重段（base 之后）
        repo.settingsSet(buildJsonObject {
            put(app.shiguang.core.model.SettingsKeys.IDENTITY_PROMPT, JsonPrimitive("自由职业者"))
        })
        val prompt = callInstructions()
        assertTrue(prompt.contains("【用户身份画像】"))
        assertTrue(prompt.endsWith("自由职业者"))
        Unit
    }

    /** 经回环 HTTP 走 initialize → instructions（内核面端到端）。 */
    private suspend fun callInstructions(): String {
        val raw = rawPost(
            controller.endpoint,
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}""",
            controller.token.value,
        )
        val body = raw.substringAfter('|', missingDelimiterValue = "")
        val obj = Json.parseToJsonElement(body).jsonObject
        return obj["result"]?.jsonObject?.get("instructions")?.jsonPrimitive?.content
            ?: error("initialize 无 instructions: $raw")
    }

    /** 极简 POST（Content-Length 标准帧；HTTP 语义逐字解析）。返回 "status|body"。 */
    private fun rawPost(hostPort: String, body: String): String =
        rawPost(hostPort, body, null)

    private fun rawPost(hostPort: String, body: String, token: String?): String {
        val port = java.net.URI(hostPort).port
        java.net.Socket("127.0.0.1", port).use { s ->
            val w = java.io.BufferedWriter(java.io.OutputStreamWriter(s.outputStream, Charsets.UTF_8))
            w.write("POST /mcp HTTP/1.1\r\nHost: 127.0.0.1\r\n")
            token?.let { w.write("x-api-key: $it\r\n") }
            w.write("Content-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n")
            w.write(body)
            w.flush()
            val r = java.io.BufferedReader(java.io.InputStreamReader(s.inputStream, Charsets.UTF_8))
            var len = 0
            var statusLine = ""
            while (true) {
                val line = r.readLine() ?: break
                if (line.isEmpty()) break
                if (statusLine.isEmpty()) statusLine = line
                if (line.lowercase().startsWith("content-length:")) {
                    len = line.substringAfter(':').trim().toInt()
                }
            }
            val buf = CharArray(len)
            var read = 0
            while (read < len) {
                val n = r.read(buf, read, len - read)
                if (n == -1) break
                read += n
            }
            val status = statusLine.split(" ").getOrNull(1) ?: "?"
            return "$status|${String(buf, 0, read)}"
        }
    }

    private fun buildJsonObject(block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject =
        kotlinx.serialization.json.buildJsonObject(block)
}
