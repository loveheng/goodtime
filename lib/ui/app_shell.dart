import 'dart:async';

import 'package:flutter/material.dart';

import 'plans_page.dart';
import 'quick_note_fab.dart';
import 'schedule_page.dart';
import 'settings_page.dart';
import '../app_services.dart';
import '../theme/tokens.dart';

/// 主框架（ui-spec §1）：底部三 Tab「日程｜清单｜设置」+ 右下角快记 FAB 两 Tab
/// 常驻（2026-10-06 拍板，自顶部常驻快记条迁改；守卫态不撤=「快记永不关门」
/// 平移）。2026-10-08 用户拍板：设置自 AppBar 右上角迁入底部第三 Tab（§11 留痕）。
class AppShell extends StatefulWidget {
  const AppShell({super.key});

  @override
  State<AppShell> createState() => _AppShellState();
}

class _AppShellState extends State<AppShell> {
  int _tab = 0;
  StreamSubscription<void>? _sugSub;

  @override
  void initState() {
    super.initState();
    _sugSub = AppServices.mcp.suggestionStream.listen((s) {
      if (!mounted) return;
      final key = s['key']?.toString() ?? '';
      final reason = s['reason']?.toString() ?? '';
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(
        content: Text('AI 建议修改「$key」：$reason'),
        action: SnackBarAction(
          label: '去设置',
          onPressed: () => Navigator.of(context)
              .push(MaterialPageRoute<void>(builder: (_) => const SettingsPage())),
        ),
        duration: const Duration(seconds: 8),
      ));
    });
  }

  @override
  void dispose() {
    _sugSub?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text(_tab == 0 ? '日程' : (_tab == 1 ? '清单' : '设置')),
      ),
      body: SafeArea(
        child: Stack(
          children: [
            Positioned.fill(
              child: _tab == 0
                  ? const SchedulePage()
                  : (_tab == 1 ? const PlansPage() : const SettingsPage()),
            ),
            const Positioned(
              right: StScale.insetLg,
              bottom: StScale.insetLg,
              child: QuickNoteFab(),
            ),
          ],
        ),
      ),
      bottomNavigationBar: NavigationBar(
        selectedIndex: _tab,
        onDestinationSelected: (i) => setState(() => _tab = i),
        destinations: const [
          NavigationDestination(icon: Icon(Icons.calendar_today_outlined), label: '日程'),
          NavigationDestination(icon: Icon(Icons.checklist_outlined), label: '清单'),
          NavigationDestination(icon: Icon(Icons.settings_outlined), label: '设置'),
        ],
      ),
    );
  }
}
