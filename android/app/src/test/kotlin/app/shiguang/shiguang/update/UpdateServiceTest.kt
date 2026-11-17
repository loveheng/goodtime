package app.shiguang.shiguang.update

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

/**
 * 更新服务对拍（N8c，update_page.md §C 转正清单）：sourceUrl 优先级/保存源校验/
 * 清单拉取（成功 config 落缓存联动 + 串包拒 + HTTP 非 200 + 格式错）/流式下载
 * sha256 校验（相符落盘 + 不符删临时文件拒）。错误串逐字（§A 表）。
 */
class UpdateServiceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var service: UpdateService
    private val saved = mutableMapOf<String, String>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        service = UpdateService(
            defaultSourceUrl = "",
            readSavedSource = { saved["url"] },
            writeSavedSource = { v -> if (v == null) saved.remove("url") else saved["url"] = v },
            appPackage = "app.shiguang.shiguang",
            updatesDir = tmp.newFolder("updates"),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun manifestJson(applicationId: String? = null, code: Long = 2): String = """
        {"versionCode":$code,"versionName":"1.4.0","apkUrl":"http://x/y.apk","sha256":null,
         ${if (applicationId != null) """"applicationId":"$applicationId",""" else ""}
         "config":{"announcement":"测试公告","announcementId":"a1","mcpInstructions":"远程指令","flags":{"k":true}}}
    """.trimIndent()

    // ── sourceUrl / setSourceUrl ──

    @Test
    fun sourceUrlSavedPreferredThenDefaultThenNull() = runBlocking {
        assertNull(service.sourceUrl())
        val withDefault = UpdateService(defaultSourceUrl = "https://d/", readSavedSource = { null })
        assertEquals("https://d/", withDefault.sourceUrl())
        saved["url"] = "https://s/"
        assertEquals("https://s/", service.sourceUrl())
        Unit
    }

    @Test
    fun setSourceUrlValidateAndRemove() = runBlocking {
        service.setSourceUrl("  https://s/updates  ")
        assertEquals("https://s/updates", saved["url"])
        // 非法源逐字拒串
        val e = runCatching { service.setSourceUrl("ftp://x") }.exceptionOrNull()
        assertEquals("更新源必须是合法的 http(s) URL", e?.message)
        // 空=移除回退默认
        service.setSourceUrl("   ")
        assertNull(saved["url"])
        Unit
    }

    // ── check ──

    @Test
    fun checkParsesManifestAndReportsNewer() = runBlocking {
        saved["url"] = server.url("/").toString()
        server.enqueue(MockResponse().setBody(manifestJson()))
        val m = service.check()
        assertEquals(2L, m.versionCode)
        assertEquals("1.4.0", m.versionName)
        assertEquals("测试公告", m.config.announcement)
        assertEquals("远程指令", m.config.mcpInstructions)
        assertTrue(m.isNewerThan(1))
        assertTrue(!m.isNewerThan(2))
        // 清单请求 = {源}/shiguang-update.json
        assertEquals("/shiguang-update.json", server.takeRequest().path)
        Unit
    }

    @Test
    fun checkRejectsForeignPackage() = runBlocking {
        saved["url"] = server.url("/").toString()
        server.enqueue(MockResponse().setBody(manifestJson(applicationId = "app.other")))
        val e = runCatching { service.check() }.exceptionOrNull()
        assertEquals(
            "更新包身份不符（疑似串包，已拒绝）：清单=app.other 本机=app.shiguang.shiguang",
            e?.message,
        )
        Unit
    }

    @Test
    fun checkHttpNon200AndFormatError() = runBlocking {
        saved["url"] = server.url("/").toString()
        server.enqueue(MockResponse().setResponseCode(500))
        val e1 = runCatching { service.check() }.exceptionOrNull()
        assertEquals("清单拉取失败：HTTP 500", e1?.message)
        server.enqueue(MockResponse().setBody("""{"versionName":"x"}"""))
        val e2 = runCatching { service.check() }.exceptionOrNull()
        assertTrue(e2?.message?.startsWith("清单格式错误：") == true)
        Unit
    }

    @Test
    fun checkWithoutSourceRejectsVerbatim() = runBlocking {
        val noSource = UpdateService(defaultSourceUrl = "", readSavedSource = { null })
        val e = runCatching { noSource.check() }.exceptionOrNull()
        assertEquals("尚未配置更新源", e?.message)
        Unit
    }

    // ── downloadApk ──

    @Test
    fun downloadVerifiesSha256AndRenames() = runBlocking {
        val payload = "apk-payload-bytes".toByteArray()
        val sha = MessageDigest.getInstance("SHA-256").digest(payload)
            .joinToString("") { "%02x".format(it) }
        server.enqueue(
            MockResponse().setBody(okio.Buffer().write(payload)).setHeader("Content-Length", payload.size),
        )
        var lastProgress = 0 to (null as Int?)
        val file = service.downloadApk(
            server.url("/y.apk").toString(),
            expectedSha256 = sha,
            onProgress = { r, t -> lastProgress = r to t },
        )
        assertTrue(file.exists() && file.name.startsWith("update-"))
        assertEquals(payload.size, lastProgress.first)
        assertEquals(payload.size, lastProgress.second)
        // 临时 .part 已改名消失
        assertTrue(!File(file.parent, "update.apk.part").exists())
        Unit
    }

    @Test
    fun downloadRejectsShaMismatchAndDeletesPart() = runBlocking {
        val payload = "tampered".toByteArray()
        server.enqueue(
            MockResponse().setBody(okio.Buffer().write(payload)).setHeader("Content-Length", payload.size),
        )
        val e = runCatching {
            service.downloadApk(server.url("/y.apk").toString(), expectedSha256 = "00".repeat(32))
        }.exceptionOrNull()
        assertEquals("SHA-256 校验失败，安装包可能被篡改或下载不完整", e?.message)
        assertTrue(!File(tmp.root, "updates/update.apk.part").exists())
        Unit
    }
}
