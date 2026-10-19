/// 存储抽象：把 [Repository] 实际用到的 sqflite `Database` 子集收敛为纯 Dart 接口，
/// 使内核（lib/mcp、lib/action、lib/data/repository）不依赖任何平台包
/// （sqflite / dart:io）——宿主可注入自有实现（轴 B 纯 Dart 化；
/// goodhub-local-plugin G2·②）。
///
/// 具体 sqflite 后端见 [SqfliteStorage]（lib/data/db.dart）。
abstract class ScheduleStorage {
  /// 插入一行；[replace]=true 等价 sqflite 的 `ConflictAlgorithm.replace`。
  Future<int> insert(String table, Map<String, Object?> values,
      {bool replace = false});

  /// 查询；仅暴露 [Repository] 实际用到的子集。
  Future<List<Map<String, Object?>>> query(String table,
      {List<String>? columns,
      String? where,
      List<Object?>? whereArgs,
      String? orderBy,
      int? limit});

  /// 原生 SQL 更新（乐观锁 CAS 等用）。
  Future<int> rawUpdate(String sql, List<Object?> args);

  /// 原生 SQL 查询（JOIN 等抽象方法覆盖不了的读形；反转关联反查 §9.5-6 用）。
  Future<List<Map<String, Object?>>> rawQuery(String sql, [List<Object?>? args]);

  /// 删除。
  Future<int> delete(String table, {String? where, List<Object?>? whereArgs});

  /// 事务：回调内拿到 [StorageTxn]（仅 insert/delete，与 sqflite 事务内句柄对齐）。
  Future<T> transaction<T>(Future<T> Function(StorageTxn txn) action);
}

/// 事务内句柄（[ScheduleStorage.transaction] 回调参数）。
/// 命令层复合写需要 query/read-your-writes + update/rawUpdate，
/// 与 [ScheduleStorage] 的子集对齐。
abstract class StorageTxn {
  Future<int> insert(String table, Map<String, Object?> values,
      {bool replace = false});

  Future<int> delete(String table, {String? where, List<Object?>? whereArgs});

  Future<List<Map<String, Object?>>> query(String table,
      {List<String>? columns,
      String? where,
      List<Object?>? whereArgs,
      String? orderBy,
      int? limit});

  Future<int> rawUpdate(String sql, List<Object?> args);

  /// 字段级更新（乐观锁 CAS 用）。
  Future<int> update(String table, Map<String, Object?> values,
      {String? where, List<Object?>? whereArgs});
}

/// 默认存储工厂：由 sqflite 实现侧（lib/data/db.dart）在模块加载时注册，
/// 使 `Repository()` 在无显式注入时仍可用，同时内核不依赖 sqflite。
/// 宿主（如 Binder 嵌入）可调用 [registerStorageFactory] 覆盖为自有后端。
typedef StorageFactory = Future<ScheduleStorage> Function();

StorageFactory? _defaultFactory;

void registerStorageFactory(StorageFactory factory) => _defaultFactory = factory;

Future<ScheduleStorage> storageFactory() {
  final f = _defaultFactory;
  if (f == null) {
    throw StateError(
        '未注册存储工厂：宿主需在启动时 registerStorageFactory（见 lib/data/db.dart）'
        '，或显式给 Repository 传入 ScheduleStorage');
  }
  return f();
}
