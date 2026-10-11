import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:fixnum/fixnum.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:shiguang/collab/proto/relay_protocol.pb.dart';
import 'package:shiguang/collab/relay/wss_client.dart';

/// 复刻 relay `internal/wss/hub.go` 的最小服务端（VM 内 HttpServer + WebSocket）：
/// 纯路径 `/ws` 建连 → 首帧 JSON AUTH(token) 校验（错则掐线）→ 之后二进制 ServerFrame 收发。
Future<HttpServer> _startMockWs({
  required String expectedToken,
  List<WebSocket>? conns,
}) async {
  final server = await HttpServer.bind('127.0.0.1', 0, shared: true);
  server.listen((req) async {
    if (req.uri.path != '/ws') {
      req.response.statusCode = 404;
      await req.response.close();
      return;
    }
    final ws = await WebSocketTransformer.upgrade(req);
    conns?.add(ws);
    var authed = false;
    ws.listen((msg) async {
      if (!authed) {
        var ok = false;
        if (msg is String) {
          try {
            final j = jsonDecode(msg) as Map<String, dynamic>;
            ok = j['action'] == 'AUTH' && j['token'] == expectedToken;
          } catch (_) {}
        }
        if (!ok) {
          await ws.close(1008, 'expected AUTH');
          return;
        }
        authed = true;
        return;
      }
      // 已鉴权：收到 CLIENT_ACK 即回推一个 schedule 帧（模拟 relay 事件广播）
      if (msg is List<int>) {
        try {
          final cf = ClientFrame.fromBuffer(msg);
          if (cf.hasSchedule() && cf.schedule.action == ActionType.CLIENT_ACK) {
            final sf = ServerFrame()
              ..seqId = Int64(7)
              ..schedule = (Envelope()
                ..action = ActionType.PING
                ..bundleId = cf.schedule.bundleId
                ..seqId = Int64(7)
                ..senderHint = 'relay');
            ws.add(sf.writeToBuffer());
          }
        } catch (_) {}
      }
    }, onDone: () => ws.close(), onError: (_) => ws.close());
  });
  return server;
}

/// 轮询等待条件成立，超时抛 [TimeoutException]。
Future<void> _waitUntil(bool Function() cond,
    {Duration timeout = const Duration(seconds: 5)}) async {
  final end = DateTime.now().add(timeout);
  while (!cond()) {
    if (DateTime.now().isAfter(end)) {
      throw TimeoutException('条件未在 ${timeout.inSeconds}s 内满足');
    }
    await Future.delayed(const Duration(milliseconds: 20));
  }
}

void main() {
  test('WSS 首帧 AUTH 握手 + 收帧分发（protobuf 解码）', () async {
    final server = await _startMockWs(expectedToken: 'tok-A');
    final uri = Uri.parse('ws://127.0.0.1:${server.port}/ws');
    final frames = <ServerFrame>[];
    final client = RelayWssClient(
      wsUri: uri,
      getToken: () async => 'tok-A',
      keepaliveInterval: const Duration(hours: 1), // 关心跳，避免干扰
    );
    client.frames.listen(frames.add);
    client.connect();

    // 等 open 后持续发 ack，直至收到 relay 回推
    await _waitUntil(() {
      client.sendClientAck('b1', 5);
      return frames.isNotEmpty;
    });
    expect(frames.first.seqId.toInt(), 7);
    expect(frames.first.schedule.bundleId, 'b1');
    expect(frames.first.schedule.senderHint, 'relay');
    client.dispose();
    await server.close(force: true);
  });

  test('断连后指数退避自动重连，恢复收帧', () async {
    final conns = <WebSocket>[];
    final server = await _startMockWs(expectedToken: 'tok-A', conns: conns);
    final uri = Uri.parse('ws://127.0.0.1:${server.port}/ws');
    final frames = <ServerFrame>[];
    final states = <WssConnectionState>[];
    final client = RelayWssClient(
      wsUri: uri,
      getToken: () async => 'tok-A',
      keepaliveInterval: const Duration(hours: 1),
      initialBackoff: const Duration(milliseconds: 100),
      maxBackoff: const Duration(milliseconds: 500),
    );
    client.frames.listen(frames.add);
    client.state.listen(states.add);
    client.connect();

    // 首帧往返
    await _waitUntil(() {
      client.sendClientAck('b1', 5);
      return frames.isNotEmpty;
    });
    expect(frames.length, 1);

    // 模拟断网：服务端主动掐掉这条连接
    await conns.first.close();

    // 客户端退避后自动重连，再次收到帧
    await _waitUntil(() {
      client.sendClientAck('b1', 5);
      return frames.length >= 2;
    });
    expect(frames.length, greaterThanOrEqualTo(2));
    expect(states, contains(WssConnectionState.reconnecting));
    client.dispose();
    await server.close(force: true);
  });

  test('非法 token 被掐线后进入重连（不悬挂）', () async {
    final server = await _startMockWs(expectedToken: 'right');
    final uri = Uri.parse('ws://127.0.0.1:${server.port}/ws');
    final states = <WssConnectionState>[];
    final client = RelayWssClient(
      wsUri: uri,
      getToken: () async => 'wrong',
      keepaliveInterval: const Duration(hours: 1),
      initialBackoff: const Duration(milliseconds: 80),
      maxBackoff: const Duration(milliseconds: 300),
    );
    client.state.listen(states.add);
    client.connect();

    // 应在短时间内检测到被关并进入重连（而非稳定 open）
    await _waitUntil(
        () => states.contains(WssConnectionState.reconnecting),
        timeout: const Duration(seconds: 4));
    client.dispose();
    await server.close(force: true);
  });

  test('CLIENT_ACK 经 WSS 送达并被 relay 处理', () async {
    final server = await _startMockWs(expectedToken: 'tok-A');
    final uri = Uri.parse('ws://127.0.0.1:${server.port}/ws');
    final frames = <ServerFrame>[];
    final client = RelayWssClient(
      wsUri: uri,
      getToken: () async => 'tok-A',
      keepaliveInterval: const Duration(hours: 1),
    );
    client.frames.listen(frames.add);
    client.connect();
    await _waitUntil(() {
      client.sendClientAck('bundle-x', 9);
      return frames.isNotEmpty;
    });
    // 回推帧的 bundleId 取自已发 ack，证明 ack 端到端送达
    expect(frames.first.schedule.bundleId, 'bundle-x');
    client.dispose();
    await server.close(force: true);
  });
}
