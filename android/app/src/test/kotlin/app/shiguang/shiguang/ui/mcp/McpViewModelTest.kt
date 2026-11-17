package app.shiguang.shiguang.ui.mcp

import app.shiguang.core.action.NativeSqlRepository
import app.shiguang.core.mcp.McpToolFace
import app.shiguang.core.model.JdbcSqlStore
import app.shiguang.core.model.ShiguangSchema
import app.shiguang.shiguang.service.McpController
import app.shiguang.shiguang.service.RelayKeys
import app.shiguang.shiguang.update.RemoteConfigStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * MCP 调试页 ViewModel 对拍（N8c，mcp_settings.md §A/§B 转正清单；JdbcSqlStore 真库）：
 * relay 四键回填/单键保存（endpoint·username trim/password 原文）/开关落盘+1s 事件/
 * 服务开关面（controller 真件——enable 起回环 HTTP，disable 全停）。
 */
class McpViewModelTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val mainDispatcher = StandardTestDispatcher()
    private lateinit var repo: NativeSqlRepository
    private lateinit var controller: McpController
    private lateinit var vm: McpViewModel
    private lateinit var scope: CoroutineScope
    private val toolFace = object : McpToolFace {
        override fun schemas(): List<JsonObject> = emptyList()
        override suspend fun call(name: String, args: JsonObject): JsonElement = JsonPrimitive("ok")
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        runBlocking {
            val store = JdbcSqlStore.inMemory()
            ShiguangSchema.createAll(store)
            repo = NativeSqlRepository(store)
            val remote = RemoteConfigStore(tmp.newFolder())
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            controller = McpController(
                kv = repo,
                toolFaceFactory = { toolFace },
                remoteConfig = remote,
                startKeepalive = {},
                scope = scope,
                bindPort = 0,
            )
            vm = McpViewModel(controller, repo)
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        runBlocking { controller.disable() }
        scope.cancel()
    }

    private suspend fun settle(timeoutMs: Long = 10_000, cond: suspend () -> Boolean) {
        val start = System.currentTimeMillis()
        while (!cond()) {
            mainDispatcher.scheduler.advanceUntilIdle()
            if (System.currentTimeMillis() - start > timeoutMs) error("settle 超时")
            delay(20)
        }
        mainDispatcher.scheduler.advanceUntilIdle()
    }

    private suspend fun kvOf(key: String): String? =
        (repo.settingsAll()[key] as? JsonPrimitive)?.content

    @Test
    fun relayStateRestoredFromKv() = runBlocking {
        repo.settingsSet(buildJson(RelayKeys.ENDPOINT to "https://relay.example.com/"))
        repo.settingsSet(buildJson(RelayKeys.USERNAME to "me"))
        repo.settingsSet(buildJson(RelayKeys.PASSWORD to " p s "))
        repo.settingsSet(buildJson(RelayKeys.ENABLED to "1"))
        settle { vm.relay.value.loaded }
        val relay = vm.relay.value
        assertEquals("https://relay.example.com/", relay.endpoint)
        assertEquals("me", relay.username)
        assertEquals(" p s ", relay.password)
        assertTrue(relay.enabled)
        Unit
    }

    @Test
    fun saveRelayTrimsExceptPassword() = runBlocking {
        settle { vm.relay.value.loaded }
        vm.saveRelay(RelayKeys.ENDPOINT, "  https://s/  ")
        vm.saveRelay(RelayKeys.USERNAME, "  user1  ")
        vm.saveRelay(RelayKeys.PASSWORD, "  pw  ")
        settle { kvOf(RelayKeys.ENDPOINT) == "https://s/" }
        assertEquals("https://s/", kvOf(RelayKeys.ENDPOINT))
        assertEquals("user1", kvOf(RelayKeys.USERNAME))
        // 密码原文保存（含首尾空格合法语义——D23 过目照抄）
        assertEquals("  pw  ", kvOf(RelayKeys.PASSWORD))
        Unit
    }

    @Test
    fun relayEnabledTogglePersists() = runBlocking {
        settle { vm.relay.value.loaded }
        vm.setRelayEnabled(true)
        settle { kvOf(RelayKeys.ENABLED) == "1" }
        assertTrue(vm.relay.value.enabled)
        vm.setRelayEnabled(false)
        settle { kvOf(RelayKeys.ENABLED) == "0" }
        assertTrue(!vm.relay.value.enabled)
        Unit
    }

    @Test
    fun serviceToggleRunsController() = runBlocking {
        settle { vm.relay.value.loaded }
        vm.setRunning(true)
        settle { vm.running.value }
        assertTrue(vm.running.value)
        assertTrue(controller.endpoint.startsWith("http://127.0.0.1:"))
        vm.setRunning(false)
        settle { !vm.running.value }
        assertTrue(!vm.running.value)
        assertEquals("0", kvOf(McpController.KEY_ENABLED))
        Unit
    }

    private fun buildJson(pair: Pair<String, String>) = kotlinx.serialization.json.buildJsonObject {
        put(pair.first, JsonPrimitive(pair.second))
    }
}
