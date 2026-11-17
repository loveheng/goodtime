package app.shiguang.shiguang.ui.plan

import app.shiguang.core.action.NativeSqlRepository
import app.shiguang.core.model.Background
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
import kotlin.test.assertTrue

/**
 * 计划「背景」子页轻 ViewModel 对拍（plan_background.md 转正清单 §A/§C/§D，D24）：
 * scope+planId 过滤拉取（G1 Expand 真库对拍）/过期派生（无窗长期/空窗 fail-closed/
 * 整窗过沉底）/save 返回关 sheet 信号+note 事件/删除清空单事务。
 */
class PlanBackgroundViewModelTest {

    private class FixedTime(fixed: LocalDateTime) : TimeProvider {
        val nowValue = fixed
        override fun now(): LocalDateTime = nowValue
        override val ticks = MutableSharedFlow<TimeSignal>()
    }

    private val time = FixedTime(LocalDateTime.of(2026, 11, 15, 10, 0))
    private val mainDispatcher = StandardTestDispatcher()
    private lateinit var repo: NativeSqlRepository
    private lateinit var vm: PlanBackgroundViewModel
    private lateinit var plan: Plan

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        runBlocking {
            val store = JdbcSqlStore.inMemory()
            ShiguangSchema.createAll(store)
            repo = NativeSqlRepository(store)
            plan = Plan.fromMap(repo.addPlan(buildJsonObject { put("title", "去旅行") }))
            vm = PlanBackgroundViewModel(repo, time, plan.id!!)
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

    private suspend fun seedBackground(
        content: String,
        scope: String = Background.SCOPE_PLAN,
        planId: String? = plan.id,
        dates: List<String>? = null,
    ) {
        repo.addBackground(buildJsonObject {
            put("scope", scope)
            put("content", content)
            if (planId != null) put("plan_id", planId)
            if (dates != null) {
                put("applicable_dates", kotlinx.serialization.json.JsonArray(dates.map { JsonPrimitive(it) }))
            }
        })
        // addBackground 返回 toMcpJson 形（tags 数组）——fromMap 只吃 DB 行形，播种后一律经 VM 态回读
    }

    @Test
    fun `scope 与 planId 过滤只拉本计划`() = runBlocking {
        seedBackground("本计划背景")
        seedBackground("别的计划背景", planId = "other-plan")
        seedBackground("全局背景", scope = Background.SCOPE_GLOBAL, planId = null)
        settle { vm.uiState.value.view != null }
        val active = vm.uiState.value.view!!.active
        assertEquals(listOf("本计划背景"), active.map { it.content }, "G1 Expand：scope=plan+planId 双过滤")
    }

    @Test
    fun `过期派生 无窗长期空窗沉底整窗过`() = runBlocking {
        seedBackground("长期无窗")
        seedBackground("空窗", dates = emptyList())
        seedBackground("今日窗", dates = listOf("2026-11-15", "2026-11-16"))
        seedBackground("过去窗", dates = listOf("2026-11-10", "2026-11-12"))
        settle { vm.uiState.value.view != null && vm.uiState.value.view!!.expired.isNotEmpty() }
        val v = vm.uiState.value.view!!
        assertEquals(listOf("今日窗", "长期无窗"), v.active.map { it.content }, "今日窗未过=进行中（repo 序=created_at DESC 不重排）")
        assertEquals(listOf("过去窗", "空窗"), v.expired.map { it.content }, "空窗 fail-closed+整窗过沉底（repo 序不重排）")
    }

    @Test
    fun `save 关 sheet 信号与 revision 刷新`() = runBlocking {
        settle { vm.uiState.value.view != null }
        val ok = vm.save(null, "  妈妈膝盖不好  ", splitTags("#健康 体力"), null)
        assertTrue(ok, "返回 true=页关 sheet")
        settle { vm.uiState.value.view?.active?.isNotEmpty() == true }
        val fresh = vm.uiState.value.view!!.active.first()
        assertEquals("妈妈膝盖不好", fresh.content)
        assertEquals(listOf("#健康", "体力"), fresh.tags)
        assertEquals(Background.SOURCE_USER, fresh.source, "human 通道 source 自动=user")
        assertEquals(Background.CAPTURED_BY_ME, fresh.capturedBy)
    }

    @Test
    fun `save 空内容不提交`() = runBlocking {
        settle { vm.uiState.value.view != null }
        val ok = vm.save(null, "   ", emptyList(), null)
        assertEquals(false, ok)
    }

    @Test
    fun `编辑保存走 CAS 与日期窗逐日枚举`() = runBlocking {
        seedBackground("原始内容")
        settle { vm.uiState.value.view != null && vm.uiState.value.view!!.active.isNotEmpty() }
        val bg = vm.uiState.value.view!!.active.first()
        val window = dateWindowDays(java.time.LocalDate.of(2026, 11, 15), java.time.LocalDate.of(2026, 11, 17))
        val ok = vm.save(bg, "更新后的内容", emptyList(), window)
        assertTrue(ok)
        settle { vm.uiState.value.view?.active?.firstOrNull()?.content == "更新后的内容" }
        val fresh = vm.uiState.value.view!!.active.first()
        assertEquals(listOf("2026-11-15", "2026-11-16", "2026-11-17"), fresh.applicableDates)
        assertEquals(bg.version + 1, fresh.version, "CAS 乐观锁推进")
    }

    @Test
    fun `删除与清空共用 merge 单事务`() = runBlocking {
        seedBackground("条目一")
        seedBackground("条目二")
        settle { vm.uiState.value.view?.active?.size == 2 }
        vm.deleteBackgrounds(vm.uiState.value.view!!.active)
        settle { vm.uiState.value.view?.active?.isEmpty() == true && vm.uiState.value.view?.expired?.isEmpty() == true }
        assertTrue(vm.uiState.value.view!!.expired.isEmpty(), "清空后 active/expired 双空")
    }
}
