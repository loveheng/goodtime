package app.shiguang.core.model.collab.relay

import app.shiguang.core.model.collab.FriendshipTicket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

/** test/collab_friends_api_test.dart Kotlin 移植（好友域写接口四例 + 请求体逐字段）。 */

private class MockFriendsRelay {
    val server = MockWebServer()
    var responseBody: JsonObject = buildJsonObject { }
    var responseCode = 200
    lateinit var lastRequest: RecordedRequest
    var lastBody: JsonObject = buildJsonObject { }

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                lastRequest = request
                val bodyStr = request.body.readUtf8()
                lastBody = if (bodyStr.isEmpty()) buildJsonObject { }
                else Json.parseToJsonElement(bodyStr).jsonObject
                return MockResponse().setResponseCode(responseCode).setBody(responseBody.toString())
            }
        }
        server.start()
    }

    val baseUrl: String get() = server.url("/").toString().trimEnd('/')

    fun client(): okhttp3.OkHttpClient = okhttp3.OkHttpClient()
}

class CollabFriendsApiTest {

    private fun ticket(): FriendshipTicket = FriendshipTicket(
        issuerUserId = "u-b",
        subjectUserId = "",
        nonce = "n-1",
        expiresAt = 1700000300,
        signature = ByteArray(64) { 1 },
        encKeyPub = ByteArray(32) { 2 },
    )

    @Test
    fun submitFriendInvitePostsTicketAndReturnsInviteId() = runBlocking {
        val relay = MockFriendsRelay()
        relay.responseCode = 201
        relay.responseBody = buildJsonObject { put("invite_id", "inv-1") }

        val inviteId = submitFriendInvite(relay.client(), relay.baseUrl, "tok-1", ticket())

        assertEquals("inv-1", inviteId)
        assertEquals("/v1/friends/invite", relay.lastRequest.path)
        assertEquals("u-b", relay.lastBody["ticket"]!!.jsonObject["issuer_user_id"]!!.jsonPrimitive.contentOrNull)
        assertEquals("", relay.lastBody["encrypted_intro"]!!.jsonPrimitive.contentOrNull)
        relay.server.close()
        Unit
    }

    @Test
    fun submitFriendInviteNormalizes409ToRelayFriendsException() = runBlocking {
        val relay = MockFriendsRelay()
        relay.responseCode = 409
        relay.responseBody = buildJsonObject { put("error_code", "TICKET_USED") }

        val e = assertFailsWith<RelayFriendsException> {
            submitFriendInvite(relay.client(), relay.baseUrl, "tok-1", ticket())
        }
        assertEquals(409, e.statusCode)
        relay.server.close()
        Unit
    }

    @Test
    fun acceptFriendInvitePostsInviteIdAndReturnsStatus() = runBlocking {
        val relay = MockFriendsRelay()
        relay.responseCode = 200
        relay.responseBody = buildJsonObject { put("status", "accepted") }

        val status = acceptFriendInvite(relay.client(), relay.baseUrl, "tok-1", "inv-9", accept = true)

        assertEquals("accepted", status)
        assertEquals("/v1/friends/accept", relay.lastRequest.path)
        assertEquals("inv-9", relay.lastBody["invite_id"]!!.jsonPrimitive.contentOrNull)
        assertEquals(true, relay.lastBody["accept"]!!.jsonPrimitive.contentOrNull.toBoolean())
        relay.server.close()
        Unit
    }

    @Test
    fun acceptFriendInviteNormalizes404ToRelayFriendsException() = runBlocking {
        val relay = MockFriendsRelay()
        relay.responseCode = 404
        relay.responseBody = buildJsonObject { put("error_code", "INVITE_NOT_FOUND") }

        val e = assertFailsWith<RelayFriendsException> {
            acceptFriendInvite(relay.client(), relay.baseUrl, "tok-1", "inv-missing", accept = true)
        }
        assertEquals(404, e.statusCode)
        relay.server.close()
        Unit
    }
}
