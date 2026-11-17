package app.shiguang.core.model.collab.relay

import app.shiguang.core.model.collab.FriendshipTicket
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class RelayFriendsException(
    val statusCode: Int,
    val detail: Any? = null,
) : Exception("RelayFriendsException($statusCode): $detail")

/**
 * 好友域 REST 客户端（lib/collab/relay/friends_api.dart Kotlin 等价，relay friends.go
 * 冻结契约）。拉取好友名单见 [fetchFriends]（PeersApi.kt，FriendshipTicket 不在此文件）。
 *
 * 两张写接口：
 * 1. POST /v1/friends/invite——扫到对方名片 Ticket 后提交，服务端验签 + 验过期 +
 *    nonce 一次性 + 配额后才写 friendships(pending)（§1.6 防撞库骚扰）；
 * 2. POST /v1/friends/accept——仅被邀方可处置（accept=false → blocked）。
 *
 * 错误归一：HTTP 4xx/5xx（带响应体）→ [RelayFriendsException]（带状态码，UI 据此
 * 区分「已加过 / 名片过期 / 登录失效」）；纯网络层失败（无响应）→ 原样抛
 * IOException，由上层归为离线。
 */
private suspend fun execute(
    client: OkHttpClient,
    request: Request,
    expectCode: Int,
): JsonObject = withContext(Dispatchers.IO) {
    val resp = client.newCall(request).execute()
    resp.use { r ->
        val bodyStr = r.body?.string() ?: ""
        if (r.code != expectCode) {
            throw RelayFriendsException(r.code, bodyStr)
        }
        if (bodyStr.isEmpty()) return@use buildJsonObject { }
        try {
            Json.parseToJsonElement(bodyStr) as? JsonObject ?: buildJsonObject { }
        } catch (_: Exception) {
            buildJsonObject { }
        }
    }
}

private fun post(url: String, accessToken: String, body: JsonObject?): Request {
    val builder = Request.Builder().url(url).header("Authorization", "Bearer $accessToken")
    return if (body != null) {
        builder.post(body.toString().toRequestBody("application/json".toMediaType())).build()
    } else {
        builder.post(byteArrayOf().toRequestBody(null)).build()
    }
}

/** 提交好友邀请（扫到的对方名片）。[encryptedIntro] 为「自报家门」密文（ECIES），
 *  端到端加密、服务端盲存；可留空——服务端不因此拒建联，仅 B 端解不开那段字节。 */
suspend fun submitFriendInvite(
    client: OkHttpClient,
    baseUrl: String,
    accessToken: String,
    ticket: FriendshipTicket,
    encryptedIntro: String = "",
): String {
    val body = buildJsonObject {
        put("ticket", ticket.toJson())
        put("encrypted_intro", encryptedIntro)
    }
    return try {
        val j = execute(
            client,
            post("${baseUrl.trimEnd('/')}/v1/friends/invite", accessToken, body),
            expectCode = 201,
        )
        j["invite_id"]!!.jsonPrimitive.content
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        throw e // 纯网络层失败原样上抛，由上层归为离线
    }
}

/** 按用户名直发好友申请（账号体系搜索路径，friends.go 双通道之一）：先 GET
 *  /v1/users/lookup 拿对方 user_id，再 POST /v1/friends/request。两路殊途同归落
 *  pending，被邀方处置接口同 [acceptFriendInvite]。 */
suspend fun submitFriendRequest(
    client: OkHttpClient,
    baseUrl: String,
    accessToken: String,
    toUserId: String,
    encryptedIntro: String = "",
): String {
    val body = buildJsonObject {
        put("to_user_id", toUserId)
        put("encrypted_intro", encryptedIntro)
    }
    return try {
        val j = execute(
            client,
            post("${baseUrl.trimEnd('/')}/v1/friends/request", accessToken, body),
            expectCode = 201,
        )
        j["invite_id"]!!.jsonPrimitive.content
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        throw e
    }
}

/** 解除关系（DELETE /v1/friends/{user_id}）：删除好友 / 撤回申请 / 解除拉黑三合一，
 *  双向命中；双方回到陌生状态，此后可重新申请。无关系 → 404 INVITE_NOT_FOUND。 */
suspend fun removeFriend(
    client: OkHttpClient,
    baseUrl: String,
    accessToken: String,
    userId: String,
): String {
    val request = Request.Builder()
        .url("${baseUrl.trimEnd('/')}/v1/friends/$userId")
        .header("Authorization", "Bearer $accessToken")
        .delete()
        .build()
    return try {
        val j = execute(client, request, expectCode = 200)
        j["status"]!!.jsonPrimitive.content
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        throw e
    }
}

/** 主动拉黑（POST /v1/friends/{user_id}/block）：无需既有关系；pending/accepted
 *  一并转 blocked。拉黑列表配额 ≤500，超限 400 EXCEED_BLOCKED_LIST。 */
suspend fun blockFriend(
    client: OkHttpClient,
    baseUrl: String,
    accessToken: String,
    userId: String,
): String {
    return try {
        val j = execute(
            client,
            post("${baseUrl.trimEnd('/')}/v1/friends/$userId/block", accessToken, null),
            expectCode = 200,
        )
        j["status"]!!.jsonPrimitive.content
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        throw e
    }
}

/** 被邀方处置一条邀请：accept=true → accepted；false → blocked（拉黑）。 */
suspend fun acceptFriendInvite(
    client: OkHttpClient,
    baseUrl: String,
    accessToken: String,
    inviteId: String,
    accept: Boolean,
): String {
    val body = buildJsonObject {
        put("invite_id", inviteId)
        put("accept", accept)
    }
    return try {
        val j = execute(
            client,
            post("${baseUrl.trimEnd('/')}/v1/friends/accept", accessToken, body),
            expectCode = 200,
        )
        j["status"]!!.jsonPrimitive.content
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        throw e
    }
}
