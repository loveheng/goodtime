package app.shiguang.shiguang.ui.collab

import app.shiguang.core.model.collab.CollabFsm
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 决策矩阵规格对拍（behavior/decision_matrix.md §A 逐字，T4.4）：状态/类型标签 +
 * M-of-N 门限回落 + 七动作载荷键——口径=lib/ui/collab/decision_matrix_page.dart 镜像。
 */
class DecisionMatrixSpecTest {

    @Test
    fun `步骤状态标签逐字`() {
        assertEquals("已达标", DecisionMatrixViewModel.stepStatusLabel(CollabFsm.StepStatus.READY))
        assertEquals("时间冲突", DecisionMatrixViewModel.stepStatusLabel(CollabFsm.StepStatus.TIME_CLASH))
        assertEquals("权责异议", DecisionMatrixViewModel.stepStatusLabel(CollabFsm.StepStatus.ROLE_CLASH))
        assertEquals("可选", DecisionMatrixViewModel.stepStatusLabel(CollabFsm.StepStatus.OPTIONAL))
        assertEquals("已裁撤", DecisionMatrixViewModel.stepStatusLabel(CollabFsm.StepStatus.PRUNED))
        assertEquals("待确认", DecisionMatrixViewModel.stepStatusLabel(CollabFsm.StepStatus.PENDING))
        assertEquals("待确认", DecisionMatrixViewModel.stepStatusLabel(CollabFsm.StepStatus.RECHECK))
    }

    @Test
    fun `包状态标签逐字`() {
        assertEquals("协商中", DecisionMatrixViewModel.bundleStatusLabel(CollabFsm.BundleStatus.PROPOSED))
        assertEquals("已确认", DecisionMatrixViewModel.bundleStatusLabel(CollabFsm.BundleStatus.CONFIRMED))
        assertEquals("待闭环", DecisionMatrixViewModel.bundleStatusLabel(CollabFsm.BundleStatus.STALLED))
        assertEquals("已流局", DecisionMatrixViewModel.bundleStatusLabel(CollabFsm.BundleStatus.EXPIRED))
        assertEquals("已取消", DecisionMatrixViewModel.bundleStatusLabel(CollabFsm.BundleStatus.CANCELLED))
        assertEquals("已归档", DecisionMatrixViewModel.bundleStatusLabel(CollabFsm.BundleStatus.ARCHIVED))
        assertEquals("本地已释放", DecisionMatrixViewModel.bundleStatusLabel(CollabFsm.BundleStatus.ABANDONED_LOCALLY))
        assertEquals("草稿", DecisionMatrixViewModel.bundleStatusLabel(CollabFsm.BundleStatus.DRAFT))
    }

    @Test
    fun `类型标签与门限回落`() {
        assertEquals("生活预约", DecisionMatrixViewModel.proposalLabel(CollabFsm.ProposalType.CONSENSUS))
        assertEquals("团队调度", DecisionMatrixViewModel.proposalLabel(CollabFsm.ProposalType.DIRECTIVE))
        val zero = CollabFsm.StepState(
            stepId = "s1",
            requiredUids = listOf("a", "b", "c"),
            threshold = 0,
            isOptional = false,
            status = CollabFsm.StepStatus.PENDING,
            revision = 1,
            ballots = emptyMap(),
        )
        val explicit = zero.copy(threshold = 2)
        assertEquals(3, DecisionMatrixViewModel.requiredCount(zero))
        assertEquals(2, DecisionMatrixViewModel.requiredCount(explicit))
    }

    @Test
    fun `七动作载荷键逐字`() {
        val nudge = DecisionMatrixViewModel.nudgePayloadOf("s1", "u2").jsonObject
        assertEquals("s1", nudge["step_id"]!!.jsonPrimitive.content)
        assertEquals("u2", nudge["nudge_uids"]!!.jsonArray[0].jsonPrimitive.content)
        val reassign = DecisionMatrixViewModel.reassignPayloadOf("s1", "u3").jsonObject
        assertEquals("u3", reassign["reassign_to"]!!.jsonArray[0].jsonPrimitive.content)
        val bypass = DecisionMatrixViewModel.bypassPayloadOf("s1", "u4").jsonObject
        assertEquals("u4", bypass["bypass_uids"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("s1", DecisionMatrixViewModel.prunePayloadOf("s1").jsonObject["step_id"]!!.jsonPrimitive.content)
        assertEquals(0, DecisionMatrixViewModel.cancelPayload.jsonObject.size)
        assertEquals(0, DecisionMatrixViewModel.reopenPayload.jsonObject.size)
        assertEquals("全体注意", DecisionMatrixViewModel.briefingPayloadOf("全体注意").jsonObject["briefing"]!!.jsonPrimitive.content)
    }

    @Test
    fun `反馈文案逐字`() {
        assertEquals("已催办，联网后自动发送", DecisionMatrixViewModel.NudgeToast)
        assertEquals("已记下，联网后自动发送", DecisionMatrixViewModel.GenericToast)
    }
}
