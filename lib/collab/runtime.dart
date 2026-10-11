/// 协同常驻运行时（Phase 4.3b：App 冷启动挂引擎）。
///
/// 此前 `wireCollabSystem` 只在测试里被组装过——App 里没有任何地方启动它，于是
/// 发件箱**只有发起页手动 drainNow 时才会弹出一次**，网络恢复不补发、WSS 不建连、
/// 被邀端收不到推送。本类把「什么时候该起、什么时候该停」说清楚：
///
/// - **起**：本机有协同身份（IdentityRecord）。MSK 由 [KeyStore] 端侧自动托管、
///   token 由账号会话颁发，均不再是起停前置（2026-10-11 拍板：门禁=登录态，
///   由调用方在起之前判定）；
/// - **停**：登出 / 应用销毁立即停；
/// - **幂等**：[start] 重复调用不重复建连，[stop] 可安全重复。
///
/// 展望：好友域 UI 落地后，未注册身份时也可只起「接收侧」——本版先守最小可用面。
library;

import 'dart:io';

import 'package:dio/dio.dart';
import 'package:sqflite/sqflite.dart';

import 'account.dart';
import 'connectivity.dart';
import 'crypto/bundle_key_source.dart';
import 'crypto/key_store.dart';
import 'engine.dart';
import 'enroll.dart';
import 'identity_store.dart';
import 'outbox.dart';
import 'relay/keys_api.dart';
import 'relay/peers_api.dart';
import 'relay/client.dart';
import 'relay_config.dart';
import 'relay_session.dart';

/// App 级协同回路持有者（单例；全部依赖由调用方注入，不读全局静态）。
class CollabRuntime {
  CollabEngine? _engine;
  RelaySession? _session;
  ConnectivityWatcher? _watcher;
  BundleKeyIntake? _intake;
  bool _starting = false;

  /// 已尝试过入户的包（本次进程内不重复拉，[stop] 后清空）。
  final Set<String> _enrolled = <String>{};

  bool get running => _engine != null;

  /// 起常驻回路；返回 false = 前置不满足（无身份），调用方据此提示。
  Future<bool> start({
    required Database db,
    required KeyStore keyStore,
    required AccountSession account,
    IdentityStore? identityStore,
    Dio? dio,
    String? baseUrl,
  }) async {
    if (_engine != null) return true;
    if (_starting) return false; // 并发 start 由首调用者完成
    _starting = true;
    try {
      final store = identityStore ?? SecureIdentityStore();
      final rec = await store.load();
      if (rec == null) return false;

      final client = dio ?? relayHttpClient();
      final config = RelayConfig(baseUrl: baseUrl ?? RelayConfig.fromEnvironment().baseUrl);
      final session = RelaySession(
        dio: client,
        baseUrl: config.baseUrl,
        identityStore: store,
        account: account,
      );
      final keys = BundleKeySource(db, keyStore);
      final watcher = PollingConnectivityWatcher(probe: () => _probe(config.baseUrl));
      final intake = BundleKeyIntake(
        fetch: (bundleId) async => fetchBundleKeyEnvelopes(
          client,
          config.baseUrl,
          await session.accessToken(),
          bundleId,
        ),
        keys: keys,
        mySignedPreKey: () => restoreSignedPreKeyPair(keyStore: keyStore, record: rec),
      );

      final engine = wireCollabSystem(
        config: config,
        db: db,
        dio: client,
        tokenProvider: session.accessToken,
        bundleKey: (bundleId, keyVer) async {
          final key = await keys(bundleId, keyVer);
          if (key == null || key.length != 32) {
            throw StateError('K_bundle 取不到：半盲，等补拉后再发');
          }
          return key;
        },
        connectivity: watcher,
        bundleKeys: keys,
        onBundleSeen: (bundleId) async {
          if (!_enrolled.add(bundleId)) return;
          await intake.enroll(bundleId);
        },
      );
      engine.start();

      _engine = engine;
      _session = session;
      _watcher = watcher;
      _intake = intake;
      return true;
    } catch (_) {
      // 装配失败（中继不可达等）不算致命：下次登录/前台再试
      return false;
    } finally {
      _starting = false;
    }
  }

  /// 停常驻回路（登出 / 应用销毁）。可安全重复调用。
  Future<void> stop() async {
    final engine = _engine;
    _engine = null;
    _session = null;
    _watcher = null;
    _intake = null;
    _enrolled.clear();
    if (engine != null) await engine.dispose();
  }

  /// 当前探针判定的连通性（未起回路时恒 false，供 UI 显示「离线」）。
  Future<bool> get online async => await _watcher?.isOnline ?? false;

  /// 手动补拉某包的密钥信封（如「解不开」提示旁的「重试」）。
  Future<int> enrollBundle(String bundleId) async =>
      _intake == null ? 0 : _intake!.enroll(bundleId);

  /// 主动同步一轮（回前台 / 下拉刷新）。
  Future<DrainReport?> drainNow() async => _engine?.drainNow();

  /// 当前好友名单（发起页复用；未起回路时返回空）。
  Future<List<FriendView>> friends() async {
    final session = _session;
    if (session == null) return const [];
    try {
      return await session.acceptedFriends();
    } catch (_) {
      return const [];
    }
  }

  /// 连通性探针：直接问 relay 的 `/v1/healthz`（比 DNS 更准——DNS 通不代表服务在）。
  static Future<bool> _probe(String baseUrl) async {
    try {
      final host = Uri.parse(baseUrl).host;
      final socket =
          await Socket.connect(host, Uri.parse(baseUrl).port, timeout: const Duration(seconds: 2));
      socket.destroy();
      return true;
    } catch (_) {
      return false;
    }
  }
}
