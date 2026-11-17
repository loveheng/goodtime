package app.shiguang.shiguang.platform

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.LocalDateTime
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * OkWeatherProvider 行为单测（行为清单 weather_provider.md 过目 2026-11-15）：
 * WMO 映射逐字/URL 逐字拼接/缓存 TTL 命中与键匹配/静默降级不缓存失败。
 * fetch 注入脚本化替身（镜像 Dart WeatherService fetch seam）。
 */
class OkWeatherProviderTest {

    private val responses = ArrayDeque<Pair<Int, String>>()
    private val requests = mutableListOf<String>()

    @BeforeTest
    fun setUp() {
        responses.clear()
        requests.clear()
    }

    private fun provider() = OkWeatherProvider(fetchImpl = { url ->
        requests.add(url)
        val (code, body) = responses.removeFirstOrNull()
            ?: throw IllegalStateException("无脚本响应（请求 $url）")
        if (code != 200) throw IllegalStateException("HTTP $code")
        body
    })

    private fun enqueue(code: Int, body: String) = responses.addLast(code to body)

    @Test
    fun describeCodeMapping() {
        val p = OkWeatherProvider()
        assertEquals("未知", p.describeCode(-1))
        assertEquals("晴", p.describeCode(0))
        assertEquals("多云", p.describeCode(3))
        assertEquals("雾", p.describeCode(45))
        assertEquals("雾", p.describeCode(48))
        assertEquals("雨", p.describeCode(51))
        assertEquals("雨", p.describeCode(67))
        assertEquals("雪", p.describeCode(71))
        assertEquals("雪", p.describeCode(77))
        assertEquals("阵雨", p.describeCode(80))
        assertEquals("阵雨", p.describeCode(82))
        assertEquals("雷雨", p.describeCode(95))
        assertEquals("多变", p.describeCode(4))
        assertEquals("多变", p.describeCode(68))
    }

    @Test
    fun emptyCityShortCircuits() = runBlocking {
        val p = provider()
        assertNull(p("  "))
        assertEquals(0, requests.size) // 零网络往返
    }

    @Test
    fun happyPathShapeAndUrl() = runBlocking {
        val p = provider()
        enqueue(200, """{"results":[{"latitude":25.6,"longitude":100.2}]}""")
        enqueue(200, """{"daily":{"time":["2026-11-15","2026-11-16","2026-11-17","2026-11-18"],"weather_code":[0,61,95],"temperature_2m_max":[21.5,20.0,18.5],"temperature_2m_min":[9.0,8.5,7.0],"precipitation_probability_max":[10,80,90]}}""")
        val out = p("大理")
        assertEquals(2, requests.size)
        assertTrue(requests[0].startsWith("https://geocoding-api.open-meteo.com/v1/search?name="))
        assertTrue(requests[0].endsWith("&count=1&language=zh&format=json"))
        assertTrue(requests[1].startsWith("https://api.open-meteo.com/v1/forecast?latitude=25.6&longitude=100.2&daily="))
        val days = out!!["days"]!! as JsonArray
        assertEquals(3, days.size) // forecast_days=3 钳制（第 4 天丢弃）
        assertEquals("2026-11-15", ((days[0] as kotlinx.serialization.json.JsonObject)["date"] as JsonPrimitive).contentOrNull)
        assertEquals("晴", ((days[0] as kotlinx.serialization.json.JsonObject)["summary"] as JsonPrimitive).contentOrNull)
        assertEquals("雨", ((days[1] as kotlinx.serialization.json.JsonObject)["summary"] as JsonPrimitive).contentOrNull)
        assertEquals("雷雨", ((days[2] as kotlinx.serialization.json.JsonObject)["summary"] as JsonPrimitive).contentOrNull)
        assertEquals("21.5", ((days[0] as kotlinx.serialization.json.JsonObject)["t_max"] as JsonPrimitive).contentOrNull)
        assertEquals("大理", (out["city"] as JsonPrimitive).contentOrNull)
    }

    @Test
    fun cacheHitsWithinTtlAndKeyMatters() = runBlocking {
        val p = provider()
        enqueue(200, """{"results":[{"latitude":1.0,"longitude":2.0}]}""")
        enqueue(200, """{"daily":{"time":["2026-11-15"],"weather_code":[0]}}""")
        val t0 = LocalDateTime.parse("2026-11-15T10:00:00")
        p("大理", t0)
        assertEquals(2, requests.size)
        // TTL 内命中：零新请求
        p("大理", t0.plusMinutes(59))
        assertEquals(2, requests.size)
        // TTL 过：重取
        enqueue(200, """{"results":[{"latitude":1.0,"longitude":2.0}]}""")
        enqueue(200, """{"daily":{"time":["2026-11-15"],"weather_code":[0]}}""")
        p("大理", t0.plusMinutes(61))
        assertEquals(4, requests.size)
        // 不同 city：键失配即重取（缓存键=原串字面）
        enqueue(200, """{"results":[{"latitude":3.0,"longitude":4.0}]}""")
        enqueue(200, """{"daily":{"time":["2026-11-15"],"weather_code":[0]}}""")
        p("昆明", t0.plusMinutes(61).plusSeconds(30))
        assertEquals(6, requests.size)
    }

    @Test
    fun failureDegradesSilentlyAndDoesNotCache() = runBlocking {
        val p = provider()
        enqueue(500, "oops")
        assertNull(p("大理"))
        assertEquals(1, requests.size) // 地理编码 500 即停
        // 失败不缓存：下一调用重新请求
        enqueue(200, """{"results":[{"latitude":1.0,"longitude":2.0}]}""")
        enqueue(200, """{"daily":{"time":["2026-11-15"],"weather_code":[0]}}""")
        p("大理")
        assertEquals(3, requests.size)
    }

    @Test
    fun geocodingEmptyResultsNull() = runBlocking {
        val p = provider()
        enqueue(200, """{"results":[]}""")
        assertNull(p("不存在的城市"))
        assertEquals(1, requests.size) // 空结果即停，零预报请求
    }
}
