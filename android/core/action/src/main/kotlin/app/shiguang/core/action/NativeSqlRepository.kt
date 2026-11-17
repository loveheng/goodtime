package app.shiguang.core.action

import app.shiguang.core.model.Background
import app.shiguang.core.model.FixedSlot
import app.shiguang.core.model.Plan
import app.shiguang.core.model.ScheduleBlock
import app.shiguang.core.model.ScheduleDay
import app.shiguang.core.model.SqlRow
import app.shiguang.core.model.SqlStore
import app.shiguang.core.model.SqlTxn
import app.shiguang.core.model.rowToJson
import app.shiguang.core.model.toSqlValues
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate

/**
 * P2 写事务单线程写队列（§10 决定 5：写=专用单线程分发器）。镜像 Dart
 * `command_handler.execute` 包 `repo.synchronized` FIFO——把「读→校验→写」复合
 * 操作压平成一列（TOCTOU 防护）；读不入队；锁内不做重活。
 *
 * 用法：命令分发层以 `withContext(DbWriteQueue.dispatcher) { handler.execute(cmd) }`
 * 承载整条命令（复合写天然串行）；仓储写方法各自再入队（独立调用面兜底）。
 * busy_timeout=5000 严禁下调（R1 双写者兜底，实测写锁持有阈值 10ms）。
 */
object DbWriteQueue {
    @Suppress("DEPRECATION")
    val dispatcher: CoroutineDispatcher = newSingleThreadContext("shiguang-db-write")
}

/**
 * 原生 SQL 仓储（BridgeRepositoryPort 的原生实现，P2 repository SQL 平移）。
 *
 * 行为契约 = storage_registry.md（2026-11-14）：行级 SQL 语义与 Dart Repository
 * 逐字（patch 白名单/CAS 乐观锁/批量原子替换/静默兜底边界）；写路径统一进
 * [DbWriteQueue.dispatcher]（读不入队）；Schema 经 :core:model ShiguangSchema
 * （驱动注入）。返回形契约：读面=裸 JsonArray（行序即 SQL 排序）、单实体=
 * 实体 MCP JSON（toMcpJson）、settingsAll=扁平 {key: value}。
 */
class NativeSqlRepository(private val store: SqlStore) : BridgeRepositoryPort {

    private fun nowMs(): Long = System.currentTimeMillis()

    /**
     * 变更刷新面（D16 裁决①）：写路径成功后单调 +1（读不 bump；乐观锁失败 0 行
     * 不 bump——未落库）。UI 侧 StateFlow 消费端据此重载。
     */
    private val _revision = MutableStateFlow(0L)
    override val revision: StateFlow<Long> get() = _revision

    private fun bumpRevision() {
        _revision.value += 1
    }

    // ── 通用 ────────────────────────────────────────────────────────────────────

    override fun newId(): String = java.util.UUID.randomUUID().toString()

    override suspend fun <T> transaction(body: suspend (SqlTxn) -> T): T =
        withContext(DbWriteQueue.dispatcher) {
            store.transaction(body).also { bumpRevision() }
        }

    // ── plans ───────────────────────────────────────────────────────────────────

    override suspend fun addPlan(values: JsonObject): JsonObject = withContext(DbWriteQueue.dispatcher) {
        val now = nowMs()
        val base = buildJsonObject {
            put("id", JsonNull)
            put("title", "")
            put("spec", JsonNull)
            put("notes", JsonNull)
            put("open_items", "[]")
            put("min_viable_action", JsonNull)
            put("energy_level", Plan.ENERGY_LIGHT)
            put("tool_required", Plan.TOOL_ANYWHERE)
            put("reward_spec", JsonNull)
            put("importance", 0)
            put("deadline", JsonNull)
            put("estimate", JsonNull)
            put("parent_id", JsonNull)
            put("archived", 0)
            put("created_at", now)
            put("updated_at", now)
            put("version", 0)
        }
        val merged = buildJsonObject {
            for ((k, v) in base) put(k, v)
            for ((k, v) in values) put(k, v) // overlay（缺省垫底+调用方值；布尔→0/1 由 fromMap 口径承接）
            put("id", (values["id"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull ?: newId())
        }
        val plan = Plan.fromMap(merged)
        store.insert("plans", plan.toMap())
        bumpRevision()
        plan.toMcpJson()
    }

    override suspend fun planById(id: String): JsonObject? {
        val rows = store.query("plans", where = "id = ?", args = listOf(id), limit = 1)
        return rows.firstOrNull()?.let { Plan.fromMap(rowToJson(it)).toMcpJson() }
    }

    override suspend fun listPlans(includeArchived: Boolean, parentId: String?): JsonElement {
        // Dart Repository.listPlans 逐字：where 子句按参拼装（全缺省=null=全表扫）
        val where = mutableListOf<String>()
        val args = mutableListOf<Any?>()
        if (!includeArchived) where.add("archived = 0")
        if (parentId != null) {
            where.add("parent_id = ?")
            args.add(parentId)
        }
        val rows = store.query(
            "plans",
            where = if (where.isEmpty()) null else where.joinToString(" AND "),
            args = if (where.isEmpty()) null else args,
            orderBy = "created_at DESC",
        )
        return JsonArray(rows.map { Plan.fromMap(rowToJson(it)).toMcpJson() })
    }

    private val planPatchColumns = setOf(
        "title", "spec", "notes", "open_items", "min_viable_action",
        "energy_level", "tool_required", "reward_spec", "importance",
        "deadline", "estimate", "parent_id", "archived",
    )

    override suspend fun patchPlan(id: String, values: JsonObject, expectedVersion: Long?): Boolean =
        patchRow("plans", planPatchColumns, id, values, expectedVersion)

    override suspend fun deletePlan(id: String) {
        deleteRow("plans", id, "plan")
    }

    // ── backgrounds ─────────────────────────────────────────────────────────────

    override suspend fun addBackground(values: JsonObject): JsonObject = withContext(DbWriteQueue.dispatcher) {
        val now = nowMs()
        val base = buildJsonObject {
            put("id", JsonNull)
            put("scope", "")
            put("plan_id", JsonNull)
            put("content", "")
            put("raw_source_text", JsonNull)
            put("tags", "[]")
            put("applicable_dates", JsonNull)
            put("source", Background.SOURCE_USER)
            put("captured_by", Background.CAPTURED_BY_ME)
            put("created_at", now)
            put("updated_at", now)
            put("version", 0)
        }
        val merged = buildJsonObject {
            for ((k, v) in base) put(k, v)
            for ((k, v) in values) put(k, v)
            put("id", (values["id"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull ?: newId())
        }
        val bg = Background.fromMap(merged)
        store.insert("backgrounds", bg.toMap())
        bumpRevision()
        bg.toMcpJson()
    }

    override suspend fun backgroundById(id: String): JsonObject? {
        val rows = store.query("backgrounds", where = "id = ?", args = listOf(id), limit = 1)
        return rows.firstOrNull()?.let { Background.fromMap(rowToJson(it)).toMcpJson() }
    }

    override suspend fun listBackgrounds(scope: String?, planId: String?): JsonElement {
        // Dart listBackgrounds({scope, planId}) 镜像：非空逐条 AND 拼接，序=created_at DESC
        val where = mutableListOf<String>()
        val args = mutableListOf<Any?>()
        scope?.let { where.add("scope = ?"); args.add(it) }
        planId?.let { where.add("plan_id = ?"); args.add(it) }
        val rows = store.query(
            "backgrounds",
            where = where.joinToString(" AND ").ifEmpty { null },
            args = args,
            orderBy = "created_at DESC",
        )
        return JsonArray(rows.map { Background.fromMap(rowToJson(it)).toMcpJson() })
    }

    private val backgroundPatchColumns = setOf("content", "tags", "applicable_dates")

    override suspend fun patchBackground(id: String, values: JsonObject, expectedVersion: Long?): Boolean =
        patchRow("backgrounds", backgroundPatchColumns, id, values, expectedVersion)

    // ── artifacts ───────────────────────────────────────────────────────────────

    override suspend fun artifactById(id: String): JsonObject? {
        val rows = store.query("artifacts", where = "id = ?", args = listOf(id), limit = 1)
        return rows.firstOrNull()?.let { artifactFrom(it) }
    }

    override suspend fun artifactForBlock(blockId: String): JsonObject? {
        val rows = store.rawQuery(
            "SELECT a.* FROM artifacts a JOIN schedule_blocks b ON b.artifact_id = a.id " +
                "WHERE b.id = ? AND a.state != ? ORDER BY a.created_at DESC LIMIT 1",
            listOf(blockId, "voided"),
        )
        return rows.firstOrNull()?.let { artifactFrom(it) }
    }

    private fun artifactFrom(row: SqlRow): JsonObject =
        app.shiguang.core.model.Artifact.fromMap(rowToJson(row)).toMcpJson()

    override suspend fun addArtifact(row: JsonObject, bindBlockId: String?): JsonObject =
        withContext(DbWriteQueue.dispatcher) {
            // Dart Repository.addArtifact：id??newId、created_at??now、updated_at=now、version=0
            val now = nowMs()
            val fullRow = buildJsonObject {
                for ((k, v) in row) put(k, v)
                put("id", (row["id"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull ?: newId())
                put("created_at", (row["created_at"] as? JsonPrimitive)?.longOrNull ?: now)
                put("updated_at", now)
                put("version", 0)
            }
            val art = app.shiguang.core.model.Artifact.fromMap(fullRow)
            store.transaction { txn ->
                txn.insert("artifacts", art.toMap())
                if (bindBlockId != null) {
                    // 块侧绑定单点（v5 反转关联）：块行在才绑，version+1 守乐观锁纪律；
                    // 块已日切物理删除（悬空 id）时 0 行=绑定静默跳过（软引用 §9.5-8）
                    txn.rawUpdate(
                        "UPDATE schedule_blocks SET artifact_id = ?, version = version + 1, updated_at = ? WHERE id = ?",
                        listOf(art.id, nowMs(), bindBlockId),
                    )
                }
            }
            bumpRevision()
            art.toMcpJson()
        }

    override suspend fun artifactsForPlans(planIds: List<String>, includeVoided: Boolean): JsonElement {
        if (planIds.isEmpty()) return JsonArray(emptyList())
        val marks = List(planIds.size) { "?" }.joinToString(", ")
        val where = if (includeVoided) "plan_id IN ($marks)" else "plan_id IN ($marks) AND state != ?"
        val args = if (includeVoided) planIds else planIds + listOf("voided")
        val rows = store.query("artifacts", where = where, args = args, orderBy = "created_at")
        return JsonArray(rows.map { artifactFrom(it) })
    }

    override suspend fun artifactsByState(state: String): JsonElement {
        val rows = store.query("artifacts", where = "state = ?", args = listOf(state), orderBy = "created_at")
        return JsonArray(rows.map { artifactFrom(it) })
    }

    /** 全量导出（D21）：六键全 query+exported_at（Dart exportAll 键集逐字）。 */
    override suspend fun exportAll(): JsonObject = buildJsonObject {
        put("exported_at", java.time.LocalDateTime.now().toString())
        put("plans", store.query("plans").toJsonRows())
        put("fixed_slots", store.query("fixed_slots").toJsonRows())
        put("schedule_blocks", store.query("schedule_blocks").toJsonRows())
        put("backgrounds", store.query("backgrounds").toJsonRows())
        put("artifacts", store.query("artifacts").toJsonRows())
        put("app_settings", store.query("app_settings").toJsonRows())
    }

    private fun List<SqlRow>.toJsonRows(): JsonArray = JsonArray(map { rowToJson(it) })

    /** 未归属凭证（§1.7 派生计数）：plan_id/bundle_id 双空且非 voided，created_at DESC。 */
    override suspend fun artifactsUnattributed(): JsonElement {
        val rows = store.rawQuery(
            "SELECT * FROM artifacts WHERE plan_id IS NULL AND bundle_id IS NULL AND state != ? ORDER BY created_at DESC",
            listOf("voided"),
        )
        return JsonArray(rows.map { artifactFrom(it) })
    }

    override suspend fun deleteArtifact(id: String) {
        deleteRow("artifacts", id, "artifact")
    }

    private val artifactPatchColumns = setOf("title", "badge", "state", "payload", "plan_id")

    override suspend fun patchArtifact(id: String, values: JsonObject, expectedVersion: Long?): Boolean =
        patchRow("artifacts", artifactPatchColumns, id, values, expectedVersion)

    // ── fixed_slots ─────────────────────────────────────────────────────────────

    private suspend fun allFixedSlots(): List<FixedSlot> =
        store.query("fixed_slots", orderBy = "start_min").map { FixedSlot.fromMap(rowToJson(it)) }

    override suspend fun listFixedSlots(): JsonElement =
        JsonArray(allFixedSlots().map { it.toMcpJson() })

    override suspend fun replaceFixedSlots(slots: JsonElement) = withContext(DbWriteQueue.dispatcher) {
        // 批量原子替换（update_fixed_slots 整单校验模式 §6）：整批成功或整批不动
        val items = (slots as? JsonArray)?.map { s ->
            val f = FixedSlot.fromMap(s as JsonObject)
            FixedSlot(f.id ?: newId(), f.name, f.weekdays, f.startMin, f.endMin)
        } ?: emptyList()
        store.transaction { txn ->
            txn.delete("fixed_slots")
            for (s in items) txn.insert("fixed_slots", s.toMap())
        }
        bumpRevision()
    }

    override suspend fun fixedSlotsForDate(date: String): JsonObject {
        val slots = allFixedSlots()
        val d = LocalDate.parse(date)
        val prev = d.minusDays(1)
        val onDate = slots.filter { it.weekdays.contains(d.dayOfWeek.value) }
        val spillover = slots.filter {
            ScheduleDay.crossesMidnight(it.startMin, it.endMin) && it.weekdays.contains(prev.dayOfWeek.value)
        }
        return buildJsonObject {
            put("on_date", JsonArray(onDate.map { it.toMcpJson() }))
            put("spillover", JsonArray(spillover.map { it.toMcpJson() }))
        }
    }

    // ── app_settings ────────────────────────────────────────────────────────────

    override suspend fun settingsAll(): JsonObject {
        val map = LinkedHashMap<String, JsonElement>()
        for (r in store.query("app_settings")) {
            val k = r["key"] as? String ?: continue
            map[k] = JsonPrimitive((r["value"] as? String) ?: "")
        }
        return JsonObject(map)
    }

    override suspend fun settingsGet(key: String): String? {
        val rows = store.query("app_settings", where = "key = ?", args = listOf(key), limit = 1)
        return rows.firstOrNull()?.get("value") as? String
    }

    override suspend fun settingsSet(values: JsonObject) = withContext(DbWriteQueue.dispatcher) {
        if (values.isEmpty()) return@withContext
        val now = nowMs()
        store.transaction { txn ->
            for ((k, v) in values) {
                txn.insert(
                    "app_settings",
                    buildJsonObject {
                        put("key", k)
                        put("value", (v as? JsonPrimitive)?.contentOrNull ?: "")
                        put("updated_at", now)
                    },
                    replace = true,
                )
            }
        }
        bumpRevision()
    }

    override suspend fun settingsClear(key: String) = withContext(DbWriteQueue.dispatcher) {
        store.delete("app_settings", where = "key = ?", args = listOf(key))
        bumpRevision()
        Unit
    }

    override suspend fun settingsClearAll(keys: List<String>) = withContext(DbWriteQueue.dispatcher) {
        if (keys.isEmpty()) return@withContext
        store.transaction { txn ->
            for (k in keys) txn.delete("app_settings", where = "key = ?", args = listOf(k))
        }
        bumpRevision()
    }

    // ── schedule_blocks ─────────────────────────────────────────────────────────

    override suspend fun addBlock(values: JsonObject): JsonObject = withContext(DbWriteQueue.dispatcher) {
        val base = buildJsonObject {
            put("id", JsonNull)
            put("date", "")
            put("start_min", 0)
            put("end_min", 0)
            put("plan_id", JsonNull)
            put("label", JsonNull)
            put("source", ScheduleBlock.SOURCE_AI)
            put("pinned", 0)
            put("status", ScheduleBlock.STATUS_PROPOSED)
            put("postpone_count", 0)
            put("execution_quality", ScheduleBlock.QUALITY_FULL)
            put("is_day_spark", 0)
            put("is_celebration", 0)
        }
        val merged = buildJsonObject {
            for ((k, v) in base) put(k, v)
            for ((k, v) in values) put(k, v)
        }
        // Dart addBlock：id??newId、created_at/updated_at 恒 now（不保留传入）、version=0
        val now = nowMs()
        val b = ScheduleBlock.fromMap(buildJsonObject {
            for ((k, v) in merged) put(k, v)
            put("id", (merged["id"] as? JsonPrimitive)?.contentOrNull ?: newId())
            put("created_at", now)
            put("updated_at", now)
            put("version", 0)
        })
        store.insert("schedule_blocks", b.toMap())
        bumpRevision()
        b.toMcpJson()
    }

    override suspend fun blockById(id: String): JsonObject? {
        val rows = store.query("schedule_blocks", where = "id = ?", args = listOf(id), limit = 1)
        return rows.firstOrNull()?.let { ScheduleBlock.fromMap(rowToJson(it)).toMcpJson() }
    }

    override suspend fun blocksOnDate(date: String): JsonElement {
        val rows = store.query("schedule_blocks", where = "date = ?", args = listOf(date), orderBy = "start_min")
        return JsonArray(rows.map { ScheduleBlock.fromMap(rowToJson(it)).toMcpJson() })
    }

    override suspend fun blocksInRange(fromIso: String, toIso: String): JsonElement {
        val rows = store.query(
            "schedule_blocks",
            where = "date >= ? AND date <= ?",
            args = listOf(fromIso, toIso),
            orderBy = "date, start_min",
        )
        return JsonArray(rows.map { ScheduleBlock.fromMap(rowToJson(it)).toMcpJson() })
    }

    private val blockPatchColumns = setOf(
        "date", "start_min", "end_min", "plan_id", "label", "source", "pinned",
        "status", "postpone_count", "execution_quality", "is_day_spark", "is_celebration",
    )

    override suspend fun patchBlock(id: String, values: JsonObject, expectedVersion: Long?): Boolean =
        patchRow("schedule_blocks", blockPatchColumns, id, values, expectedVersion)

    override suspend fun deleteBlock(id: String) {
        deleteRow("schedule_blocks", id, "block")
    }

    // ── 行级内部件 ───────────────────────────────────────────────────────────────

    /** patch 白名单过滤 + 乐观锁 CAS（Dart patchPlan/patchBackground/patchArtifact 同构）。 */
    private suspend fun patchRow(
        table: String,
        columns: Set<String>,
        id: String,
        values: JsonObject,
        expectedVersion: Long?,
    ): Boolean = withContext(DbWriteQueue.dispatcher) {
        for (k in values.keys) {
            if (k !in columns) throw IllegalArgumentException("未知列: $k（patch 只收白名单字段）")
        }
        if (values.isEmpty()) return@withContext true
        val set = values.keys.joinToString(", ") { "$it = ?" } + ", version = version + 1, updated_at = ?"
        val where = if (expectedVersion == null) "id = ?" else "id = ? AND version = ?"
        val args = values.toSqlValues().values.toList() + listOf<Any?>(nowMs(), id) +
            (expectedVersion?.let { listOf<Any?>(it) } ?: emptyList())
        store.rawUpdate("UPDATE $table SET $set WHERE $where", args).also { if (it > 0) bumpRevision() } > 0
    }

    private suspend fun deleteRow(table: String, id: String, what: String) =
        withContext(DbWriteQueue.dispatcher) {
            val n = store.delete(table, where = "id = ?", args = listOf(id))
            if (n == 0) throw IllegalStateException("$what 不存在: $id")
            bumpRevision()
        }
}
