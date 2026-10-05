import 'package:path/path.dart' as p;
import 'package:sqflite/sqflite.dart';

/// 存储层：三表台账（schedule-app.md §4）——plans（清单条目=本体 Soul）+
/// fixed_slots（固定占用=约束）+ schedule_blocks（日程块实例=肉身 Avatar）。
/// Schema 唯一出处为设计 SSOT §4；v1 三表一次成形，后续演进走
/// onUpgrade 分段幂等迁移（模式照抄拾贝 db.dart，schedule-app.md §12 领料）。
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
      version: 2,
      onCreate: (db, version) => _createAll(db),
      onUpgrade: (db, oldVersion, newVersion) async {
        // 分段幂等迁移区（拾贝模式）：每段「oldVersion < N」+ 幂等 _ensure* 补齐。
        if (oldVersion < 2) {
          // v1→v2：补齐 app_settings（settings KV 与三表同库）
          await _ensureSettingsTable(db);
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

  static Future<void> _createAll(Database db) async {
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
        source TEXT NOT NULL,                           -- human/ai——和平条款依据（§3）
        pinned INTEGER NOT NULL DEFAULT 0,              -- 钉住：AI 建的无 plan 块（火车/航班）默认 pinned（§4）
        status TEXT NOT NULL DEFAULT 'proposed',        -- proposed/confirmed/done/skipped/missed/archived/melted
        postpone_count INTEGER NOT NULL DEFAULT 0,      -- 顺延计数，≥3 打「需人工决策」标（§8）
        execution_quality TEXT NOT NULL DEFAULT 'full', -- full/spark——spark 计入深潜净值（六轮拍板）
        is_day_spark INTEGER NOT NULL DEFAULT 0,        -- 当日黄金火种，每作息日至多一个（§4）
        is_celebration INTEGER NOT NULL DEFAULT 0,      -- 庆祝块：最高豁免权，过期绝不 missed（九轮拍板）
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
      'CREATE INDEX IF NOT EXISTS idx_plans_parent ON plans(parent_id)',
    );
    await db.execute('''
      CREATE TABLE IF NOT EXISTS app_settings (
        key TEXT PRIMARY KEY,       -- 设置键注册表：lib/data/settings.dart SettingsKeys
        value TEXT NOT NULL,        -- 统一字符串存储，类型编解码在 SettingsKeys/命令层
        updated_at INTEGER NOT NULL -- 毫秒时间戳
      )
    ''');
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
}
