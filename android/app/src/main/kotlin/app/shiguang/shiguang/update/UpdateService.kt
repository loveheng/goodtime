package app.shiguang.shiguang.update

import android.content.Context
import app.shiguang.shiguang.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

/**
 * 自更新 + 配置热更服务（lib/update/update_service.dart Kotlin 等价；behavior/
 * update_page.md §C 转正清单）：一个可配置的静态源（gitee/github raw、任意静态托管）
 * 放一份 shiguang-update.json，app 检查/下载/校验全在这里完成。
 *
 * 更新源镜像（R1 双写者）：Dart shared_preferences 的 Android 实现存于
 * shared_prefs/FlutterSharedPreferences.xml、键前缀 `flutter.`——读写同文件
 * 同前缀键 `flutter.update_source_url`，双轨期 Dart UI 写入的源 Kotlin 直接可读，
 * P5 切包零迁移。
 *
 * 编译期注入的默认更新源：gradle buildConfigField UPDATE_SOURCE_URL（Dart
 * --dart-define=UPDATE_SOURCE_URL 等价物，D23 裁决；非空时用户未手动配置即自动
 * 采用，实现「装上即可检查更新」）。
 *
 * 测试接缝（OkWeatherProvider fetchImpl 同法）：读写源/包名/落盘目录以函数与
 * 值注入——JVM 测试零 Context 依赖；Android 形状经 [of] 工厂装配。
 */
class UpdateService(
    /** 编译期注入默认源（BuildConfig.UPDATE_SOURCE_URL；测试注入空串）。 */
    private val defaultSourceUrl: String = BuildConfig.UPDATE_SOURCE_URL,
    /** fetch 执行器注入面（测试 seam，镜像 Dart 构造参）。 */
    private val client: OkHttpClient = OkHttpClient(),
    /** 用户手动保存源读面（null=未保存，回退默认源）。 */
    private val readSavedSource: () -> String? = { null },
    /** 用户手动保存源写面（null 入参=移除用户值）。 */
    private val writeSavedSource: (String?) -> Unit = {},
    /** 本机包名（串包校验；null=跳过——测试面）。 */
    private val appPackage: String? = null,
    /** APK 下载落盘目录（Android=documents/updates；测试注入临时目录）。 */
    private val updatesDir: File? = null,
) {

    /** 当前更新源：优先用户手动保存值，否则回退编译期内置默认源。 */
    suspend fun sourceUrl(): String? = withContext(Dispatchers.IO) {
        readSavedSource()?.takeIf { it.isNotEmpty() } ?: defaultSourceUrl.ifEmpty { null }
    }

    /** 保存更新源：trim；空=移除用户值（回退内置默认源）；非 http/https 拒。 */
    suspend fun setSourceUrl(url: String) = withContext(Dispatchers.IO) {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) {
            writeSavedSource(null)
            return@withContext
        }
        val scheme = trimmed.toHttpUrlOrNull()?.scheme
        if (scheme != "https" && scheme != "http") {
            throw UpdateException("更新源必须是合法的 http(s) URL")
        }
        writeSavedSource(trimmed)
    }

    /** 拉取最新清单；源未配置或网络异常抛 [UpdateException]。 */
    suspend fun check(): UpdateManifest = withContext(Dispatchers.IO) {
        val source = sourceUrl()
        if (source.isNullOrEmpty()) throw UpdateException("尚未配置更新源")
        val resp = try {
            client.newCall(Request.Builder().url(manifestUri(source)).build()).execute()
        } catch (e: Exception) {
            throw UpdateException("网络异常：$e")
        }
        resp.use { r ->
            if (r.code != 200) throw UpdateException("清单拉取失败：HTTP ${r.code}")
            val body = r.body?.string() ?: throw UpdateException("网络异常：空响应体")
            val manifest = UpdateManifest.parse(body)
            // 包身份校验：清单声明 applicationId 时须与本机包名一致，防止多 app 共用
            // 统一更新源时串包（下到/装到别人的包或拉到别人的配置）。
            manifest.applicationId?.let { declared ->
                if (declared != appPackage) {
                    throw UpdateException(
                        "更新包身份不符（疑似串包，已拒绝）：清单=$declared 本机=$appPackage",
                    )
                }
            }
            manifest
        }
    }

    /**
     * 流式下载 APK，边下边算 sha256；[expectedSha256] 非空时下载完成即校验，
     * 不符删临时文件再拒（篡改或不完整不落成品）。返回落盘文件；[to] 供测试注入
     * 临时目录。[onProgress] (receivedBytes, totalOrNull)。
     */
    suspend fun downloadApk(
        url: String,
        expectedSha256: String? = null,
        onProgress: (received: Int, total: Int?) -> Unit = { _, _ -> },
        to: File? = null,
    ): File = withContext(Dispatchers.IO) {
        val dir = to ?: updatesDir ?: throw UpdateException("下载目录未就绪")
        dir.mkdirs()
        val dest = File(dir, PART_FILE_NAME)
        val resp = try {
            client.newCall(Request.Builder().url(url).build()).execute()
        } catch (e: Exception) {
            throw UpdateException("下载异常：$e")
        }
        resp.use { r ->
            if (r.code != 200) throw UpdateException("APK 下载失败：HTTP ${r.code}")
            val total = r.body?.contentLength()?.takeIf { it > 0 }
            val digest = MessageDigest.getInstance("SHA-256")
            var received = 0
            val body = r.body ?: throw UpdateException("下载异常：空响应体")
            val source = body.source()
            dest.outputStream().use { sink ->
                val buf = ByteArray(BUFFER_SIZE)
                while (true) {
                    val n = source.read(buf)
                    if (n == -1) break
                    digest.update(buf, 0, n)
                    sink.write(buf, 0, n)
                    received += n
                    onProgress(received, total?.toInt())
                }
                sink.flush()
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (expectedSha256 != null && expectedSha256.lowercase() != actual) {
                dest.delete()
                throw UpdateException("SHA-256 校验失败，安装包可能被篡改或下载不完整")
            }
            val finalFile = File(dir, "update-${System.currentTimeMillis()}.apk")
            if (!dest.renameTo(finalFile)) throw UpdateException("下载异常：临时文件改名失败")
            finalFile
        }
    }

    private fun manifestUri(sourceUrl: String): String {
        val base = if (sourceUrl.endsWith("/")) sourceUrl else "$sourceUrl/"
        return "${base}${MANIFEST_FILE_NAME}"
    }

    companion object {
        const val MANIFEST_FILE_NAME = "shiguang-update.json"
        const val UPDATES_DIR = "updates"
        const val PART_FILE_NAME = "update.apk.part"
        const val BUFFER_SIZE = 8192

        /** Dart shared_preferences 的 Android 存储约定（R1 镜像）。 */
        const val PREFS_NAME = "FlutterSharedPreferences"
        const val PREF_SOURCE_URL = "flutter.update_source_url"
        const val DOCUMENTS_DIR_NAME = "app_flutter"

        /** Android 形状装配（R1 镜像 prefs + documents/updates 落盘）。 */
        fun of(context: Context): UpdateService = UpdateService(
            readSavedSource = {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getString(PREF_SOURCE_URL, null)
            },
            writeSavedSource = { value ->
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                if (value == null) {
                    prefs.edit().remove(PREF_SOURCE_URL).apply()
                } else {
                    prefs.edit().putString(PREF_SOURCE_URL, value).apply()
                }
            },
            appPackage = context.packageName,
            updatesDir = File(context.getDir(DOCUMENTS_DIR_NAME, Context.MODE_PRIVATE), UPDATES_DIR),
        )
    }
}
