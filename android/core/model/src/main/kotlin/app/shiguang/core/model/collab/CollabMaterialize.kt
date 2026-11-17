package app.shiguang.core.model.collab

import app.shiguang.core.model.collab.CollabFsm.BundleState
import app.shiguang.core.model.collab.CollabFsm.StepStatus
import app.shiguang.core.model.collab.CollabFsm.VoteState

/**
 * FSM 派生态 → collab_steps 物化回填（lib/collab/materialize.dart Kotlin 等价，
 * Phase 4.3，解开 B 页时间槽钉子）。
 *
 * ## 为什么必须有这一层
 *
 * collab_events 只保真存密文（engine「只落库不解密」），而 UI 要的是**可读派生
 * 态**：每步当前状态、谁同意/拒绝、真实时段。此前没有任何「FSM → 步骤物化」
 * 回填路径，collab_steps 仅由 [CollabBundleRepository.ensureStep] 写入占位默认
 * 值——且该函数是 INSERT OR IGNORE，主键冲突即留旧值，于是**默认 PENDING 一旦
 * 落成就再也刷新不了**，成了 member_calendar_page 顶部那颗施工钉子的根因。
 *
 * 本层把 [replayBundle] 的派生态（含解密时顺手抓取的步骤时段）replace 进
 * collab_steps，使后续 B 页时间槽 / A 页画板直接读物化表，**不必再持有密钥**。
 */

/**
 * 把一个 bundle 的 FSM 派生态整批物化到本地 collab_steps。
 *
 * 返回写入行数（无步骤时为 0，且不会抹掉既有行）。同一 (bundle, step) 重复调用
 * 幂等：replace 覆盖，不累积。
 *
 * [bundleKey] 缺失密钥的事件会被半盲跳过（铁律 1）——对应步骤拿不到派生值时不写。
 */
suspend fun materializeBundle(
    repo: CollabBundleRepository,
    bundleId: String,
    bundleKey: suspend (bundleId: String, keyVer: Int) -> ByteArray?,
): Int {
    val events = repo.loadEvents(bundleId)
    val slots = LinkedHashMap<String, StepSlot>()
    val state = replayBundle(
        events,
        bundleKey,
        bundleId = bundleId,
        observedSlots = slots,
    )
    val rows = stepsToRows(state, slots, bundleId = bundleId)
    return repo.upsertSteps(bundleId, rows)
}

/** 纯转换（可单测）：FSM 步骤态 + 观察到的时段 → 物化行。 */
fun stepsToRows(
    state: BundleState,
    slots: Map<String, StepSlot>,
    bundleId: String,
): List<CollabStep> {
    // 缺省按 stepId 字典序，保证多次回填的行序稳定（否则 UI 排序会抖）
    val keys = state.steps.keys.sorted()
    return keys.mapIndexed { i, stepId ->
        val step = state.steps.getValue(stepId)
        val slot = slots[stepId]
        val votes = LinkedHashMap<String, String>()
        for ((uid, ballot) in step.ballots) {
            val token = voteToken(ballot.state)
            if (token != null) votes[uid] = token
        }
        CollabStep(
            bundleId = bundleId,
            id = stepId,
            requiredUids = step.requiredUids,
            threshold = step.threshold,
            isOptional = step.isOptional,
            status = statusOf(step.status),
            votes = votes,
            sort = i,
            startsAtMs = slot?.startsAtMs,
            endsAtMs = slot?.endsAtMs,
        )
    }
}

/** FSM 步骤状态 → `collab_steps.status` 枚举字符串（现有读侧口径，勿改大小写）。 */
private fun statusOf(s: StepStatus): String = when (s) {
    StepStatus.READY -> "READY"
    StepStatus.TIME_CLASH -> "TIME_CLASH"
    StepStatus.ROLE_CLASH -> "ROLE_CLASH"
    StepStatus.OPTIONAL -> "OPTIONAL"
    StepStatus.PRUNED -> "PRUNED"
    else -> "PENDING" // PENDING / RECHECK
}

/**
 * 表决态 → 物化票值 token（OK/NO）；未表态返回 null。
 *
 * 口径：accepted 记 'OK'、rejected 记 'NO'；**pending 与 pendingRecheck 都不记**
 * ——pendingRecheck 是「时段已改、旧同意作废待重确认」（FSM PATCH_TIME），
 * 语义上等同未表态，记成 OK 会让人误以为该成员认了新时段。
 */
private fun voteToken(s: VoteState): String? = when (s) {
    VoteState.ACCEPTED -> "OK"
    VoteState.REJECTED -> "NO"
    else -> null
}
