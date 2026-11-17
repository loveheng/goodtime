package app.shiguang.core.model.collab

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.File
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * P0 黄金向量对拍执行器（Kotlin 侧）——E2EE 封装往返（sealing + freemask）。
 *
 * 对拍口径（向量集 rules 节，同 Dart 基准执行器 golden_e2ee_test）：
 * - pad_512/aesgcm/seal_composite/ecies = 信封重组 → 真实现 open 链验证（实现漂移即红）；
 * - envelope_json = 编解码见证（encode 重建与见证串逐位一致——键序=产出序）；
 * - 拒绝 = 语义状态码（reject_argument/reject_auth/reject_format，异常类非契约）；
 * - freemask 信封向量记 hex，真实现为 base64 形态，过实现前按字段转码；
 * - 附活性往返性质（随机 nonce 路径）。
 * 向量文件为只读契约，实现漂移唯一出路修实现（dev-loop 测试写保护）。
 */
class GoldenE2eeTest {

    private fun loadSet(name: String): JsonObject {
        // 模块工作目录 = android/core/model → 仓库根 = ../../../
        val f = File("../../../test/golden/$name")
        check(f.exists()) { "向量文件不存在: ${f.absolutePath}（工作目录须为 :core:model 模块根）" }
        return Json.parseToJsonElement(f.readText()).jsonObject
    }

    private fun hexOf(s: String): ByteArray =
        ByteArray(s.length / 2) { i ->
            s.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }

    private fun toHex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    private fun b64OfHex(h: String): String = b64Encode(hexOf(h))

    /** 按向量语义状态码断言拒绝行为（异常类非契约——2026-11-14 拍板⑤）。 */
    private fun assertReject(id: String, status: String, block: () -> Unit) {
        val e = runCatching(block).exceptionOrNull()
        assertTrue(e != null, "$id: 预期拒绝但成功")
        when (status) {
            "reject_argument" -> assertTrue(
                e is IllegalArgumentException, "$id: 预期 ArgumentError，got $e",
            )
            "reject_auth" -> assertTrue(
                e is AEADBadTagException, "$id: 预期 MAC 校验失败，got $e",
            )
            "reject_format" -> {} // 任何异常即过
            else -> fail("$id: 未知语义状态码 $status")
        }
    }

    private fun envelopeOf(env: JsonObject) = SealedPayload(
        keyVer = env["key_ver"]!!.jsonPrimitive.long.toInt(),
        nonce = hexOf(env["nonce"]!!.jsonPrimitive.content),
        cipherText = hexOf(env["cipher_text"]!!.jsonPrimitive.content),
        mac = hexOf(env["mac"]!!.jsonPrimitive.content),
    )

    /** freemask ECIES 信封（hex 向量形态）→ 真实现信封（base64 形态）。 */
    private fun eciesEnvelopeOf(hexEnv: JsonObject): JsonObject = buildJsonObject {
        put("ek_pub", b64OfHex(hexEnv["ek_pub"]!!.jsonPrimitive.content))
        put("nonce", b64OfHex(hexEnv["nonce"]!!.jsonPrimitive.content))
        put("cipher_text", b64OfHex(hexEnv["cipher_text"]!!.jsonPrimitive.content))
        put("mac", b64OfHex(hexEnv["mac"]!!.jsonPrimitive.content))
    }

    private val runnerKey = hexOf(
        "5152535455565758595a5b5c5d5e5f606162636465666768696a6b6c6d6e6f70",
    )
    private val runnerNonce = hexOf("101112131415161718191a1b")

    // ============================================================================
    // sealing 向量集（31 case）
    // ============================================================================

    @Test
    fun `sealing 向量全量对拍`() {
        val set = loadSet("e2ee_sealing_vectors.json")
        val cases = set["cases"] as? JsonArray ?: error("cases 缺失")
        for (c in cases) {
            val o = c.jsonObject
            val id = o["id"]!!.jsonPrimitive.content
            val input = o["input"]!!.jsonObject
            val expect = o["expect"]!!.jsonObject
            when (o["op"]!!.jsonPrimitive.content) {
                "pad_512" -> {
                    // 文档化 pad 规则走 open 链验证：向量 padded → 自洽信封 → unpad 回原文
                    val (ct, mac) = aesGcmSeal(runnerKey, runnerNonce, hexOf(expect["padded"]!!.jsonPrimitive.content))
                    val env = SealedPayload(0, runnerNonce, ct, mac)
                    assertEquals(
                        input["plaintext"]!!.jsonPrimitive.content,
                        toHex(openPayload(runnerKey, env)), id,
                    )
                }
                "aesgcm_encrypt" -> {
                    val (ct, mac) = aesGcmSeal(
                        hexOf(input["key"]!!.jsonPrimitive.content),
                        hexOf(input["nonce"]!!.jsonPrimitive.content),
                        hexOf(input["padded"]!!.jsonPrimitive.content),
                    )
                    assertEquals(expect["cipher_text"]!!.jsonPrimitive.content, toHex(ct), id)
                    assertEquals(expect["mac"]!!.jsonPrimitive.content, toHex(mac), id)
                }
                "seal_composite" -> {
                    val key = hexOf(input["key"]!!.jsonPrimitive.content)
                    val plaintext = hexOf(input["plaintext"]!!.jsonPrimitive.content)
                    val env = SealedPayload(
                        input["key_ver"]!!.jsonPrimitive.long.toInt(),
                        hexOf(input["nonce"]!!.jsonPrimitive.content),
                        hexOf(expect["cipher_text"]!!.jsonPrimitive.content),
                        hexOf(expect["mac"]!!.jsonPrimitive.content),
                    )
                    assertEquals(toHex(plaintext), toHex(openPayload(key, env)), id)
                    // 活性 seal 路径同明文往返（随机 nonce，性质校验）
                    val live = sealPayload(key, plaintext, input["key_ver"]!!.jsonPrimitive.long.toInt())
                    assertEquals(toHex(plaintext), toHex(openPayload(key, live)), "$id live")
                }
                "envelope_json" -> {
                    val decoded = SealedPayload.decode(expect["json"]!!.jsonPrimitive.content)
                    assertEquals(input["key_ver"]!!.jsonPrimitive.long.toInt(), decoded.keyVer, id)
                    assertEquals(input["nonce"]!!.jsonPrimitive.content, toHex(decoded.nonce), id)
                    assertEquals(input["cipher_text"]!!.jsonPrimitive.content, toHex(decoded.cipherText), id)
                    assertEquals(input["mac"]!!.jsonPrimitive.content, toHex(decoded.mac), id)
                    // 编解码见证：encode 重建与见证串逐位一致（键序 = 产出序）
                    assertEquals(expect["json"]!!.jsonPrimitive.content, decoded.encode(), id)
                }
                "open" -> {
                    val key = hexOf(input["key"]!!.jsonPrimitive.content)
                    val env = envelopeOf(input["envelope"]!!.jsonObject)
                    val status = expect["status"]?.jsonPrimitive?.contentOrNull
                    if (status != null) {
                        assertReject(id, status) { openPayload(key, env) }
                    } else {
                        assertEquals(expect["plaintext"]!!.jsonPrimitive.content, toHex(openPayload(key, env)), id)
                    }
                }
                "seal_len" -> {
                    val live = sealPayload(ByteArray(32) { 7 }, hexOf(input["plaintext"]!!.jsonPrimitive.content), 1)
                    assertEquals(expect["cipher_text_len"]!!.jsonPrimitive.long.toInt(), live.cipherText.size, id)
                    assertEquals(2, live.cipherText.size % 512, "$id: 密文长度 ≡ 2 mod 512")
                }
                "envelope_malformed" -> {
                    assertReject(id, expect["status"]!!.jsonPrimitive.content) {
                        SealedPayload.decode(input["json"]!!.jsonPrimitive.content)
                    }
                }
                "keyring_get_unknown" -> {
                    val ring = KeyRing()
                    for (e in input["setup"]!!.jsonArray) {
                        val s = e.jsonObject
                        ring.put(
                            s["bundle"]!!.jsonPrimitive.content,
                            s["key_ver"]!!.jsonPrimitive.long.toInt(),
                            hexOf(s["key"]!!.jsonPrimitive.content),
                        )
                    }
                    val found = ring.get(
                        input["probe_bundle"]!!.jsonPrimitive.content,
                        input["probe_key_ver"]!!.jsonPrimitive.long.toInt(),
                    )
                    assertEquals(null, found, id)
                }
                "seal_guard" -> {
                    assertReject(id, expect["status"]!!.jsonPrimitive.content) {
                        sealPayload(
                            hexOf(input["key"]!!.jsonPrimitive.content),
                            hexOf(input["plaintext"]!!.jsonPrimitive.content),
                            input["key_ver"]!!.jsonPrimitive.long.toInt(),
                        )
                    }
                }
                else -> fail("未知 op: ${o["op"]!!.jsonPrimitive.content}（向量集与执行器失配，须同步更新）")
            }
        }
    }

    // ============================================================================
    // freemask 向量集（17 case）
    // ============================================================================

    @Test
    fun `freemask 向量全量对拍`() {
        val set = loadSet("e2ee_freemask_vectors.json")
        val cases = set["cases"] as? JsonArray ?: error("cases 缺失")
        for (c in cases) {
            val o = c.jsonObject
            val id = o["id"]!!.jsonPrimitive.content
            val input = o["input"]!!.jsonObject
            val expect = o["expect"]!!.jsonObject
            when (o["op"]!!.jsonPrimitive.content) {
                "x25519_publickey" -> {
                    assertEquals(
                        expect["pub"]!!.jsonPrimitive.content,
                        toHex(X25519Ops.publicKeyFromSeed(hexOf(input["seed"]!!.jsonPrimitive.content))),
                        id,
                    )
                }
                "x25519_dh" -> {
                    assertEquals(
                        expect["shared"]!!.jsonPrimitive.content,
                        toHex(
                            X25519Ops.dh(
                                hexOf(input["ek_seed"]!!.jsonPrimitive.content),
                                hexOf(input["spk_pub"]!!.jsonPrimitive.content),
                            ),
                        ),
                        id,
                    )
                }
                "quantize" -> {
                    val busy = input["busy"]!!.jsonArray.map { e ->
                        val pair = e.jsonArray
                        Pair(pair[0].jsonPrimitive.long, pair[1].jsonPrimitive.long)
                    }
                    val status = expect["status"]?.jsonPrimitive?.contentOrNull
                    if (status != null) {
                        assertReject(id, status) {
                            quantizeFreeMask(
                                input["target_start"]!!.jsonPrimitive.long,
                                input["target_end"]!!.jsonPrimitive.long,
                                busy,
                            )
                        }
                    } else {
                        assertEquals(
                            expect["mask"]!!.jsonPrimitive.content,
                            quantizeFreeMask(
                                input["target_start"]!!.jsonPrimitive.long,
                                input["target_end"]!!.jsonPrimitive.long,
                                busy,
                            ),
                            id,
                        )
                    }
                }
                "bitwise_and" -> {
                    val masks = input["masks"]!!.jsonArray.map { it.jsonPrimitive.content }
                    val status = expect["status"]?.jsonPrimitive?.contentOrNull
                    if (status != null) {
                        assertReject(id, status) { freeMaskBitwiseAnd(masks) }
                    } else {
                        assertEquals(expect["mask"]!!.jsonPrimitive.content, freeMaskBitwiseAnd(masks), id)
                    }
                }
                "ecies_seal" -> {
                    val spkSeed = hexOf(input["spk_seed"]!!.jsonPrimitive.content)
                    val env = eciesEnvelopeOf(buildJsonObject {
                        put("ek_pub", expect["ek_pub"]!!.jsonPrimitive.content)
                        put("nonce", input["nonce"]!!.jsonPrimitive.content)
                        put("cipher_text", expect["cipher_text"]!!.jsonPrimitive.content)
                        put("mac", expect["mac"]!!.jsonPrimitive.content)
                    })
                    val signal = input["signal"]!!.jsonObject
                    val opened = openFreeMaskFromMember(env, spkSeed)
                    assertEquals(signal["target_start"]!!.jsonPrimitive.long, opened.targetStartMs, id)
                    assertEquals(signal["target_end"]!!.jsonPrimitive.long, opened.targetEndMs, id)
                    assertEquals(signal["mask"]!!.jsonPrimitive.content, opened.mask, id)
                    // 明文序列化契约：signal JSON 紧凑形逐位一致（字段序 = interop 契约）
                    assertEquals(input["signal_json"]!!.jsonPrimitive.content, signal.toString(), id)
                    // 活性 seal 路径：新 EK 往返（随机 nonce，性质校验）
                    val freshEk = ByteArray(32).also(SecureRandom()::nextBytes)
                    val liveSealed = sealFreeMaskForInitiator(
                        FreeMaskSignal(
                            signal["target_start"]!!.jsonPrimitive.long,
                            signal["target_end"]!!.jsonPrimitive.long,
                            signal["mask"]!!.jsonPrimitive.content,
                        ),
                        freshEk,
                        X25519Ops.publicKeyFromSeed(spkSeed),
                    )
                    val liveOpened = openFreeMaskFromMember(liveSealed, spkSeed)
                    assertEquals(signal["mask"]!!.jsonPrimitive.content, liveOpened.mask, "$id live")
                }
                "ecies_open" -> {
                    val spkSeed = hexOf(input["spk_seed"]!!.jsonPrimitive.content)
                    val env = eciesEnvelopeOf(input["envelope"]!!.jsonObject)
                    val status = expect["status"]?.jsonPrimitive?.contentOrNull
                    if (status != null) {
                        assertReject(id, status) { openFreeMaskFromMember(env, spkSeed) }
                    } else {
                        val opened = openFreeMaskFromMember(env, spkSeed)
                        assertEquals(expect["target_start"]!!.jsonPrimitive.long, opened.targetStartMs, id)
                        assertEquals(expect["target_end"]!!.jsonPrimitive.long, opened.targetEndMs, id)
                        assertEquals(expect["mask"]!!.jsonPrimitive.content, opened.mask, id)
                    }
                }
                else -> fail("未知 op: ${o["op"]!!.jsonPrimitive.content}（向量集与执行器失配，须同步更新）")
            }
        }
    }
}
