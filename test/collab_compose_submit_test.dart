import 'dart:io';

import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:path/path.dart' as p;
import 'package:sqflite_common_ffi/sqflite_ffi.dart';

import 'package:shiguang/collab/crypto/bundle_key_source.dart';
import 'package:shiguang/collab/crypto/key_store.dart';
import 'package:shiguang/collab/crypto/secret_store.dart';
import 'package:shiguang/collab/outbox.dart';
import 'package:shiguang/collab/propose.dart';
import 'package:shiguang/collab/relay/auth_api.dart' show RelayAuthException;
import 'package:shiguang/collab/relay/peers_api.dart';
import 'package:shiguang/data/db.dart';
import 'package:shiguang/ui/collab/compose_bundle_page.dart';

// 发起回执三态（ui-spec §0.4「发起回执 / 发起失败原因」）：
// ①装箱前失败＝箱里一条都没有，必须说「没发出去：原因」，不许谎称已留待发；
// ②装箱成功但没弹出去＝常驻调度会补发，说「已记下，联网后自动发出」；
// ③在线弹出成功＝说「已发出，对方会收到一条邀约」。
//
// 测试分层：回执判定/失败文案是纯函数直接单测；「装箱成败 ↔ 箱内条目」用编排级
// 测试钉死（propose 抛＝箱空，propose 成＝条目在）。widget 层只做文案接线，
// 端到端 widget 用例在 FakeAsync + sqflite ffi 下会挂起（测试环境怪癖，真机
// 不受影响），故不在此处展开——真机验收以屏面回执为准。

const _draft = BundleDraft(
  localId: 'local-1',
  kind: ProposalKind.directive,
  inviteeUids: ['u-b'],
  steps: [ProposalStepDraft(stepId: 's1', requiredUids: ['u-b'])],
);

/// 32B 假 SPK 公钥（本组用例不验加密，只验反馈口径）。
List<int> _fakeSpk() => List<int>.filled(32, 3);

Future<BundleProposalService> _proposer(
  Database db, {
  Future<List<int>> Function(String uid)? memberSpk,
}) async {
  final vault = KeyStore(MemorySecretStore());
  await vault.ensureReady();
  return BundleProposalService(
    keys: BundleKeySource(db, vault),
    outbox: OutboxRepository(db),
    memberSpk: memberSpk ?? (_) async => _fakeSpk(),
    newBundleKey: () => List<int>.filled(32, 7),
  );
}

DrainReport _report({
  bool stopped = true,
  List<CreatedBundle> created = const [],
}) =>
    DrainReport(
      sent: created.length,
      conflicts: const [],
      stoppedByNetwork: stopped,
      created: created,
    );

void main() {
  sqfliteFfiInit();
  databaseFactory = databaseFactoryFfi;

  late Directory tmp;
  late Database db;

  setUp(() async {
    tmp = await Directory.systemTemp.createTemp('collab_compose_submit_test');
    db = await openDatabase(
      p.join(tmp.path, 't.db'),
      version: 7,
      onCreate: (d, _) => Db.createAll(d),
    );
  });

  tearDown(() async {
    await db.close();
    await tmp.delete(recursive: true);
  });

  group('回执判定 composeSentOut', () {
    test('离线保序停止＝未发出，说「已记下」', () {
      expect(composeSentOut(_report(stopped: true)), isFalse);
    });
    test('在线发出且有包被服务端发号＝已发出', () {
      expect(
        composeSentOut(_report(stopped: false, created: const [
          CreatedBundle(
            entryId: 'ob-1',
            localBundleId: 'local-1',
            bundleId: 'srv-1',
            seqId: 1,
          ),
        ])),
        isTrue,
      );
    });
    test('在线但零发号（如箱内全被清）＝仍说「已记下」', () {
      expect(composeSentOut(_report(stopped: false)), isFalse);
    });
    test('没接 drainNow（report=null）＝说「已记下」', () {
      expect(composeSentOut(null), isFalse);
    });
  });

  group('失败原因白话 composeFailureText', () {
    test('取不到成员公钥（404）→ 指向对方重新登录', () {
      expect(composeFailureText(RelayPeersException(404)),
          '没发出去：取不到对方的通信公钥，让对方重新登录一次再约');
    });
    test('登录态过期（401）→ 登录引导', () {
      expect(
        composeFailureText(RelayAuthException(401)),
        '没发出去：登录态已过期，请到「设置 → 协同账号」重新登录',
      );
    });
    test('限频（429）→ 稍后再试', () {
      expect(composeFailureText(RelayPeersException(429)),
          '没发出去：操作太频繁，稍后再试');
    });
    test('纯网络层失败（DioException）→ 联网后再试', () {
      expect(
        composeFailureText(DioException(
          requestOptions: RequestOptions(path: '/x'),
          type: DioExceptionType.connectionTimeout,
        )),
        '没发出去：暂时连不上中继，联网后再试一次',
      );
    });
    test('未知异常兜底带异常摘要（release 无日志，屏面即现场）', () {
      final text = composeFailureText(StateError('K_bundle 必须是 32B'));
      expect(text, startsWith('没发出去：'));
      expect(text, contains('K_bundle 必须是 32B'));
    });
  });

  test('装箱前挂掉（取不到成员公钥）＝箱里一条都没有', () async {
    final proposer = await _proposer(db, memberSpk: (uid) async {
      throw DioException(
        requestOptions: RequestOptions(path: '/v1/users/$uid/prekey'),
        type: DioExceptionType.connectionTimeout,
      );
    });
    await expectLater(
      proposer.propose(meId: 'u-a', draft: _draft),
      throwsA(isA<DioException>()),
    );
    // 核心契约：未入箱。此时任何「已经留在待发里」的回执都是谎话。
    expect(await OutboxRepository(db).pending(), isEmpty);
  });

  test('装箱成功＝条目确在待发箱（drain 未接也不丢，等常驻调度补发）', () async {
    final proposer = await _proposer(db);
    await proposer.propose(meId: 'u-a', draft: _draft);
    final pending = await OutboxRepository(db).pending();
    expect(pending, hasLength(1));
    expect(pending.single.actionType, 'PROPOSE');
    expect(pending.single.bundleId, 'local-1'); // 服务端发号前仍是临时 id
  });
}
