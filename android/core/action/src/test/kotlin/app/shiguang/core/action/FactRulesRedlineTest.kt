package app.shiguang.core.action

import app.shiguang.core.model.Artifact
import app.shiguang.core.model.JdbcSqlStore
import app.shiguang.core.model.ShiguangSchema
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 事实硬红线行为测试（test/commands_facts_redline_test.dart 5 例移植，§9.2/§9.3）：
 * rule_scope 建档严进 + propose/adjust 子树校验 + rejected_by_facts 拒绝体。
 * 双轨期桥端口不可用——全部走原生 NativeSqlRepository（JdbcSqlStore 测试驱动）。
 */
class FactRulesRedlineTest {

    private suspend fun fresh(): Triple<JdbcSqlStore, NativeSqlRepository, CommandHandler> {
        val store = JdbcSqlStore.inMemory()
        ShiguangSchema.createAll(store)
        val repo = NativeSqlRepository(store)
        repo.settingsSet(buildJsonObject {
            put("wake_time", "420"); put("sleep_time", "1380")
        })
        return Triple(store, repo, CommandHandler(repo))
    }

    /** 周一闭馆规则凭证（周一=2026-10-26）：rule 类 + 生效窗 + forbidden_weekdays[1]。 */
    private suspend fun ruleFact(repo: NativeSqlRepository, planId: String): String =
        repo.addArtifact(buildJsonObject {
            put("category", "venue")
            put("source_kind", "announcement")
            put("state", "structured")
            put("origin", "shared")
            put("title", "玉龙雪山景区周一闭馆")
            put("payload", Artifact.encodePayload(buildJsonObject {
                put("time_anchors", JsonArray(listOf(buildJsonObject {
                    put("role", "reference"); put("kind", "rule"); put("text", "周一闭馆")
                })))
                put("rule_scope", buildJsonObject {
                    put("valid_from", "2026-01-01"); put("valid_to", "2026-12-31")
                })
                put("constraints", buildJsonObject {
                    put("forbidden_weekdays", JsonArray(listOf(JsonPrimitive(1))))
                })
            }))
            put("plan_id", planId)
        }, null)["id"]?.jsonPrimitive?.contentOrNull!!

    private fun proposeJson(date: String, planId: String): JsonObject = buildJsonObject {
        put("op", "propose_schedule")
        put("date", date)
        put("items", JsonArray(listOf(buildJsonObject {
            put("plan_id", planId)
            put("label", "进山")
            put("start_min", 600)
            put("end_min", 660)
        })))
    }

    // ---- rule_scope 建档严进（§9.2 Q-A） --------------------------------------------

    @Test
    fun `rule 建档缺 rule_scope 即拒`() = runTest {
        val (_, _, handler) = fresh()
        val e = assertFailsWith<ActionException> {
            handler.execute(ScheduleCommandCodec.fromJson(buildJsonObject {
                put("op", "upsert_facts")
                put("category", "venue"); put("source_kind", "announcement")
                put("title", "闭馆"); put("state", "structured")
                put("time_anchors", JsonArray(listOf(buildJsonObject {
                    put("role", "reference"); put("kind", "rule"); put("text", "周一闭馆")
                })))
            }), CommandActor.AI)
        }
        assertEquals(ActionErrorCode.INVALID_REQUEST, e.code)
    }

    @Test
    fun `rule 建档给齐 rule_scope 通过且落库`() = runTest {
        val (_, repo, handler) = fresh()
        val r = handler.execute(ScheduleCommandCodec.fromJson(buildJsonObject {
            put("op", "upsert_facts")
            put("category", "venue"); put("source_kind", "announcement")
            put("title", "闭馆"); put("state", "structured")
            put("time_anchors", JsonArray(listOf(buildJsonObject {
                put("role", "reference"); put("kind", "rule"); put("text", "周一闭馆")
            })))
            put("rule_scope", buildJsonObject {
                put("valid_from", "2026-01-01"); put("valid_to", "2026-12-31")
            })
            put("constraints", buildJsonObject {
                put("forbidden_weekdays", JsonArray(listOf(JsonPrimitive(1))))
            })
            put("origin", "shared")
        }), CommandActor.AI)
        val back = repo.artifactById(r.targetId!!)!!
        val payload = back["payload"] as? JsonObject ?: JsonObject(emptyMap())
        assertTrue(payload.containsKey("rule_scope"), "rule_scope 落库（读侧缺省=长期生效，写侧 kind=rule 严进）")
        assertEquals("venue", back["category"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `非 rule 类凭证无需 rule_scope`() = runTest {
        val (_, _, handler) = fresh()
        val r = handler.execute(ScheduleCommandCodec.fromJson(buildJsonObject {
            put("op", "upsert_facts")
            put("category", "transit"); put("source_kind", "booking")
            put("title", "D8724"); put("state", "structured")
            put("time_anchors", JsonArray(listOf(buildJsonObject {
                put("role", "departure"); put("kind", "moment")
                put("date", "2026-10-20"); put("min", 540)
            })))
            put("origin", "shared")
        }), CommandActor.AI)
        assertEquals("transit", r.snapshot?.get("category")?.jsonPrimitive?.contentOrNull)
    }

    // ---- 硬红线子树校验 + rejected_by_facts（§9.3） ----------------------------------

    @Test
    fun `AI 在周一闭馆日排块 即 schedule_rejected 含 rejected_by_facts`() = runTest {
        val (_, repo, handler) = fresh()
        val plan = repo.addPlan(buildJsonObject { put("title", "云南七天") })
        ruleFact(repo, plan["id"]?.jsonPrimitive?.contentOrNull!!)
        val e = assertFailsWith<ActionException> {
            handler.execute(
                ScheduleCommandCodec.fromJson(proposeJson("2026-10-26", plan["id"]?.jsonPrimitive?.contentOrNull!!)),
                CommandActor.AI,
            )
        }
        assertEquals(ActionErrorCode.SCHEDULE_REJECTED, e.code)
        val rejections = e.data?.get("rejections") as? JsonArray ?: JsonArray(emptyList())
        assertTrue(rejections.any { r ->
            (((r as? JsonObject)?.get("data") as? JsonObject))
                ?.containsKey("rejected_by_facts") == true
        }, "rejections 应含 rejected_by_facts: ${e.data}")
    }

    @Test
    fun `周二排块不触红线 星期归因正确`() = runTest {
        val (_, repo, handler) = fresh()
        val plan = repo.addPlan(buildJsonObject { put("title", "云南七天") })
        ruleFact(repo, plan["id"]?.jsonPrimitive?.contentOrNull!!)
        val r = handler.execute(
            ScheduleCommandCodec.fromJson(proposeJson("2026-10-27", plan["id"]?.jsonPrimitive?.contentOrNull!!)),
            CommandActor.AI,
        )
        assertEquals(1, ((r.data?.get("blocks") as? JsonArray) ?: JsonArray(emptyList())).size)
    }

    @Test
    fun `超出生效窗的 rule 不参检`() = runTest {
        val (_, repo, handler) = fresh()
        val plan = repo.addPlan(buildJsonObject { put("title", "云南七天") })
        val planId = plan["id"]?.jsonPrimitive?.contentOrNull!!
        repo.addArtifact(buildJsonObject {
            put("category", "venue")
            put("source_kind", "announcement")
            put("state", "structured")
            put("origin", "shared")
            put("title", "旧年闭馆")
            put("payload", Artifact.encodePayload(buildJsonObject {
                put("time_anchors", JsonArray(listOf(buildJsonObject {
                    put("role", "reference"); put("kind", "rule"); put("text", "周一闭馆")
                })))
                put("rule_scope", buildJsonObject {
                    put("valid_from", "2025-01-01"); put("valid_to", "2025-12-31")
                })
                put("constraints", buildJsonObject {
                    put("forbidden_weekdays", JsonArray(listOf(JsonPrimitive(1))))
                })
            }))
            put("plan_id", planId)
        }, null)
        val r = handler.execute(
            ScheduleCommandCodec.fromJson(proposeJson("2026-10-26", planId)),
            CommandActor.AI,
        )
        assertEquals(1, ((r.data?.get("blocks") as? JsonArray) ?: JsonArray(emptyList())).size)
    }
}
