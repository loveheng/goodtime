package app.shiguang.shiguang.ui.collab

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.shiguang.shiguang.ShiguangApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 我的名片轻 ViewModel（behavior/my_qr.md 转正清单 §A，T4.7）：issueTicket→
 * toQrString（端侧签发含 TTL）；失败归一错误文案+刷新钮重试。
 */
class MyQrViewModel(
    /** 好友域会话提供者（容器注入；null=身份未注册——签发必失败归一错误态）。 */
    private val loadSession: suspend () -> app.shiguang.core.model.collab.relay.RelaySession?,
) : ViewModel() {

    data class MyQrUiState(
        /** QR 内容串（toQrString）；null=未签出。 */
        val qr: String? = null,
        /** 签发中（转圈）。 */
        val busy: Boolean = false,
        /** 错误文案（错误态=文字+刷新钮）。 */
        val error: String? = null,
    )

    private val _uiState = MutableStateFlow(MyQrUiState())
    val uiState: StateFlow<MyQrUiState> = _uiState

    /** 签发（进页即走；busy 防重——转圈期间无钮可点）。 */
    fun issue() {
        if (_uiState.value.busy) return
        _uiState.value = _uiState.value.copy(busy = true, error = null)
        viewModelScope.launch {
            try {
                val session = loadSession() ?: throw IllegalStateException("本机尚未注册协同身份")
                val ticket = withContext(Dispatchers.IO) { session.issueTicket() }
                _uiState.value = MyQrUiState(qr = ticket.toQrString(), busy = false)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = ErrIssue, busy = false)
            }
        }
    }

    companion object {
        /** 签发失败文案（§A 逐字）。 */
        const val ErrIssue = "暂时签不出名片，先到「设置 → 协同账号」登录或注册"

        /** Manual DI Factory（容器协同栈注入）。 */
        val factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as ShiguangApplication
                MyQrViewModel(loadSession = { app.container.loadFriendsSession() })
            }
        }
    }
}
