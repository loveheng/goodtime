/// OutboxTransport 真实实现（collab-implementation §3.4 装配 + 发起补齐）。
///
/// 对齐 Go relay `internal/httpapi/action.go` 冻结契约（tag v0.1.6-contract）：
/// - `POST /v1/schedule/action`，Bearer access token + `X-Client-Msg-Id` 幂等头；
///   幂等键与 `OutboxEntry.id` 同源——弱网重发同 id，服务端 1.1 幂等中间件
///   原样回放缓存响应，重试天然安全；
/// - body `{bundle_id, action, cipher}`：payload 明文经 `sealing.sealPayload`
///   加密（512B 填充 + AES-256-GCM + key_ver 标注，§2.3/§9.2），按 Phase 2.7
///   双机对测确立的既有约定装信封——**`cipher = base64(SealedPayload JSON utf8)`**，
///   服务端仅盲存字节零明文；
/// - **「内容加密、拓扑明文」（§4.2 半盲骨架投影）**：Go 侧 `actionReq` 早已支持
///   而端侧此前未发的字段，由本传输层从 payload 抬出带上：`proposal_type` /
///   `participant_uids` / `expires_at` / `topology` / `quorum` /
///   `member_key_envelopes`，以及结构变更动作的 `expected_revision`、
///   `add_member_uids`。缺了它们服务端既认不出谁参与，也取不到门限与初态——
///   quorum 计数器无从推进，成员更解不开自己的 K_bundle 信封；
/// - **本地发起（PROPOSE 建包）**：bundle_id 由服务端发号，本地发出前只有临时 id，
///   故请求体 `bundle_id` 留空（正是 `action.go` 的建包分支判据），201 回执里的
///   `bundle_id`/`seq_id` 经 [SendProposed] 带回做认祖归宗。
///
/// 结局三分类映射：
///   - 2xx → `SendAccepted`（含幂等回放 200）；本地发起的 PROPOSE 走 201 发号
///     分支 → `SendProposed(bundle_id, seq_id)`；
///   - 4xx 确定性打回（400 配额族/非法请求、403 ACL、404 无包、409 STALE_
///     REVISION / BUNDLE_CONFIRMED_IMMUTABLE）→ `SendRejected(error_code)`，
///     终态清箱引导，绝不重试；
///   - 5xx / 401 / 传输层异常（DioException）→ `SendOffline` 非终态保留重试。
///     401 归 Offline 的口径：token 过期是凭证问题不是操作与本地状态的矛盾，
///     清箱会丢操作——留给上层刷新 token 后重 drain（attempts 自然累加）。
library;

import 'dart:convert';

import 'package:dio/dio.dart';

import '../sealing.dart';
import '../outbox.dart';

/// dio + 三注入（供票/供钥/base）构造的真实传输。
///
/// `bundleKey` 从本地 KeyRing 按 `(bundleId, keyVer)` 取 K_bundle——取不到钥
/// （离线收到 REKEY 信封未补拉等）抛出的异常由 `OutboxDrainer` 归一化为
/// Offline 保留重试，补拉完成后再 drain 即发。
class HttpOutboxTransport implements OutboxTransport {
  final Dio dio;
  final String baseUrl;
  final Future<String> Function() accessToken;
  final Future<List<int>> Function(String bundleId, int keyVer) bundleKey;

  const HttpOutboxTransport({
    required this.dio,
    required this.baseUrl,
    required this.accessToken,
    required this.bundleKey,
  });

  @override
  Future<SendOutcome> send(OutboxEntry entry) async {
    final key = await bundleKey(entry.bundleId, entry.keyVer);
    final sealed = await sealPayload(
      key,
      utf8.encode(jsonEncode(entry.payload)),
      entry.keyVer,
    );

    // 本地发起：bundle_id 空 + 抬出服务端仲裁所需的明文元数据（§4.2 拓扑明文）。
    final create = isBundleCreation(entry);
    final body = <String, Object?>{
      'bundle_id': create ? '' : entry.bundleId,
      'action': entry.actionType,
      'cipher': base64Encode(utf8.encode(sealed.encode())),
    };
    if (create) {
      final p = entry.payload;
      body['proposal_type'] = (p['proposal_type'] as String?) ?? 'CONSENSUS';
      body['participant_uids'] = List<String>.from(
          (p['participants'] as List?)?.cast<String>() ?? const <String>[]);
      final exp = p['expires_at_ms'] as int?;
      if (exp != null) body['expires_at'] = exp ~/ 1000; // 服务端收 Unix 秒
      final topo = p['topology'];
      if (topo != null) body['topology'] = topo;
      final quorum = p['quorum'] as int?;
      if (quorum != null) body['quorum'] = quorum;
      body['member_key_envelopes'] =
          (p['envelopes'] as List?)?.cast<Object?>() ?? const <Object?>[];
    } else {
      // 结构变更类动作同包携带新版骨架 / 因果 revision（§4.2 末：同事务原子更新）。
      final topo = entry.payload['topology'];
      if (topo != null) body['topology'] = topo;
      final rev = entry.payload['expected_revision'];
      if (rev is int) body['expected_revision'] = rev;
      final add = entry.payload['add_member_uids'];
      if (add is List) body['add_member_uids'] = List<String>.from(add.cast<String>());
    }

    final Response resp;
    try {
      resp = await dio.post(
        '$baseUrl/v1/schedule/action',
        data: jsonEncode(body),
        options: Options(
          contentType: 'application/json',
          headers: {
            'Authorization': 'Bearer ${await accessToken()}',
            'X-Client-Msg-Id': entry.id,
          },
          // 状态码自分类不抛：DioException 只留给传输层故障
          validateStatus: (_) => true,
        ),
      );
    } on DioException catch (e) {
      return SendOffline(e);
    }

    final status = resp.statusCode ?? 0;
    final data = resp.data;
    final map = data is Map ? data : const <Object?, Object?>{};
    if (create && status >= 200 && status < 300) {
      final id = map['bundle_id'] as String?;
      if (id == null || id.isEmpty) {
        // 建包成功却没发号=缺少认祖归宗所必需的信息，按 Offline 保留重试，
        // 绝不把临时 id 当正式 id 用（后续操作会全部 404）。
        return SendOffline('missing bundle_id in propose ack');
      }
      return SendProposed(
        bundleId: id,
        seqId: (map['seq_id'] as int?) ?? 1,
      );
    }
    if (status >= 200 && status < 300) return const SendAccepted();
    if (status >= 500 || status == 401 || status == 0) {
      return SendOffline('HTTP $status');
    }
    // 4xx：确定性打回——error_code 承载精确阻断原因（§10.4 全局错误码字典）
    final code = map['error_code'] is String
        ? map['error_code'] as String
        : 'HTTP_$status';
    return SendRejected(code);
  }
}

/// 是否属于「本地发起 bundle」= 服务端发号的 PROPOSE（`action.go` 建包分支）。
///
/// 判据是 payload 自证的：携带 `participants` 且 actionType 为 PROPOSE。用自证
/// 而非另立布尔入参，是为了让发件箱行（可序列化、可跨进程）自带语义——杀进程
/// 重启后重 drain 仍能走同一条建包路径。
bool isBundleCreation(OutboxEntry entry) =>
    entry.actionType == 'PROPOSE' && entry.payload['participants'] is List;
