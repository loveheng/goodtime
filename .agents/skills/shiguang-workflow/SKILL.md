---
name: shiguang-workflow
description: shiguang（Flutter Android 日程台账 + 内嵌 MCP 服务端）的事实源：技术栈版本、环境硬约束、实测构建/测试命令、模块结构。编码会话开工前加载；命令变更或结构演进时按 dev-loop 维护协议更新本文件。
---

# shiguang workflow 事实源

> 取证日期 2026-10-05（flutter create 后实测）。SSOT 设计文档：`docs/design/`（三件套）；施工里程碑：`docs/design/functional-spec.md`。

## 技术栈

- Flutter 3.47.5 stable / Dart 3.13.4
- 目标平台：**仅 Android**（`--platforms android` 创建；拓扑=手机 app 当 MCP 服务端 + 桌面 AI 连接）
- 包名：`app.shiguang.shiguang`
- 核心依赖（M1 引入）：sqflite（三表台账）、内嵌 MCP server（自拾贝 `lib/mcp/` 搬运）、flutter_foreground_task（管家前台服务）

## 环境硬约束

- **flutter 不在默认 PATH**：先 `export PATH="$HOME/flutter/bin:$PATH"`（flutter 装在 `~/flutter`）
- 本地优先：全功能离线可用；MCP 仅 LAN；无任何遥测
- 文档纪律：docs/ 改动走 docs-spec（lint 用 `toolbox run docs-lint`）

## 命令（2026-10-05 实测跑通）

| 命令 | 用途 | 实测结果 |
|---|---|---|
| `flutter analyze` | 静态检查 | ✓ No issues（6.8s） |
| `flutter test` | 单元/组件测试 | ✓ 1/1 passed（模板冒烟） |
| `flutter run` | 真机/模拟器运行 | 未实测（无设备连接时跳过） |

构建（APK，M1 后期按需实测）：`flutter build apk`

## 模块结构（现状）

- `lib/main.dart` —— flutter create 模板（122 行，M1 起逐步替换）
- `lib/theme/tokens.dart` —— ui-spec §0 视觉令牌单一事实源（色彩/尺度/动效/手势常量）
- `test/widget_test.dart` —— 模板冒烟测试
- `mcp-bridge/` —— **待建**（M1）：node stdio-bridge 自拾贝原样搬运 + e2e-check
- M1 域落点（蓝图见 functional-spec §1）：三表 SQLite + 命令层 + MCP 框架层 + 引导/快记/清单/日视图 UI

## 跨仓复用源

- 拾贝 goodshare：`/home/zzh/app/goodshare`（仓库外，只读参照）；领料清单 = `docs/design/schedule-app.md` §12（2026-10-05 盘点校准版）
