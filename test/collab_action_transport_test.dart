import 'dart:convert';
import 'dart:io';

import 'package:dio/dio.dart';
import 'package:path/path.dart' as p;
import 'package:sqflite_common_ffi/sqflite_ffi.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:shiguang/data/db.dart';
import 'package:shiguang/collab/outbox.dart';
import 'package:shiguang/collab/sealing.dart';
import 'package:shiguang/collab/relay/action_api.dart';

/// Phase 3.4 传输装配验收（collab-implementation §3.4）：
/// OutboxTransport 真实实现对齐 Go relay `internal/httpapi/action.go` 冻结契约
/// ——Bearer + `X-Client-Msg-Id` 幂等头 + `{bundle_id, action, cipher}`，
/// cipher = base64(SealedPayload JSON utf8)，状态码三分类映射 Outbox 结局。

/// 复刻 relay 受理面：记录请求逐字段，按脚本队列回响应。
class MockActionRelay {
  HttpServer? _server;
  final List<Map<String, Object?>> requests = [];

  /// 响应脚本：逐请求弹出 (statusCode, body)；耗尽后默认 200。
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
    requests.add({
      'auth': req.headers.value('authorization'),
      'msg_id': req.headers.value('x-client-msg-id'),
      'bundle_id': j['bundle_id'],
      'action': j['action'],
      'cipher': j['cipher'],
    });
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

  late MockActionRelay relay;
  late String baseUrl;
  const kKey = '0123456789abcdef0123456789abcdef'; // 32B K_bundle

  setUp(() async {
    relay = MockActionRelay();
    baseUrl = await relay.start();
  });

  tearDown(() async {
    await relay.close();
  });

  HttpOutboxTransport transport() => HttpOutboxTransport(
        dio: Dio(),
        baseUrl: baseUrl,
        accessToken: () async => 'tok-1',
        bundleKey: (bundleId, keyVer) async => utf8.encode(kKey),
      );

  OutboxEntry entry() => const OutboxEntry(
        id: 'ob-42',
        bundleId: 'b-1',
        actionType: 'ACCEPT',
        payload: {'step_id': 's3', 'pref': 'ok'},
        keyVer: 1,
        attempts: 0,
        createdAt: 1000,
      );

  test('信封与幂等头逐字段对齐：Bearer / X-Client-Msg-Id / cipher 可解回明文',
      () async {
    final outcome = await transport().send(entry());

    expect(outcome, isA<SendAccepted>());
    final r = relay.requests.single;
    expect(r['auth'], 'Bearer tok-1');
    expect(r['msg_id'], 'ob-42'); // 幂等键与 OutboxEntry.id 同源
    expect(r['bundle_id'], 'b-1');
    expect(r['action'], 'ACCEPT');
    // cipher 链路：base64 → utf8 → SealedPayload → AES-GCM 解开 → 原始明文
    final sealed = SealedPayload.decode(
        utf8.decode(base64Decode(r['cipher']! as String)));
    final opened = await openPayload(utf8.encode(kKey), sealed);
    expect(utf8.decode(opened), jsonEncode(entry().payload));
  });

  test('4xx 确定性打回 → SendRejected(error_code)；缺 error_code 兜底 HTTP_<status>',
      () async {
    relay.script.addAll([
      (
        409,
        {
          'error_code': 'STALE_REVISION',
          'message': 'base revision conflict',
          'detail': {'current_seq': 18, 'expected_seq': 17}
        }
      ),
      (403, {'error_code': 'NOT_PARTICIPANT', 'message': 'no'}),
      (400, {'message': 'malformed'}), // 无 error_code 字段
    ]);

    expect(await transport().send(entry()),
        isA<SendRejected>().having((e) => e.errorCode, 'code', 'STALE_REVISION'));
    expect(await transport().send(entry()),
        isA<SendRejected>().having((e) => e.errorCode, 'code', 'NOT_PARTICIPANT'));
    expect(await transport().send(entry()),
        isA<SendRejected>().having((e) => e.errorCode, 'code', 'HTTP_400'));
  });

  test('5xx / 401 / 传输层异常 → SendOffline（非终态保留重试）', () async {
    relay.script.addAll([
      (500, {'error_code': 'INTERNAL', 'message': 'boom'}),
      (401, {'error_code': 'UNAUTHORIZED', 'message': 'token expired'}),
    ]);

    expect(await transport().send(entry()), isA<SendOffline>());
    expect(await transport().send(entry()), isA<SendOffline>());

    // 连接拒绝：指向已关闭端口 → DioException → SendOffline
    final dead = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    final deadPort = dead.port;
    await dead.close();
    final t = HttpOutboxTransport(
      dio: Dio(),
      baseUrl: 'http://127.0.0.1:$deadPort',
      accessToken: () async => 'tok-1',
      bundleKey: (b, v) async => utf8.encode(kKey),
    );
    expect(await t.send(entry()), isA<SendOffline>());
  });

  test('Drainer 集成：真传输走一轮——成功删箱、409 清箱上抛引导', () async {
    final tmp = await Directory.systemTemp.createTemp('action_transport_test');
    final db = await openDatabase(
      p.join(tmp.path, 't.db'),
      version: 5,
      onCreate: (db, version) => Db.createAll(db),
    );
    addTearDown(() async {
      await db.close();
      await tmp.delete(recursive: true);
    });

    relay.script.addAll([
      (200, {'bundle_id': 'b-1', 'seq_id': 9, 'status': 'PROPOSED'}),
      (
        409,
        {'error_code': 'STALE_REVISION', 'message': 'base revision conflict'}
      ),
    ]);
    final repo = OutboxRepository(db);
    await repo.enqueue(bundleId: 'b-1', actionType: 'ACCEPT',
        payload: {'step_id': 's1'});
    await repo.enqueue(bundleId: 'b-1', actionType: 'PATCH_TIME',
        payload: {'step_id': 's1', 'start': 42});

    final report = await OutboxDrainer(repo, transport()).drain();

    expect(report.sent, 1);
    expect(report.stoppedByNetwork, isFalse);
    expect(report.conflicts.single.errorCode, 'STALE_REVISION');
    expect(report.conflicts.single.actionType, 'PATCH_TIME');
    expect(await repo.pending(), isEmpty); // 全清：确认删 + 409 清箱
    expect(relay.requests.map((r) => r['action']),
        ['ACCEPT', 'PATCH_TIME']); // (created_at, id) 全序
  });
}
