package app.shiguang.core.model.collab

import app.shiguang.core.model.SqlRow
import app.shiguang.core.model.SqlStore
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 协同包本地仓储（lib/data/collab_repository.dart Kotlin 等价，P2 平移）。
 *
 * 职责仅限「协同包水位与事件落库」——增量补拉水位（collab_bundles.max_seq_id）的
 * 真实数据源，并持久化经管道去重排序后的已应用事件，使进程被杀后 Wake Pull 能从
 * 本地水位精准续拉。
 *
 * 设计口径（collab-implementation §3.1 / schedule-app.md §11.3，与 Dart 逐字）：
 * - 只落**原始事件**（密文），绝不在此做解密/FSM 回放——派生状态由
 *   [replayBundle] 按需从 collab_events 重放（§11.1 分层单向数据流）；
 * - max_seq_id 单调递增水位，[CollabBundleRepository.recordEvent] 以 MAX 推进，
 *   对齐 relay sync.go 的「以本页最大值推进」语义；
 * - (bundle_id, seq_id) 主键 UNIQUE 兜底幂等——重复投递原样忽略，绝不炸库。
 */

/** 已落库的单条协同事件投影（供 FSM 重放 / Wake 续拉校验）。 */
data class StoredCollabEvent(
    val seqId: Long,
    val actionType: String,
    val senderId: String,
    /** base64 解码后原始字节（SealedPayload JSON utf8）。 */
    val cipher: ByteArray,
    val keyVer: Int,
    val clientMsgId: String,
    val topologyRef: String?,
    val serverAtMs: Long,
) {
    companion object {
        fun fromRow(m: SqlRow): StoredCollabEvent = StoredCollabEvent(
            seqId = (m["seq_id"] as Long?) ?: 0L,
            actionType = m["action_type"] as String,
            senderId = m["sender_id"] as String,
            cipher = Base64.getDecoder().decode((m["cipher"] as String?) ?: ""),
            keyVer = ((m["key_ver"] as Long?) ?: 1L).toInt(),
            clientMsgId = m["client_msg_id"] as String,
            topologyRef = m["topology_ref"] as String?,
            serverAtMs = (m["server_at_ms"] as Long?) ?: 0L,
        )
    }
}

/** 协同包头表投影。 */
data class CollabBundleHeader(
    val id: String,
    val proposalType: String,
    val status: String,
    val maxSeqId: Long,
    val keyVersion: Int,
    val initiatorId: String,
    val participants: List<String>,
    val expiresAtMs: Long,
) {
    companion object {
        fun fromRow(m: SqlRow): CollabBundleHeader = CollabBundleHeader(
            id = m["id"] as String,
            proposalType = m["proposal_type"] as String,
            status = m["status"] as String,
            maxSeqId = (m["max_seq_id"] as Long?) ?: 0L,
            // D9 拍板修复（2026-11-14，与 Dart fromMap 同步）：读 key_version 真列；
            // 列推进见 recordEvent 的 rekey 旗标（FSM keyVersion+1 的持久投影）。
            keyVersion = ((m["key_version"] as Long?) ?: 1L).toInt(),
            initiatorId = m["initiator_id"] as String,
            participants = stringListFromJson((m["participants"] as String?) ?: "[]"),
            expiresAtMs = (m["expires_at"] as Long?) ?: 0L,
        )
    }
}

/** 协同步骤投影（collab_steps 行；FSM 物化的步骤态，UI 三态预占渲染源）。 */
data class CollabStep(
    val bundleId: String,
    val id: String,
    /** 该步必答成员。 */
    val requiredUids: List<String>,
    /** M-of-N 门限。 */
    val threshold: Int,
    val isOptional: Boolean,
    /** PENDING/READY/TIME_CLASH/ROLE_CLASH/PRUNED。 */
    val status: String,
    /** uid -> PREFER|OK|NO（Consensus 三值）。 */
    val votes: Map<String, String>,
    /** DAG 拓扑序。 */
    val sort: Int,
    /** 步骤时间槽（物化回填，v7）：未解到则为 null，读侧据此降级渲染。 */
    val startsAtMs: Long?,
    val endsAtMs: Long?,
) {
    companion object {
        fun fromRow(m: SqlRow): CollabStep = CollabStep(
            bundleId = m["bundle_id"] as String,
            id = m["id"] as String,
            requiredUids = stringListFromJson((m["required_uids"] as String?) ?: "[]"),
            threshold = ((m["threshold"] as Long?) ?: 1L).toInt(),
            isOptional = ((m["is_optional"] as Long?) ?: 0L) == 1L,
            status = m["status"] as String,
            votes = stringMapFromJson((m["votes"] as String?) ?: "{}"),
            sort = ((m["sort"] as Long?) ?: 0L).toInt(),
            startsAtMs = m["starts_at_ms"] as Long?,
            endsAtMs = m["ends_at_ms"] as Long?,
        )
    }
}

/** JSON 数组 → TEXT 列（与 Dart jsonEncode 逐字：紧凑无空格）。 */
internal fun jsonStringArray(list: List<String>): String =
    buildJsonArray { list.forEach { add(it) } }.toString()

/** JSON map → TEXT 列（votes：{uid: token}，保插入序）。 */
internal fun jsonStringMap(map: Map<String, String>): String =
    buildJsonObject { map.forEach { (k, v) -> put(k, v) } }.toString()

/** TEXT 列 → String 列表（Dart jsonDecode + cast<String> 镜像）。 */
internal fun stringListFromJson(raw: String): List<String> =
    if (raw.isEmpty()) emptyList()
    else Json.parseToJsonElement(raw).jsonArray.map { it.jsonPrimitive.content }

/** TEXT 列 → String map（Dart jsonDecode + cast<String, String> 镜像）。 */
internal fun stringMapFromJson(raw: String): Map<String, String> =
    if (raw.isEmpty()) emptyMap()
    else Json.parseToJsonElement(raw).jsonObject.mapValues { it.value.jsonPrimitive.content }

/**
 * 协同包本地仓储（SqlStore 句柄注入；测试 = sqlite-jdbc + ShiguangSchema.createAll）。
 * nowMs 缺省系统钟，与 Dart DateTime.now() 缺省同型——仅库账 created_at/updated_at 用，
 * UI 侧时钟纪律（TimeProvider）不涉本层。
 */
class CollabBundleRepository(
    private val db: SqlStore,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    /**
     * 增量补拉水位（WakePuller.sinceSeqOf 数据源）：优先取 collab_bundles.max_seq_id；
     * 包头行尚不存在时回退到 collab_events 已落库最大 seq；均无则 0。
     */
    suspend fun maxSeqId(bundleId: String): Long {
        val rows = db.query(
            "collab_bundles",
            columns = listOf("max_seq_id"),
            where = "id = ?",
            args = listOf(bundleId),
            limit = 1,
        )
        if (rows.isNotEmpty()) return (rows.first()["max_seq_id"] as Long?) ?: 0L
        val ev = db.rawQuery(
            "SELECT COALESCE(MAX(seq_id), 0) AS m FROM collab_events WHERE bundle_id = ?",
            listOf(bundleId),
        )
        return (ev.first()["m"] as Long?) ?: 0L
    }

    /**
     * 确保包头行存在（INSERT OR IGNORE，沿用 3.1 默认值）。PROPOSE 事件落地前或
     * Wake Pull 先于 PROPOSE 到达时调用，保证 [recordEvent] 的水位推进有宿主。
     */
    suspend fun ensureBundle(
        bundleId: String,
        proposalType: String,
        initiatorId: String,
        participants: List<String>,
        expiresAtMs: Long,
        keyVersion: Int = 1,
    ) {
        val now = nowMs()
        db.insert(
            "collab_bundles",
            buildJsonObject {
                put("id", bundleId)
                put("proposal_type", proposalType)
                put("status", "PROPOSED")
                put("lifecycle", "active")
                put("base_revision", 1L)
                put("max_seq_id", 0L)
                put("last_acked_seq", 0L)
                put("key_version", keyVersion.toLong())
                put("initiator_id", initiatorId)
                put("participants", jsonStringArray(participants))
                put("expires_at", expiresAtMs)
                put("created_at", now)
                put("updated_at", now)
            },
            ignore = true,
        )
    }

    /**
     * 落一条已应用事件并推进水位（同一事务内幂等 + 水位 GREATEST）。
     *
     * 调用方（CollabEngine 订阅 pipeline.applied）保证事件已去重排序，这里只做保真
     * 落库，不做解密/回放。重复 seq 因 UNIQUE(bundle_id, seq_id) 被忽略。
     */
    suspend fun recordEvent(
        bundleId: String,
        seqId: Long,
        actionType: String,
        senderId: String,
        cipher: ByteArray,
        keyVer: Int,
        clientMsgId: String,
        topologyRef: String? = null,
        serverAtMs: Long = 0,
        /** 本条事件是 REKEY 时由调用方置 true：同一事务内包头 key_version 列 +1
         *  （D9 拍板持久投影，取值口径=FSM keyVersion+1，与密文 keyVer 无关）。 */
        rekey: Boolean = false,
    ) {
        db.transaction { txn ->
            txn.insert(
                "collab_events",
                buildJsonObject {
                    put("bundle_id", bundleId)
                    put("seq_id", seqId)
                    put("action_type", actionType)
                    put("sender_id", senderId)
                    put("cipher", Base64.getEncoder().encodeToString(cipher))
                    put("key_ver", keyVer.toLong())
                    put("client_msg_id", clientMsgId)
                    put("topology_ref", topologyRef?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("server_at_ms", serverAtMs)
                    put("created_at", nowMs())
                },
                ignore = true,
            )
            // 确保包头行在（Wake Pull 可能先于 PROPOSE 落地）
            val head = txn.query(
                "collab_bundles",
                columns = listOf("id"),
                where = "id = ?",
                args = listOf(bundleId),
                limit = 1,
            )
            if (head.isEmpty()) {
                txn.insert(
                    "collab_bundles",
                    buildJsonObject {
                        put("id", bundleId)
                        put("proposal_type", "DIRECTIVE")
                        put("status", "PROPOSED")
                        put("lifecycle", "active")
                        put("base_revision", 1L)
                        put("max_seq_id", 0L)
                        put("last_acked_seq", 0L)
                        put("key_version", keyVer.toLong())
                        put("initiator_id", senderId)
                        put("participants", jsonStringArray(listOf(senderId)))
                        put("expires_at", 0L)
                        put("created_at", nowMs())
                        put("updated_at", nowMs())
                    },
                    ignore = true,
                )
            }
            // 水位 GREATEST 单调推进（对齐 sync.go 本页最大值语义）
            txn.rawUpdate(
                "UPDATE collab_bundles SET max_seq_id = MAX(max_seq_id, ?), " +
                    "updated_at = ? WHERE id = ?",
                listOf(seqId, nowMs(), bundleId),
            )
            // REKEY 应用即推进包头密钥版本列（D9 拍板：FSM keyVersion+1 的持久投影；
            // 单消费管道 FIFO 保证按 seq 序到达，+1 单调无竞争）
            if (rekey) {
                txn.rawUpdate(
                    "UPDATE collab_bundles SET key_version = key_version + 1, " +
                        "updated_at = ? WHERE id = ?",
                    listOf(nowMs(), bundleId),
                )
            }
        }
    }

    /** 回放用：按 seq 升序取某包全部已落库事件。 */
    suspend fun loadEvents(bundleId: String): List<StoredCollabEvent> =
        db.query(
            "collab_events",
            where = "bundle_id = ?",
            args = listOf(bundleId),
            orderBy = "seq_id ASC",
        ).map { StoredCollabEvent.fromRow(it) }

    /** 包头投影（无则 null）。 */
    suspend fun bundleHeader(bundleId: String): CollabBundleHeader? {
        val rows = db.query(
            "collab_bundles",
            where = "id = ?",
            args = listOf(bundleId),
            limit = 1,
        )
        return rows.firstOrNull()?.let { CollabBundleHeader.fromRow(it) }
    }

    /** 终态快照（供 UI/调度读；派生状态由 FSM 从 [loadEvents] 重放，这里只存权威水位）。 */
    suspend fun updateStatus(bundleId: String, status: String) {
        db.rawUpdate(
            "UPDATE collab_bundles SET status = ?, updated_at = ? WHERE id = ?",
            listOf(status, nowMs(), bundleId),
        )
    }

    /**
     * 确保步骤行存在（INSERT OR IGNORE，沿用步骤默认值）。FSM 回放落步骤态前或
     * Wake Pull 先于步骤到达时调用。
     */
    suspend fun ensureStep(
        bundleId: String,
        stepId: String,
        requiredUids: List<String>,
        threshold: Int = 1,
        isOptional: Boolean = false,
        status: String = "PENDING",
        votes: Map<String, String> = emptyMap(),
        sort: Int = 0,
    ) {
        val now = nowMs()
        db.insert(
            "collab_steps",
            buildJsonObject {
                put("bundle_id", bundleId)
                put("id", stepId)
                put("required_uids", jsonStringArray(requiredUids))
                put("threshold", threshold.toLong())
                put("is_optional", if (isOptional) 1L else 0L)
                put("status", status)
                put("votes", jsonStringMap(votes))
                put("sort", sort.toLong())
                put("created_at", now)
                put("updated_at", now)
            },
            ignore = true,
        )
    }

    /**
     * 物化回填（Phase 4.3）：把 FSM 派生态整批写入 collab_steps（replace 覆盖）。
     *
     * 与 [ensureStep] 的区别：后者是 INSERT OR IGNORE 占位（主键冲突即留旧值，故它
     * 写入的默认 PENDING 一旦落成就再也更新不了——这正是「步骤恒为 PENDING」的根因）；
     * 本方法用 replace 整体刷新派生列，重复调用幂等、不累积。
     *
     * [steps] 为空时不落任何行（避免回放尚无步骤时误抹既有派生行）。
     */
    suspend fun upsertSteps(bundleId: String, steps: List<CollabStep>): Int {
        if (steps.isEmpty()) return 0
        val now = nowMs()
        db.transaction { txn ->
            for (s in steps) {
                txn.insert(
                    "collab_steps",
                    buildJsonObject {
                        put("bundle_id", bundleId)
                        put("id", s.id)
                        put("required_uids", jsonStringArray(s.requiredUids))
                        put("threshold", s.threshold.toLong())
                        put("is_optional", if (s.isOptional) 1L else 0L)
                        put("status", s.status)
                        put("votes", jsonStringMap(s.votes))
                        put("sort", s.sort.toLong())
                        put("starts_at_ms", s.startsAtMs?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("ends_at_ms", s.endsAtMs?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("created_at", now)
                        put("updated_at", now)
                    },
                    replace = true,
                )
            }
        }
        return steps.size
    }

    /** 协同包列表（默认 active 且按更新时间倒序）；[status] 可收窄（如 CONFIRMED）。 */
    suspend fun listBundles(
        status: String? = null,
        lifecycle: String = "active",
    ): List<CollabBundleHeader> {
        val where = mutableListOf<String>()
        val args = mutableListOf<Any?>()
        if (status != null) {
            where.add("status = ?")
            args.add(status)
        }
        // lifecycle 非可空、默认 'active'，必带此收窄（归档清事件流时用 'archived'）
        where.add("lifecycle = ?")
        args.add(lifecycle)
        return db.query(
            "collab_bundles",
            where = where.joinToString(" AND "),
            args = args,
            orderBy = "updated_at DESC",
        ).map { CollabBundleHeader.fromRow(it) }
    }

    /** 某包的步骤（按拓扑序 sort → id 升序）。 */
    suspend fun listSteps(bundleId: String): List<CollabStep> =
        db.query(
            "collab_steps",
            where = "bundle_id = ?",
            args = listOf(bundleId),
            orderBy = "sort ASC, id ASC",
        ).map { CollabStep.fromRow(it) }
}
