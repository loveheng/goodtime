---
status: active
updated: 2026-11-15
---

# 行为清单 · new_block_sheets（新建事项/逆向记账双通道，P4 浮层族余件施工输入，已过目转正 2026-11-15 D25——生成通道可作施工输入）

> §9.1.6 模板产出（2026-11-15 抽取）。抽取源 = `lib/ui/block_sheet.dart`（_NewBlockSheet 390–524 行）+ `lib/ui/schedule_page.dart` 唤起点（996 空态入口/1265 空槽点按含 past 判定）+ `docs/design/ui-pages.md` §3.5（空槽点按）/§7（逆向记账流）/§10 事实通道 + 命令面（place_block/retro_log 已平移——retro_log note=「已补记入账（事实通道，豁免排程校验；凡真实专注全部承认）」）。**范围 = _NewBlockSheet 双模式（未来=新建事项 / 过去=逆向记账）+ 唤起语境 + 提交双命令**。

## 七字段

| 字段 | 内容 |
|---|---|
| 交互意图 | 空槽点按创建（ui-pages §3.5）：未来时段=「新建事项/从清单选」双段选择；过去时段=**逆向记账「刚才做了什么」**（§10 事实通道豁免排程校验，落 done——凡真实专注全部承认，UI 全程无「补排程」暗示 §7）；空态手动入口同 sheet（无 slotStart → 默认作息起点 +1h） |
| 触发与阈值 | 空槽点按（slotStart=15min 吸附值，past=slotStart 早于现在的页侧判定）；空态「记一笔」式入口（schedule_page 996 无 slot）；无 StGesture 手势 |
| 状态空间 | 双段态：新建事项（label TextField autofocus「做什么（label）」）/从清单选（Dropdown listPlans）；模式由页侧 past 旗标定标题（新建事项/刚才做了什么 titleLarge）；提交键可用态=（选了 plan 或 label trim 非空） |
| 落库命令与拒绝码 | past=false→`place_block`（date=今日 ISO/startMin/endMin/planId 选清单时/label 新建时）；past=true→`retro_log`（同参——**落 done，豁免排程校验**，越界/零长块 INVALID_REQUEST 逐字镜像命令面）；成功无 SnackBar 直接 pop（静默落账） |
| 反馈形式 | 成功 pop（无 toast——今日页 revision 刷新面自现）；失败 ActionException message SnackBar 3s；提交键 disabled 灰态为唯一前置反馈 |
| 黄金向量指针 | place_block/retro_log 命令面已平移（GoldenCommandParseTest，dispatch 26/26）；本页零新向量 |
| 像素基线指针 | 无独立基线组（空槽点按瞬时交互=宿主今日页 S3 边界态语境；§F2 收口轮核） |

## §A 数据装配

| 行为 | 口径 |
|---|---|
| 清单 | listPlans()（Dropdown 选项，title 全量） |
| 默认时段 | start=slotStart ?? (wake+60)（**wake=data.wake 作息口径**——Dart DayData.wake）；end=start+60 |
| past 判定 | 页侧：slotStart 早于当前时刻 → 逆向记账模式（标题「刚才做了什么」） |

## §B 骨架与组件

| 行为 | 口径 |
|---|---|
| 标题 | past ?「刚才做了什么」:「新建事项」titleLarge |
| 双段切换 | SegmentedButton：新建事项(value=true)/从清单选(value=false)——选中={_fromPlan==null}；切「从清单选」时 _fromPlan=plans.first（空清单=null 保持新建态） |
| 新建态输入 | TextField autofocus label「做什么（label）」OutlineInputBorder |
| 清单态输入 | DropdownButtonFormField label「从清单选」逐 plan DropdownMenuItem(title) |
| 时段双字段 | 从几点/到几点 ListTile+TimePicker（subtitle clockOf 20sp）——Row Expanded 双列间距 12 |
| 提交 | FilledButton「放入日程」（enable=fromPlan!=null || label.trim().isNotEmpty）→ past? retro_log : place_block → pop |

## §C 逆向记账语义钉（§7/§10，2026-10-08 拍板）

| 行为 | 口径 |
|---|---|
| 事实通道 | retro_log 落 done 状态（confirmed+done 口径——凡真实专注全部承认）；**豁免排程校验**（撞墙/越作息不拒，仅起止越界/零长块机械校验） |
| 中性纪律 | UI 全程无「补排程」暗示（标题/按钮/文案中性——不是「补上漏掉的日程」而是「刚才做了什么」） |
| 归属 | 选清单→planId 绑定；新建→孤立 label 块 |

## §D 架构接线

- Kotlin 承载=ShiguangBottomSheet 双模式 Composable（past 旗标参数化）；命令经 schedule_page 命令接线（CommandHandler+DbWriteQueue）。
- 唤起语境随 schedule_page 收尾：空槽点按（Timeline 空槽 15min 吸附已平移）+ 空态手动入口两处上抛。
- place_block/retro_log 命令面已平移零缺口；DayData.wake 装配面已平移（ScheduleDayData）。
