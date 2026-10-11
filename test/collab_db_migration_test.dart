import 'dart:convert';
import 'dart:io';

import 'package:path/path.dart' as p;
import 'package:sqflite_common_ffi/sqflite_ffi.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:shiguang/data/db.dart';

/// Phase 3.1 db v5 幂等迁移（collab-implementation.md §3.1）：
/// ① v4 存量升级——数据不丢、绑定搬家（artifacts.block_id → blocks.artifact_id）、
///    payload 投影清洗、block_id 出列，反转查询零 `no such column`；
/// ② 新装同构——createAll 终态与升级后列集逐一相同；
/// ③ 幂等重跑——migrateV5 二次执行零副作用；
/// ④ collab_* 六表约束——UNIQUE/PK 在位生效。
///
/// 用临时**文件库**（非 :memory:）：onUpgrade 只在「同文件以更高 version 重开」
/// 时触发，内存库每次打开都是新库，模拟不了存量升级。
void main() {
  sqfliteFfiInit();
  databaseFactory = databaseFactoryFfi;

  late Directory tmp;
  late String v4Path; // v4 存量库（升级被试）
  late String v5Path; // 新装库（同构对照组）

  setUp(() async {
    tmp = await Directory.systemTemp.createTemp('shiguang_v5_test');
    v4Path = p.join(tmp.path, 'legacy_v4.db');
    v5Path = p.join(tmp.path, 'fresh_v5.db');
  });

  tearDown(() async {
    await tmp.delete(recursive: true);
  });

  /// v4 schema **忠实复刻**（手抄旧版 _createAll：block_id 在位、无 bundle_id、
  /// 无 artifact_id、无 collab_*，列集与旧版逐一相同）——迁移被试必须对真实旧
  /// 形态收敛，极简替身会让「新装同构」对照失真。
  Future<Database> openLegacyV4(String path) => openDatabase(
        path,
        version: 4,
        onCreate: (db, version) async {
          await db.execute('''
            CREATE TABLE plans (
              id TEXT NOT NULL PRIMARY KEY,
              title TEXT NOT NULL,
              spec TEXT,
              notes TEXT,
              open_items TEXT,
              min_viable_action TEXT,
              energy_level TEXT NOT NULL DEFAULT 'light',
              tool_required TEXT NOT NULL DEFAULT 'anywhere',
              reward_spec TEXT,
              importance INTEGER NOT NULL DEFAULT 0,
              deadline TEXT,
              estimate INTEGER,
              parent_id TEXT REFERENCES plans(id),
              archived INTEGER NOT NULL DEFAULT 0,
              created_at INTEGER NOT NULL,
              updated_at INTEGER NOT NULL,
              version INTEGER NOT NULL DEFAULT 0
            )
          ''');
          await db.execute('''
            CREATE TABLE fixed_slots (
              id TEXT NOT NULL PRIMARY KEY,
              name TEXT NOT NULL,
              weekdays TEXT NOT NULL,
              start_min INTEGER NOT NULL,
              end_min INTEGER NOT NULL
            )
          ''');
          await db.execute('''
            CREATE TABLE schedule_blocks (
              id TEXT NOT NULL PRIMARY KEY,
              date TEXT NOT NULL,
              start_min INTEGER NOT NULL,
              end_min INTEGER NOT NULL,
              plan_id TEXT REFERENCES plans(id),
              label TEXT,
              source TEXT NOT NULL,
              pinned INTEGER NOT NULL DEFAULT 0,
              status TEXT NOT NULL DEFAULT 'proposed',
              postpone_count INTEGER NOT NULL DEFAULT 0,
              execution_quality TEXT NOT NULL DEFAULT 'full',
              is_day_spark INTEGER NOT NULL DEFAULT 0,
              is_celebration INTEGER NOT NULL DEFAULT 0,
              created_at INTEGER NOT NULL,
              updated_at INTEGER NOT NULL,
              version INTEGER NOT NULL DEFAULT 0
            )
          ''');
          await db.execute('''
            CREATE TABLE backgrounds (
              id TEXT NOT NULL PRIMARY KEY,
              scope TEXT NOT NULL,
              plan_id TEXT REFERENCES plans(id),
              content TEXT NOT NULL,
              raw_source_text TEXT,
              tags TEXT,
              applicable_dates TEXT,
              source TEXT NOT NULL,
              captured_by TEXT NOT NULL DEFAULT 'me',
              created_at INTEGER NOT NULL,
              updated_at INTEGER NOT NULL,
              version INTEGER NOT NULL DEFAULT 0
            )
          ''');
          await db.execute('''
            CREATE TABLE artifacts (
              id TEXT NOT NULL PRIMARY KEY,
              category TEXT NOT NULL,
              source_kind TEXT NOT NULL,
              state TEXT NOT NULL,
              origin TEXT NOT NULL,
              title TEXT NOT NULL,
              badge TEXT,
              payload TEXT NOT NULL,
              attachments TEXT NOT NULL,
              captured_by TEXT NOT NULL DEFAULT 'me',
              plan_id TEXT REFERENCES plans(id),
              block_id TEXT,
              created_at INTEGER NOT NULL,
              updated_at INTEGER NOT NULL,
              version INTEGER NOT NULL DEFAULT 0
            )
          ''');
          await db.execute('''
            CREATE TABLE app_settings (
              key TEXT PRIMARY KEY,
              value TEXT NOT NULL,
              updated_at INTEGER NOT NULL
            )
          ''');
        },
      );

  /// 播种 v4 存量：有绑定（升格流）、无绑定、悬空绑定（块已日切删）、
  /// payload 带 block_id 投影、背景、设置各若干。
  Future<void> seedLegacy(Database db) async {
    final now = DateTime.now().millisecondsSinceEpoch;
    await db.insert('plans', {
      'id': 'p1', 'title': '周末出行', 'created_at': now, 'updated_at': now,
    });
    await db.insert('schedule_blocks', {
      'id': 'b1', 'date': '2026-10-10', 'start_min': 480, 'end_min': 540,
      'plan_id': 'p1', 'source': 'ai', 'pinned': 1,
      'created_at': now, 'updated_at': now,
    });
    // a1：升格绑定块 b1，payload 带 block_id 投影（待清洗）
    await db.insert('artifacts', {
      'id': 'a1', 'category': 'transit', 'source_kind': 'booking',
      'state': 'structured', 'origin': 'ai', 'title': '去程高铁',
      'payload': jsonEncode({
        'v': 1, 'block_id': 'b1', 'plan_id': 'p1', 'raw_text': 'G102 05车12F',
      }),
      'attachments': '[]', 'plan_id': 'p1', 'block_id': 'b1',
      'created_at': now, 'updated_at': now,
    });
    // a2：块已被日切物理删除（悬空 block_id）——绑定应静默丢弃，凭证保留
    await db.insert('artifacts', {
      'id': 'a2', 'category': 'hotel', 'source_kind': 'booking',
      'state': 'raw', 'origin': 'shared', 'title': '酒店确认单',
      'payload': jsonEncode({'v': 1, 'block_id': 'ghost', 'raw_text': '已订'}),
      'attachments': '[]', 'block_id': 'ghost',
      'created_at': now, 'updated_at': now,
    });
    // a3：无绑定、payload 干净——原样保留
    await db.insert('artifacts', {
      'id': 'a3', 'category': 'verbal', 'source_kind': 'verbal',
      'state': 'raw', 'origin': 'quicknote', 'title': '口信',
      'payload': jsonEncode({'v': 1, 'raw_text': '记得带伞'}),
      'attachments': '[]',
      'created_at': now, 'updated_at': now,
    });
    await db.insert('backgrounds', {
      'id': 'bg1', 'scope': 'plan', 'plan_id': 'p1',
      'content': '三人同行', 'source': 'user',
      'created_at': now, 'updated_at': now,
    });
    await db.insert('app_settings', {'key': 'k', 'value': 'v', 'updated_at': now});
  }

  Future<Database> openUpgradedV5(String path) => openDatabase(
        path,
        version: 5,
        onUpgrade: (db, oldVersion, newVersion) async {
          await Db.migrateV5(db);
        },
      );

  Future<Set<String>> columns(Database db, String table) async {
    final rows = await db.rawQuery('PRAGMA table_info($table)');
    return {for (final r in rows) r['name'] as String};
  }

  test('v4 存量升级：绑定搬家 + payload 清洗 + block_id 出列，反转查询零 no such column',
      () async {
    final legacy = await openLegacyV4(v4Path);
    await seedLegacy(legacy);
    await legacy.close();

    final db = await openUpgradedV5(v4Path);

    // 数据一行不丢
    expect((await db.query('plans')).length, 1);
    expect((await db.query('schedule_blocks')).length, 1);
    expect((await db.query('backgrounds')).length, 1);
    expect((await db.query('app_settings')).length, 1);
    final arts = await db.query('artifacts', orderBy: 'id');
    expect(arts.map((a) => a['id']), ['a1', 'a2', 'a3']);

    // ① 绑定搬家：块侧持有，凭证侧出列
    final b1 = (await db.query('schedule_blocks', where: "id = 'b1'")).single;
    expect(b1['artifact_id'], 'a1');
    expect(await columns(db, 'artifacts'), isNot(contains('block_id')));
    expect(await columns(db, 'artifacts'), contains('bundle_id'));
    expect(await columns(db, 'schedule_blocks'), contains('artifact_id'));

    // ② 悬空绑定静默丢弃：ghost 无块可绑，凭证本体完好
    final dangling = await db.rawQuery(
      'SELECT COUNT(*) c FROM schedule_blocks WHERE artifact_id = ?', ['a2']);
    expect((dangling.single['c'] as int), 0);
    expect(arts.firstWhere((a) => a['id'] == 'a2')['title'], '酒店确认单');

    // ③ payload 投影清洗：block_id 键出列，其余字段原样
    final a1Payload =
        jsonDecode(arts.firstWhere((a) => a['id'] == 'a1')['payload'] as String)
            as Map<String, Object?>;
    expect(a1Payload.containsKey('block_id'), isFalse);
    expect(a1Payload['raw_text'], 'G102 05车12F');
    expect(a1Payload['plan_id'], 'p1');
    expect(a1Payload['v'], 1);
    final a3Payload =
        jsonDecode(arts.firstWhere((a) => a['id'] == 'a3')['payload'] as String)
            as Map<String, Object?>;
    expect(a3Payload['raw_text'], '记得带伞');

    // ④ 反转查询（artifactForBlock 的同构 SQL）：零 no such column
    final joined = await db.rawQuery(
      'SELECT a.* FROM artifacts a '
      'JOIN schedule_blocks b ON b.artifact_id = a.id '
      "WHERE b.id = 'b1' AND a.state != 'voided' LIMIT 1");
    expect(joined.single['id'], 'a1');

    await db.close();
  });

  test('新装同构：createAll 终态列集与升级后逐一相同，source 收 \'collab\'，六表在位',
      () async {
    final fresh = await openDatabase(
      v5Path,
      version: 5,
      onCreate: (db, version) => Db.createAll(db),
    );
    final legacy = await openLegacyV4(v4Path);
    await seedLegacy(legacy);
    await legacy.close();
    final upgraded = await openUpgradedV5(v4Path);

    // 列集同构（单机两表 + artifacts）
    for (final t in ['plans', 'schedule_blocks', 'artifacts', 'backgrounds']) {
      expect(await columns(fresh, t), await columns(upgraded, t),
          reason: '$t 新装与升级列集不同构');
    }

    // source 扩 'collab'：无 CHECK 约束，直接可写
    final now = DateTime.now().millisecondsSinceEpoch;
    await fresh.insert('schedule_blocks', {
      'id': 'cb1', 'date': '2026-10-11', 'start_min': 600, 'end_min': 660,
      'source': 'collab', 'created_at': now, 'updated_at': now,
    });
    expect(
      (await fresh.query('schedule_blocks', where: "id = 'cb1'")).single['source'],
      'collab',
    );

    // 六张 collab 表在位
    for (final t in [
      'collab_bundles', 'collab_steps', 'collab_events', 'collab_key_ring',
      'local_outbox_events', 'collab_block_refs',
    ]) {
      expect(await columns(fresh, t), isNotEmpty, reason: '$t 缺失');
    }

    await fresh.close();
    await upgraded.close();
  });

  test('幂等重跑：migrateV5 二次执行零副作用', () async {
    final legacy = await openLegacyV4(v4Path);
    await seedLegacy(legacy);
    await legacy.close();
    final db = await openUpgradedV5(v4Path);
    final before = await db.query('artifacts', orderBy: 'id');

    await Db.migrateV5(db); // 重跑

    expect(await db.query('artifacts', orderBy: 'id'), before);
    expect(await columns(db, 'artifacts'), isNot(contains('block_id')));
    final b1 = (await db.query('schedule_blocks', where: "id = 'b1'")).single;
    expect(b1['artifact_id'], 'a1');
    await db.close();
  });

  test('collab_* 约束：事件 (bundle_id,seq_id) 唯一、密钥环同版本幂等主键、refs 主键',
      () async {
    final db = await openDatabase(
      v5Path,
      version: 5,
      onCreate: (db, version) => Db.createAll(db),
    );
    final now = DateTime.now().millisecondsSinceEpoch;
    await db.insert('collab_bundles', {
      'id': 'bu1', 'proposal_type': 'CONSENSUS', 'initiator_id': 'u1',
      'participants': '["u1","u2"]', 'expires_at': now,
      'created_at': now, 'updated_at': now,
    });
    await db.insert('collab_events', {
      'bundle_id': 'bu1', 'seq_id': 1, 'action_type': 'PROPOSE',
      'sender_id': 'u1', 'cipher': 'Y2lwaGVy', 'client_msg_id': 'm1',
      'created_at': now,
    });
    // UNIQUE(bundle_id, seq_id) 拦重复 seq
    expect(
      () => db.insert('collab_events', {
        'bundle_id': 'bu1', 'seq_id': 1, 'action_type': 'VOTE',
        'sender_id': 'u2', 'cipher': 'eA==', 'client_msg_id': 'm2',
        'created_at': now,
      }),
      throwsA(anything),
    );
    await db.insert('collab_key_ring', {
      'bundle_id': 'bu1', 'key_ver': 1, 'envelope': 'a2VuYg==', 'created_at': now,
    });
    // PK (bundle_id, key_ver) 拦同版本重复
    expect(
      () => db.insert('collab_key_ring', {
        'bundle_id': 'bu1', 'key_ver': 1, 'envelope': 'Yg==', 'created_at': now,
      }),
      throwsA(anything),
    );
    await db.insert('collab_block_refs', {
      'block_id': 'b1', 'bundle_id': 'bu1', 'step_id': 's1', 'created_at': now,
    });
    // PK block_id 拦重复映射
    expect(
      () => db.insert('collab_block_refs', {
        'block_id': 'b1', 'bundle_id': 'bu1', 'step_id': 's2', 'created_at': now,
      }),
      throwsA(anything),
    );
    // 反查索引可用（§5.5 下沉绑定的 SQL 形态）
    final hit = await db.rawQuery(
      "SELECT block_id FROM collab_block_refs WHERE bundle_id = 'bu1' AND step_id = 's1'");
    expect(hit.single['block_id'], 'b1');
    await db.close();
  });
}
