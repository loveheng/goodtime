package app.shiguang.shiguang.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Duration
import java.time.LocalDateTime

/**
 * 天气投影平台件（行为清单 weather_provider.md 过目 2026-11-15；lib/service/weather.dart
 * Kotlin 等价）：无 key JSON 源（Open-Meteo）+ 内存 TTL 缓存不落库；经 get_schedule
 * 返回体搭载三日结构化预报。**机械层对天气无感**——天气只进提案推理与态势句；
 * 获取失败静默降级（AI 可改口问用户）。
 * 挂载点 = NativeToolFace weather 钩子（AppContainer 注入；hook 契约 suspend (String) -> JsonObject?）。
 */
class OkWeatherProvider(
    /** fetch 注入面（测试 seam，镜像 Dart WeatherService 构造参）；默认=OkHttp 实现。 */
    private val fetchImpl: suspend (String) -> String = { url -> httpFetch(url) },
) {

    /** TTL（§11：内存缓存不落库，重启/日变更高即重读）。 */
    private val ttl = Duration.ofHours(1)

    private var cacheCity: String? = null
    private var cacheAt: LocalDateTime? = null
    private var cacheData: JsonObject? = null

    /** 三日结构化预报；城市为空或任一步失败 → null（静默降级，不抛）。 */
    suspend operator fun invoke(city: String): JsonObject? = invoke(city, null)

    suspend operator fun invoke(city: String, now: LocalDateTime?): JsonObject? {
        if (city.trim().isEmpty()) return null
        val at = now ?: LocalDateTime.now()
        val cached = cacheData
        val cachedAt = cacheAt
        if (cached != null && cacheCity == city && cachedAt != null &&
            Duration.between(cachedAt, at) < ttl
        ) {
            return cached
        }
        return try {
            val geo = Json.parseToJsonElement(fetch(GEO_URL + encodeComponent(city) + GEO_QUERY)).jsonObject
            val results = geo["results"] as? JsonArray
            if (results == null || results.isEmpty()) return null
            val first = results[0] as? JsonObject ?: return null
            val lat = (first["latitude"] as? JsonPrimitive)?.contentOrNull ?: return null
            val lon = (first["longitude"] as? JsonPrimitive)?.contentOrNull ?: return null
            val raw = Json.parseToJsonElement(
                fetch("$FORECAST_URL$lat&longitude=$lon$FORECAST_QUERY"),
            ).jsonObject
            val daily = raw["daily"] as? JsonObject ?: return null
            val dates = (daily["time"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                ?: emptyList()
            val codes = (daily["weather_code"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.toIntOrNull() }
                ?: emptyList()
            val days = buildJsonArray {
                var i = 0
                while (i < dates.size && i < 3) {
                    add(buildJsonObject {
                        put("date", dates[i])
                        put("summary", describeCode(codes.getOrNull(i) ?: -1))
                        put("t_max", (daily["temperature_2m_max"] as? JsonArray)?.getOrNull(i) ?: JsonNull)
                        put("t_min", (daily["temperature_2m_min"] as? JsonArray)?.getOrNull(i) ?: JsonNull)
                        put("precip_prob", (daily["precipitation_probability_max"] as? JsonArray)?.getOrNull(i) ?: JsonNull)
                    })
                    i++
                }
            }
            val out = buildJsonObject {
                put("city", city)
                put("days", days)
            }
            cacheCity = city
            cacheAt = at
            cacheData = out
            out
        } catch (e: Exception) {
            // DEGRADE: [weather_unavailable] 天气源不可达/解析失败——静默降级（§11 拍板），不缓存失败
            ShiguangLog.d("weather", "[DEGRADE][weather_unavailable] ${e.javaClass.simpleName}")
            null
        }
    }

    /** WMO weather code → 白话摘要（户外敏感是 playbook 启发式，不加字段；映射逐字）。 */
    fun describeCode(code: Int): String = when {
        code < 0 -> "未知"
        code == 0 -> "晴"
        code <= 3 -> "多云"
        code == 45 || code == 48 -> "雾"
        code in 51..67 -> "雨"
        code in 71..77 -> "雪"
        code in 80..82 -> "阵雨"
        code >= 95 -> "雷雨"
        else -> "多变"
    }

    private suspend fun fetch(url: String): String = withContext(Dispatchers.IO) { fetchImpl(url) }

    /** RFC 3986 unreserved（=Dart Uri.encodeComponent 语义；URLEncoder 会把空格转 +，不用）。 */
    private fun encodeComponent(s: String): String = buildString {
        for (ch in s) when (ch) {
            in 'a'..'z', in 'A'..'Z', in '0'..'9', '-', '_', '.', '~' -> append(ch)
            else -> append('%').append(ch.code.toString(16).uppercase().padStart(2, '0'))
        }
    }

    companion object {
        private val client = OkHttpClient()

        private fun httpFetch(url: String): String {
            val resp = client.newCall(Request.Builder().url(url).build()).execute()
            resp.use { r ->
                if (r.code != 200) throw IllegalStateException("HTTP ${r.code}")
                return r.body?.string() ?: throw IllegalStateException("空响应体")
            }
        }

        const val GEO_URL = "https://geocoding-api.open-meteo.com/v1/search?name="
        const val GEO_QUERY = "&count=1&language=zh&format=json"
        const val FORECAST_URL = "https://api.open-meteo.com/v1/forecast?latitude="
        const val FORECAST_QUERY =
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max&forecast_days=3&timezone=auto"
    }
}
