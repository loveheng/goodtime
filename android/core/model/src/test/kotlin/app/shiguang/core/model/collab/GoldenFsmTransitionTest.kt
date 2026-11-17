package app.shiguang.core.model.collab

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P2 黄金向量对拍执行器——FSM 转移表（54 case）Kotlin 侧逐 case 对拍。
 *
 * 对拍口径：**终态全字段快照深比**（枚举语义名、对象键与 participants 字典序、
 * required_uids/candidate_slots 保构造序、可空字段显式 null——rules 节规范化规则）。
 * 事件脚本二形：{signal:{action,sender,seq,…}} 网络事件 / {local:{fn,now_ms}} 本地伪
 * 事件（时钟注入）。序列化为独立副本（与 Dart 生成器解耦；两副本漂移即红，
 * 规范以向量集 rules 节为准）。
 */
class GoldenFsmTransitionTest {

    private fun loadSet(): JsonObject {
        val f = File("../../../test/golden/fsm_transition_vectors.json")
        check(f.exists()) { "向量文件不存在: ${f.absolutePath}" }
        return Json.parseToJsonElement(f.readText()).jsonObject
    }

    // ---- JSON → 输入模型 ---------------------------------------------------------

    private val actionOf: Map<String, CollabFsm.Action> =
        CollabFsm.Action.entries.associateBy { it.name }

    private fun reasonOf(name: String) = when (name) {
        "TIME_CONFLICT" -> CollabFsm.RejectReason.TIME_CONFLICT
        else -> CollabFsm.RejectReason.ROLE_UNWILLING
    }

    private fun prefOf(name: String) = when (name) {
        "PREFER" -> CollabFsm.Preference.PREFER
        "OK" -> CollabFsm.Preference.OK
        else -> CollabFsm.Preference.NO
    }

    private fun signalOf(m: JsonObject): CollabFsm.Signal = CollabFsm.Signal(
        action = actionOf[m["action"]!!.jsonPrimitive.content]!!,
        sender = m["sender"]!!.jsonPrimitive.content,
        seq = m["seq"]!!.jsonPrimitive.long!!,
        serverAtMs = m["server_at_ms"]?.jsonPrimitive?.long ?: 0L,
        stepId = m["step_id"]?.jsonPrimitive?.contentOrNull,
        slotId = m["slot_id"]?.jsonPrimitive?.contentOrNull,
        rejectReason = m["reject_reason"]?.let { reasonOf(it.jsonPrimitive.content) },
        preference = m["preference"]?.let { prefOf(it.jsonPrimitive.content) },
        uids = (m["uids"] as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList(),
        skeleton = (m["skeleton"] as? JsonArray)?.map { sp ->
            val o = sp.jsonObject
            CollabFsm.StepSpec(
                stepId = o["step_id"]!!.jsonPrimitive.content,
                requiredUids = (o["required_uids"] as? JsonArray)
                    ?.map { it.jsonPrimitive.content } ?: emptyList(),
                threshold = o["threshold"]?.jsonPrimitive?.long?.toInt() ?: 0,
                isOptional = o["is_optional"]?.jsonPrimitive?.content == "true",
            )
        } ?: emptyList(),
        initialSlots = (m["initial_slots"] as? JsonArray)
            ?.map { it.jsonPrimitive.content } ?: emptyList(),
        quorum = m["quorum"]?.jsonPrimitive?.long?.toInt(),
        expiresAtMs = m["expires_at_ms"]?.jsonPrimitive?.long,
    )

    // ---- 状态 → 规范 JSON（独立副本；规则=向量集 rules 节） ------------------------

    private val statusNames = mapOf(
        CollabFsm.BundleStatus.DRAFT to "DRAFT",
        CollabFsm.BundleStatus.PROPOSED to "PROPOSED",
        CollabFsm.BundleStatus.STALLED to "STALLED",
        CollabFsm.BundleStatus.CONFIRMED to "CONFIRMED",
        CollabFsm.BundleStatus.CANCELLED to "CANCELLED",
        CollabFsm.BundleStatus.EXPIRED to "EXPIRED",
        CollabFsm.BundleStatus.ARCHIVED to "ARCHIVED",
        CollabFsm.BundleStatus.ABANDONED_LOCALLY to "ABANDONED_LOCALLY",
    )

    private val stepStatusNames = mapOf(
        CollabFsm.StepStatus.PENDING to "PENDING",
        CollabFsm.StepStatus.READY to "READY",
        CollabFsm.StepStatus.TIME_CLASH to "TIME_CLASH",
        CollabFsm.StepStatus.ROLE_CLASH to "ROLE_CLASH",
        CollabFsm.StepStatus.OPTIONAL to "OPTIONAL",
        CollabFsm.StepStatus.PRUNED to "PRUNED",
        CollabFsm.StepStatus.RECHECK to "RECHECK",
    )

    private val voteStateNames = mapOf(
        CollabFsm.VoteState.PENDING to "PENDING",
        CollabFsm.VoteState.ACCEPTED to "ACCEPTED",
        CollabFsm.VoteState.PENDING_RECHECK to "PENDING_RECHECK",
        CollabFsm.VoteState.REJECTED to "REJECTED",
    )

    private fun reasonName(r: CollabFsm.RejectReason) =
        if (r == CollabFsm.RejectReason.TIME_CONFLICT) "TIME_CONFLICT" else "ROLE_UNWILLING"

    private fun prefName(p: CollabFsm.Preference) = p.name // PREFER/OK/NO

    private fun stepToJson(st: CollabFsm.StepState): JsonObject {
        val ballots = st.ballots.entries
            .sortedBy { it.key }
            .associate { (uid, b) ->
                uid to buildJsonObject {
                    put("state", voteStateNames[b.state]!!)
                    if (b.reason != null) put("reason", reasonName(b.reason))
                }
            }
        return buildJsonObject {
            put("step_id", st.stepId)
            put("required_uids", strs(st.requiredUids))
            put("threshold", st.threshold)
            put("is_optional", st.isOptional)
            put("status", stepStatusNames[st.status]!!)
            put("revision", st.revision)
            put("ballots", JsonObject(ballots))
        }
    }

    private fun strs(items: List<String>): JsonArray =
        JsonArray(items.map { kotlinx.serialization.json.JsonPrimitive(it) })

    private fun stateToJson(s: CollabFsm.BundleState): JsonObject {
        val steps = s.steps.keys.sorted().associateWith { stepToJson(s.steps[it]!!) }
        val votes = s.votes.entries.sortedBy { it.key }.associate { (slot, m) ->
            slot to JsonObject(
                m.entries.sortedBy { it.key }.associate { (uid, p) ->
                    uid to kotlinx.serialization.json.JsonPrimitive(prefName(p))
                },
            )
        }
        return buildJsonObject {
            put("bundle_id", s.bundleId)
            put("proposal_type", s.proposalType.name)
            put("status", statusNames[s.status]!!)
            put("base_revision", s.baseRevision)
            put("max_seq_id", s.maxSeqId)
            put(
                "initiator_id",
                s.initiatorId?.let { kotlinx.serialization.json.JsonPrimitive(it) }
                    ?: JsonNull,
            )
            put("participants", strs(s.participants.sorted()))
            put("key_version", s.keyVersion)
            put("steps", JsonObject(steps))
            put("candidate_slots", strs(s.candidateSlots))
            put("votes", JsonObject(votes))
            put(
                "winning_slot",
                s.winningSlot?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: JsonNull,
            )
            put(
                "quorum",
                s.quorum?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: JsonNull,
            )
            put(
                "expires_at_ms",
                s.expiresAtMs?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: JsonNull,
            )
            put(
                "stalled_since_ms",
                s.stalledSinceMs?.let { kotlinx.serialization.json.JsonPrimitive(it) }
                    ?: JsonNull,
            )
        }
    }

    @Test
    fun `fsm-transition 向量全量对拍`() {
        val set = loadSet()
        val cases = set["cases"] as? JsonArray ?: error("cases 缺失")
        for (c in cases) {
            val case = c.jsonObject
            val id = case["id"]!!.jsonPrimitive.content
            val op = case["op"]!!.jsonPrimitive.content
            assertEquals("replay", op, "$id: FSM 集统一 op=replay")
            val input = case["input"]!!.jsonObject
            val expected = case["expect"]!!.jsonObject

            var s = CollabFsm.BundleState.empty(
                input["bundle_id"]!!.jsonPrimitive.content,
                if (input["proposal_type"]!!.jsonPrimitive.content == "CONSENSUS") {
                    CollabFsm.ProposalType.CONSENSUS
                } else {
                    CollabFsm.ProposalType.DIRECTIVE
                },
            )
            for (item in (input["events"] as JsonArray)) {
                val m = item.jsonObject
                if (m.containsKey("signal")) {
                    s = CollabFsm.replay(s, listOf(signalOf(m["signal"]!!.jsonObject)))
                } else {
                    val local = m["local"]!!.jsonObject
                    val now = local["now_ms"]!!.jsonPrimitive.long
                    s = when (local["fn"]!!.jsonPrimitive.content) {
                        "maybe_expire" -> CollabFsm.maybeExpire(s, now)
                        else -> CollabFsm.maybeAbandon(s, now)
                    }
                }
            }
            assertEquals(expected, stateToJson(s), id)
        }
        assertTrue(cases.size == 54, "case 数应 54，实际 ${cases.size}")
    }
}
