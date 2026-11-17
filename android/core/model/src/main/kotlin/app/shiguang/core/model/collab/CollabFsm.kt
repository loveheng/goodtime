package app.shiguang.core.model.collab

/**
 * 端侧本地 FSM 回放引擎（Kotlin 平移，P2 优先序第一块）——协同设计 §4.2–§4.5。
 *
 * **纯函数 Reducer**（§11.1 分层单向数据流 ③）：(state, event) → state'，
 * 零 I/O、零时钟读取（now/serverAt 一律由事件携带注入）、零随机——离线可测、重放幂等。
 *
 * 四条铁律（§9.1 翻译纪律）：
 * 1. **骨架优先于内容**：密文解不开骨架仍推进（回放引擎只吃明文骨架信号）；
 * 2. **派生态**：Bundle 状态由步骤聚合推导（任一 clash → STALLED；全必需 READY →
 *    CONFIRMED），终态显式覆盖派生；
 * 3. **端侧断言兜底**：越权/非法转移事件**静默丢弃**（状态不变），绝不抛；
 * 4. **法定时钟 = 服务端落盘时间**：TTL/熔断判据一律用事件 serverAtMs。
 *
 * 行为规格 SSOT = fsm_transition_vectors.json rules 节七条，对拍 = 同目录 54 case。
 */
object CollabFsm {

    // ---- 常量（§4.4/§4.5 评审定盘，双端单一出处） -------------------------------

    /** 单个 Rendezvous 候选硬上限（满额 ADD_SLOT 静默丢弃）。 */
    const val MAX_CANDIDATE_SLOTS = 8

    /** 发起人失联死锁熔断 TTL（STALLED 持续超时 → 本地 ABANDONED_LOCALLY）。 */
    const val STALLED_DEADLOCK_TTL_MS: Long = 48L * 3600 * 1000

    // ---- 枚举（语义名与向量集 enum_names 逐字一致） ------------------------------

    enum class ProposalType { DIRECTIVE, CONSENSUS }

    enum class BundleStatus {
        DRAFT, PROPOSED, STALLED, CONFIRMED,
        CANCELLED, EXPIRED, ARCHIVED, ABANDONED_LOCALLY,
    }

    enum class StepStatus { PENDING, READY, TIME_CLASH, ROLE_CLASH, OPTIONAL, PRUNED, RECHECK }

    enum class VoteState { PENDING, ACCEPTED, PENDING_RECHECK, REJECTED }

    enum class RejectReason { TIME_CONFLICT, ROLE_UNWILLING }

    enum class Preference { PREFER, OK, NO }

    /** action = proto 枚举名（30 值逐字，ActionType 契约）。 */
    enum class Action {
        PROPOSE, ACCEPT, REJECT, PATCH_TIME, REASSIGN, MAKE_OPTIONAL, PRUNE,
        CANCEL, REOPEN, VOTE, ADD_SLOT, FORCE_FINALIZE, NUDGE, BYPASS, REKEY,
        MEMBER_REMOVED, TRANSFER_INITIATOR, BRIEFING_UPDATE, STEP_NOTE,
        ATTACH_FACT, UPDATE_FACT, DETACH_FACT, FREEMASK_QUERY, FREEMASK_RESPONSE,
        DELIVERY_ACK, CLIENT_ACK, PING, ADD_MEMBER, PATCH_STEP_TIME, LEAVE,
    }

    // ---- 数据模型（immutable；全部变更经 derived 收口重算派生） -------------------

    data class StepSpec(
        val stepId: String,
        val requiredUids: List<String>,
        val threshold: Int = 0, // ≤0 = 建态时回落名单长度
        val isOptional: Boolean = false,
    )

    data class Signal(
        val action: Action,
        val sender: String,
        val seq: Long,
        val serverAtMs: Long = 0,
        val stepId: String? = null,
        val slotId: String? = null,
        val rejectReason: RejectReason? = null,
        val preference: Preference? = null,
        val uids: List<String> = emptyList(),
        val skeleton: List<StepSpec> = emptyList(),
        val initialSlots: List<String> = emptyList(),
        val quorum: Int? = null,
        val expiresAtMs: Long? = null,
        val briefing: String? = null,
    )

    data class Ballot(val state: VoteState, val reason: RejectReason? = null)

    data class StepState(
        val stepId: String,
        val requiredUids: List<String>,
        val threshold: Int,
        val isOptional: Boolean,
        val status: StepStatus,
        val revision: Long,
        val ballots: Map<String, Ballot>,
    ) {
        /** 赞成票数（仅 required 成员 ACCEPTED 计入；PENDING_RECHECK 不算）。 */
        val acceptCount: Int
            get() = ballots.count { (uid, b) ->
                uid in requiredUids && b.state == VoteState.ACCEPTED
            }

        companion object {
            /**
             * 步骤派生（§4.2 Step FSM）：OPTIONAL/PRUNED 显式终态短路保留；任一 required
             * REJECTED 即时 clash（理由分流 TIME/ROLE）；RECHECK 态门槛判定；其余 PENDING/READY。
             */
            fun derive(s: StepState): StepStatus {
                if (s.status == StepStatus.OPTIONAL || s.status == StepStatus.PRUNED) {
                    return s.status
                }
                for (uid in s.requiredUids) {
                    val b = s.ballots[uid]
                    if (b?.state == VoteState.REJECTED) {
                        return if (b.reason == RejectReason.ROLE_UNWILLING) {
                            StepStatus.ROLE_CLASH
                        } else {
                            StepStatus.TIME_CLASH
                        }
                    }
                }
                val need = if (s.threshold <= 0) s.requiredUids.size else s.threshold
                return if (s.status == StepStatus.RECHECK) {
                    if (s.acceptCount >= need) StepStatus.READY else StepStatus.RECHECK
                } else {
                    if (s.acceptCount >= need) StepStatus.READY else StepStatus.PENDING
                }
            }

            /** 变更 + 派生收口（全库步骤变更统一走此出口；status 由 derive 重算）。 */
            fun derived(
                self: StepState,
                requiredUids: List<String> = self.requiredUids,
                threshold: Int = self.threshold,
                isOptional: Boolean = self.isOptional,
                status: StepStatus = self.status,
                revision: Long = self.revision,
                ballots: Map<String, Ballot> = self.ballots,
                mutate: (StepState) -> StepState = { it },
            ): StepState {
                val candidate = mutate(
                    self.copy(
                        requiredUids = requiredUids,
                        threshold = threshold,
                        isOptional = isOptional,
                        status = status,
                        revision = revision,
                        ballots = ballots,
                    ),
                )
                return candidate.copy(status = derive(candidate))
            }
        }
    }

    data class BundleState(
        val bundleId: String,
        val proposalType: ProposalType,
        val status: BundleStatus,
        val baseRevision: Long,
        val maxSeqId: Long,
        val initiatorId: String?,
        val participants: Set<String>,
        val keyVersion: Int,
        val steps: Map<String, StepState>,
        val candidateSlots: List<String> = emptyList(),
        val votes: Map<String, Map<String, Preference>> = emptyMap(),
        val winningSlot: String? = null,
        val quorum: Int? = null,
        val expiresAtMs: Long? = null,
        val stalledSinceMs: Long? = null,
        val briefing: String? = null,
    ) {
        /** 强一致步骤（聚合派生只看它们：OPTIONAL/PRUNED 移出名单）。 */
        val bindingSteps: List<StepState>
            get() = steps.values.filter { !it.isOptional && it.status != StepStatus.PRUNED }

        fun copyWith(
            status: BundleStatus = this.status,
            baseRevision: Long = this.baseRevision,
            maxSeqId: Long = this.maxSeqId,
            initiatorId: String? = this.initiatorId,
            participants: Set<String> = this.participants,
            keyVersion: Int = this.keyVersion,
            steps: Map<String, StepState> = this.steps,
            candidateSlots: List<String> = this.candidateSlots,
            votes: Map<String, Map<String, Preference>> = this.votes,
            winningSlot: String? = this.winningSlot,
            quorum: Int? = this.quorum,
            expiresAtMs: Long? = this.expiresAtMs,
            stalledSinceMs: Long? = this.stalledSinceMs,
            briefing: String? = this.briefing,
        ) = BundleState(
            bundleId, proposalType, status, baseRevision, maxSeqId, initiatorId,
            participants, keyVersion, steps, candidateSlots, votes, winningSlot,
            quorum, expiresAtMs, stalledSinceMs, briefing,
        )

        companion object {
            /** 空态（PROPOSE 事件前的初态）：DRAFT / maxSeqId=0 / baseRevision=1 / keyVersion=1。 */
            fun empty(bundleId: String, proposalType: ProposalType) = BundleState(
                bundleId = bundleId,
                proposalType = proposalType,
                status = BundleStatus.DRAFT,
                baseRevision = 1,
                maxSeqId = 0,
                initiatorId = null,
                participants = emptySet(),
                keyVersion = 1,
                steps = emptyMap(),
            )
        }
    }

    // ---- 回放 -------------------------------------------------------------------

    /** 单事件回放：非法/越权/重复 seq 静默丢弃（状态不变），水位推进无条件（bumpSeq）。 */
    fun reduceEvent(state: BundleState, signal: Signal): BundleState {
        if (signal.seq <= state.maxSeqId && state.status != BundleStatus.DRAFT) {
            return state
        }
        val next = when (state.proposalType) {
            ProposalType.DIRECTIVE -> reduceDirective(state, signal)
            ProposalType.CONSENSUS -> reduceConsensus(state, signal)
        }
        // 备忘回显（Day-2 F6）：BRIEFING_UPDATE 折叠进状态——与动作类型无关，双提案形态同语义
        val folded = if (signal.action == Action.BRIEFING_UPDATE) {
            next.copyWith(briefing = signal.briefing)
        } else {
            next
        }
        return bumpSeq(folded, signal.seq)
    }

    private fun bumpSeq(s: BundleState, seq: Long): BundleState =
        s.copyWith(maxSeqId = if (seq > s.maxSeqId) seq else s.maxSeqId)

    private fun isInitiator(s: BundleState, e: Signal): Boolean =
        s.initiatorId != null && s.initiatorId == e.sender

    /** ABANDONED_LOCALLY 再激活面：发起人结构性信令到达即恢复（§4.5 本地自保可逆）。 */
    private fun reactivateIfAbandoned(s: BundleState): BundleState =
        if (s.status == BundleStatus.ABANDONED_LOCALLY) {
            s.copyWith(status = BundleStatus.STALLED)
        } else {
            s
        }

    /** 包派生 + stalledSince 维护（进入 STALLED 时刻 = 触发事件 serverAt）。 */
    private fun derive(s: BundleState, serverAtMs: Long): BundleState {
        if (s.status == BundleStatus.CANCELLED || s.status == BundleStatus.EXPIRED ||
            s.status == BundleStatus.ARCHIVED || s.status == BundleStatus.ABANDONED_LOCALLY ||
            s.status == BundleStatus.DRAFT || s.steps.isEmpty()
        ) {
            return s
        }
        val binding = s.bindingSteps
        val derived = when {
            binding.any { isClash(it.status) } -> BundleStatus.STALLED
            binding.isNotEmpty() && binding.all {
                it.status == StepStatus.READY || it.status == StepStatus.RECHECK
            } -> BundleStatus.CONFIRMED
            else -> BundleStatus.PROPOSED
        }
        val stalledSince = if (derived == BundleStatus.STALLED) {
            if (s.status == BundleStatus.STALLED) s.stalledSinceMs else serverAtMs
        } else {
            null
        }
        return s.copyWith(status = derived, stalledSinceMs = stalledSince)
    }

    private fun isClash(st: StepStatus): Boolean =
        st == StepStatus.TIME_CLASH || st == StepStatus.ROLE_CLASH

    private fun reduceDirective(s: BundleState, e: Signal): BundleState = when (e.action) {
        Action.PROPOSE -> {
            if (s.status != BundleStatus.DRAFT || e.skeleton.isEmpty()) {
                s
            } else {
                val steps = e.skeleton.associate { spec ->
                    val need = if (spec.threshold <= 0) spec.requiredUids.size else spec.threshold
                    spec.stepId to StepState(
                        stepId = spec.stepId,
                        requiredUids = spec.requiredUids.toList(),
                        threshold = need,
                        isOptional = spec.isOptional,
                        status = StepStatus.PENDING,
                        revision = 1,
                        ballots = spec.requiredUids.associateWith { Ballot(VoteState.PENDING) },
                    )
                }
                BundleState(
                    bundleId = s.bundleId,
                    proposalType = ProposalType.DIRECTIVE,
                    status = BundleStatus.PROPOSED,
                    baseRevision = 1,
                    maxSeqId = e.seq,
                    initiatorId = e.sender,
                    participants = setOf(e.sender) + e.uids,
                    keyVersion = 1,
                    steps = steps,
                    expiresAtMs = e.expiresAtMs,
                    briefing = e.briefing,
                )
            }
        }

        Action.ACCEPT, Action.REJECT -> {
            val step = e.stepId?.let { s.steps[it] } ?: return s
            if (e.sender !in step.requiredUids) return s
            val ballot = if (e.action == Action.ACCEPT) {
                Ballot(VoteState.ACCEPTED)
            } else {
                Ballot(VoteState.REJECTED, e.rejectReason ?: RejectReason.TIME_CONFLICT)
            }
            val updated = StepState.derived(step) {
                it.copy(ballots = it.ballots + (e.sender to ballot))
            }
            derive(s.copyWith(steps = s.steps + (step.stepId to updated)), e.serverAtMs)
        }

        Action.PATCH_TIME -> {
            if (!isInitiator(s, e) || e.stepId == null) return s
            if (s.status !in setOf(
                    BundleStatus.STALLED, BundleStatus.PROPOSED, BundleStatus.ABANDONED_LOCALLY,
                )
            ) {
                return s
            }
            val step = s.steps[e.stepId] ?: return s
            // 回音壁（§4.3）：已同意成员联动置 PENDING_RECHECK，拒绝者回 PENDING。
            val ballots = step.ballots.mapValues { (_, b) ->
                when (b.state) {
                    VoteState.ACCEPTED -> Ballot(VoteState.PENDING_RECHECK)
                    VoteState.REJECTED -> Ballot(VoteState.PENDING)
                    else -> b
                }
            }
            val updated = StepState.derived(step) {
                it.copy(ballots = ballots, revision = it.revision + 1, status = StepStatus.PENDING)
            }
            val base = reactivateIfAbandoned(s)
            derive(
                base.copyWith(
                    steps = base.steps + (step.stepId to updated),
                    baseRevision = base.baseRevision + 1,
                ),
                e.serverAtMs,
            )
        }

        Action.PATCH_STEP_TIME -> {
            // 敲定后微调轻通道（§4.3）：CONFIRMED 包内发起人改单步骤时间；包保持 CONFIRMED。
            if (!isInitiator(s, e) || e.stepId == null) return s
            if (s.status != BundleStatus.CONFIRMED) return s
            val step = s.steps[e.stepId] ?: return s
            if (step.isOptional || step.status == StepStatus.PRUNED) return s
            val ballots = step.ballots.mapValues { (_, b) ->
                if (b.state == VoteState.ACCEPTED) Ballot(VoteState.PENDING_RECHECK) else b
            }
            val updated = StepState.derived(step) {
                it.copy(ballots = ballots, revision = it.revision + 1, status = StepStatus.RECHECK)
            }
            derive(s.copyWith(steps = s.steps + (step.stepId to updated)), e.serverAtMs)
        }

        Action.REASSIGN -> {
            if (!isInitiator(s, e) || e.stepId == null || e.uids.isEmpty()) return s
            val step = s.steps[e.stepId] ?: return s
            val rejected = step.requiredUids.filter {
                step.ballots[it]?.state == VoteState.REJECTED
            }.toSet()
            if (rejected.isEmpty()) return s // 无拒绝者可替换
            val newUids = step.requiredUids.filter { it !in rejected } + e.uids
            val updated = StepState.derived(step) {
                it.copy(
                    requiredUids = newUids,
                    ballots = newUids.associateWith { Ballot(VoteState.PENDING) },
                    revision = it.revision + 1,
                    status = StepStatus.PENDING,
                )
            }
            val base = reactivateIfAbandoned(s)
            derive(
                base.copyWith(
                    steps = base.steps + (step.stepId to updated),
                    baseRevision = base.baseRevision + 1,
                ),
                e.serverAtMs,
            )
        }

        Action.MAKE_OPTIONAL -> {
            if (!isInitiator(s, e) || e.stepId == null) return s
            val step = s.steps[e.stepId] ?: return s
            if (step.status == StepStatus.PRUNED) return s
            val updated = StepState.derived(step) {
                it.copy(isOptional = true, status = StepStatus.OPTIONAL)
            }
            val base = reactivateIfAbandoned(s)
            derive(
                base.copyWith(
                    steps = base.steps + (step.stepId to updated),
                    baseRevision = base.baseRevision + 1,
                ),
                e.serverAtMs,
            )
        }

        Action.BYPASS -> {
            // §4.5 一键降级表决：移出未响应成员并清其表决，门限收缩 min(原门限, 剩余)。
            if (!isInitiator(s, e) || e.stepId == null || e.uids.isEmpty()) return s
            val step = s.steps[e.stepId] ?: return s
            val removed = e.uids.toSet()
            val kept = step.requiredUids.filter { it !in removed }
            if (kept.size == step.requiredUids.size) return s
            val ballots = step.ballots.filterKeys { it !in removed }
            val updated = StepState.derived(step) {
                it.copy(
                    requiredUids = kept,
                    threshold = if (kept.size < step.threshold) kept.size else step.threshold,
                    ballots = ballots,
                    revision = it.revision + 1,
                )
            }
            val base = reactivateIfAbandoned(s)
            derive(
                base.copyWith(
                    steps = base.steps + (step.stepId to updated),
                    baseRevision = base.baseRevision + 1,
                ),
                e.serverAtMs,
            )
        }

        Action.PRUNE -> {
            if (!isInitiator(s, e) || e.stepId == null) return s
            val step = s.steps[e.stepId] ?: return s
            val updated = StepState.derived(step) { it.copy(status = StepStatus.PRUNED) }
            val base = reactivateIfAbandoned(s)
            derive(
                base.copyWith(
                    steps = base.steps + (step.stepId to updated),
                    baseRevision = base.baseRevision + 1,
                ),
                e.serverAtMs,
            )
        }

        Action.REOPEN -> {
            // Directive：发起人紧急退回（§4.3 CONFIRMED→STALLED 显式落——纯派生会得
            // PROPOSED 与表格不符）；缺省 = 全部强一致步骤回 PENDING（绝不漏退）。
            if (!isInitiator(s, e) || s.status != BundleStatus.CONFIRMED) return s
            var steps = s.steps
            val targets = if (e.stepId != null) {
                listOfNotNull(e.stepId.takeIf { steps.containsKey(it) })
            } else {
                steps.values.filter {
                    !it.isOptional && it.status != StepStatus.PRUNED
                }.map { it.stepId }
            }
            if (targets.isEmpty()) return s
            for (tid in targets) {
                val st = steps[tid]!!
                steps = steps + (tid to StepState.derived(st) {
                    it.copy(
                        ballots = it.requiredUids.associateWith { b -> Ballot(VoteState.PENDING) },
                        revision = it.revision + 1,
                        status = StepStatus.PENDING,
                    )
                })
            }
            derive(
                s.copyWith(steps = steps, baseRevision = s.baseRevision + 1),
                e.serverAtMs,
            ).copyWith(status = BundleStatus.STALLED, stalledSinceMs = e.serverAtMs)
        }

        Action.CANCEL -> {
            if (!isInitiator(s, e)) s else s.copyWith(status = BundleStatus.CANCELLED)
        }

        Action.REKEY -> s.copyWith(keyVersion = s.keyVersion + 1)

        Action.ADD_MEMBER -> {
            if (!isInitiator(s, e) || e.uids.isEmpty()) {
                s
            } else {
                s.copyWith(participants = s.participants + e.uids)
            }
        }

        Action.LEAVE -> {
            // 成员自主退出（§4.5）：解挂自己的必需步骤（名单剔除+门限收缩 min(M,剩余)）、
            // 空名单转 OPTIONAL；发起人不可 LEAVE；participants 剔除防退出后继续投票。
            if (isInitiator(s, e) || e.sender !in s.participants) return s
            val allowed = setOf(
                BundleStatus.PROPOSED, BundleStatus.STALLED,
                BundleStatus.CONFIRMED, BundleStatus.ABANDONED_LOCALLY,
            )
            if (s.status !in allowed) return s
            var steps = s.steps
            for (st in s.steps.values) {
                if (e.sender !in st.requiredUids) continue
                val kept = st.requiredUids.filter { it != e.sender }
                val ballots = st.ballots.filterKeys { it != e.sender }
                steps = steps + (st.stepId to if (kept.isEmpty()) {
                    StepState.derived(st) {
                        it.copy(
                            requiredUids = kept,
                            ballots = ballots,
                            isOptional = true,
                            status = StepStatus.OPTIONAL,
                        )
                    }
                } else {
                    StepState.derived(st) {
                        it.copy(
                            requiredUids = kept,
                            threshold = if (kept.size < st.threshold) kept.size else st.threshold,
                            ballots = ballots,
                            revision = it.revision + 1,
                        )
                    }
                })
            }
            derive(
                s.copyWith(steps = steps, participants = s.participants - e.sender),
                e.serverAtMs,
            )
        }

        // 骨架中性事件族 + Consensus 专属动作在 Directive 下：只推水位（静默丢弃）。
        else -> s
    }

    private fun reduceConsensus(s: BundleState, e: Signal): BundleState = when (e.action) {
        Action.PROPOSE -> {
            if (s.status != BundleStatus.DRAFT || e.initialSlots.isEmpty()) {
                s
            } else {
                BundleState(
                    bundleId = s.bundleId,
                    proposalType = ProposalType.CONSENSUS,
                    status = BundleStatus.PROPOSED,
                    baseRevision = 1,
                    maxSeqId = e.seq,
                    initiatorId = e.sender, // 记账用；Consensus 全程平权，无发起人特权
                    participants = setOf(e.sender) + e.uids,
                    steps = emptyMap(),
                    keyVersion = 1,
                    candidateSlots = e.initialSlots.toList(),
                    quorum = e.quorum ?: 1,
                    expiresAtMs = e.expiresAtMs,
                    briefing = e.briefing,
                )
            }
        }

        Action.VOTE -> {
            if (s.status != BundleStatus.PROPOSED || e.sender !in s.participants ||
                e.slotId == null || e.slotId !in s.candidateSlots
            ) {
                return s
            }
            val slotVotes = s.votes[e.slotId].orEmpty() +
                (e.sender to (e.preference ?: Preference.OK))
            val votes = s.votes + (e.slotId to slotVotes)
            // 达成规则：首个集齐门限赞成票（PREFER+OK，NO 不计）的时段自动固化。
            val support = slotVotes.values.count { it != Preference.NO }
            if (support >= (s.quorum ?: 1)) {
                s.copyWith(
                    votes = votes,
                    status = BundleStatus.CONFIRMED,
                    winningSlot = e.slotId,
                )
            } else {
                s.copyWith(votes = votes)
            }
        }

        Action.ADD_SLOT -> {
            if (s.status != BundleStatus.PROPOSED || e.sender !in s.participants ||
                e.slotId == null
            ) {
                return s
            }
            // Day-2 去重（2026-11-14 拍板⑥挂账）：重复 slotId 静默跳过——收敛数据面
            // 重复候选（votes 按 slotId 索引本无危害，去重防票数被同 id 候选摊薄）。
            if (e.slotId in s.candidateSlots) return s
            if (s.candidateSlots.size >= MAX_CANDIDATE_SLOTS) return s // 满额丢弃
            s.copyWith(candidateSlots = s.candidateSlots + e.slotId)
        }

        Action.FORCE_FINALIZE -> {
            // 分票死锁落槌（§4.4）：发起人独占（并发竞态由服务端行级门禁先到先得）。
            if (!isInitiator(s, e) || s.status != BundleStatus.PROPOSED ||
                e.slotId == null || e.slotId !in s.candidateSlots
            ) {
                return s
            }
            s.copyWith(status = BundleStatus.CONFIRMED, winningSlot = e.slotId)
        }

        Action.PATCH_STEP_TIME -> {
            // Consensus 敲定后改期（§4.3）：发起人退回重议——候选保留、旧票清空、全员重投；
            // slotId 在场须 = winning_slot。
            if (!isInitiator(s, e) || s.status != BundleStatus.CONFIRMED) return s
            if (e.slotId != null && e.slotId != s.winningSlot) return s
            s.copyWith(status = BundleStatus.PROPOSED, votes = emptyMap(), winningSlot = null)
        }

        Action.LEAVE -> {
            // 成员退出：撤自己在各候选的三值票（赞成票计数随之下降），不触发 REKEY。
            if (isInitiator(s, e) || e.sender !in s.participants) return s
            if (s.status != BundleStatus.PROPOSED && s.status != BundleStatus.CONFIRMED) return s
            s.copyWith(
                participants = s.participants - e.sender,
                votes = s.votes.mapValues { (_, slot) -> slot.filterKeys { it != e.sender } },
            )
        }

        Action.REOPEN -> {
            // 平权重开（§4.4）：任意参与者，旧票全作废、候选保留（含原胜出）。
            if (s.status != BundleStatus.CONFIRMED || e.sender !in s.participants) return s
            s.copyWith(status = BundleStatus.PROPOSED, votes = emptyMap(), winningSlot = null)
        }

        Action.CANCEL -> {
            if (!isInitiator(s, e)) s else s.copyWith(status = BundleStatus.CANCELLED)
        }

        Action.REKEY -> s.copyWith(keyVersion = s.keyVersion + 1)

        // 其余（Directive 专属/骨架中性/ACK/PING）在 Consensus 下：只推水位。
        else -> s
    }

    // ---- 本地伪事件（非网络事件，时钟注入式纯函数） -----------------------------

    /** 提案流局（§4.4）：仅 CONSENSUS+PROPOSED 且 now ≥ expiresAtMs → EXPIRED。 */
    fun maybeExpire(s: BundleState, nowMs: Long): BundleState {
        val ttl = s.expiresAtMs ?: return s
        return if (s.status == BundleStatus.PROPOSED &&
            s.proposalType == ProposalType.CONSENSUS && nowMs >= ttl
        ) {
            s.copyWith(status = BundleStatus.EXPIRED)
        } else {
            s
        }
    }

    /** 发起人失联熔断（§4.5）：STALLED 且 now − stalledSince ≥ 48h → ABANDONED_LOCALLY。 */
    fun maybeAbandon(s: BundleState, nowMs: Long): BundleState {
        val since = s.stalledSinceMs
        return if (s.status == BundleStatus.STALLED && since != null &&
            nowMs - since >= STALLED_DEADLOCK_TTL_MS
        ) {
            s.copyWith(status = BundleStatus.ABANDONED_LOCALLY)
        } else {
            s
        }
    }

    /** 事件序列回放（调用方保证按 seq 升序——乱序由消费管道归序后进入）。 */
    fun replay(initial: BundleState, events: List<Signal>): BundleState {
        var s = initial
        for (e in events) s = reduceEvent(s, e)
        return s
    }
}
