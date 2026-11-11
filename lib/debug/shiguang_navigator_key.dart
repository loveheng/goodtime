import 'package:flutter/material.dart';

/// 全局根 Navigator 句柄（调试桥动作面的遍历起点与导航柄）。
///
/// 动作面（`tap_node` / `set_text` / `navigate`）经 `currentState` / `currentContext`
/// 注入宿主控件树，确定性操作、不依赖坐标。
final GlobalKey<NavigatorState> shiguangNavigatorKey = GlobalKey<NavigatorState>();
