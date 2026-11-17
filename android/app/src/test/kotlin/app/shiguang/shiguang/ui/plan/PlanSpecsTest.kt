package app.shiguang.shiguang.ui.plan

import org.junit.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 计划详情族纯函数件对拍（plan_detail/plan_background 轮，D24 转正清单 §A/§C）：
 * roadmapProgress/backgroundBadge/dateWindowDays/splitTags——口径=Flutter 实现逐字镜像。
 */
class PlanSpecsTest {

    @Test
    fun roadmap_noSection_returnsNull() {
        assertNull(roadmapProgress("纯 spec，没有路线图"))
    }

    @Test
    fun roadmap_countsChecklist() {
        val spec = "前言\n## 路线图\n- [x] 第一步\n- [ ] 第二步\n- [x] 第三步\n"
        assertEquals("当前进度 2/3", roadmapProgress(spec))
    }

    @Test
    fun roadmap_truncatesAtNextSection() {
        val spec = "## 路线图\n- [ ] 待办\n## 别的章\n- [x] 不计数\n"
        assertEquals("当前进度 0/1", roadmapProgress(spec))
    }

    @Test
    fun roadmap_zeroChecklist_returnsNull() {
        assertNull(roadmapProgress("## 路线图\n只有叙述没有清单\n"))
    }

    @Test
    fun roadmap_withoutLeadingHashStillCounts() {
        // 行首计数：缩进与无前导空格均命中（(?m)^\\s*-\\s+\\[x\\]）
        val spec = "## 路线图\n  - [x] 缩进\n- [x] 顶格\n- [x]顶格无空格\n"
        assertEquals("当前进度 3/3", roadmapProgress(spec))
    }

    @Test
    fun badge_singleDay() {
        assertEquals("[10.08]", backgroundBadge(listOf("2026-10-08")))
    }

    @Test
    fun badge_rangeFirstLast() {
        assertEquals("[10.08–10.10]", backgroundBadge(listOf("2026-10-08", "2026-10-09", "2026-10-10")))
    }

    @Test
    fun badge_emptyReturnsNull() {
        assertNull(backgroundBadge(emptyList()))
    }

    @Test
    fun badge_malformedFallsBackRaw() {
        // 解析失败原文回落（Dart tryParseIsoDate null 分支同构）
        assertEquals("[bad–10.10]", backgroundBadge(listOf("bad", "2026-10-10")))
    }

    @Test
    fun window_singleDay() {
        val d = LocalDate.of(2026, 11, 15)
        assertEquals(listOf("2026-11-15"), dateWindowDays(d, d))
    }

    @Test
    fun window_enumeratesInclusive() {
        assertEquals(
            listOf("2026-10-30", "2026-10-31", "2026-11-01"),
            dateWindowDays(LocalDate.of(2026, 10, 30), LocalDate.of(2026, 11, 1)),
        )
    }

    @Test
    fun tags_splitOnSpaceCommaDunhao() {
        assertEquals(
            listOf("#健康", "#体力", "老人"),
            splitTags(" #健康, #体力，老人、  "),
        )
    }

    @Test
    fun tags_emptyAndSeparatorOnly() {
        assertTrue(splitTags("").isEmpty())
        assertTrue(splitTags("、、，， ").isEmpty())
    }
}
