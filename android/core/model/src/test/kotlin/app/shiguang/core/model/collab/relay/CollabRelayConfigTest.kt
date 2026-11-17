package app.shiguang.core.model.collab.relay

import kotlin.test.Test
import kotlin.test.assertEquals

/** test/collab_relay_config_test.dart Kotlin 移植（RELAY_BASE_URL 单一来源 + WSS 地址推导）。
 *  Dart 侧「fromEnvironment 兜底回环」例属 dart-define/dotenv 装配面，Kotlin 由 :app
 *  注入 baseUrl 承载，不移植。 */
class CollabRelayConfigTest {

    @Test
    fun explicitCtorKeepsBaseUrlAsIs() {
        val c = RelayConfig(baseUrl = "https://relay.example.com:8443")
        assertEquals("https://relay.example.com:8443", c.baseUrl)
    }

    @Test
    fun wssUriDerivesHttpToWsAndAppendsPurePath() {
        val c = RelayConfig(baseUrl = "http://10.0.2.2:8080/")
        assertEquals("ws://10.0.2.2:8080/ws", c.wssUri.toString())
    }

    @Test
    fun wssUriDerivesHttpsToWss() {
        val c = RelayConfig(baseUrl = "https://relay.example.com")
        assertEquals("wss://relay.example.com/ws", c.wssUri.toString())
    }
}
