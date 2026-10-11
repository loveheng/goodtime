/// 协同编排收口引擎（Phase 4 编排收口项：三件套收口 + 真 relay 联调）。
///
/// 把零散的 Phase 2/3 组件焊接成一条**常驻运行**的协同回路：
/// - `SingleConsumerPipeline`：WSS 实时推送 + `/sync` 补拉两路汇入同一条串行 FIFO；
/// - `WakePuller.sinceSeqOf` ← [CollabBundleRepository.maxSeqId]（游标接 collab 表，
///   进程被杀后从本地水位精准续拉，不重复拖全量）；
/// - 订阅 `pipeline.applied` → [CollabBundleRepository.recordEvent]（落库水位 + 原
///   始事件，供 FSM 重放；这里只保真落库，不解密）；
/// - `RelayWssClient.attachWss` 接入实时流；
/// - `CollabDrainScheduler` 把 [OutboxDrainer] 挂到连通性监听（网络恢复自动弹出）。
///
/// 真 relay 联调：[wireCollabSystem] 以 [RelayConfig]（= `RELAY_BASE_URL`）为唯一
/// 地址源，统一构造 WSS / `/sync` / `/v1/schedule/action` 三路传输，杜绝 base URL
/// 散落。
library;

import 'dart:async';

import 'package:dio/dio.dart';
import 'package:sqflite/sqflite.dart';

import '../data/collab_repository.dart';
import 'crypto/bundle_key_source.dart' show BundleKeySource;
import 'connectivity.dart';
import 'outbox.dart';
import 'pipeline.dart';
import 'propose.dart' show PendingProposalReconciler;
import 'relay/action_api.dart' show HttpOutboxTransport;
import 'relay/sync_api.dart' show fetchSyncPage, SyncPage;
import 'relay/wss_client.dart' show RelayWssClient, TokenProvider;
import 'relay_config.dart' show RelayConfig;
import 'wake_puller.dart' show WakePuller, WakePayload;

/// 协同编排收口引擎。
///
/// 持有全部常驻协作者，[start] 把它们焊接成回路，[dispose] 统一释放。组件由
/// [wireCollabSystem] 从 [RelayConfig] 统一构造，或测试注入脚本化替身。
class CollabEngine {
  final SingleConsumerPipeline pipeline;
  final CollabBundleRepository repo;
  final WakePuller wakePuller;
  final CollabDrainScheduler drainScheduler;
  final RelayWssClient? wssClient;

  StreamSubscription<CollabInboundEvent>? _appliedSub;

  /// 某个包的事件首次到达时的钩子（被邀端密钥入户的触发点）。
  ///
  /// 由 [PendingProposalReconciler] 的同名纪律约束：只补一次（[\_seen] 去重），
  /// 失败静默——解不开的那段历史按半盲处理，骨架照常推进。
  final Future<void> Function(String bundleId)? onBundleSeen;
  final Set<String> _seen = <String>{};

  CollabEngine({
    required this.pipeline,
    required this.repo,
    required this.wakePuller,
    required this.drainScheduler,
    this.wssClient,
    this.onBundleSeen,
  });

  /// 在途落库 future 集合：确保 [dispose] 等所有 in-flight 写入后再关库，杜绝
  /// 「管道异步应用晚于关闭」的 database_closed（pullBundle 推入的增量事件由管道
  /// 异步 apply，Test/退出窗口尤易踩）。
  final List<Future<void>> _inflight = [];

  /// 焊接回路：接入 WSS 实时流 + 订阅 applied 落库 + 启动常驻发件箱调度。
  void start() {
    if (wssClient != null) pipeline.attachWss(wssClient!);
    _appliedSub = pipeline.applied.listen(_onApplied);
    drainScheduler.start();
  }

  Future<void> _onApplied(CollabInboundEvent e) async {
    final f = repo.recordEvent(
      bundleId: e.bundleId,
      seqId: e.seqId,
      actionType: e.action.name,
      senderId: e.senderHint ?? '',
      cipher: e.cipher ?? const [],
      keyVer: e.keyVer ?? 1,
      clientMsgId: e.clientMsgId ?? '${e.bundleId}:${e.seqId}',
      topologyRef: e.topologyRef,
      serverAtMs: e.serverAtMs,
    );
    _inflight.add(f);
    try {
      await f;
    } finally {
      _inflight.remove(f);
    }
    // 收到某包第一条事件 = 我被拉进了一个包（被邀）：趁在线补拉我的 K_bundle 信封
    if (onBundleSeen != null && _seen.add(e.bundleId)) {
      final g = onBundleSeen!(e.bundleId);
      _inflight.add(g);
      try {
        await g;
      } catch (_) {
        // 入户失败（未解锁/无网络/密钥缺失）全部按半盲降级，绝不拖垮落库回路
      } finally {
        _inflight.remove(g);
      }
    }
  }

  /// 静默唤醒入口（系统推送唤醒 → Wake Pull 精准补拉，已落库水位续拉）。
  Future<int> onWake(WakePayload wake) => wakePuller.onWake(wake);

  /// 主动补拉一个包（冷启动 / 手动）。
  Future<int> pullBundle(String bundleId, {int? latestSeqHint}) =>
      wakePuller.pullBundle(bundleId, latestSeqHint: latestSeqHint);

  /// 触发一轮发件箱弹出（回前台 / 心跳 / 手动同步）。
  Future<DrainReport> drainNow() => drainScheduler.trigger();

  /// 统一释放全部常驻资源。
  Future<void> dispose() async {
    await _appliedSub?.cancel();
    // 等所有在途落库写完后才关库，避免 database_closed（见 [_inflight]）
    await Future.wait(_inflight);
    await drainScheduler.dispose();
    wssClient?.dispose();
    await pipeline.close();
  }
}

/// 以 [RelayConfig]（= 编译期 `RELAY_BASE_URL`）为唯一地址源，构造并焊接整套协同
/// 回路，返回可直接 [CollabEngine.start] 的常驻引擎。
///
/// 三路传输统一吃 [config] 的 base URL（HTTP 基址 → WSS `/ws` 纯路径由
/// [RelayConfig.wssUri] 推导），真 relay 联调只需改 `RELAY_BASE_URL` 一处。
///
/// 网络相关组件（传输/WSS/pull）可在测试时经 override 注入脚本化替身，避免触碰真网。
CollabEngine wireCollabSystem({
  required RelayConfig config,
  required Database db,
  required Dio dio,
  required TokenProvider tokenProvider,
  required Future<List<int>> Function(String bundleId, int keyVer) bundleKey,
  required ConnectivityWatcher connectivity,

  /// 本地发起 bundle 的密钥检索源；给值即自动挂上认祖归宗钩子。
  ///
  /// 不给（默认）时本引擎仍是纯「接收 + 弹出」回路；要承载本地发起就必须给值——
  /// 密钥信封的临时 id → 服务端发号迁移需要它（见 `propose.dart`）。
  BundleKeySource? bundleKeys,

  /// 某包事件首次到达的钩子（被邀端密钥入户触发点）。
  Future<void> Function(String bundleId)? onBundleSeen,
  OutboxTransport? transportOverride,
  Future<SyncPage> Function(String bundleId, int sinceSeq)? pullOverride,
  RelayWssClient? wssOverride,
}) {
  final pipeline = SingleConsumerPipeline();
  final repo = CollabBundleRepository(db);
  final wssClient = wssOverride ??
      RelayWssClient(wsUri: config.wssUri, getToken: tokenProvider);
  final pull = pullOverride ??
      (String bundleId, int sinceSeq) async => fetchSyncPage(
            dio,
            config.baseUrl,
            await tokenProvider(), // token 缺失由 transport 层归一化 Offline
            bundleId,
            sinceSeq,
          );
  final wakePuller = WakePuller(
    pipeline: pipeline,
    pull: pull,
    sinceSeqOf: repo.maxSeqId, // 游标接 collab 表
  );
  final transport = transportOverride ??
      HttpOutboxTransport(
        dio: dio,
        baseUrl: config.baseUrl,
        accessToken: tokenProvider,
        bundleKey: bundleKey,
      );
  final outboxRepo = OutboxRepository(db);
  PendingProposalReconciler? reconciler;
  if (bundleKeys != null) {
    reconciler = PendingProposalReconciler(
      bundles: repo,
      keys: bundleKeys,
      outbox: outboxRepo,
    );
  }
  final drainer = OutboxDrainer(
    outboxRepo,
    transport,
    onBundleCreated: reconciler?.call,
  );
  final drainScheduler = CollabDrainScheduler(drainer, connectivity);
  return CollabEngine(
    pipeline: pipeline,
    repo: repo,
    wakePuller: wakePuller,
    drainScheduler: drainScheduler,
    wssClient: wssClient,
    onBundleSeen: onBundleSeen,
  );
}
