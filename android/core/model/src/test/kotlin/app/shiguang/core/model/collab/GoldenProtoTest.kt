package app.shiguang.core.model.collab

import com.google.protobuf.ByteString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import shiguang.relay.v1.RelayProtocol.ActionType
import shiguang.relay.v1.RelayProtocol.ClientFrame
import shiguang.relay.v1.RelayProtocol.Envelope
import shiguang.relay.v1.RelayProtocol.MemberKeyEnvelope
import shiguang.relay.v1.RelayProtocol.ServerFrame
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * P0 黄金向量对拍执行器（Kotlin 侧）——relay proto 字节（protobuf-javalite 重生成，
 * schedule-app §11 拍板）。
 *
 * 对拍口径（向量集 rules 节，同 Dart 基准执行器 golden_proto_test）：
 * - construction：按 input.fields 构造同字段消息 → toByteArray → hex 逐位比对；
 * - serialize 兼 parse 断言：字段值 + oneof 分支（which_body）；member_key_envelopes
 *   顺序即追加序；unknown fields 解析后按原 tag 保留回写（拍板③）；
 * - **proto_default_scalars 差异口径（rules.default_scalars_semantics 拍板②）**：Dart
 *   生成物 has-语义显式编码默认值（1000180022004800）；javalite 按 proto3 隐式 presence
 *   不编码 → 本执行器钉 javalite 实然（空 buffer）+ 解析 Dart 字节回默认值——对拍红
 *   ≠ Kotlin 错，语义差异即本 case 的契约内容。
 * 向量文件为只读契约，实现漂移唯一出路修实现（dev-loop 测试写保护）。
 */
class GoldenProtoTest {

    private val vectors: JsonObject by lazy {
        val f = File("../../../test/golden/relay_proto_vectors.json")
        check(f.exists()) { "向量文件不存在: ${f.absolutePath}（工作目录须为 :core:model 模块根）" }
        Json.parseToJsonElement(f.readText()).jsonObject
    }

    private fun hexOf(s: String): ByteArray =
        ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

    private fun toHex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    private fun bytesOf(s: String): ByteString = ByteString.copyFrom(hexOf(s))

    private fun strField(o: JsonObject, k: String): String? =
        (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.takeIf { o[k] !is kotlinx.serialization.json.JsonNull }

    private fun longField(o: JsonObject, k: String): Long? =
        (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.long

    /** input.fields → Envelope（null=字段缺省不设置；fixups 规则）。 */
    private fun envelopeOf(fields: JsonObject): Envelope {
        val b = Envelope.newBuilder()
        strField(fields, "bundle_id")?.let { b.bundleId = it }
        strField(fields, "action")?.let { b.action = ActionType.valueOf(it) }
        longField(fields, "seq_id")?.let { b.seqId = it }
        strField(fields, "sender_hint")?.let { b.senderHint = it }
        strField(fields, "cipher")?.let { b.cipher = bytesOf(it) }
        strField(fields, "client_msg_id")?.let { b.clientMsgId = it }
        longField(fields, "client_ts")?.let { b.clientTs = it }
        strField(fields, "topology_ref")?.let { b.topologyRef = it }
        longField(fields, "key_ver")?.let { b.keyVer = it.toInt() }
        for (e in (fields["member_key_envelopes"] as? kotlinx.serialization.json.JsonArray ?: JsonArray(emptyList()))) {
            val o = e.jsonObject
            b.addMemberKeyEnvelopes(
                MemberKeyEnvelope.newBuilder()
                    .setRecipientUserId(o["recipient_user_id"]!!.jsonPrimitive.content)
                    .setWrappedKey(bytesOf(o["wrapped_key"]!!.jsonPrimitive.content))
                    .setKeyVer(o["key_ver"]!!.jsonPrimitive.long.toInt())
                    .build(),
            )
        }
        return b.build()
    }

    private fun envelopeJsonOf(env: Envelope, expected: JsonObject) {
        val p = expected["parsed"]!!.jsonObject
        // 字段值断言（javalite proto3 隐式 presence 无 has*()——值即实然语义）
        val wantSeq = p["seq_id"]?.jsonPrimitive?.long
        if (wantSeq != null) assertEquals(wantSeq, env.seqId, "seq_id")
        val wantAction = p["action"]?.jsonPrimitive?.contentOrNull
        if (wantAction != null) assertEquals(ActionType.valueOf(wantAction), env.action, "action")
        val wantBundle = p["bundle_id"]?.jsonPrimitive?.contentOrNull
        if (wantBundle != null) assertEquals(wantBundle, env.bundleId, "bundle_id")
    }

    @Test
    fun `relay-proto 向量全量对拍`() {
        val cases = vectors["cases"] as? JsonArray ?: error("cases 缺失")
        for (c in cases) {
            val o = c.jsonObject
            val id = o["id"]!!.jsonPrimitive.content
            val input = o["input"]!!.jsonObject
            val expect = o["expect"]!!.jsonObject
            when (o["op"]!!.jsonPrimitive.content) {
                "serialize_envelope" -> {
                    val env = envelopeOf(input["fields"]!!.jsonObject)
                    val bytes = env.toByteArray()
                    if (id == "proto_default_scalars") {
                        // 拍板②差异口径：javalite 隐式 presence——显式 set 默认值不编码
                        assertEquals("", toHex(bytes), "$id（javalite 实然=空 buffer）")
                        // 解析 Dart has-语义字节 → 全默认值（语义差异即契约内容）
                        val fromDart = Envelope.parseFrom(hexOf(expect["bytes_hex"]!!.jsonPrimitive.content))
                        assertEquals(0L, fromDart.seqId, id)
                        assertEquals(ActionType.PROPOSE, fromDart.action, id)
                        assertEquals("", fromDart.senderHint, id)
                        assertEquals(0, fromDart.keyVer, id)
                    } else {
                        assertEquals(expect["bytes_hex"]!!.jsonPrimitive.content, toHex(bytes), id)
                        val reparsed = Envelope.parseFrom(bytes)
                        envelopeJsonOf(reparsed, expect)
                        // member_key_envelopes 顺序即追加序
                        val wantEnvelopes = input["fields"]!!.jsonObject["member_key_envelopes"]!!.jsonArray
                        assertEquals(wantEnvelopes.size, reparsed.memberKeyEnvelopesCount, "$id 信封数")
                        for ((i, e) in wantEnvelopes.withIndex()) {
                            val row = e.jsonObject
                            assertEquals(
                                row["recipient_user_id"]!!.jsonPrimitive.content,
                                reparsed.getMemberKeyEnvelopes(i).recipientUserId,
                                "$id[$i] recipient",
                            )
                        }
                    }
                }
                "serialize_frame" -> {
                    val kind = input["kind"]!!.jsonPrimitive.content
                    if (kind == "envelope_from_bytes") {
                        // unknown fields 向前兼容（拍板③）：解析 → unknown 保留 → 回写逐位一致
                        val raw = hexOf(input["bytes_hex"]!!.jsonPrimitive.content)
                        val parsed = Envelope.parseFrom(raw)
                        val p = expect["parsed"]!!.jsonObject
                        assertEquals(p["bundle_id"]!!.jsonPrimitive.content, parsed.bundleId, id)
                        assertEquals(ActionType.valueOf(p["action"]!!.jsonPrimitive.content), parsed.action, id)
                        assertEquals(p["seq_id"]!!.jsonPrimitive.long, parsed.seqId, id)
                        // unknown 保留的直接证明 = 回写逐位一致（javalite unknownFields 不公开）
                        assertEquals(
                            expect["re_bytes_hex"]!!.jsonPrimitive.content,
                            toHex(parsed.toByteArray()),
                            "$id 回写逐位一致（unknown 按原 tag 保留，拍板③）",
                        )
                        continue
                    }
                    val body = input["body"]!!.jsonObject
                    val bytes: ByteArray
                    val bodyKind = body["kind"]!!.jsonPrimitive.content
                    if (kind == "server_frame") {
                        val b = ServerFrame.newBuilder()
                        b.seqId = input["seq_id"]!!.jsonPrimitive.long
                        b.clientMsgId = input["client_msg_id"]!!.jsonPrimitive.content
                        when (bodyKind) {
                            "schedule" -> b.schedule = envelopeOf(body["envelope"]!!.jsonObject)
                            "error" -> b.error = body["error"]!!.jsonPrimitive.content
                            else -> fail("$id: 未知 body kind $bodyKind")
                        }
                        bytes = b.build().toByteArray()
                        assertEquals(
                            expect["parsed"]!!.jsonObject["which_body"]!!.jsonPrimitive.content,
                            ServerFrame.parseFrom(bytes).bodyCase.toString().substringAfterLast("_").lowercase(),
                            id,
                        )
                    } else {
                        val b = ClientFrame.newBuilder()
                        b.clientMsgId = input["client_msg_id"]!!.jsonPrimitive.content
                        b.clientTs = input["client_ts"]!!.jsonPrimitive.long
                        when (bodyKind) {
                            "schedule" -> b.schedule = envelopeOf(body["envelope"]!!.jsonObject)
                            else -> fail("$id: 未知 body kind $bodyKind")
                        }
                        bytes = b.build().toByteArray()
                        assertEquals(
                            expect["parsed"]!!.jsonObject["which_body"]!!.jsonPrimitive.content,
                            ClientFrame.parseFrom(bytes).bodyCase.toString().substringAfterLast("_").lowercase(),
                            id,
                        )
                    }
                    assertEquals(expect["bytes_hex"]!!.jsonPrimitive.content, toHex(bytes), id)
                }
                else -> fail("未知 op: ${o["op"]!!.jsonPrimitive.content}（向量集与执行器失配，须同步更新）")
            }
        }
    }
}
