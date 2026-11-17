package app.shiguang.core.model.collab.relay

import java.security.SecureRandom
import java.time.Duration
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

/**
 * 统一的 relay HTTP 客户端工厂（lib/collab/relay/client.dart Kotlin 等价）。
 *
 * relayd 中间件强制每个请求带 X-Client-Msg-Id（幂等键）与 X-Client-Ts（秒级 Unix
 * 时间戳，容差 ±300s；缺失或越界即 400 INVALID_REQUEST）。本工厂用拦截器为每个
 * 请求自动补这两个头，调用方无需逐个手写。仅当请求已显式带该头时（如发件箱用
 * OutboxEntry.id 保证重试幂等）保留原值，不覆盖。
 */
fun relayHttpClient(base: OkHttpClient = OkHttpClient()): OkHttpClient {
    val idInterceptor = Interceptor { chain ->
        val request = chain.request()
        val builder = request.newBuilder()
        if (request.header("X-Client-Msg-Id") == null) {
            builder.header("X-Client-Msg-Id", newMsgId())
        }
        if (request.header("X-Client-Ts") == null) {
            builder.header("X-Client-Ts", (System.currentTimeMillis() / 1000).toString())
        }
        chain.proceed(builder.build())
    }
    return base.newBuilder()
        // 超时兜底：relay 网络不可达时让请求快速失败，而非无限挂起（避免后台任务卡死）。
        .connectTimeout(Duration.ofSeconds(8))
        .readTimeout(Duration.ofSeconds(20))
        .writeTimeout(Duration.ofSeconds(20))
        .addInterceptor(idInterceptor)
        .build()
}

/** 16 字节 CSPRNG → 32 位 hex 幂等键（与 Dart _newMsgId 同形）。 */
internal fun newMsgId(): String {
    val bytes = ByteArray(16).also(SecureRandom()::nextBytes)
    val hex = "0123456789abcdef"
    val out = StringBuilder(32)
    for (b in bytes) {
        out.append(hex[(b.toInt() shr 4) and 0xf]).append(hex[b.toInt() and 0xf])
    }
    return out.toString()
}
