import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:fixnum/fixnum.dart';

import 'package:shiguang/collab/proto/relay_protocol.pb.dart';
import 'package:shiguang/collab/pipeline.dart';
import 'package:shiguang/collab/relay/wss_client.dart';

/// 轮询等待条件成立，超时抛 [TimeoutException]。
Future<void> _waitUntil(bool Function() cond,
    {Duration timeout = const Duration(seconds: 5)}) async {
  final end = DateTime.now().add(timeout);
  while (!cond()) {
    if (DateTime.now().isAfter(end)) {
      throw TimeoutException('条件未在 ${timeout.inSeconds}s 内满足');
    }
    await Future.delayed(const Duration(milliseconds: 10));
  }
}

CollabInboundEvent ev(String bundle, int seq, String source) => CollabInboundEvent(
      bundleId: bundle,
      seqId: seq,
      action: ActionType.PING,
      source: source,
    );

void main() {
  test('两路并发注入同包：去重 + 严格升序、无缺口', () async {
    final p = SingleConsumerPipeline();
    final applied = <CollabInboundEvent>[];
    p.applied.listen(applied.add);

    // WSS 推 1..60，HTTP /sync 补拉 41..100（重叠 41..60 必须被去重）
    Future<void> wss() async {
      for (var i = 1; i <= 60; i++) {
        p.pushWss(ev('b1', i, 'wss'));
        await Future.delayed(Duration(microseconds: (i % 4) * 8));
      }
    }

    Future<void> http() async {
      for (var i = 41; i <= 100; i++) {
        p.pushHttp(ev('b1', i, 'http'));
        await Future.delayed(Duration(microseconds: (i % 4) * 5));
      }
    }

    await Future.wait([wss(), http()]);
    await _waitUntil(() => applied.length >= 100);

    // 单一消费者：峰值并发恒为 1
    expect(p.peakConcurrent, 1);
    // 去重后恰 100 条，严格升序、无缺口、无重复
    expect(applied.length, 100);
    final seqs = applied.map((e) => e.seqId).toList();
    expect(seqs, equals([for (var i = 1; i <= 100; i++) i]));
    // 来源混合（证明两路都汇入同一条管道）
    expect(applied.any((e) => e.source == 'wss'), isTrue);
    expect(applied.any((e) => e.source == 'http'), isTrue);
    await p.close();
  });

  test('不同包各自有序、互不污染', () async {
    final p = SingleConsumerPipeline();
    final applied = <CollabInboundEvent>[];
    p.applied.listen(applied.add);

    Future<void> wss() async {
      for (var i = 1; i <= 30; i++) {
        p.pushWss(ev('b1', i, 'wss'));
        await Future.delayed(Duration(microseconds: (i % 3) * 7));
      }
    }

    Future<void> http() async {
      for (var i = 1; i <= 30; i++) {
        p.pushHttp(ev('b2', i, 'http'));
        await Future.delayed(Duration(microseconds: (i % 3) * 11));
      }
    }

    await Future.wait([wss(), http()]);
    await _waitUntil(() => applied.length >= 60);

    expect(p.peakConcurrent, 1);
    final b1 = applied.where((e) => e.bundleId == 'b1').map((e) => e.seqId).toList();
    final b2 = applied.where((e) => e.bundleId == 'b2').map((e) => e.seqId).toList();
    expect(b1, equals([for (var i = 1; i <= 30; i++) i]));
    expect(b2, equals([for (var i = 1; i <= 30; i++) i]));
    await p.close();
  });

  test('重放（replay）被去重，只应用一次', () async {
    final p = SingleConsumerPipeline();
    final applied = <CollabInboundEvent>[];
    p.applied.listen(applied.add);

    for (var i = 1; i <= 10; i++) {
      p.pushWss(ev('b1', i, 'wss'));
    }
    // 重放其中 5、7（模拟弱网重推）
    p.pushWss(ev('b1', 5, 'wss'));
    p.pushHttp(ev('b1', 7, 'http'));

    await _waitUntil(() => applied.length >= 10);
    expect(applied.length, 10);
    expect(applied.map((e) => e.seqId).toList(),
        equals([for (var i = 1; i <= 10; i++) i]));
    await p.close();
  });

  test('attachWss：WSS ServerFrame 经管道翻译为事件并应用', () async {
    final p = SingleConsumerPipeline();
    final applied = <CollabInboundEvent>[];
    p.applied.listen(applied.add);

    // 模拟 WssClient 帧（无需真连接）：连续 seq 1..3 才能触发 contiguous flush
    final frames = [
      _frame('b9', 1, [1, 2, 3]),
      _frame('b9', 2, [4, 5, 6]),
      _frame('b9', 3, [7, 8, 9]),
    ];
    p.attachWss(_FakeWss(Stream.fromIterable(frames)));
    await _waitUntil(() => applied.length >= 3);

    expect(applied.map((e) => e.seqId).toList(), [1, 2, 3]);
    expect(applied.first.bundleId, 'b9');
    expect(applied.first.source, 'wss');
    expect(applied.first.cipher, [1, 2, 3]);
    await p.close();
  });
}

ServerFrame _frame(String bundle, int seq, List<int> cipher) => ServerFrame()
  ..seqId = Int64(seq)
  ..schedule = (Envelope()
    ..action = ActionType.PING
    ..bundleId = bundle
    ..seqId = Int64(seq)
    ..senderHint = 'relay'
    ..cipher = cipher);

/// 仅用于测试：frames 流直接由给定流提供（不建真连接）。
class _FakeWss extends RelayWssClient {
  final Stream<ServerFrame> _framesStream;
  _FakeWss(this._framesStream)
      : super(
          wsUri: Uri.parse('ws://localhost:0/ws'),
          getToken: () async => 'x',
          keepaliveInterval: const Duration(hours: 1),
        );

  @override
  Stream<ServerFrame> get frames => _framesStream;
}
