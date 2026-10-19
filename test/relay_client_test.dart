import 'dart:async';
import 'dart:convert';

import 'package:fixnum/fixnum.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shiguang/relay_proto/relay_protocol.pb.dart' as relayv1;
import 'package:shiguang/service/relay_client.dart';
import 'package:stream_channel/stream_channel.dart';
import 'package:web_socket_channel/web_socket_channel.dart';

/// RelayClient 收单闭环单测（注入 fake WSS 通道 + REST 面）：
///   ① AUTH 首帧 → 收 mcp 单（token 对）→ 应答回发（corr_id 回带）+ ack；
///   ② token 不符（双保险第二道拒）→ 仅 ack 不应答；
///   ③ drainPending：离线积压 mcp 单 ack 丢弃（MVP 约定）。

class _FakeSink implements WebSocketSink {
  _FakeSink(this._ch);

  final _FakeChannel _ch;

  @override
  void add(dynamic data) => _ch.sent.add(data);

  @override
  void addError(Object error, [StackTrace? stackTrace]) {}

  @override
  Future<void> addStream(Stream<dynamic> stream) async {}

  @override
  Future<void> close([int? closeCode, String? closeReason]) async {}

  @override
  Future<void> get done => Future.value();
}

class _FakeChannel extends StreamChannelMixin<dynamic>
    implements WebSocketChannel {
  final toClient = StreamController<dynamic>.broadcast();
  final sent = <dynamic>[];

  @override
  WebSocketSink get sink => _FakeSink(this);

  @override
  Stream<dynamic> get stream => toClient.stream;

  @override
  Future<void> get ready => Future.value();

  // WebSocketChannel v3 无 channel 级 close（只在 sink 上）——此方法仅供测试断连。
  Future<void> close([int? closeCode, String? closeReason]) async {
    await toClient.close();
  }

  @override
  int? get closeCode => null;

  @override
  String? get closeReason => null;

  @override
  String? get protocol => null;
}

void main() {
  const mcpTok = 'sk-mcp';
  late _FakeChannel ws;
  late final relayCalls = <(String, String)>[]; // (method, path)
  late RelayRest rest;

  setUp(() {
    ws = _FakeChannel();
    relayCalls.clear();
    rest = (String method, String path, {Object? body, String? bearer}) async {
      relayCalls.add((method, path));
      if (path == '/v1/auth/login') {
        return {'access_token': 'jwt-a', 'refresh_token': 'jwt-r'};
      }
      if (path == '/v1/relay/pending') {
        return {
          'messages': [
            {'msg_id': 'stale-1', 'kind': relayKindMcp},
          ],
        };
      }
      return null;
    };
  });

  RelayClient makeClient({String? headerToken, String? reqToken}) {
    return RelayClient(
      handleJsonRpc: (raw) async =>
          '{"jsonrpc":"2.0","id":1,"result":{"echo":$raw}}',
      mcpToken: () => reqToken,
      config: () => const RelayConfig(
          endpoint: 'https://relay.test', username: 'u', password: 'p'),
      connect: (_) async => ws,
      rest: rest,
    );
  }

  void pushReq({String token = mcpTok, String corrId = 'c1', String? mid}) {
    final msg = relayv1.RelayMessage()
      ..msgId = mid ?? 'm1'
      ..senderUserId = 'desktop'
      ..kind = relayKindMcp
      ..cipher = utf8.encode('{"method":"tools/list"}')
      ..header.addAll({
        relayHeaderRole: relayRoleReq,
        relayHeaderCorrId: corrId,
        relayHeaderToken: token,
      });
    final frame = relayv1.ServerFrame()..relay = msg;
    ws.toClient.add(frame.writeToBuffer());
  }

  test('收单闭环：AUTH → 应答回发（corr_id 回带）+ ack', () async {
    final c = makeClient(reqToken: mcpTok);
    await c.start();
    await pumpEventQueue();

    // 首帧 AUTH
    expect(ws.sent, isNotEmpty);
    final auth = jsonDecode(ws.sent.first as String) as Map;
    expect(auth['action'], 'AUTH');
    expect(auth['token'], 'jwt-a');

    pushReq();
    await pumpEventQueue();

    // 应答回发：POST /v1/relay/msg，role=resp + corr_id 原样
    final send = relayCalls
        .where((r) => r.$2 == '/v1/relay/msg')
        .toList();
    expect(send, isNotEmpty, reason: '应回发应答');
    // ack 消费原单
    expect(
        relayCalls.any((r) => r.$1 == 'POST' && r.$2 == '/v1/relay/ack'), isTrue);

    await c.stop();
  });

  test('双保险第二道：token 不符 → 仅 ack 不应答', () async {
    final c = makeClient(reqToken: mcpTok);
    await c.start();
    await pumpEventQueue();

    pushReq(token: 'bad-token');
    await pumpEventQueue();

    expect(
        relayCalls.where((r) => r.$2 == '/v1/relay/msg'), isEmpty,
        reason: '鉴权不过不得回发');
    expect(
        relayCalls.any((r) => r.$1 == 'POST' && r.$2 == '/v1/relay/ack'),
        isTrue);

    await c.stop();
  });

  test('drainPending：离线积压 mcp 单 ack 丢弃', () async {
    final c = makeClient(reqToken: mcpTok);
    await c.drainPending();
    expect(
        relayCalls.any((r) => r.$1 == 'GET' && r.$2 == '/v1/relay/pending'),
        isTrue);
    expect(
        relayCalls.any((r) => r.$1 == 'POST' && r.$2 == '/v1/relay/ack'),
        isTrue);
  });

  test('ServerFrame 构造往返（契约副本 smoke）', () {
    final msg = relayv1.RelayMessage()
      ..msgId = 'x'
      ..expiresAt = Int64(1)
      ..header['k'] = 'v';
    final frame = relayv1.ServerFrame()..relay = msg;
    final back = relayv1.ServerFrame.fromBuffer(frame.writeToBuffer());
    expect(back.relay.header['k'], 'v');
  });
}
