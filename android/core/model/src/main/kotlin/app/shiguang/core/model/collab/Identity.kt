package app.shiguang.core.model.collab

import java.security.SecureRandom

/**
 * 身份密钥材料（lib/collab/identity.dart Kotlin 等价，协同设计 §1.5 / §5.3）。
 *
 * 坐标系严格分离（设计稿 §3 铁律）：
 * - **签名钥** identityKey = Ed25519（身份所有权证明 PoP、OPK/SPK 签名）；
 * - **加密钥** signedPreKey / OPK = X25519（X3DH Diffie-Hellman）。
 * 二者绝不分用——跨坐标系直用必炸，故在此一次性生成并冻结类型。
 *
 * Ed25519 全链 BouncyCastle 低阶承载（种子↔公钥↔签名/验签 provider 无关）：
 * Android Conscrypt 的 KeyFactory 拒 EdECPublicKeySpec、私钥类不实现
 * EdECPrivateKey（JVM 桌面 JDK 绿 / 真机崩，2026-11-16 播种器真机首走实证）——
 * 严禁让 JCA provider 参与 Ed25519 生成与还原，私钥携带物一律 raw seed。
 */
class PreKey(
    val keyId: String,
    val pub: ByteArray, // X25519 raw 32B
    val signature: ByteArray, // Ed25519 sig over pub，由身份私钥签署
)

class IdentityMaterial(
    /** Ed25519 私钥 raw 种子（32B）——Dart SimpleKeyPair.extractPrivateKeyBytes 镜像；
     *  IdentityRecord.sealedIdentityPrivateKey / recovery_vault 金库存的就是这段
     *  （换机恢复=种子还原），公钥由种子派生。 */
    val identityKeySeed: ByteArray,
    val signedPreKey: ByteArray, // X25519 私钥 seed（32B）
    val oneTimePreKeys: List<PreKey>, // X25519 首批 10~20
) {
    /** Ed25519 身份公钥（raw 32B）。 */
    val identityPublicKey: ByteArray by lazy { ed25519PubFromSeed(identityKeySeed) }

    /** X25519 SPK 公钥（raw 32B）。 */
    val signedPreKeyPublic: ByteArray by lazy { X25519Ops.publicKeyFromSeed(signedPreKey) }

    /** 服务端要的 signed_prekey_sig：用身份私钥对 SPK 公钥做 Ed25519 签名。 */
    fun signedPreKeySignature(): ByteArray =
        ed25519Sign(identityKeySeed, signedPreKeyPublic)
}

/** 生成一整套身份材料：Ed25519 身份钥 + X25519 SPK + N 个 OPK（各带身份钥签名）。 */
fun generateIdentity(opkCount: Int = 20, rng: SecureRandom = SecureRandom()): IdentityMaterial {
    if (opkCount < 10 || opkCount > 20) {
        throw IllegalArgumentException("OPK 首批数量必须 ∈ [10, 20]（防刷号瘦身，§1.5）")
    }
    // 种子驱动生成（与 X25519Ops 同款口径）：本地出 32B seed，公钥由种子派生。
    val identityKeySeed = ByteArray(32).also(rng::nextBytes)
    val signedPreKey = ByteArray(32).also(rng::nextBytes)
    val opks = (0 until opkCount).map { i ->
        val seed = ByteArray(32).also(rng::nextBytes)
        val pub = X25519Ops.publicKeyFromSeed(seed)
        val sig = ed25519Sign(identityKeySeed, pub)
        PreKey("opk-$i", pub, sig)
    }
    return IdentityMaterial(identityKeySeed, signedPreKey, opks)
}

/** Ed25519 签名（raw 私钥种子 → 消息；BouncyCastle 低阶，provider 无关）。 */
fun ed25519Sign(seed: ByteArray, message: ByteArray): ByteArray {
    val signer = org.bouncycastle.crypto.signers.Ed25519Signer()
    signer.init(true, org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters(seed, 0))
    signer.update(message, 0, message.size)
    return signer.generateSignature()
}

/** Ed25519 验签（raw 32B 公钥 + 消息 + 签名；BouncyCastle 低阶，provider 无关）。 */
fun ed25519Verify(publicKeyRaw: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
    val signer = org.bouncycastle.crypto.signers.Ed25519Signer()
    signer.init(false, org.bouncycastle.crypto.params.Ed25519PublicKeyParameters(publicKeyRaw, 0))
    signer.update(message, 0, message.size)
    return signer.verifySignature(signature)
}

/**
 * 最小 X3DH 风格会话密钥协商（lib/collab/crypto/session.dart Kotlin 等价）。
 * 坐标系铁律：DH 运算只用 X25519 加密钥（SPK↔SPK 单 DH 会话种子）。
 * 因 X25519 DH 对称，agreeSessionKey(A私,B公) 与 agreeSessionKey(B私,A公)
 * 得到同一把 32B 密钥，双机可直接对称加解密。
 */
fun agreeSessionKey(mySpkSeed: ByteArray, peerSpkPub: ByteArray): ByteArray =
    X25519Ops.dh(mySpkSeed, peerSpkPub)

/** Ed25519 raw 32B 种子 → raw 32B 公钥（BC 纯派生；种子驱动生成、
 *  换机恢复后公钥重建、对端公钥推导共用同一实现）。 */
fun ed25519PubFromSeed(seed: ByteArray): ByteArray =
    org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters(seed, 0)
        .generatePublicKey().encoded
