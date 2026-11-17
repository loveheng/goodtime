package app.shiguang.core.model

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import java.sql.Connection
import java.sql.DriverManager

/**
 * sqlite-jdbc 测试驱动：SqlStore 的 JVM 真跑实现（仅测试面——schema 向量对拍 +
 * 仓储行为测试；生产驱动 = :app AndroidSqlStore / SQLiteOpenHelper）。
 *
 * 线程口径：连接非线程安全——store 级 Mutex 串行化全部顶层操作（镜像 Dart sqflite
 * 单 isolate 串行语义；2026-11-14 引擎并发轮实证单连接并发写会互踩，单线程约定不能
 * 只靠调用方自律）。事务 = 锁内整体执行，SqlTxn 续调不再加锁（互斥覆盖事务全程）。
 */
class JdbcSqlStore(private val conn: Connection) : SqlStore {

    private val mutex = kotlinx.coroutines.sync.Mutex()

    companion object {
        /** 内存库（:memory: 每连接独立——测试用单连接即隔离）。 */
        fun inMemory(): JdbcSqlStore {
            val conn = DriverManager.getConnection("jdbc:sqlite::memory:")
            return JdbcSqlStore(conn)
        }
    }

    override suspend fun execute(sql: String) {
        mutex.withLock { conn.createStatement().use { it.execute(sql) } }
    }

    override suspend fun insert(table: String, values: JsonObject, replace: Boolean, ignore: Boolean) {
        mutex.withLock {
        val cols = values.keys.toList()
        val verb = when {
            replace -> "INSERT OR REPLACE INTO "
            ignore -> "INSERT OR IGNORE INTO "
            else -> "INSERT INTO "
        }
        val sql = "$verb$table (${cols.joinToString(", ")}) " +
            "VALUES (${cols.joinToString(", ") { "?" }})"
        execUpdate(sql, values.toSqlValues().values.toList())
        }
    }

    override suspend fun query(
        table: String,
        columns: List<String>?,
        where: String?,
        args: List<Any?>?,
        orderBy: String?,
        limit: Int?,
    ): List<SqlRow> {
        return mutex.withLock {
        val sql = buildString {
            append("SELECT ").append(columns?.joinToString(", ") ?: "*")
            append(" FROM ").append(table)
            if (where != null) append(" WHERE ").append(where)
            if (orderBy != null) append(" ORDER BY ").append(orderBy)
            if (limit != null) append(" LIMIT ").append(limit)
        }
        rawQueryLocked(sql, args ?: emptyList())
        }
    }

    override suspend fun rawUpdate(sql: String, args: List<Any?>): Int = mutex.withLock {
        execUpdate(sql, args)
    }

    override suspend fun rawQuery(sql: String, args: List<Any?>): List<SqlRow> = mutex.withLock {
        rawQueryLocked(sql, args)
    }

    override suspend fun delete(table: String, where: String?, args: List<Any?>?): Int = mutex.withLock {
        val sql = if (where == null) "DELETE FROM $table" else "DELETE FROM $table WHERE $where"
        execUpdate(sql, args ?: emptyList())
    }

    override suspend fun <T> transaction(body: suspend (SqlTxn) -> T): T = mutex.withLock {
        val prev = conn.autoCommit
        conn.autoCommit = false
        try {
            val txn = JdbcSqlTxn(conn)
            val r = body(txn)
            conn.commit()
            r
        } catch (e: Throwable) {
            conn.rollback()
            throw e
        } finally {
            conn.autoCommit = prev
        }
    }

    /** 锁内专用（调用方须已持锁）：rawQuery 复用体。 */
    private fun rawQueryLocked(sql: String, args: List<Any?>): List<SqlRow> {
        conn.prepareStatement(sql).use { ps ->
            bind(ps, args)
            return readRows(ps)
        }
    }

    private fun execUpdate(sql: String, args: List<Any?>): Int {
        conn.prepareStatement(sql).use { ps ->
            bind(ps, args)
            return ps.executeUpdate()
        }
    }

    private fun bind(ps: java.sql.PreparedStatement, args: List<Any?>) {
        args.forEachIndexed { i, v ->
            when (v) {
                null -> ps.setObject(i + 1, null)
                is Long -> ps.setLong(i + 1, v)
                is Int -> ps.setLong(i + 1, v.toLong())
                else -> ps.setString(i + 1, v.toString())
            }
        }
    }
}

/** 参数绑定（Long/String/null 三态口径）。 */
private fun bind(ps: java.sql.PreparedStatement, args: List<Any?>) {
    args.forEachIndexed { i, v ->
        when (v) {
            null -> ps.setObject(i + 1, null)
            is Long -> ps.setLong(i + 1, v)
            is Int -> ps.setLong(i + 1, v.toLong())
            else -> ps.setString(i + 1, v.toString())
        }
    }
}

/** 结果集行提取（Long 归一——64 位列铁律）。 */
private fun readRows(ps: java.sql.PreparedStatement): List<SqlRow> {
    ps.executeQuery().use { rs ->
        val meta = rs.metaData
        val n = meta.columnCount
        val out = mutableListOf<SqlRow>()
        while (rs.next()) {
            val row = mutableMapOf<String, Any?>()
            for (i in 1..n) {
                val name = meta.getColumnLabel(i)
                val v = rs.getObject(i)
                row[name] = when (v) {
                    null -> null
                    is Long -> v
                    is Int -> v.toLong()
                    is Number -> v.toLong()
                    else -> v.toString()
                }
            }
            out.add(row)
        }
        return out
    }
}

/** 事务内句柄（同一连接读写同事务）。 */
class JdbcSqlTxn(private val conn: Connection) : SqlTxn {

    override suspend fun insert(table: String, values: JsonObject, replace: Boolean, ignore: Boolean) {
        val cols = values.keys.toList()
        val verb = when {
            replace -> "INSERT OR REPLACE INTO "
            ignore -> "INSERT OR IGNORE INTO "
            else -> "INSERT INTO "
        }
        val sql = "$verb$table (${cols.joinToString(", ")}) " +
            "VALUES (${cols.joinToString(", ") { "?" }})"
        execUpdate(sql, values.toSqlValues().values.toList())
    }

    override suspend fun delete(table: String, where: String?, args: List<Any?>?): Int {
        val sql = if (where == null) "DELETE FROM $table" else "DELETE FROM $table WHERE $where"
        return execUpdate(sql, args ?: emptyList())
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
        conn.prepareStatement(sql).use { ps ->
            bind(ps, args ?: emptyList())
            return readRows(ps)
        }
    }

    override suspend fun rawUpdate(sql: String, args: List<Any?>): Int =
        execUpdate(sql, args)

    override suspend fun update(
        table: String,
        values: JsonObject,
        where: String,
        args: List<Any?>,
    ): Int {
        val set = values.keys.joinToString(", ") { "$it = ?" }
        val sql = "UPDATE $table SET $set WHERE $where"
        return execUpdate(sql, values.toSqlValues().values.toList() + args)
    }

    private fun execUpdate(sql: String, args: List<Any?>): Int {
        conn.prepareStatement(sql).use { ps ->
            args.forEachIndexed { i, v ->
                when (v) {
                    null -> ps.setObject(i + 1, null)
                    is Long -> ps.setLong(i + 1, v)
                    is Int -> ps.setLong(i + 1, v.toLong())
                    else -> ps.setString(i + 1, v.toString())
                }
            }
            return ps.executeUpdate()
        }
    }
}
