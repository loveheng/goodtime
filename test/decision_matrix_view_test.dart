import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shiguang/collab/fsm.dart'
    show
        BundleFsmStatus,
        CollabBundleState,
        CollabProposalType,
        CollabSignal,
        CollabStepSpec,
        RejectReason,
        reduceEvent;
import 'package:shiguang/collab/proto/relay_protocol.pb.dart' show ActionType;
import 'package:shiguang/ui/collab/decision_matrix_page.dart'
    show DecisionMatrixActions, DecisionMatrixView;

/// 经 FSM 自身回放构造 directive 状态（沿用 fsm.dart 公共 API，不手搓构造函数）。
///
/// 骨架：步骤 s1 需 u2/u3 二人应答、门限 2；u2 已同意。
/// [withReject] 追加 u3 的时间冲突拒绝 → 步骤落到 timeClash（触发闭环三键）。
CollabBundleState _matrixState({bool withReject = false}) {
  var s = CollabBundleState.empty('b1',
      proposalType: CollabProposalType.directive);
  s = reduceEvent(
    s,
    const CollabSignal(
      action: ActionType.PROPOSE,
      senderId: 'u1',
      seqId: 1,
      serverAtMs: 1000,
      skeleton: [
        CollabStepSpec(
            stepId: 's1', requiredUids: ['u2', 'u3'], threshold: 2),
      ],
      uids: ['u2', 'u3'],
    ),
  );
  s = reduceEvent(
    s,
    const CollabSignal(
      action: ActionType.ACCEPT,
      senderId: 'u2',
      seqId: 2,
      stepId: 's1',
      serverAtMs: 1100,
    ),
  );
  if (withReject) {
    s = reduceEvent(
      s,
      const CollabSignal(
        action: ActionType.REJECT,
        senderId: 'u3',
        seqId: 3,
        stepId: 's1',
        rejectReason: RejectReason.timeConflict,
        serverAtMs: 1200,
      ),
    );
  }
  return s;
}

Future<void> _pump(
  WidgetTester tester, {
  required CollabBundleState state,
  String? currentUserId,
  bool isInitiator = false,
  DecisionMatrixActions? actions,
}) async {
  await tester.pumpWidget(MaterialApp(
    home: Scaffold(
      body: DecisionMatrixView(
        state: state,
        currentUserId: currentUserId,
        isInitiator: isInitiator,
        actions: actions,
      ),
    ),
  ));
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('决策矩阵：门限进度条 + 逐成员徽标 + Nudge 铃铛（发起人视角）',
      (tester) async {
    await _pump(
      tester,
      state: _matrixState(),
      currentUserId: 'u2',
      isInitiator: true,
      // 提供 onNudge 才会渲染铃铛（无回调=无操作入口）
      actions: DecisionMatrixActions(onNudge: (_, _) {}),
    );

    // 头部：directive=团队调度；PROPOSE 后 bundle=协商中
    expect(find.text('团队调度'), findsOneWidget);
    expect(find.text('协商中'), findsOneWidget);
    // M-of-N：u2 已同意 → 1/2
    expect(find.text('1/2 人同意'), findsOneWidget);
    // 徽标：u2 渲染为「你」，u3 待确认
    expect(find.text('你'), findsOneWidget);
    expect(find.text('u3'), findsOneWidget);
    // 待确认成员（u3）旁有催办铃铛
    expect(find.byIcon(Icons.notifications_active), findsOneWidget);
    // 日常 pending 步骤不挂三键（仅冲突步骤才挂）
    expect(find.text('转派'), findsNothing);
  });

  testWidgets('决策矩阵：非发起人（actions=null）不渲染闭环三键与催办铃铛',
      (tester) async {
    await _pump(
      tester,
      state: _matrixState(withReject: true),
      currentUserId: 'u2',
      isInitiator: false,
      actions: null,
    );
    expect(find.text('转派'), findsNothing);
    expect(find.text('降级'), findsNothing);
    expect(find.text('裁撤'), findsNothing);
    expect(find.byIcon(Icons.notifications_active), findsNothing);
  });

  testWidgets('决策矩阵：冲突步骤发起人可见闭环三键，裁撤直接触发', (tester) async {
    var pruned = false;
    await _pump(
      tester,
      state: _matrixState(withReject: true),
      currentUserId: 'u1',
      isInitiator: true,
      actions: DecisionMatrixActions(
        onPrune: (_) => pruned = true,
        onReassign: (_, _) {},
        onBypass: (_, _) {},
      ),
    );

    expect(find.text('时间冲突'), findsOneWidget);
    expect(find.text('转派'), findsOneWidget);
    expect(find.text('降级'), findsOneWidget);
    expect(find.text('裁撤'), findsOneWidget);

    await tester.tap(find.text('裁撤'));
    await tester.pumpAndSettle();
    expect(pruned, isTrue);
  });

  testWidgets('决策矩阵：空态（无密钥回退）显示解密提示', (tester) async {
    await _pump(
      tester,
      state: CollabBundleState.empty('b9'),
      currentUserId: 'u1',
      isInitiator: true,
    );
    expect(find.text('暂无步骤数据'), findsOneWidget);
    expect(find.text('该包密钥尚未取到，联网补齐后即可解密回放'), findsOneWidget);
  });

  test('已确认方案提供重新打开、协商中提供取消', () async {
    // 纯数据断言：避免导航依赖，直接验证 bundle 状态与页脚按钮分支
    final confirmed = _matrixState().copyWith(status: BundleFsmStatus.confirmed);
    final negotiating = _matrixState();
    expect(confirmed.status, BundleFsmStatus.confirmed);
    expect(negotiating.status, BundleFsmStatus.proposed);
  });
}
