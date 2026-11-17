package app.shiguang.core.model.collab.relay

import app.shiguang.core.model.JdbcSqlStore
import app.shiguang.core.model.collab.MemoryIdentityStore
import app.shiguang.core.model.collab.MemorySecretStore
import app.shiguang.core.model.ShiguangSchema
import app.shiguang.core.model.collab.AccountSession
import app.shiguang.core.model.collab.AccountStatus
import app.shiguang.core.model.collab.IdentityRecord
import app.shiguang.core.model.collab.KeyStore
import app.shiguang.core.model.collab.X25519Ops
import app.shiguang.core.model.collab.ticketMessage
import app.shiguang.core.model.collab.ed25519Sign
import app.shiguang.core.model.collab.ed25519Verify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters

/** test/collab_relay_session_test.dart Kotlin 移植（聚焦版）：钥对还原/名片签发/invite
 *  拼装/真机装配 null 门禁。 */
class CollabRelaySessionTest {

    private suspend fun provisioned(): Triple<KeyStore, MemoryIdentityStore, IdentityRecord> {
        val store = JdbcSqlStore.inMemory()
        ShiguangSchema.createAll(store)
        val keyStore = KeyStore(MemorySecretStore())
        keyStore.importMsk(ByteArray(32) { 7 })
        val identityStore = MemoryIdentityStore()
        // 造一份真实身份材料入账（Ed25519 seed + SPK seed，均 MSK 封存）
        val material = app.shiguang.core.model.collab.generateIdentity()
        val rec = IdentityRecord(
            userId = "u-me",
            sealedIdentityPrivateKey = keyStore.seal(material.identityKeySeed),
            signedPreKeyPub = material.signedPreKeyPublic,
            opkRemaining = 20,
            sealedSignedPreKeyPrivate = keyStore.seal(material.signedPreKey),
        )
        identityStore.save(rec)
        return Triple(keyStore, identityStore, rec)
    }

    @Test
    fun restoredIdentityKeySignsWithOriginalIdentity() = runBlocking {
        val (keyStore, _, rec) = provisioned()
        val keySeed = restoreIdentityKeySeed(keyStore, rec)
        // 还原后公钥与原公钥一致（Dart 测试钉死的同一前提）
        val seedPub = Ed25519PrivateKeyParameters(
            runBlocking { keyStore.open(rec.sealedIdentityPrivateKey) },
        ).generatePublicKey().encoded
        val msg = "probe".toByteArray(Charsets.UTF_8)
        val sig = ed25519Sign(keySeed, msg)
        assertTrue(ed25519Verify(seedPub, msg, sig))
        Unit
    }

    @Test
    fun restoreSignedPreKeySeedNullThrows() = runBlocking {
        val (keyStore, _, rec) = provisioned()
        val seed = restoreSignedPreKeySeed(keyStore, rec)
        assertTrue(seed.size == 32)
        // 存量记录未封存 SPK 私钥 → 数据缺口（半盲降级）
        val bare = IdentityRecord(
            userId = rec.userId,
            sealedIdentityPrivateKey = rec.sealedIdentityPrivateKey,
            signedPreKeyPub = rec.signedPreKeyPub,
            opkRemaining = rec.opkRemaining,
            sealedSignedPreKeyPrivate = null,
        )
        assertFailsWith<IllegalStateException> { restoreSignedPreKeySeed(keyStore, bare) }
        Unit
    }

    @Test
    fun issueTicketRequiresIdentityAndSigns() = runBlocking {
        val relay = MockWebServer()
        relay.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(200).setBody("{}")
        }
        relay.start()
        val (keyStore, identityStore, rec) = provisioned()
        val account = AccountSession(
            client = okhttp3.OkHttpClient(),
            baseUrl = relay.url("/").toString().trimEnd('/'),
            keyStore = keyStore,
            identityStore = identityStore,
        )
        account.debugEstablishSession(username = "alice", userId = "u-me", tokens = TokenPair("acc", "ref", 0))
        val session = RelaySession(okhttp3.OkHttpClient(), relay.url("/").toString().trimEnd('/'), identityStore, account)

        // 无身份的门禁（空 store 会话）
        val emptyStore = MemoryIdentityStore()
        val s2 = RelaySession(okhttp3.OkHttpClient(), relay.url("/").toString().trimEnd('/'), emptyStore, account)
        assertFailsWith<IllegalStateException> { s2.issueTicket() }
        assertNull(s2.myUserId())

        // 有身份：issuer=userId、encKeyPub=SPK 公钥、签名可验
        val ticket = session.issueTicket()
        assertEquals("u-me", ticket.issuerUserId)
        assertTrue(ticket.encKeyPub.contentEquals(rec.signedPreKeyPub))
        val msg = ticketMessage(issuer = ticket.issuerUserId, subject = ticket.subjectUserId, nonce = ticket.nonce, expiresAt = ticket.expiresAt)
        assertTrue(ed25519Verify(Ed25519PrivateKeyParameters(runBlocking { keyStore.open(rec.sealedIdentityPrivateKey) }).generatePublicKey().encoded, msg.toByteArray(Charsets.UTF_8), ticket.signature))
        assertEquals(AccountStatus.LOGGED_IN, account.status.value)
        relay.close()
        Unit
    }

    @Test
    fun acceptInviteFromPeerBuildsPeerMeInviteId() = runBlocking {
        val seen = mutableListOf<String>()
        val relay = MockWebServer()
        relay.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path!!.startsWith("/v1/friends/accept")) {
                    val body = request.body.readUtf8()
                    seen.add(((Json.parseToJsonElement(body).jsonObject)["invite_id"] as kotlinx.serialization.json.JsonPrimitive).content)
                }
                return MockResponse().setResponseCode(200).setBody("""{"status":"accepted"}""")
            }
        }
        relay.start()
        val (keyStore, identityStore, _) = provisioned()
        val account = AccountSession(
            client = okhttp3.OkHttpClient(),
            baseUrl = relay.url("/").toString().trimEnd('/'),
            keyStore = keyStore,
            identityStore = identityStore,
        )
        account.debugEstablishSession(username = "alice", userId = "u-me", tokens = TokenPair("acc", "ref", 0))
        val session = RelaySession(okhttp3.OkHttpClient(), relay.url("/").toString().trimEnd('/'), identityStore, account)

        session.acceptInviteFromPeer("u-peer", accept = true)
        assertEquals(listOf("u-peer:u-me"), seen) // invite_id 拼装 {peer}:{me}
        relay.close()
        Unit
    }

    @Test
    fun buildKitReturnsNullWithoutIdentityElseAssembled() = runBlocking {
        val (keyStore, identityStore, _) = provisioned()
        val account = AccountSession(
            client = okhttp3.OkHttpClient(),
            baseUrl = "http://127.0.0.1:1",
            keyStore = keyStore,
            identityStore = identityStore,
        )
        account.debugEstablishSession(username = "alice", userId = "u-me", tokens = TokenPair("acc", "ref", 0))
        val store = JdbcSqlStore.inMemory()
        ShiguangSchema.createAll(store)

        val kit = buildBundleComposeKit(store, account, identityStore, baseUrl = "http://127.0.0.1:1")
        assertNotNull(kit)
        val friendsSession = buildFriendsSession(account, identityStore, baseUrl = "http://127.0.0.1:1")
        assertNotNull(friendsSession)

        // 无身份 → null（UI 转注册引导，不降级）
        val empty = MemoryIdentityStore()
        assertNull(buildBundleComposeKit(store, account, empty, baseUrl = "http://127.0.0.1:1"))
        assertNull(buildFriendsSession(account, empty, baseUrl = "http://127.0.0.1:1"))
        Unit
    }
}
