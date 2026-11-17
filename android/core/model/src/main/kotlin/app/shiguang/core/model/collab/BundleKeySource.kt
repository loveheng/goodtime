package app.shiguang.core.model.collab

import app.shiguang.core.model.SqlStore
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 运行期 K_bundle 检索（lib/collab/crypto/bundle_key_source.dart Kotlin 等价，
 * Phase 4.3 收口）。
 *
 * 数据流向：collab_key_ring.envelope（MSK 封送的 K_bundle，密文落本地库）
 * → [KeyStore.open] → 明文 K_bundle（32B）→ 交给 replay 解密事件 / materialize 回填。
 *
 * ## 半盲纪律（铁律 1）
 *
 * 任一环节取不到都返回 **null 而非抛**：vault 未解锁、该 (bundle, version) 无信封、
 * MSK 解封失败（MAC 校验不过）——调用方统一按「这段历史不可解密」处理，骨架仍推进。
 */
class BundleKeySource(
    private val db: SqlStore,
    private val vault: KeyStore,
) {
    /** 取 (bundleId, keyVer) 对应的 K_bundle；取不到返回 null。 */
    suspend operator fun invoke(bundleId: String, keyVer: Int): ByteArray? {
        if (!vault.isUnlocked) return null // vault 未解锁：整段历史半盲
        val rows = db.query(
            "collab_key_ring",
            columns = listOf("envelope"),
            where = "bundle_id = ? AND key_ver = ?",
            args = listOf(bundleId, keyVer),
            limit = 1,
        )
        val envelope = rows.firstOrNull()?.get("envelope") as? String
        if (envelope.isNullOrEmpty()) return null // 未 enrolled / 版本缺失
        return try {
            // KeyStore.open 收的是 SealedBlob JSON 的 utf8 字节，与 [persist] 严格互逆
            vault.open(envelope.toByteArray(Charsets.UTF_8))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null // MAC 校验失败 / 换机后 MSK 对不上：标不可解密
        }
    }

    /**
     * 丢弃某版本的本地信封（认祖归宗用：临时本地 id 迁入服务端 bundle_id 后，
     * 临时条目必须删除，绝不两条并存——并存会让同一份密钥挂两个身份）。
     */
    suspend fun discard(bundleId: String, keyVer: Int): Int =
        db.delete("collab_key_ring", where = "bundle_id = ? AND key_ver = ?", args = listOf(bundleId, keyVer))

    /**
     * 把明文 K_bundle 用 MSK 封送后落 collab_key_ring（同版本**幂等**，不覆盖）。
     * 主键 (bundle_id, key_ver)：REKEY 产生的 key_ver 递增追加新行，旧版本信封保留
     * 供离线回放历史明文。
     */
    suspend fun persist(bundleId: String, keyVer: Int, key: ByteArray) {
        val sealed = vault.seal(key)
        db.insert(
            "collab_key_ring",
            buildJsonObject {
                put("bundle_id", bundleId)
                put("key_ver", keyVer)
                put("envelope", String(sealed, Charsets.UTF_8))
                put("created_at", System.currentTimeMillis())
            },
            ignore = true,
        )
    }
}

/**
 * 被邀端密钥入户（lib/collab/enroll.dart Kotlin 等价，§5.3 分发的收件侧）。
 * 三件事：拉自己那份信封 → 本地 SPK 私钥解开 → 落户 collab_key_ring（MSK 封送）。
 *
 * ## 半盲纪律（铁律 1）
 *
 * 任一环节取不到都**跳过而非抛**：没落钥的那段历史对本地就是不可解密，骨架照常
 * 推进。绝不为了让列表好看而写入空密钥或伪造版本。
 */
class BundleKeyIntake(
    /** 拉信封（真机 = fetchBundleKeyEnvelopes 的注入版）。 */
    private val fetch: suspend (String) -> List<KeyEnvelopeView>,
    /** 本地密钥检索 / 落户（MSK 封送）。 */
    private val keys: BundleKeySource,
    /** 本端 X25519 SPK 私钥 seed（未解锁即抛，由调用方降级）。 */
    private val mySignedPreKey: suspend () -> ByteArray,
) {
    /** 把该包里「还没落户」的信封逐条解开并落户，返回本次新落户条数。
     *  [already] 可选：调用方已知版本时可省一次解密；缺省逐条问 keys。 */
    suspend fun enroll(bundleId: String, already: Set<Int>? = null): Int {
        val envelopes = try {
            fetch(bundleId)
        } catch (_: Exception) {
            return 0 // 拉不到（离线/403/5xx）：下次补拉，骨架照常推进
        }
        if (envelopes.isEmpty()) return 0

        val spk = mySignedPreKey()
        var enrolled = 0
        for (env in envelopes) {
            if (env.wrappedKey.isEmpty()) continue
            if (already?.contains(env.keyVer) == true) continue
            if (keys(bundleId, env.keyVer) != null) continue // 已落户：幂等跳过
            try {
                val key = openBundleKeyEnvelope(env.toEnvelope(), spk)
                if (key.size != 32) continue
                keys.persist(bundleId, env.keyVer, key)
                enrolled++
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                continue // MAC 校验失败（不是给我的信封/密钥换了）：静默跳过
            }
        }
        return enrolled
    }
}
