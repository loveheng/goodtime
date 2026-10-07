import 'dart:async';
import 'dart:convert';
import 'dart:io';

import '../data/repository.dart';
import 'jsonrpc.dart';
import 'tools.dart';

/// 内嵌 MCP 服务：Streamable HTTP（POST /mcp，application/json 单响应）。
/// 自拾贝原样搬运（schedule-app.md §12），仅替换 serverInfo 与 instructions。
/// v1 不做服务端主动推送：GET/DELETE 返回 405；也不签发会话 id（规范允许 MAY，
/// 无状态更简单，桌面桥与直连客户端都不依赖会话）。
class McpServer {
  McpServer({
    required this.repo,
    required this.tokenProvider,
    this.instructionsProvider,
    this.onSuggestion,
  });

  final Repository repo;

  /// 返回当前生效 token；为空表示关闭鉴权。
  final String? Function() tokenProvider;

  /// initialize 返回的 instructions；非空时覆盖内置文案（配置热更入口）。
  final Future<String?> Function()? instructionsProvider;

  /// AI 经 suggest_user_setting 推送「建议用户改设置」时的回调（app 借此弹提示）。
  final void Function(Map<String, Object?>)? onSuggestion;

  /// 政策精华（§7 行为层第一层）：每次 initialize 必读。
  static const _builtinInstructions =
      '你是「拾光」的排程师。拾光是用户本地持有的日程台账：App=台账+裁判（存储/状态机/机械校验），'
      '你=排程师（读数据、权衡、提案），人=终审（确认/否决/微调）。\n'
      '政策精华：\n'
      '1. 用户输入的是概念，日程装的是行动——只排叶子行动（动词开头、一次能做完、时长明确），不排概念；'
      '父计划被要求排期时先下钻/先拆解。\n'
      '2. 排程前必须到达明确态（spec 非空且无未决 open_items）；有未决问题先澄清'
      '（单轮最多 2–3 问、先时间后细节；≤30 分钟或琐事类免澄清，直接给叶子行动）。\n'
      '3. 和平条款：human 块与 pinned 块是不可穿透的固定占用，必须绕行；'
      'propose_schedule 只替换你自己的未确认提案块。\n'
      '4. 先读后排：propose_schedule 前必须先 get_schedule 读当天 human/pinned 分布与空窗；'
      '被拒时按逐条 reason 修正整单重试，或把块放进返回的 available_free_windows。\n'
      '5. update_plan 等 version 冲突时，直接在错误返回的 latest 快照上合并你的修改重试，不要重拉。\n'
      '6. MCP 第一定律：永远假设用户的生活处于布朗运动中。排程不是为了占满时间，'
      '而是为了在混乱的洪流中，用最低的认知摩擦，为用户捞起一颗珍珠。\n'
      '7. 设置修改权限边界：你可经 update_settings 改动数值类键与 today_energy（今日电量档）；'
      '但 user_rules（显式偏好）、weather_location（城市）、exceptions（例外日）、identity_prompt'
      '（身份画像）、theme_mode（外观）仅用户本人在 app「设置」页自设，你无权写入（会被拒）。'
      '当你判断这些用户自设键确实需要调整时，不要在回复里含糊带过，也不要尝试写入——'
      '请明确、具体地建议用户：「建议你在 app 设置页修改 XX（原因：…）」，由用户决断。';

  /// 内置 instructions 只读出口：MCP 控制层注入用户身份画像时复用此基准文案，
  /// 与其拼接后整体覆盖（instructionsProvider 非空时生效）。
  static String get builtinInstructions => _builtinInstructions;

  HttpServer? _server;
  bool get running => _server != null;
  int get port => _server?.port ?? 0;

  Future<void> start({int port = 8765}) async {
    if (running) return;
    final server = await HttpServer.bind(InternetAddress.anyIPv4, port);
    _server = server;
    server.listen(
      (req) async {
        try {
          await _onRequest(req);
        } catch (e) {
          await _safeJson(req, HttpStatus.internalServerError, _rpcError(null, errInternal, '内部错误: $e'));
        }
      },
      onError: (Object _) {/* 客户端异常断开等，忽略 */},
      cancelOnError: false,
    );
  }

  Future<void> stop() async {
    final server = _server;
    _server = null;
    await server?.close(force: true);
  }

  Future<void> _onRequest(HttpRequest req) async {
    // DNS-rebinding 防护（MCP 规范要求）：浏览器发起的请求必带 Origin，
    // 恶意网页/DNS rebinding 场景借此被拒；桌面客户端（stdio 桥/直连）不带 Origin，不受影响。
    final origin = req.headers.value('origin');
    if (origin != null && !_isAllowedOrigin(origin)) {
      await _safeJson(req, HttpStatus.forbidden, _rpcError(null, errInvalidRequest, 'Origin 不被允许: $origin'));
      return;
    }
    final token = tokenProvider();
    if (token != null && token.isNotEmpty && req.headers.value('x-api-key') != token) {
      await _safeJson(req, HttpStatus.unauthorized, _rpcError(null, -32001, 'X-Api-Key 校验失败（app「MCP 服务」页可查看 token）'));
      return;
    }
    if (req.uri.path != '/mcp') {
      await _safeJson(req, HttpStatus.notFound, _rpcError(null, errInvalidRequest, '未知路径，MCP 端点为 /mcp'));
      return;
    }
    if (req.method != 'POST') {
      await _safeJson(req, HttpStatus.methodNotAllowed, _rpcError(null, errInvalidRequest, '仅支持 POST /mcp（本服务不提供 SSE 长连接）'));
      return;
    }

    final body = await utf8.decoder.bind(req).join();
    Object? msg;
    try {
      msg = jsonDecode(body);
    } catch (_) {
      await _safeJson(req, HttpStatus.badRequest, _rpcError(null, errParse, 'JSON 解析失败'));
      return;
    }
    // 2025-06-18 已移除 batch；收到数组一律按无效请求处理。
    if (msg is! Map<String, Object?>) {
      await _safeJson(req, HttpStatus.badRequest, _rpcError(null, errInvalidRequest, '仅支持单条 JSON-RPC 消息'));
      return;
    }

    final method = msg['method'];
    final id = msg['id'];
    if (id == null) {
      // 通知（如 notifications/initialized）：无需应答
      req.response.statusCode = HttpStatus.accepted;
      await req.response.close();
      return;
    }
    if (method is! String) {
      await _json(req, HttpStatus.ok, _rpcError(id, errInvalidRequest, '缺少 method'));
      return;
    }

    try {
      final result = await _dispatch(method, msg['params']);
      await _json(req, HttpStatus.ok, {
        'jsonrpc': '2.0',
        'id': id,
        'result': result,
      });
    } on McpRpcError catch (e) {
      await _json(req, HttpStatus.ok, {'jsonrpc': '2.0', 'id': id, 'error': e.toMap()});
    } catch (e) {
      await _json(req, HttpStatus.ok, _rpcError(id, errInternal, '工具执行失败: $e'));
    }
  }

  Future<Object?> _dispatch(String method, Object? params) async {
    switch (method) {
      case 'initialize':
        final p = params is Map<String, Object?> ? params : const {};
        return {
          'protocolVersion': McpProtocol.negotiate(p['protocolVersion']),
          'capabilities': {
            'tools': {'listChanged': false},
          },
          'serverInfo': {'name': 'shiguang', 'version': '1.0.0'},
          'instructions': instructionsProvider == null
              ? _builtinInstructions
              : (await instructionsProvider!() ?? _builtinInstructions),
        };
      case 'ping':
        return <String, Object?>{};
      case 'tools/list':
        return {'tools': toolSchemas()};
      case 'tools/call':
        final p = params is Map<String, Object?> ? params : const <String, Object?>{};
        final name = p['name'];
        if (name is! String) throw McpRpcError(errInvalidParams, '缺少 tool name');
        final args = p['arguments'] is Map<String, Object?>
            ? p['arguments'] as Map<String, Object?>
            : <String, Object?>{};
        final content = await callTool(name, args, repo);
        if (name == 'suggest_user_setting') {
          onSuggestion?.call(args);
        }
        return {'content': content, 'isError': false};
      case 'resources/list':
        return {'resources': <Map<String, Object?>>[]};
      case 'prompts/list':
        // prompts 先空实现（functional-spec §1 M1）：SOP 四流程 M3 随 playbook 内容同源实装
        return {'prompts': <Map<String, Object?>>[]};
      default:
        throw McpRpcError(errMethodNotFound, '未知方法: $method');
    }
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
