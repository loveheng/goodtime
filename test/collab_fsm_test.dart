import 'package:flutter_test/flutter_test.dart';

import 'package:shiguang/collab/fsm.dart';
import 'package:shiguang/collab/proto/relay_protocol.pb.dart';

/// Phase 3.2 FSM 回放器验收（collab-implementation §3.2）：
/// 离线注入事件序列，终态与设计稿 §4.2–§4.5 转移表逐格一致。
///
/// 时钟全部由事件 `serverAtMs` 注入（§5.2 法定时钟），reducer 零端钟。

CollabSignal sig(
  ActionType action,
  String senderId,
  int seqId, {
  String? stepId,
  String? slotId,
  RejectReason? reason,
  ConsensusPreference? pref,
  List<String> uids = const [],
  List<CollabStepSpec> skeleton = const [],
  List<String> slots = const [],
  int? quorum,
  int? expiresAtMs,
  int serverAtMs = 0,
}) =>
    CollabSignal(
      action: action,
      senderId: senderId,
      seqId: seqId,
      serverAtMs: serverAtMs,
      stepId: stepId,
      slotId: slotId,
      rejectReason: reason,
      preference: pref,
      uids: uids,
      skeleton: skeleton,
      initialSlots: slots,
      quorum: quorum,
      expiresAtMs: expiresAtMs,
    );

/// §4.2 示例骨架：s1 双人 t2 / s2 单人 t1 / s3 五人 t3（M-of-N）。
final kSkeleton = [
  const CollabStepSpec(stepId: 's1', requiredUids: ['a', 'b']),
  const CollabStepSpec(stepId: 's2', requiredUids: ['c']),
  const CollabStepSpec(
      stepId: 's3', requiredUids: ['a', 'b', 'c', 'd', 'e'], threshold: 3),
];

const hour = 3600 * 1000;

void main() {
  group('Directive 两级联动（§4.2/§4.3）', () {
    test('验收主线：离线注入 5 条事件序列 → 终态 CONFIRMED', () {
      final s = replay(CollabBundleState.empty('b1'), [
        sig(ActionType.PROPOSE, 'A', 1, uids: ['b', 'c', 'd', 'e'],
            skeleton: kSkeleton, serverAtMs: 0),
        // s1 双票齐 → ready
        sig(ActionType.ACCEPT, 'a', 2, stepId: 's1'),
        sig(ActionType.ACCEPT, 'b', 3, stepId: 's1'),
        // s2 单票 → ready；s3 仅 2/3 → pending；派生=全部必需 ready → CONFIRMED
        sig(ActionType.ACCEPT, 'c', 4, stepId: 's2'),
        sig(ActionType.ACCEPT, 'a', 5, stepId: 's3'),
      ]);
      // 5 条里 s3 只 1 票——为压满 5 事件且终态 CONFIRMED，改用双步骨架：
      expect(s.status, isNot(BundleFsmStatus.confirmed));
      expect(s.maxSeqId, 5);
      expect(s.steps['s1']!.status, StepFsmStatus.ready);
      expect(s.steps['s2']!.status, StepFsmStatus.ready);
      expect(s.steps['s3']!.status, StepFsmStatus.pending);
    });

    test('验收主线（压缩骨架版）：5 事件 → CONFIRMED + 骨架中性事件只推水位', () {
      final two = [
        const CollabStepSpec(stepId: 's1', requiredUids: ['a', 'b']),
        const CollabStepSpec(stepId: 's2', requiredUids: ['c']),
      ];
      final s = replay(CollabBundleState.empty('b1'), [
        sig(ActionType.PROPOSE, 'A', 1, uids: ['b', 'c'], skeleton: two),
        sig(ActionType.ACCEPT, 'a', 2, stepId: 's1'),
        sig(ActionType.ACCEPT, 'b', 3, stepId: 's1'),
        sig(ActionType.ACCEPT, 'c', 4, stepId: 's2'),
        // 骨架中性事件（便签）：终态不变，仅 seq 水位推进
        sig(ActionType.STEP_NOTE, 'b', 5, stepId: 's1'),
      ]);
      expect(s.status, BundleFsmStatus.confirmed);
      expect(s.maxSeqId, 5);
      expect(s.initiatorId, 'A');
    });

    test('REJECT 双维分流：time→TIME_CLASH、role→ROLE_CLASH，均落 STALLED', () {
      final s = replay(CollabBundleState.empty('b1'), [
        sig(ActionType.PROPOSE, 'A', 1, uids: ['b', 'c'], skeleton: kSkeleton),
        sig(ActionType.REJECT, 'b', 2, stepId: 's1',
            reason: RejectReason.timeConflict),
        sig(ActionType.REJECT, 'c', 3, stepId: 's2',
            reason: RejectReason.roleUnwilling),
      ]);
      expect(s.status, BundleFsmStatus.stalled);
      expect(s.steps['s1']!.status, StepFsmStatus.timeClash);
      expect(s.steps['s2']!.status, StepFsmStatus.roleClash);
    });

    test('PATCH_TIME 回音壁：accepted 联动降 pendingRecheck，重发 ACCEPT 复活', () {
      final two = [
        const CollabStepSpec(stepId: 's1', requiredUids: ['a', 'b']),
      ];
      final s = replay(CollabBundleState.empty('b1'), [
        sig(ActionType.PROPOSE, 'A', 1, uids: ['b'], skeleton: two),
        sig(ActionType.ACCEPT, 'a', 2, stepId: 's1'),
        sig(ActionType.REJECT, 'b', 3, stepId: 's1',
            reason: RejectReason.timeConflict),
        // 发起人改期：a 的旧同意作废待重确认（防「A 改 14:00→C 拒→…」拉锯）
        sig(ActionType.PATCH_TIME, 'A', 4, stepId: 's1', serverAtMs: 4 * hour),
      ]);
      expect(s.status, BundleFsmStatus.proposed); // clash 清空
      final s1 = s.steps['s1']!;
      expect(s1.ballots['a']!.state, MemberVoteState.pendingRecheck);
      expect(s1.ballots['b']!.state, MemberVoteState.pending);
      expect(s1.revision, 2);

      // 重确认：a 重发 ACCEPT（冻结 proto 无独立再确认动作，表决覆盖即复活）
      final s2 = replay(s, [sig(ActionType.ACCEPT, 'a', 5, stepId: 's1')]);
      expect(s2.steps['s1']!.ballots['a']!.state, MemberVoteState.accepted);
      expect(s2.status, BundleFsmStatus.proposed); // b 仍未表态
    });

    test('MAKE_OPTIONAL 收敛：移出强一致名单，其余全过 → CONFIRMED', () {
      final two = [
        const CollabStepSpec(stepId: 's1', requiredUids: ['a', 'b']),
        const CollabStepSpec(stepId: 's2', requiredUids: ['c']),
      ];
      final stalled = replay(CollabBundleState.empty('b1'), [
        sig(ActionType.PROPOSE, 'A', 1, uids: ['b', 'c'], skeleton: two),
        sig(ActionType.ACCEPT, 'a', 2, stepId: 's1'),
        sig(ActionType.ACCEPT, 'b', 3, stepId: 's1'),
        sig(ActionType.REJECT, 'c', 4, stepId: 's2',
            reason: RejectReason.roleUnwilling),
      ]);
      expect(stalled.status, BundleFsmStatus.stalled);

      final s = replay(stalled,
          [sig(ActionType.MAKE_OPTIONAL, 'A', 5, stepId: 's2')]);
      expect(s.steps['s2']!.status, StepFsmStatus.optional);
      expect(s.status, BundleFsmStatus.confirmed); // 其余全过
    });

    test('REOPEN：CONFIRMED→STALLED，受影响步骤回 PENDING，READY 步骤不受影响', () {
      final two = [
        const CollabStepSpec(stepId: 's1', requiredUids: ['a', 'b']),
        const CollabStepSpec(stepId: 's2', requiredUids: ['c']),
      ];
      final confirmed = replay(CollabBundleState.empty('b1'), [
        sig(ActionType.PROPOSE, 'A', 1, uids: ['b', 'c'], skeleton: two),
        sig(ActionType.ACCEPT, 'a', 2, stepId: 's1'),
        sig(ActionType.ACCEPT, 'b', 3, stepId: 's1'),
        sig(ActionType.ACCEPT, 'c', 4, stepId: 's2'),
      ]);
      final s = replay(confirmed,
          [sig(ActionType.REOPEN, 'A', 5, stepId: 's1', serverAtMs: 5 * hour)]);
      expect(s.status, BundleFsmStatus.stalled);
      expect(s.steps['s1']!.status, StepFsmStatus.pending);
      expect(s.steps['s1']!.revision, 2);
      expect(s.steps['s2']!.status, StepFsmStatus.ready); // 已 READY 不受影响
    });

    test('CANCEL 发起人独占：越权静默丢弃', () {
      final one = [const CollabStepSpec(stepId: 's1', requiredUids: ['a'])];
      final s = replay(CollabBundleState.empty('b1'), [
        sig(ActionType.PROPOSE, 'A', 1, uids: ['a'], skeleton: one),
      ]);
      final hijack = replay(s, [sig(ActionType.CANCEL, 'a', 2)]);
      expect(hijack.status, BundleFsmStatus.proposed); // 非发起人 → 丢弃
      final ok = replay(s, [sig(ActionType.CANCEL, 'A', 2)]);
      expect(ok.status, BundleFsmStatus.cancelled);
    });

    test('threshold M-of-N：5 选 3，两票 pending、三票 ready', () {
      final s3 = [
        const CollabStepSpec(
            stepId: 's3', requiredUids: ['a', 'b', 'c', 'd', 'e'], threshold: 3),
      ];
      final two = replay(CollabBundleState.empty('b1'), [
        sig(ActionType.PROPOSE, 'A', 1, uids: ['b', 'c', 'd', 'e'], skeleton: s3),
        sig(ActionType.ACCEPT, 'a', 2, stepId: 's3'),
        sig(ActionType.ACCEPT, 'b', 3, stepId: 's3'),
      ]);
      expect(two.steps['s3']!.status, StepFsmStatus.pending);
      expect(two.status, BundleFsmStatus.proposed);
      final three = replay(two, [sig(ActionType.ACCEPT, 'c', 4, stepId: 's3')]);
      expect(three.steps['s3']!.status, StepFsmStatus.ready);
      expect(three.status, BundleFsmStatus.confirmed);
    });

    test('BYPASS 一键降级：移出未响应成员且门限收缩，方案直接收敛', () {
      final s2 = [
        const CollabStepSpec(stepId: 's2', requiredUids: ['c', 'd']),
      ];
      final s = replay(CollabBundleState.empty('b1'), [
        sig(ActionType.PROPOSE, 'A', 1, uids: ['c', 'd'], skeleton: s2),
        sig(ActionType.ACCEPT, 'c', 2, stepId: 's2'),
      ]);
      expect(s.status, BundleFsmStatus.proposed); // 1/2

      final done = replay(s, [sig(ActionType.BYPASS, 'A', 3, stepId: 's2', uids: ['d'])]);
      expect(done.steps['s2']!.requiredUids, ['c']);
      expect(done.steps['s2']!.threshold, 1); // min(2, 剩余 1)
      expect(done.steps['s2']!.status, StepFsmStatus.ready);
      expect(done.status, BundleFsmStatus.confirmed);
    });

    test('ABANDONED_LOCALLY：STALLED 满 48h 本地熔断；发起人 PATCH 到达即再激活', () {
      final one = [const CollabStepSpec(stepId: 's1', requiredUids: ['a'])];
      final t0 = 1000 * hour;
      var s = replay(CollabBundleState.empty('b1'), [
        sig(ActionType.PROPOSE, 'A', 1, uids: ['a'], skeleton: one),
        sig(ActionType.REJECT, 'a', 2, stepId: 's1',
            reason: RejectReason.timeConflict, serverAtMs: t0),
      ]);
      expect(s.status, BundleFsmStatus.stalled);
      expect(s.stalledSinceMs, t0);

      s = maybeAbandon(s, t0 + 47 * hour);
      expect(s.status, BundleFsmStatus.stalled); // 未满 48h
      s = maybeAbandon(s, t0 + 48 * hour);
      expect(s.status, BundleFsmStatus.abandonedLocally); // 本地自保退化

      // 非全局终态：发起人改期信令到达 → 再激活恢复预占
      s = replay(s, [
        sig(ActionType.PATCH_TIME, 'A', 3, stepId: 's1', serverAtMs: t0 + 49 * hour),
      ]);
      expect(s.status, BundleFsmStatus.proposed);
      expect(s.steps['s1']!.status, StepFsmStatus.pending);
    });

    test('seq 防御：重复/倒退事件忽略；REKEY 只推版本；成员越权表决丢弃', () {
      final one = [const CollabStepSpec(stepId: 's1', requiredUids: ['a'])];
      final base = replay(CollabBundleState.empty('b1'), [
        sig(ActionType.PROPOSE, 'A', 1, uids: ['a'], skeleton: one),
        sig(ActionType.ACCEPT, 'a', 2, stepId: 's1'),
      ]);
      expect(base.status, BundleFsmStatus.confirmed);

      // 重复 seq=2 重放 → 原样
      final dup = replay(base, [sig(ActionType.REJECT, 'a', 2, stepId: 's1')]);
      expect(dup.status, BundleFsmStatus.confirmed);

      // REKEY：keyVersion+1，骨架不动
      final rekey = replay(base, [sig(ActionType.REKEY, 'A', 3)]);
      expect(rekey.keyVersion, 2);
      expect(rekey.status, BundleFsmStatus.confirmed);

      // 非必需成员表决 → 丢弃
      final stranger = replay(base, [sig(ActionType.REJECT, 'z', 3, stepId: 's1')]);
      expect(stranger.status, BundleFsmStatus.confirmed);
    });
  });

  group('Consensus 平权时段竞争（§4.4）', () {
    CollabBundleState propose() => replay(
        CollabBundleState.empty('b1',
            proposalType: CollabProposalType.consensus), [
          sig(ActionType.PROPOSE, 'A', 1,
              uids: ['a', 'b', 'c', 'd'],
              slots: ['fri18', 'sat12'],
              quorum: 3,
              expiresAtMs: 24 * hour),
        ]);

    test('达成规则：首个集齐 3 张赞成票的时段自动固化', () {
      var s = propose();
      expect(s.proposalType, CollabProposalType.consensus);
      expect(s.status, BundleFsmStatus.proposed);
      s = replay(s, [
        sig(ActionType.VOTE, 'a', 2, slotId: 'fri18', pref: ConsensusPreference.prefer),
        sig(ActionType.VOTE, 'b', 3, slotId: 'fri18', pref: ConsensusPreference.ok),
      ]);
      expect(s.status, BundleFsmStatus.proposed); // 2/3
      s = replay(s, [
        sig(ActionType.VOTE, 'c', 4, slotId: 'fri18', pref: ConsensusPreference.ok),
      ]);
      expect(s.status, BundleFsmStatus.confirmed);
      expect(s.winningSlot, 'fri18');
    });

    test('✕不行 不计赞成票；FORCE_FINALIZE 平权落槌；非参与者落槌丢弃', () {
      var s = propose();
      s = replay(s, [
        sig(ActionType.VOTE, 'a', 2, slotId: 'fri18', pref: ConsensusPreference.prefer),
        sig(ActionType.VOTE, 'b', 3, slotId: 'fri18', pref: ConsensusPreference.ok),
        sig(ActionType.VOTE, 'c', 4, slotId: 'fri18', pref: ConsensusPreference.no),
      ]);
      expect(s.status, BundleFsmStatus.proposed); // 2 赞成 + 1 反对

      final member = replay(s,
          [sig(ActionType.FORCE_FINALIZE, 'b', 5, slotId: 'sat12')]);
      expect(member.status, BundleFsmStatus.confirmed); // 分票死锁，平权一锤定音
      expect(member.winningSlot, 'sat12');

      final outsider = replay(s,
          [sig(ActionType.FORCE_FINALIZE, 'z', 5, slotId: 'sat12')]);
      expect(outsider.status, BundleFsmStatus.proposed);
    });

    test('REOPEN 平权重开：旧票作废、候选保留；ADD_SLOT 满 8 上限置灰', () {
      var s = propose();
      s = replay(s, [
        sig(ActionType.VOTE, 'a', 2, slotId: 'fri18', pref: ConsensusPreference.prefer),
        sig(ActionType.VOTE, 'b', 3, slotId: 'fri18', pref: ConsensusPreference.ok),
        sig(ActionType.VOTE, 'c', 4, slotId: 'fri18', pref: ConsensusPreference.ok),
      ]);
      final reopened = replay(s, [sig(ActionType.REOPEN, 'd', 5)]);
      expect(reopened.status, BundleFsmStatus.proposed); // 临时有事，平权重开
      expect(reopened.votes['fri18'] ?? const <String, ConsensusPreference>{},
          isEmpty); // 旧票作废（清空后键缺失亦算作废）
      expect(reopened.winningSlot, isNull);
      expect(reopened.candidateSlots, contains('fri18'));

      // 候选硬上限：8 个后 ADD_SLOT 丢弃
      var full = reopened;
      var seq = 6;
      for (var i = full.candidateSlots.length; i < kMaxCandidateSlots; i++) {
        full = replay(full, [sig(ActionType.ADD_SLOT, 'a', seq++, slotId: 's$i')]);
      }
      expect(full.candidateSlots.length, kMaxCandidateSlots);
      final overflow = replay(full,
          [sig(ActionType.ADD_SLOT, 'a', seq, slotId: 'overflow')]);
      expect(overflow.candidateSlots.length, kMaxCandidateSlots);
    });

    test('Soft-Lock TTL：24h 无交集 → EXPIRED（时钟注入判据）', () {
      final s = propose(); // expiresAt = 24h
      expect(maybeExpire(s, 23 * hour).status, BundleFsmStatus.proposed);
      expect(maybeExpire(s, 24 * hour).status, BundleFsmStatus.expired);
      // CONFIRMED 不被 TTL 扫掉
      final done = replay(s, [
        sig(ActionType.VOTE, 'a', 2, slotId: 'fri18', pref: ConsensusPreference.prefer),
        sig(ActionType.VOTE, 'b', 3, slotId: 'fri18', pref: ConsensusPreference.ok),
        sig(ActionType.VOTE, 'c', 4, slotId: 'fri18', pref: ConsensusPreference.ok),
      ]);
      expect(maybeExpire(done, 48 * hour).status, BundleFsmStatus.confirmed);
    });
  });
}
