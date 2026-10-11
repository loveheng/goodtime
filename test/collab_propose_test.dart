import 'dart:convert';
import 'dart:io';

import 'package:cryptography/cryptography.dart';
import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:path/path.dart' as p;
import 'package:sqflite_common_ffi/sqflite_ffi.dart';

import 'package:shiguang/collab/crypto/bundle_key_source.dart';
import 'package:shiguang/collab/crypto/key_store.dart';
import 'package:shiguang/collab/crypto/secret_store.dart';
import 'package:shiguang/collab/fsm.dart';
import 'package:shiguang/collab/outbox.dart';
import 'package:shiguang/collab/propose.dart';
import 'package:shiguang/collab/proto/relay_protocol.pb.dart' show ActionType;
import 'package:shiguang/collab/relay/action_api.dart';
import 'package:shiguang/collab/replay.dart' show signalFromPayload;
import 'package:shiguang/data/collab_repository.dart';
import 'package:shiguang/data/db.dart';

// Phase 4.3「本地发起 bundle」验收（collab-schedule-draft §1.7/§4.2/§5.3）：
// ①服务端可见面只有结构四件套（内容零泄露）；②payload 与 FSM 信号严格互逆；
// ③成员信封只有本人 SPK 解得开；④入箱 → 抬字段 → 认祖归宗全链路。

KeyStore _lightKeyStore() => KeyStore(MemorySecretStore());

/// 发件箱幂等键的稳定序（测试用）：真随机后缀会让同一毫秒的两条条目排序抖动。
String Function() _seqIds() {
  var n = 0;
  return () => 'ob-${n++}';
}

BundleDraft _directiveDraft({int invitees = 1}) => BundleDraft(
      localId: 'local-1',
      kind: ProposalKind.directive,
      inviteeUids: ['u-b', 'u-c'].take(invitees).toList(),
      steps: const [
        ProposalStepDraft(
          stepId: 's1',
          title: '场地踏勘',
          requiredUids: ['u-b'],
          startsAtMs: 1000,
          endsAtMs: 2000,
        ),
        ProposalStepDraft(stepId: 's2', requiredUids: [], isOptional: true),
      ],
    );

/// 复刻 relay 受理面：记录请求逐字段，按脚本队列回响应。
class MockActionRelay {
  HttpServer? _server;
  final List<Map<String, Object?>> requests = [];
  final List<(int, Object?)> script = [];

  Future<String> start() async {
    _server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    _server!.listen(_handle);
    return 'http://127.0.0.1:${_server!.port}';
  }

  Future<void> close() => _server!.close(force: true);

  Future<void> _handle(HttpRequest req) async {
    final body = await utf8.decoder.bind(req).join();
    final j = jsonDecode(body) as Map<String, dynamic>;
    requests.add(Map<String, Object?>.from(j));
    final (code, respBody) =
        script.isEmpty ? (200, {'seq_id': 1}) : script.removeAt(0);
    req.response.statusCode = code;
    req.response.headers.contentType = ContentType.json;
    req.response.write(jsonEncode(respBody));
    await req.response.close();
  }
}

void main() {
  sqfliteFfiInit();
  databaseFactory = databaseFactoryFfi;

  late Directory tmp;
  late Database db;

  setUp(() async {
    tmp = await Directory.systemTemp.createTemp('collab_propose_test');
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

  group('拓扑与载荷', () {
    test('服务端可见面只有结构四件套：标题/时段一律不露', () {
      final topo = serverTopologyOf(_directiveDraft());
      final steps = topo['steps'] as List;
      expect(steps.length, 2);
      for (final s in steps) {
        expect((s as Map).keys.toSet(),
            {'step_id', 'required_uids', 'threshold', 'is_optional'});
      }
      expect(jsonEncode(topo), isNot(contains('场地踏勘')));
      expect((steps[0] as Map)['threshold'], 1);
      expect((steps[1] as Map)['threshold'], 0);
    });

    test('Directive payload → FSM 信号：骨架/参与者/TTL 逐项互逆', () {
      const me = 'u-a';
      final payload = proposalPayloadOf(
        initiatorId: me,
        draft: _directiveDraft(),
        nowMs: 5000,
      );
      final signal = signalFromPayload(
        ActionType.PROPOSE,
        payload,
        senderId: me,
        seqId: 1,
      );
      expect(signal.uids.toSet(), {'u-a', 'u-b'});
      expect(signal.skeleton.map((s) => s.stepId), ['s1', 's2']);
      expect(signal.skeleton.first.requiredUids, ['u-b']);
      expect(signal.skeleton.first.threshold, 1);
      expect(signal.skeleton.last.isOptional, isTrue);
      expect(signal.expiresAtMs, 5000 + kProposalDefaultTtlMs);

      final state = reduceEvent(CollabBundleState.empty('b'), signal);
      expect(state.status, BundleFsmStatus.proposed);
      expect(state.steps.keys.toSet(), {'s1', 's2'});
    });

    test('Consensus payload：initial_slots 与 quorum 被 FSM 认领', () {
      final draft = BundleDraft(
        localId: 'local-c',
        kind: ProposalKind.consensus,
        inviteeUids: const ['u-b'],
        quorum: 2,
        steps: const [
          ProposalStepDraft(stepId: 'slot-a', title: '周六下午'),
          ProposalStepDraft(stepId: 'slot-b', title: '周日上午'),
        ],
      );
      final payload =
          proposalPayloadOf(initiatorId: 'u-a', draft: draft, nowMs: 1000);
      final signal = signalFromPayload(
        ActionType.PROPOSE,
        payload,
        senderId: 'u-a',
        seqId: 1,
      );
      expect(signal.initialSlots, ['slot-a', 'slot-b']);
      expect(signal.quorum, 2);
      final state = reduceEvent(
        CollabBundleState.empty('b', proposalType: CollabProposalType.consensus),
        signal,
      );
      expect(state.candidateSlots, ['slot-a', 'slot-b']);
      expect(state.quorum, 2);
    });
  });

  group('成员密钥信封', () {
    test('逐成员封包：本人 SPK 解得开、他人 key 解不开', () async {
      final bob = await X25519().newKeyPair();
      final carol = await X25519().newKeyPair();
      const key = '0123456789abcdef0123456789abcdef';
      final envelope = await wrapBundleKeyForMember(
        bundleKey: utf8.encode(key),
        keyVer: 1,
        recipientUserId: 'u-b',
        memberSpkPub: (await bob.extractPublicKey()).bytes,
      );
      expect(envelope['recipient_user_id'], 'u-b');
      expect(envelope['key_ver'], 1);

      final opened = await openBundleKeyEnvelope(
        envelope: envelope,
        mySignedPreKey: bob,
      );
      expect(utf8.decode(opened), key);

      // 换个人：DH 得不出同一把密钥 → GCM MAC 校验失败（必抛，绝不静默给错密钥）
      await expectLater(
        openBundleKeyEnvelope(envelope: envelope, mySignedPreKey: carol),
        throwsA(anything),
      );
    });

    test('非 X25519 公钥直接拒绝（坐标系铁律）', () async {
      await expectLater(
        wrapBundleKeyForMember(
          bundleKey: utf8.encode('x'),
          keyVer: 1,
          recipientUserId: 'u-b',
          memberSpkPub: List<int>.filled(10, 1),
        ),
        throwsArgumentError,
      );
    });
  });

  group('草稿核验', () {
    test('四类不合法草稿被拦住：无成员/超配额/重复 id/候选超上限', () {
      expect(
        () => validateDraft(BundleDraft(
          localId: 'l',
          kind: ProposalKind.directive,
          inviteeUids: const [],
          steps: const [ProposalStepDraft(stepId: 's1')],
        )),
        throwsArgumentError,
      );
      expect(
        () => validateDraft(BundleDraft(
          localId: 'l',
          kind: ProposalKind.directive,
          inviteeUids: List.generate(16, (i) => 'u$i'), // 17 人 > 上限 16
          steps: const [ProposalStepDraft(stepId: 's1')],
        )),
        throwsArgumentError,
      );
      expect(
        () => validateDraft(BundleDraft(
          localId: 'l',
          kind: ProposalKind.directive,
          inviteeUids: const ['u-b'],
          steps: const [
            ProposalStepDraft(stepId: 's1'),
            ProposalStepDraft(stepId: 's1'),
          ],
        )),
        throwsArgumentError,
      );
      expect(
        () => validateDraft(BundleDraft(
          localId: 'l',
          kind: ProposalKind.consensus,
          inviteeUids: const ['u-b'],
          steps: List.generate(9, (i) => ProposalStepDraft(stepId: 's$i')),
        )),
        throwsArgumentError,
      );
      // 15 名被邀 + 发起人 = 16，恰好在配额线上
      validateDraft(BundleDraft(
        localId: 'l',
        kind: ProposalKind.directive,
        inviteeUids: List.generate(15, (i) => 'u$i'),
        steps: const [ProposalStepDraft(stepId: 's1')],
      ));
    });
  });

  group('本地发起 + 认祖归宗', () {
    Future<
        ({
          BundleProposal result,
          KeyStore vault,
          Map<String, SimpleKeyPair> spks,
        })> proposed(Database database,
            {int invitees = 2, String Function()? newId}) async {
      final vault = _lightKeyStore();
      await vault.ensureReady();
      final keys = BundleKeySource(database, vault);
      final outbox = OutboxRepository(database, newId: newId ?? _seqIds());
      final spks = {
        'u-b': await X25519().newKeyPair(),
        'u-c': await X25519().newKeyPair(),
      };
      final bundleKey = List<int>.filled(32, 7);
      final service = BundleProposalService(
        keys: keys,
        outbox: outbox,
        memberSpk: (uid) async => (await spks[uid]!.extractPublicKey()).bytes,
        newBundleKey: () => bundleKey,
      );
      final result = await service.propose(
        meId: 'u-a',
        draft: _directiveDraft(invitees: invitees),
      );
      return (result: result, vault: vault, spks: spks);
    }

    test('propose：K_bundle 落户 + 逐成员封信封 + 入箱一条自证 PROPOSE', () async {
      final env = await proposed(db);
      final r = env.result;
      expect(r.keyVer, 1);
      expect(r.envelopeCount, 2);

      expect(await BundleKeySource(db, env.vault)(r.draft.localId, 1),
          List<int>.filled(32, 7));

      final pending = await OutboxRepository(db).pending();
      expect(pending.length, 1);
      final entry = pending.single;
      expect(entry.actionType, 'PROPOSE');
      expect(entry.bundleId, 'local-1'); // 服务端发号前只有本地临时 id
      expect(entry.payload['participants'], ['u-a', 'u-b', 'u-c']);
      expect((entry.payload['envelopes'] as List).length, 2);
      expect(entry.payload['topology'], isNotNull);

      final first =
          (entry.payload['envelopes'] as List).first as Map<String, Object?>;
      expect(first['recipient_user_id'], 'u-b');
      expect(
        await openBundleKeyEnvelope(
          envelope: first,
          mySignedPreKey: env.spks['u-b']!,
        ),
        List<int>.filled(32, 7),
      );
    });

    test('传输层：PROPOSE 抬出半盲骨架字段 + bundle_id 留空 + 服务端号码回到本地',
        () async {
      final env = await proposed(db, invitees: 1);
      final relay = MockActionRelay();
      final baseUrl = await relay.start();
      addTearDown(relay.close);

      final keys = BundleKeySource(db, env.vault);
      final transport = HttpOutboxTransport(
        dio: Dio(),
        baseUrl: baseUrl,
        accessToken: () async => 'tok',
        bundleKey: (b, v) async => (await keys(b, v))!,
      );
      relay.script.add((201, {
        'bundle_id': 'srv-42',
        'seq_id': 1,
        'status': 'PROPOSED',
      }));

      final outcome = await transport.send(env.result.entry);
      expect(outcome, isA<SendProposed>());
      expect((outcome as SendProposed).bundleId, 'srv-42');
      expect(outcome.seqId, 1);

      final body = relay.requests.single;
      expect(body['bundle_id'], '', reason: 'bundle_id 留空＝服务端建包分支判据');
      expect(body['action'], 'PROPOSE');
      expect(body['proposal_type'], 'DIRECTIVE');
      expect(body['participant_uids'], ['u-a', 'u-b']);
      // Directive 不带 quorum（各步骤各用自己的 M-of-N 门限，包级票数无意义）
      expect(body['quorum'], isNull);
      expect((body['member_key_envelopes'] as List).length, 1);
      expect(((body['topology'] as Map)['steps'] as List).length, 2);
      final expMs = env.result.entry.payload['expires_at_ms'] as int;
      expect(body['expires_at'], expMs ~/ 1000);
    });

    test('既有动作请求体不因发起切片变胖：仍是三字段', () async {
      final relay = MockActionRelay();
      final baseUrl = await relay.start();
      addTearDown(relay.close);
      final transport = HttpOutboxTransport(
        dio: Dio(),
        baseUrl: baseUrl,
        accessToken: () async => 'tok',
        bundleKey: (b, v) async =>
            utf8.encode('0123456789abcdef0123456789abcdef'),
      );
      final outbox = OutboxRepository(db, newId: _seqIds());
      await outbox.enqueue(
        bundleId: 'b-1',
        actionType: 'ACCEPT',
        payload: {'step_id': 's1'},
      );
      expect(
          await transport.send((await outbox.pending()).single),
          isA<SendAccepted>());
      expect(
          relay.requests.single.keys.toSet(), {'bundle_id', 'action', 'cipher'});
    });

    test('弹出即认祖归宗：密钥迁号 + 后续操作改绑 + 包头落地（且幂等）', () async {
      final ids = _seqIds();
      final env = await proposed(db, invitees: 1, newId: ids);
      final relay = MockActionRelay();
      final baseUrl = await relay.start();
      addTearDown(relay.close);

      final keys = BundleKeySource(db, env.vault);
      final outbox = OutboxRepository(db, newId: ids);
      // 窗口期内随手又入了一条后续操作（改期），仍挂在临时本地 id 上
      await outbox.enqueue(
        bundleId: 'local-1',
        actionType: 'PATCH_TIME',
        payload: {'step_id': 's1', 'starts_at_ms': 99},
      );
      relay.script
        ..add((201, {'bundle_id': 'srv-7', 'seq_id': 1}))
        ..add((200, {'bundle_id': 'srv-7', 'seq_id': 2}));

      final reconciler = PendingProposalReconciler(
        bundles: CollabBundleRepository(db),
        keys: keys,
        outbox: outbox,
      );
      final drainer = OutboxDrainer(
        outbox,
        HttpOutboxTransport(
          dio: Dio(),
          baseUrl: baseUrl,
          accessToken: () async => 'tok',
          bundleKey: (b, v) async => (await keys(b, v))!,
        ),
        onBundleCreated: reconciler.call,
      );

      final report = await drainer.drain();
      expect(report.created.single.bundleId, 'srv-7');
      expect(report.sent, 2);

      // ①密钥改挂服务端号，临时号绝不并存
      final ring = await db.query('collab_key_ring');
      expect(ring.map((r) => r['bundle_id']).toSet(), {'srv-7'});
      expect(await keys('srv-7', 1), List<int>.filled(32, 7));

      // ②改绑生效：后续操作是拿服务端号发的（改绑失效则这里恒为 local-1 → 404）
      expect(relay.requests.last['action'], 'PATCH_TIME');
      expect(relay.requests.last['bundle_id'], 'srv-7');

      // ③包头落地，供水位续拉与列表展示
      final head = await CollabBundleRepository(db).bundleHeader('srv-7');
      expect(head, isNotNull);
      expect(head!.participants, ['u-a', 'u-b']);
      expect(head.proposalType, 'DIRECTIVE');
      expect(head.initiatorId, 'u-a');

      // ④幂等：服务端重放同一 client_msg_id 再次走到这里，重复执行零副作用
      await reconciler.call(env.result.entry, report.created.single);
      expect((await db.query('collab_key_ring')).length, 1);
      expect((await db.query('collab_bundles')).length, 1);
    });

    test('离线发起不炸：那条 PROPOSE 留在箱里等联网（§11.1 弱网不丢操作）', () async {
      final ids = _seqIds();
      final env = await proposed(db, invitees: 1, newId: ids);
      final dead = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
      final deadPort = dead.port;
      await dead.close();

      final keys = BundleKeySource(db, env.vault);
      final outbox = OutboxRepository(db, newId: ids);
      final reconciler = PendingProposalReconciler(
        bundles: CollabBundleRepository(db),
        keys: keys,
        outbox: outbox,
      );
      final drainer = OutboxDrainer(
        outbox,
        HttpOutboxTransport(
          dio: Dio(),
          baseUrl: 'http://127.0.0.1:$deadPort',
          accessToken: () async => 'tok',
          bundleKey: (b, v) async => (await keys(b, v))!,
        ),
        onBundleCreated: reconciler.call,
      );

      final report = await drainer.drain();
      expect(report.stoppedByNetwork, isTrue);
      final still = await outbox.pending();
      expect(still.single.actionType, 'PROPOSE');
      expect(still.single.attempts, 1);
      // 未发出绝不能先行认祖归宗（临时密钥保留，重连后仍可正常发）
      expect(await keys('local-1', 1), List<int>.filled(32, 7));
    });
  });
}
