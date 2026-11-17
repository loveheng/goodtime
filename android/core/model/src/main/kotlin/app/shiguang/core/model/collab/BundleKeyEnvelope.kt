package app.shiguang.core.model.collab

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.security.SecureRandom

/**
 * K_bundle 分发信封互逆件（lib/collab/propose.dart 的密钥信封切片 Kotlin 等价，
 * §5.3 K_bundle 分发）。
 *
 * 发起端把 K_bundle 用「一次性 EK × 被邀人 SPK 公钥」封成信封交服务端盲存；
 * 被邀端用本地 SPK 私钥解开（收件侧见 [BundleKeyIntake]）。本切片由
 * wrap/open 互逆自证（collab_enroll_test 同款锚）。
 */

/** CSPRNG 生成 K_bundle（32B = AES-256-GCM 密钥长度）。 */
fun generateBundleKey(rng: SecureRandom = SecureRandom()): ByteArray =
    ByteArray(32).also(rng::nextBytes)

/**
 * 给一位成员封 K_bundle：一次性 EK × 该成员 SPK 公钥 DH → AES-256-GCM。
 * 返回服务端 member_key_envelopes[] 的一行形状——relay 原样盲存。
 */
fun wrapBundleKeyForMember(
    bundleKey: ByteArray,
    keyVer: Int,
    recipientUserId: String,
    memberSpkPub: ByteArray,
    rng: SecureRandom = SecureRandom(),
): JsonObject {
    if (memberSpkPub.size != 32) {
        throw IllegalArgumentException("成员 SPK 公钥必须是 X25519 raw 32B（坐标系铁律）")
    }
    val ekSeed = ByteArray(32).also(rng::nextBytes)
    val sessionKey = X25519Ops.dh(ekSeed, memberSpkPub)
    val sealed = sealPayload(sessionKey, bundleKey, keyVer, rng)
    val wrapped = buildJsonObject {
        put("ek_pub", b64Encode(X25519Ops.publicKeyFromSeed(ekSeed)))
        put("sealed", sealed.encode())
    }
    return buildJsonObject {
        put("recipient_user_id", recipientUserId)
        put("wrapped_key", b64Encode(wrapped.toString().toByteArray(Charsets.UTF_8)))
        put("key_ver", keyVer)
    }
}

/** 成员端开信封（自身 SPK 私钥 × 信封里的 EK 公钥）：拿到明文 K_bundle。 */
fun openBundleKeyEnvelope(envelope: JsonObject, mySpkSeed: ByteArray): ByteArray {
    val wrapped = Json.parseToJsonElement(
        String(b64Decode(envelope["wrapped_key"]!!.jsonPrimitive.content), Charsets.UTF_8),
    ).jsonObject
    val sessionKey = X25519Ops.dh(mySpkSeed, b64Decode(wrapped["ek_pub"]!!.jsonPrimitive.content))
    val sealed = SealedPayload.decode(wrapped["sealed"]!!.jsonPrimitive.content)
    return openPayload(sessionKey, sealed)
}

/** relay keys_api 的信封行视图（GET /v1/bundles/{id}/key-envelopes 的一行）。 */
class KeyEnvelopeView(
    val keyVer: Int,
    val wrappedKey: String, // base64（内容由发起端自描述：{ek_pub, sealed}）
    val createdAtSec: Int = 0,
) {
    /** 喂给 [openBundleKeyEnvelope] 的形状（键名与发起端封包一致）。 */
    fun toEnvelope(): JsonObject = buildJsonObject {
        put("wrapped_key", wrappedKey)
        put("key_ver", keyVer)
    }

    companion object {
        fun fromJson(j: JsonObject): KeyEnvelopeView = KeyEnvelopeView(
            keyVer = if (j.containsKey("key_ver")) j["key_ver"]!!.jsonPrimitive.long.toInt() else 1,
            wrappedKey = j["wrapped_key"]?.jsonPrimitive?.contentOrNull ?: "",
            createdAtSec = if (j.containsKey("created_at")) j["created_at"]!!.jsonPrimitive.long.toInt() else 0,
        )
    }
}
