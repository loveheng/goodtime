package app.shiguang.core.model.collab.relay

import app.shiguang.core.model.JdbcSqlStore
import app.shiguang.core.model.ShiguangSchema
import app.shiguang.core.model.collab.OutboxDrainer
import app.shiguang.core.model.collab.OutboxEntry
import app.shiguang.core.model.collab.OutboxRepository
import app.shiguang.core.model.collab.SealedPayload
import app.shiguang.core.model.collab.SendAccepted
import app.shiguang.core.model.collab.SendOffline
import app.shiguang.core.model.collab.SendProposed
import app.shiguang.core.model.collab.SendRejected
import app.shiguang.core.model.collab.openPayload
import app.shiguang.core.model.collab.ProposalKind
import app.shiguang.core.model.collab.ProposalStepDraft
import app.shiguang.core.model.collab.BundleDraft
import app.shiguang.core.model.collab.BundleProposalService
import app.shiguang.core.model.collab.KeyStore
import app.shiguang.core.model.collab.MemorySecretStore
import app.shiguang.core.model.collab.BundleKeySource
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

/**
 * test/collab_action_transport_test.dart + test/collab_propose_test.dart 传输层两例
 * Kotlin 移植：信封与幂等头逐字段 / 4xx·5xx·401·断连结局映射 / Drainer 集成 /
 * PROPOSE 抬字段 / 既有动作请求体三字段。
 */

private val K_KEY = "0123456789abcdef0123456789abcdef" // 32B K_bundle

/** 复刻 relay 受理面：记录请求逐字段，按脚本队列回响应。 */
private class MockActionRelay {
    val server = MockWebServer()
    val requests = mutableListOf<JsonObject>()
    private val script = ArrayDeque<Pair<Int, JsonObject>>()

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = request.body.readUtf8()
                requests.add(Json.parseToJsonElement(body).jsonObject)
                val (code, respBody) = if (script.isEmpty()) {
                    200 to buildJsonObject { put("seq_id", 1) }
                } else {
                    script.removeFirst()
                }
                return MockResponse().setResponseCode(code).setBody(respBody.toString())
            }
        }
        server.start()
    }

    val baseUrl: String get() = server.url("/").toString().trimEnd('/')

    fun scriptAdd(code: Int, body: JsonObject) {
        script.addLast(code to body)
    }

    fun headerOf(request: RecordedRequest, name: String): String? = request.getHeader(name)
}

class CollabActionTransportTest {

    private fun transport(baseUrl: String): HttpOutboxTransport {
        val client = OkHttpClient.Builder().connectTimeout(Duration.ofSeconds(2)).build()
        return HttpOutboxTransport(
            client = client,
            baseUrl = baseUrl,
            accessToken = { "tok-1" },
            bundleKey = { _, _ -> K_KEY.toByteArray(Charsets.UTF_8) },
        )
    }

    private fun entry() = OutboxEntry(
        id = "ob-42",
        bundleId = "b-1",
        actionType = "ACCEPT",
        payload = buildJsonObject {
            put("step_id", "s3")
            put("pref", "ok")
        },
        keyVer = 1,
        attempts = 0,
        createdAt = 1000,
    )

    @Test
    fun envelopeAndIdempotencyHeadersAligned() = runBlocking {
        val relay = MockActionRelay()
        val outcome = transport(relay.baseUrl).send(entry())

        assertIs<SendAccepted>(outcome)
        val r = relay.requests.single()
        // 请求头经 Dispatcher 侧 RecordedRequest 只留 body——鉴权/幂等头在下一段用
        // RecordedRequest 断言不可得，改由独立 Dispatcher 记录头（见 headerAssertions 例）。
        assertEquals("b-1", r["bundle_id"]!!.jsonPrimitive.content)
        assertEquals("ACCEPT", r["action"]!!.jsonPrimitive.content)
        // cipher 链路：base64 → utf8 → SealedPayload → AES-GCM 解开 → 原始明文
        val sealed = SealedPayload.decode(
            String(java.util.Base64.getDecoder().decode(r["cipher"]!!.jsonPrimitive.content), Charsets.UTF_8),
        )
        val opened = openPayload(K_KEY.toByteArray(Charsets.UTF_8), sealed)
        assertEquals(entry().payload.toString(), String(opened, Charsets.UTF_8))
        relay.server.close()
        Unit
    }

    @Test
    fun idempotencyHeadersBearerAndMsgId() = runBlocking {
        val relay = MockWebServer()
        val seen = mutableListOf<Pair<String?, String?>>()
        relay.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen.add(request.getHeader("Authorization") to request.getHeader("X-Client-Msg-Id"))
                return MockResponse().setResponseCode(200).setBody("""{"seq_id":1}""")
            }
        }
        relay.start()
        transport(relay.url("/").toString().trimEnd('/')).send(entry())
        val (auth, msgId) = seen.single()
        assertEquals("Bearer tok-1", auth)
        assertEquals("ob-42", msgId) // 幂等键与 OutboxEntry.id 同源
        relay.close()
        Unit
    }

    @Test
    fun client4xxMapsToRejectedWithErrorCodeFallback() = runBlocking {
        val relay = MockActionRelay()
        relay.scriptAdd(
            409,
            buildJsonObject {
                put("error_code", "STALE_REVISION")
                put("message", "base revision conflict")
            },
        )
        relay.scriptAdd(403, buildJsonObject { put("error_code", "NOT_PARTICIPANT"); put("message", "no") })
        relay.scriptAdd(400, buildJsonObject { put("message", "malformed") }) // 无 error_code 字段

        assertEquals("STALE_REVISION", assertIs<SendRejected>(transport(relay.baseUrl).send(entry())).errorCode)
        assertEquals("NOT_PARTICIPANT", assertIs<SendRejected>(transport(relay.baseUrl).send(entry())).errorCode)
        assertEquals("HTTP_400", assertIs<SendRejected>(transport(relay.baseUrl).send(entry())).errorCode)
        relay.server.close()
        Unit
    }

    @Test
    fun server5xx401AndDeadPortMapToOffline() = runBlocking {
        val relay = MockActionRelay()
        relay.scriptAdd(500, buildJsonObject { put("error_code", "INTERNAL"); put("message", "boom") })
        relay.scriptAdd(401, buildJsonObject { put("error_code", "UNAUTHORIZED"); put("message", "token expired") })

        assertIs<SendOffline>(transport(relay.baseUrl).send(entry()))
        assertIs<SendOffline>(transport(relay.baseUrl).send(entry()))

        // 连接拒绝：指向已关闭端口 → IOException → SendOffline
        val dead = java.net.ServerSocket(0)
        val deadPort = dead.localPort
        dead.close()
        val t = HttpOutboxTransport(
            client = OkHttpClient.Builder().connectTimeout(Duration.ofSeconds(1)).build(),
            baseUrl = "http://127.0.0.1:$deadPort",
            accessToken = { "tok-1" },
            bundleKey = { _, _ -> K_KEY.toByteArray(Charsets.UTF_8) },
        )
        assertIs<SendOffline>(t.send(entry()))
        relay.server.close()
        Unit
    }

    @Test
    fun drainerIntegrationRealTransportRound() = runBlocking {
        val relay = MockActionRelay()
        val store = JdbcSqlStore.inMemory()
        ShiguangSchema.createAll(store)
        relay.scriptAdd(200, buildJsonObject { put("bundle_id", "b-1"); put("seq_id", 9); put("status", "PROPOSED") })
        relay.scriptAdd(409, buildJsonObject { put("error_code", "STALE_REVISION"); put("message", "base revision conflict") })

        val repo = OutboxRepository(store, nowMs = { 1000 }, newId = counter())
        repo.enqueue("b-1", "ACCEPT", buildJsonObject { put("step_id", "s1") })
        repo.enqueue("b-1", "PATCH_TIME", buildJsonObject { put("step_id", "s1"); put("start", 42) })

        val report = OutboxDrainer(repo, transport(relay.baseUrl)).drain()

        assertEquals(1, report.sent)
        assertEquals(false, report.stoppedByNetwork)
        assertEquals("STALE_REVISION", report.conflicts.single().errorCode)
        assertEquals("PATCH_TIME", report.conflicts.single().actionType)
        assertEquals(0, repo.pending().size) // 全清：确认删 + 409 清箱
        assertEquals(listOf("ACCEPT", "PATCH_TIME"), relay.requests.map { it["action"]!!.jsonPrimitive.content })
        relay.server.close()
        Unit
    }

    // ---- propose_test 传输层两例 ------------------------------------------------

    @Test
    fun proposeLiftsHalfBlindSkeletonFieldsAndServerNumberReturns() = runBlocking {
        val relay = MockActionRelay()
        val store = JdbcSqlStore.inMemory()
        ShiguangSchema.createAll(store)
        val vault = KeyStore(MemorySecretStore())
        vault.importMsk(TEST_MSK.toByteArray(Charsets.UTF_8))
        val keys = BundleKeySource(store, vault)
        val outbox = OutboxRepository(store, nowMs = { 1000 }, newId = counter())
        val spkB = ByteArray(32) { (it + 1).toByte() }
        val service = BundleProposalService(
            keys = keys,
            outbox = outbox,
            memberSpk = { _ -> spkB },
            newBundleKey = { ByteArray(32) { 7 } },
            nowMs = { 1000 },
        )
        val result = service.propose(
            meId = "u-a",
            draft = BundleDraft(
                localId = "local-1",
                kind = ProposalKind.DIRECTIVE,
                inviteeUids = listOf("u-b"),
                steps = directiveSteps(),
            ),
        )
        relay.scriptAdd(
            201,
            buildJsonObject { put("bundle_id", "srv-42"); put("seq_id", 1); put("status", "PROPOSED") },
        )

        val outcome = transport(relay.baseUrl).send(result.entry)
        val proposed = assertIs<SendProposed>(outcome)
        assertEquals("srv-42", proposed.bundleId)
        assertEquals(1L, proposed.seqId)

        val body = relay.requests.single()
        assertEquals("", body["bundle_id"]!!.jsonPrimitive.content) // bundle_id 留空＝服务端建包分支判据
        assertEquals("PROPOSE", body["action"]!!.jsonPrimitive.content)
        assertEquals("DIRECTIVE", body["proposal_type"]!!.jsonPrimitive.content)
        assertEquals(listOf("u-a", "u-b"), (body["participant_uids"] as kotlinx.serialization.json.JsonArray).map { it.jsonPrimitive.content })
        // Directive 不带 quorum（各步骤各用自己的 M-of-N 门限，包级票数无意义）
        assertTrue(!body.containsKey("quorum"))
        assertEquals(1, (body["member_key_envelopes"] as kotlinx.serialization.json.JsonArray).size)
        assertEquals(2, ((body["topology"] as JsonObject)["steps"] as kotlinx.serialization.json.JsonArray).size)
        val expMs = (result.entry.payload["expires_at_ms"] as JsonPrimitive).long
        assertEquals(expMs / 1000, body["expires_at"]!!.jsonPrimitive.long)
        relay.server.close()
        Unit
    }

    @Test
    fun existingActionBodyStaysThreeFields() = runBlocking {
        val relay = MockActionRelay()
        val store = JdbcSqlStore.inMemory()
        ShiguangSchema.createAll(store)
        val outbox = OutboxRepository(store, nowMs = { 1000 }, newId = counter())
        outbox.enqueue("b-1", "ACCEPT", buildJsonObject { put("step_id", "s1") })
        transport(relay.baseUrl).send(outbox.pending().single())
        val body = relay.requests.single()
        assertEquals(setOf("bundle_id", "action", "cipher"), body.keys.toSet())
        relay.server.close()
        Unit
    }
}

private fun directiveSteps() = listOf(
    ProposalStepDraft(
        stepId = "s1",
        title = "场地踏勘",
        requiredUids = listOf("u-b"),
        startsAtMs = 1000,
        endsAtMs = 2000,
    ),
    ProposalStepDraft(stepId = "s2", requiredUids = emptyList(), isOptional = true),
)

private const val TEST_MSK = "0123456789abcdef0123456789abcdef"

private fun counter(): () -> String {
    var n = 0
    return { "ob-${n++}" }
}
