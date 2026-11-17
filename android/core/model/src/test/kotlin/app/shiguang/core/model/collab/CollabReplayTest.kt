package app.shiguang.core.model.collab

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import shiguang.relay.v1.RelayProtocol.ActionType

/** test/replay_test.dart Kotlin 移植（signalFromPayload 纯映射 + replayBundle 解密回放）。 */

private val K = ByteArray(32) { (it + 1).toByte() }

private fun ev(
    seq: Long,
    action: String,
    payload: JsonObject,
    keyVer: Int = 1,
    senderId: String = "u2",
): StoredCollabEvent {
    val sealed = sealPayload(K, payload.toString().toByteArray(Charsets.UTF_8), keyVer)
    return StoredCollabEvent(
        seqId = seq,
        actionType = action,
        senderId = senderId,
        cipher = sealed.encode().toByteArray(Charsets.UTF_8),
        keyVer = keyVer,
        clientMsgId = "c$seq",
        topologyRef = "t0",
        serverAtMs = 1700000000000L + seq,
    )
}

class CollabReplayTest {

    // ---- signalFromPayload 纯映射 --------------------------------------------

    @Test
    fun acceptTakesStepId() = runTest {
        val s = signalFromPayload(
            ActionType.ACCEPT,
            buildJsonObject { put("step_id", "s1") },
            senderId = "u2",
            seqId = 2,
        )
        assertEquals("s1", s.stepId)
        assertEquals(CollabFsm.Action.ACCEPT, s.action)
    }

    @Test
    fun rejectMapsReasonAndSuggest() = runTest {
        val s = signalFromPayload(
            ActionType.REJECT,
            buildJsonObject {
                put("step_id", "s1")
                put("reason", "ROLE_UNWILLING")
                put("suggest_reassign", "u3")
            },
            senderId = "u2",
            seqId = 2,
        )
        assertEquals("s1", s.stepId)
        assertEquals(CollabFsm.RejectReason.ROLE_UNWILLING, s.rejectReason)
        assertEquals(listOf("u3"), s.uids)
    }

    @Test
    fun proposeDirectiveParsesSkeletonAndParticipants() = runTest {
        val s = signalFromPayload(
            ActionType.PROPOSE,
            buildJsonObject {
                put("proposal_type", "DIRECTIVE")
                putJsonArray("skeleton") {
                    add(
                        buildJsonObject {
                            put("step_id", "s1")
                            putJsonArray("required_uids") { add("u2"); add("u3") }
                            put("threshold", 2)
                            put("is_optional", false)
                        },
                    )
                }
                putJsonArray("participants") { add("u1"); add("u2"); add("u3") }
                put("expires_at_ms", 123)
            },
            senderId = "u1",
            seqId = 1,
        )
        assertEquals("s1", s.skeleton.first().stepId)
        assertEquals(2, s.skeleton.first().threshold)
        assertEquals(listOf("u1", "u2", "u3"), s.uids)
    }

    @Test
    fun bypassAndReassignTakeTargetUids() = runTest {
        val b = signalFromPayload(
            ActionType.BYPASS,
            buildJsonObject {
                put("step_id", "s1")
                putJsonArray("bypass_uids") { add("u2") }
            },
            senderId = "u1",
            seqId = 5,
        )
        val r = signalFromPayload(
            ActionType.REASSIGN,
            buildJsonObject {
                put("step_id", "s1")
                putJsonArray("reassign_to") { add("u4") }
            },
            senderId = "u1",
            seqId = 6,
        )
        assertEquals(listOf("u2"), b.uids)
        assertEquals(listOf("u4"), r.uids)
    }

    // ---- replayBundle 解密回放 -------------------------------------------------

    @Test
    fun directiveMofNProgressDerivedFromBallots() = runTest {
        val events = listOf(
            ev(
                1, "PROPOSE",
                buildJsonObject {
                    put("proposal_type", "DIRECTIVE")
                    putJsonArray("skeleton") {
                        add(
                            buildJsonObject {
                                put("step_id", "s1")
                                putJsonArray("required_uids") { add("u2"); add("u3") }
                                put("threshold", 2)
                                put("is_optional", false)
                            },
                        )
                    }
                    putJsonArray("participants") { add("u1"); add("u2"); add("u3") }
                    put("expires_at_ms", 999)
                },
                senderId = "u1",
            ),
            ev(2, "ACCEPT", buildJsonObject { put("step_id", "s1") }, senderId = "u2"),
            ev(3, "ACCEPT", buildJsonObject { put("step_id", "s1") }, senderId = "u3"),
        )
        val state = replayBundle(events, { _, _ -> K }, bundleId = "b1")
        val step = state.steps.getValue("s1")
        assertEquals(CollabFsm.ProposalType.DIRECTIVE, state.proposalType)
        assertEquals(3, state.participants.size)
        assertTrue(state.participants.contains("u1"))
        assertEquals(2, step.acceptCount)
        assertEquals(CollabFsm.StepStatus.READY, step.status)
    }

    @Test
    fun consensusInitialDispatchAligned() = runTest {
        val events = listOf(
            ev(
                1, "PROPOSE",
                buildJsonObject {
                    put("proposal_type", "CONSENSUS")
                    putJsonArray("initial_slots") { add("slot-a") }
                    put("quorum", 2)
                    putJsonArray("participants") { add("u1"); add("u2") }
                    put("expires_at_ms", 999)
                },
                senderId = "u1",
            ),
            ev(
                2, "VOTE",
                buildJsonObject { put("slot_id", "slot-a"); put("preference", "PREFER") },
                senderId = "u2",
            ),
        )
        val state = replayBundle(events, { _, _ -> K }, bundleId = "b2")
        assertEquals(CollabFsm.ProposalType.CONSENSUS, state.proposalType)
        // directive 分支不会有候选 slot；consensus 应含 slot 候选
        assertEquals(1, state.candidateSlots.size)
    }

    @Test
    fun missingKeyHalfBlindSkip() = runTest {
        val events = listOf(
            ev(
                1, "PROPOSE",
                buildJsonObject {
                    put("proposal_type", "DIRECTIVE")
                    putJsonArray("skeleton") {
                        add(
                            buildJsonObject {
                                put("step_id", "s1")
                                putJsonArray("required_uids") { add("u2") }
                                put("threshold", 1)
                                put("is_optional", false)
                            },
                        )
                    }
                    putJsonArray("participants") { add("u1"); add("u2") }
                },
                senderId = "u1",
            ),
        )
        val state = replayBundle(events, { _, _ -> null }, bundleId = "b3")
        // PROPOSE 未解密 → 无步骤；状态仍为 draft（半盲跳过）
        assertTrue(state.steps.isEmpty())
        assertEquals(CollabFsm.BundleStatus.DRAFT, state.status)
    }
}
