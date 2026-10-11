import 'package:flutter/foundation.dart';

import '../models/artifact.dart';
import '../util/schedule_day.dart';
import 'rules.dart';

/// 事实凭证规则引擎（fact-user-relay-draft.md §9.1/§9.2/§9.3，2026-10-08 拍板）：
///
/// - **role 受控枚举**：临场通关条机读判据（行动类触发 / 信息类忽略）+ 存量中文
///   role 读侧容错（按 reference 处理 + [DEGRADE] 日志，不迁移不改写 payload）。
/// - **rule_scope 生效窗派生**：读时算，不落库改 state（与补给带同纪律）。
/// - **硬红线校验**：forbidden_weekdays / daily_deadline_min + 派生失活。
/// - **临场通关条时间合成**：T_target / T_trigger / T_dismiss（本地物理时钟口径）。
///
/// 纯函数模块，无 IO 依赖，便于单测（test/fact_rules_test.dart）。
abstract final class FactRules {
  /// moment.role 受控枚举（§9.1）：行动类 4 个参与临场触发，信息类 2 个仅展示。
  static const Set<String> actionableRoles = {
    'departure',
    'deadline',
    'checkin',
    'checkout',
  };

  /// 解析 role（§9.1 存量兼容）：未知/空 → reference（不触发），并记录 [DEGRADE]。
  static String normalizeRole(String? raw) {
    if (raw == null) return 'reference';
    if (actionableRoles.contains(raw) ||
        raw == 'arrival' ||
        raw == 'reference') {
      return raw;
    }
    _logDegrade('未知 moment role=$raw → 按 reference 处理（不触发临场条）');
    return 'reference';
  }

  /// role → 白话标签（§9.1，UI 出白话不出枚举）：发车/起飞、停止检票、开放入住、
  /// 退房、到达、参考。未知/信息类兜底「参考」。
  static String roleLabel(String? role) => switch (normalizeRole(role)) {
        'departure' => '发车/起飞',
        'deadline' => '停止检票',
        'checkin' => '开放入住',
        'checkout' => '退房',
        'arrival' => '到达',
        _ => '参考',
      };

  /// rule 类锚所属凭证建档必须显式给出生效窗（§9.2 Q-A：漏填即拒，杜绝永久生效误杀）。
  static bool ruleScopeComplete(Map<String, Object?>? ruleScope) {
    if (ruleScope == null) return false;
    final from = tryParseIsoDate(ruleScope['valid_from'] as String? ?? '');
    final to = tryParseIsoDate(ruleScope['valid_to'] as String? ?? '');
    return from != null && to != null;
  }

  /// 派生生效判定（不入库）：date 落在 [valid_from, valid_to] 闭区间即生效；无窗=长期有效。
  static bool ruleScopeActive(Map<String, Object?>? ruleScope, DateTime date) {
    if (ruleScope == null) return true;
    final from = tryParseIsoDate(ruleScope['valid_from'] as String? ?? '');
    final to = tryParseIsoDate(ruleScope['valid_to'] as String? ?? '');
    if (from == null && to == null) return true;
    final d = DateTime(date.year, date.month, date.day);
    if (from != null && d.isBefore(from)) return false;
    if (to != null && d.isAfter(to)) return false;
    return true;
  }
}

/// 余生事实红线命中（硬红线对单块）。
class FactViolation {
  const FactViolation(this.fact, this.reason);

  final Artifact fact;
  final String reason;
}

List<int>? _forbiddenWeekdays(Artifact a) {
  final c = a.payload['constraints'] as Map<String, Object?>?;
  final v = c?['forbidden_weekdays'];
  if (v is List) return [for (final x in v) if (x is int) x];
  return null;
}

int? _dailyDeadlineMin(Artifact a) {
  final c = a.payload['constraints'] as Map<String, Object?>?;
  final v = c?['daily_deadline_min'];
  return v is int ? v : null;
}

/// 硬红线校验（§9.3）：给定块的归属日历日与起止分钟，对一组事实（通常=本 plan
/// 及递归子树的 structured 凭证）逐条判定。weekday 一律取块 date（开始日），
/// 跨午夜块溢出次日不校（与「按开始日归属」单一真相一致）。
List<FactViolation> factRuleViolations(
  List<Artifact> facts, {
  required DateTime blockDate,
  required int startMin,
  required int endMin,
}) {
  final weekday =
      DateTime(blockDate.year, blockDate.month, blockDate.day).weekday; // 1=周一
  final out = <FactViolation>[];
  for (final f in facts) {
    if (f.state != Artifact.stateStructured) continue;
    final scope = f.payload['rule_scope'] as Map<String, Object?>?;
    if (!FactRules.ruleScopeActive(scope, blockDate)) continue;
    final fwd = _forbiddenWeekdays(f);
    if (fwd != null && fwd.contains(weekday)) {
      out.add(FactViolation(f, '${f.title}：${_weekdayName(weekday)}禁止（规则提醒）'));
    }
    final dl = _dailyDeadlineMin(f);
    if (dl != null && startMin > dl) {
      out.add(FactViolation(f, '${f.title}：每日 ${_clockOf(dl)} 后停止受理'));
    }
  }
  return out;
}

/// 临场通关条候选项（§9.1/§9.4）。
class ActionPass {
  const ActionPass({
    required this.fact,
    required this.role,
    required this.actionTime,
    required this.triggerTime,
    required this.dismissTime,
    required this.title,
  });

  final Artifact fact;
  final String role; // 归一后的受控枚举值
  final DateTime actionTime;
  final DateTime triggerTime;
  final DateTime dismissTime;
  final String title;
}

DateTime? _abs(String? date, int? min) {
  final d = tryParseIsoDate(date ?? '');
  if (d == null || min == null) return null;
  return DateTime(d.year, d.month, d.day).add(Duration(minutes: min));
}

int? _advanceMin(Artifact a) {
  final c = a.payload['constraints'] as Map<String, Object?>?;
  final v = c?['advance_arrival_minutes'];
  return v is int ? v : null;
}

/// 临场通关条候选计算（§9.1/§9.4）：扫描 structured 事实的 moment 锚，只取行动类
/// role；按分层时相推进公式合成窗口，仅返回 [trigger, dismiss] 含 nowLocal 的条目。
/// **本地物理时钟判定**（禁用 toUtc()——旅行跨时区场景会错位）。
List<ActionPass> computeActionPasses(List<Artifact> facts, DateTime nowLocal) {
  final out = <ActionPass>[];
  for (final f in facts) {
    if (f.state != Artifact.stateStructured) continue;
    final anchors = f.payload['time_anchors'];
    if (anchors is! List) continue;
    for (final raw in anchors) {
      if (raw is! Map) continue;
      if (raw['kind'] != 'moment') continue;
      final role = FactRules.normalizeRole(raw['role'] as String?);
      if (!FactRules.actionableRoles.contains(role)) continue;
      final tAction = _abs(raw['date'] as String?, raw['min'] as int?);
      if (tAction == null) continue;
      // T_target：全角色统一 T_action − (advance ?? 0)（2026-10-09 去 2×2 特判，§9.1）
      final advance = _advanceMin(f) ?? 0;
      final tTarget = tAction.subtract(Duration(minutes: advance));
      final tTrigger =
          tTarget.subtract(Duration(minutes: ArtifactRules.passLeadMinutes));
      // T_dismiss：全角色统一 T_action + 20min（2026-10-09 去 2×2 特判，§9.1）
      final tDismiss =
          tAction.add(Duration(minutes: ArtifactRules.passDismissAfterMinutes));
      if (!nowLocal.isBefore(tTrigger) && !nowLocal.isAfter(tDismiss)) {
        out.add(ActionPass(
          fact: f,
          role: role,
          actionTime: tAction,
          triggerTime: tTrigger,
          dismissTime: tDismiss,
          title: f.title,
        ));
      }
    }
  }
  out.sort((a, b) => a.actionTime.compareTo(b.actionTime));
  return out;
}

String _weekdayName(int weekday) =>
    const ['周一', '周二', '周三', '周四', '周五', '周六', '周日'][weekday - 1];

String _clockOf(int min) {
  final h = min ~/ 60;
  final m = min % 60;
  return '${h.toString().padLeft(2, '0')}:${m.toString().padLeft(2, '0')}';
}

/// DEGRADE：读侧兜底日志（未知 role 等），不阻断主路径。
void _logDegrade(String detail) {
  // ignore: avoid_print
  debugPrint('[DEGRADE][fact_rules] $detail');
}
