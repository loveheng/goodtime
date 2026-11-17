package app.shiguang.shiguang.ui.plan

import app.shiguang.core.action.NativeSqlRepository
import app.shiguang.core.model.Artifact
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
 * 计划「随行凭证」子页轻 ViewModel 对拍（plan_facts.md 转正清单 §A/§D，D24）：
 * 子树派生聚合（子孙凭证入册）/includeVoided=true voided 沉底（G2 Expand 对拍）/
 * factExpired 派生分区（raw 琥珀/active createdAt 升序）/删除仅 human。
 */
class PlanFactsViewModelTest {

    private class FixedTime(fixed: LocalDateTime) : TimeProvider {
        val nowValue = fixed
        override fun now(): LocalDateTime = nowValue
        override val ticks = MutableSharedFlow<TimeSignal>()
    }

    private val time = FixedTime(LocalDateTime.of(2026, 11, 15, 10, 0))
    private val mainDispatcher = StandardTestDispatcher()
    private lateinit var repo: NativeSqlRepository
    private lateinit var vm: PlanFactsViewModel
    private lateinit var root: Plan
    private lateinit var child: Plan

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        runBlocking {
            val store = JdbcSqlStore.inMemory()
            ShiguangSchema.createAll(store)
            repo = NativeSqlRepository(store)
            root = Plan.fromMap(repo.addPlan(buildJsonObject { put("title", "去旅行") }))
            child = Plan.fromMap(repo.addPlan(buildJsonObject {
                put("title", "子行程")
                put("parent_id", root.id)
            }))
            vm = PlanFactsViewModel(repo, time, root.id!!)
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

    private suspend fun seedArtifact(
        title: String,
        planId: String,
        state: String = Artifact.STATE_STRUCTURED,
        category: String = Artifact.CATEGORY_TICKET,
        payload: kotlinx.serialization.json.JsonObject = buildJsonObject { put("v", 1) },
        createdAt: Long? = null,
    ) {
        val art = Artifact(
            id = null,
            category = category,
            sourceKind = Artifact.SOURCE_BOOKING,
            state = state,
            origin = Artifact.ORIGIN_AI,
            title = title,
            badge = null,
            payload = payload,
            attachments = emptyList(),
            capturedBy = Artifact.CAPTURED_BY_ME,
            planId = planId,
            createdAt = createdAt,
            updatedAt = null,
            version = 0,
        )
        repo.addArtifact(art.toMap(), null)
    }

    private fun anchorsJson(date: String, endDate: String? = null): kotlinx.serialization.json.JsonObject = buildJsonObject {
        put("date", date)
        if (endDate != null) put("end_date", endDate)
    }

    @Test
    fun `子树聚合 根与子孙凭证入册他计划不入`() = runBlocking {
        seedArtifact("根计划车票", planId = root.id!!, createdAt = 2000L)
        seedArtifact("子计划门票", planId = child.id!!, createdAt = 1000L)
        seedArtifact("无关计划凭证", planId = "other-plan")
        settle { vm.uiState.value.view != null && vm.uiState.value.view!!.structured.size == 2 }
        val v = vm.uiState.value.view!!
        // active 按 createdAt 升序（§A）
        assertEquals(listOf("子计划门票", "根计划车票"), v.structured.map { it.title })
        assertTrue(v.expired.isEmpty() && v.voided.isEmpty())
    }

    @Test
    fun `voided 沉底折叠组不进主卡面`() = runBlocking {
        seedArtifact("正常凭证", planId = root.id!!)
        seedArtifact("作废凭证", planId = root.id!!, state = Artifact.STATE_VOIDED)
        settle { vm.uiState.value.view != null && vm.uiState.value.view!!.voided.size == 1 }
        val v = vm.uiState.value.view!!
        assertEquals(listOf("作废凭证"), v.voided.map { it.title })
        assertEquals(listOf("正常凭证"), v.structured.map { it.title }, "§1.2 双通道：voided 不进主卡面")
    }

    @Test
    fun `过期派生 raw 琥珀桶与锚点口径`() = runBlocking {
        seedArtifact(
            "过去车票", planId = root.id!!,
            payload = buildJsonObject {
                put("time_anchors", kotlinx.serialization.json.JsonArray(listOf(anchorsJson("2026-11-10", "2026-11-12"))))
            },
        )
        seedArtifact(
            "未来车票", planId = root.id!!,
            payload = buildJsonObject {
                put("time_anchors", kotlinx.serialization.json.JsonArray(listOf(anchorsJson("2026-11-20"))))
            },
        )
        seedArtifact("原文快照", planId = root.id!!, state = Artifact.STATE_RAW)
        settle { vm.uiState.value.view != null && vm.uiState.value.view!!.raw.size == 1 }
        val v = vm.uiState.value.view!!
        assertEquals(listOf("过去车票"), v.expired.map { it.title }, "全部锚点最晚日早于今日→过去组")
        assertEquals(listOf("原文快照"), v.raw.map { it.title }, "raw 无锚=长期恒不过期→琥珀桶")
        assertEquals(listOf("未来车票"), v.structured.map { it.title })
    }

    @Test
    fun `删除仅 human 与 note 回执`() = runBlocking {
        seedArtifact("待删凭证", planId = root.id!!)
        settle { vm.uiState.value.view?.structured?.size == 1 }
        val f = vm.uiState.value.view!!.structured.first()
        val events = mutableListOf<app.shiguang.shiguang.ui.schedule.UiEvent>()
        val job = launch(Dispatchers.Unconfined) { vm.events.collect { events.add(it) } }
        vm.deleteArtifact(f)
        settle { vm.uiState.value.view?.structured?.isEmpty() == true }
        assertTrue(events.any { it.message == "已删除凭证" && it.durationMs == 2000L }, "note 回执=命令层文案（无块绑定不 demote）")
        job.cancel()
    }
}
