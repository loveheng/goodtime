package app.shiguang.shiguang.ui.schedule

import app.shiguang.core.action.NativeSqlRepository
import app.shiguang.core.model.JdbcSqlStore
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
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 今日页轻 ViewModel 命令面对拍（D15/D16，N5；JdbcSqlStore 真库+固定时钟桩）：
 * 遗留区三键 / 确认卡批量语义（CAS 冲突吞错）/ 电量降档 / 快记草稿即写即存与提交 /
 * revision 驱动重载（读面=collect revision → 重载 → StateFlow 单向流）。
 * viewModelScope 经 Main 替换落测试调度器（命令后 advanceUntilIdle 推到真线程挂起点），
 * IO/写队列为真实线程——断言前轮询收敛。
 */
class TodayViewModelTest {

    private class FixedTime(fixed: LocalDateTime) : TimeProvider {
        val nowValue = fixed
        override fun now(): LocalDateTime = nowValue
        override val ticks = MutableSharedFlow<TimeSignal>()
    }

    private val time = FixedTime(LocalDateTime.of(2026, 11, 15, 10, 0))
    private val mainDispatcher = StandardTestDispatcher()
    private lateinit var repo: NativeSqlRepository
    private lateinit var vm: TodayViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        runBlocking {
            val store = JdbcSqlStore.inMemory()
            ShiguangSchema.createAll(store)
            repo = NativeSqlRepository(store)
            vm = TodayViewModel(repo, time)
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** 推 Main 测试调度器至真线程挂起点，再轮询真实 IO/写队列收敛。 */
    private suspend fun settle(timeoutMs: Long = 10_000, cond: suspend () -> Boolean) {
        val start = System.currentTimeMillis()
        while (!cond()) {
            mainDispatcher.scheduler.advanceUntilIdle()
            if (System.currentTimeMillis() - start > timeoutMs) error("settle 超时; uiState=${vm.uiState.value} rev=${vm.repo.revision.value}")
            delay(20)
        }
        mainDispatcher.scheduler.advanceUntilIdle()
    }

    private suspend fun seedBlock(
        date: String,
        startMin: Int,
        endMin: Int,
        status: String,
    ) = repo.addBlock(buildJsonObject {
        put("date", date)
        put("start_min", startMin)
        put("end_min", endMin)
        put("status", status)
        put("source", "ai")
    })

    private fun idOf(row: kotlinx.serialization.json.JsonObject): String =
        (row["id"] as JsonPrimitive).content

    @Test
    fun `revision 驱动首帧装载`() = runBlocking {
        seedBlock("2026-11-15", 600, 660, "confirmed")
        settle { vm.uiState.value.data != null }
        val d = vm.uiState.value.data!!
        assertEquals(1, d.blocks.size)
        assertTrue(d.isToday)
    }

    @Test
    fun `遗留区三键 补勾后 revision 刷新与左区消退`() = runBlocking {
        seedBlock("2026-11-14", 600, 660, "missed")
        settle { vm.uiState.value.data?.leftovers?.size == 1 }
        val leftover = vm.uiState.value.data!!.leftovers.first()
        vm.tickBlock(leftover)
        settle { vm.uiState.value.data?.leftovers?.isEmpty() == true }
        // 补勾=done 落库（revision 链路：命令 bump → 重载）
        assertTrue(repo.blocksOnDate("2026-11-14").toString().contains("done"))
    }

    @Test
    fun `确认卡批量确认 单块冲突吞错不中断`() = runBlocking {
        val p1 = seedBlock("2026-11-15", 600, 660, "proposed")
        val p2 = seedBlock("2026-11-15", 700, 760, "proposed")
        settle { vm.uiState.value.data?.proposedAi?.size == 2 }
        // 批量快照（旧 version）；他端先改 p1 → p1 CAS 失败吞错留提案态，p2 照常确认
        val snapshot = vm.uiState.value.data!!.proposedAi
        repo.patchBlock(idOf(p1), buildJsonObject { put("label", "他端改动") }, null)
        vm.confirmAllSuspend(snapshot)
        settle { repo.blocksOnDate("2026-11-15").toString().let { it.contains("confirmed") } }
        val b1 = repo.blockById(idOf(p1))!!
        val b2 = repo.blockById(idOf(p2))!!
        assertTrue(b1.toString().contains("proposed"), "版本冲突块留提案态（单块吞错不中断）")
        assertTrue(b2.toString().contains("confirmed"))
    }

    @Test
    fun `确认卡批量否决 块蒸发且提案区消退`() = runBlocking {
        seedBlock("2026-11-15", 600, 660, "proposed")
        settle { vm.uiState.value.data?.proposedAi?.size == 1 }
        val proposed = vm.uiState.value.data!!.proposedAi
        vm.rejectAllSuspend(proposed, "不想做")
        settle { vm.uiState.value.data?.proposedAi?.isEmpty() == true }
        // reject=块删除蒸发（BlockOpsHandler 语义）
        assertTrue(repo.blocksOnDate("2026-11-15").toString().let { it.indexOf("proposed") == -1 })
    }

    @Test
    fun `电量降档 update_settings 落库与事件反馈`() = runBlocking {
        // reflow envelope 前提：作息边界已初始化（reflow_day 拒 settings_missing）
        repo.settingsSet(buildJsonObject {
            put("wake_time", JsonPrimitive("420"))
            put("sleep_time", JsonPrimitive("1380"))
        })
        val events = mutableListOf<UiEvent>()
        val job = launch(Dispatchers.Unconfined) {
            vm.events.collect { events.add(it) }
        }
        vm.setEnergy("low")
        settle { repo.settingsGet("today_energy") == "low" }
        // N5.5 reflow 原生化：note=「降档重算完成：…」（空库 0/0 项）
        settle { events.any { it.durationMs == 3000L && it.message.contains("降档重算完成") } }
        job.cancel()
    }

    @Test
    fun `快记草稿 300ms 防抖落盘与琥珀点信号`() = runBlocking {
        assertNull(repo.settingsGet("quick_note_draft_text"))
        vm.onDraftTextChanged("买牛奶")
        assertTrue(vm.hasDraft.value, "输入即点亮（防抖落盘前信号先行）")
        settle { repo.settingsGet("quick_note_draft_text") == "买牛奶" }
        vm.onDraftMetaChanged(important = true)
        settle { repo.settingsGet("quick_note_draft_important") == "1" }
    }

    @Test
    fun `快记提交 quick_capture 落清单清草稿事件浮动`() = runBlocking {
        val events = mutableListOf<UiEvent>()
        val job = launch(Dispatchers.Unconfined) {
            vm.events.collect { events.add(it) }
        }
        vm.onDraftTextChanged("交房租")
        val ok = vm.submitQuickNote("交房租", important = true, deadline = LocalDate.of(2026, 11, 20))
        assertTrue(ok)
        settle { repo.listPlans().toString().contains("交房租") }
        assertNull(repo.settingsGet("quick_note_draft_text"), "提交成功清草稿")
        assertEquals(false, vm.hasDraft.value)
        settle { events.any { it.message == "已收入清单" && it.floating && it.durationMs == 1000L } }
        job.cancel()
    }

    @Test
    fun `抽屉中途收起 未提交冲刷尾巴`() = runBlocking {
        vm.onDraftTextChanged("半句话")
        // 防抖窗口内直接收起（冲刷尾巴，§C 零丢失语义）
        vm.onSheetClosed()
        settle { repo.settingsGet("quick_note_draft_text") == "半句话" }
    }

    // ── 三手势回调接线（S4 对拍发现项：onDegrade/onMelt/onSwap 落库链 UI 侧可达）──

    @Test
    fun `手势融化 右滑 melt 物理回池`() = runBlocking {
        val b = seedBlock("2026-11-15", 600, 660, "proposed")
        settle { vm.uiState.value.data?.blocks?.isNotEmpty() == true }
        vm.meltBlock(app.shiguang.core.model.ScheduleBlock.fromMap(b))
        settle { repo.blocksOnDate("2026-11-15").toString().let { !it.contains(idOf(b)) } }
        assertNull(repo.blockById(idOf(b)), "melted=物理删除无痕回池")
    }

    @Test
    fun `手势降档 长按 degrade 坍缩火种胶囊`() = runBlocking {
        val b = seedBlock("2026-11-15", 600, 660, "proposed")
        vm.degradeBlock(app.shiguang.core.model.ScheduleBlock.fromMap(b))
        settle {
            val row = repo.blockById(idOf(b))!!
            (row["end_min"] as JsonPrimitive).content == "605"
        }
        val row = repo.blockById(idOf(b))!!
        assertTrue(row.toString().contains("spark"), "降档=5 分钟火种胶囊")
    }

    @Test
    fun `手势换乘 左滑候选挑选后换入`() = runBlocking {
        val original = repo.addPlan(buildJsonObject { put("title", "深活") })
        val candidate = repo.addPlan(buildJsonObject { put("title", "散步") }) // 缺省 light/anywhere=候选
        val b = repo.addBlock(buildJsonObject {
            put("date", "2026-11-15"); put("start_min", 600); put("end_min", 660)
            put("plan_id", idOf(original)); put("status", "confirmed"); put("source", "ai")
        })
        val block = app.shiguang.core.model.ScheduleBlock.fromMap(b)
        val cands = vm.swapCandidates(block)
        assertEquals(1, cands.size, "候选=light/anywhere 未排期计划")
        assertEquals(idOf(candidate), cands.first().id)
        vm.swapBlock(block, cands.first().id!!)
        settle {
            // applySwap=旧块物理删除 + 新块换入（原任务无痕回池，Dart _applySwap 口径）
            val rows = repo.blocksOnDate("2026-11-15").toString()
            !rows.contains(idOf(b)) && rows.contains(idOf(candidate))
        }
    }
}
