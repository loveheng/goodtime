---
status: active
updated: 2026-10-19
---

# 拾光编码规范（coding-standards）

> 上游 SSOT：[schedule-app.md](../design/schedule-app.md)（机制设计）+ [functional-spec.md](../design/functional-spec.md)（功能清单）。本文只做**工程规则终态陈述**：分层矩阵、落点决策、红线规则正文与违例处置。不引入任何新机制；发现设计缺口回上游补拍板。改前定位（某功能在哪）查 [index.md](index.md)；命令查 shiguang-workflow skill。

## 1. 分层矩阵

核心架构原则——**无头系统**：删掉整个 Flutter UI，MCP 命令面（mcp + action + data）应能完整运行。因此 UI 是纯呈现层，不得成为任何非 UI 域的依赖。

| 域 | 角色 | 允许出向 | 禁止 |
|---|---|---|---|
| `lib/ui/` | 呈现与交互（revision 驱动闸门 + 手势） | action（组装命令交 CommandHandler）、queries 读、models、theme、util | **repo 写方法、Db.instance、内联 JSON、Platform.isX、平台通道直连**（R1/R2/R3/R5） |
| `lib/action/` | 命令协议与唯一写入口（CommandHandler + propose 机械校验 + 越权门） | data、models、util | **import flutter UI**（R4） |
| `lib/mcp/` | 协议与服务端（JSON-RPC / 工具面 / 鉴权） | action（AI 写统一走 CommandHandler）、util | **import flutter UI**（R4）；直写 data 写方法 |
| `lib/data/` | 台账库与 Repository（FIFO 写锁 / CAS / 原子替换） | models、util | import action / mcp / ui |
| `lib/service/` | 平台与后台（管家 / 天气 / MCP 总控） | data、util | import ui |
| `lib/models/` | 实体（fromMap/toMap/copyWith） | —（纯数据） | import 任何其他域 |
| `lib/util/` | 纯函数（作息日几何） | — | import 其他域 |
| `lib/theme/` | 视觉令牌（ui-design §1–§3 落地件） | — | import ui 组件 |

> 平台差异（MethodChannel / 插件能力）一律收敛到 `lib/service/` 接口实现或工厂，UI 只持接口。

## 2. 落点决策表（新代码放哪）

| 新技术形态 / 需求 | 落点域 | 依赖边界 |
|---|---|---|
| 新写命令（AI 或人触发） | `lib/action/commands.dart`（sealed 命令 + fromJson）+ `command_handler.dart`（执行 + propose 校验） | 经 R1 唯一写入口；新增设置键先注册 `lib/data/settings.dart`（SettingsKeys） |
| 新读查询 / 聚合 | `lib/action/queries.dart` | 只读，不落写路径 |
| 新 MCP 工具 | `lib/mcp/tools.dart`（schema 注册 + callTool 分发） | data 透传走 CommandHandler，禁绕过 |
| 新后台服务 / 平台能力 | `lib/service/`（接口 + 实现） | UI 经接口消费，不直连平台通道 |
| 新页面 / 组件 | `lib/ui/`（逐一登记 index.md 代码落点表） | 规范 = ui-design（视觉/词汇）+ ui-pages（页面编排）；tokens 取用纪律 = shiguang-ui skill |
| 新实体 / 表 | `lib/models/` + `lib/data/db.dart`（schema 迁移分段幂等） | 手编契约收口 copyWith/patch 白名单 |

## 3. 红线规则正文（机械闸 = arch-guard R1–R6）

| 规则 | 正文 | 违例处置 |
|---|---|---|
| **R1** UI 禁直连 repo 写 | `lib/ui/` 禁 `repo.(addPlan|patchPlan|deletePlan|addBlock|patchBlock|deleteBlock|replaceFixedSlots|settingsSet|settingsClear)`——写路径必须组装 ScheduleCommand 交 `CommandHandler.execute`（对称性：AI 与人走同一命令面，propose 校验才不漏） | 把写意图改为命令组装；确属纯 UI 临时态则不落库 |
| **R2** UI 禁内联 JSON | `lib/ui/` 禁 `jsonDecode/jsonEncode`——编解码委托数据/命令层（快照 JSON 唯一口径在 commands.dart）；导出预览口径豁免（白名单 `settings_page.dart`） | 序列化下沉 models/action；豁免属历史例外，只减不增 |
| **R3** UI 禁平台分支 | `lib/ui/` 禁 `Platform.isX`——平台差异收敛到接口实现/工厂 | 抽接口 + service 装配注入 |
| **R4** mcp/action 禁 import UI | `lib/mcp/` `lib/action/` 禁 `import 'package:flutter/(material|widgets).dart'`——无头系统硬约束（foundation/services 属非 UI 基建不在此列） | 把 UI 语义剥离：命令层只收结构化数据，呈现交 ui |
| **R5** UI 禁直连 Db | `lib/ui/` 禁 `Db.instance(`——数据访问一律走 Repository | 读走 queries / repo 读方法，写走 R1 |
| **R6** 禁弃用 textScaleFactor | `lib/` 禁 `textScaleFactor:`——M3 尺度走 textTheme + textScaler | 改 textScaler 并映射 M3 textTheme |

**白名单纪律**：脚本内白名单条目是历史例外，**只许减少不许增加**；红灯修结构，不申请加白名单。规则口径变更 = 先改本文，再同步脚本（脚本镜像规则，不立法）。

## 4. 测试规约

- **一测一文件**，命名对齐被测件；数据/命令/MCP 契约测试用 ffi 内存库。
- **UI 流程测试（testWidgets）FakeAsync 铁律**：真实异步全包 `tester.runAsync`、pumpAndSettle 换固定次数 pump、副作用断言轮询 DB、一测一文件隔离；跨多跳 I/O 用 pumpFlush 多轮冲刷——坑数据与演进查 `context/lessons.md` [UI测试] 节，本文不复制。
- **门槛口径**：analyze 0 issue + test 全绿 + arch-guard 6/6 为交付线；流程回归优先挂 `test/ui_*`（撤修复即红的钉子）。

## 5. 读码纪律（省 token 与正确性兼得）

文档比代码省 token，但文档**有损且可能失真**（实证：mcp-orchestration 阶段一 memory.md 声称「toolsList 已实现」与代码不符，照文档下手会建在错误前提上）。三段式读法：

| 阶段 | 动作 | 成本量级 |
|---|---|---|
| ① 文档建图 | 先读 index.md 落点 + memory.md 决策 + devlog 变更记录，拿「为什么 / 边界 / 落点」 | 几十~几百 token |
| ② 定向读码 | LSP / semantic search 只读相关符号与区域（readSymbol / 行号范围），**禁整文件通读** | 几百 token/次 |
| ③ 关键断言实证 | 文档每个「已实现」结论动手前抽查一处代码确认；行号引用须实测（会漂移，实证：`settings_page.dart:19 → :35`） | 一次性 |

- 「文档 + 定向读码」比「通读代码」省 token；「只读文档」省的是阅读成本、付的是正确性风险（返工远超省下的 token）
- 本纪律即 devlog「背景核查（关键）」惯例的形式化——评估设计动笔前先实证，不当文档复读机

## 6. 维护触发器（发现即维护，不当旁观者）

**判据三问**：① 是架构分流/准入规则？② 变化频率月/季度级以上？③ 缺了它护栏或 Review 失去判定依据？——三问命中才维护；日常需求流水账禁写。

| 信号 | 本文动作 | 同轮路由 |
|---|---|---|
| 新技术形态 / 新落点疑问 | §2 落点决策表追加一行 | 落点登记 = index.md（shiguang-index skill） |
| 新增 `lib/` 一级域 | §1 矩阵登记角色 + 允许出向 | arch-guard 规则同步（改代码时执行） |
| 新红线 / 规则口径变更 | §3 正文先改，再同步 arch-guard 脚本 | workflow/ui skill 红线摘要刷新 |
| 测试组织约定演进 | §4 更新 | 坑数据 → lessons.md（shiguang-workflow 协议） |

维护收尾：刷新 frontmatter `updated`；规则正文在文档、代码只镜像不立法。
