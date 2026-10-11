import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:shiguang/data/collab_repository.dart';
import 'package:shiguang/ui/collab/member_calendar_page.dart';

/// 成员混合日历（§11.2 B 页）组件测试：纯渲染层用种子数据驱动（不依赖 DB/异步），
/// 覆盖三态预占、空态、加载态与一键响应浮条。
void main() {
  // 种子数据（直接构造，不走 DB）
  const hDirective = CollabBundleHeader(
    id: 'b1',
    proposalType: 'DIRECTIVE',
    status: 'PROPOSED',
    maxSeqId: 0,
    keyVersion: 1,
    initiatorId: 'u1',
    participants: ['u1', 'u2', 'u3'],
    expiresAtMs: 9,
  );
  const hConsensus = CollabBundleHeader(
    id: 'b2',
    proposalType: 'CONSENSUS',
    status: 'PROPOSED',
    maxSeqId: 0,
    keyVersion: 1,
    initiatorId: 'u1',
    participants: ['u1', 'u2'],
    expiresAtMs: 9,
  );
  const hConfirmed = CollabBundleHeader(
    id: 'b3',
    proposalType: 'DIRECTIVE',
    status: 'CONFIRMED',
    maxSeqId: 0,
    keyVersion: 1,
    initiatorId: 'u1',
    participants: ['u1', 'u2'],
    expiresAtMs: 9,
  );
  const s1 = CollabStep(
    bundleId: 'b1',
    id: 's1',
    requiredUids: ['u1', 'u2'],
    threshold: 2,
    isOptional: false,
    status: 'PENDING',
    votes: {},
    sort: 1,
  );

  Future<void> pumpList(
    WidgetTester tester, {
    required bool loading,
    List<CollabBundleHeader> bundles = const [],
    Map<String, List<CollabStep>> steps = const {},
    void Function(CollabBundleHeader, CollabStep)? onRespond,
    void Function(CollabBundleHeader)? onOpenTimeline,
  }) async {
    await tester.pumpWidget(MaterialApp(
      home: Scaffold(
        body: CollabBundleListView(
          loading: loading,
          bundles: bundles,
          steps: steps,
          onRespond: onRespond ?? (_, _) {},
          onOpenTimeline: onOpenTimeline,
        ),
      ),
    ));
    await tester.pumpAndSettle();
  }

  testWidgets('空态：无协同日程时显示引导文案', (tester) async {
    await pumpList(tester, loading: false);
    expect(find.text('还没有协同日程'), findsOneWidget);
    expect(find.text('协同' /* 不要求 AppBar，纯渲染层 */), findsNothing);
  });

  testWidgets('加载态：显示静态加载占位（无不定态动画，便于测试收敛）',
      (tester) async {
    await pumpList(tester, loading: true);
    expect(find.text('加载中…'), findsOneWidget);
  });

  testWidgets('三态预占：DIRECTIVE 渲染「团队调度 / 待确认 / N 人协同」',
      (tester) async {
    await pumpList(
      tester,
      loading: false,
      bundles: [hDirective],
      steps: {'b1': [s1]},
    );
    expect(find.text('团队调度'), findsOneWidget);
    expect(find.text('待确认'), findsOneWidget);
    expect(find.text('3 人协同'), findsOneWidget);
  });

  testWidgets('三态预占：CONSENSUS 未确认渲染「候选」', (tester) async {
    await pumpList(
      tester,
      loading: false,
      bundles: [hConsensus],
      steps: {'b2': [s1]},
    );
    expect(find.text('生活预约'), findsOneWidget);
    expect(find.text('候选'), findsOneWidget);
  });

  testWidgets('三态预占：CONFIRMED 步骤不再可点开响应（无 chevron、onTap 为 null）',
      (tester) async {
    await pumpList(
      tester,
      loading: false,
      bundles: [hConfirmed],
      steps: {'b3': [s1]},
      onRespond: (_, _) {},
    );
    // CONFIRMED：状态条 + 步骤徽标均含「已确认」（至少 1 处）
    expect(find.text('已确认'), findsAtLeastNWidgets(1));
    // CONFIRMED 步骤 onTap 为 null → 不应渲染可点开的 chevron
    expect(find.byIcon(Icons.chevron_right), findsNothing);
  });

  testWidgets('点未确认步骤触发 onRespond 回调', (tester) async {
    late final CollabBundleHeader gotBundle;
    late final CollabStep gotStep;
    await pumpList(
      tester,
      loading: false,
      bundles: [hDirective],
      steps: {'b1': [s1]},
      onRespond: (b, s) {
        gotBundle = b;
        gotStep = s;
      },
    );
    await tester.tap(find.text('步骤 s1'));
    await tester.pumpAndSettle();
    expect(gotBundle.id, 'b1');
    expect(gotStep.id, 's1');
  });

  testWidgets('一键响应浮条：同意 → 触发 onAccept 回调', (tester) async {
    var accepted = false;
    await tester.pumpWidget(MaterialApp(
      home: Scaffold(
        body: ResponseSheet(
          bundle: hDirective,
          step: s1,
          onAccept: () async => accepted = true,
          onReject: () {},
        ),
      ),
    ));
    await tester.pumpAndSettle();
    expect(find.text('同意'), findsOneWidget);
    expect(find.text('拒绝并说明'), findsOneWidget);
    await tester.tap(find.text('同意'));
    await tester.pumpAndSettle();
    expect(accepted, isTrue);
  });

  testWidgets('一键响应浮条：拒绝并说明 → 触发 onReject 回调', (tester) async {
    var rejected = false;
    await tester.pumpWidget(MaterialApp(
      home: Scaffold(
        body: ResponseSheet(
          bundle: hConsensus,
          step: s1,
          onAccept: () async {},
          onReject: () => rejected = true,
        ),
      ),
    ));
    await tester.pumpAndSettle();
    await tester.tap(find.text('拒绝并说明'));
    await tester.pumpAndSettle();
    expect(rejected, isTrue);
  });

  testWidgets('协同包卡片：渲染「时间轴」入口', (tester) async {
    await pumpList(
      tester,
      loading: false,
      bundles: [hDirective],
      steps: {'b1': [s1]},
      onOpenTimeline: (_) {},
    );
    expect(find.text('时间轴'), findsOneWidget);
  });
}
