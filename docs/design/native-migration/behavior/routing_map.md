---
status: active
updated: 2026-11-16
---

# 行为清单 · 类型安全路由契约（P4①-d，已过目转正 2026-11-15）

> §9.1.6 模板产出（2026-11-15 抽取）。契约骨架 = native-migration §9「路由契约」行逐字 + ui-pages §1 IA + `lib/ui/` 20 主页 + `collab/` 10 子页清单对位（文件级对位与触发点取证，未读实现体逻辑）。

## 契约（设计稿既定，本清单零新增）

- `navigation-compose` + `kotlinx.serialization`；目的地一律 `@Serializable` sealed class/interface，**严禁手写字符串路由**
- 浮层族不进 NavHost（sheet/dialog 承载，返回键逐层消费 = 丢失点 #8）
- 返回结果走 `SavedStateHandle` 回传，不引入全局事件总线
- 当前无 DeepLink 与外部唤醒导航需求——仅登记契约，不预建 deep link 基建
- 丢失点 #9：滚动偏移/当前 Tab/浮层展开态 `rememberSaveable`；核心编辑暂存态收敛 `SavedStateHandle`

## 目的地枚举（候选 21 条，过目转正）

| 组 | 目的地（@Serializable 命名建议） | 对位文件 | IA 出处 |
|---|---|---|---|
| 启动 | `Onboarding` | onboarding_page.dart | ui-pages §2 首启引导 |
| 三 Tab | `Today` / `Plans` / `Settings` | schedule_page / plans_page / settings_page | §1 底部三 Tab |
| 详情族 | `PlanDetail(planId)`（N7 参数化，data class）/ `PlanBackground(planId)` / `PlanFacts(planId)`（D24 轮参数化 2026-11-15——子页按 planId 聚合装配） | plan_detail / plan_background / plan_facts | §1 LIST→DETAIL；§9.4/§9.5 |
| 设置族 | `McpDebug` / `ExceptionsSettings` / `RhythmSettings` / `PrefsSettings`（N8a 补，候选 21→22）/ `Update` | mcp_settings / exceptions_settings / rhythm_settings / prefs_settings / update | §1 SET→（MCP 隐藏入口/例外日/一周节奏/排程偏好） |
| 账号 | `Account` | account_page | 协同族入口（ui-pages §9.1 协同日程 UI） |
| 协作 9 | `CollabAuditTimeline(bundleId)` / `CollabCanvas(bundleId, windowStartMs, windowEndMs)` / `CollabComposeBundle` / `CollabDecisionMatrix(bundleId)` / `CollabFriends` / `CollabMemberCalendar` / `CollabMyQr` / `CollabSafetyNumber(friendUserId)` / `CollabScan` | collab/ 九页 | ui-pages §8.3 B 档 9 页（**T4.9 施工注记 2026-11-16，D27 裁决执行**：data object→data class 参数化四条——DecisionMatrix/AuditTimeline/Canvas 按 bundleId（Canvas 另带窗口双参）、SafetyNumber 按 friend.userId；VM/页按 key=bundleId 隔离实例；Canvas 窗口参=清单 §A 调用方注入既定） |

**非路由件**（浮层/壳，不进 NavHost）：AppGate（启动 revision 闸门壳）/ FactSheet（块浮层·启动抽屉，A 档）/ BlockSheet（Block 弹层）/ GlobalActionSheet（全局动作·降级）/ RejectPanel / ActionPassBar / QuickNoteFab（快记条+抽屉）/ 分级确认卡（Column 内联件）/ 逆向记账·新建事项两 sheet（空槽点按，IA §1）。周视图/月历 = 今日页顶部 segment 内部视图态，**非路由**（IA 已明）。

## 对账发现（2 条——已照建议执行：todos 浮层族行修订归一）

| # | 发现 | 建议 |
|---|---|---|
| F1 | 浮层族枚举三源不一致：设计稿路由契约行五件（BlockSheet/GlobalActionSheet/QuickNoteFab/RejectPanel/ActionPassBar）vs todos「浮层族六件」（多 fact_sheet·decision_matrix）vs IA sheet 集合（多逆向记账/新建事项） | 归一口径 = 上节「非路由件」全集；todos 浮层族行按此修订 |
| F2 | `decision_matrix` 归属：todos 列入浮层族，实取证**仅页面形态**（`collab/decision_matrix_page.dart`，member_calendar 导航触发；页内 dialog 属组件级交互） | 归路由页面（ui-pages §8.3 B 档 9 页既定）；todos 行修订 |

## Open points（4 条——已裁决 2026-11-15 用户「同意默认」：OP1+OP4=三顶层 destination（Today/Plans/Settings，AppShell 仅 bottomBar+FAB 宿主）；OP2=21 条按 IA 实际（todo「约12–15」实数回写）；OP3=plan 三独立路由）

| # | 问题 | 默认建议 |
|---|---|---|
| OP1 | 三 Tab 组织：三 destination + bottomBar（Tab 切换 popUpTo + saveState/restoreState 不入栈）vs 单 AppShell destination 内部 Tab 态 | **三 destination**（返回栈语义清晰、Tab 态恢复走 saveState/restoreState；快记 FAB 两 Tab 常驻=bottomBar 宿主条件渲染，设置页无 FAB） |
| OP2 | 目的地总数：候选 21 vs todo「真实路由约 12–15 条」估算口径 | **以 IA 实际为准 21 条**（30 单元 1:1 对位，施工按路由对号；todo「约」以过目后实数回写） |
| OP3 | plan 三件形状：三独立路由 vs PlanDetail 内部 Tab/子级 | **三独立路由**（C 档 3 单元 1:1；背景区/事实页从详情页进入的层级关系随主干页轮细化） |
| OP4 | 三 Tab 命名粒度：`Today/Plans/Settings` 顶层 vs `AppShell` 单壳内嵌 | 同 OP1，**三顶层 destination**；AppShell 仅作 bottomBar+FAB 宿主组件名（与 app_shell.dart 对位） |

## 七字段核（§9.1.6）

| 字段 | 内容 |
|---|---|
| 交互意图 | 页面级导航骨架：路由参数序列化类型安全、返回结果显式回传、浮层不混入路由栈 |
| 触发与阈值 | 导航触发=底部 Tab 切换/列表点按/页内入口（逐页交互阈值归各页行为清单）；本件无手势数值 |
| 状态空间 | 不适用（路由无视觉态）；Tab 选中态/滚动偏移走 rememberSaveable（丢失点 #9，随消费页轮断言） |
| 落库命令与拒绝码 | 不适用 |
| 反馈形式 | 不适用 |
| 黄金向量指针 | 不适用 |
| 像素基线指针 | 不适用（页面像素基线随各页轮引用 ui-pages §8 编号） |
