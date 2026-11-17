package app.shiguang.shiguang.ui.collab

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.shiguang.core.model.collab.CollabBundleRepository
import app.shiguang.core.model.collab.CollabStep
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 审计时间轴轻 ViewModel（behavior/audit_timeline.md 转正清单 §A，T4.6）：
 * loadEvents 权威序（seq_id ASC——UI 不重排不倒序）+ 半盲投影（只渲染明文骨架，
 * 密文理由/便签不在本页解密——§4.2 铁律 1）+ STEP_NOTE ≤50 字覆盖式更新。
 */
class AuditTimelineViewModel(
    private val collabRepository: CollabBundleRepository,
    private val outboxRepository: OutboxRepository,
    private val bundleId: String,
    /** 本机协同身份 uid（半盲提示「本人知道自己的理由」判定）。 */
    private val currentUserId: suspend () -> String?,
) : ViewModel() {

    data class AuditTimelineUiState(
        val loading: Boolean = true,
        /** loadEvents 权威序（seq_id ASC）。 */
        val events: List<StoredCollabEvent> = emptyList(),
        /** 步骤清单（便签抽屉 ChoiceChip 数据源；listSteps 投影）。 */
        val steps: List<CollabStep> = emptyList(),
        /** 本机 uid（骨架提示判定）。 */
        val me: String? = null,
    )

    private val _uiState = MutableStateFlow(AuditTimelineUiState())
    val uiState: StateFlow<AuditTimelineUiState> = _uiState

    /** 反馈面（SnackBar 文案）。 */
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val events: SharedFlow<String> = _events

    /** 装载（IO）：事件权威序+步骤投影+本机身份。 */
    fun load() {
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                val events = collabRepository.loadEvents(bundleId)
                val steps = collabRepository.listSteps(bundleId)
                val me = currentUserId()
                Triple(events, steps, me)
            }
            _uiState.value = AuditTimelineUiState(loading = false, events = loaded.first, steps = loaded.second, me = loaded.third)
        }
    }

    /** 步骤便签保存（§A：STEP_NOTE {step_id, note} 覆盖式更新——单步骤一条）。 */
    suspend fun saveNote(stepId: String, note: String) {
        outboxRepository.enqueue(
            bundleId = bundleId,
            actionType = "STEP_NOTE",
            payload = buildJsonObject {
                put("step_id", stepId)
                put("note", note)
            },
        )
        toast(GenericToast)
    }

    private fun toast(text: String) {
        _events.tryEmit(text)
    }

    companion object {
        /** 便签保存反馈（§A 逐字）。 */
        const val GenericToast = "已记下，联网后自动发送"

        /** 便签上限（§A：≤50 字覆盖式）。 */
        const val NoteMaxLength = 50

        /** 半盲骨架提示（§A 逐字：REJECT 非本人——本人知道自己的理由不附提示）。 */
        fun privacyHint(actionType: String, senderId: String, me: String?): String? =
            if (actionType == "REJECT" && senderId != me) "已私密反馈发起人，等待调整" else null

        /** 动作→标签映射（§A 17 项逐字；图标在页面层 when 承载）。 */
        fun actionLabel(action: String): String = when (action) {
            "PROPOSE" -> "发起了方案"
            "ACCEPT" -> "同意了这一步"
            "REJECT" -> "提出了异议"
            "PATCH_TIME" -> "调整了时间"
            "REASSIGN" -> "转派了这一步"
            "MAKE_OPTIONAL" -> "将这一步设为可选"
            "BYPASS" -> "跳过了这一步"
            "PRUNE" -> "裁撤了这一步"
            "REOPEN" -> "重新打开了方案"
            "CANCEL" -> "取消了方案"
            "VOTE" -> "投了一票"
            "ADD_SLOT" -> "增加了候选时段"
            "FORCE_FINALIZE" -> "落槌定了时段"
            "NUDGE" -> "催促了待确认成员"
            "BRIEFING_UPDATE" -> "更新了备忘"
            "STEP_NOTE" -> "记录了便签"
            else -> action
        }

        /** 事件时间格式（§A：MM-dd HH:mm 双位补零；ms<=0=本地未上行事件显示空）。 */
        fun formatTime(ms: Long): String {
            if (ms <= 0) return ""
            val t = java.time.LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(ms),
                java.time.ZoneId.systemDefault(),
            )
            return "%02d-%02d %02d:%02d".format(t.monthValue, t.dayOfMonth, t.hour, t.minute)
        }

        /** Manual DI Factory（容器协同栈注入）。 */
        fun factory(bundleId: String): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as ShiguangApplication
                AuditTimelineViewModel(
                    collabRepository = app.container.collabRepository,
                    outboxRepository = app.container.outboxRepository,
                    bundleId = bundleId,
                    currentUserId = { app.container.identityStore.load()?.userId },
                )
            }
        }
    }
}
