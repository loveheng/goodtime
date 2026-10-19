import 'dart:async';
import 'dart:convert';
import 'dart:developer';
import 'dart:io';

import 'package:web_socket_channel/web_socket_channel.dart';

import '../relay_proto/relay_protocol.pb.dart' as relayv1;

/// relay 通道配置（app_settings 持久化，键见 [RelayKeys]）。
class RelayConfig {
  const RelayConfig({
    required this.endpoint,
    required this.username,
    required this.password,
  });

  /// relay 服务基地址（如 `https://relay.example.com`；ws 由本类派生 `<ep>/ws`）。
  final String endpoint;
  final String username;
  final String password;

  bool get isComplete =>
      endpoint.isNotEmpty && username.isNotEmpty && password.isNotEmpty;
}

/// MCP over relay 的 kind 与 TTL 约定（契约 v0.1.8；与 relay-bridge.mjs 同源）。
const String relayKindMcp = 'mcp';
const int relayMcpTtlSeconds = 120;

/// MCP 通道消息头约定：role=req|resp、corr_id 请求-响应匹配、token=x-api-key（仅 req）。
const String relayHeaderRole = 'role';
const String relayHeaderCorrId = 'corr_id';
const String relayHeaderToken = 'token';
const String relayRoleReq = 'req';
const String relayRoleResp = 'resp';

/// RelayRest：relay REST 面（登录/发单/拉取/确认），注入以便单测。
typedef RelayRest = Future<Map<String, Object?>?> Function(
  String method,
  String path, {
  Object? body,
  String? bearer,
});

/// RelayClient：MCP over relay——手机侧收单端（轴 C 最小闭环）。
///
/// 与 [HttpTransport] 并列的第二种传输（挂 `McpController` 之后，内核零改动）：
/// WSS 建连（首帧 AUTH）→ 收 `ServerFrame.relay`（kind=mcp, role=req）→
/// 校验 x-api-key（双保险第二道，第一道=relay 好友 ACL，服务端已强制）→
/// 喂 `handleJsonRpc` → 应答经 REST `POST /v1/relay/msg` 回发（role=resp,
/// corr_id 原样回带）→ REST ack 消费原单（防重连 pending 重放）。
///
/// MVP 口径（schedule-app.md:331 已接受「手机不在线 AI 失联」）：仅在线直推；
/// 离线积压的 mcp 单在重连 drain 时 ack 丢弃（发方已按 delivered_online 判败）。
/// 应用层明文依赖 TLS+ACL，E2EE 后续切片（见契约 v0.1.8 注）。
class RelayClient {
  RelayClient({
    required this.handleJsonRpc,
    required this.mcpToken,
    required this.config,
    Future<WebSocketChannel> Function(Uri uri)? connect,
    RelayRest? rest,
  })  : _connect = connect ?? _defaultConnect,
        _restOverride = rest;

  /// JSON-RPC 内核入口（McpServer.handleJsonRpc）。
  final Future<String> Function(String rawBody) handleJsonRpc;

  /// MCP x-api-key 提供方（与 HttpTransport 同口径；空=不校验）。
  final String? Function() mcpToken;

  /// relay 配置提供方（null/未填齐=通道不启动）。
  final RelayConfig? Function() config;

  final Future<WebSocketChannel> Function(Uri uri) _connect;
  final RelayRest? _restOverride;

  WebSocketChannel? _ws;
  String? _accessToken;
  Timer? _retry;
  bool _starting = false;

  bool get running => _ws != null;

  static Future<WebSocketChannel> _defaultConnect(Uri uri) async {
    // dart:io 原生通道（service 层合法）；内网 http 端点自动降 ws。
    if (uri.scheme == 'https') {
      return WebSocketChannel.connect(uri.replace(scheme: 'wss'));
    }
    return WebSocketChannel.connect(uri.replace(scheme: 'ws'));
  }

  /// 启动：登录 → WSS 建连 → AUTH → 收单循环。重复调用幂等。
  Future<void> start() async {
    if (_ws != null || _starting) return;
    final cfg = config();
    if (cfg == null || !cfg.isComplete) return;
    _starting = true;
    try {
      await _login(cfg);
      await _connectAndServe(cfg);
    } catch (e) {
      // DEGRADE: [relay_start_failed] 启动失败退避重试（relay 不可达不阻断 HTTP 通道）
      log('[DEGRADE][relay_start_failed] $e');
      _scheduleRetry();
    } finally {
      _starting = false;
    }
  }

  Future<void> stop() async {
    _retry?.cancel();
    _retry = null;
    final ws = _ws;
    _ws = null;
    await ws?.sink.close();
  }

  void _scheduleRetry() {
    _retry?.cancel();
    _retry = Timer(const Duration(seconds: 5), () {
      _accessToken = null; // 失效即重登
      start();
    });
  }

  Future<void> _login(RelayConfig cfg) async {
    if (_accessToken != null) return;
    final resp = await _restCall(cfg, 'POST', '/v1/auth/login',
        body: {'username': cfg.username, 'password': cfg.password});
    final token = resp?['access_token'];
    if (token is! String || token.isEmpty) {
      throw StateError('relay 登录失败：缺少 access_token');
    }
    _accessToken = token;
  }

  Future<void> _connectAndServe(RelayConfig cfg) async {
    final uri = Uri.parse('${cfg.endpoint.replaceAll(RegExp(r'/+$'), '')}/ws');
    final ws = await _connect(uri);
    _ws = ws;
    // 首帧 AUTH（明文 JSON，3s 掐线约定）
    ws.sink.add(jsonEncode({'action': 'AUTH', 'token': _accessToken}));
    ws.stream.listen(
      (data) {
        if (data is List<int>) _onFrame(cfg, data);
      },
      onError: (Object e) {
        // DEGRADE: [relay_ws_error] WSS 断开，退避重连（token 保留，仅重连）
        log('[DEGRADE][relay_ws_error] $e');
        _teardown();
        _scheduleRetry();
      },
      onDone: () {
        _teardown();
        _scheduleRetry();
      },
      cancelOnError: true,
    );
  }

  void _teardown() {
    final ws = _ws;
    _ws = null;
    ws?.sink.close();
  }

  /// 单帧处理：ServerFrame.relay & kind=mcp & role=req → 校验 token → 应答回发。
  Future<void> _onFrame(RelayConfig cfg, List<int> bytes) async {
    final relayv1.ServerFrame frame;
    try {
      frame = relayv1.ServerFrame.fromBuffer(bytes);
    } catch (_) {
      return; // 非 protobuf 帧（服务端关闭帧等）忽略
    }
    final msg = frame.relay;
    if (frame.whichBody() != relayv1.ServerFrame_Body.relay ||
        msg.kind != relayKindMcp) {
      return;
    }
    final header = msg.header;
    if (header[relayHeaderRole] != relayRoleReq) return;
    final corrId = header[relayHeaderCorrId] ?? '';
    final sender = msg.senderUserId;

    // 双保险第二道：x-api-key 校验（与 HttpTransport 同口径）
    final token = mcpToken();
    if (token != null && token.isNotEmpty && header[relayHeaderToken] != token) {
      log('[DEGRADE][relay_mcp_auth] MCP token 校验失败（sender=$sender）');
      await _ack([msg.msgId]);
      return;
    }

    String respBody = '';
    try {
      respBody = await handleJsonRpc(utf8.decode(msg.cipher));
    } catch (e) {
      // DEGRADE: [relay_mcp_dispatch] 内核异常——回 JSON-RPC 内部错误，不让单据悬死
      log('[DEGRADE][relay_mcp_dispatch] $e');
      respBody = jsonEncode({
        'jsonrpc': '2.0',
        'id': null,
        'error': {'code': -32603, 'message': '内部错误'},
      });
    }
    if (respBody.isNotEmpty) {
      await _send(
        cfg,
        recipient: sender,
        cipher: utf8.encode(respBody),
        header: {
          relayHeaderRole: relayRoleResp,
          if (corrId.isNotEmpty) relayHeaderCorrId: corrId,
        },
      );
    }
    await _ack([msg.msgId]);
  }

  /// 重连后 drain：离线积压的 mcp 单按设计 ack 丢弃（发方已判败）。
  Future<void> drainPending() async {
    final cfg = config();
    if (cfg == null) return;
    final resp = await _restCall(cfg, 'GET', '/v1/relay/pending');
    final msgs = resp?['messages'];
    if (msgs is! List) return;
    final ids = <String>[];
    for (final m in msgs) {
      if (m is Map && m['kind'] == relayKindMcp && m['msg_id'] is String) {
        ids.add(m['msg_id'] as String);
      }
    }
    if (ids.isNotEmpty) await _ack(ids);
  }

  Future<void> _send(
    RelayConfig cfg, {
    required String recipient,
    required List<int> cipher,
    required Map<String, String> header,
  }) async {
    await _restCall(cfg, 'POST', '/v1/relay/msg', body: {
      'recipient_user_id': recipient,
      'kind': relayKindMcp,
      'cipher': base64Encode(cipher),
      'ttl_seconds': relayMcpTtlSeconds,
      'header': header,
    });
  }

  Future<void> _ack(List<String> messageIds) async {
    final cfg = config();
    if (cfg == null || messageIds.isEmpty) return;
    try {
      await _restCall(cfg, 'POST', '/v1/relay/ack',
          body: {'message_ids': messageIds});
    } catch (e) {
      // DEGRADE: [relay_ack_failed] ack 失败只告警（TTL 即焚兜底，不阻断主路径）
      log('[DEGRADE][relay_ack_failed] $e');
    }
  }

  Future<Map<String, Object?>?> _restCall(
    RelayConfig cfg,
    String method,
    String path, {
    Object? body,
  }) {
    final override = _restOverride;
    if (override != null) {
      return override(method, path, body: body, bearer: _accessToken);
    }
    return _httpRest(cfg, method, path, body: body, bearer: _accessToken);
  }

  /// 默认 REST 面：dart:io HttpClient（service 层合法；测试经 [_restOverride] 注入）。
  Future<Map<String, Object?>?> _httpRest(
    RelayConfig cfg,
    String method,
    String path, {
    Object? body,
    String? bearer,
  }) async {
    final uri = Uri.parse(
        '${cfg.endpoint.replaceAll(RegExp(r'/+$'), '')}$path');
    final client = HttpClient();
    try {
      final req = await client.openUrl(method, uri);
      req.headers.contentType = ContentType.json;
      if (bearer != null && bearer.isNotEmpty) {
        req.headers.set(HttpHeaders.authorizationHeader, 'Bearer $bearer');
      }
      if (body != null) req.add(utf8.encode(jsonEncode(body)));
      final resp = await req.close();
      final text = await utf8.decoder.bind(resp).join();
      if (text.isEmpty) return null;
      final decoded = jsonDecode(text);
      return decoded is Map<String, Object?> ? decoded : null;
    } finally {
      client.close();
    }
  }
}

/// relay 键（app_settings 内部键，不经 update_settings 白名单——与 mcp_* 同口径）。
abstract final class RelayKeys {
  static const enabled = 'relay_enabled';
  static const endpoint = 'relay_endpoint';
  static const username = 'relay_username';
  static const password = 'relay_password';
}
