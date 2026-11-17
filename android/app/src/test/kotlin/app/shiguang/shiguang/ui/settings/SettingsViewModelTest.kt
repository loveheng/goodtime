package app.shiguang.shiguang.ui.settings

import app.shiguang.core.action.NativeSqlRepository
import app.shiguang.core.model.JdbcSqlStore
import app.shiguang.core.model.ShiguangSchema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 设置页轻 ViewModel 对拍（N8a，D21 清单转正；JdbcSqlStore 真库）：AI 建议列表
 * 解析/采纳（update_settings 落键+按 key 移除+2s 事件）/忽略（按 id 移除）/作息
 * TimePicker 分钟数直写/外观 uiOnly/exportAll 六键聚合。
 */
class SettingsViewModelTest {

    private val mainDispatcher = StandardTestDispatcher()
    private lateinit var repo: NativeSqlRepository
    private lateinit var vm: SettingsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        runBlocking {
            val store = JdbcSqlStore.inMemory()
            ShiguangSchema.createAll(store)
            repo = NativeSqlRepository(store)
            repo.settingsSet(buildJsonObject {
                put("wake_time", JsonPrimitive("420"))
                put("sleep_time", JsonPrimitive("1380"))
            })
            vm = SettingsViewModel(repo)
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
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

    private suspend fun seedSuggestion(id: String, key: String, value: String) {
        val list = Json.parseToJsonElement(
            """[{"id":"$id","key":"$key","suggested_value":"$value","reason":"测试","severity":"normal","status":"pending"}]""",
        )
        repo.settingsSet(buildJsonObject { put("ai_setting_suggestions", JsonPrimitive(list.toString())) })
    }

    @Test
    fun `建议列表解析与双源装载`() = runBlocking {
        seedSuggestion("s1", "weather_location", "杭州")
        settle { vm.uiState.value.suggestions.isNotEmpty() }
        val s = vm.uiState.value.suggestions.first()
        assertEquals("s1", s.id)
        assertEquals("weather_location", s.key)
        assertEquals("杭州", s.suggestedValue)
        assertEquals(420, vm.uiState.value.settings?.get("wake_time").let { (it as JsonPrimitive).intOrNull })
    }

    @Test
    fun `采纳 写入设置键并移除条目与 2s 事件`() = runBlocking {
        seedSuggestion("s1", "weather_location", "杭州")
        settle { vm.uiState.value.suggestions.isNotEmpty() }
        val events = mutableListOf<app.shiguang.shiguang.ui.schedule.UiEvent>()
        val job = launch(Dispatchers.Unconfined) {
            vm.events.collect { events.add(it) }
        }
        vm.applySuggestion(vm.uiState.value.suggestions.first())
        settle { repo.settingsGet("weather_location") == "杭州" }
        settle { vm.uiState.value.suggestions.isEmpty() }
        settle { events.any { it.message == "已采纳：weather_location = 杭州" && it.durationMs == 2000L } }
        job.cancel()
    }

    @Test
    fun `忽略 按 id 移除无事件`() = runBlocking {
        seedSuggestion("s1", "weather_location", "杭州")
        settle { vm.uiState.value.suggestions.isNotEmpty() }
        vm.dismissSuggestion(vm.uiState.value.suggestions.first())
        settle { vm.uiState.value.suggestions.isEmpty() }
        assertEquals(null, repo.settingsGet("weather_location"), "忽略不写设置键")
    }

    @Test
    fun `作息分钟数直写与外观 uiOnly`() = runBlocking {
        vm.setTime("wake_time", 390)
        settle { repo.settingsGet("wake_time") == "390" }
        vm.setThemeMode("dark")
        settle { repo.settingsGet("theme_mode") == "dark" }
    }

    @Test
    fun `exportAll 六键聚合`() = runBlocking {
        repo.addPlan(buildJsonObject { put("title", "导出计划") })
        val json = vm.exportAllJson()
        val obj = Json.parseToJsonElement(json).jsonObject
        assertEquals(7, obj.keys.size) // 六键+exported_at
        assertTrue(obj.containsKey("plans") && obj.containsKey("app_settings"))
        assertTrue(obj.containsKey("fixed_slots") && obj.containsKey("schedule_blocks"))
        assertTrue(obj.containsKey("backgrounds") && obj.containsKey("artifacts"))
        assertTrue(obj.toString().contains("导出计划"))
    }
}
