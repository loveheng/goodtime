package app.shiguang.core.model

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * 存储抽象：把原生 Repository 用到的 SQLite 子集收敛为纯 JVM 接口
 * （lib/data/storage.dart ScheduleStorage 的 Kotlin 镜像 + DDL execute 面）。
 * 使 :core:action 原生仓储不依赖任何平台包；宿主注入自有实现
 * （:app = AndroidSqlStore / SQLiteOpenHelper，测试 = sqlite-jdbc）。
 *
 * 行值口径：INTEGER→Long（**64 位列铁律**——毫秒时间戳约 1.76e12 必溢 Int）、
 * TEXT→String、NULL→null。JSON 值经 [jsonToSqlValue] 归一后入列。
 */
interface SqlStore {
    /** DDL / PRAGMA（无结果集语句）。 */
    suspend fun execute(sql: String)

    /** 插入一行；[replace]=true = INSERT OR REPLACE（settings upsert 用）；
     *  [ignore]=true = INSERT OR IGNORE（collab_key_ring 幂等落户用）。 */
    suspend fun insert(table: String, values: JsonObject, replace: Boolean = false, ignore: Boolean = false)

    /** 表内查询（仅暴露仓储实际用到的子集）。 */
    suspend fun query(
        table: String,
        columns: List<String>? = null,
        where: String? = null,
        args: List<Any?>? = null,
        orderBy: String? = null,
        limit: Int? = null,
    ): List<SqlRow>

    /** 原生 SQL 更新（乐观锁 CAS 等用）。 */
    suspend fun rawUpdate(sql: String, args: List<Any?> = emptyList()): Int

    /** 原生 SQL 查询（JOIN 等读形）。 */
    suspend fun rawQuery(sql: String, args: List<Any?> = emptyList()): List<SqlRow>

    /** 物理删除。 */
    suspend fun delete(table: String, where: String? = null, args: List<Any?>? = null): Int

    /** 事务：全成功提交 / 中途异常整体回滚；回调内 SqlTxn 读写同事务（read-your-writes）。 */
    suspend fun <T> transaction(body: suspend (SqlTxn) -> T): T
}

/** 事务内句柄（[SqlStore.transaction] 回调参数；与 Dart StorageTxn 子集对齐）。 */
interface SqlTxn {
    suspend fun insert(table: String, values: JsonObject, replace: Boolean = false, ignore: Boolean = false)
    suspend fun delete(table: String, where: String? = null, args: List<Any?>? = null): Int
    suspend fun query(
        table: String,
        columns: List<String>? = null,
        where: String? = null,
        args: List<Any?>? = null,
        orderBy: String? = null,
        limit: Int? = null,
    ): List<SqlRow>
    suspend fun rawUpdate(sql: String, args: List<Any?> = emptyList()): Int

    /** 字段级更新（乐观锁 CAS 用）。 */
    suspend fun update(table: String, values: JsonObject, where: String, args: List<Any?>): Int
}

/** 单行：列名 → Long/String/null（64 位列铁律见 SqlStore KDoc）。 */
typealias SqlRow = Map<String, Any?>

/** JSON 标量 → SQL 行值：布尔→1/0（行内 INTEGER 口径）、数字→Long、串→String、null→null。 */
fun jsonToSqlValue(e: JsonElement?): Any? = when {
    e == null || e is JsonNull -> null
    e !is JsonPrimitive -> throw IllegalArgumentException("非 SQL 标量值: $e")
    e.booleanOrNull != null -> if (e.booleanOrNull == true) 1L else 0L
    e.isString -> e.contentOrNull
    else -> e.longOrNull ?: throw IllegalArgumentException("非 SQL 标量值: $e")
}

/** JsonObject → SQL 行值 Map（键=列名）。 */
fun JsonObject.toSqlValues(): Map<String, Any?> =
    entries.associate { (k, v) -> k to jsonToSqlValue(v) }

/** SQL 行 → JsonObject（Long/String/null → JSON；MCP 快照转换的第一跳）。 */
fun rowToJson(row: SqlRow): JsonObject = buildJsonObject {
    for ((k, v) in row) {
        put(k, when (v) {
            null -> JsonNull
            is Long -> JsonPrimitive(v)
            is Number -> JsonPrimitive(v.toLong())
            is String -> JsonPrimitive(v)
            else -> throw IllegalArgumentException("非行值类型: ${v::class.simpleName}（列 $k）")
        })
    }
}
