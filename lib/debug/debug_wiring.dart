library;

import 'package:app_debug_bridge/app_debug_bridge.dart';

import '../app_services.dart';
import 'shiguang_debug_log.dart';
import 'shiguang_debug_routes.dart';
import 'shiguang_navigator_key.dart';
import 'shiguang_debug_screenshot.dart';

/// 拾光调试桥状态快照（MCP 服务运行态 + 包标识）。
///
/// 供 `/debug/state` 回显；仅暴露调试关心的运行态，不触及业务状态。
Map<String, Object?> _stateProvider() => <String, Object?>{
      'packageId': 'shiguang',
      'mcpRunning': AppServices.mcp.running,
      'mcpPort': AppServices.mcp.port,
      'mcpEndpoint': AppServices.mcp.endpoint,
    };

/// 装配调试桥：把宿主专有三件（日志源 / 导航柄 / 路由白名单）接到公共包注入端口。
///
/// 导航柄复用新建的 `shiguangNavigatorKey`（全局根 key）；公共包本身不含
/// 任何宿主业务，此处完成汇合。`navigate` 经 `debugRouteRegistry` 限白名单，`tap_node`
/// / `set_text` 经当前 context 遍历控件树，均确定性、不依赖坐标。
DebugBridge buildDebugBridge() {
  // 导航句柄 + 遍历起点（动作面需要挂载中的 context）。
  debugNavigatorProvider = () => shiguangNavigatorKey.currentState;
  debugContextProvider = () => shiguangNavigatorKey.currentContext;
  // 路由白名单（navigate 只跳登记页）。
  debugRouteRegistry = shiguangDebugRouteRegistry;
  return DebugBridge(
    stateProvider: _stateProvider,
    logSink: ShiguangDebugLogSink.instance,
    uiTreeProvider: () => collectDebugUiTree(shiguangNavigatorKey.currentContext),
    actionHandler: executeDebugAction,
    screenshotProvider: shiguangCaptureScreenshot,
  );
}
