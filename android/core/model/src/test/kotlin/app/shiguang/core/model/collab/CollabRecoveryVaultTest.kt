package app.shiguang.core.model.collab

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** test/collab_recovery_vault_test.dart Kotlin 移植（v3 拍板：金库包裹钥=登录密码派生）。
 *  含跨端向量：Dart 端（cryptography 包 Argon2id）封库 → Kotlin（BouncyCastle）开库
 *  ——R1「Argon2id 字节级兼容」机械钉（tool/golden/gen_recovery_vault_vector.dart 产出）。 */
class CollabRecoveryVaultTest {

    private val msk = ByteArray(32) { it.toByte() }
    private val idPriv = ByteArray(32) { (it + 32).toByte() }
    private val spkPriv = ByteArray(32) { (it + 64).toByte() }

    /** Dart 端固定 salt 封出的金库信封（生成器：tool/golden/gen_recovery_vault_vector.dart）。 */
    private val dartEnvelope = """
        {"v":1,"kdf":{"alg":"argon2id","mem_kib":19456,"iter":2,"parallel":1},"salt":"AQIDBAUGBwgJCgsMDQ4PEA==","nonce":"T1scdF5aNDc00lwe","cipher_text":"Zg9DhVx9vOgr9UbTLttL1/wNa+/UvMZ7uymjPaRMUUnFHC03RDYlw/J65ln7VYHoqwG7Y2doAymYDJ85V+f3YI2DXjNnTHDMQP9OEWWewH0aNor/Q4anjsqPII0vdWeG","mac":"pCiwQFVJMG765mgnPgxoxA=="}
    """.trimIndent()

    @Test
    fun sealOpenRoundtripRecoversKeys() {
        val codec = RecoveryVaultCodec()
        val vault = codec.sealVault(
            password = "correct-horse-battery",
            msk = msk,
            identityPriv = idPriv,
            spkPriv = spkPriv,
        )
        val keys = codec.openVault(password = "correct-horse-battery", envelope = vault)
        assertTrue(keys.msk.contentEquals(msk))
        assertTrue(keys.identityPriv.contentEquals(idPriv))
        assertTrue(keys.spkPriv.contentEquals(spkPriv))
    }

    @Test
    fun envelopeCarriesKdfParamsAndSalt() {
        val codec = RecoveryVaultCodec()
        val vault = codec.sealVault(
            password = "pw-12345678",
            msk = msk,
            identityPriv = idPriv,
            spkPriv = spkPriv,
            salt = ByteArray(16) { (it + 1).toByte() },
        )
        val text = String(vault, Charsets.UTF_8)
        assertTrue(text.contains(""""v":1"""))
        assertTrue(text.contains(""""alg":"argon2id""""))
        assertTrue(text.contains(""""mem_kib":19456"""))
        assertTrue(text.contains(""""iter":2"""))
        assertTrue(text.contains(""""parallel":1"""))
        // 固定 salt 逐字节回显（base64）
        assertTrue(text.contains(""""salt":"AQIDBAUGBwgJCgsMDQ4PEA==""""))
    }

    @Test
    fun wrongPasswordThrowsNeverSilent() {
        val codec = RecoveryVaultCodec()
        val vault = codec.sealVault(
            password = "correct-horse-battery",
            msk = msk,
            identityPriv = idPriv,
            spkPriv = spkPriv,
        )
        val e = assertFailsWith<RecoveryVaultException> {
            codec.openVault(password = "wrong-password-1", envelope = vault)
        }
        assertTrue(e.message!!.contains("密码错误或金库已损坏"))
    }

    @Test
    fun badEnvelopeOrVersionThrows() {
        val codec = RecoveryVaultCodec()
        assertFailsWith<RecoveryVaultException> {
            codec.openVault(password = "pw-12345678", envelope = "not-json".toByteArray())
        }
        assertFailsWith<RecoveryVaultException> {
            codec.openVault(
                password = "pw-12345678",
                envelope = """{"v":2,"kdf":{},"salt":"AA==","nonce":"AA==","cipher_text":"AA==","mac":"AA=="}""".toByteArray(),
            )
        }
    }

    @Test
    fun independentSaltYieldsDistinctCiphertext() {
        val codec = RecoveryVaultCodec()
        val v1 = codec.sealVault(password = "same-password", msk = msk, identityPriv = idPriv, spkPriv = spkPriv)
        val v2 = codec.sealVault(password = "same-password", msk = msk, identityPriv = idPriv, spkPriv = spkPriv)
        assertNotEquals(String(v1, Charsets.UTF_8), String(v2, Charsets.UTF_8))
    }

    @Test
    fun dartSealedEnvelopeOpensWithBouncyCastle() {
        // 跨端对拍钉：cryptography 包（Dart）Argon2id+GCM-AAD 封库，BC（Kotlin）解封
        // ——撤 BC 侧 Argon2 AD 或改参数即红（R1 静默失败风险的第一道机械闸）。
        val keys = RecoveryVaultCodec().openVault(password = "correct-horse-battery", envelope = dartEnvelope.toByteArray())
        assertTrue(keys.msk.contentEquals(msk))
        assertTrue(keys.identityPriv.contentEquals(idPriv))
        assertTrue(keys.spkPriv.contentEquals(spkPriv))
    }
}
