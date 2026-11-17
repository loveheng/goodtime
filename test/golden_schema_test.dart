// P0 黄金向量基准执行器（Dart 侧）——SQLite Schema 快照（lib/data/db.dart）。
//
// 职责：把 test/golden/sqlite_schema_vectors.json 跑通并锁定期望值（不依赖 Kotlin）。
// - 向量 = 跨实现契约数据（Dart 与 Kotlin 双侧消费，Kotlin 对拍在 P2 接入）；
// - 本执行器逐 case 真跑库对拍真实 DDL/迁移链/约束，凡实现漂移即红；
// - 抽取规则为独立副本（与生成器解耦，同 golden_e2ee_test 先例）：两副本漂移
//   即红，规范化规则以向量集 rules 节为准。
// 对拍口径（rules.extraction_rule）：表名字典序、列按名字典序（物理 cid 序随迁移
// 路径变化，列序非契约）、索引按 name（列按 seqno）、外键按 id；dflt 原样。
// 向量语义与字段说明见 test/golden/README.md；向量文件为只读契约，实现漂移时
// 唯一出路是修实现，严禁改向量迁就实现（dev-loop 测试写保护）。
import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:path/path.dart' as p;
import 'package:sqflite_common_ffi/sqflite_ffi.dart';

import 'package:shiguang/data/db.dart';

Map<String, dynamic> _loadSet(String name) =>
    jsonDecode(File('test/golden/$name').readAsStringSync())
        as Map<String, dynamic>;

// ---- 抽取规则（独立副本；规则=向量集 rules.extraction_rule） -------------------

Future<Map<String, Object?>> _snapshotOf(Database db) async {
  final userVersion =
      (await db.rawQuery('PRAGMA user_version')).single['user_version'] as int;
  final tableRows = await db.rawQuery(
      "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'");
  final names = tableRows.map((r) => r['name'] as String).toList()..sort();
  final tables = <String, Object?>{};
  for (final t in names) {
    final cols = (await db.rawQuery('PRAGMA table_info($t)')).toList()
      ..sort((a, b) => (a['name'] as String).compareTo(b['name'] as String));
    final columns = [
      for (final c in cols)
        {
          'name': c['name'],
          'type': c['type'],
          'notnull': c['notnull'],
          'dflt': c['dflt_value'],
          'pk': c['pk'],
        },
    ];
    final idxRows = await db.rawQuery('PRAGMA index_list($t)');
    final sorted = [...idxRows]
      ..sort((a, b) => (a['name'] as String).compareTo(b['name'] as String));
    final indexes = <Map<String, Object?>>[];
    for (final i in sorted) {
      final info = await db.rawQuery('PRAGMA index_info(${i['name']})');
      indexes.add({
        'name': i['name'],
        'unique': i['unique'],
        'origin': i['origin'],
        'columns': [for (final c in info) c['name']],
      });
    }
    final fks = await db.rawQuery('PRAGMA foreign_key_list($t)');
    tables[t] = {
      'columns': columns,
      'indexes': indexes,
      'foreign_keys': [
        for (final f in fks)
          {
            'table': f['table'],
            'from': f['from'],
            'to': f['to'],
            'on_update': f['on_update'],
            'on_delete': f['on_delete'],
          },
      ],
    };
  }
  return {'version': userVersion, 'tables': tables};
}

Future<Database> _openFresh(String path) => openDatabase(
      path,
      version: 7,
      onCreate: (db, version) => Db.createAll(db),
    );

Future<Database> _openUpgraded(String path, List<String> legacyDdl) async {
  final legacy = await openDatabase(path, version: 4,
      onCreate: (db, version) async {
    for (final ddl in legacyDdl) {
      await db.execute(ddl);
    }
  });
  await legacy.close();
  return openDatabase(
    path,
    version: 7,
    onUpgrade: (db, oldVersion, newVersion) async {
      if (oldVersion < 5) await Db.migrateV5(db);
      if (oldVersion < 6) await Db.migrateV6(db);
      if (oldVersion < 7) await Db.migrateV7(db);
    },
  );
}

void main() {
  sqfliteFfiInit();
  databaseFactory = databaseFactoryFfi;

  final set = _loadSet('sqlite_schema_vectors.json');
  group('golden: sqlite-schema', () {
    for (final c in (set['cases'] as List).cast<Map<String, dynamic>>()) {
      final id = c['id'] as String;
      final input = c['input'] as Map<String, dynamic>;
      final expected = c['expect'] as Map<String, dynamic>;
      switch (c['op'] as String) {
        case 'schema_snapshot':
          test('schema_snapshot $id', () async {
            final tmp = await Directory.systemTemp.createTemp('golden_schema');
            try {
              final db = await _openFresh(p.join(tmp.path, 'fresh.db'));
              final actual = await _snapshotOf(db);
              await db.close();
              expect(actual, expected);
            } finally {
              await tmp.delete(recursive: true);
            }
          }, timeout: const Timeout(Duration(minutes: 2)));
          break;
        case 'upgrade_isomorphic':
          test('upgrade_isomorphic $id（v4 遗留库按向量 DDL 重建 → 公开链升级）',
              () async {
            final tmp = await Directory.systemTemp.createTemp('golden_schema');
            try {
              final dbPath = p.join(tmp.path, 'upgraded.db');
              final db = await _openUpgraded(
                  dbPath, (input['legacy_ddl'] as List).cast<String>());
              final actual = await _snapshotOf(db);
              await db.close();
              expect(actual, expected);
            } finally {
              await tmp.delete(recursive: true);
            }
          }, timeout: const Timeout(Duration(minutes: 2)));
          break;
        case 'constraint_reject':
          test('constraint_reject $id（seed 全成功 → probe 必拒）', () async {
            final tmp = await Directory.systemTemp.createTemp('golden_schema');
            try {
              final db = await _openFresh(p.join(tmp.path, 'fresh.db'));
              // 生产 onOpen 口径：foreign_keys=ON（悬空 FK 用例依赖；UNIQUE/PK 用例不受影响）。
              await db.execute('PRAGMA foreign_keys = ON');
              for (final s
                  in (input['seed'] as List).cast<Map<String, dynamic>>()) {
                await db.insert(s['table'] as String,
                    Map<String, Object?>.from(s['row'] as Map));
              }
              final probe = input['probe'] as Map<String, dynamic>;
              await expectLater(
                db.insert(probe['table'] as String,
                    Map<String, Object?>.from(probe['row'] as Map)),
                throwsA(anything), // 异常类非契约，只钉「必拒」
              );
              await db.close();
            } finally {
              await tmp.delete(recursive: true);
            }
          }, timeout: const Timeout(Duration(minutes: 2)));
          break;
        default:
          fail('未知 op: ${c['op']}（向量集与执行器失配，须同步更新）');
      }
    }
  });
}
