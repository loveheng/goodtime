package app.shiguang.core.model.collab

import app.shiguang.core.model.JdbcSqlStore
import app.shiguang.core.model.ShiguangSchema
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import shiguang.relay.v1.RelayProtocol.ActionType

/**
 * test/collab_propose_test.dart Kotlin 移植（编排半边）：①服务端可见面只有结构
 * 四件套；②payload 与 FSM 信号严格互逆；③草稿核验；④入箱 → 认祖归宗全链路
 * （传输层两例归 relay 传输族节点，此处用脚本化传输驱动同一认祖路径）。
 */

private fun directiveDraft(invitees: Int = 1) = BundleDraft(
    localId = "local-1",
    kind = ProposalKind.DIRECTIVE,
    inviteeUids = listOf("u-b", "u-c").take(invitees),
    steps = listOf(
        ProposalStepDraft(
            stepId = "s1",
            title = "场地踏勘",
            requiredUids = listOf("u-b"),
            startsAtMs = 1000,
            endsAtMs = 2000,
        ),
        ProposalStepDraft(stepId = "s2", requiredUids = emptyList(), isOptional = true),
    ),
)

private fun strList(e: kotlinx.serialization.json.JsonElement?): List<String> =
    (e as JsonArray).map { it.jsonPrimitive.content }

/** 裸异常传输：模拟传输层抛非 SendOutcome 异常（坏网络）。 */
private class DeadTransport : OutboxTransport {
    override suspend fun send(e: OutboxEntry): SendOutcome = throw RuntimeException("net down")
}

/** 测试 MSK 固定档：装配与断言两侧共用同一 vault 口径（随机 MSK 无法跨构造共享）。 */
private val TEST_MSK = "0123456789abcdef0123456789abcdef"

private suspend fun unlockedVault(): KeyStore =
    KeyStore(MemorySecretStore()).also { it.importMsk(TEST_MSK.toByteArray(Charsets.UTF_8)) }

/** propose 全家装配产物（断言侧复用同一 vault/keys/outbox）。 */
private class ProposeFixture(
    val store: JdbcSqlStore,
    val vault: KeyStore,
    val keys: BundleKeySource,
    val outbox: OutboxRepository,
    val result: BundleProposal,
    val spkSeeds: Map<String, ByteArray>,
    val bundleKey: ByteArray,
)

private suspend fun proposed(store: JdbcSqlStore, invitees: Int = 2, newId: () -> String): ProposeFixture {
    val vault = unlockedVault()
    vault.ensureReady()
    val keys = BundleKeySource(store, vault)
    val outbox = OutboxRepository(store, nowMs = { 1000 }, newId = newId)
    val spkSeeds = mapOf(
        "u-b" to ByteArray(32) { (it + 1).toByte() },
        "u-c" to ByteArray(32) { (it + 41).toByte() },
    )
    val bundleKey = ByteArray(32) { 7 }
    val service = BundleProposalService(
        keys = keys,
        outbox = outbox,
        memberSpk = { uid -> X25519Ops.publicKeyFromSeed(spkSeeds[uid]!!) },
        newBundleKey = { bundleKey },
        nowMs = { 1000 },
    )
    val result = service.propose(meId = "u-a", draft = directiveDraft(invitees = invitees))
    return ProposeFixture(store, vault, keys, outbox, result, spkSeeds, bundleKey)
}

private suspend fun freshDb(): JdbcSqlStore {
    val store = JdbcSqlStore.inMemory()
    ShiguangSchema.createAll(store)
    return store
}

class CollabProposeTest {

    // ---- 拓扑与载荷 -----------------------------------------------------------

    @Test
    fun serverTopologyOnlyStructuralFourFields() {
        val topo = serverTopologyOf(directiveDraft())
        val steps = (topo["steps"] as JsonArray).map { it as JsonObject }
        assertEquals(2, steps.size)
        for (s in steps) {
            assertEquals(
                setOf("step_id", "required_uids", "threshold", "is_optional"),
                s.keys.toSet(),
            )
        }
        assertTrue(!topo.toString().contains("场地踏勘")) // 标题一律不露
        assertEquals(1L, (steps[0]["threshold"] as JsonPrimitive).longOrNull)
        assertEquals(0L, (steps[1]["threshold"] as JsonPrimitive).longOrNull)
    }

    @Test
    fun directivePayloadRoundTripsFsmSignal() = runTest {
        val me = "u-a"
        val payload = proposalPayloadOf(initiatorId = me, draft = directiveDraft(), nowMs = 5000)
        val signal = signalFromPayload(ActionType.PROPOSE, payload, senderId = me, seqId = 1)
        assertEquals(setOf("u-a", "u-b"), signal.uids.toSet())
        assertEquals(listOf("s1", "s2"), signal.skeleton.map { it.stepId })
        assertEquals(listOf("u-b"), signal.skeleton.first().requiredUids)
        assertEquals(1, signal.skeleton.first().threshold)
        assertEquals(true, signal.skeleton.last().isOptional)
        assertEquals(5000L + K_PROPOSAL_DEFAULT_TTL_MS, signal.expiresAtMs)

        val state = CollabFsm.reduceEvent(
            CollabFsm.BundleState.empty("b", CollabFsm.ProposalType.DIRECTIVE),
            signal,
        )
        assertEquals(CollabFsm.BundleStatus.PROPOSED, state.status)
        assertEquals(setOf("s1", "s2"), state.steps.keys.toSet())
    }

    @Test
    fun consensusPayloadInitialSlotsAndQuorum() = runTest {
        val draft = BundleDraft(
            localId = "local-c",
            kind = ProposalKind.CONSENSUS,
            inviteeUids = listOf("u-b"),
            quorum = 2,
            steps = listOf(
                ProposalStepDraft(stepId = "slot-a", title = "周六下午"),
                ProposalStepDraft(stepId = "slot-b", title = "周日上午"),
            ),
        )
        val payload = proposalPayloadOf(initiatorId = "u-a", draft = draft, nowMs = 1000)
        val signal = signalFromPayload(ActionType.PROPOSE, payload, senderId = "u-a", seqId = 1)
        assertEquals(listOf("slot-a", "slot-b"), signal.initialSlots)
        assertEquals(2, signal.quorum)
        val state = CollabFsm.reduceEvent(
            CollabFsm.BundleState.empty("b", CollabFsm.ProposalType.CONSENSUS),
            signal,
        )
        assertEquals(listOf("slot-a", "slot-b"), state.candidateSlots)
        assertEquals(2, state.quorum)
    }

    @Test
    fun proposalAliveTtlFarTargetRaisesToDayBefore() {
        val day = 24L * 60 * 60 * 1000
        val hour = 60L * 60 * 1000
        // now=0；目标时刻 10d+6h 落在 UTC 日界 10d 内 → 前一天 9d → 存活 9 天
        val far = proposalAliveTtlMs(
            nowMs = 0,
            steps = listOf(
                ProposalStepDraft(stepId = "s1", requiredUids = listOf("u-b"), startsAtMs = 10 * day + 6 * hour),
            ),
        )
        assertEquals(9 * day, far)
        // 无任何时间锚 → 24h 底座（软锁淡出同源）
        val bare = proposalAliveTtlMs(
            nowMs = 0,
            steps = listOf(ProposalStepDraft(stepId = "s1", requiredUids = listOf("u-b"))),
        )
        assertEquals(24 * hour, bare)
        // 发起端显式下传：expires_at_ms = now + aliveTtl（成员端 FSM 直接消费）
        val payload = proposalPayloadOf(
            initiatorId = "u-a",
            draft = directiveDraft(),
            nowMs = 0,
            aliveTtlMs = 9 * day,
        )
        assertEquals(9 * day, (payload["expires_at_ms"] as JsonPrimitive).longOrNull)
    }

    // ---- 草稿核验 --------------------------------------------------------------

    @Test
    fun validateDraftRejectsFourIllegalShapes() {
        assertFailsWith<IllegalArgumentException> {
            validateDraft(
                BundleDraft(
                    localId = "l",
                    kind = ProposalKind.DIRECTIVE,
                    inviteeUids = emptyList(),
                    steps = listOf(ProposalStepDraft(stepId = "s1")),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            validateDraft(
                BundleDraft(
                    localId = "l",
                    kind = ProposalKind.DIRECTIVE,
                    inviteeUids = (0 until 16).map { "u$it" }, // 17 人 > 上限 16
                    steps = listOf(ProposalStepDraft(stepId = "s1")),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            validateDraft(
                BundleDraft(
                    localId = "l",
                    kind = ProposalKind.DIRECTIVE,
                    inviteeUids = listOf("u-b"),
                    steps = listOf(
                        ProposalStepDraft(stepId = "s1"),
                        ProposalStepDraft(stepId = "s1"),
                    ),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            validateDraft(
                BundleDraft(
                    localId = "l",
                    kind = ProposalKind.CONSENSUS,
                    inviteeUids = listOf("u-b"),
                    steps = (0 until 9).map { ProposalStepDraft(stepId = "s$it") },
                ),
            )
        }
        // 15 名被邀 + 发起人 = 16，恰好在配额线上
        validateDraft(
            BundleDraft(
                localId = "l",
                kind = ProposalKind.DIRECTIVE,
                inviteeUids = (0 until 15).map { "u$it" },
                steps = listOf(ProposalStepDraft(stepId = "s1")),
            ),
        )
    }

    // ---- 本地发起 + 认祖归宗 ---------------------------------------------------

    @Test
    fun proposePersistsKeyWrapsEnvelopesEnqueuesSelfProvingPropose() = runTest {
        val fx = proposed(freshDb(), newId = counter())
        val r = fx.result

        assertEquals(1, r.keyVer)
        assertEquals(2, r.envelopeCount)

        assertContentEquals(fx.bundleKey, fx.keys(r.draft.localId, 1)!!)

        val pending = fx.outbox.pending()
        assertEquals(1, pending.size)
        val entry = pending.single()
        assertEquals("PROPOSE", entry.actionType)
        assertEquals("local-1", entry.bundleId) // 服务端发号前只有本地临时 id
        assertEquals(listOf("u-a", "u-b", "u-c"), strList(entry.payload["participants"]))
        assertEquals(2, (entry.payload["envelopes"] as JsonArray).size)
        assertTrue(entry.payload.containsKey("topology"))

        val first = (entry.payload["envelopes"] as JsonArray).first() as JsonObject
        assertEquals("u-b", first["recipient_user_id"]!!.jsonPrimitive.content)
        // 本人 SPK 解得开（与发起侧同一密钥）
        assertContentEquals(
            fx.bundleKey,
            openBundleKeyEnvelope(first, fx.spkSeeds["u-b"]!!),
        )
    }

    @Test
    fun drainReconcilesRebindsAndPersistsHead() = runTest {
        val fx = proposed(freshDb(), invitees = 1, newId = counter())
        // 窗口期内随手又入了一条后续操作（改期），仍挂在临时本地 id 上
        fx.outbox.enqueue(
            "local-1",
            "PATCH_TIME",
            buildJsonObject { put("step_id", "s1"); put("starts_at_ms", 99L) },
        )

        val seen = mutableListOf<OutboxEntry>()
        val transport = object : OutboxTransport {
            private var cursor = 0
            override suspend fun send(e: OutboxEntry): SendOutcome {
                seen.add(e)
                return if (cursor++ == 0) SendProposed("srv-7", 1) else SendAccepted
            }
        }
        val reconciler = PendingProposalReconciler(
            bundles = CollabBundleRepository(fx.store, nowMs = { 1000 }),
            keys = fx.keys,
            outbox = fx.outbox,
        )
        val report = OutboxDrainer(fx.outbox, transport) { entry, ack -> reconciler(entry, ack) }.drain()
        assertEquals("srv-7", report.created.single().bundleId)
        assertEquals(2, report.sent)

        // ①密钥改挂服务端号，临时号绝不并存
        val ring = fx.store.rawQuery("SELECT bundle_id FROM collab_key_ring")
        assertEquals(setOf("srv-7"), ring.map { it["bundle_id"] }.toSet())
        assertContentEquals(fx.bundleKey, fx.keys("srv-7", 1)!!)

        // ②改绑生效：后续操作是拿服务端号发的（改绑失效则这里恒为 local-1 → 404）
        assertEquals("PATCH_TIME", seen.last().actionType)
        assertEquals("srv-7", seen.last().bundleId)

        // ③包头落地，供水位续拉与列表展示
        val head = CollabBundleRepository(fx.store, nowMs = { 1000 }).bundleHeader("srv-7")!!
        assertEquals(listOf("u-a", "u-b"), head.participants)
        assertEquals("DIRECTIVE", head.proposalType)
        assertEquals("u-a", head.initiatorId)

        // ④幂等：服务端重放同一 client_msg_id 再次走到这里，重复执行零副作用
        reconciler(fx.result.entry, report.created.single())
        assertEquals(1, fx.store.rawQuery("SELECT bundle_id FROM collab_key_ring").size)
        assertEquals(1, fx.store.rawQuery("SELECT id FROM collab_bundles").size)
    }

    @Test
    fun offlineProposeStaysPendingWithKeyIntact() = runTest {
        val fx = proposed(freshDb(), invitees = 1, newId = counter())
        val reconciler = PendingProposalReconciler(
            bundles = CollabBundleRepository(fx.store, nowMs = { 1000 }),
            keys = fx.keys,
            outbox = fx.outbox,
        )
        val report = OutboxDrainer(fx.outbox, DeadTransport()) { entry, ack -> reconciler(entry, ack) }.drain()
        assertEquals(true, report.stoppedByNetwork)
        val still = fx.outbox.pending()
        assertEquals("PROPOSE", still.single().actionType)
        assertEquals(1, still.single().attempts)
        // 未发出绝不能先行认祖归宗（临时密钥保留，重连后仍可正常发）
        assertContentEquals(fx.bundleKey, fx.keys("local-1", 1)!!)
    }
}

/** 发件箱幂等键稳定序（真随机后缀会让同一毫秒的两条条目排序抖动）。 */
private fun counter(): () -> String {
    var n = 0
    return { "ob-${n++}" }
}
