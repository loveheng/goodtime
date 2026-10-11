import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:shiguang/collab/pipeline.dart';
import 'package:shiguang/collab/proto/relay_protocol.pb.dart';
import 'package:shiguang/collab/relay/auth_api.dart';
import 'package:shiguang/collab/relay/sync_api.dart';
import 'package:shiguang/collab/wake_puller.dart';

/// Phase 3.4 唤醒拉取验收（collab-implementation §3.4 / 协同设计 §2.2 §10.3-2）：
/// Wake Push 零明文载荷唤醒 → `/sync` 分页增量 → **喂入 SingleConsumerPipeline
/// 的 HTTP 入口**，与 WSS 实时推送共用去重排序——弱网恢复两路同涌不乱序不重复。

/// 复刻 Go relay `internal/httpapi/sync.go`：增量分页（limit 切页）+
/// `has_more = events 非空`（与服务端既有口径一致），可注入 500 故障。
class MockSyncRelay {
  HttpServer? _server;

  /// bundle → 事件行（seq 升序）。
  final Map<String, List<Map<String, Object?>>> bundles = {};
  int limit = 2; // 强制多页
  int failNext = 0; // >0：接下来 N 次回 500
  int requests = 0;

  Future<String> start() async {
    _server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    _server!.listen(_handle);
    return 'http://127.0.0.1:${_server!.port}';
  }

  Future<void> close() => _server!.close(force: true);

  void addEvents(String bundle, int from, int to) {
    final rows = bundles.putIfAbsent(bundle, () => []);
    for (var s = from; s <= to; s++) {
      rows.add({
        'seq_id': s,
        'action_type': s.isEven ? 'ACCEPT' : 'VOTE',
        'sender_id': 'peer-$s',
        'cipher': base64Encode(utf8.encode('pl-$s')),
        'topology_ref': '',
        'created_at': 1700000000 + s,
      });
    }
  }

  Future<void> _handle(HttpRequest req) async {
    requests++;
    if (failNext > 0) {
      failNext--;
      req.response.statusCode = 500;
      req.response.write(
          jsonEncode({'error_code': 'INTERNAL', 'message': 'boom'}));
      await req.response.close();
      return;
    }
    final q = req.uri.queryParameters;
    final bundle = q['bundle_id'] ?? '';
    final since = int.tryParse(q['since_seq'] ?? '0') ?? 0;
    final lim = int.tryParse(q['limit'] ?? '') ?? limit;
    final all = bundles[bundle] ?? const [];
    final page = all.where((e) => (e['seq_id'] as int) > since).take(lim);
    // latest_seq 语义对齐真 sync.go：本页事件内的最大 seq（非全量最大）
    var latest = 0;
    for (final e in page) {
      if ((e['seq_id'] as int) > latest) latest = e['seq_id'] as int;
    }
    req.response.headers.contentType = ContentType.json;
    req.response.write(jsonEncode({
      'bundle_id': bundle,
      'events': page.toList(),
      'latest_seq': latest,
      'has_more': page.isNotEmpty,
    }));
    await req.response.close();
  }
}

/// 轮询等待条件成立，超时抛 [TimeoutException]。
Future<void> _waitUntil(bool Function() cond,
    {Duration timeout = const Duration(seconds: 8)}) async {
  final end = DateTime.now().add(timeout);
  while (!cond()) {
    if (DateTime.now().isAfter(end)) throw TimeoutException('wake test');
    await Future<void>.delayed(const Duration(milliseconds: 20));
  }
}

void main() {
  late MockSyncRelay relay;
  late String baseUrl;
  late SingleConsumerPipeline pipeline;

  setUp(() async {
    relay = MockSyncRelay();
    baseUrl = await relay.start();
    pipeline = SingleConsumerPipeline();
  });

  tearDown(() async {
    await pipeline.close();
    await relay.close();
  });

  WakePuller puller() => WakePuller(
        pipeline: pipeline,
        pull: (bundle, since) =>
            fetchSyncPage(Dio(), baseUrl, 'tok-1', bundle, since,
                limit: relay.limit),
      );

  test('零明文载荷唤醒 → 分页拉取走单管道：applied 严格升序无缺口', () async {
    relay.addEvents('b1', 1, 5);
    expect(relay.limit, 2); // 5 事件 → 至少 3 页

    final got = <int>[];
    pipeline.applied.listen((e) {
      got.add(e.seqId);
      expect(e.source, 'http'); // 唤醒拉取走 HTTP 入口
    });

    final pushed =
        await puller().onWake(const WakePayload(
      pushType: 'BUNDLE_EVENT_WAKE',
      bundleId: 'b1',
      latestSeqId: 5,
    ));

    expect(pushed, 5);
    await _waitUntil(() => got.length == 5);
    expect(got, [1, 2, 3, 4, 5]); // 单管道去重排序后连续 flush
  });

  test('双路同涌去重：WSS 已收前缀 + 唤醒拉全量重叠 → 只应用一次', () async {
    relay.addEvents('b1', 1, 5);

    final got = <int>[];
    pipeline.applied.listen((e) => got.add(e.seqId));

    // WSS 实时路先到 seq 1、2（弱网恢复瞬间典型时序）
    pipeline.pushWss(const CollabInboundEvent(
        bundleId: 'b1', seqId: 1, action: ActionType.ACCEPT, source: 'wss'));
    pipeline.pushWss(const CollabInboundEvent(
        bundleId: 'b1', seqId: 2, action: ActionType.VOTE, source: 'wss'));
    await _waitUntil(() => got.length == 2);

    // 唤醒拉取与 WSS 完全重叠（游标缺失场景）
    await puller().onWake(const WakePayload(
        pushType: 'BUNDLE_EVENT_WAKE', bundleId: 'b1', latestSeqId: 5));

    await _waitUntil(() => got.length == 5);
    expect(got, [1, 2, 3, 4, 5]); // 重叠的 1、2 被管道去重，绝不重复应用
  });

  test('增量推进：二次唤醒只拉新事件（内部游标单调）', () async {
    final got = <int>[];
    pipeline.applied.listen((e) => got.add(e.seqId));

    relay.addEvents('b1', 1, 5);
    final wake = puller();
    final pushed1 = await wake.onWake(const WakePayload(
        pushType: 'BUNDLE_EVENT_WAKE', bundleId: 'b1', latestSeqId: 5));
    expect(pushed1, 5);
    await _waitUntil(() => got.length == 5);
    expect(got, [1, 2, 3, 4, 5]);

    relay.addEvents('b1', 6, 7);
    final pushed2 = await wake.onWake(const WakePayload(
        pushType: 'BUNDLE_EVENT_WAKE', bundleId: 'b1', latestSeqId: 7));

    expect(pushed2, 7);
    await _waitUntil(() => got.length == 7);
    expect(got, [1, 2, 3, 4, 5, 6, 7]); // 全程无重复：二次唤醒只补 6、7
  });

  test('拉取故障不上管道：500 抛 RelayAuthException，恢复后重 wake 成功', () async {
    relay.addEvents('b1', 1, 3);
    relay.failNext = 1;

    final got = <int>[];
    pipeline.applied.listen((e) => got.add(e.seqId));

    await expectLater(
      puller().onWake(const WakePayload(
          pushType: 'BUNDLE_EVENT_WAKE', bundleId: 'b1', latestSeqId: 3)),
      throwsA(isA<RelayAuthException>()),
    );
    await Future<void>.delayed(const Duration(milliseconds: 50));
    expect(got, isEmpty); // 故障轮零污染

    final pushed = await puller().onWake(const WakePayload(
        pushType: 'BUNDLE_EVENT_WAKE', bundleId: 'b1', latestSeqId: 3));
    expect(pushed, 3);
    await _waitUntil(() => got.length == 3);
    expect(got, [1, 2, 3]);
  });

  test('载荷缺 bundle_id 即拒（§10.3-2 零明文契约三字段缺一不可）', () {
    expect(() => WakePayload.fromMap({'push_type': 'BUNDLE_EVENT_WAKE'}),
        throwsFormatException);
  });
}
