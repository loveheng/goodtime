import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shiguang/data/db.dart';
import 'package:shiguang/main.dart';
import 'package:shiguang/models/artifact.dart';
import 'package:shiguang/models/plan.dart';
import 'helpers/ui.dart';

/// 计划「随行凭证」子页 voided 可见出口（fact-user-relay-draft.md §1.2 双通道
/// 口径，2026-10-08 拍板；文案=ui-pages §9.5）：排程读侧全静默，UI 沉底部
/// 「已作废 · N 条」折叠组，可查看/长按删除（仅 human）。
void main() {
  setUp(() async {
    await setUpUiTest();
    final db = await Db.instance();
    await db.delete('artifacts');
  });

  String futureIso() {
    final d = DateTime.now().add(const Duration(days: 3));
    String p(int v) => v.toString().padLeft(2, '0');
    return '${d.year}-${p(d.month)}-${p(d.day)}';
  }

  Artifact fact({
    required String planId,
    required String title,
    required String state,
  }) =>
      Artifact(
        category: Artifact.categoryTicket,
        sourceKind: Artifact.sourceBooking,
        state: state,
        origin: Artifact.originShared,
        title: title,
        payload: {
          'time_anchors': [
            {'role': 'checkin', 'kind': 'span', 'date': futureIso(), 'start_min': 600, 'end_min': 660},
          ],
        },
        planId: planId,
      );

  testWidgets('凭证子页：voided 沉「已作废」折叠组、不进主卡面；展开可见弱化标',
      (tester) async {
    await real(tester, seedSettings);
    final planId = await real(
      tester,
      () async => (await repo.addPlan(Plan(title: '云南七天'))).id!,
    );
    await real(tester, () => repo.addArtifact(fact(
          planId: planId,
          title: '玉龙雪山大索道票',
          state: Artifact.stateStructured,
        )));
    await real(tester, () => repo.addArtifact(fact(
          planId: planId,
          title: '已退票的索道票',
          state: Artifact.stateVoided,
        )));

    await tester.pumpWidget(const ShiguangApp());
    await pumpFlush(tester);
    await tester.tap(find.widgetWithText(NavigationDestination, '清单'));
    await pumpFlush(tester);
    await tester.tap(find.text('云南七天'));
    await pumpFlush(tester);
    await tester.tap(find.text('随行凭证'));
    await pumpFlush(tester);

    expect(find.text('玉龙雪山大索道票'), findsOneWidget, reason: '有效凭证在主列表');
    expect(find.text('已作废 · 1 条'), findsOneWidget, reason: 'voided 沉折叠组');
    expect(find.text('已退票的索道票'), findsNothing, reason: 'voided 不进主卡面（§1.2）');

    await tester.tap(find.text('已作废 · 1 条'));
    await pumpFlush(tester);
    expect(find.text('已退票的索道票'), findsOneWidget, reason: '展开后可读（存证保留的可见出口）');
    expect(find.text('已作废'), findsOneWidget, reason: '卡面弱化标');
  });
}
