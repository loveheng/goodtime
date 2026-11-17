package app.shiguang.core.model.collab

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.security.SecureRandom

/**
 * FreeMask 传输协议（lib/collab/freemask.dart Kotlin 等价，协同设计 §3.6
 * 「空白探测分流」评审三约束，端侧实现）。
 *
 * 作息泄漏防线，定稿三约束逐条落地：
 * 1. **靶向范围**：探测只允许针对具体提议候选区间——函数签名强制收
 *    targetStart/targetEnd，API 形状上就不可能发起整周探测；
 * 2. **粗粒度量化桶**：区间按 30 分钟切片生成二进制掩码串（1=有空）；
 * 3. **单向加密**：成员一次性 EK × 发起人 SPK 公钥做 DH（裸输出无 KDF）得对称密钥
 *    AES-256-GCM 加密；发起人端用自身 SPK 私钥 × EK 公钥解（X25519 DH 对称）。
 */
const val FREE_MASK_BUCKET_MS = 30 * 60 * 1000L

/** 掩码信号（解密后的明文形态）。 */
class FreeMaskSignal(
    val targetStartMs: Long,
    val targetEndMs: Long,
    val mask: String, // '01110001'，1=有空；长度 = 桶数
) {
    /** 紧凑序列化（字段序 target_start,target_end,mask——interop 契约，零空白）。 */
    fun toJson(): JsonObject = buildJsonObject {
        put("target_start", targetStartMs)
        put("target_end", targetEndMs)
        put("mask", mask)
    }

    companion object {
        fun fromJson(j: JsonObject): FreeMaskSignal = FreeMaskSignal(
            targetStartMs = j["target_start"]!!.jsonPrimitive.long,
            targetEndMs = j["target_end"]!!.jsonPrimitive.long,
            mask = j["mask"]!!.jsonPrimitive.content,
        )
    }
}

/**
 * 成员本地算 FreeMask（§3.6）：目标区间切 30min 桶，被任一 busy 区间污染的桶记 0
 * （占用），其余 1（有空）。**半开区间语义**：busy [10:00,11:00) 不污染
 * [09:30,10:00) 桶——判据 busyStart < bucketEnd && bucketStart < busyEnd。
 * 输入只放 HARD_TENTATIVE 与实体日程；SOFT_CANDIDATE 弱占位由调用方排除。
 */
fun quantizeFreeMask(
    targetStartMs: Long,
    targetEndMs: Long,
    busy: List<Pair<Long, Long>>,
): String {
    if (targetEndMs <= targetStartMs) {
        throw IllegalArgumentException("靶向区间必须 start < end（§3.6 约束①）")
    }
    val buckets = (targetEndMs - targetStartMs + FREE_MASK_BUCKET_MS - 1) / FREE_MASK_BUCKET_MS
    val out = StringBuilder()
    for (i in 0 until buckets) {
        val bucketStart = targetStartMs + i * FREE_MASK_BUCKET_MS
        val bucketEnd = bucketStart + FREE_MASK_BUCKET_MS
        val occupied = busy.any { it.first < bucketEnd && bucketStart < it.second }
        out.append(if (occupied) '0' else '1')
    }
    return out.toString()
}

/**
 * 发起人端全员重叠区：按位与（BITWISE AND，§3.6 约束③）——「全员共振条」数据源。
 * 掩码必须同区间同长（长度不齐即调用方 bug，防御性抛错）。
 */
fun freeMaskBitwiseAnd(masks: List<String>): String {
    if (masks.isEmpty()) throw IllegalArgumentException("至少需要一份掩码")
    val len = masks.first().length
    if (masks.any { it.length != len }) {
        throw IllegalArgumentException("掩码长度不一致：靶向区间必须统一")
    }
    val out = StringBuilder()
    for (i in 0 until len) {
        out.append(if (masks.all { it[i] == '1' }) '1' else '0')
    }
    return out.toString()
}

/**
 * 成员端封掩码：一次性 EK（[ekSeed] 派生）× 发起人 SPK 公钥做 DH → AES-256-GCM。
 * 返回信封（ek_pub/nonce/cipher_text/mac 均 base64），经临时信箱透传。
 * [rng] 可注入（向量对拍钉 nonce；生产默认 SecureRandom）。
 */
fun sealFreeMaskForInitiator(
    signal: FreeMaskSignal,
    ekSeed: ByteArray,
    initiatorSpkPub: ByteArray,
    rng: SecureRandom = SecureRandom(),
): JsonObject {
    val key = X25519Ops.dh(ekSeed, initiatorSpkPub)
    val nonce = ByteArray(12).also(rng::nextBytes)
    val plaintext = signal.toJson().toString().toByteArray(Charsets.UTF_8)
    val (ct, mac) = aesGcmSeal(key, nonce, plaintext)
    return buildJsonObject {
        put("ek_pub", b64Encode(X25519Ops.publicKeyFromSeed(ekSeed)))
        put("nonce", b64Encode(nonce))
        put("cipher_text", b64Encode(ct))
        put("mac", b64Encode(mac))
    }
}

/**
 * 发起人端开掩码：自身 SPK 私钥（[initiatorSpkSeed] 派生）× 成员 EK 公钥（DH 对称
 * → 同一把密钥）。密钥不对（非本包发起人/篡改）→ GCM MAC 校验失败抛出。
 */
fun openFreeMaskFromMember(sealed: JsonObject, initiatorSpkSeed: ByteArray): FreeMaskSignal {
    val ekPub = b64Decode(sealed["ek_pub"]!!.jsonPrimitive.content)
    val key = X25519Ops.dh(initiatorSpkSeed, ekPub)
    val padded = aesGcmOpen(
        key,
        b64Decode(sealed["nonce"]!!.jsonPrimitive.content),
        b64Decode(sealed["cipher_text"]!!.jsonPrimitive.content),
        b64Decode(sealed["mac"]!!.jsonPrimitive.content),
    )
    return FreeMaskSignal.fromJson(Json.parseToJsonElement(String(padded, Charsets.UTF_8)).jsonObject)
}

/**
 * 桶级共振密度：第 i 个桶「几人有空」（behavior/collab_canvas.md §A，T4.11）。
 *
 * 与 [freeMaskBitwiseAnd]（全员交集，0/1 二值）互补：共振条要的是**强度**（越绿
 * 人越多），由本函数给每桶人数；全员交集桶另加一圈高亮描边。
 * 掩码长度不齐视为调用方 bug（§3.6 约束①：靶向区间统一），这里取最长防越界。
 */
fun quorumDensity(masks: List<String>): List<Int> {
    if (masks.isEmpty()) return emptyList()
    val len = masks.maxOf { it.length }
    return buildList {
        for (i in 0 until len) {
            add(masks.fold(0) { n, m -> n + (if (i < m.length && m[i] == '1') 1 else 0) })
        }
    }
}
