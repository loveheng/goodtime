/// 端侧本地 FSM 回放引擎（协同设计 §4.2–§4.5 / collab-implementation §3.2）。
///
/// **纯函数 Reducer**（§11.1 分层单向数据流 ③）：`(state, event) → state'`，
/// 零 I/O、零时钟读取（now/serverAt 一律由事件携带注入）、零随机——离线可测、
/// 重放幂等。消费 `collab_events`（3.1 已落表）按 seq 升序逐条回放，产出
/// `collab_bundles`/`collab_steps` 终态。
///
/// 四条铁律：
/// 1. **骨架优先于内容**（§4.2 半盲投影）：动作枚举与拓扑引用（step_id/slot_id/
///    required_uids/threshold）明文在场，密文解不开（payload 缺失）骨架仍推进——
///    回放引擎只吃明文骨架信号，绝不因解密失败卡死（§5.3 密钥环口径）；
/// 2. **派生态**（§4.2 末）：Bundle 状态由步骤聚合推导（任一 clash → STALLED；
///    全部必需 READY → CONFIRMED），终态（CANCELLED/EXPIRED/ARCHIVED/ABANDONED）
///    显式覆盖派生；
/// 3. **端侧断言兜底**（§4.5）：越权/非法转移事件**静默丢弃**（状态不变），绝不
///    throw——服务端已仲裁过一遍，本地防御第二道，坏事件不该炸回放循环；
/// 4. **法定时钟=服务端落盘时间**（§5.2）：TTL/熔断判据一律用事件的
///    `serverAtMs`（server_received_at），绝不读端钟。
library;

import 'proto/relay_protocol.pb.dart' show ActionType;

/// 方案类型（§4.4：Directive 母子联动审批链 / Consensus 平权时段竞争）。
enum CollabProposalType { directive, consensus }

/// Bundle 母状态（§4.2/§4.3/§4.4；存储层对齐 relay schema CHECK 集合——
/// Consensus 的 PROPOSING 落库即 'PROPOSED'，本地枚举不另立第二形态）。
enum BundleFsmStatus {
  draft,
  proposed,
  stalled,
  confirmed,
  cancelled,
  expired,
  archived,
  abandonedLocally, // §4.5 本地自保性退化（非全局终态，发起人信令可重新激活）
}

/// 步骤子状态（§4.2 Step FSM）。
enum StepFsmStatus { pending, ready, timeClash, roleClash, optional, pruned }

/// 步骤成员表决态（§4.3 PATCH 回音壁：accepted 成员联动降 pendingRecheck）。
enum MemberVoteState { pending, accepted, pendingRecheck, rejected }

/// 拒绝理由（§4.3 双维：REJECT(reason=TIME_CONFLICT/ROLE_UNWILLING)）。
enum RejectReason { timeConflict, roleUnwilling }

/// Consensus 三值偏好（§4.4：🌟优先/✓可以/✕不行；prefer/ok 计赞成票）。
enum ConsensusPreference { prefer, ok, no }

// ---- 常量（§4.4/§4.5 评审定盘，双端单一出处） -------------------------------

/// 单个 Rendezvous 候选硬上限（防看板撑爆；满额 ADD_SLOT 静默丢弃）。
const int kMaxCandidateSlots = 8;

/// 发起人失联死锁熔断 TTL（STALLED 持续超此时长 → 本地 ABANDONED_LOCALLY）。
const Duration kStalledDeadlockTtl = Duration(hours: 48);

/// Consensus Soft-Lock TTL（§3.6：24h 无交集 → EXPIRED；对齐 relay TTL 约定）。
const Duration kConsensusSoftLockTtl = Duration(hours: 24);

// ---- 输入信号 ---------------------------------------------------------------

/// 步骤骨架（PROPOSE 明文外层携带，§4.2 topology_skeleton.steps[]）。
class CollabStepSpec {
  final String stepId;
  final List<String> requiredUids;
  final int threshold; // M-of-N；≤0=建态时回落全员数
  final bool isOptional;

  const CollabStepSpec({
    required this.stepId,
    required this.requiredUids,
    this.threshold = 0,
    this.isOptional = false,
  });
}

/// 回放输入信号：明文骨架引用 + 动作枚举（密文内容不进 reducer——骨架推进
/// 与内容解密正交，铁律 1）。
class CollabSignal {
  final ActionType action;
  final String senderId;
  final int seqId;

  /// 法定时钟：服务端事务落盘时间 server_received_at（§5.2，TTL/熔断判据）。
  final int serverAtMs;

  /// 明文拓扑引用：步骤 / Consensus 候选时段。
  final String? stepId;
  final String? slotId;

  /// REJECT 理由（§4.3 双维）；Consensus VOTE 的 ✕不行 票同走此字段。
  final RejectReason? rejectReason;

  /// Consensus 三值偏好（缺省=ok 可）。
  final ConsensusPreference? preference;

  /// reassign/bypass/addMember 的新成员 uids；PROPOSE(directive) 的其余参与者。
  final List<String> uids;

  /// PROPOSE 携带：初始骨架（directive）/ 候选时段（consensus）/ 门限与 TTL。
  final List<CollabStepSpec> skeleton;
  final List<String> initialSlots;
  final int? quorum; // Consensus 达成规则票数
  final int? expiresAtMs;

  const CollabSignal({
    required this.action,
    required this.senderId,
    required this.seqId,
    this.serverAtMs = 0,
    this.stepId,
    this.slotId,
    this.rejectReason,
    this.preference,
    this.uids = const [],
    this.skeleton = const [],
    this.initialSlots = const [],
    this.quorum,
    this.expiresAtMs,
  });
}

// ---- 状态 -------------------------------------------------------------------

class MemberBallot {
  final MemberVoteState state;
  final RejectReason? reason; // rejected 时在位
  const MemberBallot(this.state, {this.reason});

  static const pending = MemberBallot(MemberVoteState.pending);
  static const accepted = MemberBallot(MemberVoteState.accepted);
  static const pendingRecheck = MemberBallot(MemberVoteState.pendingRecheck);
}

class CollabStepState {
  final String stepId;
  final List<String> requiredUids;
  final int threshold;
  final bool isOptional;
  final StepFsmStatus status;
  final int revision;
  final Map<String, MemberBallot> ballots; // uid → 表决

  const CollabStepState({
    required this.stepId,
    required this.requiredUids,
    required this.threshold,
    required this.isOptional,
    required this.status,
    required this.revision,
    required this.ballots,
  });

  CollabStepState copyWith({
    List<String>? requiredUids,
    int? threshold,
    bool? isOptional,
    StepFsmStatus? status,
    int? revision,
    Map<String, MemberBallot>? ballots,
  }) {
    final candidate = CollabStepState(
      stepId: stepId,
      requiredUids: requiredUids ?? this.requiredUids,
      threshold: threshold ?? this.threshold,
      isOptional: isOptional ?? this.isOptional,
      status: status ?? this.status,
      revision: revision ?? this.revision,
      ballots: ballots ?? this.ballots,
    );
    // 状态收口=派生（§4.2 末「由下层各步骤状态汇总驱动」）：表决/门限/名单
    // 变更自动重算子状态；显式终态（optional/pruned）经 derive 短路保留。
    return CollabStepState(
      stepId: candidate.stepId,
      requiredUids: candidate.requiredUids,
      threshold: candidate.threshold,
      isOptional: candidate.isOptional,
      status: CollabStepState.derive(candidate),
      revision: candidate.revision,
      ballots: candidate.ballots,
    );
  }

  /// 赞成票数（仅 required 成员、accepted 态计入；pendingRecheck 不算——
  /// 时段已变，旧同意作废待重确认）。
  int get acceptCount => ballots.entries
      .where((e) =>
          requiredUids.contains(e.key) &&
          e.value.state == MemberVoteState.accepted)
      .length;

  /// 派生子状态（§4.2 Step FSM；显式终态 optional/pruned 优先，拒绝即时 clash）。
  static StepFsmStatus derive(CollabStepState s) {
    if (s.status == StepFsmStatus.pruned || s.status == StepFsmStatus.optional) {
      return s.status;
    }
    for (final uid in s.requiredUids) {
      final b = s.ballots[uid];
      if (b?.state == MemberVoteState.rejected) {
        return b!.reason == RejectReason.roleUnwilling
            ? StepFsmStatus.roleClash
            : StepFsmStatus.timeClash;
      }
    }
    final need = s.threshold <= 0 ? s.requiredUids.length : s.threshold;
    return s.acceptCount >= need ? StepFsmStatus.ready : StepFsmStatus.pending;
  }
}

class CollabBundleState {
  final String bundleId;
  final CollabProposalType proposalType;
  final BundleFsmStatus status;
  final int baseRevision;
  final int maxSeqId; // 已回放到的最高 seq（乱序/重复防御水位）
  final String? initiatorId;
  final Set<String> participants;
  final Map<String, CollabStepState> steps;
  final int keyVersion;

  // Consensus 专用（§4.4）
  final List<String> candidateSlots;
  final Map<String, Map<String, ConsensusPreference>> votes; // slot → uid → 偏好
  final String? winningSlot;
  final int? quorum;
  final int? expiresAtMs;

  /// STALLED 进入时刻=触发事件的 serverAtMs（ABANDONED 48h 熔断判据，§5.2）。
  final int? stalledSinceMs;

  const CollabBundleState({
    required this.bundleId,
    required this.proposalType,
    required this.status,
    required this.baseRevision,
    required this.maxSeqId,
    required this.participants,
    required this.steps,
    required this.keyVersion,
    this.initiatorId,
    this.candidateSlots = const [],
    this.votes = const {},
    this.winningSlot,
    this.quorum,
    this.expiresAtMs,
    this.stalledSinceMs,
  });

  /// 空态（PROPOSE 事件前的初态）。[proposalType] 决定 PROPOSE 后加载哪套
  /// 策略状态机（§4.4：两种模式共用传输层，仅业务状态机不同）。
  factory CollabBundleState.empty(
    String bundleId, {
    CollabProposalType proposalType = CollabProposalType.directive,
  }) =>
      CollabBundleState(
        bundleId: bundleId,
        proposalType: proposalType,
        status: BundleFsmStatus.draft,
        baseRevision: 1,
        maxSeqId: 0,
        participants: const {},
        steps: const {},
        keyVersion: 1,
      );

  static const _keep = Object();

  CollabBundleState copyWith({
    String? initiatorId,
    BundleFsmStatus? status,
    int? baseRevision,
    int? maxSeqId,
    Set<String>? participants,
    Map<String, CollabStepState>? steps,
    int? keyVersion,
    List<String>? candidateSlots,
    Map<String, Map<String, ConsensusPreference>>? votes,
    Object? winningSlot = _keep,
    int? quorum,
    Object? expiresAtMs = _keep,
    Object? stalledSinceMs = _keep,
  }) =>
      CollabBundleState(
        bundleId: bundleId,
        proposalType: proposalType,
        status: status ?? this.status,
        baseRevision: baseRevision ?? this.baseRevision,
        maxSeqId: maxSeqId ?? this.maxSeqId,
        initiatorId: initiatorId ?? this.initiatorId,
        participants: participants ?? this.participants,
        steps: steps ?? this.steps,
        keyVersion: keyVersion ?? this.keyVersion,
        candidateSlots: candidateSlots ?? this.candidateSlots,
        votes: votes ?? this.votes,
        winningSlot:
            identical(winningSlot, _keep) ? this.winningSlot : winningSlot as String?,
        quorum: quorum ?? this.quorum,
        expiresAtMs:
            identical(expiresAtMs, _keep) ? this.expiresAtMs : expiresAtMs as int?,
        stalledSinceMs: identical(stalledSinceMs, _keep)
            ? this.stalledSinceMs
            : stalledSinceMs as int?,
      );

  /// 强一致步骤（聚合派生只看它们：optional/pruned 移出名单，§4.3）。
  Iterable<CollabStepState> get bindingSteps =>
      steps.values.where((s) => !s.isOptional && s.status != StepFsmStatus.pruned);
}

// ---- 回放 -------------------------------------------------------------------

/// 单事件回放：`(state, signal) → state'`。非法/越权/重复 seq 事件原样返回
/// （§4.5 端侧断言兜底：静默丢弃不炸回放循环）。
CollabBundleState reduceEvent(CollabBundleState state, CollabSignal signal) {
  // 重复/倒退 seq 直接忽略（/sync 补拉与 WSS 双路可能重叠投递）
  if (signal.seqId <= state.maxSeqId && state.status != BundleFsmStatus.draft) {
    return state;
  }
  final next = switch (state.proposalType) {
    CollabProposalType.directive => _reduceDirective(state, signal),
    CollabProposalType.consensus => _reduceConsensus(state, signal),
  };
  return _bumpSeq(next, signal.seqId);
}

CollabBundleState _bumpSeq(CollabBundleState s, int seq) =>
    s.copyWith(maxSeqId: seq > s.maxSeqId ? seq : s.maxSeqId);

bool _isInitiator(CollabBundleState s, CollabSignal e) =>
    s.initiatorId != null && s.initiatorId == e.senderId;

/// ABANDONED_LOCALLY 的再激活面：发起人结构性信令到达即恢复（§4.5——本地自保
/// 退化可逆，成员端重新收到即恢复预占）。
CollabBundleState _reactivateIfAbandoned(CollabBundleState s) =>
    s.status == BundleFsmStatus.abandonedLocally
        ? s.copyWith(status: BundleFsmStatus.stalled)
        : s;

/// 派生 Bundle 状态 + 维护 stalledSince（进入 STALLED 的时刻=触发事件 serverAt）。
CollabBundleState _derive(CollabBundleState s, int serverAtMs) {
  if (s.status == BundleFsmStatus.cancelled ||
      s.status == BundleFsmStatus.expired ||
      s.status == BundleFsmStatus.archived ||
      s.status == BundleFsmStatus.abandonedLocally ||
      s.status == BundleFsmStatus.draft ||
      s.steps.isEmpty) {
    return s;
  }
  final binding = s.bindingSteps.toList();
  final BundleFsmStatus derived;
  if (binding.any((st) => _isClash(st.status))) {
    derived = BundleFsmStatus.stalled;
  } else if (binding.isNotEmpty && binding.every((st) => st.status == StepFsmStatus.ready)) {
    derived = BundleFsmStatus.confirmed;
  } else {
    derived = BundleFsmStatus.proposed;
  }
  final stalledSince = derived == BundleFsmStatus.stalled
      ? (s.status == BundleFsmStatus.stalled ? s.stalledSinceMs : serverAtMs)
      : null;
  return s.copyWith(status: derived, stalledSinceMs: stalledSince);
}

bool _isClash(StepFsmStatus st) =>
    st == StepFsmStatus.timeClash || st == StepFsmStatus.roleClash;

CollabBundleState _reduceDirective(CollabBundleState s, CollabSignal e) {
  switch (e.action) {
    case ActionType.PROPOSE:
      if (s.status != BundleFsmStatus.draft || e.skeleton.isEmpty) return s;
      final steps = <String, CollabStepState>{};
      for (final spec in e.skeleton) {
        final need =
            spec.threshold <= 0 ? spec.requiredUids.length : spec.threshold;
        steps[spec.stepId] = CollabStepState(
          stepId: spec.stepId,
          requiredUids: List.of(spec.requiredUids),
          threshold: need,
          isOptional: spec.isOptional,
          status: StepFsmStatus.pending,
          revision: 1,
          ballots: {
            for (final uid in spec.requiredUids) uid: MemberBallot.pending,
          },
        );
      }
      return CollabBundleState(
        bundleId: s.bundleId,
        proposalType: CollabProposalType.directive,
        status: BundleFsmStatus.proposed,
        baseRevision: 1,
        maxSeqId: e.seqId,
        initiatorId: e.senderId,
        participants: {e.senderId, ...e.uids},
        steps: steps,
        keyVersion: 1,
        expiresAtMs: e.expiresAtMs,
      );

    case ActionType.ACCEPT:
    case ActionType.REJECT:
      final step = s.steps[e.stepId];
      if (step == null || !step.requiredUids.contains(e.senderId)) return s;
      final ballot = e.action == ActionType.ACCEPT
          ? MemberBallot.accepted
          : MemberBallot(MemberVoteState.rejected,
              reason: e.rejectReason ?? RejectReason.timeConflict);
      final updated = step.copyWith(ballots: {...step.ballots, e.senderId: ballot});
      return _derive(s.copyWith(steps: {...s.steps, step.stepId: updated}), e.serverAtMs);

    case ActionType.PATCH_TIME:
      if (!_isInitiator(s, e) || e.stepId == null) return s;
      if (s.status != BundleFsmStatus.stalled &&
          s.status != BundleFsmStatus.proposed &&
          s.status != BundleFsmStatus.abandonedLocally) {
        return s;
      }
      final step = s.steps[e.stepId!];
      if (step == null) return s;
      // PATCH 二次冲突回音壁（§4.3）：已同意成员联动置 PENDING_RECHECK，
      // 拒绝者回 PENDING（等新时段重新表决），revision+1。
      final ballots = <String, MemberBallot>{
        for (final entry in step.ballots.entries)
          entry.key: switch (entry.value.state) {
            MemberVoteState.accepted => MemberBallot.pendingRecheck,
            MemberVoteState.rejected => MemberBallot.pending,
            _ => entry.value,
          },
      };
      final updated = step.copyWith(
        ballots: ballots,
        revision: step.revision + 1,
        status: StepFsmStatus.pending,
      );
      final base = _reactivateIfAbandoned(s);
      return _derive(
        base.copyWith(
          steps: {...base.steps, step.stepId: updated},
          baseRevision: base.baseRevision + 1,
        ),
        e.serverAtMs,
      );

    case ActionType.REASSIGN:
      if (!_isInitiator(s, e) || e.stepId == null || e.uids.isEmpty) return s;
      final step = s.steps[e.stepId!];
      if (step == null) return s;
      final rejected = step.requiredUids
          .where((u) => step.ballots[u]?.state == MemberVoteState.rejected)
          .toSet();
      if (rejected.isEmpty) return s; // 无拒绝者可替换
      final newUids = [
        ...step.requiredUids.where((u) => !rejected.contains(u)),
        ...e.uids,
      ];
      final updated = step.copyWith(
        requiredUids: newUids,
        ballots: {for (final uid in newUids) uid: MemberBallot.pending},
        revision: step.revision + 1,
        status: StepFsmStatus.pending,
      );
      final base = _reactivateIfAbandoned(s);
      return _derive(
        base.copyWith(
          steps: {...base.steps, step.stepId: updated},
          baseRevision: base.baseRevision + 1,
        ),
        e.serverAtMs,
      );

    case ActionType.MAKE_OPTIONAL:
      if (!_isInitiator(s, e) || e.stepId == null) return s;
      final step = s.steps[e.stepId!];
      if (step == null || step.status == StepFsmStatus.pruned) return s;
      final updated = step.copyWith(
        isOptional: true,
        status: StepFsmStatus.optional,
      );
      // 移出强一致名单后重新聚合——其余全过则 CONFIRMED（§4.3）
      final base = _reactivateIfAbandoned(s);
      return _derive(
        base.copyWith(
          steps: {...base.steps, step.stepId: updated},
          baseRevision: base.baseRevision + 1,
        ),
        e.serverAtMs,
      );

    case ActionType.BYPASS:
      // §4.5 一键降级表决（MAKE_OPTIONAL 单人化）：把未响应成员移出该步骤
      // 必答名单并清其表决，绝不触发 REKEY。发起人面板执行。
      if (!_isInitiator(s, e) || e.stepId == null || e.uids.isEmpty) return s;
      final step = s.steps[e.stepId!];
      if (step == null) return s;
      final removed = e.uids.toSet();
      final kept = step.requiredUids.where((u) => !removed.contains(u)).toList();
      if (kept.length == step.requiredUids.length) return s;
      final updated = step.copyWith(
        requiredUids: kept,
        // 门限收缩：min(原门限, 剩余人数)——全员门限(t=N)场景移 1 人后仍须可达，
        // 否则「一键降级」永不收敛（M<N 时保持 M 不变）
        threshold: kept.length < step.threshold ? kept.length : step.threshold,
        ballots: {
          for (final entry in step.ballots.entries)
            if (!removed.contains(entry.key)) entry.key: entry.value,
        },
        revision: step.revision + 1,
      );
      final base = _reactivateIfAbandoned(s);
      return _derive(
        base.copyWith(
          steps: {...base.steps, step.stepId: updated},
          baseRevision: base.baseRevision + 1,
        ),
        e.serverAtMs,
      );

    case ActionType.PRUNE:
      if (!_isInitiator(s, e) || e.stepId == null) return s;
      final step = s.steps[e.stepId!];
      if (step == null) return s;
      final updated = step.copyWith(status: StepFsmStatus.pruned);
      final base = _reactivateIfAbandoned(s);
      return _derive(
        base.copyWith(
          steps: {...base.steps, step.stepId: updated},
          baseRevision: base.baseRevision + 1,
        ),
        e.serverAtMs,
      );

    case ActionType.REOPEN:
      // Directive：发起人紧急退回（§4.3 CONFIRMED→STALLED）；Consensus：平权重开。
      if (s.proposalType == CollabProposalType.consensus) {
        return _reopenConsensus(s, e);
      }
      if (!_isInitiator(s, e) || s.status != BundleFsmStatus.confirmed) return s;
      var steps = s.steps;
      final targets = e.stepId != null
          ? [if (steps[e.stepId] != null) e.stepId!]
          // 缺省=全部必需步骤回 PENDING（保守口径：设计稿「受影响步骤」由
          // payload 决定；密文解不开时按全量重置，绝不漏退）
          : [
              for (final st in steps.values)
                if (!st.isOptional && st.status != StepFsmStatus.pruned) st.stepId,
            ];
      if (targets.isEmpty) return s; // 目标步骤不存在 → 非法 REOPEN 原样丢弃
      for (final tid in targets) {
        final st = steps[tid]!;
        steps = {
          ...steps,
          tid: st.copyWith(
            ballots: {for (final uid in st.requiredUids) uid: MemberBallot.pending},
            revision: st.revision + 1,
            status: StepFsmStatus.pending,
          ),
        };
      }
      // 已 READY 步骤不受影响（§4.3）。转移表逐格：REOPEN 显式落 STALLED
      // （步骤回 PENDING 无 clash，纯派生会得 PROPOSED，与 §4.3 表格不符）；
      // 后续事件由派生重新接管（clash→stalled / 全 ready→confirmed）。
      return _derive(
        s.copyWith(steps: steps, baseRevision: s.baseRevision + 1),
        e.serverAtMs,
      ).copyWith(status: BundleFsmStatus.stalled, stalledSinceMs: e.serverAtMs);

    case ActionType.CANCEL:
      if (!_isInitiator(s, e)) return s;
      return s.copyWith(status: BundleFsmStatus.cancelled);

    case ActionType.REKEY:
      return s.copyWith(keyVersion: s.keyVersion + 1);

    case ActionType.ADD_MEMBER:
      if (!_isInitiator(s, e) || e.uids.isEmpty) return s;
      return s.copyWith(participants: {...s.participants, ...e.uids});

    // 回音壁的成员再确认不走独立线上动作（冻结 proto 无 PENDING_RECHECK 枚举）：
    // 成员直接重发 ACCEPT，表决被覆盖为 accepted（上方 ACCEPT 分支已支持）。

    case ActionType.NUDGE:
    case ActionType.BRIEFING_UPDATE:
    case ActionType.STEP_NOTE:
    case ActionType.ATTACH_FACT:
    case ActionType.UPDATE_FACT:
    case ActionType.DETACH_FACT:
    case ActionType.FREEMASK_QUERY:
    case ActionType.FREEMASK_RESPONSE:
    case ActionType.TRANSFER_INITIATOR:
    case ActionType.MEMBER_REMOVED:
      return s; // 骨架中性事件：只推进 seq 水位

    default:
      return s;
  }
}

CollabBundleState _reopenConsensus(CollabBundleState s, CollabSignal e) {
  if (s.status != BundleFsmStatus.confirmed || !s.participants.contains(e.senderId)) {
    return s;
  }
  return s.copyWith(
    status: BundleFsmStatus.proposed,
    votes: const {},
    winningSlot: null, // 重新协商：旧票全作废，候选保留（含原胜出）
  );
}

CollabBundleState _reduceConsensus(CollabBundleState s, CollabSignal e) {
  switch (e.action) {
    case ActionType.PROPOSE:
      if (s.status != BundleFsmStatus.draft || e.initialSlots.isEmpty) return s;
      return CollabBundleState(
        bundleId: s.bundleId,
        proposalType: CollabProposalType.consensus,
        status: BundleFsmStatus.proposed,
        baseRevision: 1,
        maxSeqId: e.seqId,
        initiatorId: e.senderId, // 记账用；Consensus 全程平权，无发起人特权
        participants: {e.senderId, ...e.uids},
        steps: const {},
        keyVersion: 1,
        candidateSlots: List.of(e.initialSlots),
        quorum: e.quorum ?? 1,
        expiresAtMs: e.expiresAtMs,
      );

    case ActionType.VOTE:
      if (s.status != BundleFsmStatus.proposed ||
          !s.participants.contains(e.senderId) ||
          e.slotId == null ||
          !s.candidateSlots.contains(e.slotId)) {
        return s;
      }
      final slotVotes = {
        ...(s.votes[e.slotId!] ?? const <String, ConsensusPreference>{}),
      };
      slotVotes[e.senderId] = e.preference ?? ConsensusPreference.ok;
      final votes = {...s.votes, e.slotId!: slotVotes};
      // 达成规则：首个集齐门限赞成票（prefer+ok，✕不行 不计）的时段自动固化
      final support =
          slotVotes.values.where((p) => p != ConsensusPreference.no).length;
      if (support >= (s.quorum ?? 1)) {
        return s.copyWith(
          votes: votes,
          status: BundleFsmStatus.confirmed,
          winningSlot: e.slotId,
        );
      }
      return s.copyWith(votes: votes);

    case ActionType.ADD_SLOT:
      if (s.status != BundleFsmStatus.proposed ||
          !s.participants.contains(e.senderId) ||
          e.slotId == null) {
        return s;
      }
      if (s.candidateSlots.length >= kMaxCandidateSlots) return s; // 满额丢弃
      return s.copyWith(candidateSlots: [...s.candidateSlots, e.slotId!]);

    case ActionType.FORCE_FINALIZE:
      // 分票死锁落槌（§4.4）：任一成员平权可落槌；并发竞态由服务端行级门禁
      // 先到先得（First-Commit-Wins），端侧按 seq 次序回放自然幂等。
      if (s.status != BundleFsmStatus.proposed ||
          !s.participants.contains(e.senderId) ||
          e.slotId == null ||
          !s.candidateSlots.contains(e.slotId)) {
        return s;
      }
      return s.copyWith(status: BundleFsmStatus.confirmed, winningSlot: e.slotId);

    case ActionType.REOPEN:
      return _reopenConsensus(s, e);

    case ActionType.CANCEL:
      if (!_isInitiator(s, e)) return s;
      return s.copyWith(status: BundleFsmStatus.cancelled);

    case ActionType.REKEY:
      return s.copyWith(keyVersion: s.keyVersion + 1);

    default:
      return s;
  }
}

// ---- 本地伪事件（非网络事件，时钟注入式纯函数） -----------------------------

/// Consensus Soft-Lock TTL（§4.4：PROPOSING 24h 无交集 → EXPIRED；服务端扫描
/// 为准，端侧本地同判据兜底）。
CollabBundleState maybeExpire(CollabBundleState s, int nowMs) {
  final ttl = s.expiresAtMs;
  if (ttl == null) return s;
  if (s.status == BundleFsmStatus.proposed &&
      s.proposalType == CollabProposalType.consensus &&
      nowMs >= ttl) {
    return s.copyWith(status: BundleFsmStatus.expired);
  }
  return s;
}

/// 发起人失联死锁熔断（§4.5：STALLED 超 48h 无 PATCH → 本地 ABANDONED_LOCALLY，
/// 自动释放预占；本地自保性退化而非全局终态——发起人最新 revision 信令到达即
/// 重新激活恢复预占，见 [_reactivateIfAbandoned]）。
CollabBundleState maybeAbandon(CollabBundleState s, int nowMs) {
  final since = s.stalledSinceMs;
  if (s.status == BundleFsmStatus.stalled &&
      since != null &&
      nowMs - since >= kStalledDeadlockTtl.inMilliseconds) {
    return s.copyWith(status: BundleFsmStatus.abandonedLocally);
  }
  return s;
}

/// 事件序列回放（/sync 补拉与 WSS 实时流合并后的统一入口；调用方保证按 seq
/// 升序——乱序由 SingleConsumerPipeline 归序后进入）。
CollabBundleState replay(CollabBundleState initial, Iterable<CollabSignal> events) {
  var s = initial;
  for (final e in events) {
    s = reduceEvent(s, e);
  }
  return s;
}
