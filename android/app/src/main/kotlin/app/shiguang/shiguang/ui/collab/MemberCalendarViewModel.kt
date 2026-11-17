package app.shiguang.shiguang.ui.collab

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.shiguang.core.model.collab.AccountStatus
import app.shiguang.core.model.collab.CollabBundleHeader
import app.shiguang.core.model.collab.CollabBundleRepository
import app.shiguang.core.model.collab.CollabStep
import app.shiguang.core.model.collab.OutboxRepository
import app.shiguang.core.model.collab.AccountSession
import app.shiguang.core.model.collab.relay.BundleComposeKit
import app.shiguang.shiguang.ShiguangApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 协同日程轻 ViewModel（behavior/member_calendar.md 转正清单 §A/§C，T4.3）：
 * 包列表+逐包步骤两查询装配（Dart _load 镜像，无 JOIN 无分页）+ 响应命令
 * （全经离线发件箱落库，联网由常驻调度弹出——零本地 ScheduleCommand）。
 *
 * 分期对拍 F1（清单 §C）：ui-pages §9.1 目标态=邀约与本地日程混看时间轴叠块；
 * 实现现状=列表卡片形态（物化时间槽已在 decision 回填，叠块另切片）。
 */
class MemberCalendarViewModel(
    private val collabRepository: CollabBundleRepository,
    private val outboxRepository: OutboxRepository,
    /** 发起套件装配口（容器注入；null=门禁引导文案两分支）。 */
    private val loadComposeKit: suspend () -> BundleComposeKit?,
    private val accountSession: AccountSession,
    /** 本机协同身份 uid（拒绝面板转派候选排除自己；未注册=null）。 */
    private val currentUserId: suspend () -> String?,
) : ViewModel() {

    /** 预占三态（§11.2 B 页视觉三级）。 */
    enum class ReservationTier { CONFIRMED, HARD_TENTATIVE, SOFT_CANDIDATE }

    /** 单包装配视图（头部+步骤投影）。 */
    data class BundleView(
        val bundle: CollabBundleHeader,
        val steps: List<CollabStep>,
    )

    data class MemberCalendarUiState(
        val loading: Boolean = true,
        val bundles: List<BundleView> = emptyList(),
        /** 本机协同身份 uid（转派候选排除用）。 */
        val currentUserId: String? = null,
    )

    private val _uiState = MutableStateFlow(MemberCalendarUiState())
    val uiState: StateFlow<MemberCalendarUiState> = _uiState

    /** 反馈面（SnackBar 文案；页面消费）。 */
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val events: SharedFlow<String> = _events

    /** 列表装配（Dart _load 镜像：listBundles 全量 + 逐包 listSteps；返回本页重拉；IO 调度——AndroidSqlStore 直驱无内建切换）。 */
    fun refresh() {
        viewModelScope.launch {
            val views = withContext(Dispatchers.IO) {
                collabRepository.listBundles().map { b -> BundleView(b, collabRepository.listSteps(b.id)) }
            }
            _uiState.value = _uiState.value.copy(loading = false, bundles = views)
        }
    }

    /** 本机身份 uid 装载（拒绝面板转派候选排除自己；IO=安全区读）。 */
    fun loadCurrentUser() {
        viewModelScope.launch {
            val me = withContext(Dispatchers.IO) { currentUserId() }
            _uiState.value = _uiState.value.copy(currentUserId = me)
        }
    }

    /**
     * 发起协同门禁（FAB 点击；Dart _compose 镜像）：kit null=未登录或已登录无身份
     * 记录——文案按真实来路指路（防已登录用户对着「请登录」死循环）。
     *
     * @return true=可导航发起页（kit 装配成功）
     */
    suspend fun loadComposeGate(): Boolean {
        val kit = loadComposeKit()
        if (kit != null) return true
        val loggedIn = accountSession.status.value == AccountStatus.LOGGED_IN
        toast(
            if (loggedIn) {
                "本机协同身份未就绪，到「设置 → 协同账号」重新注册"
            } else {
                "先到「设置 → 协同账号」登录，才能发出邀约"
            },
        )
        return false
    }

    /** 同意响应（§A：CONSENSUS=AVAILABILITY / 其余=ACCEPT，decision OK）。 */
    suspend fun accept(bundle: CollabBundleHeader, step: CollabStep) {
        outboxRepository.enqueue(
            bundleId = bundle.id,
            actionType = acceptActionOf(bundle.proposalType),
            payload = acceptPayloadOf(step.id),
        )
        toast("已记下，联网后自动发送")
    }

    /** 拒绝响应（§A 分流：CONSENSUS=VOTE NO 票附结构化理由 / 其余=REJECT 双维载荷）。 */
    suspend fun reject(bundle: CollabBundleHeader, step: CollabStep, result: RejectResult) {
        outboxRepository.enqueue(
            bundleId = bundle.id,
            actionType = if (bundle.proposalType == "CONSENSUS") "VOTE" else "REJECT",
            payload = rejectPayloadOf(bundle.proposalType, step.id, result),
        )
        toast("已记下，联网后自动发送")
    }

    private fun toast(text: String) {
        _events.tryEmit(text)
    }

    companion object {
        /** 类型标签（§A 逐字）。 */
        fun proposalLabel(proposalType: String): String =
            if (proposalType == "DIRECTIVE") "团队调度" else "生活预约"

        /** 状态标签（§A 逐字：未知原样显示）。 */
        fun statusLabel(status: String): String = when (status) {
            "PROPOSED" -> "协商中"
            "CONFIRMED" -> "已确认"
            "STALLED" -> "待发起人闭环"
            "EXPIRED" -> "已流局"
            "CANCELLED" -> "已取消"
            else -> status
        }

        /** 三态预占映射（§A：CONFIRMED→实心；DIRECTIVE→主色虚线；其余→浅灰细线）。 */
        fun tierOf(status: String, proposalType: String): ReservationTier = when {
            status == "CONFIRMED" -> ReservationTier.CONFIRMED
            proposalType == "DIRECTIVE" -> ReservationTier.HARD_TENTATIVE
            else -> ReservationTier.SOFT_CANDIDATE
        }

        /** 同意动作分流（§A 纯函数）。 */
        fun acceptActionOf(proposalType: String): String =
            if (proposalType == "CONSENSUS") "AVAILABILITY" else "ACCEPT"

        /** 同意载荷（§A：{step_id, decision:OK}）。 */
        fun acceptPayloadOf(stepId: String): JsonObject = buildJsonObject {
            put("step_id", stepId)
            put("decision", "OK")
        }

        /** 拒绝载荷分流（§A 逐字：detail 非空才带；suggest_reassign 空不带）。 */
        fun rejectPayloadOf(proposalType: String, stepId: String, result: RejectResult): JsonObject {
            val detail = result.detail?.takeIf { it.isNotEmpty() }
            return if (proposalType == "CONSENSUS") {
                // Consensus 无步骤权责异议：异议走时段投票（NO 票），附理由供发起人参考。
                buildJsonObject {
                    put("step_id", stepId)
                    put("preference", "NO")
                    put("reason", result.reason)
                    if (detail != null) put("detail", detail)
                }
            } else {
                buildJsonObject {
                    put("step_id", stepId)
                    put("reason", result.reason)
                    put("sub_reason", result.subReason)
                    if (detail != null) put("detail", detail)
                    put("privacy", result.privacy)
                    if (result.suggestReassign != null) put("suggest_reassign", result.suggestReassign!!)
                }
            }
        }

        /** Manual DI Factory（容器协同栈注入）。 */
        val factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as ShiguangApplication
                MemberCalendarViewModel(
                    collabRepository = app.container.collabRepository,
                    outboxRepository = app.container.outboxRepository,
                    loadComposeKit = { app.container.loadBundleComposeKit() },
                    accountSession = app.container.accountSession,
                    currentUserId = { app.container.identityStore.load()?.userId },
                )
            }
        }
    }
}
