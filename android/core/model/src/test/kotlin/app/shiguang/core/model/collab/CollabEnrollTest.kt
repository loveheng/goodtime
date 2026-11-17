package app.shiguang.core.model.collab

import app.shiguang.core.model.JdbcSqlStore
import app.shiguang.core.model.ShiguangSchema
import kotlinx.coroutines.test.runTest
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 被邀端密钥入户行为测试（test/collab_enroll_test.dart 5 例 Kotlin 移植，§5.3）：
 * 信封互逆自证 + 半盲纪律（跳过而非抛）+ 幂等落户。
 */
class CollabEnrollTest {

    private suspend fun fresh(): Triple<JdbcSqlStore, KeyStore, BundleKeySource> {
        val db = JdbcSqlStore.inMemory()
        ShiguangSchema.createAll(db)
        val vault = KeyStore(MemorySecretStore())
        return Triple(db, vault, BundleKeySource(db, vault))
    }

    private fun seed(): ByteArray = ByteArray(32).also(SecureRandom()::nextBytes)

    /** 造一条「发起人 EK × 被邀人 SPK 公钥」封出的真实信封（与发起侧同函数，互逆自证）。 */
    private fun makeEnvelope(bundleKey: ByteArray, keyVer: Int, spkPub: ByteArray) =
        wrapBundleKeyForMember(bundleKey, keyVer, "me", spkPub)

    @Test
    fun `enroll 拉到自己的信封 SPK 私钥解开落户 返回 1`() = runTest {
        val (_, vault, keys) = fresh()
        vault.ensureReady()
        val spk = seed()
        val bundleKey = generateBundleKey()
        val env = makeEnvelope(bundleKey, 1, X25519Ops.publicKeyFromSeed(spk))
        val intake = BundleKeyIntake(
            fetch = { _ -> listOf(KeyEnvelopeView.fromJson(env)) },
            keys = keys,
            mySignedPreKey = { spk },
        )
        assertEquals(1, intake.enroll("b1"))
        // 落户后按 (bundleId, keyVer) 可取回明文 K_bundle（逐字节相等）
        val got = keys("b1", 1)!!
        assertTrue(bundleKey.contentEquals(got))
    }

    @Test
    fun `enroll 拉不到 返回 0 不抛 骨架照常推进`() = runTest {
        val (_, vault, keys) = fresh()
        vault.ensureReady()
        val spk = seed()
        val intake = BundleKeyIntake(
            fetch = { _ -> throw java.lang.IllegalStateException("relay unreachable") },
            keys = keys,
            mySignedPreKey = { spk },
        )
        assertEquals(0, intake.enroll("b1"))
        assertNull(keys("b1", 1)) // 半盲纪律：没有落户任何密钥
    }

    @Test
    fun `enroll 已落户版本幂等跳过 fetch 仍执行`() = runTest {
        val (_, vault, keys) = fresh()
        vault.ensureReady()
        val spk = seed()
        val bundleKey = generateBundleKey()
        val env = makeEnvelope(bundleKey, 1, X25519Ops.publicKeyFromSeed(spk))
        keys.persist("b1", 1, bundleKey) // 先手动落户（认祖归宗等）
        var fetchCount = 0
        val intake = BundleKeyIntake(
            fetch = { _ ->
                fetchCount++
                listOf(KeyEnvelopeView.fromJson(env))
            },
            keys = keys,
            mySignedPreKey = { spk },
        )
        assertEquals(0, intake.enroll("b1")) // 已落户：跳过
        assertEquals(1, fetchCount) // fetch 仍执行（真实补拉不该省）
    }

    @Test
    fun `enroll 密钥对不上 A 封 B 解 MAC 不过静默跳过 返回 0`() = runTest {
        val (_, vault, keys) = fresh()
        vault.ensureReady()
        val spkOfA = seed()
        val bundleKey = generateBundleKey()
        val env = makeEnvelope(bundleKey, 1, X25519Ops.publicKeyFromSeed(spkOfA))
        // 被邀端用「另一把」SPK 私钥去解（不该是我的信封）
        val spkOfB = seed()
        val intake = BundleKeyIntake(
            fetch = { _ -> listOf(KeyEnvelopeView.fromJson(env)) },
            keys = keys,
            mySignedPreKey = { spkOfB },
        )
        assertEquals(0, intake.enroll("b1"))
        assertNull(keys("b1", 1))
    }

    @Test
    fun `enroll 空信封列表 返回 0`() = runTest {
        val (_, vault, keys) = fresh()
        vault.ensureReady()
        val spk = seed()
        val intake = BundleKeyIntake(
            fetch = { _ -> emptyList() },
            keys = keys,
            mySignedPreKey = { spk },
        )
        assertEquals(0, intake.enroll("b1"))
    }
}
