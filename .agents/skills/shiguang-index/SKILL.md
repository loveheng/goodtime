---
name: shiguang-index
description: shiguang（Flutter Android 日程台账 + MCP 服务端）的「功能 → 代码落点 + 文档落点」归属索引。修改、新增、排查、评审功能前，先用本表定位落点，避免全仓扫描。
---

# shiguang 功能索引

机制/格式/维护协议见全局 skill `project-index`。

## 用法

1. 按归属表（或关键词映射）定位领域。
2. 用展开命令或以该路径为 include_pattern 限定搜索展开类清单——展开结果只用于当前任务，禁止写回本表。
3. 定位不到时才全量 grep / find_path。

## 归属表

| 领域 | 覆盖功能 | 代码落点（锚点 · 展开命令） | 文档落点 |
|---|---|---|---|
| 产品与架构设计 | 定位/数据模型/MCP 工具面/行为层/管家/UI 结构/拍板台账 | —（纯设计域） | `docs/design/schedule-app.md`（SSOT，§11 拍板台账） |
| 功能蓝图 | M1–M4 里程碑/页面清单/命令清单 | —（施工切片在蓝图） | `docs/design/functional-spec.md` |
| 金样本评审 | 金样本评审循环固定输入（#4 云南七天=旅行模式全流程+实现差异备忘） | —（纯文档域） | `docs/design/golden-samples.md` |
| UI 规格 | 视觉 tokens/导航/页面布局/BlockRenderer 矩阵/文案词汇表 | `lib/theme/tokens.dart`（§0 落地件） | `docs/design/ui-spec.md`（§0.4 白话词汇表） |
| Flutter 工程 | app 本体（M1 起逐步成形） | `lib/`（`find lib -name "*.dart"`） | — |
| MCP 桥 | stdio-bridge / e2e-check（M1 待建） | `mcp-bridge/`（待建） | schedule-app.md §6 |
| 测试 | 单元/组件测试 | `test/`（`find test -name "*.dart"`） | — |
| 跨仓复用源 | 拾贝领料（MCP 框架/命令层模式/前台服务/测试模板） | `/home/zzh/app/goodshare`（仓库外只读） | schedule-app.md §12 复用清单 |

跨域隐式契约：ui-spec §0.4 文案词汇表 ↔ BlockRenderer/各页字符串（界面文案零黑话，先入表再上屏）。

## 业务别名映射

- **产品与架构设计**：火种（黄金火种/is_day_spark）、金样本、减震器、水流模型、熔断、Clean Slate、例外日、护航、深潜净值
- **功能蓝图**：行走骨架、M1–M4、确认三键、快记条

## 维护约定

- 可推导的不维护（路径/命名/脚本现场 derive），不可推导的才平时顺手登记；禁止为收集目的给文件新增元数据字段。
- 新增领域/大功能加一行；禁止写类清单/文件触点清单。
- 有实时扫描脚本时脚本输出优先，表与脚本冲突以脚本为准。
- 联动标记只兑机器查不出的隐式契约；能用测试/护栏守护的优先加护栏。
