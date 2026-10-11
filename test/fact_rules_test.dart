import 'package:flutter_test/flutter_test.dart';
import 'package:shiguang/action/fact_rules.dart';
import 'package:shiguang/action/rules.dart';
import 'package:shiguang/models/artifact.dart';

/// 事实规则引擎（fact-user-relay-draft.md §9.1/§9.2/§9.3）：纯函数单测。
Artifact _fact({
  required String state,
  required Map<String, Object?> payload,
}) =>
    Artifact(
      category: Artifact.categoryTransit,
      sourceKind: Artifact.sourceBooking,
      state: state,
      origin: Artifact.originShared,
      title: '玉龙雪山大索道',
      payload: payload,
    );

void main() {
  group('rule_scope 派生生效（§9.2，不入库）', () {
    test('缺省=长期有效', () {
      expect(FactRules.ruleScopeActive(null, DateTime(2026, 10, 20)), isTrue);
      expect(FactRules.ruleScopeActive({}, DateTime(2026, 10, 20)), isTrue);
    });
    test('落在 [valid_from, valid_to] 闭区间=生效，超出=失活', () {
      final scope = {'valid_from': '2026-10-01', 'valid_to': '2026-10-07'};
      expect(FactRules.ruleScopeActive(scope, DateTime(2026, 10, 1)), isTrue);
      expect(FactRules.ruleScopeActive(scope, DateTime(2026, 10, 7)), isTrue);
      expect(FactRules.ruleScopeActive(scope, DateTime(2026, 10, 8)), isFalse);
      expect(FactRules.ruleScopeActive(scope, DateTime(2026, 9, 30)), isFalse);
    });
    test('rule_scope 必填校验：缺 valid_to 即不完整', () {
      expect(FactRules.ruleScopeComplete({'valid_from': '2026-10-01'}), isFalse);
      expect(
          FactRules.ruleScopeComplete(
              {'valid_from': '2026-10-01', 'valid_to': '2026-10-07'}),
          isTrue);
    });
  });

  group('硬红线 factRuleViolations（§9.3）', () {
    test('forbidden_weekdays 命中块 date 的 weekday（跨午夜按开始日）', () {
      // 周一闭馆：2026-10-26 是周一
      final fact = _fact(
        state: Artifact.stateStructured,
        payload: {
          'rule_scope': {'valid_from': '2026-10-01', 'valid_to': '2026-12-31'},
          'constraints': {
            'forbidden_weekdays': [1],
          },
        },
      );
      final hits = factRuleViolations([fact],
          blockDate: DateTime(2026, 10, 26), startMin: 600, endMin: 660);
      expect(hits.length, 1);
      expect(hits.first.reason, contains('周一'));
      // 周二不命中
      expect(
          factRuleViolations([fact],
                  blockDate: DateTime(2026, 10, 27), startMin: 600, endMin: 660)
              .isEmpty,
          isTrue);
    });

    test('daily_deadline_min：块起始晚于截止=命中', () {
      final fact = _fact(
        state: Artifact.stateStructured,
        payload: {
          'rule_scope': {'valid_from': '2026-10-01', 'valid_to': '2026-12-31'},
          'constraints': {'daily_deadline_min': 960}, // 16:00
        },
      );
      expect(
          factRuleViolations([fact],
                  blockDate: DateTime(2026, 10, 20), startMin: 1000, endMin: 1060)
              .isNotEmpty,
          isTrue);
      expect(
          factRuleViolations([fact],
                  blockDate: DateTime(2026, 10, 20), startMin: 900, endMin: 960)
              .isEmpty,
          isTrue);
    });

    test('超出生效窗=无命中（派生失活）', () {
      final fact = _fact(
        state: Artifact.stateStructured,
        payload: {
          'rule_scope': {'valid_from': '2026-01-01', 'valid_to': '2026-01-31'},
          'constraints': {'forbidden_weekdays': [1]},
        },
      );
      expect(
          factRuleViolations([fact],
                  blockDate: DateTime(2026, 10, 26), startMin: 600, endMin: 660)
              .isEmpty,
          isTrue);
    });

    test('raw 不参检；无 rule_scope 的结构化 rule 默认长期生效（写侧才严进）', () {
      final raw = _fact(state: Artifact.stateRaw, payload: {
        'constraints': {'forbidden_weekdays': [1]}
      });
      final noScope = _fact(
        state: Artifact.stateStructured,
        payload: {'constraints': {'forbidden_weekdays': [1]}},
      );
      final hits = factRuleViolations([raw, noScope],
          blockDate: DateTime(2026, 10, 26), startMin: 600, endMin: 660);
      expect(hits.where((v) => v.fact.state == Artifact.stateRaw).isEmpty, isTrue,
          reason: 'raw 不参检');
      expect(hits.where((v) => v.fact.state == Artifact.stateStructured).isNotEmpty,
          isTrue,
          reason: '无 rule_scope 的结构化 rule 默认长期生效（读侧缺省=激活）');
    });
  });

  group('临场通关条 computeActionPasses（§9.1/§9.4）', () {
    test('departure + advance 合成窗口：now 在 [trigger,dismiss] 内才返回', () {
      final fact = _fact(
        state: Artifact.stateStructured,
        payload: {
          'time_anchors': [
            {'role': 'departure', 'kind': 'moment', 'date': '2026-10-20', 'min': 540},
          ],
          'constraints': {'advance_arrival_minutes': 45},
        },
      );
      // 09:00 发车 +45 → 08:15 target −120 → 06:15 trigger；+20 → 09:20 dismiss（全角色统一）
      expect(computeActionPasses([fact], DateTime(2026, 10, 20, 8, 0)).length, 1);
      expect(computeActionPasses([fact], DateTime(2026, 10, 20, 5, 0)).isEmpty, isTrue);
      expect(computeActionPasses([fact], DateTime(2026, 10, 20, 10, 0)).isEmpty, isTrue);
      // 收折边界钉死 20min（原 30min / 到点即收特判已废除）
      expect(computeActionPasses([fact], DateTime(2026, 10, 20, 9, 20)).length, 1);
      expect(computeActionPasses([fact], DateTime(2026, 10, 20, 9, 21)).isEmpty, isTrue);
    });

    test('全角色统一公式：deadline 同样减 advance 且 +20 收折（去 2×2 特判）', () {
      final fact = _fact(
        state: Artifact.stateStructured,
        payload: {
          'time_anchors': [
            {'role': 'deadline', 'kind': 'moment', 'date': '2026-10-20', 'min': 525},
          ],
          'constraints': {'advance_arrival_minutes': 45}, // 全角色统一生效
        },
      );
      // T_action=08:45 −45 → T_target=08:00 −120 → T_trigger=06:00；+20 → T_dismiss=09:05
      expect(computeActionPasses([fact], DateTime(2026, 10, 20, 5, 59)).isEmpty, isTrue);
      expect(computeActionPasses([fact], DateTime(2026, 10, 20, 6, 0)).length, 1);
      expect(computeActionPasses([fact], DateTime(2026, 10, 20, 8, 45)).length, 1);
      expect(computeActionPasses([fact], DateTime(2026, 10, 20, 9, 5)).length, 1);
      expect(computeActionPasses([fact], DateTime(2026, 10, 20, 9, 6)).isEmpty, isTrue);
    });

    test('min=null 纯日期锚不参与临场计算（v0.1.6 刚性前置）', () {
      final fact = _fact(
        state: Artifact.stateStructured,
        payload: {
          'time_anchors': [
            {'role': 'checkin', 'kind': 'moment', 'date': '2026-10-17', 'min': null},
          ],
        },
      );
      expect(computeActionPasses([fact], DateTime(2026, 10, 17, 0, 0)).isEmpty, isTrue);
      expect(computeActionPasses([fact], DateTime(2026, 10, 16, 22, 0)).isEmpty, isTrue,
          reason: 'min 不得回退 0，否则前夜 22:00 误弹通关条');
    });

    test('信息类 role（arrival/reference）与未知中文 role 均不触发', () {
      Artifact f(String role) => _fact(
            state: Artifact.stateStructured,
            payload: {
              'time_anchors': [
                {'role': role, 'kind': 'moment', 'date': '2026-10-20', 'min': 540},
              ],
            },
          );
      expect(computeActionPasses([f('arrival')], DateTime(2026, 10, 20, 8, 0)).isEmpty, isTrue);
      expect(computeActionPasses([f('reference')], DateTime(2026, 10, 20, 8, 0)).isEmpty, isTrue);
      // 存量中文 role 读侧容错按 reference（不触发、不崩溃）
      expect(computeActionPasses([f('发车')], DateTime(2026, 10, 20, 8, 0)).isEmpty, isTrue);
    });

    test('本地物理时钟：now 按本地构造比较，非 UTC', () {
      final fact = _fact(
        state: Artifact.stateStructured,
        payload: {
          'time_anchors': [
            {'role': 'departure', 'kind': 'moment', 'date': '2026-10-20', 'min': 540},
          ],
        },
      );
      // 08:00 本地时间（含时区偏移）应命中；构造函数本身即本地，验证不抛
      expect(computeActionPasses([fact], DateTime(2026, 10, 20, 8, 0)).length, 1);
    });
  });

  group('常量与角色映射', () {
    test('ArtifactRules 临场常量单一出处', () {
      expect(ArtifactRules.passLeadMinutes, 120);
      expect(ArtifactRules.passDismissAfterMinutes, 20);
    });
    test('roleLabel 受控枚举→白话', () {
      expect(FactRules.roleLabel('departure'), '发车/起飞');
      expect(FactRules.roleLabel('deadline'), '停止检票');
      expect(FactRules.roleLabel('checkin'), '开放入住');
      expect(FactRules.roleLabel('checkout'), '退房');
      expect(FactRules.roleLabel('arrival'), '到达');
      expect(FactRules.roleLabel(null), '参考');
      expect(FactRules.roleLabel('发车'), '参考');
    });
  });
}
