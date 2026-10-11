import 'dart:async';
import 'dart:collection';

import 'package:async/async.dart';

import 'proto/relay_protocol.pb.dart';
import 'relay/wss_client.dart';

/// 归一化入管道的统一协同事件载荷。
///
/// 不论来源（WSS 实时推送 / HTTP `/sync` 补拉），统一成此结构，使两条输入路能汇入
/// 同一条串行 FIFO 队列，交由单一消费者按 seq_id 去重、排序后驱动本地落库。
class CollabInboundEvent {
  final String bundleId;
  final int seqId;
  final ActionType action;
  final String? senderHint;
  final List<int>? cipher;
  final int? keyVer;
  final String? clientMsgId;
  final String? topologyRef;
  final int serverAtMs; // 法定时钟（§5.2）：服务端落盘时间，毫秒；WSS 不可得时为 0
  final String source; // 'wss' | 'http' —— 仅可观测，不参与去重/排序

  const CollabInboundEvent({
    required this.bundleId,
    required this.seqId,
    required this.action,
    this.senderHint,
    this.cipher,
    this.keyVer,
    this.clientMsgId,
    this.topologyRef,
    this.serverAtMs = 0,
    this.source = 'wss',
  });

  /// 从 WSS 的 `ServerFrame`（含 Envelope）翻译。
  factory CollabInboundEvent.fromServerFrame(ServerFrame frame,
      {String source = 'wss', int serverAtMs = 0}) {
    final env = frame.schedule;
    return CollabInboundEvent(
      bundleId: env.bundleId,
      seqId: env.seqId.toInt(),
      action: env.action,
      senderHint: env.senderHint.isEmpty ? null : env.senderHint,
      cipher: env.cipher.isEmpty ? null : List<int>.from(env.cipher),
      keyVer: env.hasKeyVer() ? env.keyVer : null,
      clientMsgId: env.clientMsgId.isEmpty ? null : env.clientMsgId,
      topologyRef: env.topologyRef.isEmpty ? null : env.topologyRef,
      serverAtMs: serverAtMs,
      source: source,
    );
  }

  @override
  String toString() =>
      'CollabInboundEvent(bundle=$bundleId seq=$seqId action=$action src=$source)';
}

/// 单消费管道（Single Consumer Pipeline）：WSS 与 HTTP `/sync` 两路统一串行入库。
///
/// 设计（协同设计 §11.1 / collab-implementation §2.6）：
/// - 两路事件都汇入**同一条** `StreamQueue`（底层单订阅 StreamController，FIFO 缓冲），
///   由**单一消费者** `await for` 逐条取出——同一时刻只处理一条，**从结构上消灭并发重放竞态**。
/// - 取出后按 `(bundleId, seqId)` **去重**；并按 seq 升序**排序**驱动（同包内缺号则暂存于
///   有序缓冲，待补拉补齐后连续 flush），保证弱网恢复瞬时两路同时涌入也不乱序/不重复。
class SingleConsumerPipeline {
  final StreamController<CollabInboundEvent> _in =
      StreamController<CollabInboundEvent>();
  final StreamController<CollabInboundEvent> _applied =
      StreamController<CollabInboundEvent>.broadcast();
  late final StreamQueue<CollabInboundEvent> _queue;

  final Set<String> _seen = {}; // 'bundle:seq' 已应用去重
  final Map<String, int> _lastApplied = {}; // bundleId → 已应用最大 seq
  final Map<String, SplayTreeMap<int, CollabInboundEvent>> _pending = {};

  int _peakConcurrent = 0;
  int _active = 0;

  SingleConsumerPipeline() {
    _queue = StreamQueue(_in.stream);
    _run();
  }

  /// 已正确去重、排序并「应用」（落库）的事件流——上层 UI/存储订阅此流。
  Stream<CollabInboundEvent> get applied => _applied.stream;

  /// 并发峰值（诊断用；单一消费者应恒为 1）。
  int get peakConcurrent => _peakConcurrent;

  /// WSS 实时推送入口。
  void pushWss(CollabInboundEvent e) => _in.add(e);

  /// HTTP `/sync` 补拉入口。
  void pushHttp(CollabInboundEvent e) => _in.add(e);

  /// 便捷：直接灌一批（来自 `/sync` 的分页结果）。
  void pushSyncBatch(List<CollabInboundEvent> batch) => batch.forEach(_in.add);

  /// 便捷：把 WssClient 的入站帧串入 WSS 入口（2.7 编排用）。
  StreamSubscription<ServerFrame> attachWss(RelayWssClient client) =>
      client.frames.listen(
          (f) => _in.add(CollabInboundEvent.fromServerFrame(f, source: 'wss')));

  Future<void> _run() async {
    while (true) {
      CollabInboundEvent e;
      try {
        e = await _queue.next;
      } on StateError {
        break; // 源已关闭
      }
      _active++;
      _peakConcurrent = _peakConcurrent < _active ? _active : _peakConcurrent;
      assert(_active == 1, '单消费管道出现并发消费（_active=$_active）');
      _dispatch(e);
      _active--;
    }
  }

  void _dispatch(CollabInboundEvent e) {
    final key = '${e.bundleId}:${e.seqId}';
    if (_seen.contains(key)) return; // 去重（含 replay）
    final last = _lastApplied[e.bundleId] ?? 0;
    if (e.seqId <= last) return; // 旧/重复，丢弃
    _pending
        .putIfAbsent(e.bundleId, () => SplayTreeMap())
        .putIfAbsent(e.seqId, () => e);
    // 从期望的下一个 seq 起，连续 flush 已就位的事件（缺号则停在缺口等补拉）
    final pend = _pending[e.bundleId]!;
    var next = (_lastApplied[e.bundleId] ?? 0) + 1;
    while (pend.isNotEmpty && pend.firstKey() == next) {
      final seq = pend.firstKey()!;
      final ev = pend.remove(seq)!;
      _seen.add('${ev.bundleId}:$seq');
      _lastApplied[ev.bundleId] = seq;
      _applied.add(ev);
      next++;
    }
  }

  Future<void> close() async {
    await _in.close();
    await _applied.close();
  }
}
