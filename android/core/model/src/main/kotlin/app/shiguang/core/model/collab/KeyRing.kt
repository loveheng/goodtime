package app.shiguang.core.model.collab

/**
 * Key Ring：每个方案包的密钥版本链（lib/collab/crypto/key_ring.dart Kotlin 等价，
 * 协同设计 §5.2 / §5.3.1）。
 *
 * REKEY 会产生版本链，回放引擎必须能解任意 seq 的事件——事件携带明文外层
 * `key_ver`，按版本查环解密；缺对应版本且 Vault 无法还原 → 该段历史标
 * 「无法解密」灰显，FSM 跳过内容但骨架仍推进。
 */
class KeyRing {

    private val ring = HashMap<String, MutableMap<Int, ByteArray>>()

    /** 存入某方案包某版本的 K_bundle。同版本覆盖视为轮转异常，此处以静默覆盖处理。 */
    fun put(bundleId: String, keyVer: Int, key: ByteArray) {
        ring.getOrPut(bundleId) { HashMap() }[keyVer] = key.copyOf()
    }

    fun get(bundleId: String, keyVer: Int): ByteArray? = ring[bundleId]?.get(keyVer)?.copyOf()

    /** 该方案包已知的最高版本（未知返回 null）。 */
    fun latestVersion(bundleId: String): Int? = ring[bundleId]?.keys?.maxOrNull()

    /** 明文清零（退后台/锁屏）。 */
    fun clear() = ring.clear()

    val bundleCount: Int get() = ring.size
}
