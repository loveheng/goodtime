import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'dart:convert';

import 'package:shiguang/collab/account.dart';
import 'package:shiguang/collab/crypto/key_store.dart';
import 'package:shiguang/collab/crypto/secret_store.dart';
import 'package:shiguang/collab/identity_store.dart';
import 'package:shiguang/ui/account_page.dart';

// 协同账号页流程（零网络——testWidgets 环境拦截真实 HTTP，账号网络流由
// collab_account_session_test 在纯 Dart 环境覆盖；本文件只验 UI 状态机与文案）。

void main() {
  /// 空会话（未登录）：表单态测试用。
  AccountSession blankSession() => AccountSession(
        dio: Dio(),
        baseUrl: 'http://127.0.0.1:1',
        keyStore: KeyStore(MemorySecretStore()),
        identityStore: MemoryIdentityStore(),
        secrets: MemorySecretStore(),
      );

  /// 已持久化登录凭据的会话：页面 initState 的 restore() 读到即 loggedIn
  ///（restore 链路一并覆盖）。
  Future<AccountSession> loggedInSession() async {
    final secrets = MemorySecretStore();
    await secrets.write(
      'collab.account.session',
      jsonEncode({
        'username': 'alice',
        'user_id': 'u-a',
        'access_token': 'tok',
        'refresh_token': 'r',
        'access_expires_at':
            DateTime.now().millisecondsSinceEpoch ~/ 1000 + 7200,
      }),
    );
    return AccountSession(
      dio: Dio(),
      baseUrl: 'http://127.0.0.1:1',
      keyStore: KeyStore(MemorySecretStore()),
      identityStore: MemoryIdentityStore(),
      secrets: secrets,
    );
  }

  testWidgets('弱密码被端侧校验拦下（零网络、原表单保留）', (tester) async {
    final pageSession = blankSession();
    await tester.pumpWidget(
        MaterialApp(home: AccountPage(session: pageSession)));
    await tester.pump();

    await tester.enterText(find.byKey(const Key('account-username')), 'alice');
    await tester.enterText(find.byKey(const Key('account-password')), 'short');
    await tester.tap(find.byKey(const Key('account-submit')));
    await tester.pump();

    expect(find.text('密码至少 8 位'), findsOneWidget);
    expect(find.byKey(const Key('account-submit')), findsOneWidget,
        reason: '仍在表单，没有进已登录视图');
    expect(pageSession.isLoggedIn, isFalse);
  });

  testWidgets('注册模式两次密码不一致被拦下', (tester) async {
    final pageSession = blankSession();
    await tester.pumpWidget(
        MaterialApp(home: AccountPage(session: pageSession)));
    await tester.pump();

    await tester.tap(find.text('注册'));
    await tester.pump();
    await tester.enterText(find.byKey(const Key('account-username')), 'alice');
    await tester.enterText(
        find.byKey(const Key('account-password')), 'password-1');
    await tester.enterText(
        find.byKey(const Key('account-confirm')), 'password-2');
    await tester.tap(find.byKey(const Key('account-submit')));
    await tester.pump();

    expect(find.text('两次输入的密码不一致'), findsOneWidget);
    expect(pageSession.isLoggedIn, isFalse);
  });

  testWidgets('已登录视图：账号卡 + 改密/退出入口；确认后回表单', (tester) async {
    final pageSession = await loggedInSession();
    await tester.pumpWidget(
        MaterialApp(home: AccountPage(session: pageSession)));
    await tester.pump();

    expect(find.text('已登录：alice'), findsOneWidget);
    expect(find.byKey(const Key('account-logout')), findsOneWidget);
    expect(find.byKey(const Key('account-change-password')), findsOneWidget);

    // 退出登录：确认弹窗说明「数据保留」→ 确认后回表单（logout 零网络）
    await tester.tap(find.byKey(const Key('account-logout')));
    await tester.pump();
    expect(find.text('只退出账号，本地日程与协同数据都保留'), findsOneWidget);
    await tester.tap(find.widgetWithText(FilledButton, '退出'));
    await tester.pump();
    expect(find.byKey(const Key('account-submit')), findsOneWidget);
    expect(pageSession.isLoggedIn, isFalse);
    expect(pageSession.username, isNull);
  });
}
