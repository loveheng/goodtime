import 'dart:convert';

import 'package:flutter/foundation.dart';

/// artifacts 表实体（fact-user-relay-draft.md §1.1/§1.2）：硬事实凭证=现实存证——
/// 车票/门票/酒店/场馆通知/口信结构化入册，**永不直接上时间轴**（锚点驱动块生成，
/// 事实只做块的事实来源挂载显示）。
///
/// 单一真相纪律（§1.1）：[payload] 内嵌契约 JSON（首字段 "v":1）是唯一真相，
/// category/state/origin/title/badge/captured_by/plan_id/block_id 列均为提取投影，
/// 命令层同源写入——**修改 plan_id/block_id 必须同步 payload 内对应字段**（双写
/// 铁律，列与 JSON 矛盾=第二真相）。block_id 软引用无外键：凭证比块长寿，日切
/// 删块/melted 不牵连凭证；删除处置（detach 至未归属池）归命令层。
class Artifact {
  Artifact({
    this.id,
    required this.category,
    required this.sourceKind,
    this.state = stateRaw,
    required this.origin,
    required this.title,
    this.badge,
    Map<String, Object?>? payload,
    List<Object?>? attachments,
    this.capturedBy = capturedByMe,
    this.planId,
    this.blockId,
    this.createdAt,
    this.updatedAt,
    this.version = 0,
  })  : payload = _withV(payload ?? const {}),
        attachments = attachments ?? const [];

  /// 契约版本单点：内存态 payload 恒带 "v"（与落库 encodePayload 口径一致，
  /// 快照/读侧/落库三处不再出现第二形状）。
  static Map<String, Object?> _withV(Map<String, Object?> payload) =>
      payload.containsKey('v') ? payload : {'v': 1, ...payload};

  // category 五类收敛（§1.2）
  static const categoryTransit = 'transit';
  static const categoryTicket = 'ticket';
  static const categoryHotel = 'hotel';
  static const categoryVenue = 'venue';
  static const categoryVerbal = 'verbal';

  // source_kind 三级可靠性
  static const sourceBooking = 'booking';
  static const sourceAnnouncement = 'announcement';
  static const sourceVerbal = 'verbal';

  // state 三值：voided=退票/作废（存证保留不删，读侧全通道静默——不进搭载/
  // 临场通关条/一致性比对/凭证区卡面，2026-10-07 定稿修正）
  static const stateRaw = 'raw';
  static const stateStructured = 'structured';
  static const stateVoided = 'voided';

  // origin 出生来源
  static const originShared = 'shared';
  static const originQuicknote = 'quicknote';
  static const originAi = 'ai';
  static const originManual = 'manual';

  /// 多人预留钉子（主草案 §3.4）：roster 落地后回填真实身份
  static const capturedByMe = 'me';

  final String? id;
  final String category;
  final String sourceKind;
  final String state;
  final String origin;
  final String title;

  /// 时间轴微标单值（05车12F）；仅 booking 类 AI 显式指定，命令层超 12 字符截断
  final String? badge;

  /// 契约 JSON 单一真相（time_anchors/constraints/hero_metrics/raw_text/plan_id/block_id…）
  final Map<String, Object?> payload;

  /// 预留列：MVP 恒空数组，UI 见空忽略（§1.2 接口先立、逻辑不写）
  final List<Object?> attachments;
  final String capturedBy;

  /// null=未归属池（D2：宿主=挂载 sheet 常驻组+计划列表页派生入口）
  final String? planId;

  /// 软引用不设外键——升格块的关联（一致性比对与 🎫 微标的数据源）
  final String? blockId;
  final int? createdAt; // 毫秒；落库前可空，由仓库补
  final int? updatedAt; // 毫秒
  final int version; // 乐观锁：提炼回填/改挂/作废走 CAS

  Map<String, Object?> toMap() => {
        if (id != null) 'id': id,
        'category': category,
        'source_kind': sourceKind,
        'state': state,
        'origin': origin,
        'title': title,
        'badge': badge,
        'payload': encodePayload(payload),
        'attachments': jsonEncode(attachments),
        'captured_by': capturedBy,
        'plan_id': planId,
        'block_id': blockId,
        if (createdAt != null) 'created_at': createdAt,
        if (updatedAt != null) 'updated_at': updatedAt,
        'version': version,
      };

  factory Artifact.fromMap(Map<String, Object?> map) => Artifact(
        id: map['id'] as String,
        category: map['category'] as String,
        sourceKind: map['source_kind'] as String,
        state: map['state'] as String,
        origin: map['origin'] as String,
        title: map['title'] as String,
        badge: map['badge'] as String?,
        payload: decodePayload(map['payload'] as String?),
        attachments: _decodeStringList(map['attachments'] as String?, 'attachments'),
        capturedBy: map['captured_by'] as String? ?? capturedByMe,
        planId: map['plan_id'] as String?,
        blockId: map['block_id'] as String?,
        createdAt: map['created_at'] as int?,
        updatedAt: map['updated_at'] as int?,
        version: map['version'] as int? ?? 0,
      );

  static const _keep = Object();

  /// payload 编码单点：首字段固定 "v":1（契约内嵌版本，反序列化按 v 分支——
  /// attachments 启用等演进时旧数据零猜测）。所有写路径（建行/patch/detach）
  /// 一律经此编码，保证 v 序稳定。
  static String encodePayload(Map<String, Object?> payload) =>
      jsonEncode({'v': payload['v'] ?? 1, ...payload});

  /// 双写铁律的 payload 同步单点（§1.1）：命令层修改 plan_id/block_id 列时调用，
  /// 返回同步了投影字段的 payload 副本（null=移除键；缺省=保持原值不改）。
  static Map<String, Object?> payloadWithRefs(
    Map<String, Object?> payload, {
    Object? planId = _keep,
    Object? blockId = _keep,
  }) {
    final out = Map<String, Object?>.of(payload);
    if (!identical(planId, _keep)) {
      if (planId == null) {
        out.remove('plan_id');
      } else {
        out['plan_id'] = planId;
      }
    }
    if (!identical(blockId, _keep)) {
      if (blockId == null) {
        out.remove('block_id');
      } else {
        out['block_id'] = blockId;
      }
    }
    return out;
  }

  /// payload 读侧防御：整列非 JSON/非对象 → [DEGRADE] 丢弃回最小契约（卡面字段
  /// 逐层隐藏兜底，不挡读路径）；正常路径写入口已保证合法，触发即库被外部改坏。
  static Map<String, Object?> decodePayload(String? raw) {
    if (raw == null || raw.isEmpty) return const {'v': 1};
    try {
      final decoded = jsonDecode(raw);
      if (decoded is Map) return Map<String, Object?>.from(decoded);
      _logDegrade('payload 非 JSON 对象已丢弃: $raw');
    } catch (_) {
      _logDegrade('payload 整列畸形已丢弃: $raw');
    }
    return const {'v': 1};
  }

  /// JSON 数组读侧防御：非 JSON/非数组 → [DEGRADE] 丢空（attachments 见空忽略）。
  static List<Object?> _decodeStringList(String? raw, String column) {
    if (raw == null || raw.isEmpty) return const [];
    try {
      final decoded = jsonDecode(raw);
      if (decoded is List) return List<Object?>.from(decoded);
      _logDegrade('$column 非 JSON 数组已丢弃: $raw');
    } catch (_) {
      _logDegrade('$column 整列畸形已丢弃: $raw');
    }
    return const [];
  }

  // DEGRADE: [artifacts_read_malformed] 读侧兜底丢弃，不挡主路径
  static void _logDegrade(String detail) {
    debugPrint('[DEGRADE][artifacts_read_malformed] $detail');
  }
}
