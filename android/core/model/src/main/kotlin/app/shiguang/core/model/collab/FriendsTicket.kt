package app.shiguang.core.model.collab

import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * 好友名片凭证（lib/collab/friends_ticket.dart Kotlin 等价，§1.6 Friendship Ticket）
 * + 安全码（Safety Number）。
 *
 * ## Friendship Ticket
 * 接收方 B 端侧签发的一次性名片（二维码）：Ticket_B = {account_id_B, nonce,
 * expires_at, signature_B}。A 扫码携完整 Ticket 提交 POST /v1/friends/invite，
 * 服务端验签（B 的 Ed25519 身份公钥）+ 验未过期 + nonce 一次性才写
 * friendships(pending)。防撞库骚扰——禁止拿纯 account_id 全局发好友申请。
 *
 * ## Safety Number
 * 好友建立后，端侧计算双方身份公钥哈希指纹，展示为 6 组 5 位数字安全码——用户可
 * 线下核对，提供防「恶意中继置换公钥」的 MITM 校验入口（中继不可信，端侧可验证）。
 */

/** Ticket 规范化签名消息（与 relay auth.TicketMessage 逐字节一致，否则跨端验签必败）。
 *  覆盖字段：shiguang.relay.v1/friendship-ticket|<issuer>|<subject>|<nonce>|<expires_at>。
 *  subject_user_id 可空（任意扫码者），此时该段为空串。 */
fun ticketMessage(issuer: String, subject: String, nonce: String, expiresAt: Long): String =
    "shiguang.relay.v1/friendship-ticket|$issuer|$subject|$nonce|$expiresAt"

/** 好友名片凭证（QR 的载荷，字段名对齐 relay ticketReq 原生 JSON）。 */
data class FriendshipTicket(
    val issuerUserId: String,
    /** 被指定可扫码者，可空（任意扫码者）。 */
    val subjectUserId: String,
    val nonce: String,
    /** unix 秒。 */
    val expiresAt: Long,
    /** Ed25519 签 issuer 私钥。 */
    val signature: ByteArray,
    /** 签发人 X25519 加密公钥（ECIES 自报家门用，服务端不解析）。 */
    val encKeyPub: ByteArray,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("issuer_user_id", issuerUserId)
        put("subject_user_id", subjectUserId)
        put("nonce", nonce)
        put("expires_at", expiresAt)
        put("signature", Base64.getEncoder().encodeToString(signature))
        put("enc_key_pub", Base64.getEncoder().encodeToString(encKeyPub))
    }

    /** 直接喂二维码渲染器的二维码串。 */
    fun toQrString(): String = toJson().toString()

    override fun equals(other: Any?): Boolean =
        other is FriendshipTicket && issuerUserId == other.issuerUserId &&
            subjectUserId == other.subjectUserId && nonce == other.nonce &&
            expiresAt == other.expiresAt && signature.contentEquals(other.signature) &&
            encKeyPub.contentEquals(other.encKeyPub)

    override fun hashCode(): Int = nonce.hashCode() * 31 + expiresAt.hashCode()

    companion object {
        fun fromJson(j: JsonObject): FriendshipTicket = FriendshipTicket(
            issuerUserId = j["issuer_user_id"]!!.jsonPrimitiveContent(),
            subjectUserId = j["subject_user_id"]?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.content ?: "",
            nonce = j["nonce"]!!.jsonPrimitiveContent(),
            expiresAt = j["expires_at"]!!.jsonPrimitiveLong(),
            signature = Base64.getDecoder().decode(j["signature"]!!.jsonPrimitiveContent()),
            encKeyPub = Base64.getDecoder().decode(j["enc_key_pub"]?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.content ?: ""),
        )

        fun fromQrString(s: String): FriendshipTicket = fromJson(Json.parseToJsonElement(s).jsonObject)
    }
}

private fun kotlinx.serialization.json.JsonElement.jsonPrimitiveContent(): String =
    (this as kotlinx.serialization.json.JsonPrimitive).content

private fun kotlinx.serialization.json.JsonElement.jsonPrimitiveLong(): Long =
    (this as kotlinx.serialization.json.JsonPrimitive).content.toLong()

private fun randomNonce(): String {
    val bytes = ByteArray(16).also(SecureRandom()::nextBytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

/**
 * 用本地身份私钥签发自己的名片 Ticket（Ed25519 签名）。
 *
 * 与「半盲不建联」铁律一致：私钥由 MSK 封存，vault 未解锁 / 无身份记录即抛
 * （调用方先还原私钥种子），本函数保持纯密码学、零身份存储依赖、可单测。
 */
fun signFriendshipTicket(
    identityKeySeed: ByteArray,
    issuerUserId: String,
    encKeyPub: ByteArray,
    ttlSeconds: Long = 300,
    nowSec: () -> Long = { System.currentTimeMillis() / 1000 },
): FriendshipTicket {
    val nonce = randomNonce()
    val expiresAt = nowSec() + ttlSeconds
    val msg = ticketMessage(issuer = issuerUserId, subject = "", nonce = nonce, expiresAt = expiresAt)
    val sig = ed25519Sign(identityKeySeed, msg.toByteArray(Charsets.UTF_8))
    return FriendshipTicket(
        issuerUserId = issuerUserId,
        subjectUserId = "",
        nonce = nonce,
        expiresAt = expiresAt,
        signature = sig,
        encKeyPub = encKeyPub,
    )
}

private fun compareBytes(a: ByteArray, b: ByteArray): Int {
    val n = minOf(a.size, b.size)
    for (i in 0 until n) {
        val x = a[i].toInt() and 0xff
        val y = b[i].toInt() and 0xff
        if (x != y) return if (x < y) -1 else 1
    }
    return a.size.compareTo(b.size)
}

/**
 * 安全码（Safety Number，§1.6）：双方身份公钥哈希指纹，6 组 5 位数字。
 *
 * 取双方 Ed25519 身份公钥，按字节序排序后拼接做 SHA-256，逐组派生 5 位十进制数
 * （00000–99999）。排序保证双端算出同一串；加组索引避免各组撞车。
 */
fun safetyNumberDigits(myIkPub: ByteArray, peerIkPub: ByteArray): List<String> {
    val ordered = if (compareBytes(myIkPub, peerIkPub) <= 0) {
        myIkPub to peerIkPub
    } else {
        peerIkPub to myIkPub
    }
    val concat = ordered.first + ordered.second
    val out = mutableListOf<String>()
    val digest = MessageDigest.getInstance("SHA-256")
    for (i in 0 until 6) {
        val block = concat + i.toByte()
        val hash = digest.digest(block)
        val n = ((hash[0].toInt() and 0xff) shl 16) or ((hash[1].toInt() and 0xff) shl 8) or (hash[2].toInt() and 0xff)
        out.add(n.rem(100000).toString().padStart(5, '0'))
    }
    return out
}
