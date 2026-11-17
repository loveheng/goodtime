package app.shiguang.core.model.collab.relay

import java.io.IOException
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

class RelayPeersException(
    val statusCode: Int,
    val detail: Any? = null,
) : Exception("RelayPeersException($statusCode): $detail")

/** 好友条目（GET /v1/friends 单行投影）。 */
data class FriendView(
    val userId: String,
    /** accepted / pending / blocked。 */
    val status: String,
    val createdAtSec: Long,
    /**
     * 自报家门密文：只在「我是被邀方 + 仍 pending」时才有值——A 侧拿不到自己也
     * 解不开的字节（friends.go 就是这样回包的），这里是忠实映射。
     */
    val encryptedIntro: ByteArray?,
) {
    val isAccepted: Boolean get() = status == "accepted"

    override fun equals(other: Any?): Boolean =
        other is FriendView && userId == other.userId && status == other.status &&
            createdAtSec == other.createdAtSec &&
            (encryptedIntro?.contentEquals(other.encryptedIntro) ?: (other.encryptedIntro == null))

    override fun hashCode(): Int = userId.hashCode() * 31 + status.hashCode()

    companion object {
        fun fromJson(j: JsonObject): FriendView = FriendView(
            userId = j["user_id"]!!.jsonPrimitive.content,
            status = j["status"]!!.jsonPrimitive.content,
            createdAtSec = j["created_at"]?.jsonPrimitive?.longOrNull ?: 0L,
            encryptedIntro = j["encrypted_intro"]?.let {
                Base64.getDecoder().decode(it.jsonPrimitive.content)
            },
        )
    }
}

private suspend fun getJson(
    client: OkHttpClient,
    url: String,
    accessToken: String,
    expect: Int = 200,
): JsonObject = withContext(Dispatchers.IO) {
    val request = Request.Builder()
        .url(url)
        .header("Authorization", "Bearer $accessToken")
        .get()
        .build()
    val resp = client.newCall(request).execute()
    resp.use { r ->
        val bodyStr = r.body?.string() ?: ""
        if (r.code != expect) {
            throw RelayPeersException(r.code, bodyStr)
        }
        Json.parseToJsonElement(bodyStr).jsonObject
    }
}

/** 拉全部好友（Bearer 鉴权）。不滤 status——UI 需区分 pending/blocked。 */
suspend fun fetchFriends(
    client: OkHttpClient,
    baseUrl: String,
    accessToken: String,
): List<FriendView> {
    val j = getJson(client, "${baseUrl.trimEnd('/')}/v1/friends", accessToken)
    val raw = j["friends"] as? JsonArray ?: JsonArray(emptyList())
    return raw.map { FriendView.fromJson(it as JsonObject) }
}

/** 只回落已通过双向确认的好友（本地发起 bundle 的候选成员池）。 */
suspend fun fetchAcceptedFriends(
    client: OkHttpClient,
    baseUrl: String,
    accessToken: String,
): List<FriendView> = fetchFriends(client, baseUrl, accessToken).filter { it.isAccepted }

/** 成员公钥包（GET /v1/users/{id}/prekey）。 */
data class PeerIdentityBundle(
    val userId: String,
    /** Ed25519 raw 32B（验签用）。 */
    val identityKeyPub: ByteArray,
    /** X25519 raw 32B（X3DH DH 用）。 */
    val signedPrekeyPub: ByteArray,
    val signedPrekeySig: ByteArray,
    val remainingOpk: Int,
    /** OPK_EMPTY_WARNING：降级 3-DH 提示。 */
    val opkLow: Boolean,
) {
    override fun equals(other: Any?): Boolean =
        other is PeerIdentityBundle && userId == other.userId &&
            identityKeyPub.contentEquals(other.identityKeyPub) &&
            signedPrekeyPub.contentEquals(other.signedPrekeyPub) &&
            signedPrekeySig.contentEquals(other.signedPrekeySig) &&
            remainingOpk == other.remainingOpk && opkLow == other.opkLow

    override fun hashCode(): Int = userId.hashCode()

    companion object {
        fun fromJson(userId: String, j: JsonObject): PeerIdentityBundle = PeerIdentityBundle(
            userId = userId,
            identityKeyPub = Base64.getDecoder()
                .decode(j["identity_key_pub"]?.jsonPrimitive?.content ?: ""),
            signedPrekeyPub = Base64.getDecoder()
                .decode(j["signed_prekey_pub"]?.jsonPrimitive?.content ?: ""),
            signedPrekeySig = Base64.getDecoder()
                .decode(j["signed_prekey_sig"]?.jsonPrimitive?.content ?: ""),
            remainingOpk = j["remaining_opk"]?.jsonPrimitive?.longOrNull?.toInt() ?: 0,
            opkLow = j["warning"]?.jsonPrimitive?.content == "OPK_EMPTY_WARNING",
        )
    }
}

/**
 * 取成员的 X25519 广播公钥——本地发起时封 K_bundle 信封的锚点。
 *
 * 坐标系铁律（§3）：DH 只走 signedPrekeyPub；identityKeyPub 是 Ed25519，
 * 拿它做 X25519 会直接炸。
 */
suspend fun fetchPeerKeys(
    client: OkHttpClient,
    baseUrl: String,
    accessToken: String,
    userId: String,
): PeerIdentityBundle {
    val encoded = java.net.URLEncoder.encode(userId, "UTF-8")
    val j = getJson(client, "${baseUrl.trimEnd('/')}/v1/users/$encoded/prekey", accessToken)
    return PeerIdentityBundle.fromJson(userId, j)
    // IOException 由调用方归一化离线（链上 catch-all）
}
