/// FSM 派生态 → `collab_steps` 物化回填（Phase 4.3，解开 B 页时间槽钉子）。
///
/// ## 为什么必须有这一层
///
/// `collab_events` 只保真存密文（engine「只落库不解密」），而 UI 要的是**可读派生
/// 态**：每步当前状态、谁同意/拒绝、真实时段。此前全仓库没有任何「FSM → 步骤物化」
/// 回填路径，`collab_steps` 仅由 [CollabBundleRepository.ensureStep] 写入占位默认
/// 值——且该函数是 `INSERT OR IGNORE`，主键冲突即留旧值，于是**默认 PENDING 一旦
/// 落成就再也刷新不了**，成了 member_calendar_page 顶部那颗施工钉子的根因。
///
/// 本层把 [replayBundle] 的派生态（含解密时顺手抓取的步骤时段）replace 进
/// `collab_steps`，使后续 B 页时间槽 / A 页画板直接读物化表，**不必再持有密钥**。
library;

import 'fsm.dart'
    show
        CollabBundleState,
        CollabStepState,
        MemberVoteState,
        StepFsmStatus;
import 'replay.dart' show StepSlot, replayBundle;
import '../data/collab_repository.dart' show CollabBundleRepository, CollabStep;

/// 把一个 bundle 的 FSM 派生态整批物化到本地 `collab_steps`。
///
/// 返回写入行数（无步骤时为 0，且不会抹掉既有行）。同一 (bundle, step) 重复调用
/// 幂等：replace 覆盖，不累积。
///
/// [bundleKey] 缺失密钥的事件会被半盲跳过（铁律 1）——对应步骤拿不到派生值时不写。
Future<int> materializeBundle(
  CollabBundleRepository repo,
  String bundleId, {
  required Future<List<int>?> Function(String bundleId, int keyVer) bundleKey,
}) async {
  final events = await repo.loadEvents(bundleId);
  final slots = <String, StepSlot>{};
  final state = await replayBundle(
    events,
    bundleKey,
    bundleId: bundleId,
    observedSlots: slots,
  );
  final rows = stepsToRows(state, slots, bundleId: bundleId);
  return repo.upsertSteps(bundleId, rows);
}

/// 纯转换（可单测）：FSM 步骤态 + 观察到的时段 → 物化行。
///
/// [sort] 缺省按 stepId 字典序，保证多次回填的行序稳定（否则 UI 排序会抖）。
List<CollabStep> stepsToRows(
  CollabBundleState state,
  Map<String, StepSlot> slots, {
  required String bundleId,
}) {
  final keys = state.steps.keys.toList()..sort();
  return [
    for (var i = 0; i < keys.length; i++)
      _row(
        bundleId: bundleId,
        stepId: keys[i],
        step: state.steps[keys[i]]!,
        slot: slots[keys[i]],
        sort: i,
      ),
  ];
}

CollabStep _row({
  required String bundleId,
  required String stepId,
  required CollabStepState step,
  StepSlot? slot,
  required int sort,
}) {
  final votes = <String, String>{};
  for (final e in step.ballots.entries) {
    final token = _voteToken(e.value.state);
    if (token != null) votes[e.key] = token;
  }
  return CollabStep(
    bundleId: bundleId,
    id: stepId,
    requiredUids: step.requiredUids,
    threshold: step.threshold,
    isOptional: step.isOptional,
    status: _statusOf(step.status),
    votes: votes,
    sort: sort,
    startsAtMs: slot?.startsAtMs,
    endsAtMs: slot?.endsAtMs,
  );
}

/// FSM 步骤状态 → `collab_steps.status` 枚举字符串（现有读侧口径，勿改大小写）。
String _statusOf(StepFsmStatus s) => switch (s) {
      StepFsmStatus.ready => 'READY',
      StepFsmStatus.timeClash => 'TIME_CLASH',
      StepFsmStatus.roleClash => 'ROLE_CLASH',
      StepFsmStatus.optional => 'OPTIONAL',
      StepFsmStatus.pruned => 'PRUNED',
      _ => 'PENDING',
    };

/// 表决态 → 物化票值 token（OK/NO）；未表态返回 null。
///
/// 口径：accepted 记 'OK'、rejected 记 'NO'；**pending 与 pendingRecheck 都不记**
/// ——pendingRecheck 是「时段已改、旧同意作废待重确认」（fsm.dart PATCH_TIME），
/// 语义上等同未表态，记成 OK 会让人误以为该成员认了新时段。
String? _voteToken(MemberVoteState s) => switch (s) {
      MemberVoteState.accepted => 'OK',
      MemberVoteState.rejected => 'NO',
      _ => null,
    };
