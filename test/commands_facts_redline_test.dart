import 'package:flutter_test/flutter_test.dart';
import 'package:shiguang/action/command_handler.dart';
import 'package:shiguang/action/commands.dart';
import 'package:shiguang/data/db.dart';
import 'package:shiguang/data/repository.dart';
import 'package:shiguang/models/artifact.dart';
import 'package:shiguang/models/plan.dart';
import 'package:sqflite_common_ffi/sqflite_ffi.dart';

/// 事实硬红线（fact-user-relay-draft.md §9.2/§9.3）：rule_scope 建档校验 + propose/adjust
/// 子树校验 + rejected_by_facts 拒绝体 + 刻度不对称（human 通道放行）。
void main() {
  setUpAll(() {
    sqfliteFfiInit();
    databaseFactory = databaseFactoryFfi;
    Db.overridePath(inMemoryDatabasePath);
  });

  late Repository repo;
  late CommandHandler handler;

  setUp(() async {
    repo = Repository();
    handler = CommandHandler(repo);
    final db = await Db.instance();
    await db.delete('artifacts');
    await db.delete('schedule_blocks');
    await db.delete('fixed_slots');
    await db.delete('plans');
    await db.delete('app_settings');
    await repo.settingsSet({'wake_time': '420', 'sleep_time': '1380'});
  });

  Matcher throwsCode(String code) =>
      throwsA(isA<ActionException>().having((e) => e.code, 'code', code));

  /// 周一闭馆规则凭证（周一=2026-10-26）：rule 类 + 生效窗 + forbidden_weekdays[1]。
  Future<String> _ruleFact(String planId) async {
    final a = await repo.addArtifact(Artifact(
      category: Artifact.categoryVenue,
      sourceKind: Artifact.sourceAnnouncement,
      state: Artifact.stateStructured,
      origin: Artifact.originShared,
      title: '玉龙雪山景区周一闭馆',
      payload: {
        'time_anchors': [
          {'role': 'reference', 'kind': 'rule', 'text': '周一闭馆'}
        ],
        'rule_scope': {'valid_from': '2026-01-01', 'valid_to': '2026-12-31'},
        'constraints': {
          'forbidden_weekdays': [1],
        },
      },
      planId: planId,
    ));
    return a.id!;
  }

  group('rule_scope 建档严进（§9.2 Q-A）', () {
    test('kind=rule 缺 rule_scope → invalidRequest', () async {
      await expectLater(
        handler.execute(
          UpsertFactsCommand(
            category: Artifact.categoryVenue,
            sourceKind: Artifact.sourceAnnouncement,
            title: '闭馆',
            state: Artifact.stateStructured,
            timeAnchors: [
              {'role': 'reference', 'kind': 'rule', 'text': '周一闭馆'}
            ],
          ),
          actor: CommandActor.ai,
        ),
        throwsCode('invalid_request'),
      );
    });

    test('kind=rule 给齐 rule_scope → 通过', () async {
      final r = await handler.execute(
        UpsertFactsCommand(
          category: Artifact.categoryVenue,
          sourceKind: Artifact.sourceAnnouncement,
          title: '闭馆',
          state: Artifact.stateStructured,
          timeAnchors: [
            {'role': 'reference', 'kind': 'rule', 'text': '周一闭馆'}
          ],
          ruleScope: {'valid_from': '2026-01-01', 'valid_to': '2026-12-31'},
          constraints: {'forbidden_weekdays': [1]},
          origin: Artifact.originShared,
        ),
        actor: CommandActor.ai,
      );
      final back = (await repo.artifactById(r.targetId!))!;
      expect(back.payload['rule_scope'], isNotNull,
          reason: 'rule_scope 落库（读侧缺省=长期生效，写侧 kind=rule 严进）');
      expect(back.category, Artifact.categoryVenue);
    });

    test('非 rule 类凭证无需 rule_scope', () async {
      final r = await handler.execute(
        UpsertFactsCommand(
          category: Artifact.categoryTransit,
          sourceKind: Artifact.sourceBooking,
          title: 'D8724',
          state: Artifact.stateStructured,
          timeAnchors: [
            {'role': 'departure', 'kind': 'moment', 'date': '2026-10-20', 'min': 540}
          ],
          origin: Artifact.originShared,
        ),
        actor: CommandActor.ai,
      );
      expect(r.snapshot!['category'], Artifact.categoryTransit);
    });
  });

  group('硬红线子树校验 + rejected_by_facts（§9.3）', () {
    test('AI 在周一闭馆日排块 → schedule_rejected 含 rejected_by_facts', () async {
      final plan = await repo.addPlan(Plan(title: '云南七天'));
      await _ruleFact(plan.id!);
      await expectLater(
        handler.execute(
          ProposeScheduleCommand(
            date: '2026-10-26', // 周一
            items: [
              ProposedItem(planId: plan.id, label: '进山', startMin: 600, endMin: 660),
            ],
          ),
          actor: CommandActor.ai,
        ),
        throwsA(isA<ActionException>()
            .having((e) => e.code, 'code', 'schedule_rejected')
            .having(
              (e) => (e.data!['rejections'] as List).any(
                  (r) => (r['data'] as Map?)?['rejected_by_facts'] != null),
              'rejected_by_facts 存在',
              isTrue,
            )),
      );
    });

    test('周二排块不触红线（星期归因正确）', () async {
      final plan = await repo.addPlan(Plan(title: '云南七天'));
      await _ruleFact(plan.id!);
      final r = await handler.execute(
        ProposeScheduleCommand(
          date: '2026-10-27', // 周二
          items: [
            ProposedItem(planId: plan.id, label: '进山', startMin: 600, endMin: 660),
          ],
        ),
        actor: CommandActor.ai,
      );
      expect((r.data!['blocks'] as List).length, 1);
    });

    test('inalid：超出生效窗的 rule 不参检', () async {
      final plan = await repo.addPlan(Plan(title: '云南七天'));
      final a = await repo.addArtifact(Artifact(
        category: Artifact.categoryVenue,
        sourceKind: Artifact.sourceAnnouncement,
        state: Artifact.stateStructured,
        origin: Artifact.originShared,
        title: '旧年闭馆',
        payload: {
          'time_anchors': [
            {'role': 'reference', 'kind': 'rule', 'text': '周一闭馆'}
          ],
          'rule_scope': {'valid_from': '2025-01-01', 'valid_to': '2025-12-31'},
          'constraints': {'forbidden_weekdays': [1]},
        },
        planId: plan.id,
      ));
      expect(a.id, isNotNull);
      final r = await handler.execute(
        ProposeScheduleCommand(
          date: '2026-10-26',
          items: [
            ProposedItem(planId: plan.id, label: '进山', startMin: 600, endMin: 660),
          ],
        ),
        actor: CommandActor.ai,
      );
      expect((r.data!['blocks'] as List).length, 1);
    });
  });
}
