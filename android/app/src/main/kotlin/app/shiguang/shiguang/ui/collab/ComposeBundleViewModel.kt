package app.shiguang.shiguang.ui.collab

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.shiguang.core.model.collab.ProposalKind
import app.shiguang.core.model.collab.ProposalStepDraft
import app.shiguang.core.model.collab.BundleDraft
import app.shiguang.core.model.collab.composeFailureText
import app.shiguang.core.model.collab.composeSentOut
import app.shiguang.core.model.collab.relay.BundleComposeKit
import app.shiguang.core.model.collab.relay.FriendView
import app.shiguang.shiguang.ShiguangApplication
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 发起协同轻 ViewModel（behavior/compose_bundle.md 转正清单 §A，T4.5）：
 * 三块表单三件事——和谁一起（双向 accepted）/怎么定（Directive/Consensus）/
 * 做什么（步骤或候选时段）；「发出邀请」后全部 work 交 BundleProposalService
 * （造 K_bundle→逐成员封信封→落发件箱→尝试弹出），UI 只翻译结局。
 *
 * 关键语义（§A 逐字）：装箱失败=**箱里一条都没有**→「没发出去」文案（不许说已挂号）；
 * 落箱成功→drainNow 尝试弹出，成功=「已发出，对方会收到一条邀约」否则「已记下，
 * 联网后自动发出」（drain 自身炸=条目仍在箱，按未发出反馈）。
 */
class ComposeBundleViewModel(
    /** 发起套件提供者（容器注入；null=门禁态，本页仅在门禁通过后可达——兜底引导态）。 */
    private val loadKit: suspend () -> BundleComposeKit?,
    /** 本机协同身份 uid（propose meId）。 */
    private val currentUserId: suspend () -> String?,
) : ViewModel() {

    /** 单步骤表单态（title 与 steps 平行数组提升——Kotlin 无 TextEditingController 逐卡泄漏形）。 */
    data class StepUi(
        val draft: ProposalStepDraft,
        val title: String = "",
    )

    data class ComposeUiState(
        /** kit/名单装载中。 */
        val loading: Boolean = true,
        /** 门禁兜底态（kit null——正常路由不可达，debug 直进时给引导文案）。 */
        val gateError: Boolean = false,
        val contacts: List<FriendView> = emptyList(),
        /** 名单拉取失败横幅（§S3：顶部 error 行，表单仍可用）。 */
        val friendsError: Boolean = false,
        val selected: Set<String> = emptySet(),
        val kind: ProposalKind = ProposalKind.DIRECTIVE,
        val steps: List<StepUi> = emptyList(),
        val sending: Boolean = false,
    ) {
        /** 提交钮文案（§B：「发出邀请」/「正在发出…」）。 */
        val submitLabel: String get() = if (sending) "正在发出…" else "发出邀请"
    }

    private val _uiState = MutableStateFlow(ComposeUiState())
    val uiState: StateFlow<ComposeUiState> = _uiState

    /** 反馈面（floating SnackBar 文案）。 */
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val events: SharedFlow<String> = _events

    private var kit: BundleComposeKit? = null

    /** 进页装载：kit → 好友名单（开局自动加一条空步骤——不留空白 workflow）。 */
    fun init() {
        if (_uiState.value.steps.isNotEmpty()) return
        viewModelScope.launch {
            val k = loadKit()
            if (k == null) {
                _uiState.value = ComposeUiState(loading = false, gateError = true, steps = listOf(StepUi(emptyStep(1))))
                return@launch
            }
            kit = k
            val contacts = try {
                k.loadFriends()
            } catch (e: Exception) {
                _uiState.value = ComposeUiState(
                    loading = false,
                    friendsError = true,
                    steps = listOf(StepUi(emptyStep(1))),
                )
                return@launch
            }
            _uiState.value = ComposeUiState(
                loading = false,
                contacts = contacts,
                steps = listOf(StepUi(emptyStep(1))),
            )
        }
    }

    private fun emptyStep(n: Int): ProposalStepDraft = ProposalStepDraft(stepId = "s$n")

    /** 加一步/再加一个候选（§A：stepId='s{n+1}' 局部序号；新步骤必答=当前选中集）。 */
    fun addStep() {
        val s = _uiState.value
        val n = s.steps.size + 1
        _uiState.value = s.copy(steps = s.steps + StepUi(ProposalStepDraft(stepId = "s$n", requiredUids = s.selected.toList())))
    }

    /** 移除步骤（仅 steps>1 时页面暴露钮）。 */
    fun removeStep(index: Int) {
        val s = _uiState.value
        if (s.steps.size <= 1 || index !in s.steps.indices) return
        _uiState.value = s.copy(steps = s.steps.filterIndexed { i, _ -> i != index })
    }

    /** 步骤标题变更。 */
    fun setTitle(index: Int, title: String) {
        val s = _uiState.value
        if (index !in s.steps.indices) return
        _uiState.value = s.copy(steps = s.steps.mapIndexed { i, st -> if (i == index) st.copy(title = title) else st })
    }

    /** 时段回填（页面三连 picker 后调用）。 */
    fun setSlot(index: Int, startsAtMs: Long, endsAtMs: Long) {
        val s = _uiState.value
        if (index !in s.steps.indices) return
        _uiState.value = s.copy(
            steps = s.steps.mapIndexed { i, st ->
                if (i == index) st.copy(draft = st.draft.copy(startsAtMs = startsAtMs, endsAtMs = endsAtMs)) else st
            },
        )
    }

    /** 好友勾选（§A：选中→directive 下默认加入每个已存在步骤必答名单；取消→全步骤移除）。 */
    fun toggleMember(uid: String) {
        val s = _uiState.value
        val nowSelected: Set<String>
        val steps: List<StepUi>
        if (uid in s.selected) {
            nowSelected = s.selected - uid
            steps = s.steps.map { st -> st.copy(draft = st.draft.copy(requiredUids = st.draft.requiredUids - uid)) }
        } else {
            nowSelected = s.selected + uid
            steps = if (s.kind == ProposalKind.DIRECTIVE) {
                s.steps.map { st -> st.copy(draft = st.draft.copy(requiredUids = (st.draft.requiredUids + uid).distinct())) }
            } else {
                s.steps
            }
        }
        _uiState.value = s.copy(selected = nowSelected, steps = steps)
    }

    /** 逐步骤必答勾选（§A：directive 名单区 FilterChip）。 */
    fun toggleRequired(index: Int, uid: String) {
        val s = _uiState.value
        if (index !in s.steps.indices) return
        _uiState.value = s.copy(
            steps = s.steps.mapIndexed { i, st ->
                if (i != index) {
                    st
                } else {
                    val cur = st.draft.requiredUids
                    val next = if (uid in cur) cur - uid else cur + uid
                    st.copy(draft = st.draft.copy(requiredUids = next))
                }
            },
        )
    }

    /** kind 切换（§A：consensus→所有步骤重置 requiredUids/threshold/isOptional=FSM 缺省全员）。 */
    fun setKind(kind: ProposalKind) {
        val s = _uiState.value
        val steps = if (kind == ProposalKind.CONSENSUS) {
            s.steps.map { st -> st.copy(draft = st.draft.copy(requiredUids = emptyList(), threshold = 0, isOptional = false)) }
        } else {
            s.steps
        }
        _uiState.value = s.copy(kind = kind, steps = steps)
    }

    /** 发出邀请（§A 逐字链：propose→drainNow→composeSentOut 判定→翻译结局→pop）。 */
    fun submit(onDone: () -> Unit) {
        val s = _uiState.value
        if (s.sending) return
        val k = kit
        if (k == null) {
            toast("先到「设置 → 协同账号」登录，才能发出邀约")
            return
        }
        _uiState.value = s.copy(sending = true)
        viewModelScope.launch {
            val meId = currentUserId() ?: ""
            val draft = BundleDraft(
                localId = "local-${System.nanoTime().toString(36)}",
                kind = s.kind,
                inviteeUids = s.selected.toList(),
                steps = s.steps.map { st ->
                    st.draft.copy(title = st.title.trim())
                },
            )
            // 装箱前的全部风险都在这里：取成员公钥（联网）、K_bundle 落户、发件箱落盘。
            // 任一步炸掉＝箱里一条都没有，反馈必须说「没发出去」，不许说已挂号。
            try {
                k.proposer.propose(meId = meId, draft = draft)
            } catch (e: IllegalArgumentException) {
                _uiState.value = _uiState.value.copy(sending = false)
                toast(e.message ?: composeFailureText(e))
                return@launch
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(sending = false)
                toast(composeFailureText(e))
                return@launch
            }
            // 走到这里＝确已落进待发箱（先落盘、后反馈）；此后无论弹出成败，
            // 条目都在、常驻调度联网必补发，反馈只能报「联网后自动发出」。
            var sent = false
            try {
                sent = composeSentOut(k.drainNow())
            } catch (e: Exception) {
                // 弹出自身炸了（drainer 只归一化传输层异常）：箱内条目原样保留，按未发出反馈。
            }
            toast(if (sent) "已发出，对方会收到一条邀约" else "已记下，联网后自动发出")
            onDone()
        }
    }

    private fun toast(text: String) {
        _events.tryEmit(text)
    }

    companion object {
        /** Manual DI Factory（容器协同栈注入）。 */
        val factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as ShiguangApplication
                ComposeBundleViewModel(
                    loadKit = { app.container.loadBundleComposeKit() },
                    currentUserId = { app.container.identityStore.load()?.userId },
                )
            }
        }
    }
}
