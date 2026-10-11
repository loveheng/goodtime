import 'dart:convert';
import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:shiguang/collab/fsm.dart'
    show CollabBundleState, CollabProposalType, StepFsmStatus;
import 'package:shiguang/collab/proto/relay_protocol.pb.dart' show ActionType;
import 'package:shiguang/collab/replay.dart'
    as replay show signalFromPayload, replayBundle;
import 'package:shiguang/collab/sealing.dart' show sealPayload;
import 'package:shiguang/data/collab_repository.dart' show StoredCollabEvent;

const _k = <int>[
  1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16,
  17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32,
];

Future<StoredCollabEvent> _ev(int seq, String action, Map<String, Object?> payload,
    {int keyVer = 1, String senderId = 'u2'}) async {
  final sealed = await sealPayload(
      Uint8List.fromList(_k), utf8.encode(jsonEncode(payload)), keyVer);
  return StoredCollabEvent(
    seqId: seq,
    actionType: action,
    senderId: senderId,
    cipher: utf8.encode(sealed.encode()),
    keyVer: keyVer,
    clientMsgId: 'c$seq',
    topologyRef: 't0',
    serverAtMs: 1700000000000 + seq,
  );
}

void main() {
  group('signalFromPayload 纯映射', () {
    test('ACCEPT 取 step_id', () {
      final s = replay.signalFromPayload(
        ActionType.ACCEPT,
        {'step_id': 's1'},
        senderId: 'u2',
        seqId: 2,
      );
      expect(s.stepId, 's1');
      expect(s.action, ActionType.ACCEPT);
    });

    test('REJECT 映射理由 + 转派建议', () {
      final s = replay.signalFromPayload(
        ActionType.REJECT,
        {
          'step_id': 's1',
          'reason': 'ROLE_UNWILLING',
          'suggest_reassign': 'u3',
        },
        senderId: 'u2',
        seqId: 2,
      );
      expect(s.stepId, 's1');
      expect(s.rejectReason?.name, 'roleUnwilling');
      expect(s.uids, ['u3']);
    });

    test('PROPOSE(directive) 解析骨架 + 参与人', () {
      final s = replay.signalFromPayload(
        ActionType.PROPOSE,
        {
          'proposal_type': 'DIRECTIVE',
          'skeleton': [
            {
              'step_id': 's1',
              'required_uids': ['u2', 'u3'],
              'threshold': 2,
              'is_optional': false,
            }
          ],
          'participants': ['u1', 'u2', 'u3'],
          'expires_at_ms': 123,
        },
        senderId: 'u1',
        seqId: 1,
      );
      expect(s.skeleton.first.stepId, 's1');
      expect(s.skeleton.first.threshold, 2);
      expect(s.uids, ['u1', 'u2', 'u3']);
    });

    test('BYPASS / REASSIGN 取目标 uid 列表', () {
      final b = replay.signalFromPayload(
        ActionType.BYPASS,
        {'step_id': 's1', 'bypass_uids': ['u2']},
        senderId: 'u1',
        seqId: 5,
      );
      final r = replay.signalFromPayload(
        ActionType.REASSIGN,
        {'step_id': 's1', 'reassign_to': ['u4']},
        senderId: 'u1',
        seqId: 6,
      );
      expect(b.uids, ['u2']);
      expect(r.uids, ['u4']);
    });
  });

  group('replayBundle 解密回放', () {
    test('directive：M-of-N 进度由表决派生', () async {
      final events = [
        await _ev(1, 'PROPOSE', {
          'proposal_type': 'DIRECTIVE',
          'skeleton': [
            {
              'step_id': 's1',
              'required_uids': ['u2', 'u3'],
              'threshold': 2,
              'is_optional': false,
            }
          ],
          'participants': ['u1', 'u2', 'u3'],
          'expires_at_ms': 999,
        }, senderId: 'u1'),
        await _ev(2, 'ACCEPT', {'step_id': 's1'}, senderId: 'u2'),
        await _ev(3, 'ACCEPT', {'step_id': 's1'}, senderId: 'u3'),
      ];
      final state = await replay.replayBundle(
        events,
        (_, _) async => _k,
        bundleId: 'b1',
      );
      final step = state.steps['s1']!;
      expect(state.proposalType, CollabProposalType.directive);
      expect(state.participants.length, 3);
      expect(state.participants.contains('u1'), isTrue);
      expect(step.acceptCount, 2);
      expect(step.status, StepFsmStatus.ready);
    });

    test('consensus：首条 PROPOSE 初态派发对齐（不误入 directive 分支）', () async {
      final events = [
        await _ev(1, 'PROPOSE', {
          'proposal_type': 'CONSENSUS',
          'initial_slots': ['slot-a'],
          'quorum': 2,
          'participants': ['u1', 'u2'],
          'expires_at_ms': 999,
        }, senderId: 'u1'),
        await _ev(2, 'VOTE', {'slot_id': 'slot-a', 'preference': 'PREFER'},
            senderId: 'u2'),
      ];
      final state = await replay.replayBundle(
        events,
        (_, _) async => _k,
        bundleId: 'b2',
      );
      expect(state.proposalType, CollabProposalType.consensus);
      // directive 分支不会有候选 slot；consensus 应含 slot 候选
      expect(state.candidateSlots.length, 1);
    });

    test('缺密钥的事件被半盲跳过（不抛、不卡死）', () async {
      final events = [
        await _ev(1, 'PROPOSE', {
          'proposal_type': 'DIRECTIVE',
          'skeleton': [
            {
              'step_id': 's1',
              'required_uids': ['u2'],
              'threshold': 1,
              'is_optional': false,
            }
          ],
          'participants': ['u1', 'u2'],
        }, senderId: 'u1'),
      ];
      final state = await replay.replayBundle(
        events,
        (_, _) async => null, // 无密钥
        bundleId: 'b3',
      );
      // PROPOSE 未解密 → 无步骤；状态仍为 draft（半盲跳过）
      expect(state.steps.isEmpty, isTrue);
      expect(state.status, CollabBundleState.empty('x').status);
    });
  });
}
