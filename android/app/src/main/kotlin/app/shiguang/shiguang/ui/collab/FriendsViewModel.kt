package app.shiguang.shiguang.ui.collab

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.shiguang.core.model.collab.relay.FriendView
import app.shiguang.core.model.collab.relay.RelayAuthException
import app.shiguang.core.model.collab.relay.RelayFriendsException
import app.shiguang.core.model.collab.relay.RelaySession
import app.shiguang.shiguang.ShiguangApplication
import java.io.IOException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 好友域轻 ViewModel（behavior/friends.md 转正清单 §A，T4.1）：会话门禁两可文案 +
 * 名单三分区 + 处置/搜索命令——Dart friends_page.dart 状态机逐字镜像（零本地落库，
 * 全网络命令经 RelaySession 九业务面）。
 *
 * 会话门禁（§A）：loadSession null=未登录/已登录无协同身份记录——同一引导文案指
 * 「设置 → 协同账号」（防已登录用户对着「请登录」死循环）。
 */
class FriendsViewModel(
    /** 好友域会话提供者（容器注入 loadFriendsSession；null=门禁态）。 */
    private val loadSession: suspend () -> RelaySession?,
) : ViewModel() {

    enum class FriendAction { REMOVE, BLOCK, UNBLOCK }

    data class FriendsUiState(
        /** 会话门禁三态：null=探测中 / false=引导态 / true=名单面。 */
        val sessionReady: Boolean? = null,
        /** 名单刷新中（首次进页=整页转圈；刷新中=列表内静默）。 */
        val loading: Boolean = false,
        val friends: List<FriendView> = emptyList(),
        /** 名单加载失败横幅（§S3：顶部 error 行，表单仍可用）。 */
        val error: String? = null,
        /** 搜索加好友 busy（加好友钮禁用+转圈）。 */
        val searching: Boolean = false,
    )

    private val _uiState = MutableStateFlow(FriendsUiState())
    val uiState: StateFlow<FriendsUiState> = _uiState

    /** 反馈面（floating SnackBar 文案；页面消费）。 */
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val events: SharedFlow<String> = _events

    private var session: RelaySession? = null

    /** 进页初始化（Dart _init 镜像）：会话探测 → 名单首拉。 */
    fun init() {
        if (_uiState.value.sessionReady != null || session != null) return
        viewModelScope.launch {
            val s = withContext(Dispatchers.IO) { loadSession() }
            if (s == null) {
                _uiState.value = _uiState.value.copy(sessionReady = false)
                return@launch
            }
            session = s
            _uiState.value = _uiState.value.copy(sessionReady = true)
            reload()
        }
    }

    /** 名单重拉（Dart _reload 镜像：整名单重拉无增量；失败置 error 行不清名单）。 */
    fun reload() {
        val s = session ?: return
        _uiState.value = _uiState.value.copy(loading = true)
        viewModelScope.launch {
            try {
                val friends = withContext(Dispatchers.IO) { s.listFriends() }
                _uiState.value = _uiState.value.copy(
                    friends = friends,
                    loading = false,
                    error = null,
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(loading = false, error = ErrListFetch)
            }
        }
    }

    /** 待确认处置（通过/忽略；零二次确认——§B 现状口径）。 */
    fun respond(peer: String, accept: Boolean) {
        val s = session ?: return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { s.acceptInviteFromPeer(peer, accept) }
                reload()
            } catch (e: RelayFriendsException) {
                if (e.statusCode == 404) {
                    // 这是我发出的邀请（我非被邀方），服务端不认——非错误，等对方确认。
                    toast(RespondErrSelfInvited)
                    reload()
                } else {
                    toast(RespondErrGeneric)
                }
            } catch (e: IOException) {
                toast(ErrRelayUnreachable)
            } catch (e: Exception) {
                toast(RespondErrGeneric)
            }
        }
    }

    /** 关系处置（解除好友/拉黑/解除拉黑；破坏性二次确认在页面层）。 */
    fun manage(friend: FriendView, action: FriendAction) {
        val s = session ?: return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    when (action) {
                        FriendAction.REMOVE, FriendAction.UNBLOCK -> s.removeFriend(friend.userId)
                        FriendAction.BLOCK -> s.blockFriend(friend.userId)
                    }
                }
                toast(
                    when (action) {
                        FriendAction.REMOVE -> "已解除好友"
                        FriendAction.BLOCK -> "已拉黑"
                        FriendAction.UNBLOCK -> "已解除拉黑"
                    },
                )
                reload()
            } catch (e: RelayFriendsException) {
                toast(manageErrorText(e.statusCode))
            } catch (e: IOException) {
                toast(ErrRelayUnreachable)
            } catch (e: Exception) {
                toast("操作失败，稍后再试")
            }
        }
    }

    /** 搜用户名加好友（账号体系搜索路径；trim+toLowerCase+空忽略+双闸防重）。 */
    fun searchAndRequest(username: String) {
        val s = session ?: return
        if (_uiState.value.searching) return
        val uname = username.trim().lowercase()
        if (uname.isEmpty()) return
        _uiState.value = _uiState.value.copy(searching = true)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { s.requestFriendByUsername(uname) }
                toast("已发出好友申请，等对方确认")
                reload()
            } catch (e: RelayFriendsException) {
                toast(searchFriendsErrorText(e.statusCode))
            } catch (e: RelayAuthException) {
                toast(searchAuthErrorText(e.statusCode))
            } catch (e: IOException) {
                toast(ErrRelayUnreachable)
            } catch (e: Exception) {
                toast("发送失败，稍后再试")
            } finally {
                _uiState.value = _uiState.value.copy(searching = false)
            }
        }
    }

    private fun toast(text: String) {
        _events.tryEmit(text)
    }

    companion object {
        /** 会话门禁引导（§A 两可文案逐字）。 */
        const val SessionGateError = "到「设置 → 协同账号」完成登录与协同身份注册，才能管理好友"

        /** 名单拉取失败横幅（§S3 逐字）。 */
        const val ErrListFetch = "暂时连不上中继，取不到好友名单"

        /** 网络不可达兜底（§A 逐字）。 */
        const val ErrRelayUnreachable = "暂时连不上中继，稍后再试"

        /** 待确认处置 404 特判（§A 逐字：我方发出视角非错误）。 */
        const val RespondErrSelfInvited = "这是你发出的邀请，等待对方确认"

        /** 待确认处置失败兜底（§A 逐字）。 */
        const val RespondErrGeneric = "操作失败了，稍后再试"

        /** 关系处置错误映射（§A 逐字；JVM 对拍面）。 */
        fun manageErrorText(code: Int): String = when (code) {
            404 -> "这条关系已不存在，刷新一下"
            400 -> "拉黑列表已满（上限 500），先清理一下"
            401 -> "登录态已过期，请到「设置 → 协同账号」重新登录"
            else -> "操作失败，稍后再试"
        }

        /** 搜索加好友——账号域错误映射（§A 逐字）。 */
        fun searchAuthErrorText(code: Int): String = when (code) {
            404 -> "没有找到这个用户名"
            429 -> "操作太频繁，稍后再试"
            401 -> "登录态已过期，请到「设置 → 协同账号」重新登录"
            else -> "发送失败，稍后再试"
        }

        /** 搜索加好友——好友域错误映射（§A 逐字）。 */
        fun searchFriendsErrorText(code: Int): String = when (code) {
            409 -> "关系已存在"
            429 -> "操作太频繁，稍后再试"
            401 -> "登录态已过期，请到「设置 → 协同账号」重新登录"
            else -> "发送失败，稍后再试"
        }

        /** Manual DI Factory（容器 loadFriendsSession 注入）。 */
        val factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as ShiguangApplication
                FriendsViewModel(loadSession = { app.container.loadFriendsSession() })
            }
        }
    }
}
