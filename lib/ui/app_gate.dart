import 'dart:async';

import 'package:flutter/material.dart';

import '../action/queries.dart';
import '../app_services.dart';
import 'app_shell.dart';
import 'onboarding_page.dart';

/// 入口闸门：settings 未初始化（缺作息边界）→ 首启引导；完成即进主框架。
/// 引导是 propose 硬拒（§6 校验铁律）的 UI 前置（ui-spec §2）。
///
/// 决策响应式：初始查一次，此后随 repo 写入通知（revision 变化）重查——
/// 引导完成播种 / 设置被清空都能即时翻转，不依赖回调续跑。
class AppGate extends StatefulWidget {
  const AppGate({super.key});

  @override
  State<AppGate> createState() => _AppGateState();
}

class _AppGateState extends State<AppGate> {
  bool? _initialized;
  int _seenRevision = -1;

  @override
  void initState() {
    super.initState();
    _reload();
  }

  void _reload() {
    ScheduleQueries(AppServices.repo)
        .getSettings()
        .then((s) {
      if (!mounted) return;
      setState(() => _initialized = s['initialized'] as bool? ?? false);
    });
  }

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: AppServices.repo,
      builder: (context, _) {
        final revision = AppServices.repo.revision;
        if (revision != _seenRevision) {
          _seenRevision = revision;
          scheduleMicrotask(_reload);
        }
        return _decision();
      },
    );
  }

  Widget _decision() {
    final initialized = _initialized;
    if (initialized == null) {
      return const Scaffold(body: Center(child: CircularProgressIndicator()));
    }
    if (!initialized) return OnboardingPage(onDone: _reload);
    return const AppShell();
  }
}
