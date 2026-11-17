package app.shiguang.core.model.collab

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 密钥材料的持久化通道抽象（lib/collab/crypto/secret_store.dart Kotlin 等价，
 * 协同设计 §5.3）。生产实现走设备安全区（Keystore，:app 注入）；测试注入
 * [MemorySecretStore]，无需平台即可验证密码学逻辑。
 */
interface SecretStore {
    suspend fun read(key: String): String?
    suspend fun write(key: String, value: String)
    suspend fun delete(key: String)
}

/** 内存实现（测试用）：进程退出即消失，绝不用于生产。 */
class MemorySecretStore : SecretStore {
    private val map = HashMap<String, String>()
    override suspend fun read(key: String): String? = map[key]
    override suspend fun write(key: String, value: String) {
        map[key] = value
    }
    override suspend fun delete(key: String) {
        map.remove(key)
    }
}

/**
 * 密文封装的落盘形态：JSON（nonce/cipherText/mac 三段 base64）。
 *
 * 不用隐式拼接——三段显式命名，将来换算法/换 mac 长度时旧数据仍可识别，
 * 不会被当成一团字节猜。
 */
class SealedBlob(
    val nonce: ByteArray,
    val cipherText: ByteArray,
    val mac: ByteArray,
) {
    fun toJson(): kotlinx.serialization.json.JsonObject = buildJsonObject {
        put("nonce", b64Encode(nonce))
        put("cipherText", b64Encode(cipherText))
        put("mac", b64Encode(mac))
    }

    fun encode(): String = toJson().toString()

    companion object {
        fun decode(raw: String): SealedBlob {
            val map = Json.parseToJsonElement(raw).jsonObject
            return SealedBlob(
                nonce = b64Decode(map["nonce"]!!.jsonPrimitive.content),
                cipherText = b64Decode(map["cipherText"]!!.jsonPrimitive.content),
                mac = b64Decode(map["mac"]!!.jsonPrimitive.content),
            )
        }
    }
}
