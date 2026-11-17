---
status: active
updated: 2026-11-15
---

# 行为清单 · block_sheet（块弹层 M1 点块表单，P4 浮层族余件施工输入，已过目转正 2026-11-15 D25——生成通道可作施工输入）

> §9.1.6 模板产出（2026-11-15 抽取）。抽取源 = `lib/ui/block_sheet.dart`（524 行全文：_BlockSheet/showRejectReasonDialog/_NewBlockSheet）+ `lib/ui/schedule_page.dart` 唤起点（1147 块点按）+ `docs/design/ui-pages.md` §9.3（点块表单文案组）/§8.1（C 档 2 态口径）+ `lib/ui/fact_sheet.dart`（通关卡嵌入消费面）+ 命令面行为清单（confirm/reject/tick/shift/adjust/restore_block 已平移 dispatch 26/26）。**范围 = BlockSheet 本体 + 否决原因弹框 + 规则提醒角标**；通关卡本体随 fact_sheet.md；_NewBlockSheet（含逆向记账）随 new_block_sheets.md。

## 七字段

| 字段 | 内容 |
|---|---|
| 交互意图 | 块弹层=M1 点块表单（ui-pages §9.3；**Landing Gear 抽屉 M4 挂账**，见 §F1）：proposed=确认/否决（原因可选）双键；confirmed=打卡（误触可撤销）/心流专注中 (+30m，AI 块限定)/改时间；done=只读「这块已完成，没有待办了。」；missed/archived=无键（actions 空=整层消失）；头部恒为「马上开始」第一步（headlineSmall=全 app 最大字，行动前 60 秒认知摩擦纪律） |
| 触发与阈值 | 时间轴块点按唤起（schedule_page 1147）；无 StGesture 手势；编辑改时间=本地 toggle（「改时间」↔ 保存表单）；busy 态全键禁用防重复提交 |
| 状态空间 | 按 block.status 分流：proposed（确认 FilledButton+否决 OutlinedButton）/confirmed（打卡 tonal+AI 块「心流专注中 (+30m)」+改时间）/done（只读文案）/missed·archived（键区空）；局部态=改时间编辑表单 + busy 锁 |
| 落库命令与拒绝码 | `confirm_block`/`reject_block`（reason 空=null 不带原因）/`tick_block`（打卡）/`shift_block`（minutes=30）/`adjust_block_time`（startMin/endMin）——均 CAS expectedVersion；撤销=`restore_block`（toStatus=打卡前快照，2026-10-08 拍板）；拒绝=version_conflict/not_found + 时间校验族逐字镜像命令面行为清单 |
| 反馈形式 | 成功：pop sheet + SnackBar 2s（note ??「完成」，**浮动+FAB 净空** margin LTRB(16,0,16,fabClearanceDp)——撤销钮不被快记 FAB 遮挡 ui-design §3.2）；打卡成功 5s 附「撤销」action（restore 成功 note 2s）；失败 ActionException message SnackBar 3s（messenger 须 await 前捕获——pop 后 sheet 卸载） |
| 黄金向量指针 | confirm/reject/tick/shift/adjust/restore_block 命令面已平移（GoldenCommandParseTest，dispatch 26/26）；本页零新向量 |
| 像素基线指针 | C 档 block_sheet 2 态（S1 有键态/S2 无凭证态）——**manifest 现无本组，84 组收口轮补采** |

## §A 数据装配

| 行为 | 口径 |
|---|---|
| 主数据 | planById(block.planId)（null=无关联）；artifactForBlock(block.id)（v5 反转关联，缺省 null=通关卡整层消失） |
| 规则提醒角标 | planId 非空时：子树 walk（≤3 级，plan_facts 同口径）→ artifactsForPlans → factRuleViolations（**硬红线 human 通道**：AI 通道被拒、human 手动放块绝不阻断，仅亮琥珀提醒 §9.3）——命中时 Wrap 胶囊族（sparkStroke 15% 底+warning 图标 12+「规则提醒：{reason 按『：』切尾段}」11sp） |
| 状态标签 | proposed=提案（AI 建议）/confirmed=已确认/done=已完成/missed=未打卡/archived=已归档 |

## §B 骨架与组件

| 行为 | 口径 |
|---|---|
| 头部 | label（block.label ?? plan.title ?? '(未命名)'）titleLarge + 「{状态标签} · {clockOf(start)}–{clockOf(end)}{plan 非空 · 来自清单：{title}}」bodySmall textSecondary + 规则提醒角标 + 「马上开始」（notes trim 非空：labelSmall textSecondary → 首行 headlineSmall → 余行 bodySmall top 8） |
| 通关卡嵌入 | artifactForBlock 非空：FactDetailSheet(fact, block, padding=zero) bottom 12（外层已有边距）；层内一致性琥珀条（fact_sheet.md §A）含「点此核对」回块导航 |
| 确认（proposed） | FilledButton「确认」→ confirm_block |
| 否决（proposed） | OutlinedButton「否决」→ **先弹否决原因弹框**（showRejectReasonDialog：AlertDialog 标题「否决这条提案？」+ TextField autofocus maxLines 2 hint「为什么不想做？（可选，AI 下轮排程会参考）」+ 取消/否决 FilledButton；返回 null=取消不动 / ''=无原因 / 非空=带原因 AI 下轮参考）→ reject_block |
| 打卡（confirmed） | FilledTonalButton「打卡」→ tick_block + canUndo（restore_block toStatus=prevStatus；undo 成功 note 2s） |
| 心流专注中 | AI 块（source=ai）confirmed 限定：OutlinedButton「心流专注中 (+30m)」→ shift_block(minutes=30) |
| 改时间 | OutlinedButton「改时间」→ 编辑表单（从几点/到几点双 ListTile+TimePicker，subtitle clockOf 20sp）+ FilledButton「保存」→ adjust_block_time |
| done 只读 | Padding v8「这块已完成，没有待办了。」bodySmall |

## §C 架构接线

- Kotlin 承载=ShiguangBottomSheet（浮层族不进 NavHost，routing_map 非路由件）：按 block.status 分流的 Composable + 局部 VM 或页内 state（命令经 TodayViewModel 同族 CommandHandler+DbWriteQueue——沿用 schedule_page 命令接线）。
- showRejectReasonDialog=AlertDialog 独立即用件（N5 否决弹框已有同形——核对是否同源复用，避免双实现）。
- 唤起语境随 schedule_page 接装轮：时间轴块点按 → showBlockSheet。

## §F 对拍发现（过目裁决项）

| # | 发现 | 默认建议 |
|---|---|---|
| F1 | ui-pages §6「块浮层（启动抽屉）」= 85% 高抽屉+Roadmap 渲染+行动按钮区（只做 5 分钟/换件轻松的/暂缓收回）——实现为 **M1 点块表单**（代码注释自述「Landing Gear 抽屉 M4」挂账）：规范描述的是未落地的目标态 | 修规范标注分期：§6 加「M4 抽屉目标态；现状=M1 点块表单（§9.3）」分期注记，A 档基线页名 fact_sheet 的采图对象改按 M1 形态（见 fact_sheet.md F1 同源）；M4 抽屉登记迁移后 Day-2 目标态（迁移期新需求冻结不补实现） |
| F2 | A 档页单元「fact_sheet」5 态（S1 S2 S3 S5 S6）在 ui-pages §8.2 无逐态定义（§8.2 只定义 schedule_page）；manifest 亦无 fact_sheet/block_sheet 组 | 态枚举建议随过目拍板（默认建议=S1 有键正常态（proposed+凭证通关卡+马上开始）/S2 无关联凭证态（通关卡层消失）/S3 改时间编辑态（IME）/S5 无凭证+IME 复合/S6 CAS 冲突拒绝态——S4 手势中间态按 A 档通则转交互对拍不采图）；采图对象=M1 形态随 F1 裁决 |
