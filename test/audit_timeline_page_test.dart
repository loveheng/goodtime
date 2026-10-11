import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:shiguang/data/collab_repository.dart';
import 'package:shiguang/ui/collab/audit_timeline_page.dart';

/// 审计时间轴（§11.2 F 页）组件测试：加载/空态/结构化气泡 + 私密骨架提示 + 步骤便签。
void main() {
  StoredCollabEvent ev({
    required int seq,
    required String action,
    required String sender,
    int serverAtMs = 0,
  }) =>
      StoredCollabEvent(
        seqId: seq,
        actionType: action,
        senderId: sender,
        cipher: const [],
        keyVer: 1,
        clientMsgId: 'c$seq',
        serverAtMs: serverAtMs,
      );

  final events = [
    ev(seq: 1, action: 'PROPOSE', sender: 'u1', serverAtMs: 1700000000000),
    ev(seq: 2, action: 'ACCEPT', sender: 'u2', serverAtMs: 1700000060000),
    ev(seq: 3, action: 'REJECT', sender: 'u3', serverAtMs: 1700000120000),
  ];

  Future<void> pumpView(
    WidgetTester tester, {
    required bool loading,
    List<StoredCollabEvent> events = const [],
    required String initiatorId,
    String? currentUserId,
  }) async {
    await tester.pumpWidget(MaterialApp(
      home: Scaffold(
        body: AuditTimelineView(
          loading: loading,
          events: events,
          initiatorId: initiatorId,
          currentUserId: currentUserId,
        ),
      ),
    ));
    await tester.pumpAndSettle();
  }

  testWidgets('加载态显示占位', (tester) async {
    await pumpView(tester, loading: true, initiatorId: 'u1');
    expect(find.text('加载中…'), findsOneWidget);
  });

  testWidgets('空态：无动态引导文案', (tester) async {
    await pumpView(tester, loading: false, initiatorId: 'u1');
    expect(find.text('还没有动态'), findsOneWidget);
  });

  testWidgets('结构化气泡：发起 / 同意 标签与执行者', (tester) async {
    await pumpView(
      tester,
      loading: false,
      events: events,
      initiatorId: 'u1',
      currentUserId: 'u9', // 旁观者，不与任一 sender 撞车
    );
    expect(find.text('u1 发起了方案'), findsOneWidget);
    expect(find.text('u2 同意了这一步'), findsOneWidget);
  });

  testWidgets('异议气泡：非执行者视角附私密骨架提示', (tester) async {
    await pumpView(
      tester,
      loading: false,
      events: events,
      initiatorId: 'u1',
      currentUserId: 'u2', // 非异议执行者 u3
    );
    expect(find.text('u3 提出了异议'), findsOneWidget);
    expect(find.text('已私密反馈发起人，等待调整'), findsOneWidget);
  });

  testWidgets('异议气泡：本人（执行者）视角不显示私密骨架提示',
      (tester) async {
    await pumpView(
      tester,
      loading: false,
      events: events,
      initiatorId: 'u1',
      currentUserId: 'u3', // 异议本人
    );
    expect(find.text('你 提出了异议'), findsOneWidget);
    expect(find.text('已私密反馈发起人，等待调整'), findsNothing);
  });

  testWidgets('步骤便签抽屉：选步骤 + 输入 → 回调带 stepId/note',
      (tester) async {
    late String gotStep;
    late String gotNote;
    const steps = [
      CollabStep(
        bundleId: 'b1',
        id: 's1',
        requiredUids: ['u1'],
        threshold: 1,
        isOptional: false,
        status: 'PENDING',
        votes: {},
        sort: 1,
      ),
      CollabStep(
        bundleId: 'b1',
        id: 's2',
        requiredUids: ['u1'],
        threshold: 1,
        isOptional: false,
        status: 'PENDING',
        votes: {},
        sort: 2,
      ),
    ];
    await tester.pumpWidget(MaterialApp(
      home: Scaffold(
        body: StepNoteSheet(
          steps: steps,
          onConfirm: (s, n) async {
            gotStep = s;
            gotNote = n;
          },
        ),
      ),
    ));
    await tester.pumpAndSettle();

    await tester.tap(find.text('步骤 s2'));
    await tester.pumpAndSettle();
    final chip = tester.widget<ChoiceChip>(find
        .ancestor(
            of: find.text('步骤 s2'), matching: find.byType(ChoiceChip))
        .first);
    expect(chip.selected, isTrue); // 步骤已选中
    await tester.enterText(find.byType(TextField), '记得带门禁卡');
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(FilledButton, '保存便签'));
    await tester.pumpAndSettle();

    expect(gotStep, 's2');
    expect(gotNote, '记得带门禁卡');
  });

  testWidgets('步骤便签抽屉：未选步骤时保存按钮禁用', (tester) async {
    await tester.pumpWidget(MaterialApp(
      home: Scaffold(
        body: StepNoteSheet(
          steps: const [],
          onConfirm: (_, _) async {},
        ),
      ),
    ));
    await tester.pumpAndSettle();
    final save = tester.widget<FilledButton>(
        find.widgetWithText(FilledButton, '保存便签'));
    expect(save.onPressed, isNull);
  });
}
