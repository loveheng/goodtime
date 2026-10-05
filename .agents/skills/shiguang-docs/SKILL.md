---
name: shiguang-docs
description: shiguang 的 docs/ 项目数据：域目录表、lint/收集脚本路径、本仓例外；通用规范机制见全局 docs-spec §1–§7，本文件严禁复制规范正文。
---

# shiguang · docs/ 项目数据

> 规范机制（frontmatter 时效 / 落点与切片命名 / Mermaid / Tombstone / 引用移动与索引 / 写后自检）见全局 `docs-spec` §1–§7——**本文件只写项目数据**。

## 域目录表

| 域 | 定位 |
|---|---|
| `design/` | 产品设计域：SSOT（schedule-app.md）、功能施工蓝图（functional-spec.md）、UI 规格（ui-spec.md）——设计三件套，新增设计文档落此域 |
| `playbook/` | AI 行为层内容源（2026-10-05 M3 长出）：clarify/decompose/schedule/govern/dump 五文件，MCP prompts 与分发 SKILL 同源不分叉；上游锚 schedule-app.md §7 |

后续按项目实际长域（如实现期长出 `architecture/`、`deploy/` 再登记），禁止预铺切片。

## lint / 收集脚本

- `toolbox run docs-lint`（全局脚本；本仓无专属 lint 脚本）

## 本仓例外

- 无（与 docs-spec 无冲突）
