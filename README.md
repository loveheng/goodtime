# 拾光（shiguang）

本地持有的日程台账 + MCP 服务 + 确认 UI 的 Flutter Android app。

**一句话定位**：拾光管「还没定但想做的事」，系统日历管「已经定了的事」。日程生成的推理全部交给桌面 AI 客户端经 MCP 完成，**零端侧 LLM**——app 是确定性管家与台账，AI 是排程师，人是终审。

> 粒度定律（第一原则）：用户输入的是概念，日程装的是行动。两者之间隔着一整条细化管道（拆解 + 澄清），这条管道就是这个 app 的核心工作面。

完整设计见 [`docs/design/schedule-app.md`](docs/design/schedule-app.md)。

## 核心特性

- **三表台账**：`plans`（愿望清单，唯一的「家」）/ `fixed_slots`（固定占用，约束而非愿望）/ `schedule_blocks`（日程块实例）。本体（plan）与肉身（block）解耦，承认「一件事 ≠ 一个日程块」。
- **MCP 服务**：手机 app 作为 MCP 服务端，桌面 AI 客户端（如 ZCode）连接后读写台账、排程、复盘。十工具面：`list_plans` / `add_plan` / `update_plan` / `get_settings` / `update_settings` / `update_fixed_slots` / `propose_schedule` / `adjust_blocks` / `get_schedule` / `get_history`。
- **确定性中央管家（Housekeeper）**：无 LLM，只做时间驱动的状态迁移（日切、顺延记账、晨间聚合、断流静默保护）。
- **减震器哲学**：填充率红线、呼吸律缓冲、黄金火种、水流模型、中性语言复盘——AI 是用户的减震器，不是工头。
- **应用内自更新**：SHA-256 校验 + 系统安装器 + 更新源持久化（`lib/update/`）。
- **前台保活**：`flutter_foreground_task` 保证 Doze 下 MCP 应答可达。
- **网络配对**：mDNS 自动发现（`shiguang.local`）+ 手动 IP 兜底。

## 技术栈

| 维度 | 选型 |
|---|---|
| 框架 | Flutter 3.x (Dart `^3.13.4`)，Android 平台 |
| 存储 | `sqflite` + `path`（三表台账，分段迁移） |
| 自更新 | `crypto` / `open_filex` / `package_info_plus` / `path_provider` / `shared_preferences` |
| 保活 | `flutter_foreground_task` |
| 配对 | `bonsoir`（mDNS）/ `url_launcher` |
| 测试 | `flutter_test` + `sqflite_common_ffi`（VM 单测 sqlite 夹具） |

## 项目结构

```
lib/
  action/    命令层：CommandActor（载荷防伪/乐观锁/FIFO 锁/机器可读拒绝码）
  data/      db.dart（分段迁移）+ repository.dart（Repository 模式）
  mcp/       JSON-RPC 框架 + MCP server + 十工具实现 + prompts
  models/    数据模型（Plan / ScheduleBlock / FixedSlot ...）
  service/   前台任务保活 / MCP 控制器 / 管家调度
  ui/        双/三 Tab（日程/清单/设置）、时间轴、块浮层、确认卡
  update/    应用内自更新与安装器
  util/      局域网 IP 探测等
  widgets/   通用组件（快记抽屉、凭证卡、背景区 ...）
  main.dart  入口
mcp-bridge/  stdio-bridge.mjs（桌面桥接）+ e2e-check.mjs
docs/        设计文档与域表（详见 docs/）
test/        单测（mcp_server / mcp_tools / repository / action_handler 四类原型）
updates/     shiguang-update.json（自更新源描述）
```

## 快速开始

> ⚠️ `flutter` 不在默认 PATH，开发前需：
> ```bash
> export PATH="$HOME/flutter/bin:$PATH"
> ```

```bash
# 安装依赖
flutter pub get

# 运行（Android 设备 / 模拟器）
flutter run

# 跑单测（含 MCP / 命令层 / 仓库层）
flutter test
```

### 基础命令速查

```bash
flutter pub get            # 拉依赖
flutter analyze            # 静态分析
flutter test               # 全量单测
flutter build apk          # 构建安装包
```

## 开发与协作约定

本项目有 AI 协作约定（见 `AGENTS.md`）与项目专属 skill（`shiguang-workflow` / `shiguang-index` / `shiguang-docs`）。

- **设计变更**回 [`docs/design/schedule-app.md`](docs/design/schedule-app.md) §11 拍板台账留痕。
- **界面文案**先入 `ui-spec §0.4` 词汇表再上屏（零黑话：火种→今日核心、融化→暂缓并收回清单、熔断→遇到突发情况 ...）。
- **docs 改动**跑 `toolbox run docs-lint`（无 toolbox 可跳过）。
- 用户侧偏好写在明处（`settings.user_rules`），AI 经 `update_settings` 维护，拒绝黑盒猜测。

## 数据模型速览

| 表 | 角色 | 关键字段 |
|---|---|---|
| `plans` | 本体（Soul） | title / spec / notes / open_items / energy_level / tool_required / reward_spec / importance / deadline / parent_id / version |
| `fixed_slots` | 约束 | 星期集合 + 起止 + 名称（工作日/周末双模板） |
| `schedule_blocks` | 肉身（Avatar） | date + start/end / plan_id / label / source / pinned / status / execution_quality / is_day_spark / is_celebration / version |

派生状态（能算出来的绝不存）：当下要做的 / 待安排 / 紧急 / 明确态 / 悬空项目 / 清单三分区。

## 克制负清单（永久不做）

连胜打卡/勋章系统 · 无限层级项目树（深度 ≤3）· 手环/传感器生理同步 · 社交分享/排行榜。守护「轻量台账 + 减震盟友」的核心灵魂。

## 设计文档导航

- `docs/design/schedule-app.md` —— 完整设计（定位、数据模型、MCP 工具面、行为层、管家、UI、边界拍板）
- `docs/` 其余域表与项目数据
- `context/` —— epic / 记忆骨架 / 会话断点
