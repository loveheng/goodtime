package app.shiguang.shiguang.update

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 远程配置缓存对拍（N8c，update_page.md §B 转正清单）：save/load roundtrip（四键
 * 条件序列化+flags 任意值保真）+ 缓存损坏回空配置（DEGRADE [remote_config_corrupt]）。
 */
class RemoteConfigStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun saveLoadRoundtripPreservesAllKeys() {
        val dir = tmp.newFolder()
        val store = RemoteConfigStore(dir)
        val config = RemoteConfig(
            announcement = "公告 A",
            announcementId = "a1",
            mcpInstructions = "远程指令",
            flags = mapOf("flag_on" to JsonPrimitive(true), "note" to JsonPrimitive("文本")),
        )
        store.save(config)
        assertEquals(config, store.current)
        // 换实例 load 回读（R1 同文件）
        val store2 = RemoteConfigStore(dir)
        assertNull(store2.current.announcement)
        store2.load()
        assertEquals("公告 A", store2.current.announcement)
        assertEquals("a1", store2.current.announcementId)
        assertEquals("远程指令", store2.current.mcpInstructions)
        val flag = store2.current.flags["flag_on"] as? JsonPrimitive
        assertTrue(flag?.booleanOrNull == true)
        assertEquals("文本", (store2.current.flags["note"] as? JsonPrimitive)?.content)
        // 四键条件序列化：空 config 不写任何键
        store.save(RemoteConfig.EMPTY)
        val raw = kotlinx.serialization.json.Json.parseToJsonElement(
            java.io.File(dir, RemoteConfigStore.FILE_NAME).readText(),
        ).jsonObject
        assertTrue(raw.isEmpty())
    }

    @Test
    fun corruptCacheFallsBackToEmpty() {
        val dir = tmp.newFolder()
        java.io.File(dir, RemoteConfigStore.FILE_NAME).writeText("not-json{{{")
        val store = RemoteConfigStore(dir)
        store.load()
        assertEquals(RemoteConfig.EMPTY, store.current)
    }

    @Test
    fun missingCacheKeepsEmpty() {
        val store = RemoteConfigStore(tmp.newFolder())
        store.load()
        assertEquals(RemoteConfig.EMPTY, store.current)
    }
}
