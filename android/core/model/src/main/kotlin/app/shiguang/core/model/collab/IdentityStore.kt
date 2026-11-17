package app.shiguang.core.model.collab

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/**
 * 身份材料的本地落点（lib/collab/identity_store.dart Kotlin 等价，§5.3：
 * 明文密钥只驻内存/安全区，绝不落 SP/SQLite）。
 *
 * 注册成功后把「身份私钥（由 MSK 封存）」+ SPK 公钥 + 服务端回执的剩余 OPK 数
 * 入账，供后续登录态签名、低水位静默补仓（§1.5 心跳）、换机还原复用。
 */
class IdentityRecord(
    val userId: String,
    val sealedIdentityPrivateKey: ByteArray, // MSK 封存的 Ed25519 私钥
    val signedPreKeyPub: ByteArray, // X25519 raw 32B，对外广播用
    val opkRemaining: Int = 0,
    val sealedSignedPreKeyPrivate: ByteArray? = null,
) {
    /** MSK 封存的 **X25519 SPK 私钥**——被邀端解开 K_bundle 信封的唯一钥匙。
     *  可空：存量记录缺失即「解不开历史邀约」，按半盲纪律降级为不可解密。 */

    fun toJson(): JsonObject = buildJsonObject {
        put("user_id", userId)
        put("sealed_identity_private_key", b64Encode(sealedIdentityPrivateKey))
        put("signed_prekey_pub", b64Encode(signedPreKeyPub))
        put("opk_remaining", opkRemaining)
        if (sealedSignedPreKeyPrivate != null) {
            put("sealed_signed_prekey_priv", b64Encode(sealedSignedPreKeyPrivate))
        }
    }

    fun encode(): String = toJson().toString()

    companion object {
        fun fromJson(j: JsonObject): IdentityRecord = IdentityRecord(
            userId = j["user_id"]!!.jsonPrimitive.content,
            sealedIdentityPrivateKey = b64Decode(j["sealed_identity_private_key"]!!.jsonPrimitive.content),
            signedPreKeyPub = b64Decode(j["signed_prekey_pub"]!!.jsonPrimitive.content),
            opkRemaining = if (j.containsKey("opk_remaining")) j["opk_remaining"]!!.jsonPrimitive.long.toInt() else 0,
            sealedSignedPreKeyPrivate =
                (j["sealed_signed_prekey_priv"] as? kotlinx.serialization.json.JsonPrimitive)
                    ?.let { b64Decode(it.content) },
        )

        fun decode(raw: String): IdentityRecord = fromJson(Json.parseToJsonElement(raw).jsonObject)
    }
}

/** 身份记录存储抽象（生产=设备安全区，:app 注入；测试=[MemoryIdentityStore]）。 */
interface IdentityStore {
    suspend fun load(): IdentityRecord?
    suspend fun save(record: IdentityRecord)
    suspend fun clear()
}

/** 内存实现（测试用）。 */
class MemoryIdentityStore : IdentityStore {
    private var rec: IdentityRecord? = null
    override suspend fun load(): IdentityRecord? = rec
    override suspend fun save(record: IdentityRecord) {
        rec = record
    }
    override suspend fun clear() {
        rec = null
    }
}
