package app.shiguang.shiguang.ui.settings

import app.shiguang.core.action.NativeSqlRepository
import app.shiguang.core.model.ExceptionWindow
import app.shiguang.core.model.FixedSlot
import app.shiguang.core.model.JdbcSqlStore
import app.shiguang.core.model.ShiguangSchema
import app.shiguang.core.model.TimeProvider
import app.shiguang.core.model.TimeSignal
import app.shiguang.core.model.parseExceptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 设置编辑器三子页对拍（N8b，D22 清单转正；JdbcSqlStore 真库）：fixed_slots 整单
 * 替换与删除（§A）/例外日结构化直传与删除（§B R2 口径——读回经 parseExceptions）/
 * prefs 字段级提交与「已保存」1s 事件（§C）/编辑 sheet 内联校验通道。
 */
class SubpagesViewModelTest {

    private class FixedTime(fixed: LocalDateTime) : TimeProvider {
        val nowValue = fixed
        override fun now(): LocalDateTime = nowValue
        override val ticks = MutableSharedFlow<TimeSignal>()
    }

    private val time = FixedTime(LocalDateTime.of(2026, 11, 15, 10, 0))
    private val mainDispatcher = StandardTestDispatcher()
    private lateinit var repo: NativeSqlRepository
    private lateinit var vm: SubpagesViewModel

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
            vm = SubpagesViewModel(repo, time)
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

    @Test
    fun `fixed_slots 整单替换与删除`() = runBlocking {
        settle { vm.uiState.value.loading == false }
        vm.saveFixedSlot(FixedSlot(id = null, name = "上班", weekdays = listOf(1, 2, 3, 4, 5), startMin = 540, endMin = 1080))
        settle { vm.uiState.value.slots.size == 1 }
        val s = vm.uiState.value.slots.first()
        assertEquals("上班", s.name)
        assertEquals(listOf(1, 2, 3, 4, 5), s.weekdays.sorted())
        // 追加第二件（条目独立：工作日/周末可并存）
        vm.saveFixedSlot(FixedSlot(id = null, name = "周末球局", weekdays = listOf(6), startMin = 600, endMin = 720))
        settle { vm.uiState.value.slots.size == 2 }
        // 删除→整单 rest+事件
        val events = mutableListOf<app.shiguang.shiguang.ui.schedule.UiEvent>()
        val job = launch(Dispatchers.Unconfined) { vm.events.collect { events.add(it) } }
        vm.deleteFixedSlot(s)
        settle { vm.uiState.value.slots.size == 1 }
        settle { events.any { it.message == "已删除「上班」" } }
        job.cancel()
    }

    @Test
    fun `例外日结构化直传与删除`() = runBlocking {
        settle { vm.uiState.value.loading == false }
        vm.saveExceptions(
            listOf(
                ExceptionWindow(start = "2026-12-01", end = "2026-12-05", label = "云南行"),
                ExceptionWindow(start = "2027-01-28", end = "2027-02-03", label = null),
            ),
        )
        settle { vm.uiState.value.exceptions.size == 2 }
        val raw = repo.settingsAll().let {
            it.entries.associate { (k, v) -> k to ((v as JsonPrimitive).contentOrNull ?: "") }
        }
        val parsed = parseExceptions(raw)
        assertEquals(2, parsed.size)
        assertEquals("云南行", parsed[0].label)
        assertEquals(null, parsed[1].label)
        val events = mutableListOf<app.shiguang.shiguang.ui.schedule.UiEvent>()
        val job = launch(Dispatchers.Unconfined) { vm.events.collect { events.add(it) } }
        vm.deleteException(parsed[0])
        settle { vm.uiState.value.exceptions.size == 1 }
        settle { events.any { it.message == "已删除该例外日" } }
        job.cancel()
    }

    @Test
    fun `prefs 字段级提交与已保存事件`() = runBlocking {
        val events = mutableListOf<app.shiguang.shiguang.ui.schedule.UiEvent>()
        val job = launch(Dispatchers.Unconfined) { vm.events.collect { events.add(it) } }
        vm.savePrefs(mapOf("fill_rate_limit" to JsonPrimitive(35)))
        settle { repo.settingsGet("fill_rate_limit") == "35" }
        settle { events.any { it.message == "已保存" && it.durationMs == 1000L } }
        job.cancel()
    }

    @Test
    fun `编辑 sheet 内联校验通道`() = runBlocking {
        val events = mutableListOf<app.shiguang.shiguang.ui.schedule.UiEvent>()
        val job = launch(Dispatchers.Unconfined) { vm.events.collect { events.add(it) } }
        vm.notifyValidation("结束须晚于开始")
        settle { events.any { it.message == "结束须晚于开始" && it.durationMs == 3000L } }
        job.cancel()
        assertTrue(true)
    }
}
