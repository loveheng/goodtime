import 'dart:math';
import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter_foreground_task/flutter_foreground_task.dart';

import '../data/repository.dart';
import '../data/settings.dart';
import '../mcp/mcp_server.dart';
import 'http_transport.dart';
import 'relay_client.dart';
import 'weather.dart';
import '../update/remote_config_store.dart';
import 'foreground_task_init.dart';

/// MCP 服务总控（抄改自拾贝 mcp_controller，§12）：token 与开关持久化 + 内嵌 HTTP server。
///
/// 暴露面口径（2026-10-15 拍板，schedule-app §11「MCP 暴露面」行）：生产通道 =
/// Binder 宿主进程内嵌入（mcp_binder_entrypoint，不经本控）；本控的 HTTP 服务
/// 仅绑 127.0.0.1 回环供 adb reverse 内部调试——不广播 mDNS、不展示 LAN 地址。
/// token/开关走 app_settings KV（`mcp_token`/`mcp_enabled` 为内部键，不经
/// update_settings 命令白名单）；前台服务保活（Doze 下持 CPU 锁+Wi-Fi 锁，
/// 见 foreground_task_init）。
class McpController extends ChangeNotifier {
  McpController({required this.repo});

  final Repository repo;

  /// AI 经 suggest_user_setting 推送的「建议用户改设置」广播通道（app UI 监听弹提示）。
  final StreamController<Map<String, Object?>> _suggestionController =
      StreamController<Map<String, Object?>>.broadcast();
  Stream<Map<String, Object?>> get suggestionStream => _suggestionController.stream;

  static const defaultPort = 8765;

  /// app_settings 内部键（服务自身状态，非用户设置项）。
  static const _keyToken = 'mcp_token';
  static const _keyEnabled = 'mcp_enabled';

  McpServer? _server;
  HttpTransport? _transport;
  RelayClient? _relay;
  String? _token;
  String? _lastError;

  bool get running => _transport?.running ?? false;
  int get port => _transport?.port ?? defaultPort;
  String? get token => _token;
  String? get lastError => _lastError;

  /// 调试端点（仅回环可达；桌面经 `adb reverse tcp:8765 tcp:8765` 接入）。
  String get endpoint => 'http://127.0.0.1:$defaultPort/mcp';

  /// 冷启动装配：读/生成 token，并按上次开关状态自动恢复服务。
  Future<void> restoreIfNeeded() async {
    _token = await repo.settingsGet(_keyToken);
    if (_token == null || _token!.isEmpty) {
      _token = _generateToken();
      await repo.settingsSet({_keyToken: _token!});
    }
    if ((await repo.settingsGet(_keyEnabled)) != '1') return;
    final error = await enable();
    if (error != null) {
      debugPrint('[MCP] 冷启动自动恢复失败: $error');
    }
  }

  Future<void> regenerateToken() async {
    _token = _generateToken();
    await repo.settingsSet({_keyToken: _token!});
    notifyListeners();
  }

  String _generateToken() {
    final rnd = Random.secure();
    return List.generate(24, (_) => rnd.nextInt(16).toRadixString(16)).join();
  }

  /// 打开 MCP 服务。返回 null 表示成功，否则为错误信息。
  Future<String?> enable() async {
    _lastError = null;
    try {
      // 前台服务保活（Doze 下 MCP 应答须持 CPU/Wi-Fi 锁，见 foreground_task_init）；
      // VM 测试/平台未就绪时跳过，仅启 HTTP（fail-open，不阻断服务）。
      try {
        await ensureForegroundTaskInit();
        await FlutterForegroundTask.startService(
          serviceTypes: [ForegroundServiceTypes.dataSync],
          notificationTitle: '拾光 · MCP 服务运行中',
          notificationText: '仅本机回环（内部调试）；桌面先 adb reverse tcp:$defaultPort tcp:$defaultPort',
        );
      } catch (e) {
        // DEGRADE: [fg_unavailable] 前台服务不可用（VM 测试/平台未就绪）——仅 HTTP 无保活
        debugPrint('[DEGRADE][fg_unavailable] 前台服务未启动: $e');
      }
      _server ??= McpServer(
        repo: repo,
        tokenProvider: () => _token,
        // 注入真实天气后端（dart:io 网络在 lib/service，内核不沾）
        weather: weatherService,
        // AI 经 suggest_user_setting 推送建议时，广播给 app UI 弹提示
        onSuggestion: (args) {
          if (!_suggestionController.isClosed) _suggestionController.add(args);
        },
        // 系统提示词热更入口：远程下发的 mcpInstructions 可覆盖内置政策精华；
        // 用户自填身份画像（identity_prompt）始终拼接待尊重（即配即用）。
        instructionsProvider: () async {
          final remote =
              RemoteConfigStore.instance.current.mcpInstructions;
          final base = remote != null && remote.trim().isNotEmpty
              ? remote.trim()
              : McpServer.builtinInstructions;
          final prompt = await repo.settingsGet(SettingsKeys.identityPrompt);
          if (prompt != null && prompt.trim().isNotEmpty) {
            return '$base\n\n'
                '【用户身份画像】（由用户在 app 内填写，描述其自身身份与处境，排程时务必尊重）\n$prompt';
          }
          return base;
        },
      );
      _transport ??= HttpTransport(server: _server!);
      await _transport!.start(port: defaultPort);
      // mDNS 广播随 2026-10-15 暴露面拍板退役（回环服务无需 LAN 发现，§11）。
      // relay 传输（轴 C）：配置齐全才挂载——与 HTTP 并列的第二种传输，fail-open
      // （relay 不可达不阻断回环 HTTP 通道；schedule-app.md:331「手机不在线 AI 失联」同口径）。
      await _startRelay();
      await repo.settingsSet({_keyEnabled: '1'});
    } catch (e) {
      _lastError = '$e';
      await _shutdown();
      notifyListeners();
      return _lastError;
    }
    notifyListeners();
    return null;
  }

  Future<void> disable() async {
    await _shutdown();
    await repo.settingsSet({_keyEnabled: '0'});
    notifyListeners();
  }

  Future<void> _shutdown() async {
    try {
      await _transport?.stop();
    } catch (_) {/* 服务可能已停止 */}
    _transport = null;
    _server = null;
    try {
      await _relay?.stop();
    } catch (_) {/* relay 可能已断开 */}
    _relay = null;
  }

  /// relay 通道装配：读取 app_settings 配置（RelayKeys），齐全才建连。
  Future<void> _startRelay() async {
    if (await repo.settingsGet(RelayKeys.enabled) != '1') return;
    final cfg = RelayConfig(
      endpoint: await repo.settingsGet(RelayKeys.endpoint) ?? '',
      username: await repo.settingsGet(RelayKeys.username) ?? '',
      password: await repo.settingsGet(RelayKeys.password) ?? '',
    );
    if (!cfg.isComplete) return;
    _relay ??= RelayClient(
      handleJsonRpc: _server!.handleJsonRpc,
      mcpToken: () => _token,
      config: () => cfg,
    );
    await _relay!.start();
    // 重连 drain：清掉离线期间积压的 mcp 单（发方已按 delivered_online 判败）
    await _relay!.drainPending();
  }
}
