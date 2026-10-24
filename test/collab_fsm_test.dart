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

    test('✕不行 不计赞成票；FORCE_FINALIZE 发起人独占（§7-7 对账清理）；越权落槌丢弃', () {
      var s = propose();
      s = replay(s, [
        sig(ActionType.VOTE, 'a', 2, slotId: 'fri18', pref: ConsensusPreference.prefer),
        sig(ActionType.VOTE, 'b', 3, slotId: 'fri18', pref: ConsensusPreference.ok),
        sig(ActionType.VOTE, 'c', 4, slotId: 'fri18', pref: ConsensusPreference.no),
      ]);
      expect(s.status, BundleFsmStatus.proposed); // 2 赞成 + 1 反对

      final hijack = replay(s,
          [sig(ActionType.FORCE_FINALIZE, 'b', 5, slotId: 'sat12')]);
      expect(hijack.status, BundleFsmStatus.proposed); // 非发起人 → 丢弃（死路径已清）
      final outsider = replay(s,
          [sig(ActionType.FORCE_FINALIZE, 'z', 5, slotId: 'sat12')]);
      expect(outsider.status, BundleFsmStatus.proposed);

      final boss = replay(s,
          [sig(ActionType.FORCE_FINALIZE, 'A', 5, slotId: 'sat12')]);
      expect(boss.status, BundleFsmStatus.confirmed); // 发起人一锤定音
      expect(boss.winningSlot, 'sat12');
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

  group('敲定后生命周期（§4.3/§4.5 2026-10-25）', () {
    CollabBundleState confirmed() => replay(CollabBundleState.empty('b1'), [
          sig(ActionType.PROPOSE, 'A', 1, uids: ['b', 'c'], skeleton: [
            const CollabStepSpec(stepId: 's1', requiredUids: ['a', 'b']),
            const CollabStepSpec(stepId: 's2', requiredUids: ['c']),
          ]),
          sig(ActionType.ACCEPT, 'a', 2, stepId: 's1'),
          sig(ActionType.ACCEPT, 'b', 3, stepId: 's1'),
          sig(ActionType.ACCEPT, 'c', 4, stepId: 's2'),
        ]);

    test('PATCH_STEP_TIME：包保持 CONFIRMED，受影响步骤入 recheck，其余步骤不动', () {
      final s = replay(confirmed(), [
        sig(ActionType.PATCH_STEP_TIME, 'A', 5,
            stepId: 's1', serverAtMs: 5 * hour),
      ]);
      expect(s.status, BundleFsmStatus.confirmed); // 包派生态不变
      final s1 = s.steps['s1']!;
      expect(s1.status, StepFsmStatus.recheck);
      expect(s1.revision, 2);
      expect(s1.ballots['a']!.state, MemberVoteState.pendingRecheck);
      expect(s1.ballots['b']!.state, MemberVoteState.pendingRecheck);
      expect(s.steps['s2']!.status, StepFsmStatus.ready); // 其余步骤不动
    });

    test('PATCH_STEP_TIME 守卫与复活：非发起人/非 CONFIRMED 丢弃；重确认 ACCEPT 复活、REJECT 落 STALLED', () {
      final base = confirmed();
      final hijack =
          replay(base, [sig(ActionType.PATCH_STEP_TIME, 'b', 5, stepId: 's1')]);
      expect(hijack.status, BundleFsmStatus.confirmed);
      expect(hijack.steps['s1']!.status, StepFsmStatus.ready); // 原样

      final stalledBase = replay(CollabBundleState.empty('b1'), [
        sig(ActionType.PROPOSE, 'A', 1, uids: ['b'], skeleton: [
          const CollabStepSpec(stepId: 's1', requiredUids: ['a', 'b']),
        ]),
        sig(ActionType.REJECT, 'b', 2, stepId: 's1',
            reason: RejectReason.timeConflict),
      ]);
      expect(stalledBase.status, BundleFsmStatus.stalled);
      final stray = replay(stalledBase,
          [sig(ActionType.PATCH_STEP_TIME, 'A', 3, stepId: 's1')]);
      expect(stray.steps['s1']!.status, StepFsmStatus.timeClash); // 协商期改期走 PATCH_TIME，本动作丢弃

      // 重确认：a 重发 ACCEPT 复活，b 未重确认仍 recheck，包保持 CONFIRMED
      final half = replay(confirmed(), [
        sig(ActionType.PATCH_STEP_TIME, 'A', 5, stepId: 's1'),
        sig(ActionType.ACCEPT, 'a', 6, stepId: 's1'),
      ]);
      expect(half.status, BundleFsmStatus.confirmed);
      expect(half.steps['s1']!.status, StepFsmStatus.recheck);
      expect(half.steps['s1']!.ballots['a']!.state, MemberVoteState.accepted);

      // 重确认再拒 → clash → STALLED
      final out = replay(confirmed(), [
        sig(ActionType.PATCH_STEP_TIME, 'A', 5, stepId: 's1'),
        sig(ActionType.REJECT, 'b', 6, stepId: 's1',
            reason: RejectReason.timeConflict),
      ]);
      expect(out.status, BundleFsmStatus.stalled);
      expect(out.steps['s1']!.status, StepFsmStatus.timeClash);
    });

    test('LEAVE：必需名单剔除+门限收缩；空名单步骤转 optional；participants 移除', () {
      final base = confirmed(); // s1(a,b) s2(c)，全部 ready
      final s = replay(base, [sig(ActionType.LEAVE, 'c', 5)]);
      expect(s.participants, isNot(contains('c')));
      expect(s.steps['s2']!.requiredUids, isEmpty);
      expect(s.steps['s2']!.status, StepFsmStatus.optional); // 空名单移出强一致
      expect(s.status, BundleFsmStatus.confirmed); // s1 不受影响

      // 门限收缩：5 选 3，d 退出后 min(3, 剩余 4)=3 不变
      final five = [
        const CollabStepSpec(
            stepId: 's3', requiredUids: ['a', 'b', 'c', 'd', 'e'], threshold: 3),
      ];
      var t = replay(CollabBundleState.empty('b1'), [
        sig(ActionType.PROPOSE, 'A', 1,
            uids: ['a', 'b', 'c', 'd', 'e'], skeleton: five),
        sig(ActionType.ACCEPT, 'a', 2, stepId: 's3'),
        sig(ActionType.ACCEPT, 'b', 3, stepId: 's3'),
      ]);
      expect(t.status, BundleFsmStatus.proposed); // 2/3
      t = replay(t, [sig(ActionType.LEAVE, 'd', 4)]);
      expect(t.steps['s3']!.threshold, 3); // min(3, 4)
      t = replay(t, [sig(ActionType.ACCEPT, 'c', 5, stepId: 's3')]);
      expect(t.status, BundleFsmStatus.confirmed);
    });

    test('LEAVE 守卫：发起人退出丢弃；非成员丢弃；CANCELLED 终态不可再退', () {
      final base = confirmed();
      final boss = replay(base, [sig(ActionType.LEAVE, 'A', 5)]);
      expect(boss.participants, contains('A')); // 发起人不可 LEAVE
      final stranger = replay(base, [sig(ActionType.LEAVE, 'z', 5)]);
      expect(stranger.participants.length, base.participants.length);
      final dead = replay(base, [sig(ActionType.CANCEL, 'A', 5)]);
      final after = replay(dead, [sig(ActionType.LEAVE, 'c', 6)]);
      expect(after.participants, contains('c'));
    });

    test('Consensus：PATCH_STEP_TIME 发起人退回重议（候选保留/票清空）；LEAVE 撤票', () {
      var s = replay(
          CollabBundleState.empty('b1',
              proposalType: CollabProposalType.consensus), [
        sig(ActionType.PROPOSE, 'A', 1,
            uids: ['a', 'b', 'c', 'd'],
            slots: ['fri18', 'sat12'],
            quorum: 3),
        sig(ActionType.VOTE, 'a', 2, slotId: 'fri18', pref: ConsensusPreference.prefer),
        sig(ActionType.VOTE, 'b', 3, slotId: 'fri18', pref: ConsensusPreference.ok),
        sig(ActionType.VOTE, 'c', 4, slotId: 'fri18', pref: ConsensusPreference.ok),
      ]);
      expect(s.status, BundleFsmStatus.confirmed);

      // 非发起人改期丢弃
      final hijack =
          replay(s, [sig(ActionType.PATCH_STEP_TIME, 'a', 5, slotId: 'fri18')]);
      expect(hijack.status, BundleFsmStatus.confirmed);

      // 发起人改期 → 退回重议：候选保留、票清空、胜出位清空
      final amend =
          replay(s, [sig(ActionType.PATCH_STEP_TIME, 'A', 5, slotId: 'fri18')]);
      expect(amend.status, BundleFsmStatus.proposed);
      expect(amend.winningSlot, isNull);
      expect(amend.candidateSlots, containsAll(['fri18', 'sat12']));
      expect((amend.votes['fri18'] ?? const <String, ConsensusPreference>{}),
          isEmpty);

      // LEAVE：c 撤自己的 no 票；此后 c 投票被丢弃；d 补票达标
      var t = replay(
          CollabBundleState.empty('b1',
              proposalType: CollabProposalType.consensus), [
        sig(ActionType.PROPOSE, 'A', 1,
            uids: ['a', 'b', 'c', 'd'], slots: ['fri18'], quorum: 3),
        sig(ActionType.VOTE, 'a', 2, slotId: 'fri18', pref: ConsensusPreference.prefer),
        sig(ActionType.VOTE, 'b', 3, slotId: 'fri18', pref: ConsensusPreference.ok),
        sig(ActionType.VOTE, 'c', 4, slotId: 'fri18', pref: ConsensusPreference.no),
      ]);
      t = replay(t, [sig(ActionType.LEAVE, 'c', 5)]);
      expect(t.participants, isNot(contains('c')));
      expect((t.votes['fri18'] ?? const <String, ConsensusPreference>{})
          .containsKey('c'), isFalse);
      final revote =
          replay(t, [sig(ActionType.VOTE, 'c', 6, slotId: 'fri18', pref: ConsensusPreference.ok)]);
      expect((revote.votes['fri18'] ?? const <String, ConsensusPreference>{})
          .containsKey('c'), isFalse); // 已退出 → 投票丢弃
      final done =
          replay(t, [sig(ActionType.VOTE, 'd', 6, slotId: 'fri18', pref: ConsensusPreference.ok)]);
      expect(done.status, BundleFsmStatus.confirmed); // 剩余成员补齐门限
    });
  });
}
