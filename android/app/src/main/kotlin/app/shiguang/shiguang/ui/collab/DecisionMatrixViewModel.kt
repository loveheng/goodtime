package app.shiguang.shiguang.ui.collab

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.shiguang.core.model.collab.BundleKeySource
import app.shiguang.core.model.collab.CollabBundleRepository
import app.shiguang.core.model.collab.CollabFsm
import app.shiguang.core.model.collab.materializeBundle
import app.shiguang.core.model.collab.replayBundle
import app.shiguang.core.model.collab.OutboxRepository
import app.shiguang.core.model.collab.StoredCollabEvent
import app.shiguang.shiguang.ShiguangApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * 决策矩阵轻 ViewModel（behavior/decision_matrix.md 转正清单 §A/§C，T4.4）：
 * loadEvents→replayBundle 解密回放→materializeBundle 顺手回填时间槽（B 页时间槽/
 * A 页画板自此读物化表）——发起人聚合决策面板唯一权威数据源（FSM 派生态）。
 *
 * 门禁（§A）：isInitiator=currentUserId==initiatorId；非发起人动作集合全禁用
 * （页面 actions=null 对位）。bundleKey 半盲（vault 未解锁返回 null）——事件逐条
 * 跳过，状态回落近空态（头部与三键入口仍渲染，与 Dart 空态回退同视觉）。
 */
class DecisionMatrixViewModel(
    private val collabRepository: CollabBundleRepository,
    private val outboxRepository: OutboxRepository,
    /** K_bundle 取钥（容器注入 BundleKeySource；半盲纪律）。 */
    private val bundleKey: suspend (bundleId: String, keyVer: Int) -> ByteArray?,
    /** 本包发起人（头部装载自仓储 header）。 */
    private val bundleId: String,
    /** 本机协同身份 uid（isInitiator 判定；未注册=null）。 */
    private val currentUserId: suspend () -> String?,
) : ViewModel() {

    data class DecisionMatrixUiState(
        val loading: Boolean = true,
        /** 回放派生态；null=装载中。 */
        val state: CollabFsm.BundleState? = null,
        /** 发起人判定（非发起人全操作禁用）。 */
        val isInitiator: Boolean = false,
        /** 本机 uid（成员徽标「你」标注）。 */
        val me: String? = null,
    )

    private val _uiState = MutableStateFlow(DecisionMatrixUiState())
    val uiState: StateFlow<DecisionMatrixUiState> = _uiState

    /** 反馈面（SnackBar 文案；页面消费）。 */
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val events: SharedFlow<String> = _events

    /** 回放链（IO）：loadEvents → replayBundle → materializeBundle 顺手回填时间槽。 */
    fun load() {
        viewModelScope.launch {
            val st = withContext(Dispatchers.IO) {
                val events = collabRepository.loadEvents(bundleId)
                val me = currentUserId()
                val header = collabRepository.listBundles().firstOrNull { it.id == bundleId }
                val replayed = replayBundle(events, bundleKey, bundleId)
                // 顺手把派生态回填入 collab_steps——B 页时间槽/A 页画板自此读物化表
                runCatching { materializeBundle(collabRepository, bundleId, bundleKey) }
                me to (header?.let { replayed.copy(initiatorId = it.initiatorId) } ?: replayed)
            }
            _uiState.value = DecisionMatrixUiState(
                loading = false,
                state = st.second,
                isInitiator = st.first != null && st.second.initiatorId != null && st.first == st.second.initiatorId,
                me = st.first,
            )
        }
    }

    /** 催办（§A：NUDGE——toast 专属文案）。 */
    fun nudge(stepId: String, uid: String) = enqueueAction("NUDGE") {
        buildJsonObject {
            put("step_id", stepId)
            putJsonArray("nudge_uids") { add(uid) }
        }
    }

    /** 转派（§A：REASSIGN）。 */
    fun reassign(stepId: String, targetUid: String) = enqueueAction("REASSIGN") {
        buildJsonObject {
            put("step_id", stepId)
            putJsonArray("reassign_to") { add(targetUid) }
        }
    }

    /** 降级（§A：BYPASS）。 */
    fun bypass(stepId: String, uid: String) = enqueueAction("BYPASS") {
        buildJsonObject {
            put("step_id", stepId)
            putJsonArray("bypass_uids") { add(uid) }
        }
    }

    /** 裁撤（§A：PRUNE）。 */
    fun prune(stepId: String) = enqueueAction("PRUNE") { prunePayloadOf(stepId) }

    /** 取消方案（§A：CANCEL）。 */
    fun cancel() = enqueueAction("CANCEL") { cancelPayload }

    /** 重新打开方案（§A：REOPEN——FSM 门禁实装，UI 仅 confirmed 态露出）。 */
    fun reopen() = enqueueAction("REOPEN") { reopenPayload }

    /** 备忘更新（§A：BRIEFING_UPDATE——trim 后落库）。 */
    fun updateBriefing(text: String) = enqueueAction("BRIEFING_UPDATE") { briefingPayloadOf(text.trim()) }

    private fun enqueueAction(actionType: String, payload: () -> JsonObject) {
        viewModelScope.launch {
            outboxRepository.enqueue(bundleId = bundleId, actionType = actionType, payload = payload())
            toast(if (actionType == "NUDGE") NudgeToast else GenericToast)
        }
    }

    private fun toast(text: String) {
        _events.tryEmit(text)
    }

    companion object {
        /** 催办反馈（§A 逐字）。 */
        const val NudgeToast = "已催办，联网后自动发送"

        /** 其余六动作反馈（§A 逐字）。 */
        const val GenericToast = "已记下，联网后自动发送"

        /** 步骤状态标签（§A 逐字）。 */
        fun stepStatusLabel(s: CollabFsm.StepStatus): String = when (s) {
            CollabFsm.StepStatus.READY -> "已达标"
            CollabFsm.StepStatus.TIME_CLASH -> "时间冲突"
            CollabFsm.StepStatus.ROLE_CLASH -> "权责异议"
            CollabFsm.StepStatus.OPTIONAL -> "可选"
            CollabFsm.StepStatus.PRUNED -> "已裁撤"
            else -> "待确认"
        }

        /** 包状态标签（§A 逐字）。 */
        fun bundleStatusLabel(s: CollabFsm.BundleStatus): String = when (s) {
            CollabFsm.BundleStatus.PROPOSED -> "协商中"
            CollabFsm.BundleStatus.CONFIRMED -> "已确认"
            CollabFsm.BundleStatus.STALLED -> "待闭环"
            CollabFsm.BundleStatus.EXPIRED -> "已流局"
            CollabFsm.BundleStatus.CANCELLED -> "已取消"
            CollabFsm.BundleStatus.ARCHIVED -> "已归档"
            CollabFsm.BundleStatus.ABANDONED_LOCALLY -> "本地已释放"
            else -> "草稿"
        }

        /** 类型标签（§A：consensus→生活预约，其余→团队调度）。 */
        fun proposalLabel(t: CollabFsm.ProposalType): String =
            if (t == CollabFsm.ProposalType.CONSENSUS) "生活预约" else "团队调度"

        /** M-of-N 门限（§A：threshold<=0 回落必答全员数）。 */
        fun requiredCount(step: CollabFsm.StepState): Int =
            if (step.threshold <= 0) step.requiredUids.size else step.threshold

        /** 催办载荷（§A 纯函数）。 */
        fun nudgePayloadOf(stepId: String, uid: String): JsonObject = buildJsonObject {
            put("step_id", stepId)
            putJsonArray("nudge_uids") { add(uid) }
        }

        /** 转派载荷（§A 纯函数）。 */
        fun reassignPayloadOf(stepId: String, targetUid: String): JsonObject = buildJsonObject {
            put("step_id", stepId)
            putJsonArray("reassign_to") { add(targetUid) }
        }

        /** 降级载荷（§A 纯函数）。 */
        fun bypassPayloadOf(stepId: String, uid: String): JsonObject = buildJsonObject {
            put("step_id", stepId)
            putJsonArray("bypass_uids") { add(uid) }
        }

        /** 裁撤载荷（§A 纯函数）。 */
        fun prunePayloadOf(stepId: String): JsonObject = buildJsonObject { put("step_id", stepId) }

        /** 取消载荷（§A 纯函数：空对象）。 */
        val cancelPayload: JsonObject = buildJsonObject { }

        /** 重开载荷（§A 纯函数：空对象）。 */
        val reopenPayload: JsonObject = buildJsonObject { }

        /** 备忘载荷（§A 纯函数）。 */
        fun briefingPayloadOf(text: String): JsonObject = buildJsonObject { put("briefing", text) }

        /** Manual DI Factory（容器协同栈注入）。 */
        fun factory(bundleId: String): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as ShiguangApplication
                DecisionMatrixViewModel(
                    collabRepository = app.container.collabRepository,
                    outboxRepository = app.container.outboxRepository,
                    bundleKey = { bid, ver -> app.container.bundleKeySource(bid, ver) },
                    bundleId = bundleId,
                    currentUserId = { app.container.identityStore.load()?.userId },
                )
            }
        }
    }
}
