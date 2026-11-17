package app.shiguang.core.model.collab

import app.shiguang.core.model.collab.CollabFsm.Action
import app.shiguang.core.model.collab.CollabFsm.BundleState
import app.shiguang.core.model.collab.CollabFsm.BundleStatus
import app.shiguang.core.model.collab.CollabFsm.Preference
import app.shiguang.core.model.collab.CollabFsm.ProposalType
import app.shiguang.core.model.collab.CollabFsm.RejectReason
import app.shiguang.core.model.collab.CollabFsm.Signal
import app.shiguang.core.model.collab.CollabFsm.StepSpec
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import shiguang.relay.v1.RelayProtocol.ActionType

/**
 * 协同事件 → FSM 状态回放（lib/collab/replay.dart Kotlin 等价，Phase 4.3 C 页
 * 决策矩阵数据源）。
 *
 * 设计口径（collab-implementation §3.2 / §11.1 分层单向数据流）：UI 决策矩阵直接
 * 消费 FSM 派生态 [BundleState]，而派生态由落库原始事件经解密 + 回放得到——
 * **绝不在 UI 做解密**，解密统一在此一处。
 *
 * 两层：
 * 1. [signalFromPayload]：纯函数，把**已解密**的载荷 JSON 映射成 FSM 信号
 *    [Signal]（动作枚举 + 明文骨架引用），不碰 crypto，可独立单测；
 * 2. [replayBundle]：逐事件取 K_bundle 解密 → 调 [signalFromPayload] → 沿
 *    [CollabFsm.reduceEvent] 回放。解密失败/缺密钥的事件跳过（半盲投影铁律 1：
 *    个别密文解不开骨架仍推进，绝不抛）。
 */

/** 拒绝理由载荷字符串 → FSM 枚举（容错：未知/缺省归时间冲突）。 */
fun reasonFromPayload(r: String?): RejectReason = when (r) {
    "ROLE_UNWILLING" -> RejectReason.ROLE_UNWILLING
    else -> RejectReason.TIME_CONFLICT
}

/** Consensus 三值偏好载荷字符串 → FSM 枚举。 */
fun prefFromPayload(p: String?): Preference = when (p) {
    "PREFER" -> Preference.PREFER
    "NO" -> Preference.NO
    else -> Preference.OK
}

// ---- 载荷字段取用助手（对齐 Dart 动态取用语义：类型不符即抛，不静默改读） ----

/** Dart '${e}' 镜像：null → "null"，标量 → 字面文本，容器 → 文本形式。 */
private fun strOf(e: JsonElement?): String = when (e) {
    null, is JsonNull -> "null"
    is JsonPrimitive -> e.content
    else -> e.toString()
}

/** Dart `v as List?` + 逐元素 '$e' 镜像；非数组非 null 抛（不静默空表）。 */
private fun strList(e: JsonElement?): List<String> = when (e) {
    null, is JsonNull -> emptyList()
    is JsonArray -> e.map { strOf(it) }
    else -> throw IllegalArgumentException("载荷字段须为数组: $e")
}

/** Dart `v as int?` 镜像：null → null，整型标量 → Long，其余抛。 */
private fun intOrNull(e: JsonElement?): Long? = when (e) {
    null, is JsonNull -> null
    is JsonPrimitive -> e.longOrNull ?: throw IllegalArgumentException("载荷字段须为整型: $e")
    else -> throw IllegalArgumentException("载荷字段须为整型: $e")
}

/** Dart `v as String?` 镜像：null → null，字符串标量 → 值，其余抛。 */
private fun strOrNull(e: JsonElement?): String? = when (e) {
    null, is JsonNull -> null
    is JsonPrimitive -> if (e.isString) e.content
    else throw IllegalArgumentException("载荷字段须为字符串: $e")
    else -> throw IllegalArgumentException("载荷字段须为字符串: $e")
}

/**
 * 把已解密的载荷映射成 FSM 信号（纯函数，不碰 DB/crypto）。
 *
 * [payload] 为信封明文 JSON（经 [openPayload] 解出）。各动作字段取用对齐
 * member_calendar_page 的入队约定与 relay action.go 的回音壁约定。
 * 分支字段与 Dart 逐字对齐：PROPOSE/VOTE 不携带 stepId，仅 VOTE 做 slotId 回落。
 */
fun signalFromPayload(
    action: ActionType,
    payload: JsonObject,
    senderId: String,
    seqId: Long,
    serverAtMs: Long = 0,
): Signal {
    val stepId = strOrNull(payload["step_id"])
    val slotId = strOrNull(payload["slot_id"])
    return when (action) {
        ActionType.PROPOSE -> {
            val skeleton = buildList {
                val rawSteps = payload["skeleton"]
                if (rawSteps is JsonArray) {
                    for (s in rawSteps) {
                        if (s !is JsonObject) continue
                        add(
                            StepSpec(
                                stepId = strOf(s["step_id"]),
                                requiredUids = strList(s["required_uids"]),
                                threshold = (intOrNull(s["threshold"]) ?: 0L).toInt(),
                                isOptional = (s["is_optional"] as? JsonPrimitive)?.booleanOrNull == true,
                            ),
                        )
                    }
                }
            }
            Signal(
                action = Action.valueOf(action.name),
                sender = senderId,
                seq = seqId,
                serverAtMs = serverAtMs,
                skeleton = skeleton,
                uids = strList(payload["participants"]) + strList(payload["uids"]),
                initialSlots = strList(payload["initial_slots"]),
                quorum = intOrNull(payload["quorum"])?.toInt(),
                expiresAtMs = intOrNull(payload["expires_at_ms"]),
                briefing = strOrNull(payload["briefing"]),
            )
        }
        ActionType.ACCEPT -> Signal(
            action = Action.valueOf(action.name),
            sender = senderId,
            seq = seqId,
            serverAtMs = serverAtMs,
            stepId = stepId,
        )
        ActionType.REJECT -> {
            val suggest = payload["suggest_reassign"]
            Signal(
                action = Action.valueOf(action.name),
                sender = senderId,
                seq = seqId,
                serverAtMs = serverAtMs,
                stepId = stepId,
                rejectReason = reasonFromPayload(strOrNull(payload["reason"])),
                uids = if (suggest != null && suggest !is JsonNull) listOf(strOf(suggest)) else emptyList(),
            )
        }
        ActionType.VOTE -> Signal(
            action = Action.valueOf(action.name),
            sender = senderId,
            seq = seqId,
            serverAtMs = serverAtMs,
            slotId = slotId ?: stepId,
            preference = prefFromPayload(strOrNull(payload["preference"])),
        )
        ActionType.PATCH_TIME, ActionType.MAKE_OPTIONAL, ActionType.PRUNE, ActionType.REOPEN -> Signal(
            action = Action.valueOf(action.name),
            sender = senderId,
            seq = seqId,
            serverAtMs = serverAtMs,
            stepId = stepId,
        )
        ActionType.REASSIGN -> Signal(
            action = Action.valueOf(action.name),
            sender = senderId,
            seq = seqId,
            serverAtMs = serverAtMs,
            stepId = stepId,
            uids = strList(payload["reassign_to"]),
        )
        ActionType.BYPASS -> Signal(
            action = Action.valueOf(action.name),
            sender = senderId,
            seq = seqId,
            serverAtMs = serverAtMs,
            stepId = stepId,
            uids = strList(payload["bypass_uids"]),
        )
        ActionType.NUDGE -> Signal(
            action = Action.valueOf(action.name),
            sender = senderId,
            seq = seqId,
            serverAtMs = serverAtMs,
            stepId = stepId,
            uids = strList(payload["nudge_uids"]),
        )
        // 骨架中性事件（BRIEFING_UPDATE / STEP_NOTE / REKEY / FREEMASK_* 等）：
        // 仅推进 seq 水位，回放器原样忽略动作语义（BRIEFING_UPDATE 的备忘文本
        // 由 FSM 折叠进状态——Day-2 F6 备忘回显）。
        ActionType.BRIEFING_UPDATE -> Signal(
            action = Action.valueOf(action.name),
            sender = senderId,
            seq = seqId,
            serverAtMs = serverAtMs,
            stepId = stepId,
            briefing = strOrNull(payload["briefing"]),
        )
        else -> Signal(
            action = Action.valueOf(action.name),
            sender = senderId,
            seq = seqId,
            serverAtMs = serverAtMs,
            stepId = stepId,
        )
    }
}

/** 一个步骤的时间槽（起讫毫秒；任一端可空）。 */
data class StepSlot(
    val startsAtMs: Long? = null,
    val endsAtMs: Long? = null,
)

/**
 * 从已解密载荷里提取步骤时段并入 [slots]。
 *
 * 两种形状都认：①载荷顶层自带 step_id（步骤级事件，如 PATCH_TIME 改期）；
 * ②PROPOSE 的 skeleton[] 元素自带时段（骨架每步各带一段）。合并口径：后到的
 * 非空值覆盖＝最新改期生效；不携带时段的事件不覆盖既有。
 */
private fun collectSlot(slots: MutableMap<String, StepSlot>?, payload: JsonObject) {
    if (slots == null) return
    putSlot(
        slots,
        strOrNull(payload["step_id"]),
        intOrNull(payload["starts_at_ms"]),
        intOrNull(payload["ends_at_ms"]),
    )
    val skeleton = payload["skeleton"]
    if (skeleton is JsonArray) {
        for (s in skeleton) {
            if (s !is JsonObject) continue
            putSlot(
                slots,
                strOrNull(s["step_id"]),
                intOrNull(s["starts_at_ms"]),
                intOrNull(s["ends_at_ms"]),
            )
        }
    }
}

private fun putSlot(slots: MutableMap<String, StepSlot>, stepId: String?, st: Long?, en: Long?) {
    if (stepId == null || (st == null && en == null)) return
    val prev = slots[stepId]
    slots[stepId] = StepSlot(
        startsAtMs = st ?: prev?.startsAtMs,
        endsAtMs = en ?: prev?.endsAtMs,
    )
}

/** 存储动作名字符串 → proto 枚举（未知归 null 由回放跳过；javalite UNRECOGNIZED 非契约名）。 */
private fun actionFromString(s: String): ActionType? =
    ActionType.values().firstOrNull { it.name == s }?.takeUnless { it == ActionType.UNRECOGNIZED }

/**
 * 把存储事件回放成 Bundle 状态（解密 + 映射 + 沿 [CollabFsm.reduceEvent] 推进）。
 *
 * [bundleKey] 取 K_bundle（发起人端持有；非发起人端仅半盲骨架）。返回状态的步状态/
 * 表决由 FSM 派生——这是 C 页决策矩阵的唯一权威数据源。
 *
 * 解密失败或缺失密钥的事件被跳过（铁律 1：个别密文解不开骨架仍推进，绝不抛）。
 */
suspend fun replayBundle(
    events: List<StoredCollabEvent>,
    bundleKey: suspend (bundleId: String, keyVer: Int) -> ByteArray?,
    bundleId: String? = null,
    /** 顺手抓取的步骤时间槽（step_id → 时段）。**仅在解密那一刻才拿得到**：骨架只
     *  含结构，真实时段藏在加密载荷里——这是 B 页时间槽 / A 页画板的唯一数据源。 */
    observedSlots: MutableMap<String, StepSlot>? = null,
): BundleState {
    val id = bundleId ?: ""
    var state = BundleState.empty(id, ProposalType.DIRECTIVE)
    for (ev in events) {
        val action = actionFromString(ev.actionType) ?: continue
        // draft→PROPOSE：初态 proposalType 由载荷决定（FSM 派发按 state.proposalType，
        // 故首条 PROPOSE 前必须先把初态类型对齐，否则 Consensus 会被误派发到 directive 分支）
        if (state.status == BundleStatus.DRAFT && action == ActionType.PROPOSE) {
            val pt = proposalTypeOf(ev, id, bundleKey)
            state = BundleState.empty(id, pt)
        }
        val key = bundleKey(id, ev.keyVer)
        if (key == null || key.size != 32) continue // 缺密钥：半盲跳过
        val payload: JsonObject
        try {
            val sealed = SealedPayload.decode(ev.cipher.toString(Charsets.UTF_8))
            val plain = openPayload(key, sealed)
            payload = Json.parseToJsonElement(plain.toString(Charsets.UTF_8)).jsonObject
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            continue // 解密失败：骨架中性跳过
        }
        collectSlot(observedSlots, payload)
        val signal = signalFromPayload(
            action,
            payload,
            senderId = ev.senderId,
            seqId = ev.seqId,
            serverAtMs = ev.serverAtMs,
        )
        state = CollabFsm.reduceEvent(state, signal)
    }
    return state
}

/** 半盲地读出 PROPOSE 载荷的 proposal_type（不依赖 K_bundle：仅用于初态派发对齐）。 */
private suspend fun proposalTypeOf(
    ev: StoredCollabEvent,
    bundleId: String,
    bundleKey: suspend (String, Int) -> ByteArray?,
): ProposalType {
    val key = bundleKey(bundleId, ev.keyVer)
    if (key == null || key.size != 32) return ProposalType.DIRECTIVE
    return try {
        val sealed = SealedPayload.decode(ev.cipher.toString(Charsets.UTF_8))
        val plain = openPayload(key, sealed)
        val p = Json.parseToJsonElement(plain.toString(Charsets.UTF_8)).jsonObject
        if (strOrNull(p["proposal_type"]) == "CONSENSUS") ProposalType.CONSENSUS
        else ProposalType.DIRECTIVE
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        ProposalType.DIRECTIVE
    }
}
