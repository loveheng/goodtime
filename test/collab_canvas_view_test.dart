import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shiguang/data/collab_repository.dart' show CollabStep;
import 'package:shiguang/ui/collab/collab_canvas_page.dart'
    show CollabCanvasView, quorumDensity;

CollabStep _step(String id, {int? s, int? e, String status = 'READY'}) =>
    CollabStep(
      bundleId: 'b1',
      id: id,
      requiredUids: const ['u2'],
      threshold: 1,
      isOptional: false,
      status: status,
      votes: const {},
      sort: 0,
      startsAtMs: s,
      endsAtMs: e,
    );

void main() {
  const winS = 1700000000000;
  const winE = 1700003600000; // 一小时窗口

  test('quorumDensity：逐桶统计「几人有空」', () {
    // 3 份掩码、4 个桶：桶0 全有空=3，桶1 两人=2，桶2 一人=1，桶3 无人=0
    expect(quorumDensity(['1100', '1000', '1010']), [3, 1, 1, 0]);
  });

  test('quorumDensity：无掩码返回空（共振条降级）', () {
    expect(quorumDensity(const []), isEmpty);
  });

  testWidgets('共振条：按密度着色，全员交集桶描边高亮', (tester) async {
    await tester.pumpWidget(MaterialApp(
      home: Scaffold(
        body: CollabCanvasView(
          steps: const [],
          masks: const ['1100', '1000', '1010'],
          windowStartMs: winS,
          windowEndMs: winE,
        ),
      ),
    ));
    await tester.pumpAndSettle();
    expect(find.text('全员共振条'), findsOneWidget);
    // 4 个桶渲染成 4 个色块容器
    expect(find.byType(Container), findsWidgets);
  });

  testWidgets('无掩码时共振条降级提示', (tester) async {
    await tester.pumpWidget(MaterialApp(
      home: Scaffold(
        body: CollabCanvasView(
          steps: const [],
          masks: const [],
          windowStartMs: winS,
          windowEndMs: winE,
        ),
      ),
    ));
    await tester.pumpAndSettle();
    expect(find.text('暂无成员空闲数据'), findsOneWidget);
  });

  testWidgets('双轨切换：团队大盘 ↔ 个人总览', (tester) async {
    await tester.pumpWidget(MaterialApp(
      home: Scaffold(
        body: CollabCanvasView(
          steps: const [],
          masks: const ['11'],
          privateBusy: const [(winS + 600000, winS + 1200000)],
          windowStartMs: winS,
          windowEndMs: winE,
        ),
      ),
    ));
    await tester.pumpAndSettle();
    expect(find.text('团队大盘'), findsOneWidget);
    expect(find.text('个人总览'), findsOneWidget);
    // 团队大盘轨不投影幽灵图层
    expect(find.byKey(CollabCanvasView.ghostLayerKey), findsNothing);

    await tester.tap(find.text('个人总览'));
    await tester.pumpAndSettle();
    // 切到个人总览后铺幽灵图层（半透明斜纹）
    expect(find.byKey(CollabCanvasView.ghostLayerKey), findsOneWidget);
  });

  testWidgets('画布：仅渲染已回填时段的步骤', (tester) async {
    await tester.pumpWidget(MaterialApp(
      home: Scaffold(
        body: CollabCanvasView(
          steps: [
            _step('s-slotted', s: winS + 600000, e: winS + 1800000),
            _step('s-no-slot'), // 无时段：不应出现在画布
          ],
          masks: const ['11'],
          windowStartMs: winS,
          windowEndMs: winE,
        ),
      ),
    ));
    await tester.pumpAndSettle();
    expect(find.text('步骤 s-slotted'), findsOneWidget);
    expect(find.text('步骤 s-no-slot'), findsNothing);
  });

  testWidgets('画布：无已回填步骤时给出空态', (tester) async {
    await tester.pumpWidget(MaterialApp(
      home: Scaffold(
        body: CollabCanvasView(
          steps: [_step('s-no-slot')],
          masks: const ['11'],
          windowStartMs: winS,
          windowEndMs: winE,
        ),
      ),
    ));
    await tester.pumpAndSettle();
    expect(find.text('暂无已回填时段的步骤'), findsOneWidget);
  });
}
