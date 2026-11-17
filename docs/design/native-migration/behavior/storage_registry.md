---
status: active
updated: 2026-11-14
---

# 行为清单 · storage 原生存储层（repository SQL 平移，P2）

> §9.1.6 模板产出（2026-11-14 抽取自 lib/data/{storage,db,repository}.dart，Schema 契约权威出处 = 黄金向量 `sqlite_schema_vectors.json` 6 case）。
> 契约红线：**Schema 严禁变更**（六类语义之一，DDL → PRAGMA 结构逐位对拍）；WAL + busy_timeout=5000 严禁下调；64 位列全量 Long。

## 分层与落点（2026-11-14 定）

- `:core:model`：`SqlStore`/`SqlTxn` 接口（镜像 Dart `ScheduleStorage`/`StorageTxn` 轴 B 抽象）+ `ShiguangSchema`（DDL 常量 + createAll + 七段幂等迁移）——存储接口与 Schema 契约的家（§10 决定 5「:core:model 含存储接口，必须 pure JVM」）。
- `:core:action`：`NativeSqlRepository`（实现既有 `BridgeRepositoryPort`，行级 SQL + values 解释）+ `dbWriteDispatcher` 单线程写队列。
- `:app`：`AndroidSqlStore`（SQLiteOpenHelper 驱动：onConfigure 开 WAL + busy_timeout=5000、onOpen foreign_keys=ON、onCreate→createAll、onUpgrade→分段幂等迁移）——接线进 binder 入口属后续轮（goodhub 联调挂账）。

## SqlStore 接口（镜像 Dart ScheduleStorage 子集）

| 方法 | 语义 |
|---|---|
| `insert(table, values, replace)` | replace=true = `INSERT OR REPLACE`（app_settings upsert / replaceFixedSlots 不用 replace） |
| `query(table, columns?, where?, args?, orderBy?, limit?)` | 读查询（表内） |
| `rawUpdate(sql, args)` | CAS/字段级 UPDATE |
| `rawQuery(sql, args?)` | JOIN 等读形 |
| `delete(table, where?, args?)` | 物理删除 |
| `transaction(body)` | 全成功提交 / 中途异常整体回滚；回调内 SqlTxn（insert/delete/query/rawUpdate/update）读写同事务（read-your-writes） |

- 行值类型：INTEGER→`Long`（**64 位列铁律**：created_at/updated_at/expires_at/seq/version 等毫秒时间戳约 1.76e12）、TEXT→`String?`；NULL→`null`。
- 64 位映射断言：种子行写入 1.76e12 时间戳回读逐列相等（防 Int 静默截断）。

## Schema DDL（v7 终态，12 表）

权威出处 = `sqlite_schema_vectors.json` `schema_fresh_v7`（PRAGMA 全结构）；DDL 文本抽取自 db.dart createAll（结构以向量仲裁，写法差异属实现细节）：

- `plans`（17 列，id TEXT PK，parent_id REFERENCES plans(id)，energy_level/tool_required/archived/version 带缺省）+ `idx_plans_parent`
- `fixed_slots`（5 列，weekdays CSV TEXT）
- `schedule_blocks`（16 列，plan_id REFERENCES，source 无 CHECK，v5 追加 artifact_id 软引用）+ `idx_blocks_date/plan/artifact`
- `backgrounds`（12 列，plan_id REFERENCES NO ACTION——删除处置归命令层）+ `idx_backgrounds_plan`
- `artifacts`（15 列，v5 终态：无 block_id、有 bundle_id 弱引用）+ `idx_artifacts_plan/bundle`
- `app_settings`（key TEXT PK / value / updated_at）
- collab 六表：`collab_bundles`（id PK）／`collab_steps`（复合 PK (bundle_id,id)，REFERENCES bundles）／`collab_events`（id INTEGER PK AUTOINCREMENT，UNIQUE(bundle_id,seq_id)，REFERENCES bundles）+ `idx_collab_events_msg`／`collab_key_ring`（复合 PK (bundle_id,key_ver)，REFERENCES bundles）／`local_outbox_events`（id PK，bundle_id 软引用）／`collab_block_refs`（block_id PK 软引用）+ `idx_collab_refs_step`

## 七段幂等迁移史（新装=直接 createAll v7；升级=分段补齐）

| 段 | 内容 |
|---|---|
| v1→v2 | `CREATE TABLE IF NOT EXISTS app_settings` |
| v2→v3 | backgrounds 表 + idx_backgrounds_plan |
| v3→v4 | artifacts **v4 形态（block_id 在位）** + idx_artifacts_plan |
| v4→v5 | migrateV5 六步（顺序不可换）：① blocks ADD artifact_id ② artifacts ADD bundle_id ③ 绑定搬家 block_id→blocks.artifact_id（悬空静默丢弃）④ payload 清洗 block_id 键（畸形不洗——洁癖不是修复）⑤ DROP artifacts.block_id（SQLite≥3.35 原生；老内核 12 步重建兜底 artifacts_v4_old→复制→DROP→补两索引）⑥ collab 六表 + ⑦ 索引幂等补齐 7 条（D6 拍板） |
| v5→v6 | collab_events ADD server_at_ms INTEGER NOT NULL DEFAULT 0 |
| v6→v7 | collab_steps ADD starts_at_ms/ends_at_ms INTEGER（可空） |

- 幂等性=每段 `IF NOT EXISTS` / addColumnIfMissing（PRAGMA table_info 探测），重跑零副作用（迁移测试钉样）。
- 向量对拍段 = v4→v7（公开链 migrateV5/V6/V7）；v1→v4 三段为幂等补表。

## 连接口径（AndroidSqlStore，2026-11-15 真机仪器测试修订）

- **单连接制**：不开框架 `ENABLE_WRITE_AHEAD_LOGGING` flag（该 flag 开连接池，池化读连接不过 SQLiteOpenHelper 回调——真机实测池化读连接 busy_timeout 回落 Android 默认 2500）；每引擎单连接 = sqflite 形态 = R3 双写者实测受测形状。
- `onConfigure`：显式 `journal_mode=WAL` + `busy_timeout=5000`（**严禁下调**——实测阈值 10ms 写锁持有，5s 是 R1 双写者兜底）；**Android Cursor 懒执行——rawQuery 必须 moveToFirst 触发**（close 不执行，懒执行即 journal 读回框架默认）。
- `onOpen`：`PRAGMA foreign_keys = ON`（plans 自引用树与 blocks.plan_id FK 护栏依赖此开关，sqflite 默认关闭）。
- 真机实证（仪器测试 6/6，2026-11-15）：存量库直开零迁移/双句柄 N=400 竞争零 busy 异常/PRAGMA 章/64 位 Long 保真；回归钉=AndroidSqlStoreDeviceTest。
- `onCreate` → createAll（v7 全量）；`onUpgrade` → 分段幂等迁移（oldVersion < N 逐段）。
- 库文件 = 既有 sqflite `shiguang.db`（首启打开验证：存量 v7 库零迁移直开）。

## NativeSqlRepository（BridgeRepositoryPort 行级语义 + values 解释）

写路径全部经 `dbWriteDispatcher`（专用单线程协程分发器）——镜像 Dart `command_handler.execute` 包 `repo.synchronized` FIFO：命令级复合写天然串行（TOCTOU 防护）；读不入队；锁内不做重活。双轨期 Dart 侧 Repository.synchronized 同在（两套队列分列单侧写，WAL+busy_timeout 兜底跨侧竞争）。

| 方法 | 语义（Dart Repository 逐字） |
|---|---|
| `newId()` | RFC 4122 v4 uuid（Java UUID.randomUUID 即同构） |
| `addPlan(values)` | 缺省垫底+overlay：energy=light/tool=anywhere/importance=0/archived=0/version=0/open_items='[]'；id??newId；created_at??now、updated_at=now → INSERT → 返回 planToJson 形 |
| `planById(id)` | limit 1 → planToJson 形或 null |
| `listPlans()` | `archived = 0` ORDER BY created_at DESC（归档冷数据不拉 §6） |
| `patchPlan(id, values, cas)` | 白名单 {title,spec,notes,open_items,min_viable_action,energy_level,tool_required,reward_spec,importance,deadline,estimate,parent_id,archived}；未知列抛；SET 列 + `version=version+1` + updated_at=now；CAS `AND version=?`；空 patch 幂等 true |
| `deletePlan(id)` | 物理删除；0 行 → 异常（FK NO ACTION 有引用即拒，处置归命令层） |
| `addBackground/patchBackground/backgroundById/listBackgrounds()` | 同构（白名单 {content,tags,applicable_dates}；list 全量 ORDER created_at DESC——scope/planId 过滤在调用方） |
| `addArtifact(values)` | **upsert_facts 行语义**（含 block_id 键出列）：create（id??newId、state??raw、payload 键组装 "v":1 契约 JSON、attachments='[]'、captured_by='me'、version=0）→ 事务 INSERT + `UPDATE schedule_blocks SET artifact_id,version+1,updated_at WHERE id=block_id`（块已删=绑定静默跳过，软引用 §9.5-8）；edit（id 在）→ 读旧行 → CAS expected_version → 白名单 {title,badge,state,payload,plan_id} patch → 返回 artifactToJson 形 |
| `artifactById(id)` / `artifactsForPlans(planIds)` / `artifactsByState(state)` | 行读取 → artifactToJson 形；ForPlans=`plan_id IN (…) AND state != 'voided'` ORDER created_at ASC；ByState=`state = ?` ORDER created_at ASC |
| `deleteArtifact(id)` | 0 行 → 异常 |
| `listFixedSlots()` | ORDER BY start_min → slotToJson 形 |
| `replaceFixedSlots(slots)` | 事务：DELETE 全表 + 逐条 INSERT（id??newId）——整批原子 |
| `fixedSlotsForDate(date)` | listFixedSlots 派生：onDate=coversWeekday(当日 weekday)；spillover=跨午夜 && coversWeekday(前日)——返回 `{on_date:[…], spillover:[…]}`（slotToJson 形） |
| `settingsAll()` | 全表 → Map |
| `settingsGet(key)` | limit 1 → String? |
| `settingsSet(values)` | 事务批量 `INSERT OR REPLACE`（updated_at=now） |
| `settingsClear(key)` | DELETE；`settingsClearAll(keys)` 事务批量 |
| `blocksOnDate(date)` | `date = ?` ORDER BY start_min → blockToJson 形 |
| `blocksInRange(from,to)` | `date >= ? AND date <= ?` ORDER BY date,start_min |
| `blockById(id)` | limit 1 → blockToJson 形或 null |
| `addBlock(values)` | 缺省垫底（status=proposed/quality=full/pinned 等显式入值）+ created_at=updated_at=now、version=0 → INSERT → blockToJson 形 |
| `patchBlock(id, values, cas)` | 白名单 {date,start_min,end_min,plan_id,label,source,pinned,status,postpone_count,execution_quality,is_day_spark,is_celebration}；CAS 同构 |
| `deleteBlock(id)` | 0 行 → 异常 |

- **布尔列口径**：行内 INTEGER 0/1 ⇄ MCP JSON true/false——白名单 patch 的布尔值入列前转 0/1；返回一律经 toMcpJson（模型层既有）。
- values 解释契约同样约束未来 Dart 适配器（双轨对称）：两实现同输入 → 同行语义。

## Kotlin 对拍（验收实录 2026-11-14）

- schema 向量 6 case Kotlin 首消费全绿（consumers 注记兑现）：fresh 全结构深比 / upgrade v4→v7 同构（新装≡升级不变量）/ 约束四钉拒绝 / 迁移幂等重跑零副作用——执行器 GoldenSchemaVectorTest（:core:model，JdbcSqlStore 内存库）
- 64 位列断言过：1.76e12 毫秒时间戳行级回读 Long 保真（Int 溢出防线实证）
- 仓储行为测试 9 件全绿（NativeSqlRepositoryTest）：addPlan 缺省垫底/patchPlan CAS+白名单拒+空 patch 幂等/addArtifact 建档绑定两态（块在=绑定+version+1；块删=静默跳过）/upsertFacts 全语义链（raw verbal 占位→出生确认严进→提炼回填 raw_text 追加+badge 截断→作废 demote）/rule 锚漏窗即拒/deleteArtifact demote/replaceFixedSlots 重复 id 整批回滚/settingsSet upsert+批量清键/blocksInRange 排序+缺失删除即异常/transaction 回滚
- 门禁注记：K1 正则移除 SQLiteOpenHelper/RoomDatabase 全域类名扫描（数据层本体≠UI 触达，判定本职=Konsist P4）；K8 收窄（前轮）同则
- 遗留偏差修复入账：FixedSlot.toMap weekdays 数组→CSV（Dart join(',') 行口径）；FixedSlot.fromMap 补 CSV/数组双形态（Dart _parseWeekdays 镜像）；缺口挂账=propose/adjust 的 factRuleViolations 硬红线族未平移（独立轮，行为清单先行）

## Kotlin 对拍（本轮验收）

- **schema 向量消费骨架**（向量 consumers 首个 Kotlin 执行器）：sqlite-jdbc 驱动真跑——fresh_create_all → PRAGMA 全结构快照逐字段深比 schema_fresh_v7；legacy v4 DDL + user_version=4 → migrateV5/V6/V7 → 快照深比 upgrade_isomorphic_v4_to_v7；约束四 case（events UNIQUE(bundle_id,seq_id)/key_ring 同版本 PK/FK 悬空拒/block_refs PK）断言拒绝行为。
- **64 位列映射断言**：种子 1.76e12 时间戳逐表回读。
- **repository 行为测试**：addPlan 缺省/patchPlan CAS+白名单拒/addArtifact 绑定事务（块在/块删两态）/replaceFixedSlots 原子/settingsSet upsert/blocksInRange 排序。
- GoldenQueriesTest（queries 读面值泛化对拍）不在本轮——行为测试移植独立轮。
