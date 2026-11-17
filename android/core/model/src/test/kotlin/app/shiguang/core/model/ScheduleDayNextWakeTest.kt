package app.shiguang.core.model

import kotlinx.serialization.json.Json
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

/** nextWakeBoundary 边界（fgs_housekeeper.md：过夜日切排程几何）。 */
class ScheduleDayNextWakeTest {

    @Test
    fun beforeWakeToday() {
        // wake=420（07:00），now=05:00 → 今天 07:00
        assertEquals(
            LocalDateTime.parse("2026-11-15T07:00"),
            ScheduleDay.nextWakeBoundary(LocalDateTime.parse("2026-11-15T05:00"), 420),
        )
    }

    @Test
    fun afterWakeTomorrow() {
        // now=23:30 → 明天 07:00
        assertEquals(
            LocalDateTime.parse("2026-11-16T07:00"),
            ScheduleDay.nextWakeBoundary(LocalDateTime.parse("2026-11-15T23:30"), 420),
        )
    }

    @Test
    fun exactlyAtWakeGoesTomorrow() {
        // 严格晚于 now：恰在边界 → 明天（避免同刻重复触发）
        assertEquals(
            LocalDateTime.parse("2026-11-16T07:00"),
            ScheduleDay.nextWakeBoundary(LocalDateTime.parse("2026-11-15T07:00"), 420),
        )
    }

    @Test
    fun jsonParseUnused() {
        // 占位断言防误删导入（无独立语义）
        assertEquals("null", Json.parseToJsonElement("null").toString())
    }
}
