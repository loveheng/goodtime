/// Wake Push 唤醒拉取（协同设计 §2.2「端侧单一消费网关」/ §10.3-2，collab-
/// implementation §3.4 装配）。
///
/// Wake Push 载荷零明文（§2.2 v0.1.6 定盘）：仅 `{push_type, bundle_id,
/// latest_seq_id}` 三个无特征路由字段。端侧被系统静默推送唤醒后走本地管道
/// 精准拉取：`GET /v1/schedule/sync` 分页增量 → 喂入 [SingleConsumerPipeline]
/// 的 HTTP 入口——与 WSS 实时推送汇入同一条串行 FIFO，由管道统一去重排序，
/// 弱网恢复瞬间两路同涌也绝不乱序/重复（§11.1 单消费铁律）。
///
/// 本类只管「拉 + 喂」，不做解密与落库——那是管道下游（隔离加解密引擎 → FSM
/// 回放 → 本地库）的职责；测试注入假 pull，生产注入 `fetchSyncPage` 偏特化。
library;

import 'proto/relay_protocol.pb.dart';
import 'relay/sync_api.dart';
import 'pipeline.dart';

/// Wake Push 唤醒载荷（零明文路由字段，§10.3-2）。
class WakePayload {
  final String pushType;
  final String bundleId;
  final int latestSeqId;

  const WakePayload({
    required this.pushType,
    required this.bundleId,
    required this.latestSeqId,
  });

  factory WakePayload.fromMap(Map<String, Object?> m) {
    final bundleId = m['bundle_id'] as String? ?? '';
    if (bundleId.isEmpty) {
      throw const FormatException('Wake Push 载荷缺 bundle_id（§10.3-2）');
    }
    return WakePayload(
      pushType: m['push_type'] as String? ?? '',
      bundleId: bundleId,
      latestSeqId: (m['latest_seq_id'] as num?)?.toInt() ?? 0,
    );
  }
}

/// 唤醒拉取器：Wake Push → `/sync` 分页增量 → 单消费管道 HTTP 入口。
class WakePuller {
  final SingleConsumerPipeline pipeline;

  /// 拉页函数（生产注入 [fetchSyncPage] 偏特化；测试脚本化）。
  final Future<SyncPage> Function(String bundleId, int sinceSeq) pull;

  /// 本地 since 游标来源（已落库的 last_applied_seq；Phase 4 编排接 collab
  /// 表）。缺省用内部游标——进程内连续唤醒天然增量。
  final Future<int> Function(String bundleId)? sinceSeqOf;

  final Map<String, int> _cursor = {};

  WakePuller({
    required this.pipeline,
    required this.pull,
    this.sinceSeqOf,
  });

  /// 静默唤醒入口：解析零明文载荷 → 精准补拉。返回该包推进到的 seq。
  Future<int> onWake(WakePayload wake) =>
      pullBundle(wake.bundleId, latestSeqHint: wake.latestSeqId);

  /// 补拉一个包到 [latestSeqHint]（或服务端无更多）为止。
  ///
  /// 事件全部喂管道 `pushHttp`——管道按 `(bundleId, seqId)` 去重 + 升序 flush，
  /// 这里**不做**任何本地状态假设，重复拉取（WSS 已收 / 游标回退）天然安全。
  Future<int> pullBundle(String bundleId, {int? latestSeqHint}) async {
    var since = sinceSeqOf != null
        ? await sinceSeqOf!(bundleId)
        : (_cursor[bundleId] ?? 0);
    while (latestSeqHint == null || since < latestSeqHint) {
      final page = await pull(bundleId, since);
      for (final e in page.events) {
        pipeline.pushHttp(CollabInboundEvent(
          bundleId: bundleId,
          seqId: e.seqId,
          action: _parseAction(e.actionType),
          senderHint: e.senderId.isEmpty ? null : e.senderId,
          cipher: e.cipher.isEmpty ? null : e.cipher,
          keyVer: null, // sync 回包无 key_ver，解密方按 SealedPayload 自描述取版本
          topologyRef: e.topologyRef.isEmpty ? null : e.topologyRef,
          serverAtMs: e.serverAtSec * 1000, // 法定时钟（§5.2）
          source: 'http',
        ));
      }
      // 游标以服务端 latest_seq 为准推进（空页也推进，补齐跳号后的空洞窗口）
      if (page.latestSeq > since) since = page.latestSeq;
      if (page.events.isEmpty || !page.hasMore) break; // 无进展/到顶，防死循环
    }
    _cursor[bundleId] = since;
    return since;
  }

  static final Map<String, ActionType> _actionByName = {
    for (final v in ActionType.values) v.name: v,
  };

  ActionType _parseAction(String name) {
    final a = _actionByName[name];
    if (a == null) {
      throw FormatException('未知 action_type: $name（契约外枚举，拒绝入管道）');
    }
    return a;
  }
}
