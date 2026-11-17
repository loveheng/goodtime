---
status: active
updated: 2026-11-15
---

# 行为清单 · action_pass_bar（临场通关条，P4 浮层族余件施工输入，已过目转正 2026-11-15 D25——生成通道可作施工输入）

> §9.1.6 模板产出（2026-11-15 抽取）。抽取源 = `lib/ui/action_pass_bar.dart`（86 行全文）+ `lib/action/fact_rules.dart`（ActionPass/computeActionPasses/FactRules.role 表/passLead=120/passDismiss=20）+ `docs/design/ui-pages.md` §9.4（通关条行）/fact 草案 §9.1/§9.4（分期二）+ schedule_page 479 插桩语境。**范围 = computeActionPasses 纯函数 + ActionPassBar 形态 + dailyActionPasses 数据源 + role 白话标签表**；点按目标=通关 sheet（fact_sheet.md §C）。

## 七字段

| 字段 | 内容 |
|---|---|
| 交互意图 | 临场通关条（fact 草案 §9.1/§9.4）：行动类凭证的时刻即将到来时今日页主动顶出——插在系统 Banner 下、遗留区上，**不挤掉双轨仪表盘**；多条并存按行动时刻最早者展开、其余折叠「+N」胶囊；点按开通关卡全量 |
| 触发与阈值 | 窗口=[T_trigger, T_dismiss] 含本地现在（**闭区间**：!before(trigger) && !after(dismiss)）；T_target=T_action−(advance_arrival_minutes ?? 0)（全角色统一，2026-10-09 去 2×2 特判）；T_trigger=T_target−**120min**（ArtifactRules.passLeadMinutes）；T_dismiss=T_action+**20min**（passDismissAfterMinutes）；**本地物理时钟判定禁 toUtc**（旅行跨时区错位） |
| 状态空间 | 零条=整条消失（SizedBox.shrink）；单条=无 +N 胶囊；多条=首条展开+「+N」胶囊（N=总数−1）——D 档 2 态（S1 单条展示态/S2 多条 +N 态） |
| 落库命令与拒绝码 | 零命令（纯派生展示件，读面无写） |
| 反馈形式 | 点按→showVoucherSheet(通关卡全量 fact_sheet.md §C)；「建议 {hh:mm} 前到站」副行提示（advance>0 时） |
| 黄金向量指针 | 零新向量（computeActionPasses 为纯函数——JVM 对拍测试承载，非 golden 集；语义=既有 fact_rules_test.dart 固化行为+窗口公式） |
| 像素基线指针 | D 档 action_pass_bar 2 态——**manifest 现无本组，84 组收口轮补采** |

## §A 纯函数（computeActionPasses，Kotlin 落点 :core:action FactRules 扩件）

| 行为 | 口径 |
|---|---|
| 扫描面 | 仅 state==structured 凭证；payload.time_anchors 逐条；**仅 kind=='moment' 锚**；role 归一后 ∈ actionableRoles{departure, deadline, checkin, checkout}（行动类 4 个参与触发；信息类 arrival/reference 忽略） |
| role 归一 | normalizeRole：null/未知 → 'reference'（不触发）+ [DEGRADE] 日志（存量中文 role 读侧容错不改写 payload §9.1） |
| tAction | _abs(date, min)=本地日 + 分钟；任一缺 → 跳过该锚 |
| 窗口合成 | advance=constraints.advance_arrival_minutes（int 才取）；T_target=tAction−advance；T_trigger=T_target−120；T_dismiss=tAction+20；nowLocal ∈ [T_trigger, T_dismiss] → 收录 |
| 排序 | actionTime 升序（最早行动在前——primary=first） |
| Kotlin 落点 | :core:action FactRules 扩 ActionPass 数据类+computeActionPasses+normalizeRole/actionableRoles；ArtifactRules 补 **passLeadMinutes=120/passDismissAfterMinutes=20** 常量（FixedSlot.kt 注记「随 P3 临场条轮补全」=本轮）；JVM 对拍测试（窗口边界：T_trigger 恰含/T_dismiss 恰含/advance 归零/未知 role 不触发/非 moment 跳过） |

## §B role 白话标签（FactRules.roleLabel，UI 出白话不出枚举 §9.1）

| role | 标签 |
|---|---|
| departure | 发车/起飞 |
| deadline | 停止检票 |
| checkin | 开放入住 |
| checkout | 退房 |
| arrival | 到达 |
| 其余（reference/未知） | 参考 |

## §C 形态（ActionPassBar）

| 行为 | 口径 |
|---|---|
| 容器 | margin (12,6) padding (14,10)：voucherBg 底 radiusCard+**primary 35% 透明度 1dp 边** |
| 主行 | confirmation_number_outlined 18 + 8 + Expanded 列：「{hh:mm(actionTime)} {roleLabel(role)} · {title}」bodyMedium maxLines 1 ellipsis；副行（advance>0）「建议 {hh:mm(actionTime−advance)} 前到站」11sp textSecondary |
| +N 胶囊 | extra>0：margin left 8 padding h8 v2 primaryContainer radiusCapsule「+{extra}」11sp onPrimaryContainer |
| 点按 | onTap(primary) → 通关 sheet（全量 FactDetailSheet——比条面多出时空网格/约束/动作/原文全层） |
| 数据源 | dailyActionPasses(repo)：listPlans→ids→artifactsForPlans(ids)（非 voided）→ computeActionPasses(facts, **DateTime.now()**)；插桩=schedule_page 系统横幅之下、遗留区之上（Kotlin SchedulePage 装配段同位接装） |

## §D 架构接线

- computeActionPasses 纯函数 → :core:action FactRules（JVM 测试直测）；ActionPassBar → :app ui/schedule/ 装配段（今日页 SchedulePage 插桩——N2 结构已备位，本轮接装）。
- 点按 → showVoucherSheet（fact_sheet.md 施工件；UNCERTAIN 消标随 fact_sheet 轮——**本轮同批施工即消**）。
- 数据源经 SchedulePage 装配（VM 装配段：collect revision 重载含 dailyActionPasses——或页侧派生，随施工轮定，两形均不破 Konsist）。
- Kotlin 现状缺口：FactRules（:core:action）现仅 ruleScopeActive/factRuleViolations——normalizeRole/roleLabel/actionableRoles/ActionPass/computeActionPasses 五件为本轮补齐面。
