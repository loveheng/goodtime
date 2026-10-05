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
- 核心依赖（M1–M4）：sqflite（三表台账）、内嵌 MCP server（自拾贝 `lib/mcp/` 搬运）、flutter_foreground_task（管家前台服务）、bonsoir（mDNS 广播 fail-open，升级挂账 `context/todos.md`）

## 环境硬约束

- **flutter 不在默认 PATH**：先 `export PATH="$HOME/flutter/bin:$PATH"`（flutter 装在 `~/flutter`）
- 本地优先：全功能离线可用；MCP 仅 LAN；无任何遥测
- **Android 交付约束（2026-10-06 拍板）**：minSdk=34（仅支持 Android 14+，勿回退 flutter.minSdkVersion）；根 `android/build.gradle.kts` 对全部 library 子项目钳制 compileSdk≥36——陈旧插件对策（bonsoir_android 5.1.6 硬编码 33、依赖 androidx 需 ≥34）
- 文档纪律：docs/ 改动走 docs-spec（lint 用 `toolbox run docs-lint`）

## 命令（2026-10-05 实测跑通）

| 命令 | 用途 | 实测结果 |
|---|---|---|
| `flutter analyze` | 静态检查 | ✓ No issues（5.5s，2026-10-05 复测） |
| `flutter test` | 单元/组件/流程测试 | ✓ 91/91 passed（数据/命令/MCP/UI 手势契约全量，2026-10-06） |
| `node mcp-bridge/e2e-check.mjs` | 桥接端到端自检（mock 上游） | ✓ E2E PASS（5 项，2026-10-05） |
| `toolbox run arch-guard`（别名 ag） | 架构硬约束护栏（6 条：UI 禁直连 repo 写/禁内联 JSON/禁平台分支/禁直连 Db、mcp+action 禁 import UI、禁弃用 textScaleFactor） | ✓ 6/6 通过（2026-10-06，自检金丝雀 PASS） |
| `toolbox run pre-commit-gate`（别名 pcg） | 提交前门禁：lib/ 改动→arch-guard、docs/ 改动→docs-lint | ✓ 实战通过（2026-10-06）；git 钩子已装自动拦截 |
| `toolbox run build-apk`（别名 apk） | APK 编译（analyze 门槛+ABI 分包+产物校验+`--install` 真机装包） | ✓ 真机 release 分包实测通过（2026-10-06，arm64 19M，aapt+sha256 产物校验过） |
| `toolbox run bump-version`（别名 bump） | pubspec 版本更新+设备防降级守卫（包名 app.shiguang.shiguang） | ✓ --json 读 1.0.0+1 |
| `flutter run` | 真机/模拟器运行 | 未实测（无设备连接时跳过） |

构建（APK）：一律走 `toolbox run build-apk`（analyze 门槛+产物校验+可选 `--install`），勿裸跑 flutter build apk

## 模块结构（现状）

- `lib/data/db.dart` —— 三表台账 schema v2（plans/fixed_slots/schedule_blocks + app_settings KV）+ 分段幂等迁移 + 外键兜底（v2=2026-10-05）
- `lib/data/repository.dart` —— 三表 Repository：FIFO 写锁 / 字段级 patch+乐观锁 CAS / replaceFixedSlots 原子替换 / fixedSlotsForDate 溢出解析 / blocksInRange 范围查询 / settings KV / revision 通知代次
- `lib/data/settings.dart` —— app_settings 键注册表（SettingsKeys，propose 硬前置判据）
- `lib/models/` —— plan / fixed_slot / schedule_block 实体（fromMap/toMap/copyWith）
- `lib/action/commands.dart` —— 命令协议：CommandActor(human\|ai) / ActionErrorCode / sealed 命令 + fromJson / 快照 JSON 唯一口径
- `lib/action/command_handler.dart` —— **唯一写入口**（14 写命令 + propose 机械校验全量（填充率/呼吸律/deep 禁排）+ 越权门；模式照抄拾贝 item_action_handler）
- `lib/action/queries.dart` —— 读入口（get_settings / get_schedule 服务端供作息日 / get_history 基础聚合）
- `lib/mcp/jsonrpc.dart` —— JSON-RPC/MCP 协议常量（拾贝原样）
- `lib/mcp/mcp_server.dart` —— 内嵌 MCP 服务：Streamable HTTP POST /mcp + token 鉴权 + Origin 防护；instructions=政策精华六条；prompts 先空实现
- `lib/mcp/tools.dart` —— 十工具 schema 全量注册（§6 定格）+ callTool 分发（AI 侧统一走 CommandHandler，data 透传）
- `mcp-bridge/` —— stdio-bridge.mjs（桌面 stdio ↔ 手机 HTTP 桥）+ e2e-check.mjs（mock 上游自检）；mDNS 自动发现 M4 新写，手动 IP 兜底
- `lib/service/housekeeper.dart` —— 中央管家：日切扫描（missed/作废/Clean Slate 静默，幂等）+ dailyCutIfNeeded 冷启动；晨间 digest 在 queries
- `lib/service/weather.dart` —— 天气投影（Open-Meteo 无 key + TTL + 静默降级），get_schedule 搭载（例外日/水位线同在 queries）
- `lib/service/mcp_controller.dart` —— MCP 总控 + bonsoir mDNS 广播（fail-open）+ 前台服务
- `lib/util/lan_ip.dart` —— 局域网 IPv4 探测（拾贝原样）
- `lib/service/mcp_controller.dart` —— MCP 总控：token 生成/持久化（app_settings KV）/启停/冷启动恢复；前台服务保活留 M2 管家
- `lib/util/schedule_day.dart` —— 作息日 wake_time 切换 / 跨午夜几何 / ISO 日期 / overlapsMinutes / clockOf
- `lib/theme/tokens.dart` —— ui-spec §0 视觉令牌单一事实源（色彩/尺度/动效/手势常量）
- `lib/main.dart` + `lib/ui/` —— app 入口（M3 固定种子/Light 锁定）+ app_gate（revision 驱动闸门）+ app_shell（双 Tab+快记条常驻）+ onboarding 三步 + quick_note_bar + schedule_page 日视图（1dp/min 时间轴/车道分栏/确认三键/+30m/空槽创建）+ block_sheet + plans_page 三分区 + settings_page（作息/一周节奏/MCP）
- `test/` —— schedule_day / repository / commands / mcp_tools / mcp_server 契约测试 + ui_* 流程测试（一测一文件，ffi 内存库；FakeAsync 铁律见 lessons.md [UI测试]）+ widget_test 冒烟
- 已过测试里程碑：M1 行走骨架 + M2 减震系统 + M3 心理层 + M4 现实接口（functional-spec §1 全清，2026-10-06）。剩余（用户指定最后统一测）：真机四天型走查 + 桌面 AI 全链路 + 金样本评审 + mDNS/日历投影实证

## 跨仓复用源

- 拾贝 goodshare：`/home/zzh/app/goodshare`（仓库外，只读参照）；领料清单 = `docs/design/schedule-app.md` §12（2026-10-05 盘点校准版）
