/// 协同包本地仓储（Phase 4 编排收口项：WakePuller 游标接 collab 表）。
///
/// 职责仅限「协同包水位与事件落库」——是 [WakePuller.sinceSeqOf] 的真实数据源
/// （增量补拉水位 = `collab_bundles.max_seq_id`），并持久化经管道去重排序后的
/// 已应用事件（[recordEvent]），使进程被杀后 Wake Pull 能从本地水位精准续拉，
/// 不重复拖全量。
///
/// 设计口径（collab-implementation §3.1 / schedule-app.md §11.3）：
/// - 只落**原始事件**（密文 + 明文骨架），绝不在此做解密/FSM 回放——派生状态由
///   `lib/collab/fsm.dart` 按需从 `collab_events` 重放（§11.1 分层单向数据流）；
/// - `max_seq_id` 单调递增水位，[recordEvent] 以 GREATEST 推进，对齐 relay
///   `sync.go` 的「以本页最大值推进」语义；
/// - `(bundle_id, seq_id)` 主键 UNIQUE 兜底幂等——重复投递（WSS 已收 + `/sync`
///   补拉重叠）原样忽略，绝不炸库。
library;

import 'dart:convert';

import 'package:sqflite/sqflite.dart';

import 'db.dart';

/// 协同仓储族共用的底层句柄（sqflite 后端单例）。UI 经此装配协同仓储
/// （BundleKeySource/OutboxRepository/CollabBundleRepository），不直连
/// [Db.instance]——arch-guard R5：数据访问一律走 data 层。
Future<Database> collabDatabase() => Db.instance();

/// 已落库的单条协同事件投影（供 FSM 重放 / Wake 续拉校验）。
class StoredCollabEvent {
  final int seqId;
  final String actionType;
  final String senderId;
  final List<int> cipher; // base64 解码后原始字节（SealedPayload JSON utf8）
  final int keyVer;
  final String clientMsgId;
  final String? topologyRef;
  final int serverAtMs;

  const StoredCollabEvent({
    required this.seqId,
    required this.actionType,
    required this.senderId,
    required this.cipher,
    required this.keyVer,
    required this.clientMsgId,
    this.topologyRef,
    this.serverAtMs = 0,
  });

  factory StoredCollabEvent.fromMap(Map<String, Object?> m) => StoredCollabEvent(
        seqId: (m['seq_id'] as int?) ?? 0,
        actionType: m['action_type'] as String,
        senderId: m['sender_id'] as String,
        cipher: base64Decode(m['cipher'] as String? ?? ''),
        keyVer: (m['key_ver'] as int?) ?? 1,
        clientMsgId: m['client_msg_id'] as String,
        topologyRef: m['topology_ref'] as String?,
        serverAtMs: (m['server_at_ms'] as int?) ?? 0,
      );
}

/// 协同包头表投影。
class CollabBundleHeader {
  final String id;
  final String proposalType;
  final String status;
  final int maxSeqId;
  final int keyVersion;
  final String initiatorId;
  final List<String> participants;
  final int expiresAtMs;

  const CollabBundleHeader({
    required this.id,
    required this.proposalType,
    required this.status,
    required this.maxSeqId,
    required this.keyVersion,
    required this.initiatorId,
    required this.participants,
    required this.expiresAtMs,
  });

  factory CollabBundleHeader.fromMap(Map<String, Object?> m) => CollabBundleHeader(
        id: m['id'] as String,
        proposalType: m['proposal_type'] as String,
        status: m['status'] as String,
        maxSeqId: (m['max_seq_id'] as int?) ?? 0,
        keyVersion: (m['key_ver'] as int?) ?? 1,
        initiatorId: m['initiator_id'] as String,
        participants: (jsonDecode(m['participants'] as String? ?? '[]')
                as List)
            .cast<String>(),
        expiresAtMs: (m['expires_at'] as int?) ?? 0,
      );
}

/// 协同步骤投影（`collab_steps` 行；FSM 物化的步骤态，UI 三态预占渲染源）。
///
/// **注意（施工钉子）**：`collab_steps` 当前只存步骤态与投票，不存具体的**时间槽**
/// （date/start_min）——时间槽仍在加密载荷 / 拓扑骨架里，未物化到本地表。因此
/// 成员混合日历的「时间轴叠块」需等 FSM 回放把槽位落表后才能做；本页先以**列表/
/// 卡片混合承载**呈现（§11.2 B 页三态预占 + 一键响应），时间叠块为后续切片。
class CollabStep {
  final String bundleId;
  final String id;
  final List<String> requiredUids; // 该步必答成员
  final int threshold; // M-of-N 门限
  final bool isOptional;
  final String status; // PENDING/READY/TIME_CLASH/ROLE_CLASH/PRUNED
  final Map<String, String> votes; // uid -> PREFER|OK|NO（Consensus 三值）
  final int sort; // DAG 拓扑序

  /// 步骤时间槽（物化回填，v7）：架子账里 skeleton 只带结构，真实时段藏在加密载荷
  /// 里——只能在解密回放那一刻顺手抓取写入；未解到则为 null，读侧据此降级渲染。
  final int? startsAtMs;
  final int? endsAtMs;

  const CollabStep({
    required this.bundleId,
    required this.id,
    required this.requiredUids,
    required this.threshold,
    required this.isOptional,
    required this.status,
    required this.votes,
    required this.sort,
    this.startsAtMs,
    this.endsAtMs,
  });

  factory CollabStep.fromMap(Map<String, Object?> m) => CollabStep(
        bundleId: m['bundle_id'] as String,
        id: m['id'] as String,
        requiredUids: (jsonDecode(m['required_uids'] as String? ?? '[]') as List)
            .cast<String>(),
        threshold: (m['threshold'] as int?) ?? 1,
        isOptional: ((m['is_optional'] as int?) ?? 0) == 1,
        status: m['status'] as String,
        votes: (jsonDecode(m['votes'] as String? ?? '{}') as Map)
            .cast<String, String>(),
        sort: (m['sort'] as int?) ?? 0,
        startsAtMs: m['starts_at_ms'] as int?,
        endsAtMs: m['ends_at_ms'] as int?,
      );
}

/// 协同包本地仓储（sqflite 句柄注入；测试用 ffi 库 + `Db.createAll`）。
class CollabBundleRepository {
  final Database db;
  final int Function() nowMs;

  CollabBundleRepository(this.db, {int Function()? nowMs})
      : nowMs = nowMs ?? (() => DateTime.now().millisecondsSinceEpoch);

  /// 增量补拉水位（[WakePuller.sinceSeqOf] 数据源）：优先取 `collab_bundles.
  /// max_seq_id`；包头行尚不存在时回退到 `collab_events` 已落库最大 seq；均无则 0。
  Future<int> maxSeqId(String bundleId) async {
    final rows = await db.query(
      'collab_bundles',
      columns: ['max_seq_id'],
      where: 'id = ?',
      whereArgs: [bundleId],
      limit: 1,
    );
    if (rows.isNotEmpty) return (rows.first['max_seq_id'] as int?) ?? 0;
    final ev = await db.rawQuery(
      'SELECT COALESCE(MAX(seq_id), 0) AS m FROM collab_events WHERE bundle_id = ?',
      [bundleId],
    );
    return (ev.first['m'] as int?) ?? 0;
  }

  /// 确保包头行存在（INSERT OR IGNORE，沿用 3.1 默认值）。PROPOSE 事件落地前或
  /// Wake Pull 先于 PROPOSE 到达时调用，保证 [recordEvent] 的 max_seq_id 推进有宿主。
  Future<void> ensureBundle({
    required String bundleId,
    required String proposalType,
    required String initiatorId,
    required List<String> participants,
    required int expiresAtMs,
    int keyVersion = 1,
  }) async {
    final now = nowMs();
    await db.insert(
      'collab_bundles',
      {
        'id': bundleId,
        'proposal_type': proposalType,
        'status': 'PROPOSED',
        'lifecycle': 'active',
        'base_revision': 1,
        'max_seq_id': 0,
        'last_acked_seq': 0,
        'key_version': keyVersion,
        'initiator_id': initiatorId,
        'participants': jsonEncode(participants),
        'expires_at': expiresAtMs,
        'created_at': now,
        'updated_at': now,
      },
      conflictAlgorithm: ConflictAlgorithm.ignore,
    );
  }

  /// 落一条已应用事件并推进水位（同一事务内幂等 + 水位 GREATEST）。
  ///
  /// 调用方（[CollabEngine] 订阅 `pipeline.applied`）保证事件已去重排序，这里
  /// 只做保真落库，不做解密/回放。重复 seq 因 UNIQUE(bundle_id, seq_id) 被忽略。
  Future<void> recordEvent({
    required String bundleId,
    required int seqId,
    required String actionType,
    required String senderId,
    required List<int> cipher,
    required int keyVer,
    required String clientMsgId,
    String? topologyRef,
    int serverAtMs = 0,
  }) async {
    await db.transaction((txn) async {
      await txn.insert(
        'collab_events',
        {
          'bundle_id': bundleId,
          'seq_id': seqId,
          'action_type': actionType,
          'sender_id': senderId,
          'cipher': base64Encode(cipher),
          'key_ver': keyVer,
          'client_msg_id': clientMsgId,
          'topology_ref': topologyRef,
          'server_at_ms': serverAtMs,
          'created_at': nowMs(),
        },
        conflictAlgorithm: ConflictAlgorithm.ignore,
      );
      // 确保包头行在（Wake Pull 可能先于 PROPOSE 落地）
      final head = await txn.query(
        'collab_bundles',
        columns: ['id'],
        where: 'id = ?',
        whereArgs: [bundleId],
        limit: 1,
      );
      if (head.isEmpty) {
        await txn.insert(
          'collab_bundles',
          {
            'id': bundleId,
            'proposal_type': 'DIRECTIVE',
            'status': 'PROPOSED',
            'lifecycle': 'active',
            'base_revision': 1,
            'max_seq_id': 0,
            'last_acked_seq': 0,
            'key_version': keyVer,
            'initiator_id': senderId,
            'participants': jsonEncode(<String>[senderId]),
            'expires_at': 0,
            'created_at': nowMs(),
            'updated_at': nowMs(),
          },
          conflictAlgorithm: ConflictAlgorithm.ignore,
        );
      }
      // 水位 GREATEST 单调推进（对齐 sync.go 本页最大值语义）
      await txn.rawUpdate(
        'UPDATE collab_bundles SET max_seq_id = MAX(max_seq_id, ?), '
        'updated_at = ? WHERE id = ?',
        [seqId, nowMs(), bundleId],
      );
    });
  }

  /// 回放用：按 seq 升序取某包全部已落库事件。
  Future<List<StoredCollabEvent>> loadEvents(String bundleId) async {
    final rows = await db.query(
      'collab_events',
      where: 'bundle_id = ?',
      whereArgs: [bundleId],
      orderBy: 'seq_id ASC',
    );
    return [for (final r in rows) StoredCollabEvent.fromMap(r)];
  }

  /// 包头投影（无则 null）。
  Future<CollabBundleHeader?> bundleHeader(String bundleId) async {
    final rows = await db.query(
      'collab_bundles',
      where: 'id = ?',
      whereArgs: [bundleId],
      limit: 1,
    );
    if (rows.isEmpty) return null;
    return CollabBundleHeader.fromMap(rows.first);
  }

  /// 终态快照（供 UI/调度读；派生状态由 FSM 从 [loadEvents] 重放，这里只存权威水位）。
  Future<void> updateStatus(String bundleId, String status) async {
    await db.update(
      'collab_bundles',
      {'status': status, 'updated_at': nowMs()},
      where: 'id = ?',
      whereArgs: [bundleId],
    );
  }

  /// 确保步骤行存在（INSERT OR IGNORE，沿用步骤默认值）。FSM 回放落步骤态前或
  /// Wake Pull 先于步骤到达时调用。
  Future<void> ensureStep({
    required String bundleId,
    required String stepId,
    required List<String> requiredUids,
    int threshold = 1,
    bool isOptional = false,
    String status = 'PENDING',
    Map<String, String> votes = const {},
    int sort = 0,
  }) async {
    final now = nowMs();
    await db.insert(
      'collab_steps',
      {
        'bundle_id': bundleId,
        'id': stepId,
        'required_uids': jsonEncode(requiredUids),
        'threshold': threshold,
        'is_optional': isOptional ? 1 : 0,
        'status': status,
        'votes': jsonEncode(votes),
        'sort': sort,
        'created_at': now,
        'updated_at': now,
      },
      conflictAlgorithm: ConflictAlgorithm.ignore,
    );
  }

  /// 物化回填（Phase 4.3）：把 FSM 派生态整批写入 `collab_steps`（replace 覆盖）。
  ///
  /// 与 [ensureStep] 的区别：后者是 INSERT OR IGNORE 占位（主键冲突即留旧值，
  /// 故它写入的默认 PENDING 一旦落成就再也更新不了——这正是「步骤恒为 PENDING」
  /// 的根因）；本方法用 replace 整体刷新派生列，重复调用幂等、不累积。
  ///
  /// [steps] 为空时不落任何行（避免回放尚无步骤时误抹既有派生行）。
  Future<int> upsertSteps(
    String bundleId,
    List<CollabStep> steps,
  ) async {
    if (steps.isEmpty) return 0;
    final now = nowMs();
    final batch = db.batch();
    for (var i = 0; i < steps.length; i++) {
      final s = steps[i];
      batch.insert(
        'collab_steps',
        {
          'bundle_id': bundleId,
          'id': s.id,
          'required_uids': jsonEncode(s.requiredUids),
          'threshold': s.threshold,
          'is_optional': s.isOptional ? 1 : 0,
          'status': s.status,
          'votes': jsonEncode(s.votes),
          'sort': s.sort,
          'starts_at_ms': s.startsAtMs,
          'ends_at_ms': s.endsAtMs,
          'created_at': now,
          'updated_at': now,
        },
        conflictAlgorithm: ConflictAlgorithm.replace,
      );
    }
    await batch.commit(noResult: true);
    return steps.length;
  }

  /// 协同包列表（默认 active 且按更新时间倒序）；[status] 可收窄（如 CONFIRMED）。
  Future<List<CollabBundleHeader>> listBundles({
    String? status,
    String lifecycle = 'active',
  }) async {
    final where = <String>[];
    final args = <Object>[];
    if (status != null) {
      where.add('status = ?');
      args.add(status);
    }
    // lifecycle 非可空、默认 'active'，必带此收窄（归档清事件流时用 'archived'）
    where.add('lifecycle = ?');
    args.add(lifecycle);
    final rows = await db.query(
      'collab_bundles',
      where: where.join(' AND '),
      whereArgs: args,
      orderBy: 'updated_at DESC',
    );
    return [for (final r in rows) CollabBundleHeader.fromMap(r)];
  }

  /// 某包的步骤（按拓扑序 sort → id 升序）。
  Future<List<CollabStep>> listSteps(String bundleId) async {
    final rows = await db.query(
      'collab_steps',
      where: 'bundle_id = ?',
      whereArgs: [bundleId],
      orderBy: 'sort ASC, id ASC',
    );
    return [for (final r in rows) CollabStep.fromMap(r)];
  }
}
