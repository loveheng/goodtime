/// 网络连通性监听 + 发件箱常驻弹出（Phase 4 编排收口项：Drainer 接网络监听常驻）。
///
/// 弱网下本地操作只落 `local_outbox_events`（`lib/collab/outbox.dart` §11.1），联网
/// 才能按序弹出。本模块把 [OutboxDrainer] 挂到「连通性变化」上——网络从断到通
/// 的瞬间自动重 drain，弱网恢复不丢操作；同时暴露 [trigger] 供应用层（回到前台 /
/// 定时心跳）主动触发。
///
/// 连通性抽象 [ConnectivityWatcher] 与具体探测实现解耦：测试注入脚本化假监听；
/// 真机用 [PollingConnectivityWatcher]（无第三方依赖，注入可达性探针，默认走
/// DNS 解析探测——如需 OS 级即时变化可后续换 `connectivity_plus` 实现同接口）。
library;

import 'dart:async';

import 'outbox.dart';

/// 串行链空首项：首轮触发之前的占位（零发送 / 零冲突 / 未因网络停止）。
const DrainReport _emptyReport =
    DrainReport(sent: 0, conflicts: [], stoppedByNetwork: false);


/// 连通性监听抽象：发射 `true`（在线）变化，[isOnline] 取当前态。
abstract class ConnectivityWatcher {
  /// 连通性状态流（仅在发生**变化**时发射；首值为当前态）。
  Stream<bool> get onConnectivityChanged;

  /// 当前是否在线。
  Future<bool> get isOnline;
}

/// 轮询式连通性监听（真实可用、零依赖）。
///
/// 周期性调用 [probe]（默认 DNS 解析探测），比较前后态，仅在跃迁时发射——断→通
/// 触发发件箱重 drain，通→断静默忽略（离线本就不该 drain）。
class PollingConnectivityWatcher implements ConnectivityWatcher {
  final Future<bool> Function() probe;
  final Duration interval;

  final StreamController<bool> _controller =
      StreamController<bool>.broadcast();
  Timer? _timer;
  bool? _last;
  bool _started = false;

  PollingConnectivityWatcher({
    required this.probe,
    this.interval = const Duration(seconds: 15),
  });

  @override
  Stream<bool> get onConnectivityChanged => _controller.stream;

  @override
  Future<bool> get isOnline => probe();

  /// 启动轮询（幂等）。先立即探测一次并作为首值发射，随后周期探测。
  void start() {
    if (_started) return;
    _started = true;
    _tick();
    _timer = Timer.periodic(interval, (_) => _tick());
  }

  Future<void> _tick() async {
    final now = await probe();
    if (_last == null || _last != now) {
      _last = now;
      _controller.add(now);
    }
  }

  /// 释放轮询定时器。
  void dispose() {
    _timer?.cancel();
    _timer = null;
    _controller.close();
  }
}

/// 发件箱常驻弹出调度器：把 [OutboxDrainer] 接到连通性监听上。
///
/// - 网络恢复（断→通）自动 drain；
/// - [trigger] 供应用层（回到前台 / 心跳）主动 drain；
/// - 持有 [OutboxDrainer] 引用，多源触发共享同一把锁，绝不并发弹出（§11.1 保序铁律）。
class CollabDrainScheduler {
  final OutboxDrainer drainer;
  final ConnectivityWatcher connectivity;

  StreamSubscription<bool>? _sub;
  final StreamController<DrainReport> _reports =
      StreamController<DrainReport>.broadcast();

  /// 弹出串行链：所有触发（网络恢复 / 手动 / 心跳）经同一 Future 链排队，绝不对同
  /// 一批待发条目并发弹出——否则 `pending()` 与 `remove()` 之间存在 TOCTOU 竞态，
  /// 并发 drain 会双重发送同一条（离线保序铁律被破坏，§11.1）。
  Future<DrainReport>? _chain;

  /// 每轮弹出战报（sent/conflicts/stoppedByNetwork），供 UI 渲染同步状态。
  Stream<DrainReport> get reports => _reports.stream;

  CollabDrainScheduler(this.drainer, this.connectivity);

  /// 接入常驻监听（幂等）。当前若已在线也立即 drain 一次。
  Future<void> start() async {
    if (_sub != null) return;
    _sub = connectivity.onConnectivityChanged.listen((online) {
      if (online) trigger();
    });
    if (await connectivity.isOnline) trigger();
  }

  /// 主动触发一轮弹出（应用层：回前台 / 心跳 / 用户手动）。与自动触发共享串行链，
  /// 多源并发触发亦安全（见 [_chain]）。
  Future<DrainReport> trigger() {
    final prev = _chain;
    final next = Completer<DrainReport>();
    _chain = next.future;
    (prev ?? Future<DrainReport>.value(_emptyReport)).then((_) async {
      try {
        final report = await drainer.drain();
        // 在途弹出晚于 [dispose] 完成时管道已关闭：丢事件而非往关闭的 broadcast
        // 投递（后者会炸成未处理的异步错误，把无辜测试的退出码带走）。
        if (!_reports.isClosed) _reports.add(report);
        next.complete(report);
      } catch (e, st) {
        next.completeError(e, st);
      }
    });
    return next.future;
  }

  /// 断开监听（应用生命周期销毁时）。
  Future<void> dispose() async {
    await _sub?.cancel();
    _sub = null;
    // 等在途那一轮弹出落地后再关管道：否则 dispose 与 drain 竞赛，
    // _reports 在 add 之前被关（见 [trigger] 里的守卫与之互为双保险）。
    await _chain?.catchError((_) => _emptyReport);
    _chain = null;
    await _reports.close();
  }
}
