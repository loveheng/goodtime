package app.shiguang.core.model.collab

import app.shiguang.core.model.JdbcSqlStore
import app.shiguang.core.model.ShiguangSchema
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

/**
 * CollabBundleRepository 单测（test/collab_bundle_repo_test.dart Kotlin 移植；
 * D9 拍板读/写面回归钉 + 水位回落冒烟）。
 */
class CollabBundleRepoTest {

    private suspend fun newRepo(): CollabBundleRepository {
        val store = JdbcSqlStore.inMemory()
        ShiguangSchema.createAll(store)
        var clock = 0L
        return CollabBundleRepository(store) { clock++ }
    }

    @Test
    fun headerKeyVersionReadsRealColumn() = runTest {
        val repo = newRepo()
        repo.ensureBundle(
            bundleId = "b1",
            proposalType = "DIRECTIVE",
            initiatorId = "u1",
            participants = listOf("u1"),
            expiresAtMs = 9,
            keyVersion = 2, // 修复前 fromRow 读 key_ver（不存在）→ 恒回落 1
        )
        assertEquals(2, repo.bundleHeader("b1")!!.keyVersion)
    }

    @Test
    fun recordEventRekeyFlagAdvancesColumn() = runTest {
        val repo = newRepo()
        repo.ensureBundle(
            bundleId = "b1",
            proposalType = "DIRECTIVE",
            initiatorId = "u1",
            participants = listOf("u1"),
            expiresAtMs = 9,
        )
        assertEquals(1, repo.bundleHeader("b1")!!.keyVersion)
        repo.recordEvent("b1", 1, "REKEY", "u1", byteArrayOf(1), 1, "m1", rekey = true)
        repo.recordEvent("b1", 2, "REKEY", "u1", byteArrayOf(2), 2, "m2", rekey = true)
        repo.recordEvent("b1", 3, "ACCEPT", "u2", byteArrayOf(3), 2, "m3") // 非 REKEY 不推进
        val h = repo.bundleHeader("b1")!!
        assertEquals(3, h.keyVersion) // 1 + 两次 REKEY；ACCEPT 不动
        assertEquals(3L, h.maxSeqId)
    }

    @Test
    fun maxSeqIdFallsBackToEventsWhenHeadMissing() = runTest {
        val repo = newRepo()
        // 无包头落事件 → 兜底自补头（key_version=keyVer）+ 事件水位
        repo.recordEvent("b-ghost", 7, "ACCEPT", "u1", byteArrayOf(1), 1, "m7")
        assertEquals(7L, repo.maxSeqId("b-ghost"))
        assertEquals(7L, repo.bundleHeader("b-ghost")!!.maxSeqId)
    }
}
