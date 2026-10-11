/// 协同事件 → FSM 状态回放（Phase 4.3 C 页决策矩阵数据源）。
///
/// 设计口径（collab-implementation §3.2 / §11.1 分层单向数据流）：UI 决策矩阵直接
/// 消费 FSM 派生态 [CollabBundleState]，而派生态由 [collab_events] 落库原始事件经
/// 解密 + 回放得到——**绝不在 UI 做解密**，解密统一在此一处。
///
/// 两层：
/// 1. [signalFromPayload]：纯函数，把**已解密**的载荷 JSON 映射成 FSM 信号
///    [CollabSignal]（动作枚举 + 明文骨架引用），不碰 crypto，可独立单测；
/// 2. [replayBundle]：逐事件取 K_bundle 解密 → 调 [signalFromPayload] → 沿
///    [reduceEvent] 回放。解密失败/缺密钥的事件跳过（半盲投影铁律 1：个别密文解
///    不开骨架仍推进，绝不抛）。
library;

import 'dart:convert';
import 'dart:typed_data';

import 'fsm.dart';
import 'proto/relay_protocol.pb.dart' show ActionType;
import 'sealing.dart';
import '../data/collab_repository.dart';

/// 拒绝理由载荷字符串 → FSM 枚举（容错：未知/缺省归时间冲突）。
RejectReason reasonFromPayload(String? r) => switch (r) {
      'ROLE_UNWILLING' => RejectReason.roleUnwilling,
      _ => RejectReason.timeConflict,
    };

/// Consensus 三值偏好载荷字符串 → FSM 枚举。
ConsensusPreference prefFromPayload(String? p) => switch (p) {
      'PREFER' => ConsensusPreference.prefer,
      'NO' => ConsensusPreference.no,
      _ => ConsensusPreference.ok,
    };

List<String> _strList(Object? v) =>
    (v as List?)?.map((e) => '$e').toList() ?? const <String>[];

/// 把已解密的载荷映射成 FSM 信号（纯函数，不碰 DB/crypto）。
///
/// [payload] 为信封明文 JSON（经 [openPayload] 解出）。各动作字段取用对齐
/// `member_calendar_page` 的入队约定与 relay `action.go` 的回音壁约定。
CollabSignal signalFromPayload(
  ActionType action,
  Map<String, dynamic> payload, {
  required String senderId,
  required int seqId,
  int serverAtMs = 0,
}) {
  final stepId = payload['step_id'] as String?;
  final slotId = payload['slot_id'] as String?;
  switch (action) {
    case ActionType.PROPOSE:
      final skeleton = <CollabStepSpec>[];
      final rawSteps = payload['skeleton'];
      if (rawSteps is List) {
        for (final s in rawSteps) {
          if (s is! Map) continue;
          skeleton.add(CollabStepSpec(
            stepId: '${s['step_id']}',
            requiredUids: _strList(s['required_uids']),
            threshold: (s['threshold'] as int?) ?? 0,
            isOptional: s['is_optional'] == true,
          ));
        }
      }
      return CollabSignal(
        action: action,
        senderId: senderId,
        seqId: seqId,
        serverAtMs: serverAtMs,
        skeleton: skeleton,
        uids: _strList(payload['participants'])..addAll(_strList(payload['uids'])),
        initialSlots: _strList(payload['initial_slots']),
        quorum: payload['quorum'] as int?,
        expiresAtMs: payload['expires_at_ms'] as int?,
      );
    case ActionType.ACCEPT:
      return CollabSignal(
          action: action, senderId: senderId, seqId: seqId, serverAtMs: serverAtMs, stepId: stepId);
    case ActionType.REJECT:
      final suggest = payload['suggest_reassign'];
      return CollabSignal(
        action: action,
        senderId: senderId,
        seqId: seqId,
        serverAtMs: serverAtMs,
        stepId: stepId,
        rejectReason: reasonFromPayload(payload['reason'] as String?),
        uids: suggest != null ? <String>['$suggest'] : const <String>[],
      );
    case ActionType.VOTE:
      return CollabSignal(
        action: action,
        senderId: senderId,
        seqId: seqId,
        serverAtMs: serverAtMs,
        slotId: slotId ?? stepId,
        preference: prefFromPayload(payload['preference'] as String?),
      );
    case ActionType.PATCH_TIME:
    case ActionType.MAKE_OPTIONAL:
    case ActionType.PRUNE:
    case ActionType.REOPEN:
      return CollabSignal(
          action: action, senderId: senderId, seqId: seqId, serverAtMs: serverAtMs, stepId: stepId);
    case ActionType.REASSIGN:
      return CollabSignal(
        action: action,
        senderId: senderId,
        seqId: seqId,
        serverAtMs: serverAtMs,
        stepId: stepId,
        uids: _strList(payload['reassign_to']),
      );
    case ActionType.BYPASS:
      return CollabSignal(
        action: action,
        senderId: senderId,
        seqId: seqId,
        serverAtMs: serverAtMs,
        stepId: stepId,
        uids: _strList(payload['bypass_uids']),
      );
    case ActionType.NUDGE:
      return CollabSignal(
        action: action,
        senderId: senderId,
        seqId: seqId,
        serverAtMs: serverAtMs,
        stepId: stepId,
        uids: _strList(payload['nudge_uids']),
      );
    default:
      // 骨架中性事件（BRIEFING_UPDATE / STEP_NOTE / REKEY / FREEMASK_* 等）：
      // 仅推进 seq 水位，回放器原样忽略动作语义。
      return CollabSignal(
          action: action, senderId: senderId, seqId: seqId, serverAtMs: serverAtMs, stepId: stepId);
  }
}

/// 一个步骤的时间槽（起讫毫秒；任一端可空）。
///
/// 由 [replayBundle] 在解密载荷时抓取：同一步骤可能被多个事件提到（PROPOSE 发起
/// 时段、PATCH_TIME 改时段），故合并口径为「后到的非空值覆盖」＝最新改期生效。
class StepSlot {
  final int? startsAtMs;
  final int? endsAtMs;

  const StepSlot({this.startsAtMs, this.endsAtMs});
}

/// 从已解密载荷里提取步骤时段并入 [slots]。
///
/// 两种形状都认：①载荷顶层自带 step_id（步骤级事件，如 PATCH_TIME 改期）；
/// ②PROPOSE 的 skeleton[] 元素自带时段（骨架每步各带一段）。合并口径：后到的
/// 非空值覆盖＝最新改期生效；不携带时段的事件不覆盖既有。
void _collectSlot(Map<String, StepSlot>? slots, Map<String, dynamic> payload) {
  if (slots == null) return;
  _putSlot(
    slots,
    payload['step_id'] as String?,
    payload['starts_at_ms'] as int?,
    payload['ends_at_ms'] as int?,
  );
  final skeleton = payload['skeleton'];
  if (skeleton is List) {
    for (final s in skeleton) {
      if (s is! Map) continue;
      _putSlot(
        slots,
        s['step_id'] as String?,
        s['starts_at_ms'] as int?,
        s['ends_at_ms'] as int?,
      );
    }
  }
}

void _putSlot(Map<String, StepSlot> slots, String? stepId, int? st, int? en) {
  if (stepId == null || (st == null && en == null)) return;
  final prev = slots[stepId];
  slots[stepId] = StepSlot(
    startsAtMs: st ?? prev?.startsAtMs,
    endsAtMs: en ?? prev?.endsAtMs,
  );
}

ActionType? _actionFromString(String s) {
  for (final a in ActionType.values) {
    if (a.name == s) return a;
  }
  return null;
}

/// 把存储事件回放成 Bundle 状态（解密 + 映射 + 沿 [reduceEvent] 推进）。
///
/// [bundleKey] 取 K_bundle（发起人端持有；非发起人端仅半盲骨架）。返回状态的步状态/
/// 表决由 FSM 派生——这是 C 页决策矩阵的唯一权威数据源。
///
/// 解密失败或缺失密钥的事件被跳过（铁律 1：个别密文解不开骨架仍推进，绝不抛）。
Future<CollabBundleState> replayBundle(
  List<StoredCollabEvent> events,
  Future<List<int>?> Function(String bundleId, int keyVer) bundleKey, {
  String? bundleId,

  /// 顺手抓取的步骤时间槽（step_id → 时段）。**仅在解密那一刻才拿得到**：骨架只
  /// 含结构，真实时段藏在加密载荷里——这是 B 页时间槽 / A 页画板的唯一数据源。
  Map<String, StepSlot>? observedSlots,
}) async {
  final id = bundleId ?? '';
  var state = CollabBundleState.empty(id);
  for (final ev in events) {
    final action = _actionFromString(ev.actionType);
    if (action == null) continue;
    // draft→PROPOSE：初态 proposalType 由载荷决定（FSM 派发按 state.proposalType，
    // 故首条 PROPOSE 前必须先把初态类型对齐，否则 Consensus 会被误派发到 directive 分支）
    if (state.status == BundleFsmStatus.draft && action == ActionType.PROPOSE) {
      final pt = await _proposalTypeOf(ev, id, bundleKey);
      state = CollabBundleState.empty(id, proposalType: pt);
    }
    final key = await bundleKey(id, ev.keyVer);
    if (key == null || key.length != 32) continue; // 缺密钥：半盲跳过
    Map<String, dynamic> payload;
    try {
      final sealed = SealedPayload.decode(utf8.decode(ev.cipher));
      final plain = await openPayload(Uint8List.fromList(key), sealed);
      payload = jsonDecode(utf8.decode(plain)) as Map<String, dynamic>;
    } catch (_) {
      continue; // 解密失败：骨架中性跳过
    }
    _collectSlot(observedSlots, payload);
    final signal = signalFromPayload(
      action,
      payload,
      senderId: ev.senderId,
      seqId: ev.seqId,
      serverAtMs: ev.serverAtMs,
    );
    state = reduceEvent(state, signal);
  }
  return state;
}

/// 半盲地读出 PROPOSE 载荷的 proposal_type（不依赖 K_bundle：仅用于初态派发对齐）。
Future<CollabProposalType> _proposalTypeOf(
  StoredCollabEvent ev,
  String bundleId,
  Future<List<int>?> Function(String, int) bundleKey,
) async {
  final key = await bundleKey(bundleId, ev.keyVer);
  if (key == null || key.length != 32) return CollabProposalType.directive;
  try {
    final sealed = SealedPayload.decode(utf8.decode(ev.cipher));
    final plain = await openPayload(Uint8List.fromList(key), sealed);
    final p = jsonDecode(utf8.decode(plain)) as Map<String, dynamic>;
    return (p['proposal_type'] as String?) == 'CONSENSUS'
        ? CollabProposalType.consensus
        : CollabProposalType.directive;
  } catch (_) {
    return CollabProposalType.directive;
  }
}
