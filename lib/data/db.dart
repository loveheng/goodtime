import 'dart:convert';

import 'package:path/path.dart' as p;
import 'package:sqflite/sqflite.dart';

import '../models/artifact.dart';

/// 存储层：台账库——plans（清单条目=本体 Soul）+ fixed_slots（固定占用=约束）+
/// schedule_blocks（日程块实例=肉身 Avatar）+ backgrounds（软背景，v3 增，
/// background-context-draft.md §2）+ artifacts（硬事实凭证，v4 增，
/// fact-user-relay-draft.md §1.1）+ app_settings KV + collab_*（协同本地六表，
/// v5 增：collab-schedule-draft.md §11.3 / collab-implementation.md §3.1）。
/// Schema 唯一出处为设计 SSOT §4 + 背景草案 §2 + 事实草案 §1.1/§5.5/§9.5-6；
/// 后续演进走 onUpgrade 分段幂等迁移（模式照抄拾贝 db.dart，schedule-app.md §12 领料）。
class Db {
  static Database? _db;

  /// 库路径覆盖口：单测用内存库（inMemoryDatabasePath）做 isolate 级隔离，运行时不设置。
  static String? _pathOverride;
  static void overridePath(String path) => _pathOverride = path;

  static Future<Database> instance() async {
    final cached = _db;
    if (cached != null) return cached;
    final dir = await getDatabasesPath();
    final db = await openDatabase(
      _pathOverride ?? p.join(dir, 'shiguang.db'),
      version: 7,
      onCreate: (db, version) => createAll(db),
      onUpgrade: (db, oldVersion, newVersion) async {
        // 分段幂等迁移区（拾贝模式）：每段「oldVersion < N」+ 幂等 _ensure* 补齐。
        if (oldVersion < 2) {
          // v1→v2：补齐 app_settings（settings KV 与三表同库）
          await _ensureSettingsTable(db);
        }
        if (oldVersion < 3) {
          // v2→v3：backgrounds 软背景表单表先行（施工时序调整，schedule-app §11
          // 留痕；artifacts 拍板落地时另批迁移）
          await _ensureBackgroundsTable(db);
        }
        if (oldVersion < 4) {
          // v3→v4：artifacts 硬事实凭证单表（fact-user-relay-draft.md §1.1，
          // 背景侧已占 v3、本批另批迁移——schedule-app §11 留痕）
          await _ensureArtifactsTable(db);
        }
        if (oldVersion < 5) {
          // v4→v5（collab-implementation.md §3.1，2026-10-09）：块-凭证关联反转
          // （fact §9.5-6：blocks 持 artifact_id，凭证弃 block_id 换 bundle_id，
          // payload 投影清洗）+ collab_* 本地六表 + schedule_blocks.source 扩
          // 'collab'（无 CHECK 约束，零 DDL，新装同构即认）。
          await migrateV5(db);
        }
        if (oldVersion < 6) {
          // v5→v6（Phase 4 编排收口，2026-10-10）：collab_events 补 server_at_ms
          // （法定时钟 §5.2，本地 TTL 研判源）；_addColumnIfMissing 幂等，重跑零副作用。
          await migrateV6(db);
        }
        if (oldVersion < 7) {
          // v6→v7（Phase 4.3，2026-10-10）：collab_steps 补步骤时间槽（物化回填）。
          await migrateV7(db);
        }
      },
      onOpen: (db) async {
        // plans 自引用树与 blocks.plan_id 的外键保护依赖此开关，sqflite 默认关闭
        await db.execute('PRAGMA foreign_keys = ON');
      },
    );
    _db = db;
    return db;
  }

  /// 全量建表（v5 终态）：onCreate 与单测「新装同构」共用的唯一 DDL 源。
  static Future<void> createAll(Database db) async {
    await db.execute('''
      CREATE TABLE IF NOT EXISTS plans (
        id TEXT NOT NULL PRIMARY KEY,                            -- uuid，对外稳定标识（MCP 工具引用）
        title TEXT NOT NULL,                            -- 用户随手打的原始标题，原样保留
        spec TEXT,                                      -- 精确无歧义描述（AI 维护的唯一权威陈述），可空
        notes TEXT,                                     -- 已敲定细节沉淀；首行约定=启动第一步（playbook Landing Gear）
        open_items TEXT,                                -- JSON 数组 [{question, answer?}]，未决问题
        min_viable_action TEXT,                         -- 降级行动描述；spark 执行依据（六轮拍板）
        energy_level TEXT NOT NULL DEFAULT 'light',     -- deep/light，默认 light——快记零负担（七轮拍板）
        tool_required TEXT NOT NULL DEFAULT 'anywhere', -- desk/anywhere，默认 anywhere（七轮拍板）
        reward_spec TEXT,                               -- 犒赏锚点，兑现时生成庆祝块（九轮拍板）
        importance INTEGER NOT NULL DEFAULT 0,          -- 手动一击（快记条旁一颗星）
        deadline TEXT,                                  -- 可空截止日 YYYY-MM-DD；「紧急」为派生态不入库
        estimate INTEGER,                               -- 预估时长（分钟，概念态粗估，细化后修正）
        parent_id TEXT REFERENCES plans(id),            -- 父计划，可空，放开多级（政策建议深度 ≤3）
        archived INTEGER NOT NULL DEFAULT 0,            -- 清单「归档」落点；list_plans 默认过滤（§6）
        created_at INTEGER NOT NULL,                    -- 毫秒时间戳
        updated_at INTEGER NOT NULL,                    -- 毫秒时间戳；冷藏池「超龄」派生判据（§8）
        version INTEGER NOT NULL DEFAULT 0              -- 乐观锁：任何成功写入 +1，CAS 校验用
      )
    ''');
    await db.execute('''
      CREATE TABLE IF NOT EXISTS fixed_slots (
        id TEXT NOT NULL PRIMARY KEY,        -- uuid
        name TEXT NOT NULL,         -- 占用名称（如「睡眠」「通勤」）
        weekdays TEXT NOT NULL,     -- ISO 星期 CSV：'1,2,3'（1=周一…7=周日）；工作日/周末模板=不同 weekday 集合的行并存
        start_min INTEGER NOT NULL, -- 当日起始分钟 0..1439
        end_min INTEGER NOT NULL    -- 当日结束分钟 1..1440（1440=24:00）；小于 start_min ⇒ 跨午夜段
      )
    ''');
    await db.execute('''
      CREATE TABLE IF NOT EXISTS schedule_blocks (
        id TEXT NOT NULL PRIMARY KEY,                            -- uuid
        date TEXT NOT NULL,                             -- YYYY-MM-DD 开始日（作息归属唯一真相；跨午夜不拆段）
        start_min INTEGER NOT NULL,                     -- 当日起始分钟 0..1439
        end_min INTEGER NOT NULL,                       -- 当日结束分钟 1..1440；end<start ⇒ 跨午夜；必须有起止（§11 拍板）
        plan_id TEXT REFERENCES plans(id),              -- 可空——孤立块不欠清单交代（§4）
        label TEXT,                                     -- 块自己的行动描述（动词开头），可空回落 plan 标题
        source TEXT NOT NULL,                           -- human/ai/collab——和平条款依据（§3；v5 扩 'collab'，CONFIRMED 下沉块，设计稿 §11.3-1）
        pinned INTEGER NOT NULL DEFAULT 0,              -- 钉住：AI 建的无 plan 块（火车/航班）默认 pinned（§4）
        status TEXT NOT NULL DEFAULT 'proposed',        -- proposed/confirmed/done/skipped/missed/archived/melted
        postpone_count INTEGER NOT NULL DEFAULT 0,      -- 顺延计数，≥3 打「需人工决策」标（§8）
        execution_quality TEXT NOT NULL DEFAULT 'full', -- full/spark——spark 计入深潜净值（六轮拍板）
        is_day_spark INTEGER NOT NULL DEFAULT 0,        -- 当日黄金火种，每作息日至多一个（§4）
        is_celebration INTEGER NOT NULL DEFAULT 0,      -- 庆祝块：最高豁免权，过期绝不 missed（九轮拍板）
        artifact_id TEXT,                               -- v5 反转关联（fact §9.5-6）：块持凭证 1:N 软引用无外键——
                                                        -- 凭证比块长寿，日切删块只自然悬空，读侧查无此块静默降级
        created_at INTEGER NOT NULL,                    -- 毫秒时间戳
        updated_at INTEGER NOT NULL,                    -- 毫秒时间戳
        version INTEGER NOT NULL DEFAULT 0              -- 乐观锁：任何成功写入 +1，CAS 校验用
      )
    ''');
    await db.execute(
      'CREATE INDEX IF NOT EXISTS idx_blocks_date ON schedule_blocks(date)',
    );
    await db.execute(
      'CREATE INDEX IF NOT EXISTS idx_blocks_plan ON schedule_blocks(plan_id)',
    );
    await db.execute(
      'CREATE INDEX IF NOT EXISTS idx_blocks_artifact ON schedule_blocks(artifact_id)',
    );
    await db.execute(
      'CREATE INDEX IF NOT EXISTS idx_plans_parent ON plans(parent_id)',
    );
    await db.execute('''
      CREATE TABLE IF NOT EXISTS backgrounds (
        id TEXT NOT NULL PRIMARY KEY,                   -- uuid
        scope TEXT NOT NULL,                            -- global/plan——建档定死不迁移（改挂=删了重录）
        plan_id TEXT REFERENCES plans(id),              -- scope=plan 时必填；NO ACTION 护栏（无 ON DELETE），
                                                        -- 删除处置=delete_plan 命令层同事务级联销毁（§2 否决 DDL CASCADE）
        content TEXT NOT NULL,                          -- 给人与 AI 读的归纳描述（机读/展示分离）
        raw_source_text TEXT,                           -- 原话永存（溯源/撤销重构）；建档填一次，永不变（§3 手编契约）
        tags TEXT,                                      -- JSON 字符串数组：#健康 式自由标签，不锁闭枚举
        applicable_dates TEXT,                          -- JSON 单日 ISO 日期数组（一律日历日）；null=长期有效
        source TEXT NOT NULL,                           -- user/ai_derived（provenance，出生来源不可变）
        captured_by TEXT NOT NULL DEFAULT 'me',         -- 多人预留钉子（主草案 §3.4）
        created_at INTEGER NOT NULL,                    -- 毫秒时间戳
        updated_at INTEGER NOT NULL,                    -- 毫秒时间戳
        version INTEGER NOT NULL DEFAULT 0              -- 乐观锁：任何成功写入 +1，CAS 校验用
      )
    ''');
    await db.execute(
      'CREATE INDEX IF NOT EXISTS idx_backgrounds_plan ON backgrounds(plan_id)',
    );
    await db.execute('''
      CREATE TABLE IF NOT EXISTS artifacts (
        id TEXT NOT NULL PRIMARY KEY,                   -- uuid
        category TEXT NOT NULL,                         -- transit/ticket/hotel/venue/verbal（五类收敛，§1.2）
        source_kind TEXT NOT NULL,                      -- booking/announcement/verbal（三级可靠性）
        state TEXT NOT NULL,                            -- raw/structured/voided（voided=退票作废存证，读侧全通道静默）
        origin TEXT NOT NULL,                           -- shared/quicknote/ai/manual（出生来源）
        title TEXT NOT NULL,                            -- 摘要标题（快记通道=原文原样落库）
        badge TEXT,                                     -- 时间轴微标单值（仅 booking 类 AI 显式指定，超 12 字符命令层截断）
        payload TEXT NOT NULL,                          -- §1.2 契约 JSON 单一真相（首字段 "v":1；元数据列=提取投影同源写入）
        attachments TEXT NOT NULL,                      -- 预留列恒存空数组 JSON（图片 V1.5 走 human 通道，零迁移演进）
        captured_by TEXT NOT NULL DEFAULT 'me',         -- 多人预留钉子（主草案 §3.4）
        plan_id TEXT REFERENCES plans(id),              -- 可空=null=未归属池；NO ACTION 护栏，detach 处置归命令层
        bundle_id TEXT,                                 -- v5 协同弱引用不设外键（fact §5.5）：协商期凭证挂靠方案包
                                                        -- 但不持 plan，既不触发外键死锁也不污染未归属池
        created_at INTEGER NOT NULL,                    -- 毫秒时间戳
        updated_at INTEGER NOT NULL,                    -- 毫秒时间戳
        version INTEGER NOT NULL DEFAULT 0              -- 乐观锁：提炼回填/改挂/作废走 CAS
      )
    ''');
    await db.execute(
      'CREATE INDEX IF NOT EXISTS idx_artifacts_plan ON artifacts(plan_id)',
    );
    await db.execute(
      'CREATE INDEX IF NOT EXISTS idx_artifacts_bundle ON artifacts(bundle_id)',
    );
    await db.execute('''
      CREATE TABLE IF NOT EXISTS app_settings (
        key TEXT PRIMARY KEY,       -- 设置键注册表：lib/data/settings.dart SettingsKeys
        value TEXT NOT NULL,        -- 统一字符串存储，类型编解码在 SettingsKeys/命令层
        updated_at INTEGER NOT NULL -- 毫秒时间戳
      )
    ''');
    await _createCollabTables(db);
  }

  // ─── v4→v5 迁移（collab-implementation.md §3.1，2026-10-09）──────────────────
  //
  // 反转关联（fact 草案 §9.5-6 二次修订定盘）：现实是「块依赖凭证」——废除
  // artifacts.block_id，改由 schedule_blocks 持 artifact_id（1:N，酒店/门票一证
  // 多块各引）。迁移五步全幂等，顺序不可换：
  //   ① schedule_blocks ADD artifact_id（可空软引用）
  //   ② artifacts ADD bundle_id（协同弱引用，fact §5.5 协商期挂靠）
  //   ③ **存量绑定搬家**：artifacts.block_id → schedule_blocks.artifact_id
  //     （日切已删的悬空引用静默丢弃——读侧查无此块自然降级，§9.5-8 口径）
  //   ④ payload 投影清洗：契约 JSON 内残留的 block_id 键移除（双写铁律收窄为
  //     plan_id 单键，fact 草案 §1.1-② 2026-10-09 修订）
  //   ⑤ DROP artifacts.block_id（SQLite ≥3.35 原生；更老版本走 12 步重建兜底）
  //   ⑥ collab_* 本地六表幂等补齐
  //
  // 测试/手动重建口：公开静态方法，onCreate 与 onUpgrade < 5 共用同一 DDL 源
  // （保证「新装同构」），单测经 databaseFactory 直接驱动验证迁移语义。
  static Future<void> migrateV5(Database db) async {
    // ① blocks 持凭证（列在才加，重跑零副作用）
    await _addColumnIfMissing(db, 'schedule_blocks', 'artifact_id', 'TEXT');
    // ② 凭证持协同包（弱引用）
    await _addColumnIfMissing(db, 'artifacts', 'bundle_id', 'TEXT');
    // ③ 绑定搬家：块侧回填（块已物理删除的引用自然丢弃，不造悬空块）
    if (await _columnExists(db, 'artifacts', 'block_id')) {
      final bound = await db.rawQuery(
        'SELECT id, block_id FROM artifacts WHERE block_id IS NOT NULL',
      );
      for (final r in bound) {
        await db.execute(
          'UPDATE schedule_blocks SET artifact_id = ? WHERE id = ?',
          [r['id'] as String, r['block_id'] as String],
        );
      }
      // ④ payload 投影清洗：block_id 键出列（plan_id 投影保留，双写铁律收窄）
      final dirtied = await db.rawQuery(
        "SELECT id, payload FROM artifacts WHERE payload LIKE '%block_id%'",
      );
      for (final r in dirtied) {
        final raw = r['payload'] as String?;
        Map<String, Object?> payload;
        try {
          final decoded = jsonDecode(raw ?? '');
          if (decoded is! Map) continue; // 非对象 payload 不动（读侧另有防御）
          payload = Map<String, Object?>.from(decoded);
        } catch (_) {
          continue; // 整列畸形 payload 不动——清洗是洁癖不是修复，别把数据洗丢
        }
        if (!payload.containsKey('block_id')) continue;
        payload.remove('block_id');
        await db.execute(
          'UPDATE artifacts SET payload = ? WHERE id = ?',
          [Artifact.encodePayload(payload), r['id'] as String],
        );
      }
      // ⑤ 出列：SQLite ≥3.35 原生 DROP COLUMN；老内核（Android ≤12 的 3.32/3.34）
      //    语法不支持 → 12 步重建兜底（同列集复制，一行不丢）
      try {
        await db.execute('ALTER TABLE artifacts DROP COLUMN block_id');
      } catch (_) {
        if (await _columnExists(db, 'artifacts', 'block_id')) {
          await _rebuildArtifactsWithoutBlockId(db);
        }
      }
    }
    // ⑥ collab_* 本地六表（CREATE IF NOT EXISTS，重跑零副作用）
    await _createCollabTables(db);
  }

  /// artifacts 去掉 block_id 的 12 步重建兜底（老 SQLite 无 DROP COLUMN 时走此路）。
  static Future<void> _rebuildArtifactsWithoutBlockId(Database db) async {
    await db.execute('ALTER TABLE artifacts RENAME TO artifacts_v4_old');
    await db.execute('''
      CREATE TABLE artifacts (
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
      )
    ''');
    await db.execute('''
      INSERT INTO artifacts (
        id, category, source_kind, state, origin, title, badge, payload,
        attachments, captured_by, plan_id, bundle_id, created_at, updated_at, version
      )
      SELECT id, category, source_kind, state, origin, title, badge, payload,
             attachments, captured_by, plan_id, bundle_id, created_at, updated_at, version
      FROM artifacts_v4_old
    ''');
    await db.execute('DROP TABLE artifacts_v4_old');
    await db.execute(
      'CREATE INDEX IF NOT EXISTS idx_artifacts_plan ON artifacts(plan_id)',
    );
    await db.execute(
      'CREATE INDEX IF NOT EXISTS idx_artifacts_bundle ON artifacts(bundle_id)',
    );
  }

  /// v5→v6（Phase 4 编排收口，2026-10-10）：collab_events 补 `server_at_ms`（法定时钟
  /// §5.2，本地 TTL/熔断研判源）。_addColumnIfMissing 幂等，重跑零副作用；新装经
  /// createAll 的 _createCollabTables 已含该列，本迁移只补存量 v5 库。
  static Future<void> migrateV6(Database db) async {
    await _addColumnIfMissing(
      db,
      'collab_events',
      'server_at_ms',
      'INTEGER NOT NULL DEFAULT 0',
    );
  }

  /// v6→v7（Phase 4.3 物化回填，2026-10-10）：collab_steps 补步骤**时间槽**
  /// [starts_at_ms]/[ends_at_ms]——解开 member_calendar_page 顶部「时间槽未物化」
  /// 施工钉子（骨架只含结构，真实时段在加密载荷里，需在解密回放那一刻顺手抓取写入）。
  ///
  /// 两列可为 NULL：未拿到对应步骤密钥/载荷未含时段的行保持空，UI 据此降级渲染。
  /// _addColumnIfMissing 幂等，重跑零副作用；新装经 createAll 已含该列。
  static Future<void> migrateV7(Database db) async {
    await _addColumnIfMissing(
      db,
      'collab_steps',
      'starts_at_ms',
      'INTEGER',
    );
    await _addColumnIfMissing(
      db,
      'collab_steps',
      'ends_at_ms',
      'INTEGER',
    );
  }

  /// collab_* 协同本地六表（collab-schedule-draft.md §11.3 + collab-implementation
  /// §3.1）。列名与 relay schema.sql snake_case 对齐防翻译错；onCreate 与 v5 迁移
  /// 共用同一 DDL 源（新装同构）。FK 只挂同生同灭的强引用（NO ACTION 护栏，删除
  /// 处置归命令层——沿用单机口径）；块引用/包引用一律软引用（归档清事件流、日切
  /// 删块都不能被外键卡死）。
  static Future<void> _createCollabTables(Database db) async {
    await db.execute('''
      CREATE TABLE IF NOT EXISTS collab_bundles (
        id TEXT NOT NULL PRIMARY KEY,               -- = relay bundles.bundle_id（uuid）
        proposal_type TEXT NOT NULL,                -- DIRECTIVE/CONSENSUS（协同 §4.4 双模）
        status TEXT NOT NULL DEFAULT 'PROPOSED',    -- PROPOSED/STALLED/CONFIRMED/CANCELLED/EXPIRED（FSM 终态，§4.3）
        lifecycle TEXT NOT NULL DEFAULT 'active',   -- active/archived（§5.7 归档只清事件流）
        base_revision INTEGER NOT NULL DEFAULT 1,   -- 整包重构校验（事实三动作豁免，v0.1.3）
        max_seq_id INTEGER NOT NULL DEFAULT 0,      -- 本地已见最高 seq（增量补拉水位）
        last_acked_seq INTEGER NOT NULL DEFAULT 0,  -- CLIENT_ACK 游标（§10.3-2）
        key_version INTEGER NOT NULL DEFAULT 1,     -- 当前 K_bundle 版本（REKEY +1）
        initiator_id TEXT NOT NULL,                 -- 发起人 user_id
        participants TEXT NOT NULL,                 -- JSON 数组：参与全员 user_id（ACL 投影）
        expires_at INTEGER NOT NULL,                -- TTL（Soft-Lock / EXPIRED 扫描）
        created_at INTEGER NOT NULL,
        updated_at INTEGER NOT NULL
      )
    ''');
    await db.execute('''
      CREATE TABLE IF NOT EXISTS collab_steps (
        bundle_id TEXT NOT NULL REFERENCES collab_bundles(id),
        id TEXT NOT NULL,                           -- step_uid（bundle 内唯一，target_step_ref 反查键）
        required_uids TEXT NOT NULL,                -- JSON 数组：该步必答成员
        threshold INTEGER NOT NULL DEFAULT 1,       -- M-of-N 门限（§4.2 骨架 threshold）
        is_optional INTEGER NOT NULL DEFAULT 0,
        status TEXT NOT NULL DEFAULT 'PENDING',     -- PENDING/READY/TIME_CLASH/ROLE_CLASH/PRUNED
        votes TEXT,                                 -- JSON map {uid: PREFER|OK|NO}（Consensus 三值）
        sort INTEGER NOT NULL DEFAULT 0,            -- DAG 拓扑序（渲染排序）
        starts_at_ms INTEGER,                       -- 步骤时间槽起点（物化回填；未解密到则为 NULL）
        ends_at_ms INTEGER,                         -- 步骤时间槽终点（同上）
        created_at INTEGER NOT NULL,
        updated_at INTEGER NOT NULL,
        PRIMARY KEY (bundle_id, id)
      )
    ''');
    await db.execute('''
      CREATE TABLE IF NOT EXISTS collab_events (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        bundle_id TEXT NOT NULL REFERENCES collab_bundles(id),
        seq_id INTEGER NOT NULL,                    -- 服务端定序；UNIQUE(bundle_id, seq_id) 对齐 relay
        action_type TEXT NOT NULL,                  -- Envelope.ActionType 枚举值（明文在场：密文解不开骨架仍推进）
        sender_id TEXT NOT NULL,                    -- sender_user_id
        cipher TEXT NOT NULL,                       -- base64 密文原样落库（解密在回放时按 key_ver 取钥）
        key_ver INTEGER NOT NULL DEFAULT 1,         -- 回放按 KeyRing 版本取钥（§5.3 密钥环）
        client_msg_id TEXT NOT NULL,                -- 幂等去重键（relay SETEX 同源）
        topology_ref TEXT,                          -- bundle_skeletons.skeleton_id
        server_at_ms INTEGER NOT NULL DEFAULT 0,     -- 法定时钟（§5.2）：服务端落盘时间，本地 TTL 研判源
        created_at INTEGER NOT NULL,
        UNIQUE (bundle_id, seq_id)
      )
    ''');
    await db.execute(
      'CREATE INDEX IF NOT EXISTS idx_collab_events_msg ON collab_events(bundle_id, client_msg_id)',
    );
    await db.execute('''
      CREATE TABLE IF NOT EXISTS collab_key_ring (
        bundle_id TEXT NOT NULL REFERENCES collab_bundles(id),
        key_ver INTEGER NOT NULL,                   -- K_bundle 版本（REKEY 追加新行，旧版本保留供离线回放）
        envelope TEXT NOT NULL,                     -- MSK 封送的 K_bundle 信封（密文落库）
        created_at INTEGER NOT NULL,
        PRIMARY KEY (bundle_id, key_ver)            -- 同版本幂等，绝不 LWW 覆盖（对齐 relay user_key_envelopes）
      )
    ''');
    await db.execute('''
      CREATE TABLE IF NOT EXISTS local_outbox_events (
        id TEXT NOT NULL PRIMARY KEY,               -- uuid（与 client_msg_id 同源生成）
        bundle_id TEXT NOT NULL,                    -- 软引用（outbox 生命周期独立于 bundle 行）
        action_type TEXT NOT NULL,
        payload TEXT NOT NULL,                      -- 待发明文 payload JSON（本地域；发送时经 sealing 加密）
        key_ver INTEGER NOT NULL DEFAULT 1,
        status TEXT NOT NULL DEFAULT 'PENDING_SEND', -- PENDING_SEND/SENT（确认后物理删除，§11.1）
        attempts INTEGER NOT NULL DEFAULT 0,
        created_at INTEGER NOT NULL,
        sent_at INTEGER
      )
    ''');
    await db.execute('''
      CREATE TABLE IF NOT EXISTS collab_block_refs (
        block_id TEXT NOT NULL PRIMARY KEY,         -- schedule_blocks.id（软引用不设外键：日切删块自然悬空）
        bundle_id TEXT NOT NULL,                    -- 软引用
        step_id TEXT NOT NULL,                      -- = target_step_ref（§5.5 下沉绑定反查键）
        created_at INTEGER NOT NULL
      )
    ''');
    await db.execute(
      'CREATE INDEX IF NOT EXISTS idx_collab_refs_step ON collab_block_refs(bundle_id, step_id)',
    );
  }

  // ---- 幂等小件 ----

  static Future<bool> _columnExists(Database db, String table, String col) async {
    final cols = await db.rawQuery('PRAGMA table_info($table)');
    return cols.any((c) => c['name'] == col);
  }

  static Future<void> _addColumnIfMissing(
    Database db,
    String table,
    String col,
    String decl,
  ) async {
    if (!await _columnExists(db, table, col)) {
      await db.execute('ALTER TABLE $table ADD COLUMN $col $decl');
    }
  }

  /// 幂等补齐 app_settings（v1→v2，2026-10-05）：settings 是三表之外唯一持久化面，
  /// 与台账同库——「导出 JSON 全量」（§11 数据出口）单库齐全，备份恢复一个文件。
  static Future<void> _ensureSettingsTable(Database db) async {
    await db.execute('''
      CREATE TABLE IF NOT EXISTS app_settings (
        key TEXT PRIMARY KEY,
        value TEXT NOT NULL,
        updated_at INTEGER NOT NULL
      )
    ''');
  }

  /// 幂等补齐 backgrounds（v2→v3，2026-10-07）：软背景层（background-context-draft.md §2）。
  static Future<void> _ensureBackgroundsTable(Database db) async {
    await db.execute('''
      CREATE TABLE IF NOT EXISTS backgrounds (
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
      )
    ''');
    await db.execute(
      'CREATE INDEX IF NOT EXISTS idx_backgrounds_plan ON backgrounds(plan_id)',
    );
  }

  /// 幂等补齐 artifacts（v3→v4，2026-10-07）：硬事实凭证层（fact-user-relay-draft.md §1.1）。
  /// 注意：此处保持 **v4 形态（block_id 在位）**——v3 存量先补齐 v4 基线，随后
  /// 统一走 migrateV5 反转（保证 v1/v3 设备与 v4 设备收敛到同一 v5 终态）。
  static Future<void> _ensureArtifactsTable(Database db) async {
    await db.execute('''
      CREATE TABLE IF NOT EXISTS artifacts (
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
      )
    ''');
    await db.execute(
      'CREATE INDEX IF NOT EXISTS idx_artifacts_plan ON artifacts(plan_id)',
    );
  }
}
