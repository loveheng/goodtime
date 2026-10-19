---
status: active
updated: 2026-10-19
---

# 拾光 · 功能落点索引（数据）

> 拾光索引数据：代码落点 / 跨域隐式契约（架构债暂无活跃项，清零/新增时按 shiguang-index skill 纪律增设登记）。改前定位查本表；文档明细唯一事实源 = `docs/README.md`，不在本表复制（防双源）。登记纪律与维护协议见 `.agents/skills/shiguang-index/SKILL.md`，表格式机制见全局 `project-index` skill。表随实现增量登记——禁止一次性铺满；**只放「功能 + 落点 + 指针」，施工过程 / 日期叙事 / 即时行数一律不入表**。

## 代码落点

| 功能 | 落点 | 备注 |
|---|---|---|
| 台账库（三表 schema + 分段幂等迁移 + 外键兜底） | `lib/data/db.dart` | schema v3（plans/fixed_slots/schedule_blocks + backgrounds + app_settings KV）；设计 SSOT `docs/design/schedule-app.md` |
| 三表 Repository（FIFO 写锁 / 字段级 patch+CAS / 原子替换 / 范围查询 / revision 通知） | `lib/data/repository.dart` | 乐观锁语义见方法注释；settings KV 经 `lib/data/settings.dart`（SettingsKeys 注册表，propose 硬前置判据） |
| 实体模型（plan/fixed_slot/schedule_block/background） | `lib/models/` | fromMap/toMap/copyWith；background 手编契约收口在 copyWith/patch 白名单 |
| 命令协议（CommandActor / sealed 命令 / 快照 JSON 唯一口径） | `lib/action/commands.dart` | 模式照抄拾贝 item_action_handler |
| 唯一写入口（14 写命令 + propose 机械校验 + 越权门） | `lib/action/command_handler.dart` | **红线**：一切 AI 写操作必经此门，禁绕过直写 repo |
| 读入口（get_settings / get_schedule / get_history） | `lib/action/queries.dart` | 作息日 / 例外日 / 水位线同在此 |
| 内嵌 MCP 服务端（Streamable HTTP + token 鉴权 + Origin 防护） | `lib/mcp/mcp_server.dart` + `lib/mcp/jsonrpc.dart` | HTTP 仅 127.0.0.1 回环（2026-10-15 拍板）；jsonrpc 常量拾贝原样 |
| MCP 工具面（十工具 schema + callTool 分发） | `lib/mcp/tools.dart` | AI 侧统一走 CommandHandler，data 透传 |
| stdio 桥 / relay 桥 / e2e 自检 | `mcp-bridge/`（stdio-bridge.mjs / relay-bridge.mjs / e2e-check.mjs） | stdio↔手机回环 HTTP（adb reverse）；relay 默认关（轴 C） |
| MCP 总控（token 生成持久化 / 启停 / 冷启动恢复 / 回环 HTTP / relay 挂载 / 前台保活） | `lib/service/mcp_controller.dart` | 暴露面收口后 mDNS 退役 |
| 中央管家（日切扫描 missed/作废/Clean Slate，幂等 + 冷启动 dailyCut） | `lib/service/housekeeper.dart` | 晨间 digest 在 queries |
| 天气投影（Open-Meteo 无 key + TTL + 静默降级） | `lib/service/weather.dart` | get_schedule 搭载 |
| 作息日几何（wake_time 切换 / 跨午夜 / overlapsMinutes / clockOf） | `lib/util/schedule_day.dart` | 纯函数域 |
| UI 视觉令牌（色彩/尺度/动效/手势常量单一事实源） | `lib/theme/tokens.dart` | ui-design §1–§3 落地件；取用纪律见 shiguang-ui skill |
| App 入口与壳（app_gate revision 闸门 / 三 Tab+快记条 / onboarding） | `lib/main.dart` + `lib/ui/app_gate.dart` + `lib/ui/app_shell.dart` | Light 锁定；M3 固定种子 |
| 日视图（1dp/min 时间轴 / 车道分栏 / 确认三键 / +30m / 空槽创建） | `lib/ui/schedule_page.dart` | 交互规格 = `docs/design/ui-pages.md` §3；手势契约测试在 `test/ui_*` |
| 快记条 / Block 弹层 | `lib/ui/quick_note_bar.dart` + `lib/ui/block_sheet.dart` | 文案先入 ui-design §4 术语映射 / ui-pages §9 文案清单再上屏 |
| 计划三分区页 | `lib/ui/plans_page.dart` | 背景区（backgrounds 双通道装配）在此呈现 |
| 设置页（作息 / 一周节奏 / 排程偏好 / 关于） | `lib/ui/settings_page.dart` | MCP 子页=隐藏入口：页尾「关于」长按唤调试菜单（2026-10-15 拍板） |

## 跨域隐式契约

- ui-design §4 术语映射 + ui-pages §9 文案清单 ↔ BlockRenderer / 各页字符串（界面文案零黑话，先入表再上屏）。
- SettingsKeys 注册表 ↔ propose 机械校验判据（新增设置键须先注册）。

## 业务术语映射（黑话 → 概念/落点）

用户与设计文档使用的领域黑话，读码/读文档前先对表；机制定义正文 = `docs/design/schedule-app.md`，本表只做检索映射：

- **火种**（黄金火种 / `is_day_spark`）——当日黄金时段判定；作息日几何见 `lib/util/schedule_day.dart`
- **金样本**——评审循环固定输入（#4 云南七天=旅行模式全流程）；`docs/design/golden-samples.md`
- **减震器**——排程阻尼机制（拒绝过满日程），schedule-app.md 行为层
- **水流模型**——日程填充的流动隐喻（任务像水流进空槽），schedule-app.md 数据模型
- **熔断**——连续失败后的保护性停机；管家/propose 判据见 `lib/action/command_handler.dart`
- **Clean Slate**——日切扫描的清零态（missed/作废静默清空）；`lib/service/housekeeper.dart`
- **例外日**——作息日 wake_time 切换/非默认节奏的一天；`lib/util/schedule_day.dart` + queries
- **护航**——管家对在途计划的守护呈现；schedule-app.md §7 行为层
- **深潜净值**——深度工作时段的净值计量口径，schedule-app.md
- **快记条**——常驻底部的大脑倾倒入口；`lib/ui/quick_note_bar.dart`（内容管道 = playbook/dump.md）
- **确认三键**——日视图对 AI 排程提案的人工确认交互；`lib/ui/schedule_page.dart`
- **背景（backgrounds / 软上下文）**——计划附带的环境信息层：注入预算 / 过去的背景 / applicable_dates / 日期角标；契约 = `docs/design/background-context-draft.md`，落点 = db/action/tools/plans_page

## 跨仓复用源

- 拾贝 goodshare：`/home/zzh/app/goodshare`（仓库外，只读参照）；领料清单 = `docs/design/schedule-app.md` §12（2026-10-05 盘点校准版）。
