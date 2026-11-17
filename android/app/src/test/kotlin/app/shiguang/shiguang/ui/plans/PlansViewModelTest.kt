package app.shiguang.shiguang.ui.plans

import app.shiguang.core.action.NativeSqlRepository
import app.shiguang.core.model.JdbcSqlStore
import app.shiguang.core.model.Plan
import app.shiguang.core.model.ShiguangSchema
import app.shiguang.core.model.TimeProvider
import app.shiguang.core.model.TimeSignal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 清单页轻 ViewModel 对拍（N7，D20 清单转正；JdbcSqlStore 真库+固定时钟桩）：
 * 三分区分流（今日/推进中/待安排/冷藏池）/空池判定（冷藏池有货不算空池）/归档撤销
 * 链（fresh version 回传）/撤销与删除/未归属计数/搜索态。
 */
class PlansViewModelTest {

    private class FixedTime(fixed: LocalDateTime) : TimeProvider {
        val nowValue = fixed
        override fun now(): LocalDateTime = nowValue
        override val ticks = MutableSharedFlow<TimeSignal>()
    }

    private val time = FixedTime(LocalDateTime.of(2026, 11, 15, 10, 0))
    private val mainDispatcher = StandardTestDispatcher()
    private lateinit var repo: NativeSqlRepository
    private lateinit var vm: PlansViewModel

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
            vm = PlansViewModel(repo, time)
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

    private suspend fun seedPlan(
        title: String,
        archived: Boolean = false,
        deadline: String? = null,
        importance: Boolean = false,
    ): Plan {
        val row = repo.addPlan(buildJsonObject {
            put("title", title)
            put("importance", if (importance) 1 else 0)
            if (deadline != null) put("deadline", deadline)
            if (archived) put("archived", 1)
        })
        return Plan.fromMap(row)
    }

    private suspend fun seedBlock(date: String, startMin: Int, endMin: Int, planId: String, status: String = "confirmed", spark: Boolean = false) {
        repo.addBlock(buildJsonObject {
            put("date", date)
            put("start_min", startMin)
            put("end_min", endMin)
            put("status", status)
            put("source", "ai")
            put("plan_id", planId)
            put("is_day_spark", if (spark) 1 else 0)
        })
    }

    @Test
    fun `三分区分流 今日推进中待安排冷藏池`() = runBlocking {
        val a = seedPlan("今日计划")
        val b = seedPlan("推进计划")
        val c = seedPlan("待安排计划")
        seedPlan("冷藏计划", archived = true)
        seedBlock("2026-11-15", 600, 660, a.id!!)
        seedBlock("2026-11-17", 600, 660, b.id!!) // 未来块（+2 天窗口内）
        settle { vm.uiState.value.view != null }
        val v = vm.uiState.value.view!!
        assertEquals(listOf("今日计划"), v.today.map { it.title })
        assertEquals(listOf("推进计划"), v.inProgress.map { it.title })
        assertEquals(listOf("待安排计划"), v.waiting.map { it.title })
        assertEquals(listOf("冷藏计划"), v.archived.map { it.title })
        assertFalse(v.isEmpty)
    }

    @Test
    fun `空池判定 冷藏池有货不算空池`() = runBlocking {
        seedPlan("冷藏计划", archived = true)
        settle { vm.uiState.value.view != null }
        val v = vm.uiState.value.view!!
        assertTrue(v.archived.isNotEmpty())
        assertFalse(v.isEmpty, "冷藏池有货不算空池（§B）")
        // 无 archived 全空 → isEmpty
        val repo2 = runBlocking {
            val store = JdbcSqlStore.inMemory()
            ShiguangSchema.createAll(store)
            NativeSqlRepository(store).also { s ->
                s.settingsSet(buildJsonObject {
                    put("wake_time", JsonPrimitive("420"))
                    put("sleep_time", JsonPrimitive("1380"))
                })
            }
        }
        val vm2 = PlansViewModel(repo2, time)
        settle { vm2.uiState.value.view != null }
        assertTrue(vm2.uiState.value.view!!.isEmpty)
    }

    @Test
    fun `归档撤销链 fresh version 回传`() = runBlocking {
        val p = seedPlan("目标计划")
        settle { vm.uiState.value.view != null }
        val events = mutableListOf<app.shiguang.shiguang.ui.schedule.UiEvent>()
        val job = launch(Dispatchers.Unconfined) {
            vm.events.collect { events.add(it) }
        }
        vm.archiveWithUndo(p)
        settle { vm.uiState.value.view?.archived?.size == 1 }
        val e = events.last { it.actionUndoPlanId != null }
        assertEquals("已归档「目标计划」", e.message)
        assertEquals("撤销", e.actionLabel)
        assertTrue((e.actionUndoVersion ?: 0L) > p.version, "撤销钮 expectedVersion=快照 fresh version")
        vm.undoArchive(e.actionUndoPlanId!!, e.actionUndoVersion!!)
        settle { vm.uiState.value.view?.waiting?.size == 1 }
        job.cancel()
    }

    @Test
    fun `删除与未归属计数`() = runBlocking {
        val p = seedPlan("删除目标")
        repo.addArtifact(buildJsonObject {
            put("title", "无主凭证")
            put("state", "structured")
            put("category", "generic")
            put("source_kind", "text")
            put("origin", "manual")
            put("captured_by", "me")
            put("payload", "{}")
        }, null)
        settle { vm.uiState.value.view?.unattributedCount == 1 }
        vm.deletePlan(p)
        settle { vm.uiState.value.view?.waiting?.isEmpty() == true }
        assertTrue(repo.planById(p.id!!) == null)
    }

    @Test
    fun `搜索态 空串不过滤`() = runBlocking {
        vm.setSearch("  交房租  ")
        assertEquals("交房租", vm.query.value) // trim 即滤
        vm.setSearch("")
        assertEquals("", vm.query.value)
    }
}
