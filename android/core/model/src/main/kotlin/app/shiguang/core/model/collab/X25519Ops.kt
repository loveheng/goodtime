package app.shiguang.core.model.collab

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters

/**
 * X25519 原语单点（RFC 7748；BouncyCastle 低阶承载，provider 无关）。
 *
 * 坐标系铁律（协同设计 §3）：X3DH 的 DH 运算只用 X25519 加密钥；裸 DH 输出
 * 即对称密钥（无 KDF——与 freemask ECIES / session.dart 口径一致）。
 * 向量锚 = RFC 7748 测试向量（e2ee_freemask x25519_* case 逐字节对拍）。
 *
 * BC 化缘由（2026-11-16 播种器真机首走实证）：JDK XDH KeyFactory 在 Android
 * Conscrypt 上拒 XECPrivateKeySpec/XECPublicKeySpec（JVM 桌面 JDK 绿 / 真机
 * 崩）——与 Ed25519 同族边界，严禁让 JCA provider 参与坐标还原。
 */
object X25519Ops {

    /** 公钥派生：pub = X25519(seed, base=9)（= newKeyPairFromSeed 的公钥）。 */
    fun publicKeyFromSeed(seed: ByteArray): ByteArray =
        X25519PrivateKeyParameters(seed, 0).generatePublicKey().encoded

    /** 裸 DH（X25519(privSeed, peerPub)，输出 32B，无 KDF）。 */
    fun dh(privSeed: ByteArray, peerPubRaw: ByteArray): ByteArray {
        val agreement = X25519Agreement()
        agreement.init(X25519PrivateKeyParameters(privSeed, 0))
        val out = ByteArray(agreement.agreementSize)
        agreement.calculateAgreement(X25519PublicKeyParameters(peerPubRaw, 0), out, 0)
        return out
    }
}
