package app.shiguang.shiguang.data

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * AndroidSecretStore 真机实证（UNCERTAIN 消标轮，2026-11-15）：
 * AndroidKeyStore MasterKey 生成 + EncryptedSharedPreferences 首启建库 +
 * 写/读/删往返 + 独立实例重开持久化（文件面）。
 * 备份恢复场景不在仪器测试可达面（adb backup 已弃用）——消标后转 (风险) 观察项。
 */
@RunWith(AndroidJUnit4::class)
class AndroidSecretStoreDeviceTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val probeKey = "device_test_secret"

    @Test
    fun keystoreRoundtripAndPersistence() = runBlocking {
        val store = AndroidSecretStore(context) // MasterKey 首次生成（AndroidKeyStore 真实面）
        store.write(probeKey, "msk-probe-payload-0123456789abcdef")
        assertEquals("msk-probe-payload-0123456789abcdef", store.read(probeKey))
        // 持久化：独立实例重开同一加密文件（非内存态）
        val reopened = AndroidSecretStore(context)
        assertEquals("msk-probe-payload-0123456789abcdef", reopened.read(probeKey))
        reopened.delete(probeKey)
        assertEquals(null, reopened.read(probeKey))
    }
}
