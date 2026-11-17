package app.shiguang.shiguang.ui.collab

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 协同日程规格对拍（behavior/member_calendar.md §A 逐字，T4.3）：三态预占映射 +
 * 状态/类型标签 + 响应分流（ACCEPT/AVAILABILITY/VOTE/REJECT）与载荷键——
 * 口径=lib/ui/collab/member_calendar_page.dart 镜像。
 */
class MemberCalendarSpecTest {

    @Test
    fun `三态预占映射`() {
        assertEquals(
            MemberCalendarViewModel.ReservationTier.CONFIRMED,
            MemberCalendarViewModel.tierOf("CONFIRMED", "DIRECTIVE"),
        )
        assertEquals(
            MemberCalendarViewModel.ReservationTier.HARD_TENTATIVE,
            MemberCalendarViewModel.tierOf("PROPOSED", "DIRECTIVE"),
        )
        assertEquals(
            MemberCalendarViewModel.ReservationTier.SOFT_CANDIDATE,
            MemberCalendarViewModel.tierOf("PROPOSED", "CONSENSUS"),
        )
        assertEquals(
            MemberCalendarViewModel.ReservationTier.SOFT_CANDIDATE,
            MemberCalendarViewModel.tierOf("STALLED", "CONSENSUS"),
        )
    }

    @Test
    fun `状态标签逐字`() {
        assertEquals("协商中", MemberCalendarViewModel.statusLabel("PROPOSED"))
        assertEquals("已确认", MemberCalendarViewModel.statusLabel("CONFIRMED"))
        assertEquals("待发起人闭环", MemberCalendarViewModel.statusLabel("STALLED"))
        assertEquals("已流局", MemberCalendarViewModel.statusLabel("EXPIRED"))
        assertEquals("已取消", MemberCalendarViewModel.statusLabel("CANCELLED"))
        assertEquals("ARCHIVED", MemberCalendarViewModel.statusLabel("ARCHIVED"))
    }

    @Test
    fun `类型标签逐字`() {
        assertEquals("团队调度", MemberCalendarViewModel.proposalLabel("DIRECTIVE"))
        assertEquals("生活预约", MemberCalendarViewModel.proposalLabel("CONSENSUS"))
        assertEquals("生活预约", MemberCalendarViewModel.proposalLabel("OTHER"))
    }

    @Test
    fun `同意动作分流与载荷`() {
        assertEquals("AVAILABILITY", MemberCalendarViewModel.acceptActionOf("CONSENSUS"))
        assertEquals("ACCEPT", MemberCalendarViewModel.acceptActionOf("DIRECTIVE"))
        val payload = MemberCalendarViewModel.acceptPayloadOf("s1").jsonObject
        assertEquals("s1", payload["step_id"]!!.jsonPrimitive.content)
        assertEquals("OK", payload["decision"]!!.jsonPrimitive.content)
    }

    @Test
    fun `拒绝载荷分流 DIRECTIVE 双维键`() {
        val payload = MemberCalendarViewModel.rejectPayloadOf(
            "DIRECTIVE",
            "s2",
            RejectResult(
                reason = RejectDimension.TIME_CONFLICT,
                subReason = "PERSONAL",
                detail = "周三天不用",
                privacy = RejectPrivacy.INITIATOR_ONLY,
                suggestReassign = "u9",
            ),
        ).jsonObject
        assertEquals("s2", payload["step_id"]!!.jsonPrimitive.content)
        assertEquals("TIME_CONFLICT", payload["reason"]!!.jsonPrimitive.content)
        assertEquals("PERSONAL", payload["sub_reason"]!!.jsonPrimitive.content)
        assertEquals("周三天不用", payload["detail"]!!.jsonPrimitive.content)
        assertEquals("INITIATOR_ONLY", payload["privacy"]!!.jsonPrimitive.content)
        assertEquals("u9", payload["suggest_reassign"]!!.jsonPrimitive.content)
    }

    @Test
    fun `拒绝载荷分流 CONSENSUS 走 VOTE NO 票`() {
        val payload = MemberCalendarViewModel.rejectPayloadOf(
            "CONSENSUS",
            "s3",
            RejectResult(
                reason = RejectDimension.ROLE_UNWILLING,
                subReason = "PRIORITY",
                detail = null,
                privacy = RejectPrivacy.PUBLIC,
                suggestReassign = null,
            ),
        ).jsonObject
        assertEquals("s3", payload["step_id"]!!.jsonPrimitive.content)
        assertEquals("NO", payload["preference"]!!.jsonPrimitive.content)
        assertEquals("ROLE_UNWILLING", payload["reason"]!!.jsonPrimitive.content)
        assertEquals(null, payload["detail"])
        assertEquals(null, payload["sub_reason"])
        assertEquals(null, payload["suggest_reassign"])
    }
}
