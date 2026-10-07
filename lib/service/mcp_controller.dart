import 'dart:math';
import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:bonsoir/bonsoir.dart';
import 'package:flutter_foreground_task/flutter_foreground_task.dart';

import '../data/repository.dart';
import '../data/settings.dart';
import '../mcp/mcp_server.dart';
import '../update/remote_config_store.dart';
import '../util/lan_ip.dart';
import 'foreground_task_init.dart';

/// MCP 服务总控（抄改自拾贝 mcp_controller，§12）：token 与开关持久化 + 内嵌 HTTP server。
///
/// M1 口径：无前台服务保活（管家 M2 随 flutter_foreground_task 接入，届时补
/// Doze 下持 CPU 锁+Wi-Fi 锁的踩坑结论）；token/开关走 app_settings KV
/// （`mcp_token`/`mcp_enabled` 为内部键，不经 update_settings 命令白名单）。
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
  BonsoirBroadcast? _mdns;
  String? _token;
  String? _lastError;

  bool get running => _server?.running ?? false;
  int get port => _server?.port ?? defaultPort;
  String? get token => _token;
  String? get lastError => _lastError;

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

  /// 当前 LAN 端点；无 Wi-Fi 时回退 127.0.0.1（仅 adb reverse 场景可用）。
  Future<String> endpoint() async {
    final ip = await lanIpv4();
    return 'http://${ip ?? '127.0.0.1'}:$defaultPort/mcp';
  }

  /// 打开 MCP 服务。返回 null 表示成功，否则为错误信息。
  Future<String?> enable() async {
    _lastError = null;
    try {
      // 前台服务保活（Doze 下 MCP 应答须持 CPU/Wi-Fi 锁，见 foreground_task_init）；
      // VM 测试/平台未就绪时跳过，仅启 HTTP（fail-open，不阻断服务）。
      try {
        await ensureForegroundTaskInit();
        final addr = await lanIpv4();
        await FlutterForegroundTask.startService(
          serviceTypes: [ForegroundServiceTypes.dataSync],
          notificationTitle: '拾光 · MCP 服务运行中',
          notificationText: 'http://${addr ?? '127.0.0.1'}:$defaultPort/mcp（地址见 app 内 MCP 页）',
        );
      } catch (e) {
        // DEGRADE: [fg_unavailable] 前台服务不可用（VM 测试/平台未就绪）——仅 HTTP 无保活
        debugPrint('[DEGRADE][fg_unavailable] 前台服务未启动: $e');
      }
      _server ??= McpServer(
        repo: repo,
        tokenProvider: () => _token,
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
      await _server!.start(port: defaultPort);
      // mDNS 广播（§10/§11 网络配对：消灭「Connection Refused」在用户修网络之前）。
      // UNCERTAIN: bonsoir 新集成未真机实证；不可用时静默降级走手动 IP 兜底（拍板真）。
      try {
        final broadcast = BonsoirBroadcast(
          service: BonsoirService(
            name: 'shiguang-mcp',
            type: '_shiguang-mcp._tcp.',
            port: defaultPort,
          ),
        );
        await broadcast.ready;
        await broadcast.start();
        _mdns = broadcast;
      } catch (e) {
        // DEGRADE: [mdns_unavailable] mDNS 广播不可用（VM 测试/权限/网络）——手动 IP 兜底
        debugPrint('[DEGRADE][mdns_unavailable] mDNS 广播未启动: $e');
      }
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
      await _server?.stop();
    } catch (_) {/* 服务可能已停止 */}
    _server = null;
    try {
      await _mdns?.stop();
    } catch (_) {/* 广播可能已停止 */}
    _mdns = null;
  }
}
