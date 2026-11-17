package app.shiguang.shiguang.ui.onboarding

import app.shiguang.core.action.FixedSlotRef
import app.shiguang.core.action.NativeSqlRepository
import app.shiguang.core.model.JdbcSqlStore
import app.shiguang.core.model.ShiguangSchema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 首启引导 ViewModel 对拍（N8c，onboarding.md §A/§C 转正清单；JdbcSqlStore 真库）：
 * 预设 slots 逐字（上班族/学生两段工作日占用+自由职业空表不写）+ ④一次性提交口
 * （四数值键恒写+identity/rules trim 空不写+human 通道）。①②③暂存不落库=页内
 * 本地态（无落库面可测——行为清单 §D.2 照抄）。
 */
class OnboardingViewModelTest {

    private val mainDispatcher = StandardTestDispatcher()
    private lateinit var repo: NativeSqlRepository
    private lateinit var vm: OnboardingViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        runBlocking {
            val store = JdbcSqlStore.inMemory()
            ShiguangSchema.createAll(store)
            repo = NativeSqlRepository(store)
            vm = OnboardingViewModel(repo)
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

    private suspend fun slotNames(): List<String> {
        val all = repo.exportAll()["fixed_slots"]
        if (all !is JsonArray) return emptyList()
        return all.mapNotNull { (it as? JsonObject)?.get("name")?.let { v -> (v as? JsonPrimitive)?.contentOrNull } }
    }

    private suspend fun settingOf(key: String): String? =
        (repo.settingsAll()[key] as? JsonPrimitive)?.contentOrNull

    @Test
    fun presetSlotsVerbatim() {
        val worker = OnboardingViewModel.presetSlots("上班族")
        assertEquals(2, worker.size)
        assertEquals(FixedSlotRef(null, "上班", listOf(1, 2, 3, 4, 5), 540, 720), worker[0])
        assertEquals(FixedSlotRef(null, "上班（下午）", listOf(1, 2, 3, 4, 5), 840, 1080), worker[1])
        val student = OnboardingViewModel.presetSlots("学生")
        assertEquals(2, student.size)
        assertEquals(FixedSlotRef(null, "上课", listOf(1, 2, 3, 4, 5), 480, 720), student[0])
        assertEquals(FixedSlotRef(null, "上课（下午）", listOf(1, 2, 3, 4, 5), 840, 1020), student[1])
        assertEquals(0, OnboardingViewModel.presetSlots("自由职业").size)
        assertEquals(0, OnboardingViewModel.presetSlots("未知").size)
    }

    @Test
    fun commitPresetWritesFreelanceSkipped() = runBlocking {
        // 自由职业=空表合法直接过：不写 slots
        vm.commitPreset("自由职业")
        mainDispatcher.scheduler.advanceUntilIdle()
        delay(100)
        mainDispatcher.scheduler.advanceUntilIdle()
        assertTrue(slotNames().isEmpty())
        // 上班族：整单批量替换两段
        vm.commitPreset("上班族")
        settle { slotNames().size == 2 }
        assertTrue(slotNames().contains("上班"))
        assertTrue(slotNames().contains("上班（下午）"))
        Unit
    }

    @Test
    fun commitFinalWritesFourKeysAndNonEmptyText() = runBlocking {
        vm.commitFinal(wake = 430, sleep = 1390, minBlock = 60, dailyLimit = 6, identity = "  自由职业者  ", rules = "  ")
        settle { settingOf("wake_time") != null }
        assertEquals("430", settingOf("wake_time"))
        assertEquals("1390", settingOf("sleep_time"))
        assertEquals("60", settingOf("min_block_minutes"))
        assertEquals("6", settingOf("daily_new_blocks_limit"))
        assertEquals("自由职业者", settingOf("identity_prompt"))
        // rules trim 后空 → 不写
        assertNull(settingOf("user_rules"))
        Unit
    }

    @Test
    fun initializedSemanticsAfterCommit() = runBlocking {
        vm.commitFinal(wake = 420, sleep = 1380, minBlock = 30, dailyLimit = 8, identity = "", rules = "")
        settle { settingOf("wake_time") != null && settingOf("sleep_time") != null }
        // initialized 判定=含双键（SettingsKeys.initialized 语义；闸门随 revision 翻转）
        assertTrue(settingOf("wake_time") != null && settingOf("sleep_time") != null)
        Unit
    }
}
