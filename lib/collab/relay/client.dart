import 'dart:math';

import 'package:dio/dio.dart';

/// 统一的 relay HTTP 客户端工厂。
///
/// relayd 中间件强制每个请求带 `X-Client-Msg-Id`（幂等键）与 `X-Client-Ts`
///（秒级 Unix 时间戳，容差 ±300s；缺失或越界即 400 `INVALID_REQUEST`）。本工厂
/// 用拦截器为每个请求自动补这两个头，调用方无需逐个手写。仅当请求已显式带该头时
/// （如发件箱用 `OutboxEntry.id` 保证重试幂等）保留原值，不覆盖。
Dio relayHttpClient() {
  final dio = Dio();
  // 超时兜底：relay 网络不可达时让请求快速失败，而非无限挂起（避免后台任务卡死）。
  dio.options.connectTimeout = const Duration(seconds: 8);
  dio.options.receiveTimeout = const Duration(seconds: 20);
  dio.options.sendTimeout = const Duration(seconds: 20);
  dio.interceptors.add(
    InterceptorsWrapper(
      onRequest: (options, handler) {
        if (!options.headers.containsKey('X-Client-Msg-Id')) {
          options.headers['X-Client-Msg-Id'] = _newMsgId();
        }
        if (!options.headers.containsKey('X-Client-Ts')) {
          options.headers['X-Client-Ts'] = _nowSeconds().toString();
        }
        handler.next(options);
      },
    ),
  );
  return dio;
}

String _newMsgId() {
  final r = Random.secure();
  final bytes = List<int>.generate(16, (_) => r.nextInt(256));
  const hex = '0123456789abcdef';
  return String.fromCharCodes(
    bytes.expand((b) => [hex.codeUnitAt(b >> 4), hex.codeUnitAt(b & 0xf)]),
  );
}

int _nowSeconds() => DateTime.now().millisecondsSinceEpoch ~/ 1000;
