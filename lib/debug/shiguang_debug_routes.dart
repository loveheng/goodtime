library;

import 'package:app_debug_bridge/app_debug_bridge.dart' show DebugRouteRegistry;
import 'package:flutter/material.dart';

import '../ui/mcp_settings_page.dart';

/// 调试可达页面白名单（宿主页面清单；公共包只认名字，不认业务）。
///
/// `McpSettingsPage` 是拾光自带的 MCP 服务页（开关 / 端点 / 令牌 / relay 区），借调试桥的
/// `navigate` 开放为调试期可达（release 编译剔除，不影响生产入口）。
final DebugRouteRegistry shiguangDebugRouteRegistry = DebugRouteRegistry(
  <String, WidgetBuilder>{
    'mcp': (_) => const McpSettingsPage(),
  },
);
