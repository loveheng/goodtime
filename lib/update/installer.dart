import 'dart:io';

import 'package:flutter/services.dart';

/// 系统 PackageInstaller 会话通道（原生侧实现，见 android MainActivity.kt）。
/// 用会话 API 直接读本地 APK 写入安装会话，绕开 ACTION_VIEW + FileProvider 的
/// 跨应用 URI 授权在 Android 14+ 上不稳定导致「软件包无效」的坑。
const _installChannel = MethodChannel('app.shiguang/installer');

/// 本应用是否已获「安装未知应用」授权（Android 14+ 自更新前置）。
/// 非 Android 返回 true（桌面无此门槛）。
Future<bool> canRequestPackageInstalls() async {
  if (!Platform.isAndroid) return true;
  try {
    return await _installChannel
            .invokeMethod<bool>('canRequestPackageInstalls') ??
        false;
  } on PlatformException {
    return false;
  }
}

/// 拉起系统「安装未知应用」授权开关页（直达本应用条目）。
Future<void> openUnknownSourceSettings() async {
  if (!Platform.isAndroid) {
    throw UnsupportedError('openUnknownSourceSettings 仅支持 Android');
  }
  await _installChannel.invokeMethod<void>('openUnknownSourceSettings');
}

/// 拉起系统安装器安装 APK（Android 14+ 可靠的自更新方式）。
/// 返回系统安装器状态串；非 Android 抛 [UnsupportedError]。
Future<String> installApk(String path) async {
  if (!Platform.isAndroid) {
    throw UnsupportedError('installApk 仅支持 Android');
  }
  try {
    final result = await _installChannel.invokeMethod<String>(
      'installApk',
      {'path': path},
    );
    return result ?? 'unknown';
  } on PlatformException catch (e) {
    throw Exception('安装失败：${e.message}');
  }
}
