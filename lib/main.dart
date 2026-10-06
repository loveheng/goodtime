import 'package:flutter/material.dart';

import 'app_services.dart';
import 'data/settings.dart';
import 'theme/tokens.dart';
import 'ui/app_gate.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await AppServices.init();
  runApp(const ShiguangApp());
}

/// 拾光 App 根（ui-spec §0：Material 3 + 固定品牌色板、关闭动态取色；
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
