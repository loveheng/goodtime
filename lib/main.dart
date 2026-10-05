import 'package:flutter/material.dart';

import 'app_services.dart';
import 'theme/tokens.dart';
import 'ui/app_gate.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await AppServices.init();
  runApp(const ShiguangApp());
}

/// 拾光 App 根（ui-spec §0：Material 3 + 固定品牌色板、关闭动态取色、
/// MVP 锁定 Light Mode——不设 darkTheme 即锁定）。
class ShiguangApp extends StatelessWidget {
  const ShiguangApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: '拾光',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        useMaterial3: true,
        colorScheme: ColorScheme.fromSeed(seedColor: StColors.deepFocusBg),
        scaffoldBackgroundColor: Colors.white,
      ),
      home: const AppGate(),
    );
  }
}
