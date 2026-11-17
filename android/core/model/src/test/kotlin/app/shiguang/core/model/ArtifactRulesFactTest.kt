package app.shiguang.core.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ArtifactRules 凭证派生规则对拍（plan_facts 轮，D24 转正清单 §A）：factLatestAnchorDate
 * /factExpired/factDateBadge 三件纯函数——口径=lib/ui/fact_sheet.dart 逐字镜像。
 */
class ArtifactRulesFactTest {

    private fun artifact(payload: JsonObject) = Artifact(
        id = "a1",
        category = Artifact.CATEGORY_TICKET,
        sourceKind = Artifact.SOURCE_BOOKING,
        state = Artifact.STATE_STRUCTURED,
        origin = Artifact.ORIGIN_AI,
        title = "去程高铁",
        badge = null,
        payload = payload,
        attachments = emptyList(),
        capturedBy = Artifact.CAPTURED_BY_ME,
        planId = "p1",
        createdAt = 1000L,
        updatedAt = 1000L,
        version = 0,
    )

    private fun anchors(vararg rows: JsonObject) = buildJsonObject {
        put("v", 1)
        put("time_anchors", JsonArray(rows.toList()))
    }

    @Test
    fun latestAnchor_noAnchors_returnsNull() {
        assertNull(ArtifactRules.factLatestAnchorDate(artifact(buildJsonObject { put("v", 1) })))
        // time_anchors 缺失与非数组（对象）同空口径
        assertNull(
            ArtifactRules.factLatestAnchorDate(
                artifact(buildJsonObject { put("time_anchors", buildJsonObject { }) }),
            ),
        )
    }

    @Test
    fun latestAnchor_spanTakesEndDate_nullEndFallsBackTo() {
        // span：end_date ?? date
        val span = artifact(anchors(buildJsonObject { put("date", "2026-11-15"); put("end_date", "2026-11-18") }))
        assertEquals("2026-11-18", ArtifactRules.factLatestAnchorDate(span))
        // end_date 显式 null（JsonNull）回落 date——Dart `as String? ?? raw['date']` 镜像
        val nullEnd = artifact(
            anchors(
                buildJsonObject {
                    put("date", "2026-11-10")
                    put("end_date", JsonNull)
                },
            ),
        )
        assertEquals("2026-11-10", ArtifactRules.factLatestAnchorDate(nullEnd))
    }

    @Test
    fun latestAnchor_multiAnchorsTakesMax() {
        val a = artifact(
            anchors(
                buildJsonObject { put("date", "2026-11-09"); put("end_date", "2026-11-10") },
                buildJsonObject { put("date", "2026-11-20") },
            ),
        )
        assertEquals("2026-11-20", ArtifactRules.factLatestAnchorDate(a))
        // 非对象行跳过（Dart `raw is! Map continue`）
        val mixed = artifact(
            anchors(
                buildJsonObject { put("date", "2026-11-05") },
                buildJsonObject { put("junk", 1) },
            ),
        )
        assertEquals("2026-11-05", ArtifactRules.factLatestAnchorDate(mixed))
    }

    @Test
    fun expired_noAnchorNeverExpires() {
        assertFalse(
            ArtifactRules.factExpired(artifact(buildJsonObject { put("v", 1) }), "2026-11-15"),
        )
    }

    @Test
    fun expired_boundaryComparison() {
        val past = artifact(anchors(buildJsonObject { put("date", "2026-11-14") }))
        assertTrue(ArtifactRules.factExpired(past, "2026-11-15"))
        val today = artifact(anchors(buildJsonObject { put("date", "2026-11-15") }))
        assertFalse(ArtifactRules.factExpired(today, "2026-11-15"))
        val future = artifact(anchors(buildJsonObject { put("date", "2026-11-20") }))
        assertFalse(ArtifactRules.factExpired(future, "2026-11-15"))
    }

    @Test
    fun badge_singleAndRange() {
        assertNull(ArtifactRules.factDateBadge(artifact(buildJsonObject { put("v", 1) })))
        val single = artifact(anchors(buildJsonObject { put("date", "2026-10-20") }))
        assertEquals("[10.20]", ArtifactRules.factDateBadge(single))
        val range = artifact(
            anchors(
                buildJsonObject { put("date", "2026-10-19"); put("end_date", "2026-10-17") },
                buildJsonObject { put("date", "2026-10-15") },
            ),
        )
        // 全集排序首末（15/17/19）——乱序锚点不破角标
        assertEquals("[10.15–10.19]", ArtifactRules.factDateBadge(range))
    }
}
