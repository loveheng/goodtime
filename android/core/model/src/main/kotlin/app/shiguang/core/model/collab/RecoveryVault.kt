package app.shiguang.core.model.collab

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.bouncycastle.crypto.params.Argon2Parameters
import org.bouncycastle.crypto.generators.Argon2BytesGenerator

/**
 * 换机恢复金库（lib/collab/recovery_vault.dart Kotlin 等价，schedule-app §11
 * 「Plan A v3」拍板）。
 *
 * 金库包裹钥 K_recovery 由登录密码派生（Argon2id，独立 salt + 独立 context，
 * 与服务端密码哈希完全隔离）——密码=登录凭据+金库钥唯一点，改密即重封。
 * Argon2id 用 BouncyCastle（设计稿 §依赖映射：JCA/Tink 均无；R1 行明确拍板）。
 *
 * 信封 JSON 结构（服务端纯盲整体存储，端侧自解析）：
 * ```json
 * {"v":1,"kdf":{"alg":"argon2id","mem_kib":19456,"iter":2,"parallel":1},
 *  "salt":"<b64>","nonce":"<b64>","cipher_text":"<b64>","mac":"<b64>"}
 * ```
 * 明文载荷 = MSK(32B) ‖ 身份私钥(32B) ‖ SPK 私钥(32B) 顺序拼接（各 32 字节定长）。
 */
class RecoveryVaultCodec {

    fun sealVault(
        password: String,
        msk: ByteArray,
        identityPriv: ByteArray,
        spkPriv: ByteArray,
        salt: ByteArray? = null,
        rng: SecureRandom = SecureRandom(),
    ): ByteArray {
        val s = salt ?: ByteArray(16).also(rng::nextBytes)
        val kek = derive(password, s, MEM_KIB, ITERATIONS, PARALLELISM)
        val plain = msk + identityPriv + spkPriv
        val nonce = ByteArray(12).also(rng::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(kek, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(CONTEXT.toByteArray(Charsets.UTF_8))
        val ctTag = cipher.doFinal(plain)
        val envelope = buildJsonObject {
            put("v", 1L)
            put(
                "kdf",
                buildJsonObject {
                    put("alg", "argon2id")
                    put("mem_kib", MEM_KIB)
                    put("iter", ITERATIONS)
                    put("parallel", PARALLELISM)
                },
            )
            put("salt", java.util.Base64.getEncoder().encodeToString(s))
            put("nonce", java.util.Base64.getEncoder().encodeToString(nonce))
            put("cipher_text", java.util.Base64.getEncoder().encodeToString(ctTag.copyOfRange(0, ctTag.size - 16)))
            put("mac", java.util.Base64.getEncoder().encodeToString(ctTag.copyOfRange(ctTag.size - 16, ctTag.size)))
        }
        return envelope.toString().toByteArray(Charsets.UTF_8)
    }

    /** 开库：换机冷启动用登录密码解出三段私钥。密码错/信封坏一律抛
     *  [RecoveryVaultException]（调用方 UI 必须明示「重新生成身份」后果，禁止静默半盲）。 */
    fun openVault(password: String, envelope: ByteArray): RecoveredKeys {
        val json: JsonObject = try {
            Json.parseToJsonElement(String(envelope, Charsets.UTF_8)).jsonObject
        } catch (_: Exception) {
            throw RecoveryVaultException("金库信封格式非法")
        }
        val v = json["v"]?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.content?.toLongOrNull()
        if (v != 1L) {
            throw RecoveryVaultException("金库信封版本不支持")
        }
        val kdf = json["kdf"] as? JsonObject
        val saltB64 = strOrNull(json["salt"])
        val nonceB64 = strOrNull(json["nonce"])
        val cipherB64 = strOrNull(json["cipher_text"])
        val macB64 = strOrNull(json["mac"])
        if (kdf == null || saltB64 == null || nonceB64 == null || cipherB64 == null || macB64 == null) {
            throw RecoveryVaultException("金库信封字段缺失")
        }
        if (strOrNull(kdf["alg"]) != "argon2id") {
            throw RecoveryVaultException("KDF 算法不支持")
        }
        val salt = try {
            java.util.Base64.getDecoder().decode(saltB64)
        } catch (_: Exception) {
            throw RecoveryVaultException("金库信封 base64 非法")
        }
        val kek = try {
            derive(
                password,
                salt,
                memory = kdf["mem_kib"]?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.content?.toLongOrNull()?.toInt() ?: MEM_KIB,
                iterations = kdf["iter"]?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.content?.toLongOrNull()?.toInt() ?: ITERATIONS,
                parallelism = kdf["parallel"]?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.content?.toLongOrNull()?.toInt() ?: PARALLELISM,
            )
        } catch (e: RecoveryVaultException) {
            throw e
        } catch (_: Exception) {
            throw RecoveryVaultException("KDF 算法不支持")
        }
        val plain = try {
            val box = javax.crypto.spec.IvParameterSpec(java.util.Base64.getDecoder().decode(nonceB64))
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(kek, "AES"), GCMParameterSpec(128, box.iv))
            c.updateAAD(CONTEXT.toByteArray(Charsets.UTF_8))
            c.doFinal(java.util.Base64.getDecoder().decode(cipherB64) +
                java.util.Base64.getDecoder().decode(macB64))
        } catch (_: Exception) {
            throw RecoveryVaultException("密码错误或金库已损坏")
        }
        if (plain.size != 96) {
            throw RecoveryVaultException("金库载荷长度异常")
        }
        return RecoveredKeys(
            msk = plain.copyOfRange(0, 32),
            identityPriv = plain.copyOfRange(32, 64),
            spkPriv = plain.copyOfRange(64, 96),
        )
    }

    private fun derive(
        password: String,
        salt: ByteArray,
        memory: Int,
        iterations: Int,
        parallelism: Int,
    ): ByteArray {
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withSalt(salt)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withIterations(iterations)
            .withMemoryAsKB(memory)
            .withParallelism(parallelism)
            // 独立 context：与任何其他 Argon2 用途（含服务端密码哈希）隔离
            .withAdditional(CONTEXT.toByteArray(Charsets.UTF_8))
            .build()
        val gen = Argon2BytesGenerator()
        gen.init(params)
        val out = ByteArray(KEY_LEN)
        gen.generateBytes(password.toByteArray(Charsets.UTF_8), out, 0, out.size)
        return out
    }

    companion object {
        private const val CONTEXT = "shiguang.recovery.v1"

        // Argon2id 参数：OWASP 推荐 ≥19MiB 内存、2 迭代（t=2, p=1）。
        const val MEM_KIB = 19456
        const val ITERATIONS = 2
        const val PARALLELISM = 1
        const val KEY_LEN = 32

        private fun strOrNull(e: kotlinx.serialization.json.JsonElement?): String? =
            (e as? kotlinx.serialization.json.JsonPrimitive)?.content
    }
}

/** 开库成功取回的三段私钥。 */
data class RecoveredKeys(
    val msk: ByteArray,
    val identityPriv: ByteArray,
    val spkPriv: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is RecoveredKeys && msk.contentEquals(other.msk) &&
            identityPriv.contentEquals(other.identityPriv) && spkPriv.contentEquals(other.spkPriv)

    override fun hashCode(): Int = msk.contentHashCode()
}

/** 金库解封失败（密码错误 / 信封损坏 / 版本不支持）。 */
class RecoveryVaultException(message: String) : Exception("RecoveryVaultException: $message")
