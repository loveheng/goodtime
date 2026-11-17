package app.shiguang.shiguang.ui.collab

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 审计时间轴规格对拍（behavior/audit_timeline.md §A 逐字，T4.6）：17 动作标签映射 +
 * 半盲骨架提示判定 + 时间格式（含 ms<=0 空显示）——口径=lib/ui/collab/audit_timeline_page.dart 镜像。
 */
class AuditTimelineSpecTest {

    @Test
    fun `动作标签十七项逐字`() {
        assertEquals("发起了方案", AuditTimelineViewModel.actionLabel("PROPOSE"))
        assertEquals("同意了这一步", AuditTimelineViewModel.actionLabel("ACCEPT"))
        assertEquals("提出了异议", AuditTimelineViewModel.actionLabel("REJECT"))
        assertEquals("调整了时间", AuditTimelineViewModel.actionLabel("PATCH_TIME"))
        assertEquals("转派了这一步", AuditTimelineViewModel.actionLabel("REASSIGN"))
        assertEquals("将这一步设为可选", AuditTimelineViewModel.actionLabel("MAKE_OPTIONAL"))
        assertEquals("跳过了这一步", AuditTimelineViewModel.actionLabel("BYPASS"))
        assertEquals("裁撤了这一步", AuditTimelineViewModel.actionLabel("PRUNE"))
        assertEquals("重新打开了方案", AuditTimelineViewModel.actionLabel("REOPEN"))
        assertEquals("取消了方案", AuditTimelineViewModel.actionLabel("CANCEL"))
        assertEquals("投了一票", AuditTimelineViewModel.actionLabel("VOTE"))
        assertEquals("增加了候选时段", AuditTimelineViewModel.actionLabel("ADD_SLOT"))
        assertEquals("落槌定了时段", AuditTimelineViewModel.actionLabel("FORCE_FINALIZE"))
        assertEquals("催促了待确认成员", AuditTimelineViewModel.actionLabel("NUDGE"))
        assertEquals("更新了备忘", AuditTimelineViewModel.actionLabel("BRIEFING_UPDATE"))
        assertEquals("记录了便签", AuditTimelineViewModel.actionLabel("STEP_NOTE"))
        assertEquals("SOME_FUTURE_ACTION", AuditTimelineViewModel.actionLabel("SOME_FUTURE_ACTION"))
    }

    @Test
    fun `半盲骨架提示判定`() {
        assertEquals("已私密反馈发起人，等待调整", AuditTimelineViewModel.privacyHint("REJECT", "u9", "me1"))
        // 本人知道自己的理由——不附提示
        assertEquals(null, AuditTimelineViewModel.privacyHint("REJECT", "me1", "me1"))
        // 非 REJECT 不附提示
        assertEquals(null, AuditTimelineViewModel.privacyHint("ACCEPT", "u9", "me1"))
        // 本人身份未知（未注册）——他人异议仍附提示
        assertEquals("已私密反馈发起人，等待调整", AuditTimelineViewModel.privacyHint("REJECT", "u9", null))
    }

    @Test
    fun `时间格式与空值`() {
        assertEquals("", AuditTimelineViewModel.formatTime(0))
        assertEquals("", AuditTimelineViewModel.formatTime(-5))
        // 固定钟：2026-03-05 07:09（系统时区）→ MM-dd HH:mm
        val ms = java.time.LocalDateTime.of(2026, 3, 5, 7, 9)
            .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals("03-05 07:09", AuditTimelineViewModel.formatTime(ms))
    }

    @Test
    fun `反馈文案与便签上限`() {
        assertEquals("已记下，联网后自动发送", AuditTimelineViewModel.GenericToast)
        assertEquals(50, AuditTimelineViewModel.NoteMaxLength)
    }
}
