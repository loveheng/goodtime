package app.shiguang.shiguang.ui.collab

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.shiguang.core.model.collab.AccountSession
import app.shiguang.core.model.collab.isValidPassword
import app.shiguang.core.model.collab.isValidUsername
import app.shiguang.core.model.collab.relay.RelayAuthException
import app.shiguang.core.model.collab.relay.errorCodeOf
import app.shiguang.core.model.collab.relay.retryAfterSecOf
import app.shiguang.shiguang.ShiguangApplication
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 协同账号轻 ViewModel（behavior/account.md 转正清单 §A，T4.8）：登录/注册/改密/
 * 退出；防账号枚举统一口径（登录失败绝不区分两种原因）；错误码映射与节流派生
 * 纯函数 JVM 对拍面。
 */
class AccountViewModel(
    val session: AccountSession,
) : ViewModel() {

    data class AccountFormState(
        val register: Boolean = false,
        val busy: Boolean = false,
        /** 表单校验/服务端错误（password 字段 errorText 内联——非 SnackBar）。 */
        val error: String? = null,
    )

    private val _form = MutableStateFlow(AccountFormState())
    val form: StateFlow<AccountFormState> = _form

    /** 反馈面（SnackBar 文案）。 */
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val events: SharedFlow<String> = _events

    /** 切换登录/注册（§B：切注册清 error）。 */
    fun setRegister(register: Boolean) {
        _form.value = _form.value.copy(register = register, error = null)
    }

    /** 登录/注册提交（§A：trim+toLowerCase→校验三连→register/login）。
     *  @return true=成功（视图随 session.status 监听切换）。 */
    fun submit(username: String, password: String, confirm: String) {
        val s = _form.value
        if (s.busy) return
        val uname = username.trim().lowercase()
        var err: String? = null
        if (!isValidUsername(uname)) err = "用户名需为 3~32 位小写字母、数字或下划线"
        else if (!isValidPassword(password)) err = "密码至少 8 位"
        else if (s.register && password != confirm) err = "两次输入的密码不一致"
        if (err != null) {
            _form.value = s.copy(error = err)
            return
        }
        val isRegister = s.register // 提交时捕获（飞行中切换 SegmentedButton 不改变意图——Dart _register 镜像）
        _form.value = s.copy(busy = true, error = null)
        viewModelScope.launch {
            try {
                if (isRegister) {
                    withContext(Dispatchers.IO) { session.register(username = uname, password = password) }
                    toast("已注册并登录")
                } else {
                    withContext(Dispatchers.IO) { session.login(username = uname, password = password) }
                    toast("已登录")
                }
            } catch (e: RelayAuthException) {
                _form.value = _form.value.copy(error = authErrorText(e))
            } catch (e: IOException) {
                _form.value = _form.value.copy(error = ErrNetwork)
            } finally {
                _form.value = _form.value.copy(busy = false)
            }
        }
    }

    /** 改密（§A：401→当前密码不正确；成功踢会话=契约 §3.4，视图随 status 回表单）。 */
    fun changePassword(oldPassword: String, newPassword: String) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { session.changePassword(oldPassword, newPassword) }
                toast("密码已更新，请重新登录")
            } catch (e: RelayAuthException) {
                toast(if (e.statusCode == 401) "当前密码不正确" else "修改失败，稍后再试")
            } catch (e: IllegalArgumentException) {
                toast("密码至少 8 位")
            } catch (e: IOException) {
                toast(ErrNetwork)
            }
        }
    }

    /** 退出登录（§A：只退账号不动本地数据）。 */
    fun logout() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { session.logout() }
            toast("已退出登录")
        }
    }

    private fun toast(text: String) {
        _events.tryEmit(text)
    }

    companion object {
        /** 网络不可达（§A 逐字）。 */
        const val ErrNetwork = "暂时连不上中继，稍后再试"

        /** 服务端错误码→文案映射（§A 逐字；防账号枚举统一口径）。 */
        fun authErrorText(e: RelayAuthException): String = when (errorCodeOf(e.body as? kotlinx.serialization.json.JsonElement)) {
            "USER_OR_PASSWORD_INVALID" -> "用户名或密码错误"
            "USERNAME_EXISTS" -> "该用户名已被占用，换一个试试"
            "USERNAME_INVALID" -> "用户名需为 3~32 位小写字母、数字或下划线"
            "PASSWORD_TOO_WEAK" -> "密码至少 8 位"
            "PASSWORD_MISMATCH" -> "当前密码不正确"
            "LOGIN_THROTTLED" -> throttledText(e)
            "RATE_LIMITED" -> "操作太频繁，稍后再试"
            "CHALLENGE_INVALID" -> "注册会话已过期，请重试"
            "TOKEN_REVOKED", "TOKEN_STALE" -> "登录态已过期，请重新登录"
            else -> "操作失败，稍后再试"
        }

        /** 节流等待窗口派生（§A：retry_after_sec 秒/分钟 ceil）。 */
        fun throttledText(e: RelayAuthException): String {
            val sec = retryAfterSecOf(e.body as? kotlinx.serialization.json.JsonElement)
            if (sec == null || sec <= 0) return "尝试太频繁，请稍后再试"
            if (sec >= 60) return "尝试太频繁，账号已临时锁定，约 ${(sec + 59) / 60} 分钟后再试"
            return "尝试太频繁，请 $sec 秒后再试"
        }

        /** Manual DI Factory（容器 accountSession 注入）。 */
        val factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as ShiguangApplication
                AccountViewModel(session = app.container.accountSession)
            }
        }
    }
}
