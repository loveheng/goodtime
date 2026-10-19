import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'action/command_handler.dart';
import 'action/commands.dart';
import 'collab/account.dart';
import 'collab/crypto/key_store.dart';
import 'collab/crypto/secure_secret_store.dart';
import 'collab/identity_store.dart';
import 'collab/runtime.dart';
import 'collab/relay/client.dart';
import 'collab/relay_config.dart';
import 'data/repository.dart';
import 'data/db.dart';
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

  /// 协同密钥托管（轻量惰性装配）。
  ///
  /// **只构造 KeyStore，不读安全区**：`KeyStore(SecureSecretStore())` 本身不触碰
  /// FlutterSecureStorage，真实读写发生在 [KeyStore.ensureReady]（登录后由
  /// [attachCollabLifecycle] 触发）。AppServices.init 的热路径（含单测）不碰平台插件。
  static KeyStore get keys => _keys ??= KeyStore(SecureSecretStore());
  static KeyStore? _keys;

  /// 协同账号会话（登录态单一来源，惰性构造不触网）。
  static AccountSession get account => _account ??= AccountSession(
        dio: relayHttpClient(),
        baseUrl: RelayConfig.fromEnvironment().baseUrl,
        keyStore: keys,
        identityStore: SecureIdentityStore(),
        secrets: SecureSecretStore(),
      );
  static AccountSession? _account;

  /// 协同常驻回路（App 级单例；冷启动挂引擎 Phase 4.3b）。
  static CollabRuntime get collab => _collab ??= CollabRuntime();
  static CollabRuntime? _collab;

  /// 把协同回路与登录态绑定：登录即备钥，登出即停回路。
  ///
  /// 必须在 main 入口调用一次（构造不读安全区/不触网，冷启动零负担）；
  /// 重复调用只挂一次监听。
  static void attachCollabLifecycle() {
    if (_collabBound) return;
    _collabBound = true;
    account.addListener(_onAccountChange);
  }

  static bool _collabBound = false;

  static Future<void> _onAccountChange() async {
    try {
      if (account.isLoggedIn) {
        // 登录即就绪 MSK（幂等）：后续 seal/open 不再等待。
        await keys.ensureReady();
        // 刻意不起 [collab.start]（常驻引擎 WSS/sync）。该路径此前在 App 里因
        // rec==null 从未真正起过引擎，属未联调代码；好友页只依赖本地身份 +
        // 登录态 RelaySession，不依赖常驻引擎，待引擎单独联调稳定后再恢复。
      } else {
        // 登出：停回路（目前引擎未起，stop 为空转安全调用）。
        await collab.stop();
      }
    } catch (_) {
      // 装配失败（中继不可达 / 库异常）不致命：下次登录或前台再试
    }
  }

  static Future<void> init() async {
    if (_repo != null) return;
    // 默认存储工厂先行注册（Repository 内核不 import sqflite，轴 B G2·②）：
    // 闭包惰性求值，真正建库发生在首个 DB 操作时。
    Db.registerDefaultStorageFactory();
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
