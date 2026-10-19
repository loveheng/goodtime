import 'package:flutter/material.dart';
import 'package:flutter_dotenv/flutter_dotenv.dart';

import 'app_services.dart';
// Binder 插件后台入口（headless FlutterEngine 经 ShiguangMcpBinderService 拉起）。
// 必须 import 以使该库编译进 app kernel，否则 DartEntrypoint 解析不到 mcpBinderMain。
// （已标 @pragma('vm:entry-point') 防 tree-shake，静态无直接引用 → 忽略 unused_import）
// ignore: unused_import
import 'mcp_binder_entrypoint.dart';
import 'data/settings.dart';
import 'theme/tokens.dart';
import 'ui/app_gate.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  // 联调配置：从 config/relay.env 读取真 relay 地址（RELAY_BASE_URL），
  // 不必写进 flutter run 命令；--dart-define 仍可临时覆盖。
  await dotenv.load(fileName: 'config/relay.env');
  await AppServices.init();
  await AppServices.initFlutter();
  // 协同回路随登录态起停：登录即备钥、登出即停（Phase 4.3b；2026-10-11 起
  // 门禁=登录态）。account 构造不读安全区/不触网，冷启动零负担。
  AppServices.attachCollabLifecycle();
  // 冷启动恢复登录态（只读安全区，不触网）：协同各入口门禁现查
  // isLoggedIn——不先 restore 会把已登录误判成未登录，先进好友页就被
  // 引导去「登录」，其实会话一直在。
  await AppServices.account.restore();
  runApp(const ShiguangApp());
}

/// 拾光 App 根（ui-design §1–§3：Material 3 + 固定品牌色板、关闭动态取色；
/// 2026-10-06 拍板解锁双主题：跟随系统/浅色/深色三档，settings.theme_mode）。
class ShiguangApp extends StatefulWidget {
  const ShiguangApp({super.key});

  @override
  State<ShiguangApp> createState() => _ShiguangAppState();
}

class _ShiguangAppState extends State<ShiguangApp> {
  ThemeMode _mode = ThemeMode.system;

  @override
  void initState() {
    super.initState();
    AppServices.repo.addListener(_reload);
    _reload();
  }

  @override
  void dispose() {
    AppServices.repo.removeListener(_reload);
    super.dispose();
  }

  /// 设置页经 UpdateSettingsCommand 写 theme_mode 后由 repo 通知重读。
  Future<void> _reload() async {
    final raw = await AppServices.repo.settingsGet(SettingsKeys.themeMode);
    final mode = switch (raw) {
      'light' => ThemeMode.light,
      'dark' => ThemeMode.dark,
      _ => ThemeMode.system,
    };
    if (!mounted || mode == _mode) return;
    setState(() => _mode = mode);
  }

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: AppServices.repo,
      builder: (context, _) {
        final darkNow = _mode == ThemeMode.dark ||
            (_mode == ThemeMode.system &&
                WidgetsBinding
                    .instance.platformDispatcher.platformBrightness ==
                    Brightness.dark);
        // 全局语义面板随主题置位（单窗口单主题，tokens.dart 纪律）。
        StColors.applyBrightness(
            darkNow ? Brightness.dark : Brightness.light);
        return MaterialApp(
          title: '拾光',
          debugShowCheckedModeBanner: false,
          themeMode: _mode,
          theme: ThemeData(
            useMaterial3: true,
            colorScheme: ColorScheme.fromSeed(seedColor: StColors.deepFocusBg),
            scaffoldBackgroundColor: Colors.white,
          ),
          darkTheme: ThemeData(
            useMaterial3: true,
            colorScheme: ColorScheme.fromSeed(
                seedColor: StColors.deepFocusBg, brightness: Brightness.dark),
          ),
          home: const AppGate(),
        );
      },
    );
  }
}
