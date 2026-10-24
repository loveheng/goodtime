/// 本地发起 bundle（Phase 4.3 A 页背后的编排层；协同设计 §1.7/§3.6/§4.2/§5.3）。
///
/// 「发起」做了三件此前全仓没人做的事：
/// 1. **造 K_bundle 并落户**：CSPRNG 32B → [BundleKeySource.persist] 用 MSK 封存
///    后落 `collab_key_ring`（vault 未解锁即半盲，取钥返回 null，§铁律 1）；
/// 2. **逐成员封信封**：`一次性 X25519 EK × 成员 SPK 公钥` DH → AES-256-GCM 封
///    K_bundle（与 `freemask.dart` 的单向加密同 shape）。服务端只当字节盲存，成员
///    拉到自己的信封后用 SPK 私钥解开（[openBundleKeyEnvelope]）——**发起人自己
///    不发信封**，它直接持有明文 K（在 key_ring 里）；
/// 3. **入箱自证**：把服务端仲裁所需的明文元数据（proposal_type/participants/
///    topology/quorum/expires_at/envelopes）一并写进 payload，`action_api` 抬出
///    带上——payload 自证让发件箱行跨进程/杀进程重启后仍走同一条建包路径。
///
/// ## 「认祖归宗」为什么必须存在
///
/// bundle_id 由**服务端发号**（`store.Propose` INSERT … RETURNING bundle_id）。本地在
/// 发出之前只有临时 id（[BundleDraft.localId]），窗口期内：
/// - 密钥信封挂在临时 id 上；
/// - 用户随手入队的后续操作（改期/裁员/取消）也挂在临时 id 上；
/// - 而服务端只认自己发的号，临时 id 恒 404。
///
/// 故 [PendingProposalReconciler] 拿到 201 回执后做迁改密钥 + 改绑发件箱 + 落包头；
/// 全部按幂等写（服务端重放 client_msg_id 会再次回到这里）。
library;

import 'dart:convert';
import 'dart:math';
import 'dart:typed_data';

import 'package:cryptography/cryptography.dart';

import '../data/collab_repository.dart';
import 'crypto/bundle_key_source.dart';
import 'fsm.dart' show kMaxCandidateSlots;
import 'outbox.dart';
import 'sealing.dart';

/// 单 Bundle 成员上限（含发起人，relay `store.MaxBundleMembers`=16，§1.7）。
const int kMaxBundleMembers = 16;

/// 24h 底座 TTL（§3.6 2026-10-25 双语义拆分）：一物两用——①幽灵软锁淡出判据
/// （本地视觉层恒 24h）；②提案存活 `proposal_alive_ttl` 的下限与「无时间锚草稿」
/// 的回落值（上限由 [proposalAliveTtlMs] 按目标日抬高）。
///
/// 与 `fsm.kConsensusSoftLockTtl` 同值同类目；写成常量字面量是因为 [BundleDraft]
/// 是 const 构造，只能吃编译期常量。
const int kProposalDefaultTtlMs = 24 * 60 * 60 * 1000;

/// 集体投票的候选时段上限（与 §4.4 ADD_SLOT 硬上限同一个值，`fsm` 是 SSOT）。
const int kMaxConsensusSlots = kMaxCandidateSlots;

/// 发起模式（§4.4：两种模式共用传输层，仅业务状态机不同）。
enum ProposalKind {
  /// 各司其职（Directive）：步骤 + 指派必答人 + M-of-N 门限。
  directive,

  /// 集体投票（Consensus）：候选时段池，全员三值偏好升至门限即固化。
  consensus,
}

String proposalTypeOf(ProposalKind kind) =>
    kind == ProposalKind.directive ? 'DIRECTIVE' : 'CONSENSUS';

/// 步骤 / 候选时段草稿。
///
/// Directive 模式下 [requiredUids] 是必答成员名单；Consensus 模式下步骤退化为候选
/// 时段，[stepId] 同时充当 slot_id（成员 VOTE 的 `slot_id ?? step_id` 收敛于此）。
class ProposalStepDraft {
  final String stepId;
  final String title;
  final List<String> requiredUids;
  final int threshold; // ≤0 = 全员（回放时回落 required_uids.length）
  final bool isOptional;
  final int? startsAtMs;
  final int? endsAtMs;

  const ProposalStepDraft({
    required this.stepId,
    this.title = '',
    this.requiredUids = const [],
    this.threshold = 0,
    this.isOptional = false,
    this.startsAtMs,
    this.endsAtMs,
  });
}

/// 发起草稿（纯数据，UI 收集 → [BundleProposalService.propose] 消费）。
class BundleDraft {
  /// **临时本地 id**：服务端发号前的占位号（认祖归宗时会改绑）。
  final String localId;
  final ProposalKind kind;
  final List<String> inviteeUids; // 不含发起人
  final List<ProposalStepDraft> steps;
  final int quorum; // Consensus 达成票数；Directiv 忽略
  final int ttlMs;
  final String? briefing;

  const BundleDraft({
    required this.localId,
    required this.kind,
    required this.inviteeUids,
    required this.steps,
    this.quorum = 1,
    this.ttlMs = kProposalDefaultTtlMs,
    this.briefing,
  });
}

/// 草稿合法性核验（§1.7 配额 + §4.2 骨架约束）。违反即抛 [ArgumentError]——UI 层
/// 应当在发送前就把按钮置灰，这里的抛错只是最后一道防线，不是 UX 通道。
void validateDraft(BundleDraft draft) {
  if (draft.inviteeUids.isEmpty) {
    throw ArgumentError('至少邀请一位成员（§1.7：单人不成 bundle）');
  }
  if (draft.inviteeUids.length + 1 > kMaxBundleMembers) {
    throw ArgumentError(
        '成员数 ${draft.inviteeUids.length + 1} 超过上限 $kMaxBundleMembers（§1.7）');
  }
  if (draft.steps.isEmpty) {
    throw ArgumentError(draft.kind == ProposalKind.consensus
        ? '集体投票至少要给一个候选时段'
        : '至少要有一个步骤');
  }
  if (draft.kind == ProposalKind.consensus &&
      draft.steps.length > kMaxConsensusSlots) {
    throw ArgumentError('候选时段上限 $kMaxConsensusSlots（§4.4 ADD_SLOT 硬上限）');
  }
  final seen = <String>{};
  for (final s in draft.steps) {
    if (!seen.add(s.stepId)) {
      throw ArgumentError('步骤 id 重复：${s.stepId}（骨架主键即 step_id）');
    }
  }
  if (draft.kind == ProposalKind.directive) {
    final pool = {...draft.inviteeUids};
    for (final s in draft.steps) {
      for (final uid in s.requiredUids) {
        if (!pool.contains(uid)) {
          throw ArgumentError('必答成员 $uid 不在被邀名单里');
        }
      }
    }
  }
}

/// 提案存活 TTL（§3.6 双语义拆分，2026-10-25 拍板）：
/// `proposal_alive_ttl = max(24h, 目标日前一天 − 发起时刻)`。
///
/// 目标日取草稿步骤最早的 `startsAtMs` 所在 UTC 日历日——发起时目标日在明文，
/// 成员端 FSM 骨架优先于内容（铁律 1）读不到密文时段，故存活时刻必须由发起端
/// 算好随载荷 `expires_at_ms` 下发；无任何时间锚的草稿回落 24h 底座。
/// 返回 TTL 时长（ms），不是绝对时刻。
int proposalAliveTtlMs({
  required int nowMs,
  required List<ProposalStepDraft> steps,
}) {
  const dayMs = 24 * 60 * 60 * 1000;
  var aliveUntilMs = nowMs + kProposalDefaultTtlMs;
  for (final s in steps) {
    final start = s.startsAtMs;
    if (start == null) continue;
    final dayBefore = (start ~/ dayMs) * dayMs - dayMs; // 目标日 00:00 UTC 的前一天
    if (dayBefore > aliveUntilMs) aliveUntilMs = dayBefore;
  }
  return aliveUntilMs - nowMs;
}

/// **服务端可见面**（§4.2 隐私边界）：只有纯结构四件套，标题/真实时间/备注一概
/// 不露——服务端据此推进 quorum 计数器，但读不到「几点做什么」。
Map<String, Object?> serverTopologyOf(BundleDraft draft) => {
      'steps': [
        for (final s in draft.steps)
          {
            'step_id': s.stepId,
            'required_uids': List<String>.from(s.requiredUids),
            'threshold': s.threshold <= 0 ? s.requiredUids.length : s.threshold,
            'is_optional': s.isOptional,
          },
      ],
    };

/// 加密载荷（全体成员可解）：结构 + 内容（标题/时段/备忘），字段取值与
/// `replay.signalFromPayload` 严格互逆——FSM 派发就是靠这几个键。
/// [aliveTtlMs] 为发起端算好的提案存活 TTL（§3.6，缺省回落 draft.ttlMs）。
Map<String, Object?> proposalPayloadOf({
  required String initiatorId,
  required BundleDraft draft,
  required int nowMs,
  List<Object?> envelopes = const [],
  int? aliveTtlMs,
}) {
  final topology = serverTopologyOf(draft);
  return <String, Object?>{
    'proposal_type': proposalTypeOf(draft.kind),
    'initiator_id': initiatorId, // 认祖归宗取发起人用（服务端事件虽有 sender，抽屉条目优先本地可执行性）
    'participants': <String>[initiatorId, ...draft.inviteeUids],
    'skeleton': [
      for (final s in draft.steps)
        <String, Object?>{
          'step_id': s.stepId,
          'required_uids': List<String>.from(s.requiredUids),
          'threshold': s.threshold <= 0 ? s.requiredUids.length : s.threshold,
          'is_optional': s.isOptional,
          if (s.title.isNotEmpty) 'title': s.title,
          if (s.startsAtMs != null) 'starts_at_ms': s.startsAtMs,
          if (s.endsAtMs != null) 'ends_at_ms': s.endsAtMs,
        },
    ],
    // Consensus：候选时段池 = 骨架 step_id 序列（VOTE 的 slot_id 口径）
    if (draft.kind == ProposalKind.consensus)
      'initial_slots': [for (final s in draft.steps) s.stepId],
    if (draft.kind == ProposalKind.consensus) 'quorum': draft.quorum,
    'topology': topology,
    'expires_at_ms': nowMs + (aliveTtlMs ?? draft.ttlMs),
    if (draft.briefing != null) 'briefing': draft.briefing,
    'envelopes': envelopes,
  };
}

// ---- 成员密钥信封（§5.3 K_bundle 分发） --------------------------------------

final Random _rng = Random.secure();

/// CSPRNG 生成 K_bundle（32B = AES-256-GCM 密钥长度）。
Uint8List generateBundleKey() =>
    Uint8List.fromList(List<int>.generate(32, (_) => _rng.nextInt(256)));

/// 给一位成员封 K_bundle：一次性 EK × 该成员 SPK 公钥 DH → AES-256-GCM。
///
/// 返回服务端 `member_key_envelopes[]` 的一行形状——relay 原样盲存
/// （`decodeEnvelopes` 只校验 base64 与 key_ver>0），解开所需的一切自描述在
/// `wrapped_key` 里。
Future<Map<String, Object?>> wrapBundleKeyForMember({
  required List<int> bundleKey,
  required int keyVer,
  required String recipientUserId,
  required List<int> memberSpkPub,
}) async {
  if (memberSpkPub.length != 32) {
    throw ArgumentError('成员 SPK 公钥必须是 X25519 raw 32B（坐标系铁律）');
  }
  final ek = await X25519().newKeyPair();
  final dh = await X25519().sharedSecretKey(
    keyPair: ek,
    remotePublicKey: SimplePublicKey(memberSpkPub, type: KeyPairType.x25519),
  );
  final sessionKey = Uint8List.fromList(await dh.extractBytes());
  final sealed = await sealPayload(
      sessionKey, Uint8List.fromList(bundleKey), keyVer);
  final ekPub = await ek.extractPublicKey();
  final wrapped = jsonEncode({
    'ek_pub': base64Encode(ekPub.bytes),
    'sealed': sealed.encode(),
  });
  return {
    'recipient_user_id': recipientUserId,
    'wrapped_key': base64Encode(utf8.encode(wrapped)),
    'key_ver': keyVer,
  };
}

/// 成员端开信封（自身 SPK 私钥 × 信封里的 EK 公钥）：拿到明文 K_bundle。
///
/// 本切片由 `wrapBundleKeyForMember` 与它互逆自证；被邀端拉 `/bundles/{id}/
/// key-envelopes` 后消费同函数（收口 Phase 4.5）。
Future<List<int>> openBundleKeyEnvelope({
  required Map<String, Object?> envelope,
  required SimpleKeyPair mySignedPreKey,
}) async {
  final wrapped = jsonDecode(
          utf8.decode(base64Decode(envelope['wrapped_key'] as String)))
      as Map<String, dynamic>;
  final ekPub = SimplePublicKey(base64Decode(wrapped['ek_pub'] as String),
      type: KeyPairType.x25519);
  final dh = await X25519().sharedSecretKey(
    keyPair: mySignedPreKey,
    remotePublicKey: ekPub,
  );
  final sessionKey = Uint8List.fromList(await dh.extractBytes());
  final sealed = SealedPayload.decode(wrapped['sealed'] as String);
  return openPayload(sessionKey, sealed);
}

// ---- 编排 -------------------------------------------------------------------

/// 本地发起结果（UI 据此给反馈 / 认祖归宗兑时淡出草稿态）。
class BundleProposal {
  final BundleDraft draft;
  final OutboxEntry entry; // 已落 PENDING_SEND（§11.1：先落盘，后反馈）
  final int keyVer;
  final int envelopeCount;

  const BundleProposal({
    required this.draft,
    required this.entry,
    required this.keyVer,
    required this.envelopeCount,
  });
}

/// 本地发起 bundle 的编排（纯 Orchestrator：无 UI、无端钟，时钟/随机数全注入）。
class BundleProposalService {
  final BundleKeySource keys;
  final OutboxRepository outbox;

  /// 被邀成员的 X25519 广播公钥来源（真机＝`peers_api.fetchPeerKeys`）。
  final Future<List<int>> Function(String userId) memberSpk;

  final List<int> Function() newBundleKey;
  final int Function() nowMs;

  const BundleProposalService({
    required this.keys,
    required this.outbox,
    required this.memberSpk,
    this.newBundleKey = generateBundleKey,
    int Function()? nowMs,
  }) : nowMs = nowMs ?? _defaultNow;

  static int _defaultNow() => DateTime.now().millisecondsSinceEpoch;

  Future<BundleProposal> propose({
    required String meId,
    required BundleDraft draft,
  }) async {
    validateDraft(draft);

    const keyVer = 1;
    final bundleKey = newBundleKey();
    if (bundleKey.length != 32) {
      throw StateError('K_bundle 必须是 32B（AES-256-GCM）');
    }
    // 先落户密钥，后入箱——弹出时按 (bundleId, keyVer) 取钥加密，顺序颠倒会让
    // drain 命中 Offline 保序重试（取钥失败＝传输层异常归一化为 Offline）。
    await keys.persist(draft.localId, keyVer, bundleKey);

    final envelopes = <Object?>[];
    for (final uid in draft.inviteeUids) {
      final spk = await memberSpk(uid);
      envelopes.add(await wrapBundleKeyForMember(
        bundleKey: bundleKey,
        keyVer: keyVer,
        recipientUserId: uid,
        memberSpkPub: spk,
      ));
    }

    final now = nowMs();
    final aliveTtl = proposalAliveTtlMs(nowMs: now, steps: draft.steps);
    final payload = proposalPayloadOf(
      initiatorId: meId,
      draft: draft,
      nowMs: now,
      envelopes: envelopes,
      // 显式 ttl（draft.ttlMs）是发起人下限覆盖：实际存活取 max(显式, 目标日推算)
      aliveTtlMs: aliveTtl > draft.ttlMs ? aliveTtl : draft.ttlMs,
    );
    final entry = await outbox.enqueue(
      bundleId: draft.localId,
      actionType: 'PROPOSE',
      payload: payload,
      keyVer: keyVer,
    );
    return BundleProposal(
      draft: draft,
      entry: entry,
      keyVer: keyVer,
      envelopeCount: envelopes.length,
    );
  }
}

/// 认祖归宗：临时本地 id → 服务端发号。
///
/// 挂在 [OutboxDrainer.onBundleCreated] 上，三件事在同一区间内完成：
/// 1. 密钥信封迁移（`collab_key_ring` 换挂服务端 bundle_id，临时条目删除）；
/// 2. 发件箱改绑（窗口期内入队的后续操作跟着改，否则恒 404）；
/// 3. 落包头（[CollabBundleRepository.ensureBundle]，供水位续拉与列表展示）。
///
/// **幂等**：服务端重放同一 client_msg_id 时会再次回到这里——三处写入分别是
/// ignore / update 零行 / ignore，重复执行零副作用。
class PendingProposalReconciler {
  final CollabBundleRepository bundles;
  final BundleKeySource keys;
  final OutboxRepository outbox;

  const PendingProposalReconciler({
    required this.bundles,
    required this.keys,
    required this.outbox,
  });

  Future<void> call(OutboxEntry entry, CreatedBundle ack) async {
    final p = entry.payload;
    final keyVer = entry.keyVer;
    final key = await keys(ack.localBundleId, keyVer);
    if (key != null) {
      await keys.persist(ack.bundleId, keyVer, key);
      await keys.discard(ack.localBundleId, keyVer);
    }
    await outbox.rebindBundle(ack.localBundleId, ack.bundleId);

    final participants = <String>[
      ...((p['participants'] as List?)?.cast<String>() ?? const <String>[]),
    ];
    final initiator = (p['initiator_id'] as String?) ?? entry.bundleId;
    await bundles.ensureBundle(
      bundleId: ack.bundleId,
      proposalType: (p['proposal_type'] as String?) ?? 'CONSENSUS',
      initiatorId: initiator,
      participants: participants,
      expiresAtMs: (p['expires_at_ms'] as int?) ?? 0,
      keyVersion: keyVer,
    );
  }
}
