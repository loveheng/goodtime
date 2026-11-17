package app.shiguang.core.action

import app.shiguang.core.model.FixedSlot
import app.shiguang.core.model.SqlTxn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * P2 双轨期桥接端口（D8 混合态）：CommandHandler 语义平移期的 Repository 调用面。
 *
 * Kotlin handler 的「读→校验→写」经本端口桥回 Dart Repository（MethodChannel
 * `shiguang/mcp_binder`，同 binder entrypoint 既有通道）；SQL 落地后切原生实现。
 * FIFO 锁（Dart Repository.synchronized）在 Dart 侧生效——Kotlin 侧桥调用天然串行
 * （每 invokeMethod 一个来回），不需要额外锁。
 *
 * 调用面盘点（command_handler 26 方法）：按使用频次分组；全部异步（suspend）。
 */
interface BridgeRepositoryPort {
    /**
     * 事务：全成功提交 / 中途异常整体回滚（镜像 Dart Repository.transaction——
     * 命令层复合写原子性：凭证插行+块绑定、删除+demote 等）。回调内 SqlTxn
     * 读写同事务。双轨桥实现抛不支持（跨 channel 无事务协议；原生实现直通）。
     */
    suspend fun <T> transaction(body: suspend (SqlTxn) -> T): T
    // ── blocks（15+4+3+2 = 24 处调用，最热面）─────────────────────────────────
    suspend fun patchBlock(id: String, values: JsonObject, expectedVersion: Long?): Boolean
    suspend fun blocksOnDate(date: String): JsonElement
    suspend fun blocksInRange(fromIso: String, toIso: String): JsonElement
    suspend fun addBlock(values: JsonObject): JsonObject
    suspend fun blockById(id: String): JsonObject?
    suspend fun deleteBlock(id: String)
    suspend fun deleteArtifact(id: String)

    /** 块关联凭证（镜像 Dart Repository.artifactForBlock）：b.artifact_id → 非 voided 凭证快照，缺省 null。 */
    suspend fun artifactForBlock(blockId: String): JsonObject?

    // ── plans（5+2+2+1 = 10 处）─────────────────────────────────────────────────
    suspend fun planById(id: String): JsonObject?
    suspend fun addPlan(values: JsonObject): JsonObject
    suspend fun patchPlan(id: String, values: JsonObject, expectedVersion: Long?): Boolean
    suspend fun deletePlan(id: String)

    /** [includeArchived]=false 只拉未归档（清单默认视图）；[parentId] 子树过滤。 */
    suspend fun listPlans(includeArchived: Boolean = false, parentId: String? = null): JsonElement

    // ── settings（5+3+2+1+1 = 12 处）────────────────────────────────────────────
    suspend fun settingsAll(): JsonObject
    suspend fun settingsSet(values: JsonObject)
    suspend fun settingsGet(key: String): String?
    suspend fun settingsClear(key: String)

    /** 批量删键（单事务+单 notify——快记草稿三键整组清除走此口）。 */
    suspend fun settingsClearAll(keys: List<String>)

    // ── fixed_slots（7+1+1 = 9 处）─────────────────────────────────────────────
    /**
     * 固定占用解析（镜像 Dart Repository.fixedSlotsForDate 记录）：
     * {"on_date": [slotToJson…], "spillover": [slotToJson…]}——onDate=coversWeekday(当日)，
     * spillover=跨午夜且 coversWeekday(前一日)（溢出段 portion=(0, endMin)）。
     */
    suspend fun fixedSlotsForDate(date: String): JsonObject
    suspend fun replaceFixedSlots(slots: JsonElement)
    suspend fun listFixedSlots(): JsonElement

    // ── backgrounds（3+2+2+1+1 = 9 处）──────────────────────────────────────────
    suspend fun backgroundById(id: String): JsonObject?
    suspend fun addBackground(values: JsonObject): JsonObject
    suspend fun patchBackground(id: String, values: JsonObject, expectedVersion: Long?): Boolean
    /**
     * 背景列表读面。[scope]/[planId] 过滤（镜像 Dart listBackgrounds({scope, planId})
     * ——WHERE AND 拼接，null 不过滤；plan_background 子页按 scope=plan+planId 拉，
     * D24-G2 端口 Expand）。
     */
    suspend fun listBackgrounds(scope: String? = null, planId: String? = null): JsonElement

    // ── artifacts（3+1+1+1 = 6 处）─────────────────────────────────────────────
    suspend fun artifactById(id: String): JsonObject?

    /**
     * 插入凭证行（**行级语义**，镜像 Dart Repository.addArtifact）：
     * [row] = artifacts 行映射（Artifact.toMap 口径）；[bindBlockId] 非空时同事务
     * 写块侧绑定（fact §9.5-6：UPDATE schedule_blocks SET artifact_id + version+1，
     * 块已日切删除=绑定静默跳过，软引用口径）。返回插入后的 artifactToJson 形。
     * upsert_facts 的载荷组装/合并/出生确认归命令层（ResidualHandler）。
     */
    suspend fun addArtifact(row: JsonObject, bindBlockId: String?): JsonObject
    /** [includeVoided]=true 搭载 voided 行（plan_facts「已作废」折叠组；Dart includeVoided 镜像，D24-G2 Expand）。 */
    suspend fun artifactsForPlans(planIds: List<String>, includeVoided: Boolean = false): JsonElement

    /** 按 state 取凭证行（get_schedule 搭载与 list_plans 搭载共用读面）。 */
    suspend fun artifactsByState(state: String): JsonElement

    /** 未归属凭证（§1.7 派生计数，D20 清单转正）：plan_id/bundle_id 双空且非 voided，created_at DESC。 */
    suspend fun artifactsUnattributed(): JsonElement

    /**
     * 全量导出（D21 清单转正，数据出口）：六键全 query（plans/fixed_slots/
     * schedule_blocks/backgrounds/artifacts/app_settings，行序=SQL 默认）+
     * exported_at ISO 时间戳——镜像 Dart Repository.exportAll 键集。
     */
    suspend fun exportAll(): JsonObject

    /**
     * 凭证字段级 patch + 乐观锁（镜像 Dart Repository.patchArtifact）：白名单
     * {title,badge,state,payload,plan_id}（category/source_kind/origin/captured_by
     * 出生不可变——唯一例外=出生确认，命令层经 transaction 直写列）。
     */
    suspend fun patchArtifact(id: String, values: JsonObject, expectedVersion: Long?): Boolean

    // ── 通用 ────────────────────────────────────────────────────────────────────
    fun newId(): String

    /**
     * 变更刷新面（D16 裁决①，N5）：写路径广播——Dart Repository(ChangeNotifier)
     * 的 ListenableBuilder 对应物。每次写事务/写命令落库成功后单调 +1；UI 侧
     * StateFlow 消费端据此重载（VM collect → 重载 → StateFlow，禁事件回调反查）。
     * 混态期已知差异：Dart isolate 写不通知本流（跨运行时无总线，P5 后单写者消失）。
     */
    val revision: StateFlow<Long>
}

/**
 * 双轨桥实现（P2 语义平移期）：经 MethodChannel `shiguang/action_bridge` 调
 * Dart Repository 同名方法（Dart 侧 handler `action_bridge` 通道注册分发）。
 * SQL 落地后切原生实现（:core:model SQL 层）。
 */
class DartBridgeRepository(
    private val send: suspend (method: String, args: JsonObject) -> JsonElement?,
) : BridgeRepositoryPort {

    // 变更刷新面（D16）：双轨桥为本地写计数近似——call 层按写方法名判定，send 成功
    // 即 bump（桥无 SQL 行数语义，乐观锁失败也会计数；生产面 NativeSqlRepository
    // 已逐写点精确 bump，桥仅双轨期测试替身消费）。
    private val _revision = MutableStateFlow(0L)
    override val revision: StateFlow<Long> = _revision

    private val writeOps = setOf(
        "patchBlock", "addBlock", "deleteBlock", "addPlan", "patchPlan", "deletePlan",
        "settingsSet", "settingsClear", "settingsClearAll", "replaceFixedSlots",
        "addBackground", "patchBackground", "addArtifact", "deleteArtifact", "patchArtifact",
    )

    private suspend fun call(method: String, args: JsonObject): JsonElement? =
        send("action_$method", args).also { if (method in writeOps) _revision.value += 1 }

    private suspend fun callObj(method: String, args: JsonObject): JsonObject? =
        call(method, args) as? JsonObject

    override suspend fun patchBlock(id: String, values: JsonObject, expectedVersion: Long?): Boolean =
        callObj("patchBlock", buildJsonObject {
            put("id", id)
            put("values", values)
            expectedVersion?.let { put("expected_version", it) }
        })?.get("ok")?.let { (it as? JsonPrimitive)?.content == "true" } ?: false

    override suspend fun blocksInRange(fromIso: String, toIso: String): JsonElement =
        call("blocksInRange", buildJsonObject { put("from", fromIso); put("to", toIso) })
            ?: kotlinx.serialization.json.Json.parseToJsonElement("[]")

    override suspend fun blocksOnDate(date: String): JsonElement =
        call("blocksOnDate", buildJsonObject { put("date", date) })
            ?: kotlinx.serialization.json.Json.parseToJsonElement("[]")

    override suspend fun addBlock(values: JsonObject): JsonObject =
        callObj("addBlock", buildJsonObject { put("values", values) })
            ?: throw ActionException("addBlock 返回空", ActionErrorCode.INVALID_REQUEST)

    override suspend fun blockById(id: String): JsonObject? = callObj("blockById", buildJsonObject { put("id", id) })

    override suspend fun deleteBlock(id: String) {
        call("deleteBlock", buildJsonObject { put("id", id) })
    }

    override suspend fun planById(id: String): JsonObject? = callObj("planById", buildJsonObject { put("id", id) })

    override suspend fun addPlan(values: JsonObject): JsonObject =
        callObj("addPlan", buildJsonObject { put("values", values) })
            ?: throw ActionException("addPlan 返回空", ActionErrorCode.INVALID_REQUEST)

    override suspend fun patchPlan(id: String, values: JsonObject, expectedVersion: Long?): Boolean =
        callObj("patchPlan", buildJsonObject {
            put("id", id)
            put("values", values)
            expectedVersion?.let { put("expected_version", it) }
        })?.get("ok")?.let { (it as? JsonPrimitive)?.content == "true" } ?: false

    override suspend fun deletePlan(id: String) {
        call("deletePlan", buildJsonObject { put("id", id) })
    }

    override suspend fun listPlans(includeArchived: Boolean, parentId: String?): JsonElement =
        call("listPlans", buildJsonObject {
            if (includeArchived) put("include_archived", true)
            parentId?.let { put("parent_id", it) }
        }) ?: kotlinx.serialization.json.Json.parseToJsonElement("[]")

    override suspend fun settingsAll(): JsonObject =
        callObj("settingsAll", buildJsonObject { }) ?: buildJsonObject { }

    override suspend fun settingsSet(values: JsonObject) {
        call("settingsSet", buildJsonObject { put("values", values) })
    }

    override suspend fun settingsGet(key: String): String? {
        val r = call("settingsGet", buildJsonObject { put("key", key) })
        return (r as? JsonPrimitive)?.contentOrNull
    }

    override suspend fun settingsClear(key: String) {
        call("settingsClear", buildJsonObject { put("key", key) })
    }

    override suspend fun settingsClearAll(keys: List<String>) {
        call("settingsClearAll", buildJsonObject {
            put("keys", kotlinx.serialization.json.JsonArray(keys.map { JsonPrimitive(it) }))
        })
    }

    override suspend fun <T> transaction(body: suspend (SqlTxn) -> T): T =
        throw ActionException(
            "双轨桥不支持跨通道事务（复合写原子性属原生切换后能力）",
            ActionErrorCode.INVALID_REQUEST,
            hint = "原生 NativeSqlRepository 直通 SqlStore.transaction",
        )

    override suspend fun fixedSlotsForDate(date: String): JsonObject =
        callObj("fixedSlotsForDate", buildJsonObject { put("date", date) })
            ?: buildJsonObject { }

    override suspend fun replaceFixedSlots(slots: JsonElement) {
        call("replaceFixedSlots", buildJsonObject { put("slots", slots) })
    }

    override suspend fun listFixedSlots(): JsonElement =
        call("listFixedSlots", buildJsonObject { })
            ?: kotlinx.serialization.json.Json.parseToJsonElement("[]")

    override suspend fun backgroundById(id: String): JsonObject? =
        callObj("backgroundById", buildJsonObject { put("id", id) })

    override suspend fun addBackground(values: JsonObject): JsonObject =
        callObj("addBackground", buildJsonObject { put("values", values) })
            ?: throw ActionException("addBackground 返回空", ActionErrorCode.INVALID_REQUEST)

    override suspend fun patchBackground(id: String, values: JsonObject, expectedVersion: Long?): Boolean =
        callObj("patchBackground", buildJsonObject {
            put("id", id)
            put("values", values)
            expectedVersion?.let { put("expected_version", it) }
        })?.get("ok")?.let { (it as? JsonPrimitive)?.content == "true" } ?: false

    override suspend fun listBackgrounds(scope: String?, planId: String?): JsonElement =
        call("listBackgrounds", buildJsonObject {
            scope?.let { put("scope", it) }
            planId?.let { put("plan_id", it) }
        })
            ?: kotlinx.serialization.json.Json.parseToJsonElement("[]")

    override suspend fun artifactById(id: String): JsonObject? =
        callObj("artifactById", buildJsonObject { put("id", id) })

    override suspend fun artifactForBlock(blockId: String): JsonObject? =
        callObj("artifactForBlock", buildJsonObject { put("block_id", blockId) })

    override suspend fun addArtifact(row: JsonObject, bindBlockId: String?): JsonObject =
        callObj("addArtifact", buildJsonObject {
            put("row", row)
            bindBlockId?.let { put("bind_block_id", it) }
        }) ?: throw ActionException("addArtifact 返回空", ActionErrorCode.INVALID_REQUEST)

    override suspend fun deleteArtifact(id: String) {
        call("deleteArtifact", buildJsonObject { put("id", id) })
    }

    override suspend fun artifactsForPlans(planIds: List<String>, includeVoided: Boolean): JsonElement =
        call("artifactsForPlans", buildJsonObject {
            put("plan_ids", kotlinx.serialization.json.JsonArray(planIds.map { JsonPrimitive(it) }))
            if (includeVoided) put("include_voided", true)
        }) ?: kotlinx.serialization.json.Json.parseToJsonElement("[]")

    override suspend fun artifactsByState(state: String): JsonElement =
        call("artifactsByState", buildJsonObject { put("state", state) })
            ?: kotlinx.serialization.json.Json.parseToJsonElement("[]")

    override suspend fun artifactsUnattributed(): JsonElement =
        call("artifactsUnattributed", buildJsonObject { })
            ?: kotlinx.serialization.json.Json.parseToJsonElement("[]")

    override suspend fun exportAll(): JsonObject =
        callObj("exportAll", buildJsonObject { })
            ?: throw ActionException("exportAll 返回空", ActionErrorCode.INVALID_REQUEST)

    override suspend fun patchArtifact(id: String, values: JsonObject, expectedVersion: Long?): Boolean =
        callObj("patchArtifact", buildJsonObject {
            put("id", id)
            put("values", values)
            expectedVersion?.let { put("expected_version", it) }
        })?.get("ok")?.let { (it as? JsonPrimitive)?.content == "true" } ?: false

    override fun newId(): String =
        java.util.UUID.randomUUID().toString()
}

/** fixedSlotsForDate 记录字段 → FixedSlot 列表（slotToJson 线形 → fromMap 双形态解析）。 */
internal fun slotsOfJson(raw: JsonElement?): List<FixedSlot> =
    (raw as? JsonArray)
        ?.mapNotNull { e -> (e as? JsonObject)?.let { FixedSlot.fromMap(it) } }
        ?: emptyList()
