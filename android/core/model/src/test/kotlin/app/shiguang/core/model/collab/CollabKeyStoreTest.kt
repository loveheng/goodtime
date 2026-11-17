package app.shiguang.core.model.collab

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import javax.crypto.AEADBadTagException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 密钥域单测（test/collab_key_store_test.dart Kotlin 移植，2026-10-11 改版口径）：
 * MSK 端侧自动托管——KeyStore 只剩「就绪 + seal/open」两面。
 */
class CollabKeyStoreTest {

    private fun store(s: SecretStore = MemorySecretStore()) = KeyStore(s)

    // ---- MSK 自动托管 ------------------------------------------------------------

    @Test
    fun `ensureReady 生成 MSK 落安全区 同 store 二次装配读回同一把`() = runTest {
        val mem = MemorySecretStore()
        val ks1 = store(mem)
        assertFalse(ks1.isUnlocked)
        ks1.ensureReady()
        assertTrue(ks1.isUnlocked)
        assertEquals(32, ks1.mskBytes?.size)
        // 模拟换进程：同一安全区内容、全新内存态
        val ks2 = store(mem)
        assertFalse(ks2.isUnlocked)
        ks2.ensureReady()
        assertTrue(ks1.mskBytes.contentEquals(ks2.mskBytes!!), "同一安全区=同一把 MSK")
    }

    @Test
    fun `ensureReady 幂等 重复调用不换钥`() = runTest {
        val ks = store()
        ks.ensureReady()
        val msk = ks.mskBytes
        ks.ensureReady()
        ks.ensureReady()
        assertTrue(msk.contentEquals(ks.mskBytes!!))
    }

    @Test
    fun `并发 ensureReady 合并为一次 两边拿到同一把钥`() = runTest {
        val ks = store()
        awaitAll(
            async { ks.ensureReady() },
            async { ks.ensureReady() },
        )
        assertEquals(32, ks.mskBytes?.size)
    }

    @Test
    fun `不同安全区各自生成独立 MSK 互不相通`() = runTest {
        val a = store()
        a.ensureReady()
        val b = store()
        b.ensureReady()
        assertNotEquals(a.mskBytes?.let { it.toList() }, b.mskBytes?.toList())
    }

    // ---- seal/open ---------------------------------------------------------------

    @Test
    fun `往返 密封后解封还原原文`() = runTest {
        val ks = store()
        ks.ensureReady()
        val plain = ByteArray(64) { it.toByte() }
        val sealed = ks.seal(plain)
        assertTrue(plain.contentEquals(ks.open(sealed)))
    }

    @Test
    fun `未就绪即 seal open 拒绝 绝不静默造钥`() = runTest {
        val ks = store()
        assertFailsWith<IllegalStateException> { ks.seal(byteArrayOf(1)) }
        assertFailsWith<IllegalStateException> { ks.open(byteArrayOf(1)) }
        // 就绪后恢复正常
        ks.ensureReady()
        val sealed = ks.seal(byteArrayOf(1, 2, 3))
        assertTrue(byteArrayOf(1, 2, 3).contentEquals(ks.open(sealed)))
    }

    @Test
    fun `别的 MSK 解不开 GCM MAC 校验失败抛出`() = runTest {
        val a = store()
        a.ensureReady()
        val b = store()
        b.ensureReady()
        val sealed = a.seal(byteArrayOf(9, 9, 9))
        assertFailsWith<AEADBadTagException> { b.open(sealed) }
    }
}
