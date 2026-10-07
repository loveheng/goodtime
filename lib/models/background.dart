import 'dart:convert';

import 'package:flutter/foundation.dart';

/// backgrounds 表实体（background-context-draft.md §2）：背景信息 = 软上下文——
/// 叙事性、定性的行程背景，仅影响 AI 软提案权重与提案理由，绝不生成硬约束
/// （§1：事实是「引擎必须遵守的规则母体」，背景是「AI 应记在心里的上下文」）。
///
/// 手编契约（§3）：raw_source_text 永不变（原话仅建档时填一次）、source 出生
/// 来源不可变——两者均不在 [copyWith] 与仓库 patch 白名单内，数据层就杜绝翻转通道。
class Background {
  Background({
    this.id,
    required this.scope,
    this.planId,
    required this.content,
    this.rawSourceText,
    this.tags = const [],
    List<String>? applicableDates,
    required this.source,
    this.capturedBy = capturedByMe,
    this.createdAt,
    this.updatedAt,
    this.version = 0,
  }) : applicableDates = _normalizeApplicableDates(applicableDates);

  static const scopeGlobal = 'global'; // 常驻画像叙事（受注入预算约束，§2）
  static const scopePlan = 'plan'; // 行程背景，随计划终结（delete_plan 级联销毁）

  static const sourceUser = 'user';
  static const sourceAiDerived = 'ai_derived';

  /// 多人预留钉子（主草案 §3.4）：roster 落地后回填真实身份
  static const capturedByMe = 'me';

  final String? id;

  /// global | plan；建档定死，不做 scope 迁移（改挂=删了重录）
  final String scope;

  /// scope=plan 时必填；FK NO ACTION 护栏——引用未处置让删除硬失败，处置归命令层
  final String? planId;

  /// 给人与 AI 读的归纳描述（机读/展示分离：原话在 [rawSourceText]）
  final String content;

  /// 原话永存（溯源/撤销重构）；永不变、永不被后续提炼覆盖
  final String? rawSourceText;

  /// 自由字符串标签（#健康 式，不锁闭枚举；真需机读再收敛）
  final List<String> tags;

  /// 瞬态背景作用日期窗（单日 ISO 日期字符串数组，一律日历日非作息日）；
  /// null=长期有效。比对=集合成员判断（当日 ∈ 数组），零语义。
  final List<String>? applicableDates;

  /// 出生来源 provenance，不因后续编辑翻转
  final String source;
  final String capturedBy;
  final int? createdAt; // 毫秒；落库前可空，由仓库补
  final int? updatedAt; // 毫秒
  final int version; // 乐观锁：字段级 patch + CAS，同 plans/blocks 纪律

  /// 编辑面=content/tags/applicable_dates 三字段（契约字段不可达，故不在参数里）
  Background copyWith({
    String? content,
    List<String>? tags,
    List<String>? applicableDates,
  }) =>
      Background(
        id: id,
        scope: scope,
        planId: planId,
        content: content ?? this.content,
        rawSourceText: rawSourceText,
        tags: tags ?? this.tags,
        applicableDates: applicableDates ?? this.applicableDates,
        source: source,
        capturedBy: capturedBy,
        createdAt: createdAt,
        updatedAt: updatedAt,
        version: version,
      );

  Map<String, Object?> toMap() => {
        if (id != null) 'id': id,
        'scope': scope,
        'plan_id': planId,
        'content': content,
        'raw_source_text': rawSourceText,
        'tags': jsonEncode(tags),
        if (applicableDates != null) 'applicable_dates': jsonEncode(applicableDates),
        'source': source,
        'captured_by': capturedBy,
        if (createdAt != null) 'created_at': createdAt,
        if (updatedAt != null) 'updated_at': updatedAt,
        'version': version,
      };

  factory Background.fromMap(Map<String, Object?> map) => Background(
        id: map['id'] as String,
        scope: map['scope'] as String,
        planId: map['plan_id'] as String?,
        content: map['content'] as String,
        rawSourceText: map['raw_source_text'] as String?,
        tags: decodeTags(map['tags'] as String?),
        applicableDates: decodeApplicableDates(map['applicable_dates'] as String?),
        source: map['source'] as String,
        capturedBy: map['captured_by'] as String? ?? capturedByMe,
        createdAt: map['created_at'] as int?,
        updatedAt: map['updated_at'] as int?,
        version: map['version'] as int? ?? 0,
      );

  /// applicable_dates 写入口规范化（施工钉子 §2，构造函数单点保证所有构建路径
  /// 一致）：去重+排序（集合判断与「过去的背景」折叠比较需稳定序）、空数组归一化
  /// 为 null（「永不生效」是陷阱态，语义=长期）。合法性双检不在此处——校验单点=
  /// 命令层 UpsertBackground，畸形要在写入口被拒绝而非静默丢。
  static List<String>? _normalizeApplicableDates(List<String>? dates) {
    if (dates == null || dates.isEmpty) return null;
    return dates.toSet().toList()..sort();
  }

  /// applicable_dates 读侧防御（施工钉子 §2）：合法元素逐个双检保留；畸形元素
  /// 过滤并打 [DEGRADE]。全灭（或整列废）→ 空数组=fail-closed（永不进排程上下文，
  /// 治理面仍可见可清理），绝不降级成 null「长期」让坏窗变恒注入。
  static List<String>? decodeApplicableDates(String? raw) {
    if (raw == null || raw.isEmpty) return null;
    final out = <String>[];
    for (final e in _decodeStringList(raw, 'applicable_dates')) {
      if (isValidIsoDate(e)) {
        out.add(e);
      } else {
        _logDegrade('applicable_dates', e);
      }
    }
    return out;
  }

  static List<String> decodeTags(String? raw) {
    if (raw == null || raw.isEmpty) return const [];
    return _decodeStringList(raw, 'tags');
  }

  /// 逐元素双检（公开给命令层复用——写入口校验单点在 UpsertBackground，
  /// 但判定逻辑只此一份）：正则挡格式 + DateTime.tryParse 回写比对挡非法日历日
  /// （纯正则挡不住 2026-02-30；构造器归一化同理被回写比对识破）。
  static bool isValidIsoDate(String s) {
    if (!RegExp(r'^\d{4}-\d{2}-\d{2}$').hasMatch(s)) return false;
    final d = DateTime.tryParse(s);
    return d != null && d.toIso8601String().substring(0, 10) == s;
  }

  /// JSON 字符串数组读侧防御：整列非 JSON/非数组 → 丢弃；元素非字符串 → 逐个过滤。
  /// 正常路径写入口已保证合法，触发即库被外部改坏——丢数据不挡读路径，日志留痕。
  static List<String> _decodeStringList(String raw, String column) {
    final Object? decoded;
    try {
      decoded = jsonDecode(raw);
    } catch (_) {
      return _degradeColumn(column, raw);
    }
    if (decoded is! List<dynamic>) return _degradeColumn(column, raw);
    final out = <String>[];
    for (final e in decoded) {
      if (e is String) {
        out.add(e);
      } else {
        _logDegrade(column, '$e');
      }
    }
    return out;
  }

  // DEGRADE: [backgrounds_read_malformed] 整列畸形丢弃——读侧兜底，不挡主路径
  static List<String> _degradeColumn(String column, String raw) {
    debugPrint('[DEGRADE][backgrounds_read_malformed] $column 整列畸形已丢弃: $raw');
    return const [];
  }

  // DEGRADE: [backgrounds_read_malformed] 元素畸形过滤——读侧兜底，不挡主路径
  static void _logDegrade(String column, String detail) {
    debugPrint('[DEGRADE][backgrounds_read_malformed] $column 畸形元素已过滤: $detail');
  }
}
