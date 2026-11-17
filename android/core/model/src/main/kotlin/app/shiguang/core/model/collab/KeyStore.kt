package app.shiguang.core.model.collab

import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * MSK（Master Secret Key）托管（lib/collab/crypto/key_store.dart Kotlin 等价，
 * 协同设计 §5.3，2026-10-11 拍板改版）。
 *
 * 账号体系（Plan A）门禁统一为**登录态**：MSK 由端侧自动托管——首次用到时生成
 * 256 位随机密钥，明文只存在于「设备安全区（Keystore）+ 内存」两种形态，对用户
 * 全程无感。业务数据（身份私钥 / K_bundle 信封）一律经 [seal]/[open] 用 MSK 封存，
 * 「明文密钥永不落 SQLite/SharedPreferences」纪律不变。
 *
 * 迁移窗例外（D11 C 案 2026-11-15 拍板）：P5 切包不迁 Dart 侧 flutter_secure_storage
 * 存量——MSK 重生成、协同面重置（重登录/重注册），见 native-migration 决策日志。
 */
class KeyStore(
    private val store: SecretStore,
    private val rng: SecureRandom = SecureRandom(),
) {

    private val mskMutex = Mutex()
    private var msk: ByteArray? = null

    /** 已就绪的 MSK（未就绪返回 null）。调用方**不得**长期持有副本。 */
    val mskBytes: ByteArray? get() = msk?.copyOf()

    /** MSK 是否已在内存（语义=「密钥已就绪」）。 */
    val isUnlocked: Boolean get() = msk != null

    /**
     * 确保 MSK 就绪（幂等；并发调用合并为一次生成）。
     * 安全区已有 → 直接载入；没有 → 生成新 MSK 并落安全区。
     * 失败（写安全区异常）时 msk 保持 null，下次调用重试。
     */
    suspend fun ensureReady() {
        if (msk != null) return
        mskMutex.withLock {
            if (msk != null) return // 并发合并：另一调用方已就绪
            val raw = store.read(MSK_KEY)
            if (!raw.isNullOrEmpty()) {
                msk = Base64.getDecoder().decode(raw)
                return
            }
            val generated = ByteArray(32).also(rng::nextBytes)
            store.write(MSK_KEY, Base64.getEncoder().encodeToString(generated))
            msk = generated
        }
    }

    /** 换机恢复专用：用金库解出的 MSK 接管本机安全区（覆盖旧值并置内存就绪）。 */
    suspend fun importMsk(msk: ByteArray) {
        store.write(MSK_KEY, Base64.getEncoder().encodeToString(msk))
        this.msk = msk.copyOf()
    }

    /** 用 MSK 加密业务数据（身份私钥 / K_bundle 信封）。未就绪即抛，调用方先 [ensureReady]。 */
    suspend fun seal(data: ByteArray): ByteArray {
        val m = requireMsk()
        val nonce = ByteArray(12).also(rng::nextBytes)
        val (ct, mac) = aesGcmSeal(m, nonce, data)
        return SealedBlob(nonce, ct, mac).encode().toByteArray(Charsets.UTF_8)
    }

    /** 解封业务数据（别的 MSK 解 → GCM MAC 校验失败抛出）。 */
    suspend fun open(sealed: ByteArray): ByteArray {
        val m = requireMsk()
        val blob = SealedBlob.decode(String(sealed, Charsets.UTF_8))
        return aesGcmOpen(m, blob.nonce, blob.cipherText, blob.mac)
    }

    private fun requireMsk(): ByteArray =
        msk ?: throw IllegalStateException("MSK 未就绪：先 await keyStore.ensureReady()")

    companion object {
        const val MSK_KEY = "collab.msk.raw"
    }
}
