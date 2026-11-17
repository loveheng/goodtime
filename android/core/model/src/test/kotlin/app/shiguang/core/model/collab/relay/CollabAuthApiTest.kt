package app.shiguang.core.model.collab.relay

import app.shiguang.core.model.collab.PreKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

/**
 * test/collab_auth_api_test.dart Kotlin 移植（客户端契约面聚焦版）：DTO 解析/注册体
 * 构建/errorCode·retryAfter 提取/login·refresh·lookup·patchMe·logout 的 HTTP 形状。
 * 服务端行为场景（refresh 轮换拉黑/防爆破锁定/登出 jti 拉黑）属 relay 服务端测试面，
 * Dart 侧测试保留为参考实现基线。
 */

private class ScriptedAuthRelay {
    val server = MockWebServer()
    var code = 200
    var body: JsonObject = buildJsonObject { }
    lateinit var last: RecordedRequest
    var lastBodyStr = ""

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                last = request
                lastBodyStr = request.body.readUtf8()
                return MockResponse().setResponseCode(code).setBody(body.toString())
            }
        }
        server.start()
    }

    val baseUrl: String get() = server.url("/").toString().trimEnd('/')

    fun client(): OkHttpClient = okhttp3.OkHttpClient()
}

class CollabAuthApiTest {

    @Test
    fun loginExchangesCredentialsForTokenPair() = runBlocking {
        val relay = ScriptedAuthRelay()
        relay.code = 200
        relay.body = buildJsonObject {
            put("access_token", "acc-1")
            put("refresh_token", "ref-1")
            put("access_expires_at", 1700003600)
        }
        val pair = login(relay.client(), relay.baseUrl, "alice", "pw-1")
        assertEquals("acc-1", pair.access)
        assertEquals("ref-1", pair.refresh)
        assertEquals(1700003600L, pair.accessExpiresAt)
        assertEquals("/v1/auth/login", relay.last.path)
        assertTrue(relay.lastBodyStr.contains("\"username\":\"alice\""))
        relay.server.close()
        Unit
    }

    @Test
    fun login401UnifiedInvalidUserOrPassword() = runBlocking {
        val relay = ScriptedAuthRelay()
        relay.code = 401
        relay.body = buildJsonObject { put("error_code", "USER_OR_PASSWORD_INVALID") }
        val e = assertFailsWith<RelayAuthException> {
            login(relay.client(), relay.baseUrl, "ghost", "bad") // 用户不存在与密码错误同口径
        }
        assertEquals(401, e.statusCode)
        relay.server.close()
        Unit
    }

    @Test
    fun refreshRotatesTokenPair() = runBlocking {
        val relay = ScriptedAuthRelay()
        relay.code = 200
        relay.body = buildJsonObject {
            put("access_token", "acc-2")
            put("refresh_token", "ref-2")
        }
        val pair = refresh(relay.client(), relay.baseUrl, "ref-1")
        assertEquals("acc-2", pair.access)
        assertEquals("ref-2", pair.refresh)
        assertTrue(relay.lastBodyStr.contains("\"refresh_token\":\"ref-1\""))
        relay.server.close()
        Unit
    }

    @Test
    fun lookupReturnsIdentityAnd404Raises() = runBlocking {
        val relay = ScriptedAuthRelay()
        relay.code = 200
        relay.body = buildJsonObject {
            put("user_id", "u-1")
            put("username", "alice")
            put("identity_key_pub", "AAAA")
            put("signed_prekey_pub", "AQID")
            put("signed_prekey_sig", "BAUG")
        }
        val u = lookupUser(relay.client(), relay.baseUrl, "tok-1", "alice")
        assertEquals("u-1", u.userId)
        assertTrue(u.identityKeyPub.contentEquals(byteArrayOf(0, 0, 0)))
        assertTrue(u.signedPrekeyPub.contentEquals(byteArrayOf(1, 2, 3)))

        relay.code = 404
        relay.body = buildJsonObject { put("error_code", "USER_NOT_FOUND") }
        assertFailsWith<RelayAuthException> { lookupUser(relay.client(), relay.baseUrl, "tok-1", "ghost") }
        relay.server.close()
        Unit
    }

    @Test
    fun errorCodeAndRetryAfterExtraction() {
        val standard = Json.parseToJsonElement(
            """{"error_code":"USER_OR_PASSWORD_INVALID","message":"no","detail":{"retry_after_sec":900}}""",
        ) as JsonObject
        assertEquals("USER_OR_PASSWORD_INVALID", errorCodeOf(standard))
        assertEquals(900, retryAfterSecOf(standard))
        // 非标准体 → null
        val odd = Json.parseToJsonElement("""{"message":"boom"}""") as JsonObject
        assertNull(errorCodeOf(odd))
        assertNull(retryAfterSecOf(odd))
    }

    @Test
    fun registerBodyShapeAndRegisterUser201() = runBlocking {
        val body = buildRegisterBody(
            username = "alice",
            password = "pw",
            identityKeyPub = byteArrayOf(1),
            signedPrekeyPub = byteArrayOf(2),
            signedPrekeySig = byteArrayOf(3),
            nonce = "n-1",
            powSolution = "sol",
            oneTimePreKeys = listOf(PreKey("k1", byteArrayOf(4), byteArrayOf(5))),
        )
        assertEquals("alice", body["username"]!!.jsonPrimitive.content)
        assertEquals("Ag==", body["signed_prekey_pub"]!!.jsonPrimitive.content)
        assertEquals("", body["push_token"]!!.jsonPrimitive.content)
        val opk0 = ((body["one_time_prekeys"] as kotlinx.serialization.json.JsonArray)[0]) as JsonObject
        assertEquals("k1", opk0["key_id"]!!.jsonPrimitive.content)

        val relay = ScriptedAuthRelay()
        relay.code = 201
        relay.body = buildJsonObject { put("user_id", "u-9"); put("username", "alice"); put("opk_count", 20) }
        val reg = registerUser(relay.client(), relay.baseUrl, body)
        assertEquals("u-9", reg.userId)
        assertEquals(20, reg.opkCount)
        assertEquals("/v1/users", relay.last.path)
        relay.server.close()
        Unit
    }

    @Test
    fun patchMeRequiresAtLeastOneFieldAndSendsPresentOnly() = runBlocking {
        val relay = ScriptedAuthRelay()
        assertFailsWith<IllegalArgumentException> {
            patchMe(relay.client(), relay.baseUrl, "tok-1")
        }
        relay.code = 200
        patchMe(relay.client(), relay.baseUrl, "tok-1", pushToken = "") // 空串=清空推送令牌
        assertEquals("PATCH", relay.last.method)
        assertTrue(relay.lastBodyStr.contains("\"push_token\":\"\""))
        assertTrue(!relay.lastBodyStr.contains("recovery_vault"))
        relay.server.close()
        Unit
    }

    @Test
    fun logoutSwallowsNetworkFailureAndSendsRefreshWhenPresent() = runBlocking {
        // 带 refresh：体含 refresh_token
        val relay = ScriptedAuthRelay()
        relay.code = 200
        logoutRemote(relay.client(), relay.baseUrl, "tok-1", refreshToken = "ref-9")
        assertTrue(relay.lastBodyStr.contains("\"refresh_token\":\"ref-9\""))
        relay.server.close()
        // 网络失败不阻断（连接拒绝）
        val dead = java.net.ServerSocket(0)
        val deadPort = dead.localPort
        dead.close()
        logoutRemote(
            OkHttpClient.Builder().connectTimeout(java.time.Duration.ofSeconds(1)).build(),
            "http://127.0.0.1:$deadPort",
            "tok-1",
        )
        Unit
    }
}
