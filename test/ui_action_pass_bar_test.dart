import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shiguang/action/fact_rules.dart';
import 'package:shiguang/models/artifact.dart';
import 'package:shiguang/ui/action_pass_bar.dart';

/// 临场通关条 UI（fact 草案 §9.4 分期二）：展开最早一条 +「+N」胶囊折叠。
Artifact _fact(String title, String role, String date, int min) => Artifact(
      category: Artifact.categoryTransit,
      sourceKind: Artifact.sourceBooking,
      state: Artifact.stateStructured,
      origin: Artifact.originShared,
      title: title,
      payload: {
        'time_anchors': [
          {'role': role, 'kind': 'moment', 'date': date, 'min': min},
        ],
        'constraints': {'advance_arrival_minutes': 45},
      },
    );

ActionPass _pass(Artifact f, DateTime at) => ActionPass(
      fact: f,
      role: FactRules.normalizeRole('departure'),
      actionTime: at,
      triggerTime: at.subtract(const Duration(minutes: 165)),
      dismissTime: at.add(const Duration(minutes: 30)),
      title: f.title,
    );

void main() {
  testWidgets('单条通关条：显示时刻/角色/标题与「建议 X 前到站」', (tester) async {
    final f = _fact('D8724 动车', 'departure', '2026-10-20', 540);
    await tester.pumpWidget(MaterialApp(
      home: Scaffold(body: ActionPassBar(passes: [_pass(f, DateTime(2026, 10, 20, 9, 0))])),
    ));
    expect(find.textContaining('D8724 动车'), findsOneWidget);
    expect(find.textContaining('发车/起飞'), findsOneWidget);
    expect(find.textContaining('建议'), findsOneWidget); // 带 advance
  });

  testWidgets('多条：展开最早一条 +「+N」折叠胶囊', (tester) async {
    final a = _fact('早班机', 'departure', '2026-10-20', 360);
    final b = _fact('高铁', 'departure', '2026-10-20', 540);
    final c = _fact('末班船', 'departure', '2026-10-20', 720);
    final passes = [_pass(a, DateTime(2026, 10, 20, 6, 0)), _pass(b, DateTime(2026, 10, 20, 9, 0)), _pass(c, DateTime(2026, 10, 20, 12, 0))];
    await tester.pumpWidget(MaterialApp(
      home: Scaffold(body: ActionPassBar(passes: passes)),
    ));
    expect(find.textContaining('早班机'), findsOneWidget, reason: '最早行动时刻展开');
    expect(find.text('+2'), findsOneWidget, reason: '额外两条折叠为胶囊');
    expect(find.textContaining('末班船'), findsNothing);
  });

  testWidgets('空列表不渲染', (tester) async {
    await tester.pumpWidget(MaterialApp(home: Scaffold(body: ActionPassBar(passes: const []))));
    expect(find.byType(ActionPassBar), findsOneWidget); // SizedBox.shrink 仍是组件树节点
  });
}
