// P0 黄金向量基准执行器（Dart 侧）——FSM 转移表（lib/collab/fsm.dart 纯函数 Reducer）。
//
// 职责：把 test/golden/fsm_transition_vectors.json 跑通并锁定期望值（不依赖 Kotlin）。
// - 向量 = 跨实现契约数据（Dart 与 Kotlin 双侧消费，Kotlin 对拍在 P2 接入）；
// - 本执行器逐 case 重放事件脚本并对拍真实 Reducer，凡实现漂移即红；
// - 序列化为独立副本（与生成器解耦，同 golden_e2ee_test 先例）：两副本漂移即红，
//   规范化规则以向量集 rules 节为准。
// 向量语义与字段说明见 test/golden/README.md；向量文件为只读契约，实现漂移时
// 唯一出路是修实现，严禁改向量迁就实现（dev-loop 测试写保护）。
import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

import 'package:shiguang/collab/fsm.dart';
import 'package:shiguang/collab/proto/relay_protocol.pb.dart';

// ---- 枚举名 ⇄ 枚举（语义大写名，= 生成器同款映射） ----------------------------

const _bundleStatusOf = {
  'DRAFT': BundleFsmStatus.draft,
  'PROPOSED': BundleFsmStatus.proposed,
  'STALLED': BundleFsmStatus.stalled,
  'CONFIRMED': BundleFsmStatus.confirmed,
  'CANCELLED': BundleFsmStatus.cancelled,
  'EXPIRED': BundleFsmStatus.expired,
  'ARCHIVED': BundleFsmStatus.archived,
  'ABANDONED_LOCALLY': BundleFsmStatus.abandonedLocally,
};

const _stepStatusOf = {
  'PENDING': StepFsmStatus.pending,
  'READY': StepFsmStatus.ready,
  'TIME_CLASH': StepFsmStatus.timeClash,
  'ROLE_CLASH': StepFsmStatus.roleClash,
  'OPTIONAL': StepFsmStatus.optional,
  'PRUNED': StepFsmStatus.pruned,
  'RECHECK': StepFsmStatus.recheck,
};

const _voteStateOf = {
  'PENDING': MemberVoteState.pending,
  'ACCEPTED': MemberVoteState.accepted,
  'PENDING_RECHECK': MemberVoteState.pendingRecheck,
  'REJECTED': MemberVoteState.rejected,
};

final _actionOf = {for (final v in ActionType.values) v.name: v};

RejectReason _reasonOf(String name) => name == 'TIME_CONFLICT'
    ? RejectReason.timeConflict
    : RejectReason.roleUnwilling;

ConsensusPreference _prefOf(String name) => switch (name) {
      'PREFER' => ConsensusPreference.prefer,
      'OK' => ConsensusPreference.ok,
      _ => ConsensusPreference.no,
    };

String _prefName(ConsensusPreference p) => p.name.toUpperCase();
String _reasonName(RejectReason r) =>
    r == RejectReason.timeConflict ? 'TIME_CONFLICT' : 'ROLE_UNWILLING';

// ---- JSON → 输入模型 ---------------------------------------------------------

CollabSignal _signalOf(Map<String, dynamic> m) => CollabSignal(
      action: _actionOf[m['action'] as String]!,
      senderId: m['sender'] as String,
      seqId: m['seq'] as int,
      serverAtMs: (m['server_at_ms'] as int?) ?? 0,
      stepId: m['step_id'] as String?,
      slotId: m['slot_id'] as String?,
      rejectReason:
          m['reject_reason'] == null ? null : _reasonOf(m['reject_reason'] as String),
      preference:
          m['preference'] == null ? null : _prefOf(m['preference'] as String),
      uids: ((m['uids'] as List?) ?? const []).cast<String>(),
      skeleton: ((m['skeleton'] as List?) ?? const [])
          .map((e) => CollabStepSpec(
                stepId: (e as Map<String, dynamic>)['step_id'] as String,
                requiredUids:
                    ((e['required_uids'] as List?) ?? const []).cast<String>(),
                threshold: (e['threshold'] as int?) ?? 0,
                isOptional: (e['is_optional'] as bool?) ?? false,
              ))
          .toList(),
      initialSlots: ((m['initial_slots'] as List?) ?? const []).cast<String>(),
      quorum: m['quorum'] as int?,
      expiresAtMs: m['expires_at_ms'] as int?,
    );

// ---- 状态 → 规范 JSON（独立副本；规则=向量集 rules 节） ------------------------

Map<String, Object?> _stepToJson(CollabStepState st) => {
      'step_id': st.stepId,
      'required_uids': List.of(st.requiredUids),
      'threshold': st.threshold,
      'is_optional': st.isOptional,
      'status': _stepStatusOf.entries
          .firstWhere((e) => e.value == st.status)
          .key,
      'revision': st.revision,
      'ballots': {
        for (final uid in st.ballots.keys.toList()..sort())
          uid: {
            'state': _voteStateOf.entries
                .firstWhere((e) => e.value == st.ballots[uid]!.state)
                .key,
            if (st.ballots[uid]!.reason != null)
              'reason': _reasonName(st.ballots[uid]!.reason!),
          },
      },
    };

Map<String, Object?> _stateToJson(CollabBundleState s) {
  final bundleStatus = _bundleStatusOf.entries
      .firstWhere((e) => e.value == s.status)
      .key;
  final stepStatus = {
    for (final id in s.steps.keys.toList()..sort())
      id: _stepToJson(s.steps[id]!),
  };
  return {
    'bundle_id': s.bundleId,
    'proposal_type': s.proposalType.name.toUpperCase(),
    'status': bundleStatus,
    'base_revision': s.baseRevision,
    'max_seq_id': s.maxSeqId,
    'initiator_id': s.initiatorId,
    'participants': s.participants.toList()..sort(),
    'key_version': s.keyVersion,
    'steps': stepStatus,
    'candidate_slots': List.of(s.candidateSlots),
    'votes': {
      for (final slot in s.votes.keys.toList()..sort())
        slot: {
          for (final uid in s.votes[slot]!.keys.toList()..sort())
            uid: _prefName(s.votes[slot]![uid]!),
        },
    },
    'winning_slot': s.winningSlot,
    'quorum': s.quorum,
    'expires_at_ms': s.expiresAtMs,
    'stalled_since_ms': s.stalledSinceMs,
  };
}

void main() {
  final set =
      jsonDecode(File('test/golden/fsm_transition_vectors.json').readAsStringSync())
          as Map<String, dynamic>;
  group('golden: fsm-transition', () {
    for (final c in (set['cases'] as List).cast<Map<String, dynamic>>()) {
      final id = c['id'] as String;
      final input = c['input'] as Map<String, dynamic>;
      final expected = c['expect'] as Map<String, dynamic>;
      test('replay $id', () {
        var s = CollabBundleState.empty(
          input['bundle_id'] as String,
          proposalType: input['proposal_type'] == 'CONSENSUS'
              ? CollabProposalType.consensus
              : CollabProposalType.directive,
        );
        for (final item in (input['events'] as List).cast<Map<String, dynamic>>()) {
          if (item.containsKey('signal')) {
            s = replay(s, [_signalOf(item['signal'] as Map<String, dynamic>)]);
          } else {
            final local = item['local'] as Map<String, dynamic>;
            final now = local['now_ms'] as int;
            s = local['fn'] == 'maybe_expire'
                ? maybeExpire(s, now)
                : maybeAbandon(s, now);
          }
        }
        expect(_stateToJson(s), expected);
      });
    }
  });
}
