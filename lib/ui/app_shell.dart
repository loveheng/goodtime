import 'package:flutter/material.dart';

import 'plans_page.dart';
import 'quick_note_fab.dart';
import 'schedule_page.dart';
import 'settings_page.dart';
import '../theme/tokens.dart';

/// 主框架（ui-spec §1）：底部双 Tab「日程｜清单」+ 右下角快记 FAB 两 Tab 常驻
/// （2026-10-06 拍板，自顶部常驻快记条迁改；守卫态不撤=「快记永不关门」平移）
/// + 设置右上角进。
class AppShell extends StatefulWidget {
  const AppShell({super.key});

  @override
  State<AppShell> createState() => _AppShellState();
}

class _AppShellState extends State<AppShell> {
  int _tab = 0;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text(_tab == 0 ? '日程' : '清单'),
        actions: [
          IconButton(
            icon: const Icon(Icons.settings_outlined),
            tooltip: '设置',
            onPressed: () => Navigator.of(context).push(
              MaterialPageRoute<void>(builder: (_) => const SettingsPage()),
            ),
          ),
        ],
      ),
      body: SafeArea(
        child: Stack(
          children: [
            Positioned.fill(
              child: _tab == 0 ? const SchedulePage() : const PlansPage(),
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
        ],
      ),
    );
  }
}
