import 'dart:async';
import 'dart:convert';
import 'dart:io';

import '../mcp/jsonrpc.dart';
import '../mcp/mcp_server.dart';

/// HTTP 传输层（在线 epic 轴 A 接缝）：持有 [HttpServer]，把 HTTP 专属逻辑
/// （origin/DNS-rebinding 校验、x-api-key 鉴权、/mcp 与 POST 检查、202 通知、
/// 错误兜底）从 [McpServer] 内核剥离（goodhub-local-plugin G4/G5/G6）。
///
/// 内核 [McpServer] 保持纯 Dart（不沾 dart:io），经 [handleJsonRpc] 被本层驱动；
/// 将来 Binder 嵌入只需另建等价的传输层，复用同一内核入口。
class HttpTransport {
  HttpTransport({required this.server});

  final McpServer server;

  HttpServer? _s;
  bool get running => _s != null;
  int get port => _s?.port ?? 0;

  Future<void> start({int port = 8765}) async {
    if (running) return;
    final s = await HttpServer.bind(InternetAddress.anyIPv4, port);
    _s = s;
    s.listen(
      (req) async {
        try {
          await _serve(req);
        } catch (e) {
          await _safeJson(req, HttpStatus.internalServerError,
              _rpcError(null, errInternal, '内部错误: $e'));
        }
      },
      onError: (Object _) {/* 客户端异常断开等，忽略 */},
      cancelOnError: false,
    );
  }

  Future<void> stop() async {
    final s = _s;
    _s = null;
    await s?.close(force: true);
  }

  Future<void> _serve(HttpRequest req) async {
    // DNS-rebinding 防护（MCP 规范要求）：浏览器发起的请求必带 Origin，
    // 恶意网页/DNS rebinding 场景借此被拒；桌面客户端（stdio 桥/直连）不带 Origin，不受影响。
    final origin = req.headers.value('origin');
    if (origin != null && !_isAllowedOrigin(origin)) {
      await _safeJson(req, HttpStatus.forbidden,
          _rpcError(null, errInvalidRequest, 'Origin 不被允许: $origin'));
      return;
    }
    final token = server.tokenProvider();
    if (token != null && token.isNotEmpty &&
        req.headers.value('x-api-key') != token) {
      await _safeJson(req, HttpStatus.unauthorized,
          _rpcError(null, -32001, 'X-Api-Key 校验失败（app「MCP 服务」页可查看 token）'));
      return;
    }
    if (req.uri.path != '/mcp') {
      await _safeJson(req, HttpStatus.notFound,
          _rpcError(null, errInvalidRequest, '未知路径，MCP 端点为 /mcp'));
      return;
    }
    if (req.method != 'POST') {
      await _safeJson(req, HttpStatus.methodNotAllowed,
          _rpcError(null, errInvalidRequest, '仅支持 POST /mcp（本服务不提供 SSE 长连接）'));
      return;
    }

    final body = await utf8.decoder.bind(req).join();
    final resp = await server.handleJsonRpc(body);
    if (resp.isEmpty) {
      // 通知（id==null）：无需应答，回 202（与原 _onRequest 行为一致）
      req.response.statusCode = HttpStatus.accepted;
      await req.response.close();
      return;
    }
    await _json(req, HttpStatus.ok, jsonDecode(resp));
  }

  /// 仅放行本机来源（localhost / 127.0.0.1 / [::1]，任意端口）。
  /// app 内如出现 WebView 类浏览器客户端需直连时，在此扩展白名单。
  bool _isAllowedOrigin(String origin) {
    final uri = Uri.tryParse(origin);
    if (uri == null || uri.host.isEmpty) return false;
    const localHosts = {'localhost', '127.0.0.1', '[::1]', '::1'};
    return localHosts.contains(uri.host.toLowerCase());
  }

  Map<String, Object?> _rpcError(Object? id, int code, String message) => {
        'jsonrpc': '2.0',
        'id': id,
        'error': {'code': code, 'message': message},
      };

  Future<void> _json(HttpRequest req, int status, Object? payload) async {
    req.response.statusCode = status;
    req.response.headers.contentType = ContentType.json;
    req.response.write(payload == null ? '' : jsonEncode(payload));
    await req.response.close();
  }

  Future<void> _safeJson(HttpRequest req, int status, Object? payload) async {
    try {
      await _json(req, status, payload);
    } catch (_) {/* 连接已断开 */}
  }
}
