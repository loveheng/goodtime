package app.shiguang.core.model

import kotlinx.serialization.json.JsonObject

/**
 * Schema 契约：台账库 12 表 v7 终态 DDL + 七段幂等迁移史。
 *
 * **契约红线：Schema 严禁变更**——权威出处 = test/golden/sqlite_schema_vectors.json
 * （PRAGMA 全结构 6 case）；DDL 文本抽取自 lib/data/db.dart（结构以向量仲裁，
 * 写法差异属实现细节）。唯一 DDL 源：新装 createAll 与升级迁移共用（新装同构）。
 *
 * 连接口径（驱动层职责，AndroidSqlStore）：WAL + busy_timeout=5000 严禁下调 +
 * onOpen `PRAGMA foreign_keys = ON`（plans 自引用树 FK 护栏依赖此开关）。
 */
object ShiguangSchema {

    const val VERSION = 7

    // ─── 全量建表（v7 终态；列序/可空/缺省与 db.dart createAll 逐字同构）──────────

    /** 有序建表语句（表 → 索引；CREATE IF NOT EXISTS 重跑零副作用）。 */
    val CREATE_STATEMENTS: List<String> = listOf(
        """CREATE TABLE IF NOT EXISTS plans (
        id TEXT NOT NULL PRIMARY KEY,
        title TEXT NOT NULL,
        spec TEXT,
        notes TEXT,
        open_items TEXT,
        min_viable_action TEXT,
        energy_level TEXT NOT NULL DEFAULT 'light',
        tool_required TEXT NOT NULL DEFAULT 'anywhere',
        reward_spec TEXT,
        importance INTEGER NOT NULL DEFAULT 0,
        deadline TEXT,
        estimate INTEGER,
        parent_id TEXT REFERENCES plans(id),
        archived INTEGER NOT NULL DEFAULT 0,
        created_at INTEGER NOT NULL,
        updated_at INTEGER NOT NULL,
        version INTEGER NOT NULL DEFAULT 0
      )""",
        """CREATE TABLE IF NOT EXISTS fixed_slots (
        id TEXT NOT NULL PRIMARY KEY,
        name TEXT NOT NULL,
        weekdays TEXT NOT NULL,
        start_min INTEGER NOT NULL,
        end_min INTEGER NOT NULL
      )""",
        """CREATE TABLE IF NOT EXISTS schedule_blocks (
        id TEXT NOT NULL PRIMARY KEY,
        date TEXT NOT NULL,
        start_min INTEGER NOT NULL,
        end_min INTEGER NOT NULL,
        plan_id TEXT REFERENCES plans(id),
        label TEXT,
        source TEXT NOT NULL,
        pinned INTEGER NOT NULL DEFAULT 0,
        status TEXT NOT NULL DEFAULT 'proposed',
        postpone_count INTEGER NOT NULL DEFAULT 0,
        execution_quality TEXT NOT NULL DEFAULT 'full',
        is_day_spark INTEGER NOT NULL DEFAULT 0,
        is_celebration INTEGER NOT NULL DEFAULT 0,
        artifact_id TEXT,
        created_at INTEGER NOT NULL,
        updated_at INTEGER NOT NULL,
        version INTEGER NOT NULL DEFAULT 0
      )""",
        "CREATE INDEX IF NOT EXISTS idx_blocks_date ON schedule_blocks(date)",
        "CREATE INDEX IF NOT EXISTS idx_blocks_plan ON schedule_blocks(plan_id)",
        "CREATE INDEX IF NOT EXISTS idx_blocks_artifact ON schedule_blocks(artifact_id)",
        "CREATE INDEX IF NOT EXISTS idx_plans_parent ON plans(parent_id)",
        """CREATE TABLE IF NOT EXISTS backgrounds (
        id TEXT NOT NULL PRIMARY KEY,
        scope TEXT NOT NULL,
        plan_id TEXT REFERENCES plans(id),
        content TEXT NOT NULL,
        raw_source_text TEXT,
        tags TEXT,
        applicable_dates TEXT,
        source TEXT NOT NULL,
        captured_by TEXT NOT NULL DEFAULT 'me',
        created_at INTEGER NOT NULL,
        updated_at INTEGER NOT NULL,
        version INTEGER NOT NULL DEFAULT 0
      )""",
        "CREATE INDEX IF NOT EXISTS idx_backgrounds_plan ON backgrounds(plan_id)",
        """CREATE TABLE IF NOT EXISTS artifacts (
        id TEXT NOT NULL PRIMARY KEY,
        category TEXT NOT NULL,
        source_kind TEXT NOT NULL,
        state TEXT NOT NULL,
        origin TEXT NOT NULL,
        title TEXT NOT NULL,
        badge TEXT,
        payload TEXT NOT NULL,
        attachments TEXT NOT NULL,
        captured_by TEXT NOT NULL DEFAULT 'me',
        plan_id TEXT REFERENCES plans(id),
        bundle_id TEXT,
        created_at INTEGER NOT NULL,
        updated_at INTEGER NOT NULL,
        version INTEGER NOT NULL DEFAULT 0
      )""",
        "CREATE INDEX IF NOT EXISTS idx_artifacts_plan ON artifacts(plan_id)",
        "CREATE INDEX IF NOT EXISTS idx_artifacts_bundle ON artifacts(bundle_id)",
        """CREATE TABLE IF NOT EXISTS app_settings (
        key TEXT PRIMARY KEY,
        value TEXT NOT NULL,
        updated_at INTEGER NOT NULL
      )""",
        """CREATE TABLE IF NOT EXISTS collab_bundles (
        id TEXT NOT NULL PRIMARY KEY,
        proposal_type TEXT NOT NULL,
        status TEXT NOT NULL DEFAULT 'PROPOSED',
        lifecycle TEXT NOT NULL DEFAULT 'active',
        base_revision INTEGER NOT NULL DEFAULT 1,
        max_seq_id INTEGER NOT NULL DEFAULT 0,
        last_acked_seq INTEGER NOT NULL DEFAULT 0,
        key_version INTEGER NOT NULL DEFAULT 1,
        initiator_id TEXT NOT NULL,
        participants TEXT NOT NULL,
        expires_at INTEGER NOT NULL,
        created_at INTEGER NOT NULL,
        updated_at INTEGER NOT NULL
      )""",
        """CREATE TABLE IF NOT EXISTS collab_steps (
        bundle_id TEXT NOT NULL REFERENCES collab_bundles(id),
        id TEXT NOT NULL,
        required_uids TEXT NOT NULL,
        threshold INTEGER NOT NULL DEFAULT 1,
        is_optional INTEGER NOT NULL DEFAULT 0,
        status TEXT NOT NULL DEFAULT 'PENDING',
        votes TEXT,
        sort INTEGER NOT NULL DEFAULT 0,
        starts_at_ms INTEGER,
        ends_at_ms INTEGER,
        created_at INTEGER NOT NULL,
        updated_at INTEGER NOT NULL,
        PRIMARY KEY (bundle_id, id)
      )""",
        """CREATE TABLE IF NOT EXISTS collab_events (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        bundle_id TEXT NOT NULL REFERENCES collab_bundles(id),
        seq_id INTEGER NOT NULL,
        action_type TEXT NOT NULL,
        sender_id TEXT NOT NULL,
        cipher TEXT NOT NULL,
        key_ver INTEGER NOT NULL DEFAULT 1,
        client_msg_id TEXT NOT NULL,
        topology_ref TEXT,
        server_at_ms INTEGER NOT NULL DEFAULT 0,
        created_at INTEGER NOT NULL,
        UNIQUE (bundle_id, seq_id)
      )""",
        "CREATE INDEX IF NOT EXISTS idx_collab_events_msg ON collab_events(bundle_id, client_msg_id)",
        """CREATE TABLE IF NOT EXISTS collab_key_ring (
        bundle_id TEXT NOT NULL REFERENCES collab_bundles(id),
        key_ver INTEGER NOT NULL,
        envelope TEXT NOT NULL,
        created_at INTEGER NOT NULL,
        PRIMARY KEY (bundle_id, key_ver)
      )""",
        """CREATE TABLE IF NOT EXISTS local_outbox_events (
        id TEXT NOT NULL PRIMARY KEY,
        bundle_id TEXT NOT NULL,
        action_type TEXT NOT NULL,
        payload TEXT NOT NULL,
        key_ver INTEGER NOT NULL DEFAULT 1,
        status TEXT NOT NULL DEFAULT 'PENDING_SEND',
        attempts INTEGER NOT NULL DEFAULT 0,
        created_at INTEGER NOT NULL,
        sent_at INTEGER
      )""",
        """CREATE TABLE IF NOT EXISTS collab_block_refs (
        block_id TEXT NOT NULL PRIMARY KEY,
        bundle_id TEXT NOT NULL,
        step_id TEXT NOT NULL,
        created_at INTEGER NOT NULL
      )""",
        "CREATE INDEX IF NOT EXISTS idx_collab_refs_step ON collab_block_refs(bundle_id, step_id)",
    )

    /** 全量建表（新装 v7 直达；onCreate 与测试共用的唯一 DDL 源）。
     *  末尾盖 user_version 章——Dart 侧由 sqflite openDatabase(version:7) 承担，
     *  Kotlin 侧收口在 Schema 契约（驱动无需再管版本）。 */
    suspend fun createAll(store: SqlStore) {
        for (sql in CREATE_STATEMENTS) store.execute(sql)
        store.execute("PRAGMA user_version = $VERSION")
    }

    // ─── 分段幂等迁移（onUpgrade 逐段；每段 IF NOT EXISTS / addColumnIfMissing）────

    /** 升级入口：从 [oldVersion] 逐段补齐到 v7（幂等，重跑零副作用）+ 末尾盖 user_version 章。 */
    suspend fun upgrade(store: SqlStore, oldVersion: Int) {
        if (oldVersion < 2) ensureSettingsTable(store)
        if (oldVersion < 3) ensureBackgroundsTable(store)
        if (oldVersion < 4) ensureArtifactsTable(store) // v4 形态（block_id 在位）
        if (oldVersion < 5) migrateV5(store)
        if (oldVersion < 6) migrateV6(store)
        if (oldVersion < 7) migrateV7(store)
        store.execute("PRAGMA user_version = $VERSION")
    }

    /** v4→v5 六步（顺序不可换，collab-implementation §3.1）：反转关联 + collab 六表。 */
    suspend fun migrateV5(store: SqlStore) {
        // ① blocks 持凭证（列在才加，重跑零副作用）
        addColumnIfMissing(store, "schedule_blocks", "artifact_id", "TEXT")
        // ② 凭证持协同包（弱引用）
        addColumnIfMissing(store, "artifacts", "bundle_id", "TEXT")
        // ③④⑤ 绑定搬家 / payload 清洗 / 出列——v3 存量库才含 block_id
        if (columnExists(store, "artifacts", "block_id")) {
            // ③ 存量绑定搬家：块侧回填（块已物理删除的引用自然丢弃）
            val bound = store.rawQuery(
                "SELECT id, block_id FROM artifacts WHERE block_id IS NOT NULL",
            )
            for (r in bound) {
                store.rawUpdate(
                    "UPDATE schedule_blocks SET artifact_id = ? WHERE id = ?",
                    listOf(r["block_id"], r["id"]),
                )
            }
            // ④ payload 投影清洗：block_id 键出列（非对象/畸形 payload 不动——洁癖不是修复）
            val dirtied = store.rawQuery(
                "SELECT id, payload FROM artifacts WHERE payload LIKE '%block_id%'",
            )
            for (r in dirtied) {
                val raw = r["payload"] as? String ?: continue
                val payload: JsonObject? = try {
                    kotlinx.serialization.json.Json.parseToJsonElement(raw) as? JsonObject
                } catch (_: Exception) {
                    null
                }
                if (payload == null || "block_id" !in payload) continue
                val cleaned = JsonObject(payload - "block_id")
                store.rawUpdate(
                    "UPDATE artifacts SET payload = ? WHERE id = ?",
                    listOf(Artifact.encodePayload(cleaned), r["id"]),
                )
            }
            // ⑤ 出列：SQLite ≥3.35 原生 DROP COLUMN；老内核走 12 步重建兜底
            try {
                store.execute("ALTER TABLE artifacts DROP COLUMN block_id")
            } catch (_: Exception) {
                if (columnExists(store, "artifacts", "block_id")) {
                    rebuildArtifactsWithoutBlockId(store)
                }
            }
        }
        // ⑥ collab_* 本地六表（CREATE IF NOT EXISTS）
        createCollabTables(store)
        // ⑦ 单机索引幂等补齐（D6 拍板修复：v1–v4 索引只在 createAll 建，存量升级从未补）
        createSingleMachineIndexes(store)
    }

    /** v5→v6：collab_events 补 server_at_ms（法定时钟 §5.2，本地 TTL 研判源）。 */
    suspend fun migrateV6(store: SqlStore) {
        addColumnIfMissing(store, "collab_events", "server_at_ms", "INTEGER NOT NULL DEFAULT 0")
    }

    /** v6→v7：collab_steps 补步骤时间槽（物化回填；可空=未拿到密钥/载荷的行降级渲染）。 */
    suspend fun migrateV7(store: SqlStore) {
        addColumnIfMissing(store, "collab_steps", "starts_at_ms", "INTEGER")
        addColumnIfMissing(store, "collab_steps", "ends_at_ms", "INTEGER")
    }

    // ─── 内部件（公开供测试驱动）────────────────────────────────────────────────

    /** 幂等补齐 app_settings（v1→v2）：settings 与台账同库——备份恢复一个文件。 */
    suspend fun ensureSettingsTable(store: SqlStore) {
        store.execute(
            """CREATE TABLE IF NOT EXISTS app_settings (
        key TEXT PRIMARY KEY,
        value TEXT NOT NULL,
        updated_at INTEGER NOT NULL
      )""",
        )
    }

    /** 幂等补齐 backgrounds（v2→v3）：软背景层。 */
    suspend fun ensureBackgroundsTable(store: SqlStore) {
        store.execute(
            """CREATE TABLE IF NOT EXISTS backgrounds (
        id TEXT NOT NULL PRIMARY KEY,
        scope TEXT NOT NULL,
        plan_id TEXT REFERENCES plans(id),
        content TEXT NOT NULL,
        raw_source_text TEXT,
        tags TEXT,
        applicable_dates TEXT,
        source TEXT NOT NULL,
        captured_by TEXT NOT NULL DEFAULT 'me',
        created_at INTEGER NOT NULL,
        updated_at INTEGER NOT NULL,
        version INTEGER NOT NULL DEFAULT 0
      )""",
        )
        store.execute("CREATE INDEX IF NOT EXISTS idx_backgrounds_plan ON backgrounds(plan_id)")
    }

    /** 幂等补齐 artifacts（v3→v4）：**保持 v4 形态（block_id 在位）**——存量先补齐
     *  v4 基线，随后统一走 migrateV5 反转（v1/v3/v4 设备收敛到同一 v5 终态）。 */
    suspend fun ensureArtifactsTable(store: SqlStore) {
        store.execute(
            """CREATE TABLE IF NOT EXISTS artifacts (
        id TEXT NOT NULL PRIMARY KEY,
        category TEXT NOT NULL,
        source_kind TEXT NOT NULL,
        state TEXT NOT NULL,
        origin TEXT NOT NULL,
        title TEXT NOT NULL,
        badge TEXT,
        payload TEXT NOT NULL,
        attachments TEXT NOT NULL,
        captured_by TEXT NOT NULL DEFAULT 'me',
        plan_id TEXT REFERENCES plans(id),
        block_id TEXT,
        created_at INTEGER NOT NULL,
        updated_at INTEGER NOT NULL,
        version INTEGER NOT NULL DEFAULT 0
      )""",
        )
        store.execute("CREATE INDEX IF NOT EXISTS idx_artifacts_plan ON artifacts(plan_id)")
    }

    /** collab_* 协同本地六表（v5 起；FK 只挂同生同灭强引用，块/包引用一律软引用）。 */
    suspend fun createCollabTables(store: SqlStore) {
        for (sql in CREATE_STATEMENTS.filter { it.contains("collab_") || it.contains("local_outbox") }) {
            store.execute(sql)
        }
    }

    /** 单机索引幂等补齐（D6：7 条 IF NOT EXISTS，老内核重建兜底路径同样在此收敛）。 */
    suspend fun createSingleMachineIndexes(store: SqlStore) {
        for (sql in listOf(
            "CREATE INDEX IF NOT EXISTS idx_blocks_date ON schedule_blocks(date)",
            "CREATE INDEX IF NOT EXISTS idx_blocks_plan ON schedule_blocks(plan_id)",
            "CREATE INDEX IF NOT EXISTS idx_blocks_artifact ON schedule_blocks(artifact_id)",
            "CREATE INDEX IF NOT EXISTS idx_plans_parent ON plans(parent_id)",
            "CREATE INDEX IF NOT EXISTS idx_backgrounds_plan ON backgrounds(plan_id)",
            "CREATE INDEX IF NOT EXISTS idx_artifacts_plan ON artifacts(plan_id)",
            "CREATE INDEX IF NOT EXISTS idx_artifacts_bundle ON artifacts(bundle_id)",
        )) {
            store.execute(sql)
        }
    }

    /** artifacts 去掉 block_id 的 12 步重建兜底（老 SQLite 无 DROP COLUMN 时走此路）。 */
    suspend fun rebuildArtifactsWithoutBlockId(store: SqlStore) {
        store.execute("ALTER TABLE artifacts RENAME TO artifacts_v4_old")
        store.execute(
            """CREATE TABLE artifacts (
        id TEXT NOT NULL PRIMARY KEY,
        category TEXT NOT NULL,
        source_kind TEXT NOT NULL,
        state TEXT NOT NULL,
        origin TEXT NOT NULL,
        title TEXT NOT NULL,
        badge TEXT,
        payload TEXT NOT NULL,
        attachments TEXT NOT NULL,
        captured_by TEXT NOT NULL DEFAULT 'me',
        plan_id TEXT REFERENCES plans(id),
        bundle_id TEXT,
        created_at INTEGER NOT NULL,
        updated_at INTEGER NOT NULL,
        version INTEGER NOT NULL DEFAULT 0
      )""",
        )
        store.execute(
            """INSERT INTO artifacts (
        id, category, source_kind, state, origin, title, badge, payload,
        attachments, captured_by, plan_id, bundle_id, created_at, updated_at, version
      )
      SELECT id, category, source_kind, state, origin, title, badge, payload,
             attachments, captured_by, plan_id, bundle_id, created_at, updated_at, version
      FROM artifacts_v4_old""",
        )
        store.execute("DROP TABLE artifacts_v4_old")
        store.execute("CREATE INDEX IF NOT EXISTS idx_artifacts_plan ON artifacts(plan_id)")
        store.execute("CREATE INDEX IF NOT EXISTS idx_artifacts_bundle ON artifacts(bundle_id)")
    }

    suspend fun columnExists(store: SqlStore, table: String, col: String): Boolean {
        val cols = store.rawQuery("PRAGMA table_info($table)")
        return cols.any { it["name"] == col }
    }

    suspend fun addColumnIfMissing(store: SqlStore, table: String, col: String, decl: String) {
        if (!columnExists(store, table, col)) {
            store.execute("ALTER TABLE $table ADD COLUMN $col $decl")
        }
    }
}
