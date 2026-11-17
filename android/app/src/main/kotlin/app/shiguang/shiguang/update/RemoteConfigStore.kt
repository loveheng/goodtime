package app.shiguang.shiguang.update

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * 远程配置的本地缓存（lib/update/remote_config_store.dart Kotlin 等价；D23 裁决=
 * AppContainer 装配注入，非全局单例）：启动时读缓存，检查更新成功后写缓存。
 * MCP instructions 等配置热更项从这里取值。
 *
 * R1 双写者镜像：缓存目录 = Dart path_provider documents 目录（`getDir("app_flutter")`
 * → /data/data/<pkg>/app_flutter，与 Dart 侧同文件）——双轨期 Dart 写入的缓存
 * Kotlin 直接可读，P5 切包零迁移。缓存文件名 remote_config.json 逐字。
 *
 * [dir] 供测试注入。
 */
class RemoteConfigStore(private val documentsDir: File) {

    /** Android 形状（R1 双写者镜像：Dart path_provider documents 目录）。 */
    constructor(context: Context) : this(
        context.getDir(DOCUMENTS_DIR_NAME, Context.MODE_PRIVATE),
    )

    /** 只读快照（无监听需求——公告行随页面 reload 现取，MCP instructions 每次调用现取）。 */
    @Volatile
    var current: RemoteConfig = RemoteConfig.EMPTY
        private set

    fun load(dir: File? = null) {
        val file = cacheFile(dir)
        if (!file.exists()) return
        current = try {
            RemoteConfig.fromJson(Json.parseToJsonElement(file.readText()) as JsonObject)
        } catch (e: Exception) {
            // DEGRADE: [remote_config_corrupt] 缓存损坏时回到空配置，等下次检查更新重建
            android.util.Log.w("update", "[DEGRADE][remote_config_corrupt] ${e.message}")
            RemoteConfig.EMPTY
        }
    }

    fun save(config: RemoteConfig, dir: File? = null) {
        current = config
        cacheFile(dir).writeText(config.toJson().toString())
    }

    private fun cacheFile(dir: File?): File = dir ?: File(documentsDir, FILE_NAME)

    companion object {
        /** Dart path_provider getApplicationDocumentsDirectory 的 Android 实现（getDir 前缀）。 */
        const val DOCUMENTS_DIR_NAME = "app_flutter"
        const val FILE_NAME = "remote_config.json"
    }
}
