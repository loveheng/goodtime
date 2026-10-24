import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:shiguang/data/collab_repository.dart';
import 'package:shiguang/ui/collab/reject_panel.dart';

/// 双维拒绝面板（§11.2 D 页 §3.4 减层版，2026-10-25）组件测试：
/// 默认一屏（维度+子类+可选备注一键拒绝）+ 展开态（隐私分级/建议转派）。
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

  testWidgets('默认一屏：维度/子类/备注/提交同屏，隐私与转派收在展开态',
      (tester) async {
    await pump(tester, onConfirm: (_) async {});
    expect(find.text('提出异议'), findsOneWidget);
    expect(find.text('1 / 4'), findsNothing); // 四步向导指示已退役
    expect(find.text('时间冲突'), findsOneWidget);
    expect(find.text('权责异议'), findsOneWidget);
    expect(find.text('非我职责'), findsOneWidget);
    expect(find.text('提交异议'), findsOneWidget);
    // 展开态默认折叠：隐私与转派不可见
    expect(find.text('谁可以看到你的理由？'), findsNothing);
    expect(find.text('建议转给谁？（选填）'), findsNothing);
    expect(find.text('详细说明与隐私（选填）'), findsOneWidget);
  });

  testWidgets('一屏提交：维度+子类即可，备注留空 → 默认隐私、无转派',
      (tester) async {
    late RejectResult got;
    await pump(tester, onConfirm: (r) async => got = r, currentUserId: 'u2');

    await tester.tap(find.text('权责异议'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('非我职责'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('提交异议'));
    await tester.pumpAndSettle();

    expect(got.reason, 'ROLE_UNWILLING');
    expect(got.subReason, 'NOT_MY_JOB');
    expect(got.detail, isNull);
    expect(got.privacy, 'PUBLIC');
    expect(got.suggestReassign, isNull);
  });

  testWidgets('展开态：隐私私密 + 建议转派 → 结构化结果带 INITIATOR_ONLY/转派',
      (tester) async {
    late RejectResult got;
    await pump(tester, onConfirm: (r) async => got = r, currentUserId: 'u2');

    await tester.tap(find.text('时间冲突'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('缺前置条件'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextField), '场地还没订');
    await tester.pumpAndSettle();

    await tester.tap(find.text('详细说明与隐私（选填）'));
    await tester.pumpAndSettle();
    expect(find.text('谁可以看到你的理由？'), findsOneWidget);
    // 建议转派排除自己 u2，候选为 u1/u3（单屏+展开态超出测试视口，先滚动可见）
    await tester.ensureVisible(find.text('u3'));
    await tester.pumpAndSettle();
    expect(find.text('u1'), findsOneWidget);
    expect(find.text('u3'), findsOneWidget);
    await tester.ensureVisible(find.text('仅发起人可见'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('仅发起人可见'));
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.text('u3'));
    await tester.pumpAndSettle();
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

  testWidgets('未选维度时提交禁用；维度选中后未选子类仍禁用', (tester) async {
    await pump(tester, onConfirm: (_) async {});
    FilledButton submit() => tester.widget<FilledButton>(
        find.widgetWithText(FilledButton, '提交异议'));
    expect(submit().onPressed, isNull); // 维度未选 → 禁用

    await tester.tap(find.text('时间冲突'));
    await tester.pumpAndSettle();
    expect(submit().onPressed, isNull); // 子类未选 → 禁用

    await tester.tap(find.text('个人原因'));
    await tester.pumpAndSettle();
    expect(submit().onPressed, isNotNull); // 齐 → 可提交
  });

  testWidgets('主维度单选可切换：后选覆盖先选', (tester) async {
    late RejectResult got;
    await pump(tester, onConfirm: (r) async => got = r, currentUserId: 'u2');

    await tester.tap(find.text('时间冲突'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('权责异议'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('个人原因'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('提交异议'));
    await tester.pumpAndSettle();

    expect(got.reason, 'ROLE_UNWILLING'); // 后选覆盖
    expect(got.subReason, 'PERSONAL');
  });
}
