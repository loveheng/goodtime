/// 真 relay 联调配置（Phase 4 编排收口项：真 relay 联调 RELAY_BASE_URL）。
///
/// 所有协同传输层（WSS 建连 / `/sync` 增量补拉 / `/v1/schedule/action` 发件箱）
/// 的统一地址来源——`lib/collab/engine.dart` 的 `wireCollabSystem` 据此构造三路
/// 传输，避免 base URL 散落各处。
///
/// 取值优先级：
/// 1. 编译期 `dart-define=RELAY_BASE_URL=...`（CI / 临时覆盖，最高优先）；
/// 2. 配置文件 `config/relay.env` 的 `RELAY_BASE_URL`（运行时注入，默认联调值，
///    由 `flutter_dotenv` 在 `main` 启动早期 `dotenv.load` 载入；改地址只动文件，
///    不必写进 `flutter run` 命令）；
/// 3. 兜底默认 `http://10.0.2.2:8080`——Android 模拟器访问宿主机 relay 的标准
///    回环地址（配置文件缺失/未加载时生效）。
library;

import 'package:flutter_dotenv/flutter_dotenv.dart';

/// relay 服务地址配置。
///
/// [baseUrl] 为 HTTP(S) 基址（不含尾斜杠），WSS 地址由其推导（`http(s)` →
/// `ws(s)` 并追加 `/ws` 纯路径，对齐 hub.go「纯路径建连 token 不进 URL」）。
class RelayConfig {
  /// HTTP(S) 基址，如 `http://10.0.2.2:8080`。
  final String baseUrl;

  const RelayConfig({required this.baseUrl});

  /// 按优先级解析 relay 地址：dart-define > 配置文件 relay.env > 回环默认。
  factory RelayConfig.fromEnvironment() => RelayConfig(baseUrl: _resolveBaseUrl());

  static String _resolveBaseUrl() {
    // 1. 编译期 dart-define（CI / 临时覆盖，最高优先）。
    const fromDefine = String.fromEnvironment('RELAY_BASE_URL');
    if (fromDefine.isNotEmpty) return fromDefine;

    // 2. 配置文件 config/relay.env（main 启动早期 dotenv.load 已载入）。
    //    未初始化（如单测未 load）时回落默认，不抛错。
    final fromFile =
        dotenv.isInitialized ? dotenv.env['RELAY_BASE_URL'] : null;
    if (fromFile != null && fromFile.isNotEmpty) return fromFile;

    // 3. 兜底：Android 模拟器访问宿主机 relay 回环。
    return 'http://10.0.2.2:8080';
  }

  /// WSS 建连 URI（纯路径 `/ws`，token 走首帧 AUTH 不进 URL）。
  Uri get wssUri {
    final ws = baseUrl.replaceFirst(RegExp(r'^https'), 'wss').replaceFirst(
        RegExp(r'^http'), 'ws');
    final trimmed = ws.endsWith('/') ? ws.substring(0, ws.length - 1) : ws;
    return Uri.parse('$trimmed/ws');
  }

  @override
  String toString() => 'RelayConfig(baseUrl: $baseUrl)';
}
