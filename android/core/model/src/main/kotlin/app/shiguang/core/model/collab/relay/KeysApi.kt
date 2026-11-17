package app.shiguang.core.model.collab.relay

import app.shiguang.core.model.collab.KeyEnvelopeView

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request

/** 成员密钥信封 REST 客户端异常（relay keys_api.dart 等价）。 */
class RelayKeysException(
    val statusCode: Int,
    val detail: Any? = null,
) : Exception("RelayKeysException($statusCode): $detail")

/**
 * GET /v1/bundles/{id}/key-envelopes：拉我在这个包里的全部密钥信封
 * （relay sync.go 冻结契约，§5.3 K_bundle 分发；lib/collab/relay/keys_api.dart Kotlin 等价）。
 *
 * 只有**本成员自己的那一份**会回包（MemberKeyEnvelopes 按 caller 过滤），服务端
 * 纯粹当字节盲存：它不知道里面包的是 K_bundle，也解不开。行视图复用
 * [KeyEnvelopeView]（BundleKeyEnvelope.kt 单源）。非成员 403（服务端 ACL），
 * 空列表也是合法回包（发起人自己就没有信封——它直接持有明文 K_bundle）。
 */
suspend fun fetchBundleKeyEnvelopes(
    client: OkHttpClient,
    baseUrl: String,
    accessToken: String,
    bundleId: String,
): List<KeyEnvelopeView> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    val encoded = java.net.URLEncoder.encode(bundleId, "UTF-8")
    val request = Request.Builder()
        .url("${baseUrl.trimEnd('/')}/v1/bundles/$encoded/key-envelopes")
        .header("Authorization", "Bearer $accessToken")
        .get()
        .build()
    val resp = client.newCall(request).execute()
    resp.use { r ->
        val bodyStr = r.body?.string() ?: ""
        if (r.code != 200) {
            throw RelayKeysException(r.code, bodyStr)
        }
        val j = Json.parseToJsonElement(bodyStr).jsonObject
        val raw = j["envelopes"] as? JsonArray ?: JsonArray(emptyList())
        raw.map { KeyEnvelopeView.fromJson(it as JsonObject) }
    }
    // IOException 由调用方归一化（Intake 链 catch-all 跳过；CancellationException 穿透）
}
