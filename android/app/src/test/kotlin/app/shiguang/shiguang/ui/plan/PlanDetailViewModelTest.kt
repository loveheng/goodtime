package app.shiguang.shiguang.ui.plan

import app.shiguang.core.action.NativeSqlRepository
import app.shiguang.core.model.JdbcSqlStore
import app.shiguang.core.model.Plan
import app.shiguang.core.model.ScheduleDay
import app.shiguang.core.model.ShiguangSchema
import app.shiguang.core.model.TimeProvider
import app.shiguang.core.model.TimeSignal
import app.shiguang.shiguang.ui.schedule.UiEvent
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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 计划详情页轻 ViewModel 对拍（plan_detail.md 转正清单 §A/§C/§D，D24）：
 * 三态装载（F2）/编辑保存 trim 空=null 不改动（F3）+version 回填/手答整表替换/
 * 子树 ≤3 级/排期 place_block 落库（estimate 缺省 60）。
 */
class PlanDetailViewModelTest {

    private class FixedTime(fixed: LocalDateTime) : TimeProvider {
        val nowValue = fixed
        override fun now(): LocalDateTime = nowValue
        override val ticks = MutableSharedFlow<TimeSignal>()
    }

    private val time = FixedTime(LocalDateTime.of(2026, 11, 15, 10, 0))
    private val mainDispatcher = StandardTestDispatcher()
    private lateinit var repo: NativeSqlRepository
    private lateinit var vm: PlanDetailViewModel
    private lateinit var plan: Plan

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
            plan = Plan.fromMap(repo.addPlan(buildJsonObject {
                put("title", "去旅行")
                put("spec", "准备行装\n## 路线图\n- [x] 订票\n- [ ] 打包\n")
                put("notes", "先查护照\n其余收拾")
                put("reward_spec", "吃顿好的")
                put("open_items", kotlinx.serialization.json.JsonArray(listOf(
                    buildJsonObject { put("question", "住哪里"); put("answer", kotlinx.serialization.json.JsonNull) },
                )))
            }))
            vm = PlanDetailViewModel(repo, time, plan.id!!, androidx.lifecycle.SavedStateHandle())
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
    fun `装载三态与首载回填`() = runBlocking {
        settle { vm.uiState.value.plan != null }
        val s = vm.uiState.value
        assertEquals("去旅行", s.plan?.title)
        assertEquals(1, s.openItems.size)
        assertEquals("去旅行", vm.title.value)
        assertEquals("先查护照\n其余收拾", vm.notes.value)
        assertEquals("吃顿好的", vm.reward.value)
        assertEquals(listOf("当前进度 1/2"), s.plan?.spec?.let { listOf(roadmapProgress(it)) })
    }

    @Test
    fun `不存在态 plan null 不闪文案`() = runBlocking {
        val vm2 = PlanDetailViewModel(repo, time, "no-such-id", androidx.lifecycle.SavedStateHandle())
        settle { !vm2.uiState.value.loading }
        assertNull(vm2.uiState.value.plan)
    }

    @Test
    fun `SavedStateHandle 编辑暂存回填与写点（丢失点 #9 回归钉）`() = runBlocking {
        // 进程重建回填：handle 留有草稿 → 首载回填草稿优先于 DB 内容
        val handle = androidx.lifecycle.SavedStateHandle(
            mapOf(
                "edit_title" to "走查草稿甲",
                "edit_spec" to "草稿 spec",
                "edit_notes" to "草稿 notes",
                "edit_reward" to "草稿 reward",
            ),
        )
        val vm2 = PlanDetailViewModel(repo, time, plan.id!!, handle)
        settle { vm2.uiState.value.plan != null }
        assertEquals("走查草稿甲", vm2.title.value, "handle 草稿优先回填（首载）")
        assertEquals("草稿 notes", vm2.notes.value)
        // setter 写点：编辑即落 handle（进程杀后可回填）
        val handle2 = androidx.lifecycle.SavedStateHandle()
        val vm3 = PlanDetailViewModel(repo, time, plan.id!!, handle2)
        settle { vm3.uiState.value.plan != null }
        assertEquals("去旅行", vm3.title.value, "无草稿=DB 内容回填")
        vm3.setTitle("改题")
        assertEquals("改题", handle2["edit_title"], "setter 落 handle")
        vm3.setReward("改犒赏")
        assertEquals("改犒赏", handle2["edit_reward"], "setter 落 handle（reward）")
    }

    @Test
    fun `编辑保存 trim 空传 null 不改动 version 回填回查看态`() = runBlocking {
        settle { vm.uiState.value.plan != null }
        val events = mutableListOf<UiEvent>()
        val job = launch(Dispatchers.Unconfined) { vm.events.collect { events.add(it) } }
        vm.toggleEditing()
        assertTrue(vm.uiState.value.editing)
        vm.setTitle("  ") // trim 空=null=不改动（D24-F3 现状）
        vm.setReward("吃顿大餐")
        vm.save()
        settle {
            !vm.uiState.value.editing &&
                Plan.fromMap(repo.planById(plan.id!!)!!).rewardSpec == "吃顿大餐"
        }
        val fresh = Plan.fromMap(repo.planById(plan.id!!)!!)
        assertEquals("去旅行", fresh.title, "trim 空不覆盖原值")
        assertEquals("吃顿大餐", fresh.rewardSpec)
        assertTrue(vm.uiState.value.version > plan.version, "version 快照回填")
        assertTrue(events.any { it.message == "已保存" && it.durationMs == 1000L })
        job.cancel()
    }

    @Test
    fun `手答整表替换与空答案不提交`() = runBlocking {
        settle { vm.uiState.value.plan != null }
        vm.answer(0, "  ") // 空=不提交
        assertEquals(null, vm.uiState.value.openItems[0].answer)
        vm.answer(0, "青旅")
        settle { vm.uiState.value.openItems[0].answer == "青旅" }
        val fresh = Plan.fromMap(repo.planById(plan.id!!)!!)
        assertEquals("青旅", fresh.openItems[0].answer)
        assertEquals("住哪里", fresh.openItems[0].question)
    }

    @Test
    fun `子树深度三级封顶`() = runBlocking {
        val child = Plan.fromMap(repo.addPlan(buildJsonObject {
            put("title", "子计划")
            put("parent_id", plan.id)
        }))
        val grand = Plan.fromMap(repo.addPlan(buildJsonObject {
            put("title", "孙计划")
            put("parent_id", child.id)
        }))
        repo.addPlan(buildJsonObject {
            put("title", "曾孙计划")
            put("parent_id", grand.id)
        })
        settle { vm.uiState.value.subtree.isNotEmpty() }
        val subtree = vm.uiState.value.subtree
        assertEquals(3, subtree.size, "depth>3 不入（曾孙不入册）")
        assertEquals(listOf(1, 2, 3), subtree.map { it.first })
        assertEquals(listOf("子计划", "孙计划", "曾孙计划"), subtree.map { it.second.title })
    }

    @Test
    fun `排期 place_block 估时缺省六十`() = runBlocking {
        settle { vm.uiState.value.plan != null }
        val events = mutableListOf<UiEvent>()
        val job = launch(Dispatchers.Unconfined) { vm.events.collect { events.add(it) } }
        vm.schedule("2026-11-16", 540)
        settle { events.any { it.message.startsWith("已排入 2026-11-16 09:00") } }
        val blocks = (repo.blocksOnDate("2026-11-16") as kotlinx.serialization.json.JsonArray)
        assertEquals(1, blocks.size)
        val b = blocks.first() as kotlinx.serialization.json.JsonObject
        assertEquals(540L, (b["start_min"] as JsonPrimitive).longOrNull)
        assertEquals(600L, (b["end_min"] as JsonPrimitive).longOrNull, "estimate 缺省 60")
        assertEquals(plan.id, (b["plan_id"] as JsonPrimitive).contentOrNull)
        assertEquals(
            ScheduleDay.isoDate(time.nowValue.toLocalDate()),
            ScheduleDay.scheduleDayOf(time.now(), 420).toString(),
        )
        job.cancel()
    }
}
