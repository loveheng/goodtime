package app.shiguang.core.model.collab

import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** test/collab_friends_ticket_test.dart Kotlin 移植（§1.6 名片签发/验签/QR 往返/安全码）。 */
class CollabFriendsTicketTest {

    @Test
    fun signAndVerifyAgainstCanonicalMessage() = runTest {
        val seed = ByteArray(32).also(SecureRandom()::nextBytes)
        val encKeyPub = ByteArray(32) { 9 }
        val ticket = signFriendshipTicket(
            identityKeySeed = seed,
            issuerUserId = "u-a",
            encKeyPub = encKeyPub,
            nowSec = { 1700000000 },
        )
        assertEquals("u-a", ticket.issuerUserId)
        assertEquals("", ticket.subjectUserId)
        assertEquals(1700000300L, ticket.expiresAt)
        assertTrue(ticket.encKeyPub.contentEquals(encKeyPub))

        // 规范化签名消息逐字节一致（relay 验签同源）
        val msg = ticketMessage(
            issuer = ticket.issuerUserId,
            subject = ticket.subjectUserId,
            nonce = ticket.nonce,
            expiresAt = ticket.expiresAt,
        )
        assertTrue(msg.startsWith("shiguang.relay.v1/friendship-ticket|u-a||"))
        assertTrue(ed25519Verify(ed25519PubFromSeed(seed), msg.toByteArray(Charsets.UTF_8), ticket.signature))
    }

    @Test
    fun tamperedNonceFailsVerification() = runTest {
        val seed = ByteArray(32).also(SecureRandom()::nextBytes)
        val ticket = signFriendshipTicket(
            identityKeySeed = seed,
            issuerUserId = "u-a",
            encKeyPub = ByteArray(32) { 9 },
            nowSec = { 1700000000 },
        )
        val forged = ticketMessage(
            issuer = ticket.issuerUserId,
            subject = ticket.subjectUserId,
            nonce = ticket.nonce + "x", // nonce 被篡改
            expiresAt = ticket.expiresAt,
        )
        assertTrue(!ed25519Verify(ed25519PubFromSeed(seed), forged.toByteArray(Charsets.UTF_8), ticket.signature))
    }

    @Test
    fun qrStringRoundTripLossless() = runTest {
        val ticket = FriendshipTicket(
            issuerUserId = "u-a",
            subjectUserId = "u-b",
            nonce = "abc123",
            expiresAt = 1700000300,
            signature = ByteArray(64) { (it % 251).toByte() },
            encKeyPub = ByteArray(32) { 9 },
        )
        val restored = FriendshipTicket.fromQrString(ticket.toQrString())
        assertEquals(ticket, restored)
        // JSON 键名对齐 relay ticketReq（snake_case + base64 值）
        val j = Json.parseToJsonElement(ticket.toQrString()).jsonObject
        assertEquals("u-a", j["issuer_user_id"]!!.jsonPrimitive.contentOrNull)
        assertEquals("abc123", j["nonce"]!!.jsonPrimitive.contentOrNull)
    }

    @Test
    fun safetyNumberOrderIndependentAndDeterministic() {
        val a = ByteArray(32) { it.toByte() }
        val b = ByteArray(32) { (31 - it).toByte() }
        val ab = safetyNumberDigits(a, b)
        val ba = safetyNumberDigits(b, a)
        assertEquals(ab, ba) // 顺序无关：双端算出同一串
        assertEquals(6, ab.size)
        assertTrue(ab.all { it.length == 5 && it.all { c -> c in '0'..'9' } })
        assertEquals(ab, safetyNumberDigits(a, b)) // 确定可复现
        // 不同公钥对 → 不同安全码
        val c = ByteArray(32) { (it + 7).toByte() }
        assertTrue(ab != safetyNumberDigits(a, c))
    }
}
