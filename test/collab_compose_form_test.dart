import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:shiguang/collab/propose.dart';
import 'package:shiguang/collab/relay/peers_api.dart';
import 'package:shiguang/ui/collab/compose_bundle_page.dart';

// 发起表单的纯渲染验收（白话文案 + 交互回调），不碰 DB / crypto。

void main() {
  final contacts = [
    const FriendView(userId: 'u-b', status: 'accepted'),
    const FriendView(userId: 'u-c', status: 'accepted'),
  ];

  ComposeBundleForm form({
    List<FriendView>? list,
    bool loading = false,
    String? error,
    ProposalKind kind = ProposalKind.directive,
    List<ProposalStepDraft>? steps,
    Set<String> selected = const {'u-b'},
    void Function(String uid)? onToggleContact,
    void Function(ProposalKind)? onKindChanged,
    VoidCallback? onAddStep,
    Future<void> Function()? onSubmit,
    VoidCallback? onAddFriend,
  }) {
    final actualSteps = steps ??
        const [ProposalStepDraft(stepId: 's1', requiredUids: ['u-b'])];
    return ComposeBundleForm(
      loading: loading,
      error: error,
      contacts: list ?? contacts,
      selected: selected,
      kind: kind,
      steps: actualSteps,
      titleOf: (_) => TextEditingController(),
      sending: false,
      onToggleContact: onToggleContact ?? (_) {},
      onKindChanged: onKindChanged ?? (_) {},
      onToggleRequired: (int index, String uid) {},
      onPickSlot: (int index) async {},
      onAddStep: onAddStep ?? () {},
      onRemoveStep: (int index) {},
      onSubmit: onSubmit ?? () async {},
      onAddFriend: onAddFriend,
    );
  }

  Future<void> pump(WidgetTester t, Widget w) => t.pumpWidget(
        MaterialApp(home: Scaffold(body: w)),
      );

  testWidgets('三块表单即三件事：和谁一起 / 怎么定 / 做什么（大白话无黑话）',
      (tester) async {
    await pump(tester, form());
    expect(find.text('和谁一起'), findsOneWidget);
    expect(find.text('怎么定'), findsOneWidget);
    expect(find.text('要做什么'), findsOneWidget);
    expect(find.text('各司其职'), findsOneWidget);
    expect(find.text('大家投票'), findsOneWidget);
    expect(find.text('发出邀请'), findsOneWidget);
    // 内网术语不上屏
    expect(find.textContaining('Bundle'), findsNothing);
    expect(find.textContaining('proposal_type'), findsNothing);
  });

  testWidgets('还没有好友时给老实空态，不给空 chips', (tester) async {
    await pump(tester, form(list: const [], selected: const {}));
    expect(find.text('还没有互相确认的好友'), findsOneWidget);
    expect(find.byType(FilterChip), findsNothing);
    // 未接 onAddFriend 时不展示引导按钮（发起页未注入好友入口则不显示）
    expect(find.widgetWithText(TextButton, '去好友里加一位'), findsNothing);
  });

  testWidgets('零好友 + onAddFriend 时给「去好友里加一位」引导入口', (tester) async {
    var tapped = false;
    await pump(
      tester,
      form(list: const [], selected: const {}, onAddFriend: () => tapped = true),
    );
    final button = find.widgetWithText(TextButton, '去好友里加一位');
    expect(button, findsOneWidget);
    await tester.tap(button);
    expect(tapped, isTrue);
  });

  testWidgets('选中/取消成员、加步、发起都要有回响', (tester) async {
    final toggled = <String>[];
    var added = 0;
    var submitted = 0;
    await pump(
      tester,
      form(
        onToggleContact: toggled.add,
        onAddStep: () => added++,
        onSubmit: () async => submitted++,
      ),
    );

    await tester.tap(find.byKey(const Key('contact-u-c')));
    await tester.tap(find.byKey(const Key('compose-add-step')));
    await tester.tap(find.byKey(const Key('compose-submit')));
    await tester.pumpAndSettle();

    expect(toggled, ['u-c']);
    expect(added, 1);
    expect(submitted, 1);
  });

  testWidgets('切到大家投票：区块名从「要做什么」变「候选时间」', (tester) async {
    ProposalKind? got;
    await pump(tester, form(onKindChanged: (k) => got = k));
    await tester.tap(find.text('大家投票'));
    await tester.pumpAndSettle();
    expect(got, ProposalKind.consensus);

    await pump(
      tester,
      form(
        kind: ProposalKind.consensus,
        steps: const [ProposalStepDraft(stepId: 'slot-a')],
      ),
    );
    expect(find.text('候选时间'), findsOneWidget);
    expect(find.text('要做什么'), findsNothing);
  });

  testWidgets('中继没连上时把原因说出来，而不是转圈到底', (tester) async {
    await pump(
      tester,
      form(error: '暂时连不上中继，取不到好友名单', list: const []),
    );
    expect(find.text('暂时连不上中继，取不到好友名单'), findsOneWidget);
  });
}
