---
status: active
updated: 2026-11-15
---

# 行为清单 · plans_page（清单页，N7 施工输入，已过目转正——生成通道可作施工输入）

> §9.1.6 模板产出（2026-11-15 N7 前置抽取）。抽取源 = `lib/ui/plans_page.dart`（579 行全文）+ `docs/design/ui-pages.md` §5（清单页定稿）/§8（基线组）/§9.3（长按组文案）+ `schedule-app.md` §8（冷藏池）+ `lib/action/fact_rules.dart`（未归属凭证入口指针）。**范围 = 清单页本体（三分区/四象限/冷藏池/搜索/左滑归档撤销/长按 sheet/删除确认/空态记一笔/未归属凭证入口）+ PlanDetail 路由接线（页面本体随 PlanDetail 轮另抽施工）**。命令面 update_plan/delete_plan 已平移（dispatch 收口）。

## 七字段

| 字段 | 内容 |
|---|---|
| 交互意图 | 清单=愿望与承诺池：三分区单页滚动（今天=今日有块的 plan / 当下推进中=有未来块 / 待安排=四象限分组）+ 冷藏池折叠（收起的旧想法）；执行裁决回今日页，本页只管「有什么、搁在哪」；卡片点按进计划详情页（全页路由，编辑 sheet 已退役） |
| 触发与阈值 | 左滑 35% 卡点归档（swapFraction 卡点族，DismissDirection.endToStart——右滑挂账待「排期」提名流）；长按=动作 sheet；搜索=标题子串大小写不敏感过滤 |
| 状态空间 | 搜索查询态（空串=不过滤）/冷藏池展开态（默认收起）/空池态（四分区全空，**冷藏池有货不算空池**）/无命中态（搜索非空且全分区空） |
| 落库命令与拒绝码 | `update_plan`（archived true/false，CAS expectedVersion——归档/恢复/撤销共用）/ `delete_plan`；拒绝=version_conflict/not_found（删除不存在 plan 时命令层抛） |
| 反馈形式 | 归档 SnackBar 浮动+FAB 净空「已归档「title」」+撤销钮（archived:false，expectedVersion=快照 fresh version）；删除确认弹框（取消/删除 FilledButton）；失败 ActionException message SnackBar 3s；归档触发触觉 selectionClick |
| 黄金向量指针 | update_plan/delete_plan 命令面 Kotlin 已平移（GoldenCommandParseTest）；本页零新命令 |
| 像素基线指针 | plans_page 组（ui-pages §8 manifest）；卡片/分区形态随 C 档基线轮采图 |

## §A 数据装配（_load 镜像）

| 行为 | 口径 |
|---|---|
| 作息日 | getSettings wake（缺省 0）→ scheduleDayOf(now, wake)=今日；无 initialized 前置校验（清单页宽容） |
| 单查询族 | listPlans(includeArchived=true)（冷藏池需要）/ artifactsUnattributed（§1.7 派生计数，**Kotlin 端口缺——N7 补**：`plan_id IS NULL AND bundle_id IS NULL AND state != 'voided'` 按 created_at DESC）/ blocksOnDate(今日) / blocksInRange(今日, +30 天)（未来块窗口 30 天字面量） |
| 分区分流 | planOfBlock（block planId→date，今日块恒 todayIso、未来块 putIfAbsent 首日）；proposedByPlan（status==proposed 计数，今日+未来合并）；sparkPlanIds=今日 isDaySpark 块的 planId；archived→冷藏池；blockDate==todayIso→今天；blockDate!=null→当下推进中；否则→待安排 |

## §B 页面骨架

| 行为 | 口径 |
|---|---|
| 搜索框 | 页顶常驻（padding 16/8/16/4）：prefixIcon search 20 / hint「搜计划标题…」/ 非空时 suffix 清空钮（close 18 tooltip 清空）；isDense OutlineInputBorder；onChanged trim 即滤（只影响各分区**可见条目**，归属判定仍按块日期；含冷藏池同滤） |
| 未归属凭证入口 | N>0 显现（bottom 8）：voucherBg 底 radiusCard 圆角条（folder_shared_outlined 18 + 「N 条未归属凭证」bodyMedium w600 + chevron_right 18 textSecondary，horizontal 12 vertical 10）→ 未归属凭证管理面板（fact_sheet.dart 同源挂载 sheet——**浮层族轮挂账**，N7 只上屏入口） |
| 空池态 | 四分区全空（冷藏池有货不算空）→ 居中「池子空空的——想到什么就记下来，30 秒的事」bodySmall textSecondary + FilledButton.tonal「记一笔」→ 快记抽屉（quick_note_fab 统一通道，2026-10-08 拍板）；**搜索非空时隐藏此出口**（有结果就不该再劝记新条目） |
| 无命中态 | 搜索框 + 居中「没有匹配「q」的计划」textSecondary |
| 滚动容器 | 单页 SingleChildScrollView padding (16,0,16,fabClearanceDp) |

## §C 分区渲染

| 行为 | 口径 |
|---|---|
| 三分区标题 | 「今天」「当下推进中」「待安排」titleSmall（top 12 bottom 4），空分区不渲染标题 |
| 四象限分组 | urgentDays=3 字面量：urgent=deadline 合法且 < now+4 天；组序=Q1「重要 × 紧急」（importance&&urgent）/Q2「重要」（importance）/Q3「紧急」（urgent）/Q4「愿望池」（其余）；组标题 labelMedium textSecondary「text · count」，**空组隐藏**；卡 proposed=0 spark=false |
| 冷藏池 | Card（top 12）：头行 InkWell（expand_less/more 18 textSecondary + 「收起的旧想法 · N 条」labelMedium textSecondary）默认收起；展开=逐条 dense ListTile（title bodyMedium）+ trailing TextButton「恢复」→ update_plan{archived:false} CAS；卡片双形态（text · count 组计数） |

## §D PlanCard

| 行为 | 口径 |
|---|---|
| 形态 | Card（bottom 8）+ InkWell：左象限色点 10dp 圆（重要+deadline=primary / 重要=primary 50% / 仅 deadline=sparkStroke 琥珀 / 无=safelineOff）+ title bodyLarge（Expanded）+ 🔥 12sp（spark）+ 待确认胶囊（primaryContainer radiusCapsule「待确认 N 项」11sp onPrimaryContainer，left 8）+ deadline 11sp textSecondary（left 8） |
| 左滑归档 | Dismissible endToStart 35% 卡点；背景=fixedSlotBg radiusCard 圆角 + archive_outlined + 「归档」（textSecondary，**绝不显红——减震器语义**）；onDismissed→归档+撤销 |
| 归档撤销链 | selectionClick 触觉 → update_plan{archived:true} CAS → SnackBar 浮动+FAB 净空「已归档「title」」+ 撤销钮（update_plan{archived:false, expectedVersion=快照 version}）——归档在 UI 上暂不可逆必附撤销；messenger 须命令执行前捕获（卡片随清单重建卸载） |
| 长按动作 sheet | ModalBottomSheet showDragHandle：归档（inventory_2_outlined）→ update_plan CAS 收 sheet / 删除（delete_outline）→ **先 pop sheet 再确认弹框** / 取消（close）→ pop |
| 删除确认 | AlertDialog「删除这条计划？」content「「title」将从清单移除，无法恢复。」取消 TextButton/删除 FilledButton → delete_plan → 失败 ActionException SnackBar |
| 点按 | → PlanDetail 路由（routing_map `PlanDetail(planId)` 三独立路由已注册——**页面本体随 PlanDetail 轮接装**，N7 先挂导航） |

## §E 架构接线（D15 同形）

- PlansViewModel（轻 VM 同 TodayViewModel 模式）：StateFlow&lt;PlansUiState&gt;（view+query+searching）+ collect repo.revision 重载 + 命令方法（archive/restore/deletePlan）+ 事件面（归档撤销浮动 SnackBar/失败 3s）+ Factory 注入；壳层 Plans destination 接装。
- Kotlin 端口扩展：BridgeRepositoryPort.artifactsUnattributed()（Expand——NativeSqlRepository 真实现 + DartBridgeRepository call 同步，D16 先例）。
- 挂账：PlanDetail/PlanBackground/PlanFacts 页本体（随 PlanDetail 轮抽取施工）/未归属凭证管理面板（fact_sheet 浮层族轮）/右滑排期提名流（Dart 注释挂账沿用）。
