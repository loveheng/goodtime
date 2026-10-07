import 'dart:async';
import 'dart:math';

import 'package:flutter/foundation.dart';
import 'package:sqflite/sqflite.dart';

import '../models/background.dart';
import '../models/fixed_slot.dart';
import '../models/plan.dart';
import '../models/schedule_block.dart';
import '../util/schedule_day.dart';
import 'db.dart';

/// 数据仓库：UI 与 MCP 工具共用的唯一入口（拾贝 Repository 模式照抄，§12 领料）。
/// 写复合操作统一走 [synchronized] FIFO 锁；更新走字段级 patch + 乐观锁 version
/// （并发契约 §11）。业务校验（重叠/作息边界/plan 存在性/填充率/呼吸律）归命令层
/// （M1 后续段落），仓库只管台账保真；plan 存在性与引用完整性由 DB 外键兜底。
class Repository extends ChangeNotifier {
  Database? _db;

  Future<Database> _database() async => _db ??= await Db.instance();

  int _revision = 0;

  /// 写入代次：每次 notify 自增，监听方据此感知「有新写入需重查」
  /// （跨 zone 场景下 FutureBuilder 的快照交付不可靠，代次轮询是可靠信号）。
  int get revision => _revision;

  @override
  void notifyListeners() {
    _revision++;
    super.notifyListeners();
  }

  /// 写路径 FIFO 队尾（串行锁的实现载体，见 [synchronized]）。
  Future<void> _tail = Future<void>.value();

  /// **写路径串行锁（FIFO）**：把「读 → 校验 → 写」复合操作压平成一列。
  ///
  /// sqflite 只保证单条 SQL 的原子性，保证不了动作层跨 await 的复合操作
  /// （TOCTOU 竞态）；大模型经 MCP 可在极短时间内甩来多个并发请求。
  /// 零依赖实现：Dart 单线程 event loop + Future 链（模式照抄拾贝）。
  /// **使用边界**：只包写路径；读查询不得入队；锁内不得做重活。
  Future<T> synchronized<T>(Future<T> Function() action) {
    final previous = _tail;
    final next = Completer<void>();
    _tail = next.future;
    return previous.catchError((Object _) {/* 前序失败不阻断后续排队 */}).then((_) async {
      try {
        return await action();
      } finally {
        next.complete();
      }
    });
  }

  /// 批量事务：多条写「全成功才提交，中途出错整体回滚」（propose 整单原子写 §6）。
  /// 事务内的读写必须走传入的 [txn]，否则读不到同一事务里上一条的未提交改动。
  Future<T> transaction<T>(Future<T> Function(Transaction txn) action) async {
    final db = await _database();
    final result = await db.transaction((txn) => action(txn));
    notifyListeners();
    return result;
  }

  final Random _random = Random.secure();

  /// RFC 4122 v4 uuid（对外稳定标识，MCP 工具引用）。事务内直接建行时也用它。
  String newId() {
    final b = List<int>.generate(16, (_) => _random.nextInt(256));
    b[6] = (b[6] & 0x0f) | 0x40;
    b[8] = (b[8] & 0x3f) | 0x80;
    final h = [for (final v in b) v.toRadixString(16).padLeft(2, '0')].join();
    return '${h.substring(0, 8)}-${h.substring(8, 12)}-'
        '${h.substring(12, 16)}-${h.substring(16, 20)}-${h.substring(20)}';
  }

  /// patch 白名单过滤：未知列直接抛（防呆下沉，杜绝注入面）。
  Map<String, Object?> _whitelist(Map<String, Object?> patch, Set<String> columns) {
    final out = <String, Object?>{};
    for (final e in patch.entries) {
      if (!columns.contains(e.key)) {
        throw ArgumentError('未知列: ${e.key}（patch 只收白名单字段）');
      }
      out[e.key] = e.value;
    }
    return out;
  }

  // ---------- plans ----------

  static const _planPatchColumns = {
    'title', 'spec', 'notes', 'open_items', 'min_viable_action', //
    'energy_level', 'tool_required', 'reward_spec', 'importance', //
    'deadline', 'estimate', 'parent_id', 'archived', //
  };

  Future<Plan> addPlan(Plan plan) async {
    final now = DateTime.now().millisecondsSinceEpoch;
    final p = Plan(
      id: plan.id ?? newId(),
      title: plan.title,
      spec: plan.spec,
      notes: plan.notes,
      openItems: plan.openItems,
      minViableAction: plan.minViableAction,
      energyLevel: plan.energyLevel,
      toolRequired: plan.toolRequired,
      rewardSpec: plan.rewardSpec,
      importance: plan.importance,
      deadline: plan.deadline,
      estimate: plan.estimate,
      parentId: plan.parentId,
      archived: plan.archived,
      createdAt: plan.createdAt ?? now,
      updatedAt: now,
      version: 0,
    );
    final db = await _database();
    await db.insert('plans', p.toMap());
    notifyListeners();
    return p;
  }

  Future<Plan?> planById(String id) async {
    final db = await _database();
    final rows = await db.query('plans', where: 'id = ?', whereArgs: [id], limit: 1);
    return rows.isEmpty ? null : Plan.fromMap(rows.first);
  }

  /// 清单列表。默认排除 archived（list_plans 默认过滤口径 §6：归档/冷数据不拉）。
  Future<List<Plan>> listPlans({bool includeArchived = false, String? parentId}) async {
    final db = await _database();
    final where = <String>[];
    final args = <Object?>[];
    if (!includeArchived) where.add('archived = 0');
    if (parentId != null) {
      where.add('parent_id = ?');
      args.add(parentId);
    }
    final rows = await db.query(
      'plans',
      where: where.isEmpty ? null : where.join(' AND '),
      whereArgs: where.isEmpty ? null : args,
      orderBy: 'created_at DESC',
    );
    return [for (final r in rows) Plan.fromMap(r)];
  }

  /// 字段级 patch + 乐观锁（§11 并发契约：只发要改的字段）。
  /// [expectedVersion] 非空时做 CAS——版本不符不写并返回 false
  /// （命令层转 version_conflict 并回最新快照，§6 返回契约）。
  /// 成功写入 version +1、updated_at 刷新；空补丁幂等返回 true。
  Future<bool> patchPlan(String id, Map<String, Object?> patch, {int? expectedVersion}) async {
    final values = _whitelist(patch, _planPatchColumns);
    if (values.isEmpty) return true;
    final db = await _database();
    final set = [for (final k in values.keys) '$k = ?', 'version = version + 1', 'updated_at = ?'].join(', ');
    final where = expectedVersion == null ? 'id = ?' : 'id = ? AND version = ?';
    final args = <Object?>[
      ...values.values,
      DateTime.now().millisecondsSinceEpoch,
      id,
      ?expectedVersion,
    ];
    final n = await db.rawUpdate('UPDATE plans SET $set WHERE $where', args);
    if (n > 0) notifyListeners();
    return n > 0;
  }

  /// 物理删除。子树（plans.parent_id）与肉身（blocks.plan_id）受外键 NO ACTION
  /// 保护：有引用时抛出——删除前处置策略归命令层显式处理，数据层不静默级联。
  Future<void> deletePlan(String id) async {
    final db = await _database();
    final n = await db.delete('plans', where: 'id = ?', whereArgs: [id]);
    if (n == 0) throw StateError('plan 不存在: $id');
    notifyListeners();
  }

  // ---------- backgrounds ----------

  /// patch 白名单（手编契约 §3）：raw_source_text 永不变、source 出生不可变、
  /// scope/plan_id 建档定死不迁移——契约字段不在白名单内，patch 通道根本改不到。
  static const _backgroundPatchColumns = {'content', 'tags', 'applicable_dates'};

  Future<Background> addBackground(Background bg) async {
    final now = DateTime.now().millisecondsSinceEpoch;
    final b = Background(
      id: bg.id ?? newId(),
      scope: bg.scope,
      planId: bg.planId,
      content: bg.content,
      rawSourceText: bg.rawSourceText,
      tags: bg.tags,
      applicableDates: bg.applicableDates,
      source: bg.source,
      capturedBy: bg.capturedBy,
      createdAt: bg.createdAt ?? now,
      updatedAt: now,
      version: 0,
    );
    final db = await _database();
    await db.insert('backgrounds', b.toMap());
    notifyListeners();
    return b;
  }

  Future<Background?> backgroundById(String id) async {
    final db = await _database();
    final rows = await db.query('backgrounds', where: 'id = ?', whereArgs: [id], limit: 1);
    return rows.isEmpty ? null : Background.fromMap(rows.first);
  }

  /// 背景列表：scope/plan 过滤（global=常驻画像、plan=行程背景），created_at DESC。
  /// 祖先链继承与 applicable_dates 日期窗集合判断归 queries 装配层（§4 双通道装配），
  /// 仓库只保台账行原样。
  Future<List<Background>> listBackgrounds({String? scope, String? planId}) async {
    final db = await _database();
    final where = <String>[];
    final args = <Object?>[];
    if (scope != null) {
      where.add('scope = ?');
      args.add(scope);
    }
    if (planId != null) {
      where.add('plan_id = ?');
      args.add(planId);
    }
    final rows = await db.query(
      'backgrounds',
      where: where.isEmpty ? null : where.join(' AND '),
      whereArgs: where.isEmpty ? null : args,
      orderBy: 'created_at DESC',
    );
    return [for (final r in rows) Background.fromMap(r)];
  }

  /// 字段级 patch + 乐观锁，语义同 [patchPlan]（§2 度量口径：预算终态校验归命令层）。
  Future<bool> patchBackground(String id, Map<String, Object?> patch, {int? expectedVersion}) async {
    final values = _whitelist(patch, _backgroundPatchColumns);
    if (values.isEmpty) return true;
    final db = await _database();
    final set = [for (final k in values.keys) '$k = ?', 'version = version + 1', 'updated_at = ?'].join(', ');
    final where = expectedVersion == null ? 'id = ?' : 'id = ? AND version = ?';
    final args = <Object?>[
      ...values.values,
      DateTime.now().millisecondsSinceEpoch,
      id,
      ?expectedVersion,
    ];
    final n = await db.rawUpdate('UPDATE backgrounds SET $set WHERE $where', args);
    if (n > 0) notifyListeners();
    return n > 0;
  }

  /// 物理删除（§6 单设备真删，无墓碑；命令层 AI 提炼回显后的删除通道同此口）。
  Future<void> deleteBackground(String id) async {
    final db = await _database();
    final n = await db.delete('backgrounds', where: 'id = ?', whereArgs: [id]);
    if (n == 0) throw StateError('background 不存在: $id');
    notifyListeners();
  }

  // ---------- fixed_slots ----------

  Future<List<FixedSlot>> listFixedSlots() async {
    final db = await _database();
    final rows = await db.query('fixed_slots', orderBy: 'start_min');
    return [for (final r in rows) FixedSlot.fromMap(r)];
  }

  /// 批量原子替换（update_fixed_slots 整单校验模式 §6）：整批成功或整批不动。
  Future<void> replaceFixedSlots(List<FixedSlot> slots) async {
    final db = await _database();
    final rows = [
      for (final s in slots)
        FixedSlot(
          id: s.id ?? newId(),
          name: s.name,
          weekdays: s.weekdays,
          startMin: s.startMin,
          endMin: s.endMin,
        ).toMap(),
    ];
    await db.transaction((txn) async {
      await txn.delete('fixed_slots');
      for (final r in rows) {
        await txn.insert('fixed_slots', r);
      }
    });
    notifyListeners();
  }

  /// 某日历日的固定占用解析（§4：校验查「当天开始的固定占用 + 前一天溢出段」）。
  /// onDate = 开始日=当天的占用（按 weekday 集合判定）；
  /// spillover = 前一天开始、跨午夜溢出到当天的段（如前夜睡眠 23:30–07:30）。
  /// 月/年边界由 [addDays] 日历算术保证。
  Future<({List<FixedSlot> onDate, List<FixedSlot> spillover})> fixedSlotsForDate(
      DateTime date) async {
    final slots = await listFixedSlots();
    final prev = addDays(date, -1);
    return (
      onDate: [for (final s in slots) if (s.coversWeekday(date.weekday)) s],
      spillover: [
        for (final s in slots)
          if (crossesMidnight(s.startMin, s.endMin) && s.coversWeekday(prev.weekday)) s,
      ],
    );
  }

  // ---------- app_settings ----------

  Future<Map<String, String>> settingsAll() async {
    final db = await _database();
    final rows = await db.query('app_settings');
    return {for (final r in rows) r['key'] as String: r['value'] as String};
  }

  Future<String?> settingsGet(String key) async {
    final db = await _database();
    final rows = await db.query('app_settings', where: 'key = ?', whereArgs: [key], limit: 1);
    return rows.isEmpty ? null : rows.first['value'] as String;
  }

  /// 批量 upsert（update_settings 走此口；合法键与类型校验在命令层）。
  Future<void> settingsSet(Map<String, String> values) async {
    if (values.isEmpty) return;
    final db = await _database();
    final now = DateTime.now().millisecondsSinceEpoch;
    await db.transaction((txn) async {
      for (final e in values.entries) {
        await txn.insert(
          'app_settings',
          {'key': e.key, 'value': e.value, 'updated_at': now},
          conflictAlgorithm: ConflictAlgorithm.replace,
        );
      }
    });
    notifyListeners();
  }

  /// 删除设置键（update_settings 传 null 清空对应项）。
  Future<void> settingsClear(String key) async {
    final db = await _database();
    await db.delete('app_settings', where: 'key = ?', whereArgs: [key]);
    notifyListeners();
  }

  /// 批量删键（单事务+单 notify——快记草稿三键整组清除走此口，避免三连重建）。
  Future<void> settingsClearAll(List<String> keys) async {
    if (keys.isEmpty) return;
    final db = await _database();
    await db.transaction((txn) async {
      for (final k in keys) {
        await txn.delete('app_settings', where: 'key = ?', whereArgs: [k]);
      }
    });
    notifyListeners();
  }

  // ---------- schedule_blocks ----------

  static const _blockPatchColumns = {
    'date', 'start_min', 'end_min', 'plan_id', 'label', 'source', 'pinned', //
    'status', 'postpone_count', 'execution_quality', 'is_day_spark', 'is_celebration', //
  };

  Future<ScheduleBlock> addBlock(ScheduleBlock block) async {
    final now = DateTime.now().millisecondsSinceEpoch;
    final b = ScheduleBlock(
      id: block.id ?? newId(),
      date: block.date,
      startMin: block.startMin,
      endMin: block.endMin,
      planId: block.planId,
      label: block.label,
      source: block.source,
      pinned: block.pinned,
      status: block.status,
      postponeCount: block.postponeCount,
      executionQuality: block.executionQuality,
      isDaySpark: block.isDaySpark,
      isCelebration: block.isCelebration,
      createdAt: now,
      updatedAt: now,
      version: 0,
    );
    final db = await _database();
    await db.insert('schedule_blocks', b.toMap());
    notifyListeners();
    return b;
  }

  Future<ScheduleBlock?> blockById(String id) async {
    final db = await _database();
    final rows = await db.query('schedule_blocks', where: 'id = ?', whereArgs: [id], limit: 1);
    return rows.isEmpty ? null : ScheduleBlock.fromMap(rows.first);
  }

  /// 某开始日的全部块（按 start_min 排序）。跨午夜块按开始日归属、不拆段（§4）：
  /// 「明天读今天」的显示层溢出回显归 BlockRenderer，查询恒按 date 单列。
  Future<List<ScheduleBlock>> blocksOnDate(String isoDate) async {
    final db = await _database();
    final rows = await db.query(
      'schedule_blocks',
      where: 'date = ?',
      whereArgs: [isoDate],
      orderBy: 'start_min',
    );
    return [for (final r in rows) ScheduleBlock.fromMap(r)];
  }

  /// 日期范围（闭区间）内的块，按 date + start_min 排序（派生视图走单条 SQL，
  /// 复用 idx_blocks_date 索引）。
  Future<List<ScheduleBlock>> blocksInRange(String fromIso, String toIso) async {
    final db = await _database();
    final rows = await db.query(
      'schedule_blocks',
      where: 'date >= ? AND date <= ?',
      whereArgs: [fromIso, toIso],
      orderBy: 'date, start_min',
    );
    return [for (final r in rows) ScheduleBlock.fromMap(r)];
  }

  /// 字段级 patch + 乐观锁，语义同 [patchPlan]（确认三键/顺延/降级等命令共用）。
  Future<bool> patchBlock(String id, Map<String, Object?> patch, {int? expectedVersion}) async {
    final values = _whitelist(patch, _blockPatchColumns);
    if (values.isEmpty) return true;
    final db = await _database();
    final set = [for (final k in values.keys) '$k = ?', 'version = version + 1', 'updated_at = ?'].join(', ');
    final where = expectedVersion == null ? 'id = ?' : 'id = ? AND version = ?';
    final args = <Object?>[
      ...values.values,
      DateTime.now().millisecondsSinceEpoch,
      id,
      ?expectedVersion,
    ];
    final n = await db.rawUpdate('UPDATE schedule_blocks SET $set WHERE $where', args);
    if (n > 0) notifyListeners();
    return n > 0;
  }

  /// 全量导出（§11 数据出口「导出 JSON 全量」）：五个存储面一次 JSON 化，
  /// 备份/迁移用途；设置页一键触发。
  Future<Map<String, Object?>> exportAll() async {
    final db = await _database();
    return {
      'exported_at': DateTime.now().toIso8601String(),
      'plans': await db.query('plans'),
      'fixed_slots': await db.query('fixed_slots'),
      'schedule_blocks': await db.query('schedule_blocks'),
      'backgrounds': await db.query('backgrounds'),
      'app_settings': await db.query('app_settings'),
    };
  }

  /// 物理删除（仅 proposed 块的否决蒸发走命令层守卫后调用；其余状态迁移走 patch）。
  Future<void> deleteBlock(String id) async {
    final db = await _database();
    final n = await db.delete('schedule_blocks', where: 'id = ?', whereArgs: [id]);
    if (n == 0) throw StateError('block 不存在: $id');
    notifyListeners();
  }
}
