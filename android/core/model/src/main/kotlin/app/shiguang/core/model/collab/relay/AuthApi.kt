package app.shiguang.core.model.collab.relay

import app.shiguang.core.model.collab.PreKey
import java.io.IOException
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 账号域 REST 契约（lib/collab/relay/auth_api.dart Kotlin 等价，relay
 * internal/httpapi/register.go / router.go，账号体系 Plan A）。OkHttp 承载（D10）。
 *
 * 用户只见「用户名 + 密码」；E2EE 密钥材料由端侧自动生成随注册上传，服务端纯盲存。
 * 注意：REST 体是**原生 JSON**（snake_case 键 + base64 值），并非 protobuf。
 */

/** relay 非成功状态归一异常（状态码自分类不抛的契约口径；由调用方归一化 Offline/重试）。 */
class RelayAuthException(
    val statusCode: Int,
    val body: Any? = null,
) : Exception("relay http $statusCode")

data class TokenPair(
    val access: String,
    val refresh: String,
    /** access 过期时刻（unix 秒；对齐 relay auth.TokenPair.access_expires_at）。 */
    val accessExpiresAt: Long,
)

data class Challenge(
    val nonce: String,
    val powChallenge: String,
    val powDifficulty: Int,
    val expiresAt: Long,
) {
    companion object {
        fun fromJson(j: JsonObject): Challenge = Challenge(
            nonce = j["nonce"]!!.jsonPrimitive.content,
            powChallenge = j["pow_challenge"]!!.jsonPrimitive.content,
            powDifficulty = j["pow_difficulty"]!!.jsonPrimitive.content.toInt(),
            expiresAt = j["expires_at"]!!.jsonPrimitive.content.toLong(),
        )
    }
}

data class RegisterResponse(
    val userId: String,
    val username: String,
    val opkCount: Int,
) {
    companion object {
        fun fromJson(j: JsonObject): RegisterResponse = RegisterResponse(
            userId = j["user_id"]!!.jsonPrimitive.content,
            username = j["username"]?.jsonPrimitive?.contentOrNull ?: "",
            opkCount = j["opk_count"]!!.jsonPrimitive.content.toInt(),
        )
    }
}

/** GET /v1/users/lookup?username= 的回包（加好友搜索路径的用户身份面）。 */
data class UserLookup(
    val userId: String,
    val username: String,
    val identityKeyPub: ByteArray,
    val signedPrekeyPub: ByteArray,
    val signedPrekeySig: ByteArray,
)

/** 构造 POST /v1/users 的 JSON 体。密钥字段按服务端要求 base64；
 *  user_id 由服务端发号（UUID），客户端不再自报。 */
fun buildRegisterBody(
    username: String,
    password: String,
    identityKeyPub: ByteArray,
    signedPrekeyPub: ByteArray,
    signedPrekeySig: ByteArray,
    nonce: String,
    powSolution: String,
    oneTimePreKeys: List<PreKey>,
    pushToken: String? = null,
    encryptedProfile: ByteArray? = null,
    recoveryVault: ByteArray? = null,
): JsonObject = buildJsonObject {
    put("username", username)
    put("password", password)
    put("identity_key_pub", Base64.getEncoder().encodeToString(identityKeyPub))
    put("signed_prekey_pub", Base64.getEncoder().encodeToString(signedPrekeyPub))
    put("signed_prekey_sig", Base64.getEncoder().encodeToString(signedPrekeySig))
    put("push_token", pushToken ?: "")
    put("encrypted_profile", if (encryptedProfile == null) "" else Base64.getEncoder().encodeToString(encryptedProfile))
    put("recovery_vault", if (recoveryVault == null) "" else Base64.getEncoder().encodeToString(recoveryVault))
    put("nonce", nonce)
    put("pow_solution", powSolution)
    putJsonArray("one_time_prekeys") {
        oneTimePreKeys.forEach { k ->
            add(
                buildJsonObject {
                    put("key_id", k.keyId)
                    put("pub", Base64.getEncoder().encodeToString(k.pub))
                    put("signature", Base64.getEncoder().encodeToString(k.signature))
                },
            )
        }
    }
}

private suspend fun execute(
    client: OkHttpClient,
    request: Request,
    expectCode: Int,
): JsonObject = withContext(Dispatchers.IO) {
    val resp = try {
        client.newCall(request).execute()
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        throw e // 纯网络层失败原样上抛，由上层归为离线
    }
    resp.use { r ->
        val bodyStr = r.body?.string() ?: ""
        if (r.code != expectCode) {
            throw RelayAuthException(r.code, bodyStr)
        }
        if (bodyStr.isEmpty()) return@use buildJsonObject { }
        try {
            Json.parseToJsonElement(bodyStr) as? JsonObject ?: buildJsonObject { }
        } catch (_: Exception) {
            buildJsonObject { }
        }
    }
}

private fun jsonRequest(url: String, method: String, body: JsonObject?, accessToken: String? = null): Request {
    val builder = Request.Builder().url(url)
    if (accessToken != null) builder.header("Authorization", "Bearer $accessToken")
    return when (method) {
        "GET" -> builder.get().build()
        "PATCH" -> builder.patch(body.toString().toRequestBody("application/json".toMediaType())).build()
        else -> builder.post(body.toString().toRequestBody("application/json".toMediaType())).build()
    }
}

private fun tokenPairOf(j: JsonObject): TokenPair = TokenPair(
    access = j["access_token"]!!.jsonPrimitive.content,
    refresh = j["refresh_token"]!!.jsonPrimitive.content,
    accessExpiresAt = j["access_expires_at"]?.jsonPrimitive?.longOrNull ?: 0L,
)

/** POST /v1/auth/challenge：申领一次性 nonce + PoW 题面（注册用；登录不再需要）。 */
suspend fun fetchChallenge(client: OkHttpClient, baseUrl: String): Challenge {
    val j = execute(
        client,
        jsonRequest("${baseUrl.trimEnd('/')}/v1/auth/challenge", "POST", buildJsonObject { }),
        expectCode = 200,
    )
    return Challenge.fromJson(j)
}

/** 服务端统一错误体的 error_code（{error_code, message, detail}）；非标准体返回 null。
 *  UI 据此给可读文案（防枚举口径见 apierr 字典）。 */
fun errorCodeOf(detail: JsonElement?): String? {
    if (detail is JsonObject && detail["error_code"] is JsonPrimitive) {
        return detail["error_code"]!!.jsonPrimitive.content
    }
    return null
}

/** 服务端统一错误体的 detail.retry_after_sec（登录防爆破：账号锁定期等待窗口）。 */
fun retryAfterSecOf(detail: JsonElement?): Int? {
    if (detail is JsonObject && detail["detail"] is JsonObject) {
        val inner = detail["detail"] as JsonObject
        val v = inner["retry_after_sec"]
        if (v is JsonPrimitive) return v.content.toIntOrNull() ?: v.longOrNull?.toInt()
    }
    return null
}

/** POST /v1/users：提交注册（username + password + E2EE 公钥材料 + PoW）。
 *  期待 201；其余状态抛 [RelayAuthException]。 */
suspend fun registerUser(client: OkHttpClient, baseUrl: String, body: JsonObject): RegisterResponse {
    val j = execute(
        client,
        jsonRequest("${baseUrl.trimEnd('/')}/v1/users", "POST", body),
        expectCode = 201,
    )
    return RegisterResponse.fromJson(j)
}

/** POST /v1/auth/login：纯密码登录（{username, password} → 双 Token）。
 *
 * 用户不存在与密码错误同一 401 口径（USER_OR_PASSWORD_INVALID，防账号枚举）；
 * 客户端 UI 也不得区分。 */
suspend fun login(client: OkHttpClient, baseUrl: String, username: String, password: String): TokenPair {
    val body = buildJsonObject {
        put("username", username)
        put("password", password)
    }
    val j = execute(client, jsonRequest("${baseUrl.trimEnd('/')}/v1/auth/login", "POST", body), expectCode = 200)
    return tokenPairOf(j)
}

/** POST /v1/auth/refresh：用 refresh token 续期双 Token。 */
suspend fun refresh(client: OkHttpClient, baseUrl: String, refreshToken: String): TokenPair {
    val body = buildJsonObject { put("refresh_token", refreshToken) }
    val j = execute(client, jsonRequest("${baseUrl.trimEnd('/')}/v1/auth/refresh", "POST", body), expectCode = 200)
    return tokenPairOf(j)
}

/** POST /v1/auth/change-password（Bearer）：只换密码哈希，不影响 E2EE 密钥与历史消息
 *  （账号体系 Plan A：密码仅鉴权）。 */
suspend fun changePassword(
    client: OkHttpClient,
    baseUrl: String,
    accessToken: String,
    oldPassword: String,
    newPassword: String,
    newRecoveryVault: ByteArray? = null,
) {
    val body = buildJsonObject {
        put("old_password", oldPassword)
        put("new_password", newPassword)
        // v3 拍板：金库包裹钥=登录密码派生，改密即原子重封（缺省=不改金库，
        // 服务端单事务落库，缺省字段仅换哈希兼容无金库账号）。
        if (newRecoveryVault != null) {
            put("new_recovery_vault", Base64.getEncoder().encodeToString(newRecoveryVault))
        }
    }
    execute(
        client,
        jsonRequest("${baseUrl.trimEnd('/')}/v1/auth/change-password", "POST", body, accessToken),
        expectCode = 200,
    )
}

/** GET /v1/users/lookup?username=（Bearer）：用户名搜索身份（加好友搜索路径）。
 *  查无此人 → 404 USER_NOT_FOUND。 */
suspend fun lookupUser(
    client: OkHttpClient,
    baseUrl: String,
    accessToken: String,
    username: String,
): UserLookup {
    val url = "${baseUrl.trimEnd('/')}/v1/users/lookup?username=" +
        java.net.URLEncoder.encode(username, "UTF-8")
    val j = execute(client, jsonRequest(url, "GET", null, accessToken), expectCode = 200)
    return UserLookup(
        userId = j["user_id"]!!.jsonPrimitive.content,
        username = j["username"]!!.jsonPrimitive.content,
        identityKeyPub = Base64.getDecoder().decode(j["identity_key_pub"]!!.jsonPrimitive.content),
        signedPrekeyPub = Base64.getDecoder().decode(j["signed_prekey_pub"]!!.jsonPrimitive.content),
        signedPrekeySig = Base64.getDecoder().decode(j["signed_prekey_sig"]!!.jsonPrimitive.content),
    )
}

/** GET /v1/users/me（Bearer）：自账户读回——换机恢复链路的取回通道。
 *  encrypted_profile/recovery_vault 未设置时字段缺省（null）。 */
data class MeProfile(
    val userId: String,
    val username: String,
    val encryptedProfile: ByteArray?,
    val recoveryVault: ByteArray?,
    val createdAt: Long,
)

suspend fun fetchMe(client: OkHttpClient, baseUrl: String, accessToken: String): MeProfile {
    val j = execute(client, jsonRequest("${baseUrl.trimEnd('/')}/v1/users/me", "GET", null, accessToken), expectCode = 200)
    return MeProfile(
        userId = j["user_id"]!!.jsonPrimitive.content,
        username = j["username"]!!.jsonPrimitive.content,
        encryptedProfile = j["encrypted_profile"]?.let { Base64.getDecoder().decode(it.jsonPrimitive.content) },
        recoveryVault = j["recovery_vault"]?.let { Base64.getDecoder().decode(it.jsonPrimitive.content) },
        createdAt = j["created_at"]?.jsonPrimitive?.longOrNull ?: 0L,
    )
}

/** PATCH /v1/users/me（Bearer）：自账户更新。三字段**可选独立生效**——传 null = 不动；
 *  pushToken 传空串 = 清空（推送令牌换发时调用）。encryptedProfile/recoveryVault
 *  传 base64 密文字节，present 即整体替换。 */
suspend fun patchMe(
    client: OkHttpClient,
    baseUrl: String,
    accessToken: String,
    encryptedProfile: ByteArray? = null,
    recoveryVault: ByteArray? = null,
    pushToken: String? = null,
) {
    if (encryptedProfile == null && recoveryVault == null && pushToken == null) {
        throw IllegalArgumentException("patchMe：至少给一个字段（服务端拒绝空更新）")
    }
    val body = buildJsonObject {
        if (encryptedProfile != null) put("encrypted_profile", Base64.getEncoder().encodeToString(encryptedProfile))
        if (recoveryVault != null) put("recovery_vault", Base64.getEncoder().encodeToString(recoveryVault))
        if (pushToken != null) put("push_token", pushToken) // Dart ?pushToken 镜像：present 即上送
    }
    execute(
        client,
        jsonRequest("${baseUrl.trimEnd('/')}/v1/users/me", "PATCH", body, accessToken),
        expectCode = 200,
    )
}

/** POST /v1/auth/logout（Bearer）：主动登出——当前 access 与随附 refresh 双 jti 拉黑至
 *  各自自然到期，重复登出幂等无害。服务端永远 200（非法 refresh 静默忽略）。 */
suspend fun logoutRemote(client: OkHttpClient, baseUrl: String, accessToken: String, refreshToken: String? = null) {
    try {
        val body = buildJsonObject {
            // Dart 'refresh_token': ?refreshToken 镜像——present 即上送
            if (refreshToken != null) put("refresh_token", refreshToken)
        }
        execute(
            client,
            jsonRequest("${baseUrl.trimEnd('/')}/v1/auth/logout", "POST", body, accessToken),
            expectCode = 200,
        )
    } catch (e: CancellationException) {
        throw e
    } catch (_: IOException) {
        // 登出必须本地可完成：网络失败不阻断（服务端拉黑尽力而为；access 到期自然失效）
    }
}

/** POST /v1/users/me/prekeys：低存量时后台补仓（≤50/批，§1.5 心跳监听用）。 */
suspend fun refillPreKeys(
    client: OkHttpClient,
    baseUrl: String,
    accessToken: String,
    oneTimePreKeys: List<PreKey>,
): JsonObject {
    val body = buildJsonObject {
        putJsonArray("one_time_prekeys") {
            oneTimePreKeys.forEach { k ->
                add(
                    buildJsonObject {
                        put("key_id", k.keyId)
                        put("pub", Base64.getEncoder().encodeToString(k.pub))
                        put("signature", Base64.getEncoder().encodeToString(k.signature))
                    },
                )
            }
        }
    }
    return execute(
        client,
        jsonRequest("${baseUrl.trimEnd('/')}/v1/users/me/prekeys", "POST", body, accessToken),
        expectCode = 200,
    )
}
