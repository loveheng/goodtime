/// `GET /v1/schedule/sync` HTTP 契约（对齐 Go relay `internal/httpapi/sync.go`，
/// 冻结 tag v0.1.6-contract）。
///
/// 增量补拉是换机冷启动 / Wake Push 唤醒 / WSS 断线恢复三条链路的公共取数口：
/// 服务端按 `(bundle_id, since_seq)` 流式吐事件（单页 ≤500，has_more 分页），
/// 事件 `cipher` 是 base64 的自描述 SealedPayload JSON 字节（与上行同构）。
/// 契约注记：sync 回包**不含 key_ver**——解密方按本地 KeyRing 从 SealedPayload
/// 自描述字段取 key_ver 选钥（§5.3.1 密钥环回放），服务端零参与。
library;

import 'dart:convert';

import 'package:dio/dio.dart';

import 'auth_api.dart';

/// 单条增量事件（服务端 `team_schedule_events` 行投影）。
class SyncEvent {
  final int seqId;
  final String actionType; // ActionType 枚举名（明文骨架，服务端盲仲裁所需）
  final String senderId;
  final List<int> cipher; // base64 解码后原始字节（SealedPayload JSON utf8）
  final String topologyRef;
  final int serverAtSec; // 服务端落盘时间（§5.2 法定时钟，秒）

  const SyncEvent({
    required this.seqId,
    required this.actionType,
    required this.senderId,
    required this.cipher,
    required this.topologyRef,
    required this.serverAtSec,
  });
}

/// 一页拉取结果。
class SyncPage {
  final String bundleId;
  final List<SyncEvent> events;
  final int latestSeq; // 服务端该包当前最大 seq
  final bool hasMore;

  const SyncPage({
    required this.bundleId,
    required this.events,
    required this.latestSeq,
    required this.hasMore,
  });
}

/// 拉一页增量。非 200 抛 [RelayAuthException]（由调用方归一化 Offline/重试）。
Future<SyncPage> fetchSyncPage(
  Dio dio,
  String baseUrl,
  String accessToken,
  String bundleId,
  int sinceSeq, {
  int? limit,
}) async {
  final resp = await dio.get(
    '$baseUrl/v1/schedule/sync',
    queryParameters: {
      'bundle_id': bundleId,
      'since_seq': '$sinceSeq',
      if (limit != null) 'limit': '$limit',
    },
    options: Options(
      headers: {'Authorization': 'Bearer $accessToken'},
      // 状态码自分类不抛：非 200 统一转 RelayAuthException（契约口径）
      validateStatus: (_) => true,
    ),
  );
  if (resp.statusCode != 200) {
    throw RelayAuthException(resp.statusCode ?? 0, resp.data);
  }
  final j = resp.data as Map<String, dynamic>;
  final rawEvents = (j['events'] as List?) ?? const [];
  return SyncPage(
    bundleId: j['bundle_id'] as String? ?? bundleId,
    events: [
      for (final e in rawEvents.cast<Map<String, dynamic>>())
        SyncEvent(
          seqId: (e['seq_id'] as num).toInt(),
          actionType: e['action_type'] as String,
          senderId: e['sender_id'] as String? ?? '',
          cipher: base64Decode(e['cipher'] as String? ?? ''),
          topologyRef: e['topology_ref'] as String? ?? '',
          serverAtSec: (e['created_at'] as num?)?.toInt() ?? 0,
        ),
    ],
    latestSeq: (j['latest_seq'] as num?)?.toInt() ?? sinceSeq,
    hasMore: j['has_more'] as bool? ?? false,
  );
}
