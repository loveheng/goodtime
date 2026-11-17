package app.shiguang.core.model.collab

import app.shiguang.core.model.JdbcSqlStore
import app.shiguang.core.model.ShiguangSchema
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * test/collab_outbox_test.dart Kotlin 移植（Phase 3.3 本地 Outbox 验收）：
 * 弱网单测——杀进程不丢操作（file DB close→reopen→pending 原样），外加 §11.1
 * 三段式语义逐条：入队即落盘 / 按序弹出 / 确认即删 / 409 清箱引导 / 网络失败保序停止。
 */

/** 脚本化传输：按序吐出预设结局，并记录被发送的条目顺序。 */
private class ScriptedTransport(private val script: List<SendOutcome>) : OutboxTransport {
    val seen = mutableListOf<OutboxEntry>()
    private var cursor = 0

    override suspend fun send(e: OutboxEntry): SendOutcome {
        seen.add(e)
        check(cursor < script.size) { "script exhausted at ${e.id}" }
        return script[cursor++]
    }
}

/** 裸异常传输：模拟传输层抛非 SendOutcome 异常（坏网络）。 */
private class ThrowingTransport : OutboxTransport {
    override suspend fun send(e: OutboxEntry): SendOutcome = throw RuntimeException("net down")
}

private fun payload(vararg pairs: Pair<String, String>): kotlinx.serialization.json.JsonObject =
    buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }

class CollabOutboxTest {

    private suspend fun newRepo(): Pair<JdbcSqlStore, OutboxRepository> {
        val store = JdbcSqlStore.inMemory()
        ShiguangSchema.createAll(store)
        var seq = 0
        val repo = OutboxRepository(store, nowMs = { 1000 }, newId = { "ob-${seq++}" })
        return store to repo
    }

    @Test
    fun enqueuePersistsWithCreatedAtIdOrder() = runTest {
        val (store, repo) = newRepo()
        // 冻结时钟 → 靠 id tie-break；故意乱序语义入队：id 依次 0、1、2
        val first = repo.enqueue("b1", "ACCEPT", payload("step_id" to "s3", "pref" to "ok"))
        val second = repo.enqueue("b1", "REJECT", payload("step_id" to "s1", "reason" to "time_conflict"), keyVer = 2)
        repo.enqueue("b1", "ACCEPT", payload("step_id" to "s2"))

        assertEquals("ob-0", first.id)
        assertEquals("ob-1", second.id)
        assertEquals(2, second.keyVer)

        val pending = repo.pending()
        assertEquals(listOf("ob-0", "ob-1", "ob-2"), pending.map { it.id })
        // payload JSON 往返无损（明文本地域）
        assertEquals(payload("step_id" to "s3", "pref" to "ok"), pending[0].payload)
        assertEquals("REJECT", pending[1].actionType)
        assertEquals(0, pending[1].attempts)
    }

    @Test
    fun killProcessLosesNothingFileDbReopen() = runTest {
        val dir = Files.createTempDirectory("outbox_test")
        val dbPath = dir.resolve("t.db").toString()
        val conn1 = DriverManager.getConnection("jdbc:sqlite:$dbPath")
        val store1 = JdbcSqlStore(conn1)
        ShiguangSchema.createAll(store1)
        var seq = 100
        val repo = OutboxRepository(store1, nowMs = { 1000 }, newId = { "ob-${seq++}" })
        repo.enqueue("b1", "ACCEPT", payload("step_id" to "s1"))
        repo.enqueue("b1", "REJECT", payload("step_id" to "s2", "reason" to "role_unwilling"))
        repo.enqueue("b1", "VOTE", payload("slot_id" to "fri18", "pref" to "prefer"))
        conn1.close() // 杀进程

        val conn2 = DriverManager.getConnection("jdbc:sqlite:$dbPath") // 冷启动重开
        val store2 = JdbcSqlStore(conn2)
        var seq2 = 200
        val repo2 = OutboxRepository(store2, nowMs = { 1000 }, newId = { "ob-${seq2++}" })
        val pending = repo2.pending()
        assertEquals(3, pending.size)
        assertEquals("ACCEPT", pending[0].actionType)
        assertEquals(payload("step_id" to "s2", "reason" to "role_unwilling"), pending[1].payload)
        assertEquals(payload("slot_id" to "fri18", "pref" to "prefer"), pending[2].payload)
        assertTrue(pending.all { it.attempts == 0 })
        conn2.close()
    }

    @Test
    fun drainAllAcceptedInOrderAndClears() = runTest {
        val (store, repo) = newRepo()
        for (s in listOf("s1", "s2", "s3")) {
            repo.enqueue("b1", "ACCEPT", payload("step_id" to s))
        }
        val t = ScriptedTransport(listOf(SendAccepted, SendAccepted, SendAccepted))
        val report = OutboxDrainer(repo, t).drain()

        assertEquals(3, report.sent)
        assertEquals(0, report.conflicts.size)
        assertEquals(false, report.stoppedByNetwork)
        // 发送序 = 弹出全序
        assertEquals(listOf("s1", "s2", "s3"), t.seen.map { it.payload["step_id"]!!.jsonContent() })
        assertEquals(0, repo.pending().size) // 确认即删
    }

    @Test
    fun offlineKeepsEntryAndStopsInOrder() = runTest {
        val (store, repo) = newRepo()
        for (s in listOf("s1", "s2", "s3")) {
            repo.enqueue("b1", "ACCEPT", payload("step_id" to s))
        }
        // 第 2 条摸网失败（非终态）→ 第 3 条因果依赖前序，绝不乱序发出
        val t1 = ScriptedTransport(listOf(SendAccepted, SendOffline(RuntimeException("timeout"))))
        val r1 = OutboxDrainer(repo, t1).drain()

        assertEquals(1, r1.sent)
        assertEquals(true, r1.stoppedByNetwork)
        assertEquals(2, t1.seen.size) // 第 3 条未尝试
        val pending = repo.pending()
        assertEquals(listOf("s2", "s3"), pending.map { it.payload["step_id"]!!.jsonContent() })
        assertEquals(1, pending[0].attempts) // 离线重试计数
        assertEquals(0, pending[1].attempts) // 原样未动

        // 弱网恢复：重 drain 补发剩余
        val t2 = ScriptedTransport(listOf(SendAccepted, SendAccepted))
        val r2 = OutboxDrainer(repo, t2).drain()
        assertEquals(2, r2.sent)
        assertEquals(false, r2.stoppedByNetwork)
        assertEquals(0, repo.pending().size)
    }

    @Test
    fun rejectedClearsEntryAndReportsConflictWithoutBlocking() = runTest {
        val (store, repo) = newRepo()
        repo.enqueue("b1", "ACCEPT", payload("step_id" to "s1"))
        repo.enqueue("b1", "PATCH_TIME", payload("step_id" to "s2"))

        val t = ScriptedTransport(listOf(SendRejected("STALE_REVISION"), SendAccepted))
        val report = OutboxDrainer(repo, t).drain()

        assertEquals(1, report.sent) // 第 2 条不因第 1 条被打回而阻塞
        assertEquals(false, report.stoppedByNetwork)
        assertEquals(1, report.conflicts.size)
        assertEquals("STALE_REVISION", report.conflicts.first().errorCode)
        assertEquals("ACCEPT", report.conflicts.first().actionType)
        assertEquals(0, repo.pending().size) // 两条均已出箱
    }

    @Test
    fun transportBareExceptionNormalizedToOffline() = runTest {
        val (store, repo) = newRepo()
        repo.enqueue("b1", "ACCEPT", payload("step_id" to "s1"))

        val report = OutboxDrainer(repo, ThrowingTransport()).drain()
        assertEquals(true, report.stoppedByNetwork)
        val pending = repo.pending()
        assertEquals(1, pending.size)
        assertEquals(1, pending.single().attempts)
    }
}

/** JsonPrimitive 文本取值（step_id 等字符串标量）。 */
private fun kotlinx.serialization.json.JsonElement.jsonContent(): String =
    (this as kotlinx.serialization.json.JsonPrimitive).content
