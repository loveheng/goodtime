import 'dart:async';

import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';

import 'data/db.dart';
import 'data/repository.dart';
import 'mcp/mcp_server.dart';
import 'service/weather.dart';

/// 拾光本地 Binder 插件后台入口（headless FlutterEngine，goodhub-local-plugin 终点）。
///
/// 部署点：goodhub 经 `bindService(BIND_AUTO_CREATE)` 拉起 `ShiguangMcpBinderService`
/// （原生侧），原生侧创建独立 FlutterEngine 并执行本 entrypoint；本 isolate 持有
/// 独立 `McpServer`/`Repository`，经 MethodChannel `shiguang/mcp_binder` 把
/// `call(json)` 转现有 [McpServer.handleJsonRpc]（协议无关，与 HTTP 版同一套
/// `_dispatch`）。模式照抄 goodshare 范本（mcp_binder_entrypoint.dart，已验证）；
/// goodhub 契约 thirdconnect.md §1.2.2。
///
/// 设计红线：
/// - Binder 走同签名 `signature` 权限，零端口、零 `X-Api-Key` → [McpServer.tokenProvider] 恒 null。
/// - 本 isolate 与 MainActivity 主 isolate 互相独立（各自一套 McpServer/Repository，
///   sqlite WAL 下安全），故 Binder 路径不依赖 app 是否在前台——满足「随调随起」。
/// - 与 goodshare 范本的差异：shiguang 内核不 import sqflite（轴 B 纯 Dart 化），
///   存储工厂须显式注册（Db.registerDefaultStorageFactory，不能靠模块加载副作用）；
///   不走 AppServices.init（那会装配 McpController 起 HTTP 监听/前台服务/远程配置，
///   Binder isolate 只要台账内核）。天气注入真实 [WeatherService]（get_weather 与
///   HTTP 通道同源）。
///
/// F1 `_async`+onEvent 终帧切片（契约 thirdconnect.md §1.2.2）：tools/call 由原生侧
/// 立回 `{"_async":true}` 占位帧，dispatch 照旧同步返回完整 JSON-RPC 响应字符串，
/// 由原生 Result 回调经 `IMcpCallback.onEvent` 转发为同 id 终帧——本 isolate 不感知
/// 切片；dispatch 内任何异常经 MethodChannel error 回调由原生侧兜底成 error 终帧。
const _kChannel = 'shiguang/mcp_binder';

@pragma('vm:entry-point')
void mcpBinderMain() {
  // 后台 isolate 无 UI，但 MethodChannel 需要 ServicesBinding 初始化。
  WidgetsFlutterBinding.ensureInitialized();

  // 存储工厂先行注册（幂等；Repository 惰性开库，首次 DB 访问才真正建库）。
  // 与主 isolate 共享同一 shiguang.db 文件（WAL 并发，R1 缓解依赖 onOpen busy_timeout）。
  Db.registerDefaultStorageFactory();
  final repo = Repository();
  final server = McpServer(
    repo: repo,
    tokenProvider: () => null, // Binder 同签名零鉴权
    weather: WeatherService(),
  );

  final channel = MethodChannel(_kChannel);
  channel.setMethodCallHandler((call) async {
    switch (call.method) {
      case 'dispatch':
        // 同步 JSON-RPC 请求 → 响应字符串（原生侧 B1 per-call ≤5s 超时兜底）。
        return await server.handleJsonRpc(call.arguments as String);
      case 'hostCallbackReady':
        // 宿主已注册 IMcpCallback（registerCallback）。注册后原生侧对 tools/call 走
        // _async 切片（call 立回占位帧，dispatch 结果经 onEvent 转发终帧）；
        // 本 isolate 无反向请求/进度帧，仅作可观测标记。
        return null;
      default:
        throw PlatformException(code: 'not_implemented', message: call.method);
    }
  });

  // 通知原生侧引擎就绪，解除 ShiguangMcpBinderService.call 首调竞态等待。
  // 用 microtask 确保上面 handler 已注册后再发 ready。
  scheduleMicrotask(() => channel.invokeMethod<void>('ready'));
}
