package app.shiguang.shiguang.ui.collab

import app.shiguang.core.model.collab.CollabFsm
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 双维拒绝面板规格对拍（behavior/reject_panel.md §A 逐字，T4.2）：FSM 枚举名逐字
 * （reason/privacy 与 CollabFsm.RejectReason 硬契约对位）+ 子类四选一映射与渲染序。
 */
class RejectPanelSpecTest {

    @Test
    fun `主维度枚举名与 FSM 契约逐字`() {
        assertEquals(CollabFsm.RejectReason.TIME_CONFLICT.name, RejectDimension.TIME_CONFLICT)
        assertEquals(CollabFsm.RejectReason.ROLE_UNWILLING.name, RejectDimension.ROLE_UNWILLING)
    }

    @Test
    fun `隐私分级枚举名逐字`() {
        assertEquals("PUBLIC", RejectPrivacy.PUBLIC)
        assertEquals("INITIATOR_ONLY", RejectPrivacy.INITIATOR_ONLY)
    }

    @Test
    fun `子类四选一映射与渲染序`() {
        assertEquals(
            listOf("NOT_MY_JOB", "PRIORITY", "MISSING_PREREQ", "PERSONAL"),
            RejectSubReasons.keys.toList(),
        )
        assertEquals("非我职责", RejectSubReasons["NOT_MY_JOB"])
        assertEquals("优先级不符", RejectSubReasons["PRIORITY"])
        assertEquals("缺前置条件", RejectSubReasons["MISSING_PREREQ"])
        assertEquals("个人原因", RejectSubReasons["PERSONAL"])
    }

    @Test
    fun `RejectResult 构造语义`() {
        val r = RejectResult(
            reason = RejectDimension.TIME_CONFLICT,
            subReason = "PERSONAL",
            detail = "  ",
            privacy = RejectPrivacy.PUBLIC,
            suggestReassign = null,
        )
        assertEquals(RejectDimension.TIME_CONFLICT, r.reason)
        assertEquals("PERSONAL", r.subReason)
        assertEquals(RejectPrivacy.PUBLIC, r.privacy)
        assertEquals(null, r.suggestReassign)
    }
}
