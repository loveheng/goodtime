package app.shiguang.shiguang.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.After
import org.junit.Assume
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * AndroidSqlStore 真机实证（UNCERTAIN 消标轮，2026-11-15）：
 * 新装 createAll/存量 v7 直开零迁移/WAL+busy_timeout+FK 编译期章/64 位列 Long 保真/
 * 双句柄同文件写竞争（sqflite 第二写者同机制面）。
 * 测试库用独立名（不触碰生产 shiguang.db 写面）；存量真库仅只读打开验证。
 */
@RunWith(AndroidJUnit4::class)
class AndroidSqlStoreDeviceTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val testDb = "test_schema_device.db"

    @After
    fun cleanup() {
        context.deleteDatabase(testDb)
    }

    private fun insertSetting(store: AndroidSqlStore, key: String, updatedAtMs: Long) = runBlocking {
        store.insert(
            "app_settings",
            buildJsonObject {
                put("key", JsonPrimitive(key))
                put("value", JsonPrimitive("v-$key"))
                put("updated_at", JsonPrimitive(updatedAtMs))
            },
            replace = true,
            ignore = false,
        )
    }

    @Test
    fun freshCreateRoundtripAndPragmas() {
        runBlocking {
            val store = AndroidSqlStore(context, testDb)
            insertSetting(store, "created_at_probe", 1_760_000_000_000L)
            val rows = store.query("app_settings", null, "key = ?", listOf("created_at_probe"), null, null)
            assertEquals(1, rows.size)
            // 64 位列 Long 保真（防 Int 静默截断）
            assertEquals(1_760_000_000_000L, rows[0]["updated_at"])
            // 编译期章三件（onConfigure/onOpen 章逐 PRAGMA 实测）
            assertEquals(
                "wal",
                (store.rawQuery("PRAGMA journal_mode", emptyList()).single()["journal_mode"] as String).lowercase(),
            )
            assertEquals(5000L, store.rawQuery("PRAGMA busy_timeout", emptyList()).single()["timeout"] as? Long)
            assertEquals(1L, store.rawQuery("PRAGMA foreign_keys", emptyList()).single()["foreign_keys"] as? Long)
            assertEquals(7L, store.rawQuery("PRAGMA user_version", emptyList()).single()["user_version"] as? Long)
            store.delete("app_settings", "key = ?", listOf("created_at_probe"))
        }
    }

    @Test
    fun existingDbReopensWithoutMigration() {
        runBlocking {
            // 首开触发 onCreate（v7 全量）→ 新实例重开：存量 v7 库零迁移直开（onUpgrade 零触发面）
            insertSetting(AndroidSqlStore(context, testDb), "reopen_probe", 1L)
            val reopened = AndroidSqlStore(context, testDb)
            assertEquals(7L, reopened.rawQuery("PRAGMA user_version", emptyList()).single()["user_version"] as? Long)
            val rows = reopened.query("app_settings", null, "key = ?", listOf("reopen_probe"), null, null)
            assertEquals(1, rows.size)
        }
    }

    @Test
    fun realSqfliteDatabaseOpensReadOnly() {
        runBlocking {
            // 存量 sqflite 真库（生产 shiguang.db）首启打开验证——只读面，零写零迁移
            val dbFile = context.getDatabasePath("shiguang.db")
            Assume.assumeTrue("真机无存量 shiguang.db（跳过存量验证）", dbFile.exists())
            val store = AndroidSqlStore(context, "shiguang.db")
            assertEquals(7L, store.rawQuery("PRAGMA user_version", emptyList()).single()["user_version"] as? Long)
            val tables = store.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name", emptyList(),
            )
            val names = tables.map { it["name"] as String }
            // 12 表契约面抽查（plans/schedule_blocks/app_settings/collab_events）
            assertEquals(true, names.containsAll(listOf("plans", "schedule_blocks", "app_settings", "collab_events")))
        }
    }

    @Test
    fun dualHandleWriteContention() {
        // 双写者同文件竞争：A=AndroidSqlStore / B=独立裸句柄（sqflite 第二写者同机制面——
        // WAL+busy_timeout=5000 吸收竞争，零 SQLITE_BUSY 异常逃逸；N=200 对齐 R3 实测口径）
        runBlocking {
            val storeA = AndroidSqlStore(context, testDb)
            insertSetting(storeA, "contention_probe", 1L) // 确保表已在位
            val dbB = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(testDb), null)
            dbB.enableWriteAheadLogging()
            dbB.rawQuery("PRAGMA busy_timeout = 5000", null).close()

            val n = 200
            val errors = mutableListOf<Exception>()
            val tA = Thread {
                try {
                    for (i in 0 until n) runBlocking {
                        insertSetting(storeA, "k_a_$i", i.toLong())
                    }
                } catch (e: Exception) {
                    synchronized(errors) { errors.add(e) }
                }
            }
            val tB = Thread {
                try {
                    for (i in 0 until n) {
                        val cv = android.content.ContentValues().apply {
                            put("key", "k_b_$i")
                            put("value", "v-k_b_$i")
                            put("updated_at", i.toLong())
                        }
                        dbB.insertWithOnConflict(
                            "app_settings", null, cv,
                            SQLiteDatabase.CONFLICT_REPLACE,
                        )
                    }
                } catch (e: Exception) {
                    synchronized(errors) { errors.add(e) }
                }
            }
            tA.start(); tB.start(); tA.join(); tB.join()
            assertEquals(0, errors.size, "双写者竞争异常: $errors")
            val rows = storeA.rawQuery("SELECT COUNT(*) AS c FROM app_settings WHERE key LIKE 'k_%'", emptyList())
            assertEquals(2L * n, rows.single()["c"] as? Long)
            dbB.close()
        }
    }
}
