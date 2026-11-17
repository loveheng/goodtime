package app.shiguang.core.model.collab

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 本地发起 bundle（lib/collab/propose.dart 编排半边 Kotlin 等价，Phase 4.3 A 页
 * 背后的编排层；协同设计 §1.7/§3.6/§4.2/§5.3）。密钥信封切片见
 * [wrapBundleKeyForMember]/[openBundleKeyEnvelope]（BundleKeyEnvelope.kt）。
 *
 * 「发起」做了三件此前全仓没人做的事：
 * 1. **造 K_bundle 并落户**：CSPRNG 32B → [BundleKeySource.persist] 用 MSK 封存
 *    后落 collab_key_ring（vault 未解锁即半盲，取钥返回 null，§铁律 1）；
 * 2. **逐成员封信封**：一次性 X25519 EK × 成员 SPK 公钥 DH → AES-256-GCM 封
 *    K_bundle。服务端只当字节盲存，成员拉到自己的信封后用 SPK 私钥解开——
 *    **发起人自己不发信封**，它直接持有明文 K（在 key_ring 里）；
 * 3. **入箱自证**：把服务端仲裁所需的明文元数据（proposal_type/participants/
 *    topology/quorum/expires_at/envelopes）一并写进 payload，action_api 抬出
 *    带上——payload 自证让发件箱行跨进程/杀进程重启后仍走同一条建包路径。
 *
 * ## 「认祖归宗」为什么必须存在
 *
 * bundle_id 由**服务端发号**（store.Propose INSERT … RETURNING bundle_id）。本地在
 * 发出之前只有临时 id（[BundleDraft.localId]），窗口期内：密钥信封、用户随手入队
 * 的后续操作都挂在临时 id 上，而服务端只认自己发的号，临时 id 恒 404。故
 * [PendingProposalReconciler] 拿到 201 回执后做迁改密钥 + 改绑发件箱 + 落包头；
 * 全部按幂等写（服务端重放 client_msg_id 会再次回到这里）。
 */

/** 单 Bundle 成员上限（含发起人，relay store.MaxBundleMembers=16，§1.7）。 */
const val K_MAX_BUNDLE_MEMBERS = 16

/** 24h 底座 TTL（§3.6 2026-10-25 双语义拆分）：一物两用——①幽灵软锁淡出判据
 *  （本地视觉层恒 24h）；②提案存活的下限与「无时间锚草稿」的回落值。 */
const val K_PROPOSAL_DEFAULT_TTL_MS: Long = 24L * 60 * 60 * 1000

/** 集体投票的候选时段上限（与 §4.4 ADD_SLOT 硬上限同一个值，FSM 是 SSOT）。 */
val K_MAX_CONSENSUS_SLOTS: Int = CollabFsm.MAX_CANDIDATE_SLOTS

/** 发起模式（§4.4：两种模式共用传输层，仅业务状态机不同）。 */
enum class ProposalKind {
    /** 各司其职（Directive）：步骤 + 指派必答人 + M-of-N 门限。 */
    DIRECTIVE,

    /** 集体投票（Consensus）：候选时段池，全员三值偏好升至门限即固化。 */
    CONSENSUS,
}

fun proposalTypeOf(kind: ProposalKind): String =
    if (kind == ProposalKind.DIRECTIVE) "DIRECTIVE" else "CONSENSUS"

/**
 * 步骤 / 候选时段草稿。
 *
 * Directive 模式下 [ProposalStepDraft.requiredUids] 是必答成员名单；Consensus 模式
 * 下步骤退化为候选时段，stepId 同时充当 slot_id（成员 VOTE 的 slot_id ?? step_id
 * 收敛于此）。
 */
data class ProposalStepDraft(
    val stepId: String,
    val title: String = "",
    val requiredUids: List<String> = emptyList(),
    /** ≤0 = 全员（回放时回落 required_uids.length）。 */
    val threshold: Int = 0,
    val isOptional: Boolean = false,
    val startsAtMs: Long? = null,
    val endsAtMs: Long? = null,
)

/** 发起草稿（纯数据，UI 收集 → [BundleProposalService.propose] 消费）。 */
data class BundleDraft(
    /** **临时本地 id**：服务端发号前的占位号（认祖归宗时会改绑）。 */
    val localId: String,
    val kind: ProposalKind,
    /** 不含发起人。 */
    val inviteeUids: List<String>,
    val steps: List<ProposalStepDraft>,
    /** Consensus 达成票数；Directive 忽略。 */
    val quorum: Int = 1,
    val ttlMs: Long = K_PROPOSAL_DEFAULT_TTL_MS,
    val briefing: String? = null,
)

/**
 * 草稿合法性核验（§1.7 配额 + §4.2 骨架约束）。违反即抛 [IllegalArgumentException]
 * ——UI 层应当在发送前就把按钮置灰，这里的抛错只是最后一道防线，不是 UX 通道。
 */
fun validateDraft(draft: BundleDraft) {
    if (draft.inviteeUids.isEmpty()) {
        throw IllegalArgumentException("至少邀请一位成员（§1.7：单人不成 bundle）")
    }
    if (draft.inviteeUids.size + 1 > K_MAX_BUNDLE_MEMBERS) {
        throw IllegalArgumentException(
            "成员数 ${draft.inviteeUids.size + 1} 超过上限 $K_MAX_BUNDLE_MEMBERS（§1.7）",
        )
    }
    if (draft.steps.isEmpty()) {
        throw IllegalArgumentException(
            if (draft.kind == ProposalKind.CONSENSUS) "集体投票至少要给一个候选时段"
            else "至少要有一个步骤",
        )
    }
    if (draft.kind == ProposalKind.CONSENSUS && draft.steps.size > K_MAX_CONSENSUS_SLOTS) {
        throw IllegalArgumentException("候选时段上限 $K_MAX_CONSENSUS_SLOTS（§4.4 ADD_SLOT 硬上限）")
    }
    val seen = mutableSetOf<String>()
    for (s in draft.steps) {
        if (!seen.add(s.stepId)) {
            throw IllegalArgumentException("步骤 id 重复：${s.stepId}（骨架主键即 step_id）")
        }
    }
    if (draft.kind == ProposalKind.DIRECTIVE) {
        val pool = draft.inviteeUids.toSet()
        for (s in draft.steps) {
            for (uid in s.requiredUids) {
                if (!pool.contains(uid)) {
                    throw IllegalArgumentException("必答成员 $uid 不在被邀名单里")
                }
            }
        }
    }
}

/**
 * 提案存活 TTL（§3.6 双语义拆分，2026-10-25 拍板）：
 * `proposal_alive_ttl = max(24h, 目标日前一天 − 发起时刻)`。
 *
 * 目标日取草稿步骤最早的 startsAtMs 所在 UTC 日历日——发起时目标日在明文，
 * 成员端 FSM 骨架优先于内容（铁律 1）读不到密文时段，故存活时刻必须由发起端
 * 算好随载荷 expires_at_ms 下发；无任何时间锚的草稿回落 24h 底座。
 * 返回 TTL 时长（ms），不是绝对时刻。
 */
fun proposalAliveTtlMs(nowMs: Long, steps: List<ProposalStepDraft>): Long {
    val dayMs = 24L * 60 * 60 * 1000
    var aliveUntilMs = nowMs + K_PROPOSAL_DEFAULT_TTL_MS
    for (s in steps) {
        val start = s.startsAtMs ?: continue
        val dayBefore = (start / dayMs) * dayMs - dayMs // 目标日 00:00 UTC 的前一天
        if (dayBefore > aliveUntilMs) aliveUntilMs = dayBefore
    }
    return aliveUntilMs - nowMs
}

/** 载荷字段取用（Dart 动态语义镜像：`as String?` / `as int?`）。 */
private fun jsonStrOrNull(e: JsonElement?): String? = when (e) {
    null, is JsonNull -> null
    is JsonPrimitive -> if (e.isString) e.contentOrNull
    else throw IllegalArgumentException("载荷字段须为字符串: $e")
    else -> throw IllegalArgumentException("载荷字段须为字符串: $e")
}

private fun jsonLongOrNull(e: JsonElement?): Long? = when (e) {
    null, is JsonNull -> null
    is JsonPrimitive -> e.longOrNull ?: throw IllegalArgumentException("载荷字段须为整型: $e")
    else -> throw IllegalArgumentException("载荷字段须为整型: $e")
}

/**
 * **服务端可见面**（§4.2 隐私边界）：只有纯结构四件套，标题/真实时间/备注一概
 * 不露——服务端据此推进 quorum 计数器，但读不到「几点做什么」。
 */
fun serverTopologyOf(draft: BundleDraft): JsonObject = buildJsonObject {
    putJsonArray("steps") {
        for (s in draft.steps) {
            add(
                buildJsonObject {
                    put("step_id", s.stepId)
                    putJsonArray("required_uids") { s.requiredUids.forEach { add(it) } }
                    put(
                        "threshold",
                        (if (s.threshold <= 0) s.requiredUids.size else s.threshold).toLong(),
                    )
                    put("is_optional", s.isOptional)
                },
            )
        }
    }
}

/**
 * 加密载荷（全体成员可解）：结构 + 内容（标题/时段/备忘），字段取值与
 * [signalFromPayload] 严格互逆——FSM 派发就是靠这几个键。
 * [aliveTtlMs] 为发起端算好的提案存活 TTL（§3.6，缺省回落 draft.ttlMs）。
 */
fun proposalPayloadOf(
    initiatorId: String,
    draft: BundleDraft,
    nowMs: Long,
    envelopes: List<JsonElement> = emptyList(),
    aliveTtlMs: Long? = null,
): JsonObject = buildJsonObject {
    put("proposal_type", proposalTypeOf(draft.kind))
    put("initiator_id", initiatorId) // 认祖归宗取发起人用（服务端事件虽有 sender，抽屉条目优先本地可执行性）
    putJsonArray("participants") { (listOf(initiatorId) + draft.inviteeUids).forEach { add(it) } }
    putJsonArray("skeleton") {
        for (s in draft.steps) {
            add(
                buildJsonObject {
                    put("step_id", s.stepId)
                    putJsonArray("required_uids") { s.requiredUids.forEach { add(it) } }
                    put(
                        "threshold",
                        (if (s.threshold <= 0) s.requiredUids.size else s.threshold).toLong(),
                    )
                    put("is_optional", s.isOptional)
                    if (s.title.isNotEmpty()) put("title", s.title)
                    if (s.startsAtMs != null) put("starts_at_ms", s.startsAtMs)
                    if (s.endsAtMs != null) put("ends_at_ms", s.endsAtMs)
                },
            )
        }
    }
    // Consensus：候选时段池 = 骨架 step_id 序列（VOTE 的 slot_id 口径）
    if (draft.kind == ProposalKind.CONSENSUS) {
        putJsonArray("initial_slots") { draft.steps.forEach { add(it.stepId) } }
    }
    if (draft.kind == ProposalKind.CONSENSUS) put("quorum", draft.quorum.toLong())
    put("topology", serverTopologyOf(draft))
    put("expires_at_ms", nowMs + (aliveTtlMs ?: draft.ttlMs))
    if (draft.briefing != null) put("briefing", draft.briefing)
    putJsonArray("envelopes") { envelopes.forEach { add(it) } }
}

/** 本地发起结果（UI 据此给反馈 / 认祖归宗兑时淡出草稿态）。 */
data class BundleProposal(
    val draft: BundleDraft,
    /** 已落 PENDING_SEND（§11.1：先落盘，后反馈）。 */
    val entry: OutboxEntry,
    val keyVer: Int,
    val envelopeCount: Int,
)

/** 本地发起 bundle 的编排（纯 Orchestrator：无 UI、无端钟，时钟/随机数全注入）。 */
class BundleProposalService(
    private val keys: BundleKeySource,
    private val outbox: OutboxRepository,
    /** 被邀成员的 X25519 广播公钥来源（真机＝peers_api.fetchPeerKeys）。 */
    private val memberSpk: suspend (String) -> ByteArray,
    private val newBundleKey: () -> ByteArray = { generateBundleKey() },
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    suspend fun propose(meId: String, draft: BundleDraft): BundleProposal {
        validateDraft(draft)

        val keyVer = 1
        val bundleKey = newBundleKey()
        if (bundleKey.size != 32) {
            throw IllegalStateException("K_bundle 必须是 32B（AES-256-GCM）")
        }
        // 先落户密钥，后入箱——弹出时按 (bundleId, keyVer) 取钥加密，顺序颠倒会让
        // drain 命中 Offline 保序重试（取钥失败＝传输层异常归一化为 Offline）。
        keys.persist(draft.localId, keyVer, bundleKey)

        val envelopes = buildList {
            for (uid in draft.inviteeUids) {
                add(wrapBundleKeyForMember(bundleKey, keyVer, uid, memberSpk(uid)))
            }
        }

        val now = nowMs()
        val aliveTtl = proposalAliveTtlMs(nowMs = now, steps = draft.steps)
        val payload = proposalPayloadOf(
            initiatorId = meId,
            draft = draft,
            nowMs = now,
            envelopes = envelopes,
            // 显式 ttl（draft.ttlMs）是发起人下限覆盖：实际存活取 max(显式, 目标日推算)
            aliveTtlMs = if (aliveTtl > draft.ttlMs) aliveTtl else draft.ttlMs,
        )
        val entry = outbox.enqueue(
            bundleId = draft.localId,
            actionType = "PROPOSE",
            payload = payload,
            keyVer = keyVer,
        )
        return BundleProposal(
            draft = draft,
            entry = entry,
            keyVer = keyVer,
            envelopeCount = envelopes.size,
        )
    }
}

/**
 * 认祖归宗：临时本地 id → 服务端发号。
 *
 * 挂在 [OutboxDrainer] 的 onBundleCreated 上，三件事在同一区间内完成：
 * 1. 密钥信封迁移（collab_key_ring 换挂服务端 bundle_id，临时条目删除）；
 * 2. 发件箱改绑（窗口期内入队的后续操作跟着改，否则恒 404）；
 * 3. 落包头（[CollabBundleRepository.ensureBundle]，供水位续拉与列表展示）。
 *
 * **幂等**：服务端重放同一 client_msg_id 时会再次回到这里——三处写入分别是
 * ignore / update 零行 / ignore，重复执行零副作用。
 */
class PendingProposalReconciler(
    private val bundles: CollabBundleRepository,
    private val keys: BundleKeySource,
    private val outbox: OutboxRepository,
) {
    suspend operator fun invoke(entry: OutboxEntry, ack: CreatedBundle) {
        val p = entry.payload
        val keyVer = entry.keyVer
        val key = keys(ack.localBundleId, keyVer)
        if (key != null) {
            keys.persist(ack.bundleId, keyVer, key)
            keys.discard(ack.localBundleId, keyVer)
        }
        outbox.rebindBundle(ack.localBundleId, ack.bundleId)

        val rawParticipants = p["participants"]
        val participants = if (rawParticipants == null || rawParticipants is JsonNull) {
            emptyList<String>()
        } else {
            (rawParticipants as JsonArray).map { it.jsonPrimitive.content }
        }
        bundles.ensureBundle(
            bundleId = ack.bundleId,
            proposalType = jsonStrOrNull(p["proposal_type"]) ?: "CONSENSUS",
            initiatorId = jsonStrOrNull(p["initiator_id"]) ?: entry.bundleId,
            participants = participants,
            expiresAtMs = jsonLongOrNull(p["expires_at_ms"]) ?: 0L,
            keyVersion = keyVer,
        )
    }
}
