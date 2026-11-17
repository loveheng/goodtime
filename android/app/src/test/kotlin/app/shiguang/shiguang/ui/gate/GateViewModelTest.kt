package app.shiguang.shiguang.ui.gate

import app.shiguang.core.action.NativeSqlRepository
import app.shiguang.core.model.Artifact
import app.shiguang.core.model.JdbcSqlStore
import app.shiguang.core.model.ShiguangSchema
import app.shiguang.shiguang.ui.gate.GateViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 入口闸门 ViewModel 对拍（N8c，app_gate.md §A/§B 转正清单；JdbcSqlStore 真库）：
 * 三态决策（载入 null→false→播种翻转 true）+ 分享转投消费（verbal/verbal 占位
 * 入册 origin=shared + title 首行截 40 + 池清空 + 空池不动作）。
 */
class GateViewModelTest {

    private val mainDispatcher = StandardTestDispatcher()
    private lateinit var repo: NativeSqlRepository
    private lateinit var pendingShare: MutableStateFlow<String?>
    private lateinit var vm: GateViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        runBlocking {
            val store = JdbcSqlStore.inMemory()
            ShiguangSchema.createAll(store)
            repo = NativeSqlRepository(store)
            pendingShare = MutableStateFlow(null)
            vm = GateViewModel(repo, pendingShare) { pendingShare.value = null }
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
    fun gateThreeStatesFlipsOnSeed() = runBlocking {
        // 未播种 → false（缺作息边界=未初始化）
        settle { vm.initialized.value == false }
        assertEquals(false, vm.initialized.value)
        // 播种 → revision bump → true
        repo.settingsSet(buildJsonObject {
            put("wake_time", JsonPrimitive(420))
            put("sleep_time", JsonPrimitive(1380))
        })
        settle { vm.initialized.value == true }
        assertEquals(true, vm.initialized.value)
        Unit
    }

    @Test
    fun pendingShareConsumedAsRawVerbalFact() = runBlocking {
        settle { vm.initialized.value == false }
        pendingShare.value = "从浏览器分享的一段话\n第二行"
        vm.consumePendingShare()
        settle { pendingShare.value == null }
        // raw 建档：verbal/verbal 占位 + origin=shared + title=首行截 40
        // 断言走 exportAll 六键聚合的 artifacts 键
        val exported = repo.exportAll()
        val arr = exported["artifacts"] as? kotlinx.serialization.json.JsonArray
        assertTrue(arr != null && arr.size == 1)
        val fact = arr!![0].let { it as kotlinx.serialization.json.JsonObject }
        assertEquals(Artifact.CATEGORY_VERBAL, (fact["category"] as? JsonPrimitive)?.content)
        assertEquals(Artifact.SOURCE_VERBAL, (fact["source_kind"] as? JsonPrimitive)?.content)
        assertEquals(Artifact.ORIGIN_SHARED, (fact["origin"] as? JsonPrimitive)?.content)
        assertEquals(Artifact.STATE_RAW, (fact["state"] as? JsonPrimitive)?.content)
        assertEquals("从浏览器分享的一段话", (fact["title"] as? JsonPrimitive)?.content)
        Unit
    }

    @Test
    fun pendingShareTitleTruncatedAt40() = runBlocking {
        settle { vm.initialized.value == false }
        val longFirstLine = "一".repeat(50) + "\n尾行"
        pendingShare.value = longFirstLine
        vm.consumePendingShare()
        settle { pendingShare.value == null }
        val exported = repo.exportAll()
        val fact = (exported["artifacts"] as kotlinx.serialization.json.JsonArray)[0]
            as kotlinx.serialization.json.JsonObject
        assertEquals("一".repeat(40), (fact["title"] as? JsonPrimitive)?.content)
        Unit
    }

    @Test
    fun emptyOrBlankShareIgnored() = runBlocking {
        settle { vm.initialized.value == false }
        pendingShare.value = "   "
        vm.consumePendingShare()
        mainDispatcher.scheduler.advanceUntilIdle()
        delay(100)
        mainDispatcher.scheduler.advanceUntilIdle()
        val exported = repo.exportAll()
        val arr = exported["artifacts"] as? kotlinx.serialization.json.JsonArray
        assertTrue(arr == null || arr.isEmpty())
        Unit
    }

    @Test
    fun concurrentConsumptionGuarded() = runBlocking {
        settle { vm.initialized.value == false }
        pendingShare.value = "并发一号\n行"
        // 双发消费（首帧排一次+变化兜一次）——互斥保单次入册
        vm.consumePendingShare()
        vm.consumePendingShare()
        settle { pendingShare.value == null }
        delay(200)
        mainDispatcher.scheduler.advanceUntilIdle()
        val exported = repo.exportAll()
        val arr = exported["artifacts"] as? kotlinx.serialization.json.JsonArray
        assertEquals(1, arr?.size)
        Unit
    }
}
