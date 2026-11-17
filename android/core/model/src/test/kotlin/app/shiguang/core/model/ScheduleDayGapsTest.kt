package app.shiguang.core.model

import kotlin.test.Test
import kotlin.test.assertEquals

/** gapsIn 行为对拍（Dart util/schedule_day.dart gapsIn 镜像；派生保护区渲染几何）。 */
class ScheduleDayGapsTest {

    @Test
    fun `空占用返回整窗单空隙`() {
        assertEquals(listOf(Pair(420, 1380)), ScheduleDay.gapsIn(420, 1380, emptyList()))
    }

    @Test
    fun `中部占用切出两段空隙`() {
        val gaps = ScheduleDay.gapsIn(420, 1380, listOf(Pair(600, 720)))
        assertEquals(listOf(Pair(420, 600), Pair(720, 1380)), gaps)
    }

    @Test
    fun `占用越窗两端裁剪`() {
        // 占用左越窗起点、右越窗终点：右邻占用起点 1000 > 窗尾 900 → 空隙钳到窗尾
        assertEquals(
            listOf(Pair(600, 900)),
            ScheduleDay.gapsIn(480, 900, listOf(Pair(400, 600), Pair(1000, 1100))),
        )
    }

    @Test
    fun `相接占用经并集合并后视为一整段`() {
        assertEquals(
            listOf(Pair(420, 600), Pair(900, 1380)),
            ScheduleDay.gapsIn(420, 1380, listOf(Pair(600, 800), Pair(800, 900))),
        )
    }

    @Test
    fun `占用完全罩住窗口返回空表`() {
        assertEquals(emptyList(), ScheduleDay.gapsIn(600, 720, listOf(Pair(420, 1380))))
    }

    @Test
    fun `占用整段落在窗口之后不产生零长空隙`() {
        assertEquals(
            listOf(Pair(420, 600)),
            ScheduleDay.gapsIn(420, 600, listOf(Pair(900, 1000))),
        )
    }
}
