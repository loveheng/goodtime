---
dev-loop: epic-patch-list
format: v1
epic: online
parent: "online/memory.md（Step 1 配方 §5 + goodhub-local-plugin G1–G7 修正）"
status: drafted
purpose: "Step 1 代码级 Patch 清单（逐条可勾选），验收通过后再执行"
---

# Step 1 精确 Patch 清单（online epic · 轴 A 接缝）

> 目标：抽 `handleJsonRpc` 传输无关入口 + 建 `HttpTransport` + 迁移 test，**HTTP 行为零变化**。
> 验收：flutter analyze 0 issue / flutter test 全过 / e2e 响应体逐字节等价 + D1 状态码漂移登记。
> 绑定域 `goodhub-local-plugin` 的 G1–G7 已逐条吸收（见对应 P 项标注）。

## 0. 符号核对（已读真实代码确认，严禁凭记忆）

- `lib/mcp/jsonrpc.dart`：`errParse=-32700`、`errInvalidRequest=-32600`、`errMethodNotFound=-32601`、`errInvalidParams=-32602`、`errInternal=-32603`
- `McpRpcError(this.code, this.message, [this.data])`；`toMap()` → `{'code':code,'message':message,'data':data?}`
- `McpProtocol.latestVersion='2025-06-18'`、`negotiate(Object?)`
- **token 校验错误码用字面量 `-32001`（非常量），必须保持**
- `mcp_server.dart` 内保留：`_dispatch`(L148-186)、`_rpcError`(L197-201)、`_builtinInstructions`、`builtinInstructions` getter、构造参数
- `McpServer.tokenProvider` 是公有 `final` 字段（L24）→ `HttpTransport` 可直接 `server.tokenProvider()`

---

## P1 `lib/mcp/mcp_server.dart`

### P1.1 新增 `handleJsonRpc`（传输无关入口）
- 位置：McpServer 类内（`_dispatch` 附近）；**不 import `dart:io`**
- 搬运 `_onRequest`(L107-145) 的解析/dispatch/写回逻辑；通知（`id==null`）返 `''`
- 复用：`_dispatch` / `_rpcError` / `McpRpcError` / `err*` 常量（本文件或 jsonrpc.dart）

```dart
/// 传输无关：输入 JSON-RPC 请求体，输出响应体字符串。
/// 通知（id==null）返回空串，由传输层决定回 202。
Future<String> handleJsonRpc(String rawBody) async {
  Object? msg;
  try {
    msg = jsonDecode(rawBody);
  } catch (_) {
    return jsonEncode(_rpcError(null, errParse, 'JSON 解析失败'));
  }
  if (msg is! Map<String, Object?>) {
    return jsonEncode(_rpcError(null, errInvalidRequest, '仅支持单条 JSON-RPC 消息'));
  }
  final method = msg['method'];
  final id = msg['id'];
  if (id == null) return ''; // 通知：无需应答
  if (method is! String) {
    return jsonEncode(_rpcError(id, errInvalidRequest, '缺少 method'));
  }
  try {
    final result = await _dispatch(method, msg['params']);
    return jsonEncode({'jsonrpc': '2.0', 'id': id, 'result': result});
  } on McpRpcError catch (e) {
    return jsonEncode({'jsonrpc': '2.0', 'id': id, 'error': e.toMap()});
  } catch (e) {
    return jsonEncode(_rpcError(id, errInternal, '工具执行失败: $e'));
  }
}
```

### P1.2 删除 HTTP 专属（核心变纯 Dart，G4 内核不沾 dart:io）
- [ ] 删 `import 'dart:io';`（L3）
- [ ] 删 `HttpServer? _server;` / `running` / `port`（L58-60）
- [ ] 删 `start()`（L62-77）
- [ ] 删 `stop()`（L79-83）
- [ ] 删 `_onRequest(HttpRequest)` 整段（L85-146）
- [ ] 删 `_isAllowedOrigin`（L190-195）
- [ ] 删 `_json`（L203-208）
- [ ] 删 `_safeJson`（L210-214）
- [ ] **保留**：`handleJsonRpc` / `_dispatch` / `_rpcError` / `_builtinInstructions` / `builtinInstructions` getter / 构造参数

### P1.3 行为等价校验点
- 通知 `id==null` → `''`（transport 回 202）✓ 与原 L123-128 一致
- 非 Map / 解析失败 / 缺 method → JSON-RPC 错误体（transport 回 200；原 400→200 漂移，见 D1）
- 其余完全走 `_dispatch`，零变化

---

## P2 `lib/service/http_transport.dart`（**新建，G4 落点钉死 `lib/service`，不进 `lib/mcp`**）

### P2.1 职责
- 持有 `HttpServer`（**G5**：HttpServer 归属 transport，暴露 `start/stop/port/running`）
- 对每请求：校验 → `server.handleJsonRpc(body)` → 写回
- **G6**：保留外层 `try/catch` 兜底 500（原 L66-76）

### P2.2 代码骨架
```dart
import 'dart:async';
import 'dart:convert';
import 'dart:io';

import '../mcp/mcp_server.dart';
import 'package:shiguang/mcp/jsonrpc.dart'; // err* 常量

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
    s.listen((req) async {
      try {
        await _serve(req);
      } catch (e) {
        await _safeJson(req, HttpStatus.internalServerError,
            _rpcError(null, errInternal, '内部错误: $e'));
      }
    }, onError: (_) {/* 客户端异常断开等，忽略 */}, cancelOnError: false);
  }

  Future<void> stop() async {
    final s = _s;
    _s = null;
    await s?.close(force: true);
  }

  Future<void> _serve(HttpRequest req) async {
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
      req.response.statusCode = HttpStatus.accepted; // 通知 202
      await req.response.close();
      return;
    }
    await _json(req, HttpStatus.ok, jsonDecode(resp));
  }

  bool _isAllowedOrigin(String origin) {
    final uri = Uri.tryParse(origin);
    if (uri == null || uri.host.isEmpty) return false;
    const localHosts = {'localhost', '127.0.0.1', '[::1]', '::1'};
    return localHosts.contains(uri.host.toLowerCase());
  }

  Map<String, Object?> _rpcError(Object? id, int code, String message) =>
      {'jsonrpc': '2.0', 'id': id, 'error': {'code': code, 'message': message}};

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
```
- `-32001` 保持字面量（与原 L95 一致）；其余 `err*` 来自 jsonrpc.dart 导入
- `_isAllowedOrigin` 纯字符串、无 `dart:io` 依赖，原样搬入（原 L190-195）
- 校验顺序与原 `_onRequest`(L88-105) 完全一致

### P2.3 `McpServer` 侧需暴露
- `tokenProvider` 已是公有 final（L24）→ 无需改动即可 `server.tokenProvider()`

---

## P3 `lib/service/mcp_controller.dart`

- [ ] 保持 `import '../mcp/mcp_server.dart';`
- [ ] 新增字段 `HttpTransport? _transport;`（原 `McpServer? _server` 保留，作内核引用）
- [ ] `enable()`：把 `await _server!.start(port: defaultPort);`（L118）替换：
  ```dart
  _transport ??= HttpTransport(server: _server!);
  await _transport!.start(port: defaultPort);
  ```
- [ ] `running` getter（L41）→ `_transport?.running ?? false`
- [ ] `port` getter（L42）→ `_transport?.port ?? defaultPort`
- [ ] `_shutdown()`（L153-162）：把 `await _server?.stop();` 改为 `await _transport?.stop(); _transport = null;`；`_server = null` 保留
- [ ] 不再直接碰 `HttpServer`；`endpoint()`（L72-75）不变

---

## P4 `test/mcp_server_test.dart`（**G1 同步迁移，否则 test 全过不可达**）

- [ ] 顶部加 `import '../service/http_transport.dart';`
- [ ] `spinUp`（L28-33）改：
  ```dart
  Future<({McpServer server, HttpTransport transport, HttpClient client, String base})> spinUp({String? token}) async {
    final server = McpServer(repo: Repository(), tokenProvider: () => token);
    final transport = HttpTransport(server: server);
    await transport.start(port: 0);
    final client = HttpClient();
    return (server: server, transport: transport, client: client, base: 'http://127.0.0.1:${transport.port}');
  }
  ```
- [ ] 所有 `await env.server.stop();` → `await env.transport.stop();`（出现于 L74, L92, L142, L164, L177）
- [ ] `rpc()` / 断言全部不变；14 工具 tripwire（L170-173）不变
- [ ] **G3**：现测试未断言 400 分支，故全过；日后新增解析失败断言须按 200 调整

---

## D1 状态码漂移登记（G3，必填）

| 场景 | 改造前 `_onRequest` | 改造后 `HttpTransport` | 影响 |
|------|------|------|------|
| JSON 解析失败 | 400 | 200（handleJsonRpc 错误体） | golden/e2e 需更新 |
| body 非 Map | 400 | 200 | 同上 |
| 缺 method | 400 | 200 | 同上 |
| Origin 不符 | 403 | 403 | 不变 |
| token 不符 | 401 | 401 | 不变 |
| 路径非 `/mcp` | 404 | 404 | 不变 |
| 非 POST | 405 | 405 | 不变 |
| 通知 | 202 | 202 | 不变 |
| 正常 / 领域错误 | 200 | 200 | 不变 |

> 漂移仅限「解析/结构类错误」由 400 升 200（因错误体改由 handleJsonRpc 产出、transport 一律 200 写回）。鉴权/路由/方法/通知状态码完全不变。

---

## 验收清单
- [x] `flutter analyze` 0 issue（4 改动文件 analyze 通过）
- [x] `flutter test` 全过（含 14 工具 tripwire；全量 194 测无回归）
- [ ] e2e：`node mcp-bridge/stdio-bridge.mjs --token <app token>` 经 `adb reverse tcp:8765 tcp:8765` 连 `:8765`，`tools/list` / `add_plan` / `get_schedule` / `propose_schedule` 响应体逐字节等价（golden 比对，按 D1 更新）——**设备侧 stdio-bridge over adb 需真机/模拟器，无头环境未跑；HTTP 行为等价性已由 `test/mcp_server_test.dart` 经真实 `HttpTransport` 断言 200/202/401 与响应体覆盖**，设备侧 golden 比对待手动补。
- [x] D1 漂移已登记（`§D1`）；单元测无 400 断言（G3 一致），故全过无改动。
- [x] 断点回填：已在 `online/memory.md` 断点区勾选 Step 1，并在 `goodhub-local-plugin/memory.md` 标记本域 Step 1 执行。
