package app.shiguang.core.model.collab

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 加密封装单点（lib/collab/sealing.dart Kotlin 等价，协同设计 §9.2）。
 *
 * 一切落网明文都经此一处出口，统一三件事：
 * 1. **定长填充**：PKCS#7 补到 512B 整数倍——「好」与小作文密文等长，杜绝长度侧信道；
 *    经典 PKCS#7 填充字节只能表达 1..255，故用 2 字节大端长度尾注承载真实 pad 量
 *    （1..512），填充字节本身取 pad 低 8 位仅作占位——解码只读尾注，绝不歧义；
 * 2. **AES-256-GCM**：12B 随机 nonce 每次不复用；16B tag 不入 cipher_text（随 mac 走）；
 * 3. **key_ver 标注**：密文自带所用 K_bundle 版本，回放端按版本取密钥（见 KeyRing）。
 */
class SealedPayload(
    val keyVer: Int,
    val nonce: ByteArray, // 12B
    val cipherText: ByteArray,
    val mac: ByteArray,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("key_ver", keyVer)
        put("nonce", b64Encode(nonce))
        put("cipher_text", b64Encode(cipherText))
        put("mac", b64Encode(mac))
    }

    fun encode(): String = toJson().toString()

    companion object {
        fun fromJson(j: JsonObject): SealedPayload = SealedPayload(
            keyVer = j["key_ver"]!!.jsonPrimitive.long.toInt(),
            nonce = b64Decode(j["nonce"]!!.jsonPrimitive.content),
            cipherText = b64Decode(j["cipher_text"]!!.jsonPrimitive.content),
            mac = b64Decode(j["mac"]!!.jsonPrimitive.content),
        )

        fun decode(raw: String): SealedPayload = fromJson(Json.parseToJsonElement(raw).jsonObject)
    }
}

internal fun b64Encode(b: ByteArray): String = Base64.getEncoder().encodeToString(b)

internal fun b64Decode(s: String): ByteArray = Base64.getDecoder().decode(s)

/** 定长填充到 512B 整数倍（抗长度侧信道）：data ‖ pad×(pad&0xff) ‖ BE16(pad)，pad=1..512。 */
internal fun pkcs7Pad512(data: ByteArray, blockSize: Int = 512): ByteArray {
    val pad = blockSize - (data.size % blockSize)
    val out = ByteArray(data.size + pad + 2)
    data.copyInto(out)
    for (i in 0 until pad) out[data.size + i] = (pad and 0xff).toByte()
    out[out.size - 2] = ((pad shr 8) and 0xff).toByte()
    out[out.size - 1] = (pad and 0xff).toByte()
    return out
}

/** 去填充：只读 2 字节大端尾注（长度非法即抛——密文被篡改或口径失配）。 */
internal fun pkcs7Unpad512(data: ByteArray, blockSize: Int = 512): ByteArray {
    if (data.size < 2) throw IllegalArgumentException("PKCS#7：数据过短")
    val pad = ((data[data.size - 2].toInt() and 0xff) shl 8) or (data[data.size - 1].toInt() and 0xff)
    if (pad < 1 || pad > blockSize) throw IllegalArgumentException("PKCS#7：填充长度非法")
    return data.copyOfRange(0, data.size - pad - 2)
}

/** AES-256-GCM 原语（无 AAD；tag 16B 不入 cipher_text——Dart 口径逐字）。 */
internal fun aesGcmSeal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray): Pair<ByteArray, ByteArray> {
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
    val ctTag = cipher.doFinal(plaintext)
    return Pair(
        ctTag.copyOfRange(0, ctTag.size - 16),
        ctTag.copyOfRange(ctTag.size - 16, ctTag.size),
    )
}

/** AES-256-GCM 开封（MAC 校验失败 → AEADBadTagException）。 */
internal fun aesGcmOpen(key: ByteArray, nonce: ByteArray, cipherText: ByteArray, mac: ByteArray): ByteArray {
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
    return cipher.doFinal(cipherText + mac)
}

/**
 * 加密：明文 → 定长填充 → AES-256-GCM。返回自描述信封（含 key_ver）。
 * [rng] 可注入（向量对拍钉 nonce；生产默认 SecureRandom）。
 */
fun sealPayload(
    key: ByteArray,
    plaintext: ByteArray,
    keyVer: Int,
    rng: SecureRandom = SecureRandom(),
): SealedPayload {
    if (key.size != 32) throw IllegalArgumentException("K_bundle 密钥须为 32 字节")
    val padded = pkcs7Pad512(plaintext)
    val nonce = ByteArray(12).also(rng::nextBytes)
    val (ct, mac) = aesGcmSeal(key, nonce, padded)
    return SealedPayload(keyVer, nonce, ct, mac)
}

/** 解密：AES-256-GCM → 去填充 → 明文。密钥错会抛（GCM MAC 校验失败）。 */
fun openPayload(key: ByteArray, p: SealedPayload): ByteArray {
    if (key.size != 32) throw IllegalArgumentException("K_bundle 密钥须为 32 字节")
    return pkcs7Unpad512(aesGcmOpen(key, p.nonce, p.cipherText, p.mac))
}
