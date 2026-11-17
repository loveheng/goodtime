package app.shiguang.core.model.collab

import kotlinx.coroutines.test.runTest
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 身份材料与 PoW 行为测试（§1.5 / fact §7.1-2）：
 * 坐标系分离（Ed25519 签名钥 / X25519 加密钥）+ OPK 首批刻度 + DH 对称 + hashcash。
 */
class CollabIdentityPowTest {

    // ---- 身份材料（identity.dart 行为面）------------------------------------------

    @Test
    fun `OPK 首批刻度 10 20 合法 越界拒`() {
        assertEquals(10, generateIdentity(opkCount = 10).oneTimePreKeys.size)
        assertEquals(20, generateIdentity().oneTimePreKeys.size)
        assertFailsWith<IllegalArgumentException> { generateIdentity(opkCount = 9) }
        assertFailsWith<IllegalArgumentException> { generateIdentity(opkCount = 21) }
    }

    @Test
    fun `身份材料形状 raw 32B 与签名可验`() {
        val id = generateIdentity(opkCount = 12)
        assertEquals(32, id.identityPublicKey.size)
        assertEquals(32, id.signedPreKeyPublic.size)
        // signed_prekey_sig = 身份私钥对 SPK 公钥的 Ed25519 签名（服务端兜底验签同式）
        assertTrue(
            ed25519Verify(id.identityPublicKey, id.signedPreKeyPublic, id.signedPreKeySignature()),
            "SPK 签名须可由身份公钥验证",
        )
        // OPK 各带身份钥签名
        for (opk in id.oneTimePreKeys) {
            assertTrue(ed25519Verify(id.identityPublicKey, opk.pub, opk.signature))
            assertEquals(32, opk.pub.size)
            assertTrue(opk.keyId.startsWith("opk-"))
        }
        // 坐标系铁律：SPK/OPK 公钥互不相同（一次性钥不可复用）
        val pubs = id.oneTimePreKeys.map { it.pub.toList() }.toSet()
        assertEquals(id.oneTimePreKeys.size, pubs.size)
    }

    @Test
    fun `ed25519 全链 BC 签名验签闭环与确定性`() {
        // 回归钉（2026-11-16 Conscrypt 崩修复）：Ed25519 签名/验签/种子→公钥
        // 全链 BC 低阶承载（provider 无关）——私钥种子签、公钥验、派生确定性。
        val rng = SecureRandom()
        repeat(4) {
            val seed = ByteArray(32).also(rng::nextBytes)
            val pub = ed25519PubFromSeed(seed)
            val msg = "BC 闭环探针 $it".toByteArray()
            assertTrue(pub.contentEquals(ed25519PubFromSeed(seed)), "种子→公钥派生确定性")
            assertTrue(ed25519Verify(pub, msg, ed25519Sign(seed, msg)), "签名须可验")
            assertTrue(!ed25519Verify(pub, msg, msg.copyOf(64)), "篡改签名须拒")
        }
    }

    @Test
    fun `agreeSessionKey X25519 DH 对称 A私B公 与 B私A公 同钥`() {
        val rng = SecureRandom()
        val aSeed = ByteArray(32).also(rng::nextBytes)
        val bSeed = ByteArray(32).also(rng::nextBytes)
        val k1 = agreeSessionKey(aSeed, X25519Ops.publicKeyFromSeed(bSeed))
        val k2 = agreeSessionKey(bSeed, X25519Ops.publicKeyFromSeed(aSeed))
        assertEquals(32, k1.size)
        assertTrue(k1.contentEquals(k2), "DH 对称：双机同钥")
        // 与向量口径一致：裸 DH 输出即 AES-256-GCM 密钥
        val (ct, mac) = aesGcmSeal(k1, ByteArray(12) { 1 }, "hello".toByteArray())
        assertTrue("hello".toByteArray().contentEquals(aesGcmOpen(k2, ByteArray(12) { 1 }, ct, mac)))
    }

    // ---- Hashcash PoW（pow.dart）---------------------------------------------------

    @Test
    fun `verifyPoW 难度 0 恒过 前导零判定`() {
        assertTrue(verifyPoW("chal", "any", 0))
        // 难度 1：需要散列以 0 开头——爆破一枚即证口径
        val solved = solvePoW("probe-challenge", 1)
        assertTrue(verifyPoW("probe-challenge", solved, 1))
        // solve 与 verify 同口径：低难度解出即过
        val s4 = solvePoW("pow-x", 2)
        assertTrue(verifyPoW("pow-x", s4, 2))
        assertFalse(verifyPoW("pow-y", s4, 2), "challenge 参与散列——换 challenge 即废")
    }

    @Test
    fun `solvePoW 拼接口径 solution = i-challenge`() {
        // 口径钉样：与 verify 同一拼接口径——solve 返回值=首个满足难度的 "i-challenge"
        val solved = solvePoW("calc", 1)
        assertTrue(Regex("^\\d+-calc$").matches(solved), "拼接口径：solution = i-challenge")
        assertTrue(verifyPoW("calc", solved, 1), "solve 产出必过同口径 verify")
        // 首枚性：更小的 i 都不满足（solve 从 0 递增）
        val firstI = solved.substringBefore('-').toInt()
        assertTrue((0 until firstI).none { verifyPoW("calc", "$it-calc", 1) })
    }
}
