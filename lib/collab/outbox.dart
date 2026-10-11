/// 本地 Outbox（协同设计 §11.1「离线发件箱」，collab-implementation §3.3）。
///
/// 弱网不丢操作的三段式：
/// 1. **离线操作立即落库**——`local_outbox_events` 写入 PENDING_SEND，先于任何
///    UI 反馈；操作不存内存、不直写正式状态（被 409 打回即污染本地），杀进程
///    零丢失；
/// 2. **联网按序弹出**——按 `(created_at, id)` 全序逐条发往服务端。保序是硬约束：
///    服务端按到达序行锁取号（§5.1），跳过前序乱序发出必被 409 STALE_REVISION
///    打回，故一旦遇网络失败立即停止本轮（后续条目因果依赖前序）；
/// 3. **三种出口**：
///    - **Accepted**：确认即物理删除（「收到确认后删 Outbox 项」，正式流水由
///      响应/同步通道入本地 WAL）；
///    - **Rejected**（服务端确定性打回：409 STALE_REVISION / BUNDLE_CONFIRMED_
///      IMMUTABLE / 403 ACL 等）：终态——清该条并把冲突上抛 [DrainReport.conflicts]，
///      UI 据此弹「内容已更新，请重新确认」引导；**不阻塞后续**（被打回的是该
///      操作与本地状态的矛盾，独立操作仍有效）；
///    - **Offline**（网络不可达/超时）：**非终态**——条目保留、attempts+1、保序
///      停止，弱网恢复后重 drain。
library;

import 'dart:convert';
import 'dart:math';

import 'package:sqflite/sqflite.dart';

/// 发件箱条目（`local_outbox_events` 行投影）。
class OutboxEntry {
  /// 幂等键：与服务端 `client_msg_id` 同源——弱网重发同 id，服务端幂等中间件
  /// 原样回放缓存响应（Phase 1.1），重试天然安全。
  final String id;
  final String bundleId;
  final String actionType; // ActionType 枚举名（明文，服务端盲仲裁所需）
  final Map<String, Object?> payload; // 待发明文（发送时经 sealing 加密上链）
  final int keyVer;
  final int attempts; // 离线重试计数（≥3 可打「需人工关注」标，§8 口径复用）
  final int createdAt; // 毫秒；弹出序 = (created_at, id) 全序

  const OutboxEntry({
    required this.id,
    required this.bundleId,
    required this.actionType,
    required this.payload,
    required this.keyVer,
    required this.attempts,
    required this.createdAt,
  });

  factory OutboxEntry.fromMap(Map<String, Object?> m) => OutboxEntry(
        id: m['id'] as String,
        bundleId: m['bundle_id'] as String,
        actionType: m['action_type'] as String,
        payload: jsonDecode(m['payload'] as String) as Map<String, Object?>,
        keyVer: (m['key_ver'] as int?) ?? 1,
        attempts: (m['attempts'] as int?) ?? 0,
        createdAt: m['created_at'] as int,
      );

  Map<String, Object?> toMap() => {
        'id': id,
        'bundle_id': bundleId,
        'action_type': actionType,
        'payload': jsonEncode(payload),
        'key_ver': keyVer,
        'status': 'PENDING_SEND',
        'attempts': attempts,
        'created_at': createdAt,
        'sent_at': null,
      };
}

// ---- 弹出结局 -----------------------------------------------------------------

/// 单条弹出结局：服务端确认 / 确定性打回 / 没摸到网。
sealed class SendOutcome {
  const SendOutcome();
}

/// 服务端已确认（含幂等回放：同 client_msg_id 重发原样 200）。
class SendAccepted extends SendOutcome {
  const SendAccepted();
}

/// 服务端确定性打回——终态，清箱 + 引导，绝不重试。
class SendRejected extends SendOutcome {
  final String errorCode;
  const SendRejected(this.errorCode);
}

/// 网络不可达/超时——非终态，保留重试。
class SendOffline extends SendOutcome {
  final Object? cause;
  const SendOffline([this.cause]);
}

/// **服务端已建包**（PROPOSE 受理：201 + 服务端发号）。
///
/// 与 [SendAccepted] 的区别：纯追加动作已经有 bundle_id，无需回执；而本地发起的
/// bundle 在服务端发号前只有**临时本地 id**——窗口期内（入队到弹出之间）后续随
/// 手入队的操作仍挂在临时 id 上，故必须把服务端返回的 `bundle_id`/`seq_id` 带回
/// 本地做「认祖归宗」（见 `propose.dart` 的 `PendingProposalReconciler`）。
class SendProposed extends SendOutcome {
  final String bundleId;
  final int seqId;
  const SendProposed({required this.bundleId, this.seqId = 1});
}

/// 传输口。真实实现接 `POST /v1/schedule/action`（Bearer + Envelope：payload
/// 经 `sealing.sealPayload` 加密、512B 填充、key_ver 标注——3.4 装配时接线）；
/// 弱网单测注入脚本化假传输。
abstract class OutboxTransport {
  Future<SendOutcome> send(OutboxEntry entry);
}

// ---- 弹出器 -------------------------------------------------------------------

/// 409 清箱引导数据源（UI：条目级「内容已更新，请重新确认」提示）。
class OutboxConflict {
  final String id;
  final String bundleId;
  final String actionType;
  final String errorCode;

  const OutboxConflict({
    required this.id,
    required this.bundleId,
    required this.actionType,
    required this.errorCode,
  });
}

/// 本轮弹出里「服务端已发号」的本地发起包回执。
class CreatedBundle {
  final String entryId; // 幂等键（OutboxEntry.id，与 client_msg_id 同源）
  final String localBundleId; // 发号前的临时本地 id
  final String bundleId; // 服务端发号
  final int seqId; // 首条 PROPOSE 事件的 seq（恒为 1）

  const CreatedBundle({
    required this.entryId,
    required this.localBundleId,
    required this.bundleId,
    required this.seqId,
  });
}

/// 一轮弹出战报：UI 据此渲染「N 条已同步 / M 条冲突待处置 / 仍有积压」。
class DrainReport {
  final int sent;
  final List<OutboxConflict> conflicts;
  final bool stoppedByNetwork; // true = 因离线保序停止，箱内仍有积压

  /// 本轮里被服务端正式发号的本地发起包（认祖归宗数据源）。
  final List<CreatedBundle> created;

  const DrainReport({
    required this.sent,
    required this.conflicts,
    required this.stoppedByNetwork,
    this.created = const [],
  });
}

class OutboxDrainer {
  final OutboxRepository repo;
  final OutboxTransport transport;

  /// 本地发起包的认祖归宗钩子：临时本地 id → 服务端 bundle_id。
  ///
  /// 钩子在条目出箱**之后**、带着完整 [OutboxEntry] 调用——此时尚有原始 payload
  /// 可查（例如成员清单与拓扑），出箱后再补就晚了。服务端重放 client_msg_id 会
  /// 再次回到这里，故认祖归宗必须幂等（见 `propose.dart`）。
  final Future<void> Function(OutboxEntry entry, CreatedBundle ack)? onBundleCreated;

  const OutboxDrainer(
    this.repo,
    this.transport, {
    this.onBundleCreated,
  });

  /// 按 (created_at, id) 全序逐条弹出。传输层裸异常（DioException/
  /// SocketException/超时…）一律归一化为 Offline——坏网络绝不炸弹出循环。
  ///
  /// **每发一条都重新 `pending()` 拉首条**，而不是开局缓存整批：本地发起包的认祖
  /// 归宗会 `UPDATE local_outbox_events SET bundle_id = 服务端号`，缓存下来的后续
  /// 条目仍带着临时本地 id，照发必 404（服务端只认自己发的号）。多一次查询换来
  /// 「改绑立刻生效」，全序由 `pending()` 的 ORDER BY 保证。
  Future<DrainReport> drain() async {
    final conflicts = <OutboxConflict>[];
    final created = <CreatedBundle>[];
    var sent = 0;
    while (true) {
      final pending = await repo.pending();
      if (pending.isEmpty) break;
      final entry = pending.first;
      final SendOutcome outcome;
      try {
        outcome = await transport.send(entry);
      } catch (e) {
        await repo.markAttempt(entry.id);
        return DrainReport(
            sent: sent, conflicts: conflicts, stoppedByNetwork: true);
      }
      switch (outcome) {
        case SendProposed(:final bundleId, :final seqId):
          sent++;
          final ack = CreatedBundle(
            entryId: entry.id,
            localBundleId: entry.bundleId,
            bundleId: bundleId,
            seqId: seqId,
          );
          created.add(ack);
          // 认祖归宗失败绝不炸弹出循环：幂等键由服务端兜底，下次重连可再补。
          if (onBundleCreated != null) {
            try {
              await onBundleCreated!(entry, ack);
            } catch (_) {}
          }
          await repo.remove(entry.id); // 确认即删（§11.1）
        case SendAccepted():
          await repo.remove(entry.id); // 确认即删（§11.1）
          sent++;
        case SendRejected(:final errorCode):
          await repo.remove(entry.id); // 409 清箱（§11.1）
          conflicts.add(OutboxConflict(
            id: entry.id,
            bundleId: entry.bundleId,
            actionType: entry.actionType,
            errorCode: errorCode,
          ));
        case SendOffline():
          await repo.markAttempt(entry.id); // 非终态保留
          return DrainReport(
              sent: sent, conflicts: conflicts, stoppedByNetwork: true);
      }
    }
    return DrainReport(
        sent: sent,
        conflicts: conflicts,
        stoppedByNetwork: false,
        created: created);
  }
}

// ---- 落库仓储 -----------------------------------------------------------------

/// `local_outbox_events` 仓储（sqflite 句柄注入；测试用 ffi 库 + Db.createAll）。
class OutboxRepository {
  final Database db;
  final int Function() nowMs; // 时钟注入（测试钉时间控全序）
  final String Function() newId; // 与 client_msg_id 同源的幂等键生成

  OutboxRepository(this.db, {int Function()? nowMs, String Function()? newId})
      : nowMs = nowMs ?? _defaultNow,
        newId = newId ?? _defaultNewId;

  static int _defaultNow() => DateTime.now().millisecondsSinceEpoch;

  static String _defaultNewId() {
    final r = Random.secure();
    final rand = List<int>.generate(8, (_) => r.nextInt(16))
        .map((n) => n.toRadixString(16))
        .join();
    return 'ob-${_defaultNow().toRadixString(36)}-$rand';
  }

  /// 入队即落盘（§11.1：离线操作立即写入，先于任何 UI 反馈）。
  Future<OutboxEntry> enqueue({
    required String bundleId,
    required String actionType,
    required Map<String, Object?> payload,
    int keyVer = 1,
  }) async {
    final entry = OutboxEntry(
      id: newId(),
      bundleId: bundleId,
      actionType: actionType,
      payload: payload,
      keyVer: keyVer,
      attempts: 0,
      createdAt: nowMs(),
    );
    await db.insert('local_outbox_events', entry.toMap());
    return entry;
  }

  /// 待发条目：(created_at, id) 升序全序——同毫秒兜底按 id 排，弹出序稳定。
  Future<List<OutboxEntry>> pending() async {
    final rows = await db.query(
      'local_outbox_events',
      where: "status = 'PENDING_SEND'",
      orderBy: 'created_at, id',
    );
    return [for (final r in rows) OutboxEntry.fromMap(r)];
  }

  /// 确认/清箱：物理删除，无墓碑——幂等由服务端 client_msg_id 兜底。
  Future<void> remove(String id) async {
    await db.delete('local_outbox_events', where: 'id = ?', whereArgs: [id]);
  }

  /// **认祖归宗**：本地发起包的临时 id → 服务端发号，把尚未弹出的随手操作
  /// （窗口期内入队的后续动作）整体改绑到真实 bundle_id。
  ///
  /// 为什么必须改绑：`/v1/schedule/action` 的 bundle_id 由服务端行锁定位，临时
  /// 本地 id 在服务端不存在——不改绑则这批操作弹出时恒 404。幂等：重复改绑零行。
  Future<int> rebindBundle(String fromBundleId, String toBundleId) async {
    if (fromBundleId == toBundleId) return 0;
    return db.update(
      'local_outbox_events',
      {'bundle_id': toBundleId},
      where: 'bundle_id = ?',
      whereArgs: [fromBundleId],
    );
  }

  /// 离线保留：attempts+1，条目原样待重试。
  Future<void> markAttempt(String id) async {
    await db.rawUpdate(
      'UPDATE local_outbox_events SET attempts = attempts + 1 WHERE id = ?',
      [id],
    );
  }
}
