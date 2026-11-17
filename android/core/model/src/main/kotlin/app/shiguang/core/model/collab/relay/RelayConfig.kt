package app.shiguang.core.model.collab.relay

import java.net.URI

/**
 * relay 服务地址配置（lib/collab/relay_config.dart Kotlin 等价，Phase 4 编排收口项）。
 *
 * 所有协同传输层（WSS 建连 / /sync 增量补拉 / /v1/schedule/action 发件箱）的统一
 * 地址来源——engine 的 wireCollabSystem 据此构造三路传输，避免 base URL 散落各处。
 *
 * Dart 侧取值优先级（dart-define > config/relay.env > 模拟器回环兜底）属宿主装配
 * 面（flutter_dotenv/dart-define 为 Flutter 机制）——Kotlin 侧由 :app 装配时解析
 * 后传入 [baseUrl]，本类只承载值与推导（纯 JVM 无环境读取）。
 *
 * [baseUrl] 为 HTTP(S) 基址（不含尾斜杠），WSS 地址由其推导（http(s) → ws(s) 并
 * 追加 /ws 纯路径，对齐 hub.go「纯路径建连 token 不进 URL」）。
 */
class RelayConfig(val baseUrl: String) {

    /** WSS 建连 URI（纯路径 /ws，token 走首帧 AUTH 不进 URL）。 */
    val wssUri: URI
        get() {
            val ws = baseUrl
                .replaceFirst(Regex("^https"), "wss")
                .replaceFirst(Regex("^http"), "ws")
            val trimmed = if (ws.endsWith("/")) ws.substring(0, ws.length - 1) else ws
            return URI("$trimmed/ws")
        }

    override fun toString(): String = "RelayConfig(baseUrl: $baseUrl)"
}
