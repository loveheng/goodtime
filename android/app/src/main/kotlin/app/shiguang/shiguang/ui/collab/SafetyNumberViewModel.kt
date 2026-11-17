package app.shiguang.shiguang.ui.collab

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.shiguang.core.model.collab.safetyNumberDigits
import app.shiguang.shiguang.ShiguangApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 核对安全码轻 ViewModel（behavior/safety_number.md 转正清单 §A，T4.7）：
 * 双端身份公钥 → safetyNumberDigits 6 组 5 位指纹；失败归一错误态+重试整链重跑。
 */
class SafetyNumberViewModel(
    private val friendUserId: String,
    /** 好友域会话提供者（容器注入；null=错误态）。 */
    private val loadSession: suspend () -> app.shiguang.core.model.collab.relay.RelaySession?,
) : ViewModel() {

    data class SafetyUiState(
        /** 6 组 5 位数字（safetyNumberDigits 派生）。 */
        val digits: List<String>? = null,
        val busy: Boolean = false,
        val error: String? = null,
    )

    private val _uiState = MutableStateFlow(SafetyUiState())
    val uiState: StateFlow<SafetyUiState> = _uiState

    /** 计算（进页即走；重试=整链重跑——公钥拉取顺序 await 镜像 Dart）。 */
    fun load() {
        if (_uiState.value.busy) return
        _uiState.value = _uiState.value.copy(busy = true, error = null)
        viewModelScope.launch {
            try {
                val session = loadSession() ?: throw IllegalStateException("本机尚未注册协同身份")
                val digits = withContext(Dispatchers.IO) {
                    val myIk = session.myIdentityPublicKey()
                    val peerIk = session.peerIdentityPublicKey(friendUserId)
                    safetyNumberDigits(myIk, peerIk)
                }
                _uiState.value = SafetyUiState(digits = digits, busy = false)
            } catch (e: Exception) {
                _uiState.value = SafetyUiState(error = ErrFetch, busy = false)
            }
        }
    }

    companion object {
        /** 公钥拉取失败文案（§A 逐字）。 */
        const val ErrFetch = "暂时取不到对方公钥，连上中继后再试"

        /** Manual DI Factory（容器协同栈注入）。 */
        fun factory(friendUserId: String): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as ShiguangApplication
                SafetyNumberViewModel(friendUserId, loadSession = { app.container.loadFriendsSession() })
            }
        }
    }
}
