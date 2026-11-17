package app.shiguang.shiguang.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import app.shiguang.core.model.collab.SecretStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * SecretStore 平台实现（P3 密钥存储，2026-11-11 拍板 B3「Android Keystore +
 * EncryptedSharedPreferences」）：MSK/身份私钥等密钥材料的设备安全区承载。
 *
 * 纪律（KeyStore.kt 同款）：明文密钥永不落普通 SharedPreferences/SQLite——
 * 本类由 AndroidKeyStore 主密钥（MasterKey AES256-GCM）加密的
 * EncryptedSharedPreferences 承载，文件名独立于 Dart 侧 flutter_secure_storage
 * （v11=Tink 自有格式，两源不同名不互通——存量迁移路径见 pending-confirm D11）。
 *
 * 线程口径：读写走 Dispatchers.IO（磁盘读面）；写串行 Mutex，commit 同步刷盘
 * （失败可见，抛 IllegalStateException——对齐 Dart flutter_secure_storage 抛错语义）。
 */
// 真机实证（2026-11-15 仪器测试）：AndroidKeyStore MasterKey 生成+首启建库+写读删
// 往返+独立实例重开持久化——回归钉=AndroidSecretStoreDeviceTest。备份恢复场景不可
// 仪器化验证（adb backup 已弃用）→ (风险) 观察项挂 todos；D11 C 案无兼容路径负担。
class AndroidSecretStore(context: Context) : SecretStore {

    private val prefs: SharedPreferences by lazy {
        val app = context.applicationContext
        val masterKey = MasterKey.Builder(app)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            app,
            "shiguang_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private val writeMutex = Mutex()

    override suspend fun read(key: String): String? =
        withContext(Dispatchers.IO) { prefs.getString(key, null) }

    override suspend fun write(key: String, value: String) {
        withContext(Dispatchers.IO) {
            writeMutex.withLock {
                if (!prefs.edit().putString(key, value).commit()) {
                    throw IllegalStateException("安全区写失败")
                }
            }
        }
    }

    override suspend fun delete(key: String) {
        withContext(Dispatchers.IO) {
            writeMutex.withLock {
                if (!prefs.edit().remove(key).commit()) {
                    throw IllegalStateException("安全区删除失败")
                }
            }
        }
    }
}
