package app.shiguang.shiguang.ui.collab

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.shiguang.core.model.collab.FriendshipTicket
import app.shiguang.core.model.collab.relay.RelayFriendsException
import app.shiguang.shiguang.ShiguangApplication
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 扫一扫建联轻 ViewModel（behavior/scan.md 转正清单 §A，D26 夜间自裁）：
 * 权限态/提交中/错误条三态 + handle 双闸防重（一码一次）+ 错误映射（§A 逐字）。
 * 提交经容器协同会话提供者（D27 装配消标：RelaySession.submitInvite 真实链路）；
 * 未登录经 ensureAccessToken 抛 IllegalStateException → 401 文案（scan.md §A）。
 */
class ScanViewModel(
    /** 邀请提交口（容器注入：协同栈装配后=RelaySession.submitInvite；未装配=401 文案桩）。 */
    private val submitInvite: suspend (FriendshipTicket) -> Unit,
) : ViewModel() {

    data class ScanUiState(
        /** 相机权限授予（granted/limited）；null=未请求。 */
        val permissionGranted: Boolean? = null,
        val submitting: Boolean = false,
        /** 错误条文案（非 null=显示；扫下一次码即复位）。 */
        val error: String? = null,
    )

    private val _uiState = MutableStateFlow(ScanUiState())
    val uiState: StateFlow<ScanUiState> = _uiState

    private var done = false // 一码一次闸（Dart _done 镜像）

    fun setPermission(granted: Boolean) {
        _uiState.value = _uiState.value.copy(permissionGranted = granted)
    }

    /** 扫码/手动提交统一入口（§A：解析→提交→错误映射）。 */
    fun handle(code: String) {
        val s = _uiState.value
        if (done || s.submitting) return
        if (code.trim().isEmpty()) return
        done = true
        _uiState.value = s.copy(submitting = true)
        viewModelScope.launch {
            try {
                val ticket = FriendshipTicket.fromQrString(code.trim())
                submitInvite(ticket)
                _events.emit(ScanEvent.InviteSent)
            } catch (e: IllegalArgumentException) {
                resetAfterError(ErrInvalidCard) // JSON 解析失败（fromJson 硬族——Dart FormatException 对位）
            } catch (e: RelayFriendsException) {
                resetAfterError(inviteErrorText(e.statusCode))
            } catch (e: IllegalStateException) {
                // 未登录（AccountSession.ensureAccessToken 门禁）→ 401 文案（scan.md §A 路径）。
                resetAfterError(inviteErrorText(401))
            } catch (e: Exception) {
                resetAfterError(ErrRelayUnreachable)
            }
        }
    }

    /** 手动提交（trim 非空才走——§B）。 */
    fun submitManual(text: String) {
        val code = text.trim()
        if (code.isEmpty()) return
        handle(code)
    }

    private val _events = MutableSharedFlow<ScanEvent>(extraBufferCapacity = 4)
    val events: kotlinx.coroutines.flow.SharedFlow<ScanEvent> = _events

    private fun resetAfterError(msg: String) {
        _uiState.value = _uiState.value.copy(submitting = false, error = msg)
        done = false
    }

    companion object {
        /** 解析失败文案（§A 逐字）。 */
        const val ErrInvalidCard = "这串不是有效的好友名片，换一张二维码试试"

        /** 中继不可达兜底文案（§A 逐字）。 */
        const val ErrRelayUnreachable = "暂时连不上中继，稍后再试"

        /** 成功文案（§A 逐字）。 */
        const val OkInviteSent = "已发出好友申请，等对方确认"

        /** 邀请错误状态码映射（§A 逐字；JVM 对拍面）。 */
        fun inviteErrorText(code: Int): String = when (code) {
            409 -> "你们已经是好友，或申请已在处理中"
            400 -> "这张名片已过期或被用过，让对方刷新一下"
            401 -> "登录态已过期，请到「设置 → 协同账号」重新登录"
            403 -> "名片不对，换一张试试"
            else -> "发送失败，稍后再试"
        }

        /** Manual DI Factory（AppContainer 纪律；提交口随容器协同装配态注入）。 */
        val factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as ShiguangApplication
                ScanViewModel(submitInvite = app.container.collabInviteSubmitter())
            }
        }
    }
}

/** 扫码页事件（成功后 pop 由页侧消费）。 */
sealed interface ScanEvent {
    data object InviteSent : ScanEvent
}
