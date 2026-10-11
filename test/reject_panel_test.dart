import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:shiguang/data/collab_repository.dart';
import 'package:shiguang/ui/collab/reject_panel.dart';

/// 双维拒绝面板（§11.2 D 页）组件测试：四步结构化流程 + 隐私分级 + 建议转派。
void main() {
  const bundle = CollabBundleHeader(
    id: 'b1',
    proposalType: 'DIRECTIVE',
    status: 'PROPOSED',
    maxSeqId: 0,
    keyVersion: 1,
    initiatorId: 'u1',
    participants: ['u1', 'u2', 'u3'],
    expiresAtMs: 9,
  );
  const step = CollabStep(
    bundleId: 'b1',
    id: 's1',
    requiredUids: ['u1', 'u2'],
    threshold: 2,
    isOptional: false,
    status: 'PENDING',
    votes: {},
    sort: 1,
  );

  Future<void> pump(
    WidgetTester tester, {
    required Future<void> Function(RejectResult) onConfirm,
    String? currentUserId,
  }) async {
    await tester.pumpWidget(MaterialApp(
      home: Scaffold(
        body: RejectPanel(
          bundle: bundle,
          step: step,
          currentUserId: currentUserId,
          onConfirm: onConfirm,
        ),
      ),
    ));
    await tester.pumpAndSettle();
  }

  testWidgets('初始显示主维度分流（时间冲突 / 权责异议），四步指示',
      (tester) async {
    await pump(tester, onConfirm: (_) async {});
    expect(find.text('提出异议'), findsOneWidget);
    expect(find.text('1 / 4'), findsOneWidget);
    expect(find.text('时间冲突'), findsOneWidget);
    expect(find.text('权责异议'), findsOneWidget);
  });

  testWidgets('四步流程：时间冲突 + 子类 + 隐私私密 + 转派 → 结构化结果',
      (tester) async {
    late RejectResult got;
    await pump(tester, onConfirm: (r) async => got = r, currentUserId: 'u2');

    // 步骤 0：选时间冲突
    await tester.tap(find.text('时间冲突'));
    await tester.pumpAndSettle();
    expect(find.text('2 / 4'), findsOneWidget);

    // 步骤 1：子类四选一 + 选填说明
    await tester.tap(find.text('缺前置条件'));
    await tester.pumpAndSettle();
    await tester.enterText(
        find.byType(TextField), '场地还没订');
    await tester.pumpAndSettle();
    await tester.tap(find.text('下一步'));
    await tester.pumpAndSettle();
    expect(find.text('3 / 4'), findsOneWidget);

    // 步骤 2：隐私分级 → 仅发起人可见
    await tester.tap(find.text('仅发起人可见'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('下一步'));
    await tester.pumpAndSettle();
    expect(find.text('4 / 4'), findsOneWidget);

    // 步骤 3：建议转派（排除自己 u2，候选为 u1/u3）
    expect(find.text('u1'), findsOneWidget);
    expect(find.text('u3'), findsOneWidget);
    await tester.tap(find.text('u3'));
    await tester.pumpAndSettle();

    await tester.tap(find.text('提交异议'));
    await tester.pumpAndSettle();

    expect(got.reason, 'TIME_CONFLICT');
    expect(got.subReason, 'MISSING_PREREQ');
    expect(got.detail, '场地还没订');
    expect(got.privacy, 'INITIATOR_ONLY');
    expect(got.suggestReassign, 'u3');
  });

  testWidgets('隐私默认全员可见，转派不选 → 默认隐私 + 无转派',
      (tester) async {
    late RejectResult got;
    await pump(tester, onConfirm: (r) async => got = r, currentUserId: 'u2');

    await tester.tap(find.text('权责异议'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('非我职责'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('下一步'));
    await tester.pumpAndSettle();
    // 步骤 2 默认 PUBLIC，直接前进
    await tester.tap(find.text('下一步'));
    await tester.pumpAndSettle();
    // 步骤 3 不选转派，直接提交
    await tester.tap(find.text('提交异议'));
    await tester.pumpAndSettle();

    expect(got.reason, 'ROLE_UNWILLING');
    expect(got.subReason, 'NOT_MY_JOB');
    expect(got.privacy, 'PUBLIC');
    expect(got.suggestReassign, isNull);
  });

  testWidgets('未选子类时「下一步」禁用（必填）', (tester) async {
    await pump(tester, onConfirm: (_) async {});
    await tester.tap(find.text('时间冲突'));
    await tester.pumpAndSettle();
    final next = tester.widget<FilledButton>(
        find.widgetWithText(FilledButton, '下一步'));
    expect(next.onPressed, isNull); // 禁用
  });

  testWidgets('上一步可回退到主维度', (tester) async {
    await pump(tester, onConfirm: (_) async {});
    await tester.tap(find.text('时间冲突'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('上一步'));
    await tester.pumpAndSettle();
    expect(find.text('1 / 4'), findsOneWidget);
  });
}
