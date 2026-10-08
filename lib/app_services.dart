import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'action/command_handler.dart';
import 'action/commands.dart';
import 'data/repository.dart';
import 'models/artifact.dart';
import 'service/housekeeper.dart';
import 'service/mcp_controller.dart';
import 'ui/fact_sheet.dart';
import 'update/remote_config_store.dart';

/// 应用级服务装配（轻量服务定位器）：main 与测试共用同一初始化路径。
/// 命令层/查询层是无状态包装，用时装配（CommandHandler(repo)/ScheduleQueries(repo)）。
class AppServices {
  AppServices._();

  static Repository? _repo;
  static McpController? _mcp;

  /// 原生分享转投的待发文本（fact 草案 §2.3）：消息早于首帧时挂起，
  /// 首帧 ValueListenableBuilder 消费（冷启动 ACTION_SEND 不丢件）。
  static final ValueNotifier<String> pendingShare = ValueNotifier('');
  static bool _shareRunning = false;

  static Repository get repo => _repo ?? (throw StateError('AppServices 未初始化'));
  static McpController get mcp => _mcp ?? (throw StateError('AppServices 未初始化'));
  static bool get ready => _repo != null;

  static Future<void> init() async {
    if (_repo != null) return;
    final repo = Repository();
    _repo = repo;
    final mcp = McpController(repo: repo);
    _mcp = mcp;
    await mcp.restoreIfNeeded();
    // 远程配置缓存预读（公告/MCP instructions 热更，docs/guide/self-update.md）
    await RemoteConfigStore.instance.load();
    // 冷启动日切（幂等，每作息日至多一次）：missed/作废迁移先行，digest 随取随算
    await Housekeeper(repo).dailyCutIfNeeded();
  }

  /// Flutter 侧分享通道（与 MainActivity SHARE_CHANNEL 同名）：原生只转投
  /// EXTRA_TEXT 字节串（零解析纪律在命令层），文本挂 pendingShare 等首帧消费。
  static Future<void> initFlutter() async {
    BasicMessageChannel<dynamic>(
            'app.shiguang/sharing', const StandardMessageCodec())
        .setMessageHandler((raw) async {
      // 原生侧投 JSON 串 {"text": "…"}（StandardMessageCodec 解出 String）
      String? text;
      if (raw is String) {
        try {
          text = (json.decode(raw) as Map?)?['text'] as String?;
        } on FormatException {
          text = raw; // 非 JSON 直通（测试注入）
        }
      }
      if (text == null || text.trim().isEmpty) return;
      pendingShare.value = text;
    });
  }

  /// 消费待发分享（§2.3）：raw 入册（origin=shared，state=raw，title 取首行
  /// ≤40 字）→ 挂载 sheet 归属。[context] 空=首帧未就绪，文本留池等下轮；
  /// 归属不阻断（未归属池兜底，sheet 可直接「先记下」）。
  static Future<void> runPendingShare(BuildContext? context) async {
    final text = pendingShare.value.trim();
    if (text.isEmpty || _shareRunning) return;
    _shareRunning = true;
    try {
      final firstLine = text.split(RegExp(r'[\r\n]')).first.trim();
      final title = firstLine.length <= 40 ? firstLine : firstLine.substring(0, 40);
      final r = await CommandHandler(repo).execute(
        UpsertFactsCommand(
          // raw 建档：命令层强制 verbal/verbal 占位（出生确认条款 §1.2），
          // 提炼回填时 AI 定 category/source_kind 终值
          category: Artifact.categoryVerbal,
          sourceKind: Artifact.sourceVerbal,
          rawText: text,
          title: title,
          origin: Artifact.originShared,
          state: Artifact.stateRaw,
        ),
        actor: CommandActor.human,
      );
      if (context == null) return; // 已入池，挂起等首帧 sheet
      final fact = r.targetId == null ? null : await repo.artifactById(r.targetId!);
      if (fact == null || !context.mounted) return;
      await showFactMountSheet(context, fact: fact);
    } finally {
      pendingShare.value = '';
      _shareRunning = false;
    }
  }
}
