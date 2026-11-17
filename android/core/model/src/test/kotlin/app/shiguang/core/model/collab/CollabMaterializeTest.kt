package app.shiguang.core.model.collab

import app.shiguang.core.model.JdbcSqlStore
import app.shiguang.core.model.ShiguangSchema
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** test/collab_materialize_test.dart Kotlin 移植（真库 + CollabBundleRepository 装配）。 */

private val K = ByteArray(32) { (it + 1).toByte() }

class CollabMaterializeTest {

    /** in-memory 真库 + 仓储 + b1 包头（对齐 Dart setUp：ffi 库 + createAll + nowMs 步进钟）。 */
    private suspend fun newRepo(): CollabBundleRepository {
        val store = JdbcSqlStore.inMemory()
        ShiguangSchema.createAll(store)
        var clock = 0L
        val repo = CollabBundleRepository(store) { clock++ }
        repo.ensureBundle(
            bundleId = "b1",
            proposalType = "DIRECTIVE",
            initiatorId = "u1",
            participants = listOf("u1", "u2", "u3"),
            expiresAtMs = 9999999999999L,
        )
        return repo
    }

    private suspend fun emitSuspend(
        repo: CollabBundleRepository,
        seq: Long,
        action: String,
        payload: JsonObject,
        sender: String,
    ) {
        val sealed = sealPayload(K, payload.toString().toByteArray(Charsets.UTF_8), 1)
        repo.recordEvent(
            bundleId = "b1",
            seqId = seq,
            actionType = action,
            senderId = sender,
            cipher = sealed.encode().toByteArray(Charsets.UTF_8),
            keyVer = 1,
            clientMsgId = "m$seq",
            topologyRef = "t0",
            serverAtMs = 1700000000000L + seq,
        )
    }

    @Test
    fun materializeOverwritesEnsureStepPendingNail() = runTest {
        val repo = newRepo()
        // ensureStep 先塞占位默认行（IGNORE 语义下这行之后再也刷新不了）
        repo.ensureStep(bundleId = "b1", stepId = "s1", requiredUids = listOf("u2", "u3"), threshold = 2)
        assertEquals("PENDING", repo.listSteps("b1").single().status)

        emitSuspend(
            repo, 1, "PROPOSE",
            buildJsonObject {
                put("proposal_type", "DIRECTIVE")
                putJsonArray("skeleton") {
                    add(
                        buildJsonObject {
                            put("step_id", "s1")
                            putJsonArray("required_uids") { add("u2"); add("u3") }
                            put("threshold", 2)
                            put("is_optional", false)
                            put("starts_at_ms", 1700003600000)
                            put("ends_at_ms", 1700007200000)
                        },
                    )
                }
                putJsonArray("participants") { add("u1"); add("u2"); add("u3") }
            },
            sender = "u1",
        )
        emitSuspend(repo, 2, "ACCEPT", buildJsonObject { put("step_id", "s1") }, sender = "u2")
        emitSuspend(repo, 3, "ACCEPT", buildJsonObject { put("step_id", "s1") }, sender = "u3")

        val n = materializeBundle(repo, "b1") { _, _ -> K }
        assertEquals(1, n)

        val step = repo.listSteps("b1").single()
        // 两人同意且达门限 2 → READY（不再是默认 PENDING）
        assertEquals("READY", step.status)
        assertEquals(2, step.threshold)
        // 逐成员表决落表：u2/u3 记 OK
        assertEquals(mapOf("u2" to "OK", "u3" to "OK"), step.votes)
        // 时间槽物化成功（B 页叠块数据源到位）
        assertEquals(1700003600000L, step.startsAtMs)
        assertEquals(1700007200000L, step.endsAtMs)
    }

    @Test
    fun rejectStepTimeClashAndNoVote() = runTest {
        val repo = newRepo()
        emitSuspend(
            repo, 1, "PROPOSE",
            buildJsonObject {
                put("proposal_type", "DIRECTIVE")
                putJsonArray("skeleton") {
                    add(
                        buildJsonObject {
                            put("step_id", "s1")
                            putJsonArray("required_uids") { add("u2"); add("u3") }
                            put("threshold", 2)
                            put("is_optional", false)
                        },
                    )
                }
                putJsonArray("participants") { add("u1"); add("u2"); add("u3") }
            },
            sender = "u1",
        )
        emitSuspend(repo, 2, "ACCEPT", buildJsonObject { put("step_id", "s1") }, sender = "u2")
        emitSuspend(
            repo, 3, "REJECT",
            buildJsonObject { put("step_id", "s1"); put("reason", "TIME_CONFLICT") },
            sender = "u3",
        )

        materializeBundle(repo, "b1") { _, _ -> K }

        val step = repo.listSteps("b1").single()
        assertEquals("TIME_CLASH", step.status)
        assertEquals(mapOf("u2" to "OK", "u3" to "NO"), step.votes)
        // 未抓取到时段 → 两列留空（读侧据此降级）
        assertEquals(null, step.startsAtMs)
        assertEquals(null, step.endsAtMs)
    }

    @Test
    fun missingKeyWritesNothingAndKeepsExisting() = runTest {
        val repo = newRepo()
        repo.ensureStep(bundleId = "b1", stepId = "s-old", requiredUids = listOf("u2"), threshold = 1)
        emitSuspend(
            repo, 1, "PROPOSE",
            buildJsonObject {
                put("proposal_type", "DIRECTIVE")
                putJsonArray("skeleton") {
                    add(
                        buildJsonObject {
                            put("step_id", "s1")
                            putJsonArray("required_uids") { add("u2") }
                            put("threshold", 1)
                            put("is_optional", false)
                        },
                    )
                }
                putJsonArray("participants") { add("u1"); add("u2") }
            },
            sender = "u1",
        )

        val n = materializeBundle(repo, "b1") { _, _ -> null }
        assertEquals(0, n)
        val steps = repo.listSteps("b1")
        assertEquals("s-old", steps.single().id) // 既有行未被抹
    }

    @Test
    fun repeatedMaterializeIdempotent() = runTest {
        val repo = newRepo()
        emitSuspend(
            repo, 1, "PROPOSE",
            buildJsonObject {
                put("proposal_type", "DIRECTIVE")
                putJsonArray("skeleton") {
                    add(
                        buildJsonObject {
                            put("step_id", "s1")
                            putJsonArray("required_uids") { add("u2") }
                            put("threshold", 1)
                            put("is_optional", false)
                        },
                    )
                }
                putJsonArray("participants") { add("u1"); add("u2") }
            },
            sender = "u1",
        )
        repeat(3) { materializeBundle(repo, "b1") { _, _ -> K } }
        assertEquals(1, repo.listSteps("b1").size)
    }
}
