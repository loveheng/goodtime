import 'package:flutter_test/flutter_test.dart';
import 'package:shiguang/data/db.dart';
import 'package:shiguang/data/repository.dart';
import 'package:shiguang/models/background.dart';
import 'package:shiguang/models/fixed_slot.dart';
import 'package:shiguang/models/plan.dart';
import 'package:shiguang/models/schedule_block.dart';
import 'package:sqflite_common_ffi/sqflite_ffi.dart';

/// 数据层契约单测：三表台账核心行为（schedule-app.md §4；夹具模式照抄拾贝 repository_test）。
void main() {
  setUpAll(() {
    // VM 单测用 ffi 数据库工厂；内存库做 isolate 级隔离（套件并行不互踩）
    sqfliteFfiInit();
    databaseFactory = databaseFactoryFfi;
    Db.overridePath(inMemoryDatabasePath);
  });

  late Repository repo;
  setUp(() async {
    repo = Repository();
    // 清空上例残留（内存库按测试文件共享）；先删引用方再删被引用方（FK NO ACTION）
    final db = await Db.instance();
    await db.delete('backgrounds');
    await db.delete('schedule_blocks');
    await db.delete('fixed_slots');
    await db.delete('plans');
  });

  group('plans', () {
    test('入库生成 uuid 与默认值，version 从 0 起', () async {
      final p = await repo.addPlan(Plan(title: '想去云南玩'));
      expect(p.id, matches(RegExp(r'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$')));
      expect(p.version, 0);
      expect(p.energyLevel, Plan.energyLight, reason: '快记零负担默认 light');
      expect(p.toolRequired, Plan.toolAnywhere);
      final back = await repo.planById(p.id!);
      expect(back!.title, '想去云南玩');
      expect(back.createdAt, isNotNull);
    });

    test('字段级 patch + 乐观锁 CAS：版本不符不写', () async {
      final p = await repo.addPlan(Plan(title: '有个考试'));
      expect(await repo.patchPlan(p.id!, {'spec': '2026-11-20 期末考试'}, expectedVersion: 0), isTrue);
      final after = await repo.planById(p.id!);
      expect(after!.spec, '2026-11-20 期末考试');
      expect(after.version, 1, reason: '成功写入 version +1');
      // 拿旧版本号再 patch → CAS 拒绝，内容不动
      expect(await repo.patchPlan(p.id!, {'spec': '被踩踏的写入'}, expectedVersion: 0), isFalse);
      expect((await repo.planById(p.id!))!.version, 1);
    });

    test('patch 未知列直接抛（防呆下沉）', () async {
      final p = await repo.addPlan(Plan(title: 'x'));
      expect(
        () => repo.patchPlan(p.id!, {'not_a_column': 1}),
        throwsArgumentError,
      );
    });

    test('open_items JSON round trip', () async {
      final p = await repo.addPlan(Plan(
        title: '全家爬山',
        openItems: const [OpenItem(question: '几点出发？'), OpenItem(question: '带谁？', answer: '全家')],
      ));
      final back = await repo.planById(p.id!);
      expect(back!.openItems.length, 2);
      expect(back.openItems[0].question, '几点出发？');
      expect(back.openItems[0].answer, isNull);
      expect(back.openItems[1].answer, '全家');
    });

    test('list 默认排除 archived，显式放开可查', () async {
      await repo.addPlan(Plan(title: '活跃'));
      final gone = await repo.addPlan(Plan(title: '收进抽屉'));
      await repo.patchPlan(gone.id!, {'archived': 1});
      expect((await repo.listPlans()).map((p) => p.title), ['活跃']);
      expect((await repo.listPlans(includeArchived: true)).length, 2);
    });

    test('外键兜底：被日程块引用的 plan 拒删，无引用可删', () async {
      final p = await repo.addPlan(Plan(title: '被引用'));
      final b = await repo.addBlock(ScheduleBlock(
        date: '2026-10-05',
        startMin: 540,
        endMin: 600,
        planId: p.id,
        source: ScheduleBlock.sourceHuman,
      ));
      expect(() => repo.deletePlan(p.id!), throwsA(anything),
          reason: '删除处置策略归命令层，数据层不静默级联');
      await repo.deleteBlock(b.id!);
      await repo.deletePlan(p.id!);
      expect(await repo.planById(p.id!), isNull);
    });
  });

  group('schedule_blocks', () {
    test('跨午夜块按开始日归属，不拆段（§4）', () async {
      await repo.addBlock(ScheduleBlock(
        date: '2026-10-05',
        startMin: 1410, // 23:30
        endMin: 450, // 次日 07:30
        source: ScheduleBlock.sourceHuman,
      ));
      expect((await repo.blocksOnDate('2026-10-05')).length, 1);
      expect((await repo.blocksOnDate('2026-10-06')).length, 0,
          reason: '归属规则只在数据层；次日回显归 BlockRenderer');
    });

    test('plan 存在性由外键兜底，坏引用直接拒', () async {
      await expectLater(
        repo.addBlock(ScheduleBlock(
          date: '2026-10-05',
          startMin: 540,
          endMin: 600,
          planId: 'no-such-plan',
          source: ScheduleBlock.sourceAi,
        )),
        throwsA(anything),
      );
    });
    test('patch CAS 与 version 自增', () async {
      final b = await repo.addBlock(ScheduleBlock(
        date: '2026-10-05',
        startMin: 540,
        endMin: 600,
        source: ScheduleBlock.sourceAi,
      ));
      expect(
        await repo.patchBlock(b.id!, {'status': ScheduleBlock.statusConfirmed}, expectedVersion: 0),
        isTrue,
      );
      final after = await repo.blockById(b.id!);
      expect(after!.status, ScheduleBlock.statusConfirmed);
      expect(after.version, 1);
      expect(await repo.patchBlock(b.id!, {'status': 'x'}, expectedVersion: 0), isFalse);
    });

    test('blocksOnDate 按开始时间排序', () async {
      await repo.addBlock(ScheduleBlock(
          date: '2026-10-05', startMin: 600, endMin: 660, source: ScheduleBlock.sourceHuman));
      await repo.addBlock(ScheduleBlock(
          date: '2026-10-05', startMin: 540, endMin: 560, source: ScheduleBlock.sourceHuman));
      final blocks = await repo.blocksOnDate('2026-10-05');
      expect([for (final b in blocks) b.startMin], [540, 600]);
    });
  });

  group('fixed_slots', () {
    test('批量原子替换', () async {
      await repo.replaceFixedSlots([
        FixedSlot(name: '旧占用', weekdays: const [1], startMin: 0, endMin: 60),
      ]);
      await repo.replaceFixedSlots([
        FixedSlot(name: '睡眠', weekdays: [1, 2, 3, 4, 5, 6, 7], startMin: 1410, endMin: 450),
        FixedSlot(name: '深度工作', weekdays: const [2], startMin: 540, endMin: 660),
      ]);
      final slots = await repo.listFixedSlots();
      expect(slots.length, 2);
      expect(slots.every((s) => s.id != null), isTrue, reason: '替换批补齐 id');
    });

    test('固定占用解析：当天开始 + 前一天溢出段（§4）', () async {
      await repo.replaceFixedSlots([
        // 睡眠 23:30–07:30 全周：跨午夜
        FixedSlot(name: '睡眠', weekdays: [1, 2, 3, 4, 5, 6, 7], startMin: 1410, endMin: 450),
        // 深度工作仅周二 09:00–11:00：不跨午夜
        FixedSlot(name: '深度工作', weekdays: const [DateTime.tuesday], startMin: 540, endMin: 660),
      ]);
      final tue = DateTime(2026, 10, 6);
      expect(tue.weekday, DateTime.tuesday, reason: '测试锚点自检');
      final tueResolved = await repo.fixedSlotsForDate(tue);
      expect(tueResolved.onDate.map((s) => s.name), containsAll(['睡眠', '深度工作']));
      expect(tueResolved.spillover.map((s) => s.name), ['睡眠'], reason: '溢出只来自周一夜间');

      final wed = DateTime(2026, 10, 7);
      final wedResolved = await repo.fixedSlotsForDate(wed);
      expect(wedResolved.onDate.map((s) => s.name), ['睡眠']);
      expect(wedResolved.spillover.map((s) => s.name), ['睡眠'],
          reason: '不跨午夜的周二深度工作不溢出到周三');

      // 月/年边界：2027-01-01（周五）的溢出段来自 2026-12-31 夜间
      final newYear = DateTime(2027, 1, 1);
      expect(newYear.weekday, DateTime.friday, reason: '测试锚点自检');
      final resolved = await repo.fixedSlotsForDate(newYear);
      expect(resolved.onDate.map((s) => s.name), ['睡眠']);
      expect(resolved.spillover.map((s) => s.name), ['睡眠'], reason: '跨年夜间睡眠段仍被解析');
    });
  });

  group('backgrounds', () {
    test('入库生成 uuid 与默认值：version 0/captured_by me/无日期窗=长期', () async {
      final p = await repo.addPlan(Plan(title: '云南七天'));
      final b = await repo.addBackground(Background(
        scope: Background.scopePlan,
        planId: p.id,
        content: '妈妈膝盖不好，少长台阶陡坡',
        rawSourceText: '妈妈膝盖不好，少走陡坡',
        tags: const ['#健康', '#体力'],
        source: Background.sourceAiDerived,
      ));
      expect(b.id, matches(RegExp(r'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$')));
      expect(b.version, 0);
      expect(b.capturedBy, 'me');
      expect(b.applicableDates, isNull);
      final back = await repo.backgroundById(b.id!);
      expect(back!.content, '妈妈膝盖不好，少长台阶陡坡');
      expect(back.rawSourceText, '妈妈膝盖不好，少走陡坡', reason: '原话永存');
      expect(back.tags, ['#健康', '#体力']);
      expect(back.source, Background.sourceAiDerived);
    });

    test('applicable_dates 写入口规范化：去重+排序、空数组归一 null', () async {
      final b = await repo.addBackground(Background(
        scope: Background.scopeGlobal,
        content: '连雨周，户外改室内',
        source: Background.sourceUser,
        applicableDates: const ['2026-10-09', '2026-10-08', '2026-10-09'],
      ));
      expect(b.applicableDates, ['2026-10-08', '2026-10-09'], reason: '集合判断需稳定序');
      expect(await repo.backgroundById(b.id!).then((v) => v!.applicableDates), ['2026-10-08', '2026-10-09']);
      final empty = await repo.addBackground(Background(
        scope: Background.scopeGlobal,
        content: '空窗陷阱态',
        source: Background.sourceUser,
        applicableDates: const [],
      ));
      expect(empty.applicableDates, isNull, reason: '空数组归一 null（语义=长期）');
      expect(await repo.backgroundById(empty.id!).then((v) => v!.applicableDates), isNull);
    });

    test('读侧防御：畸形元素过滤留合法项（2026-02-30 被回写比对挡下）', () async {
      final p = await repo.addPlan(Plan(title: 'x'));
      final b = await repo.addBackground(Background(
        scope: Background.scopePlan,
        planId: p.id,
        content: '脚扭伤',
        source: Background.sourceUser,
      ));
      // 直接改库模拟外部破坏——写入口有双检，正常路径产不出畸形
      final db = await Db.instance();
      await db.update(
        'backgrounds',
        {'applicable_dates': '["2026-02-30","2026-10-08","bogus"]'},
        where: 'id = ?',
        whereArgs: [b.id],
      );
      final back = await repo.backgroundById(b.id!);
      expect(back!.applicableDates, ['2026-10-08']);
    });

    test('patch 白名单：content 可改，raw/source/scope 契约字段改不到', () async {
      final b = await repo.addBackground(Background(
        scope: Background.scopeGlobal,
        content: '常住深圳',
        source: Background.sourceUser,
      ));
      expect(await repo.patchBackground(b.id!, {'content': '常住深圳，周末爱短途游'}, expectedVersion: 0), isTrue);
      final back = await repo.backgroundById(b.id!);
      expect(back!.content, '常住深圳，周末爱短途游');
      expect(back.version, 1);
      expect(() => repo.patchBackground(b.id!, {'raw_source_text': '篡改原话'}), throwsArgumentError);
      expect(() => repo.patchBackground(b.id!, {'source': Background.sourceAiDerived}), throwsArgumentError);
      expect(() => repo.patchBackground(b.id!, {'scope': Background.scopePlan}), throwsArgumentError);
    });

    test('乐观锁 CAS：版本不符不写', () async {
      final b = await repo.addBackground(Background(
        scope: Background.scopeGlobal,
        content: 'a',
        source: Background.sourceUser,
      ));
      expect(await repo.patchBackground(b.id!, {'content': 'b'}, expectedVersion: 0), isTrue);
      expect(await repo.patchBackground(b.id!, {'content': 'c'}, expectedVersion: 0), isFalse);
      expect((await repo.backgroundById(b.id!))!.content, 'b');
    });

    test('list 过滤：global/plan 两 scope 互不串', () async {
      final p = await repo.addPlan(Plan(title: 'x'));
      await repo.addBackground(Background(scope: Background.scopeGlobal, content: 'g1', source: Background.sourceUser));
      await repo.addBackground(Background(scope: Background.scopeGlobal, content: 'g2', source: Background.sourceUser));
      await repo.addBackground(Background(
        scope: Background.scopePlan,
        planId: p.id,
        content: 'p1',
        source: Background.sourceUser,
      ));
      expect((await repo.listBackgrounds(scope: Background.scopeGlobal)).length, 2);
      expect(
        (await repo.listBackgrounds(scope: Background.scopePlan, planId: p.id)).map((b) => b.content),
        ['p1'],
      );
    });

    test('外键护栏：plan 名下有背景时 deletePlan 硬失败（处置归命令层级联销毁）', () async {
      final p = await repo.addPlan(Plan(title: '有背景'));
      await repo.addBackground(Background(
        scope: Background.scopePlan,
        planId: p.id,
        content: 'x',
        source: Background.sourceUser,
      ));
      await expectLater(repo.deletePlan(p.id!), throwsA(anything));
      expect(await repo.planById(p.id!), isNotNull, reason: 'NO ACTION 护栏：未处置引用让删除硬失败');
    });

    test('deleteBackground 物理删除；不存在抛', () async {
      final b = await repo.addBackground(Background(
        scope: Background.scopeGlobal,
        content: 'x',
        source: Background.sourceUser,
      ));
      await repo.deleteBackground(b.id!);
      expect(await repo.backgroundById(b.id!), isNull);
      expect(() => repo.deleteBackground('nope'), throwsStateError);
    });

    test('导出 JSON 带全：backgrounds 面随 exportAll 落盘', () async {
      await repo.addBackground(Background(
        scope: Background.scopeGlobal,
        content: '海鲜严重过敏',
        tags: const ['#健康'],
        source: Background.sourceUser,
      ));
      final dump = await repo.exportAll();
      expect(dump['backgrounds'], isA<List<Map<String, Object?>>>());
      expect((dump['backgrounds'] as List).length, 1);
    });
  });
}
