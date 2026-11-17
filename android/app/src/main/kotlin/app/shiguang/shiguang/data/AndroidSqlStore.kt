package app.shiguang.shiguang.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import app.shiguang.core.model.ShiguangSchema
import app.shiguang.core.model.SqlRow
import app.shiguang.core.model.SqlStore
import app.shiguang.core.model.SqlTxn
import app.shiguang.core.model.toSqlValues
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Android SQLite 驱动（SqlStore 的平台实现）：框架 SQLiteOpenHelper 承载
 * ShiguangSchema（DDL/迁移收口在 :core:model 契约层，Schema 严禁变更）。
 *
 * 连接口径（R1 双写者红线，busy_timeout 严禁下调）：
 * - onConfigure：显式 `journal_mode=WAL` + `busy_timeout=5000`（单连接制——真机仪器
 *   测试实证框架 WAL flag 的池化读连接不过回调、回落默认 2500，2026-11-15 改单连接；
 *   对齐 sqflite 每引擎单连接形态 = R3 双写者实测受测形状）；
 * - onOpen：`PRAGMA foreign_keys = ON`（plans 自引用树 FK 护栏依赖此开关）；
 * - onCreate → createAll（v7 全量+user_version 章）、onUpgrade → 分段幂等迁移。
 *
 * 线程口径：调用方须在自有分发器上串行使用（写路径=dbWriteDispatcher；
 * 读路径=IO 面）——SQLiteOpenHelper 事务靠线程内 begin/end，跨线程挂起会破。
 */
// 真机实证（2026-11-15 仪器测试 6/6）：存量 sqflite 库直开零迁移 / 双句柄同文件
// 写竞争 N=400 零 busy 异常 / WAL+busy_timeout 编译期章 / 64 位 Long 保真——
// 回归钉=AndroidSqlStoreDeviceTest（androidTest，撤 moveToFirst 或单连接制即红）。
class AndroidSqlStore(context: Context, dbName: String = "shiguang.db") :
    SQLiteOpenHelper(context, dbName, null, ShiguangSchema.VERSION), SqlStore {

    override fun onConfigure(db: SQLiteDatabase) {
        // 单连接制（真机仪器测试实证 2026-11-15：框架 WAL flag 开启连接池后，池化读
        // 连接不过 SQLiteOpenHelper 回调，busy_timeout 回落 Android 默认 2500）——
        // 不开 WAL flag 即单连接，PRAGMA 全程生效；读并行度让位确定性（MCP 读面轻量，
        // 跨引擎并发由各引擎独立连接承担，= R3 实测受测形状）。
        // 两条均带返回值，execSQL 拒结果集语句，走 rawQuery；显式 WAL 不依赖框架全局默认。
        // ⚠ Android Cursor 懒执行：必须 moveToFirst 触发语句——close() 不执行（真机探针
        // 实证 2026-11-15：懒执行 journal_mode 读回框架默认 memory，执行后 wal+5000 全落）。
        db.rawQuery("PRAGMA journal_mode = WAL", null).use { c -> c.moveToFirst() }
        db.rawQuery("PRAGMA busy_timeout = 5000", null).use { c -> c.moveToFirst() }
        super.onConfigure(db)
    }

    override fun onCreate(db: SQLiteDatabase) {
        // SQLiteOpenHelper onCreate 已在框架事务内——DDL 直跑（createAll 含 user_version 章）
        kotlinx.coroutines.runBlocking {
            ShiguangSchema.createAll(object : SqlStore by NoDmlStore(db) {})
        }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        kotlinx.coroutines.runBlocking {
            ShiguangSchema.upgrade(object : SqlStore by NoDmlStore(db) {}, oldVersion)
        }
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        db.execSQL("PRAGMA foreign_keys = ON")
    }

    // ── SqlStore（阻塞实现：调用方线程执行）──────────────────────────────────────

    override suspend fun execute(sql: String) {
        writableDatabase.execSQL(sql)
    }

    override suspend fun insert(table: String, values: JsonObject, replace: Boolean, ignore: Boolean) {
        val cv = cvOf(values)
        val mode = when {
            replace -> SQLiteDatabase.CONFLICT_REPLACE
            ignore -> SQLiteDatabase.CONFLICT_IGNORE
            else -> SQLiteDatabase.CONFLICT_FAIL
        }
        writableDatabase.insertWithOnConflict(table, null, cv, mode)
    }

    override suspend fun query(
        table: String,
        columns: List<String>?,
        where: String?,
        args: List<Any?>?,
        orderBy: String?,
        limit: Int?,
    ): List<SqlRow> {
        val sql = buildString {
            append("SELECT ").append(columns?.joinToString(", ") ?: "*")
            append(" FROM ").append(table)
            if (where != null) append(" WHERE ").append(where)
            if (orderBy != null) append(" ORDER BY ").append(orderBy)
            if (limit != null) append(" LIMIT ").append(limit)
        }
        return rawQuery(sql, args ?: emptyList())
    }

    override suspend fun rawUpdate(sql: String, args: List<Any?>): Int {
        writableDatabase.execSQL(sql, args.toBindArgs())
        return changes()
    }

    override suspend fun rawQuery(sql: String, args: List<Any?>): List<SqlRow> =
        readableDatabase.rawQuery(sql, args.toBindArgs()).use(::readRows)

    override suspend fun delete(table: String, where: String?, args: List<Any?>?): Int =
        writableDatabase.delete(table, where, args?.toBindArgs())

    override suspend fun <T> transaction(body: suspend (SqlTxn) -> T): T {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val r = body(HelperTxn(db))
            db.setTransactionSuccessful()
            return r
        } finally {
            db.endTransaction()
        }
    }

    // ── 内部件 ──────────────────────────────────────────────────────────────────

    private fun changes(): Int =
        readableDatabase.rawQuery("SELECT changes()", null).use { c ->
            if (c.moveToFirst()) c.getLong(0).toInt() else 0
        }

    private class HelperTxn(private val db: SQLiteDatabase) : SqlTxn {
        override suspend fun insert(table: String, values: JsonObject, replace: Boolean, ignore: Boolean) {
            val cv = cvOf(values)
            val mode = when {
                replace -> SQLiteDatabase.CONFLICT_REPLACE
                ignore -> SQLiteDatabase.CONFLICT_IGNORE
                else -> SQLiteDatabase.CONFLICT_FAIL
            }
            db.insertWithOnConflict(table, null, cv, mode)
        }

        override suspend fun delete(table: String, where: String?, args: List<Any?>?): Int =
            db.delete(table, where, args?.toBindArgs())

        override suspend fun query(
            table: String,
            columns: List<String>?,
            where: String?,
            args: List<Any?>?,
            orderBy: String?,
            limit: Int?,
        ): List<SqlRow> {
            val sql = buildString {
                append("SELECT ").append(columns?.joinToString(", ") ?: "*")
                append(" FROM ").append(table)
                if (where != null) append(" WHERE ").append(where)
                if (orderBy != null) append(" ORDER BY ").append(orderBy)
                if (limit != null) append(" LIMIT ").append(limit)
            }
            return db.rawQuery(sql, (args ?: emptyList()).toBindArgs()).use(::readRows)
        }

        override suspend fun rawUpdate(sql: String, args: List<Any?>): Int {
            db.execSQL(sql, args.toBindArgs())
            return db.rawQuery("SELECT changes()", null).use { c ->
                if (c.moveToFirst()) c.getLong(0).toInt() else 0
            }
        }

        override suspend fun update(
            table: String,
            values: JsonObject,
            where: String,
            args: List<Any?>,
        ): Int {
            val set = values.keys.joinToString(", ") { "$it = ?" }
            val bind = values.toSqlValues().values.toList() + args
            db.execSQL("UPDATE $table SET $set WHERE $where", bind.toBindArgs())
            return db.rawQuery("SELECT changes()", null).use { c ->
                if (c.moveToFirst()) c.getLong(0).toInt() else 0
            }
        }
    }

    /** onCreate/onUpgrade 的 DDL 只读面（schema 阶段仅 execute/rawQuery）。 */
    private class NoDmlStore(private val db: SQLiteDatabase) : SqlStore {
        override suspend fun execute(sql: String) = db.execSQL(sql)
        override suspend fun rawQuery(sql: String, args: List<Any?>): List<SqlRow> =
            db.rawQuery(sql, args.toBindArgs()).use(::readRows)
        override suspend fun insert(table: String, values: JsonObject, replace: Boolean, ignore: Boolean) = dml()
        override suspend fun query(
            table: String, columns: List<String>?, where: String?, args: List<Any?>?, orderBy: String?, limit: Int?,
        ) = dml()
        override suspend fun rawUpdate(sql: String, args: List<Any?>) = dml()
        override suspend fun delete(table: String, where: String?, args: List<Any?>?) = dml()
        override suspend fun <T> transaction(body: suspend (SqlTxn) -> T) = dml()
        private fun dml(): Nothing = throw UnsupportedOperationException("schema 阶段无 DML 面")
    }
}

/** JSON 行值 → ContentValues（布尔→0/1、数字→Long、串→String、null→NULL）。 */
private fun cvOf(values: JsonObject): ContentValues {
    val cv = ContentValues()
    for ((k, v) in values) {
        when {
            v is JsonNull -> cv.putNull(k)
            v is JsonPrimitive && v.isString -> cv.put(k, v.content)
            v is JsonPrimitive && v.booleanOrNull != null -> cv.put(k, if (v.booleanOrNull == true) 1L else 0L)
            v is JsonPrimitive && v.longOrNull != null -> cv.put(k, v.longOrNull)
            else -> throw IllegalArgumentException("非 SQL 标量值: $v（列 $k）")
        }
    }
    return cv
}

private fun List<Any?>.toBindArgs(): Array<String?> =
    map { a ->
        when (a) {
            null -> null
            is Long -> a.toString()
            is Int -> a.toString()
            else -> a.toString()
        }
    }.toTypedArray()

private fun readRows(c: Cursor): List<SqlRow> {
    val out = mutableListOf<SqlRow>()
    while (c.moveToNext()) {
        val row = mutableMapOf<String, Any?>()
        for (i in 0 until c.columnCount) {
            row[c.getColumnName(i)] = when (c.getType(i)) {
                Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                Cursor.FIELD_TYPE_NULL -> null
                else -> c.getString(i)
            }
        }
        out.add(row)
    }
    return out
}
