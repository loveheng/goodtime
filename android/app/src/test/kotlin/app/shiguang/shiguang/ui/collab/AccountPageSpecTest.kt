package app.shiguang.shiguang.ui.collab

import app.shiguang.core.model.collab.isValidPassword
import app.shiguang.core.model.collab.isValidUsername
import app.shiguang.core.model.collab.relay.RelayAuthException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 协同账号规格对拍（behavior/account.md §A 逐字，T4.8）：服务端错误码→文案映射 +
 * 节流派生（秒/分钟 ceil）+校验纯函数——口径=lib/ui/account_page.dart 镜像。
 */
class AccountPageSpecTest {

    private fun auth(code: String, retryAfter: Int? = null): RelayAuthException {
        val body = buildJsonObject {
            put("error_code", code)
            if (retryAfter != null) {
                putJsonObject("detail") { put("retry_after_sec", retryAfter) }
            }
        }
        return RelayAuthException(400, body)
    }

    @Test
    fun `错误码映射九项逐字`() {
        assertEquals("用户名或密码错误", AccountViewModel.authErrorText(auth("USER_OR_PASSWORD_INVALID")))
        assertEquals("该用户名已被占用，换一个试试", AccountViewModel.authErrorText(auth("USERNAME_EXISTS")))
        assertEquals("用户名需为 3~32 位小写字母、数字或下划线", AccountViewModel.authErrorText(auth("USERNAME_INVALID")))
        assertEquals("密码至少 8 位", AccountViewModel.authErrorText(auth("PASSWORD_TOO_WEAK")))
        assertEquals("当前密码不正确", AccountViewModel.authErrorText(auth("PASSWORD_MISMATCH")))
        assertEquals("操作太频繁，稍后再试", AccountViewModel.authErrorText(auth("RATE_LIMITED")))
        assertEquals("注册会话已过期，请重试", AccountViewModel.authErrorText(auth("CHALLENGE_INVALID")))
        assertEquals("登录态已过期，请重新登录", AccountViewModel.authErrorText(auth("TOKEN_REVOKED")))
        assertEquals("登录态已过期，请重新登录", AccountViewModel.authErrorText(auth("TOKEN_STALE")))
        assertEquals("操作失败，稍后再试", AccountViewModel.authErrorText(auth("SOMETHING_NEW")))
    }

    @Test
    fun `节流派生秒与分钟`() {
        assertEquals("尝试太频繁，请稍后再试", AccountViewModel.throttledText(auth("LOGIN_THROTTLED")))
        assertEquals("尝试太频繁，请 30 秒后再试", AccountViewModel.throttledText(auth("LOGIN_THROTTLED", 30)))
        assertEquals("尝试太频繁，账号已临时锁定，约 2 分钟后再试", AccountViewModel.throttledText(auth("LOGIN_THROTTLED", 120)))
        assertEquals("尝试太频繁，账号已临时锁定，约 1 分钟后再试", AccountViewModel.throttledText(auth("LOGIN_THROTTLED", 60)))
        assertEquals("尝试太频繁，请稍后再试", AccountViewModel.throttledText(auth("LOGIN_THROTTLED", 0)))
    }

    @Test
    fun `校验纯函数`() {
        assertTrue(isValidUsername("abc_123"))
        assertFalse(isValidUsername("Abc"))
        assertFalse(isValidUsername("ab"))
        assertTrue(isValidPassword("12345678"))
        assertFalse(isValidPassword("1234567"))
    }

    @Test
    fun `固定文案逐字`() {
        assertEquals("暂时连不上中继，稍后再试", AccountViewModel.ErrNetwork)
    }
}
