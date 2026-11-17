package app.shiguang.core.model.collab

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import app.shiguang.core.model.collab.relay.RelayAuthException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

/**
 * test/collab_account_session_test.dart Kotlin 移植（账号会话：注册即登录、凭据持久化
 * 跨进程、access 只存内存、refresh 强制轮换、改密踢会话、端侧守卫）。
 * 测试架 = 紧凑有状态 mock relay（复刻 relay_mock.dart 的本测试所用面：
 * challenge/register/login/refresh 轮换/me/change-password/logout）。
 */

/** 紧凑有状态 mock relay：refresh 轮换（旧票重用 401 TOKEN_REVOKED）+ 改密踢纪元。 */
private class MockAuthRelay {
    val server = MockWebServer()
    var failLoginOnce = false
    private var epoch = 0
    private var currentRefresh: String? = null
    private var counter = 0
    var registeredUserIds = mutableMapOf<String, String>() // username → user_id

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                val body = if (request.body.size == 0L) "{}" else request.body.readUtf8()
                val j = Json.parseToJsonElement(body).jsonObject
                return when {
                    path.startsWith("/v1/auth/challenge") ->
                        MockResponse().setResponseCode(200).setBody(
                            """{"nonce":"n-$epoch","pow_challenge":"abc","pow_difficulty":0,"expires_at":9999999999}""",
                        )
                    path.startsWith("/v1/auth/login") -> {
                        if (failLoginOnce) {
                            failLoginOnce = false
                            MockResponse().setResponseCode(401)
                                .setBody("""{"error_code":"USER_OR_PASSWORD_INVALID","message":"no"}""")
                        } else {
                            val uname = j["username"]!!.jsonPrimitive.content
                            val uid = registeredUserIds[uname] ?: "u-${++counter}"
                            val (access, refresh) = issueTokens(uid)
                            MockResponse().setResponseCode(200).setBody(
                                """{"access_token":"$access","refresh_token":"$refresh","access_expires_at":1700003600}""",
                            )
                        }
                    }
                    path.startsWith("/v1/auth/refresh") -> {
                        val presented = j["refresh_token"]!!.jsonPrimitive.content
                        val expected = currentRefresh
                        if (expected != null && presented == expected) {
                            val uid = uidOf(presented)
                            val (access, refresh) = issueTokens(uid)
                            MockResponse().setResponseCode(200).setBody(
                                """{"access_token":"$access","refresh_token":"$refresh","access_expires_at":1700003600}""",
                            )
                        } else {
                            MockResponse().setResponseCode(401)
                                .setBody("""{"error_code":"TOKEN_REVOKED","message":"rotated"}""")
                        }
                    }
                    path.startsWith("/v1/users/me") && request.method == "GET" -> {
                        val uid = uidOf(tokenUid(request.getHeader("Authorization")))
                        MockResponse().setResponseCode(200).setBody(
                            """{"user_id":"$uid","username":"alice","created_at":1700000000}""",
                        )
                    }
                    path.startsWith("/v1/auth/change-password") -> {
                        epoch++
                        currentRefresh = null // 纪元 bump：全票失效
                        MockResponse().setResponseCode(200).setBody("{}")
                    }
                    path.startsWith("/v1/auth/logout") -> MockResponse().setResponseCode(200).setBody("{}")
                    path.startsWith("/v1/users") -> {
                        // 注册：服务端发号（幂等 username→uid）
                        val uname = j["username"]!!.jsonPrimitive.content
                        val uid = registeredUserIds.getOrPut(uname) { "u-${++counter}" }
                        MockResponse().setResponseCode(201).setBody(
                            """{"user_id":"$uid","username":"$uname","opk_count":20}""",
                        )
                    }
                    else -> MockResponse().setResponseCode(404).setBody("{}")
                }
            }
        }
        server.start()
    }

    private var tokenSeq = 0
    private val tokenUidMap = mutableMapOf<String, String>()

    private fun issueTokens(uid: String): Pair<String, String> {
        tokenSeq++
        val access = "acc-$tokenSeq"
        val refresh = "ref-$tokenSeq"
        tokenUidMap[access] = uid
        tokenUidMap[refresh] = uid
        currentRefresh = refresh // 轮换：旧票立即作废
        return access to refresh
    }

    private fun uidOf(token: String?): String = tokenUidMap[token ?: ""] ?: "u-ghost"

    private fun tokenUid(authHeader: String?): String? = authHeader?.removePrefix("Bearer ")

    val baseUrl: String get() = server.url("/").toString().trimEnd('/')
}

private fun newSession(relay: MockAuthRelay, secrets: MemorySecretStore): AccountSession =
    AccountSession(
        client = okhttp3.OkHttpClient(),
        baseUrl = relay.baseUrl,
        keyStore = KeyStore(MemorySecretStore()),
        identityStore = MemoryIdentityStore(),
        secrets = secrets,
        nowSec = { 1700000000 },
    )

private suspend fun storedRefresh(secrets: MemorySecretStore): String? {
    val raw = secrets.read("collab.account.session") ?: return null
    return ((Json.parseToJsonElement(raw).jsonObject)["refresh_token"] as kotlinx.serialization.json.JsonPrimitive).content
}

class CollabAccountSessionTest {

    @Test
    fun registerLogsInAndPersistsIdentity() = runBlocking {
        val relay = MockAuthRelay()
        val secrets = MemorySecretStore()
        val session = newSession(relay, secrets)

        val result = session.register("Alice", "pw-12345678")

        assertEquals(AccountStatus.LOGGED_IN, session.status.value)
        assertEquals("alice", session.username) // 端侧归一小写
        assertEquals("alice", result.username)
        assertTrue(session.userId == result.userId)
        // 协同身份落账
        val rec = session.identityStore.load()
        assertTrue(rec != null && rec.userId == result.userId)
        // 安全区只存 refresh，access 绝不落盘（§5 红线）
        val blob = secrets.read("collab.account.session")!!
        assertTrue(blob.contains("refresh_token"))
        assertTrue(!blob.contains("acc-"))
        relay.server.close()
        Unit
    }

    @Test
    fun crossProcessRestoreRotatesRefresh() = runBlocking {
        val relay = MockAuthRelay()
        val secrets = MemorySecretStore()
        val s1 = newSession(relay, secrets)
        val result = s1.register("alice", "pw-12345678")
        val oldRefresh = storedRefresh(secrets)!!

        // 换进程：新会话从安全区恢复（无 access 也 LOGGED_IN）
        val s2 = newSession(relay, secrets)
        s2.restore()
        assertEquals(AccountStatus.LOGGED_IN, s2.status.value)
        assertEquals("alice", s2.username)

        // 首次取票走 refresh 轮换：旧票被拉黑，新票落盘
        val access = s2.ensureAccessToken()
        assertTrue(access.startsWith("acc-"))
        val newRefresh = storedRefresh(secrets)!!
        assertNotEquals(oldRefresh, newRefresh)
        // 旧票重用 → 401 TOKEN_REVOKED → 降级未登录
        val s3 = newSession(relay, secrets)
        s3.restore()
        // 手动把旧票塞回去（模拟轮换与落盘之间被杀）
        secrets.write("collab.account.session", """{"username":"alice","user_id":"${result.userId}","refresh_token":"$oldRefresh"}""")
        val s4 = newSession(relay, secrets)
        s4.restore()
        assertFailsWith<RelayAuthException> { s4.ensureAccessToken() }
        assertEquals(AccountStatus.LOGGED_OUT, s4.status.value)
        relay.server.close()
        Unit
    }

    @Test
    fun restoreWithoutCredentialsLoggedOutAndBadDataNoCrash() = runBlocking {
        val relay = MockAuthRelay()
        val secrets = MemorySecretStore()
        val s1 = newSession(relay, secrets)
        s1.restore()
        assertEquals(AccountStatus.LOGGED_OUT, s1.status.value)

        // 坏数据不崩溃 → 降级 LOGGED_OUT
        secrets.write("collab.account.session", "not-json{{{")
        val s2 = newSession(relay, secrets)
        s2.restore()
        assertEquals(AccountStatus.LOGGED_OUT, s2.status.value)
        relay.server.close()
        Unit
    }

    @Test
    fun loginFailureKeepsLoggedOutThenSuccess() = runBlocking {
        val relay = MockAuthRelay()
        val secrets = MemorySecretStore()
        val session = newSession(relay, secrets)
        // 镜像 Dart：先注册+登出（→LOGGED_OUT），失败登录保持未登录
        session.register("alice", "pw-12345678")
        session.logout()
        relay.failLoginOnce = true
        val e = assertFailsWith<RelayAuthException> { session.login("alice", "pw-12345678") }
        assertEquals(401, e.statusCode)
        assertEquals(false, session.isLoggedIn)
        session.login("alice", "pw-12345678")
        assertEquals(AccountStatus.LOGGED_IN, session.status.value)
        relay.server.close()
        Unit
    }

    @Test
    fun cachedTokenUntilExpiryThenRotation() = runBlocking {
        val relay = MockAuthRelay()
        val secrets = MemorySecretStore()
        val session = newSession(relay, secrets)
        val result = session.register("alice", "pw-12345678")
        // mock 发未来过期时刻 → 缓存直用不轮换
        val a1 = session.ensureAccessToken()
        val a2 = session.ensureAccessToken()
        assertEquals(a1, a2)

        // 用 debug 注入口验证缓存语义：带未来过期时刻 → 不轮换
        // （refresh 用 mock 当前有效票——轮换语义下外来票会被 401 拒）
        val s2 = newSession(relay, secrets)
        s2.debugEstablishSession(
            username = "alice",
            userId = result.userId,
            tokens = app.shiguang.core.model.collab.relay.TokenPair("acc-fixed", storedRefresh(secrets)!!, 1700003600),
        )
        assertEquals("acc-fixed", s2.ensureAccessToken())
        assertEquals("acc-fixed", s2.ensureAccessToken()) // 未到期直用缓存
        s2.debugExpireAccessToken()
        val rotated = s2.ensureAccessToken() // 过期 → 走 refresh 轮换
        assertNotEquals("acc-fixed", rotated)
        relay.server.close()
        Unit
    }

    @Test
    fun changePasswordKicksSessionEverywhere() = runBlocking {
        val relay = MockAuthRelay()
        val secrets = MemorySecretStore()
        val session = newSession(relay, secrets)
        session.register("alice", "pw-12345678")

        session.changePassword(oldPassword = "pw-12345678", newPassword = "pw-87654321")
        assertEquals(AccountStatus.LOGGED_OUT, session.status.value) // 踢会话
        assertNull(session.username)

        // 新密码可重新登录
        session.login("alice", "pw-87654321")
        assertEquals(AccountStatus.LOGGED_IN, session.status.value)
        relay.server.close()
        Unit
    }

    @Test
    fun logoutClearsLocalCredentials() = runBlocking {
        val relay = MockAuthRelay()
        val secrets = MemorySecretStore()
        val session = newSession(relay, secrets)
        session.register("alice", "pw-12345678")
        session.logout()
        assertEquals(AccountStatus.LOGGED_OUT, session.status.value)
        assertNull(session.username)
        assertNull(session.userId)
        relay.server.close()
        Unit
    }

    @Test
    fun clientSideGuardsRejectBeforeNetwork() = runBlocking {
        val relay = MockAuthRelay()
        val secrets = MemorySecretStore()
        val session = newSession(relay, secrets)
        assertFailsWith<IllegalArgumentException> { session.register("Bad-Name", "pw-12345678") }
        assertFailsWith<IllegalArgumentException> { session.register("okname", "short") }
        assertFailsWith<IllegalArgumentException> { session.login("", "pw-12345678") }
        assertEquals(AccountStatus.UNKNOWN, session.status.value) // 未触网
        relay.server.close()
        Unit
    }
}
