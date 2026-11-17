---
status: active
updated: 2026-11-15
---

# 行为清单 · fact_sheet（通关卡族 + 挂载/未归属面板，P4 浮层族余件施工输入，已过目转正 2026-11-15 D25——生成通道可作施工输入）

> §9.1.6 模板产出（2026-11-15 抽取）。抽取源 = `lib/ui/fact_sheet.dart`（1075 行全文：FactDetailSheet/showVoucherSheet/showFactMountSheet/showUnattributedFactsPanel/_UnattributedGroup/FactCard/一致性派生）+ `lib/app_services.dart:158`（分享入册→挂载 sheet 唤起）+ `lib/debug/debug_overlays.dart:35`（调试菜单入口）+ `docs/design/ui-pages.md` §9.4/§9.5（文案组）+ fact 草案 §2.2/§2.4/§1.7 + 命令面（upsert_facts/delete_artifact 已平移）。**范围 = 通关卡 FactDetailSheet 六层装配 + 通关 sheet 入口 + 挂载 sheet + 未归属管理面板 + 常驻折叠组 + FactCard 组件归位**；块浮层嵌入消费面随 block_sheet.md。

## 七字段

| 字段 | 内容 |
|---|---|
| 交互意图 | 通关卡（fact 草案 §2.2/§2.4，Slot Assembler）=凭证详情单一 Widget 按序装配六层**空层自动消失**：Header 药丸 → Hero 通关区（hero_metrics ≤3 组 KV）→ 时空网格 → 约束 Chip 流（零任务压力不打勾不计数）→ 功能动作行（复制/拨号/导航零权限全 intent）→ 溯源手风琴；块浮层与独立入口（showVoucherSheet）同源装配。挂载 sheet=系统分享 raw 入册后归属归一通道（两次点击内完成）；未归属管理面板=计划列表页提示行唤起（N7 挂账本轮回接） |
| 触发与阈值 | 无 StGesture 手势；通关卡点按入口=凭证子页 FactCard 点按/通关条点按/常驻组条目点按；手风琴/折叠组头点按展开；面板左滑删除（二次确认）；长按=改挂 sheet |
| 状态空间 | 装配态=六层空层消失派生（verbal 头/一致性琥珀条/hero/网格/约束/动作/原文）；挂载 sheet=装载圈/凭证不存在态/归属列表态；面板=装载圈/空态「未归属凭证都处理完了」/列表态；A 档页单元 fact_sheet（块浮层·启动抽屉 M4）**Flutter 未实现**（§F1） |
| 落库命令与拒绝码 | `upsert_facts`（挂载/改挂：id+planId，human）→ not_found 等；`delete_artifact`（面板左滑删除，仅 human §0.4）→ not_found；成功 note SnackBar 2s（note ?? 「已归属」/「已改挂」/「已删除」） |
| 反馈形式 | 复制动作=剪贴板+SnackBar 2s「已复制 {code}」；拨号=tel intent、导航=geo intent（canLaunch 守卫）；失败 ActionException message SnackBar 3s（挂载 sheet）/默认 4s（面板删除）；一致性琥珀条「与日程时间不一致，点此核对」点按回块导航 |
| 黄金向量指针 | upsert_facts/delete_artifact 已平移（GoldenCommandParseTest）；本族零新向量（一致性派生/通关条合成属 JVM 纯函数对拍非 golden） |
| 像素基线指针 | A 档 fact_sheet 5 态（块浮层）——**Flutter 无此页面（§F1）manifest 未采**；通关卡独立形态无独立基线组（随块浮层 S1 嵌入态） |

## §A 一致性派生（factInconsistentWithBlock，D24-F1 琥珀条数据源）

| 行为 | 口径 |
|---|---|
| 在位判定 | block==null → false（日切删块悬空静默不检 §9.5-8；调用方经块侧 artifact_id 配对传入） |
| 块区间 | b0=date+startMin 绝对时刻；b1=date+endMin（b1<b0 跨午夜 +1 天） |
| 选锚 | 仅 kind=='span' 锚（start_min/end_min 绝对折算，end_date??date，s1<s0 跨午夜 +1 天）；**重叠度贪心**：与块重叠分钟最大者优先；均不重叠 → 起始最早者（§9.5-5 2026-10-08 拍板） |
| 不一致判定 | best.start != b0 \|\| best.end != b1（选锚与块起止不一致 → 琥珀条） |
| Kotlin 落点 | :core:action FactRules 扩 factInconsistentWithBlock（纯函数 JVM 对拍；_overlapMinutes 同文件） |

## §B 通关卡六层装配（FactDetailSheet）

| 层 | 口径 |
|---|---|
| verbal 头 | category==verbal：「💬 口头信息 · 待核实」header（titleMedium）+ 12 |
| 一致性条 | inconsistent && onNavigateToBlock 非空：_AmberBar（voucherBg radiusBlock+warning_amber 16+6+「与日程时间不一致，点此核对」bodySmall，h12 v8）+ 12 |
| Header | _Pill（fixedSlotBg radiusCapsule h8 v3+类别 icon 12 textSecondary+3+类别标签 11sp textSecondary）+ 8 + title titleMedium maxLines 2 ellipsis；badge（factDateBadge [MM.dd] 式 11sp textSecondary）top 4 |
| Hero 通关区 | payload.hero_metrics 列表非空：voucherBg 底 radiusCard insetMd padding+**sparkStroke 1dp 边**（top 12）——逐组 Expanded 列：k labelSmall textSecondary + 2 + v titleMedium（**字级纪律：不夺块浮层「马上开始」headlineSmall 焦点**；≤3 组=ArtifactRules.HERO_METRICS_MAX 出生截断） |
| 时空网格 | 锚点行（v3 纵距）：span=date+start_min/end_min（「{d} {clockOf(sm)}–{clockOf(em)}」；跨日「{d} → {e}」/全量「{d} {clock} → {e} {clock}」）；moment=「{d} {clockOf(min)}」；其余=role 非空则 role else「长期」；role 含「停止」前缀 ⚠ 11sp；行文本「{role} · {when}」（role 空=when）；📍 location（非空时尾行）；整层空（无锚无 location）消失（top 12） |
| 约束 Chip 流 | payload.constraints Map 三数组：required_items→_Chip(check_box_outline_blank, supplyBandBg) / rules→_Chip(block 图标, **fixedSlotBg 中性灰——禁令不是失败，红色语义全 app 保留 §2.2**) / notices→_Chip(info_outline, sparkBg)；_Chip=radiusCapsule h8 v4+icon 12 textSecondary+4+文本 11sp；Wrap 6/6（top 12）；空消失 |
| 功能动作行 | copyable_code→OutlinedButton.icon(copy 16)「复制」→剪贴板+SnackBar 2s「已复制 {code}」；contact_phone→「拨号 {phone}」tel intent；location→「导航」geo intent；Wrap 8/8（top 12）；空消失 |
| 溯源手风琴 | raw_text 非空：_Accordion「查看原文」默认折叠（expand 图标 16+label bodySmall textSecondary v4）→展开 projectionBg radiusBlock insetMd SelectableText bodySmall（**raw_text 永存原文**） |

## §C 通关 sheet 入口（showVoucherSheet）

| 行为 | 口径 |
|---|---|
| 形态 | ModalBottomSheet showDragHandle isScrollControlled + SafeArea + FactDetailSheet(padding LTRB(20,0,20,20)) |
| 入口全集 | 凭证子页 FactCard 点按/未归属组条目点按/未归属面板点按/通关条点按（schedule_page 481）/「查看日程」兜底链 |
| 返回参 | block（一致性比对数据源）+ onNavigateToBlock（琥珀条回块导航）——块浮层嵌入时由 BlockSheet 提供 |

## §D 挂载 sheet（showFactMountSheet，fact 草案 §1.7）

| 行为 | 口径 |
|---|---|
| 唤起 | 系统分享 raw 入册后（app_services.dart:158）+ 调试菜单（debug_overlays:35） |
| 装配 | _UnattributedGroup（常驻折叠组，**D2 宿主=本 sheet**）+ 8 + 分享预览（voucherBg radiusBlock insetMd：title bodySmall w600 1 行 + raw 原文 SelectableText bodySmall textSecondary top 6 **一字不解析逐字展示**）+ 8 + 「归到哪条计划？」titleSmall + 4 + plan 列表（dense ListTile folder_outlined 18 + title 1 行 ellipsis）+ 「先记下，稍后归属」TextButton（留池退场） |
| 归属 | 点计划 → upsert_facts(id, planId) human（**busy 防重**）→ pop + SnackBar 2s（note ??「已归属」）；失败 message SnackBar 3s |
| 装载/不存在 | FutureBuilder 装载圈（40 padding 居中）；artifactById null →「这条凭证不存在了」24 padding |

## §E 未归属常驻组与管理面板

| 行为 | 口径 |
|---|---|
| 常驻组 _UnattributedGroup | N=0 隐藏；Card 头行（expand_less/more 18 textSecondary+6+「未归属 · N 条」labelMedium textSecondary，h12 v10）默认收起；展开=逐条 dense ListTile（title 1 行 ellipsis + subtitle 11sp textSecondary=raw「原文待提炼」else 类别标签）；点按→通关 sheet / 长按→改挂 sheet（「把「{title}」挂到哪条计划？」titleSmall+8+空计划「还没有计划——先建一条计划」居中+plan 逐条 dense→upsert_facts 改挂→pop+note 2s「已改挂」） |
| 管理面板 showUnattributedFactsPanel | plans_page 未归属入口行唤起（N7 已挂空壳——本轮回接）；装载圈；空态「未归属凭证都处理完了」32 padding 居中；列表 padding LTRB(12,0,12,20)：「未归属凭证」titleSmall（12,8,12,4）+ 逐条 Dismissible endToStart（背景 fixedSlotBg radiusCard+delete_outline 18 右 20；confirmDismiss=二次确认弹框「删除「{title}」？」/「删除后无法恢复。」取消/删除→delete_artifact human→note 2s/失败默认 4s）+ FactCard（点按通关 sheet/长按改挂同 §E 同源） |
| FactCard 组件归位 | plan_facts 轮已落页内最小实现（§C 形态）——本轮组件化归位单点（plan_facts/面板/常驻组三消费方统一），形态规格不变 |

## §F 对拍发现（过目裁决项）

| # | 发现 | 默认建议 |
|---|---|---|
| F1 | **A 档页单元 fact_sheet（块浮层·启动抽屉 M4）Flutter 未实现**——ui-pages §6 的 85% 抽屉（含 Roadmap/行动按钮区）为目标态，实现仅有 M1 点块表单（block_sheet.md）；A 档 84 组中 fact_sheet 5 态采图对象=不存在页面 | 基线枚举改按 M1 形态（block_sheet.md F2 态建议随过目拍板）+ ui-pages §6 标注分期（M1 现状/M4 目标态挂 Day-2）；A 档 5 态采图对象=块浮层 M1（页名沿用 fact_sheet 口径留痕） |
| F2 | 挂载 sheet 分享通道：Dart 经 app_services 分享接收流唤起——Kotlin 双轨期分享接收面未建（N8c 分享转投=进程内 SharedFlow 直投挂载 sheet 已接线**壳层预览入口**？）——施工时核 D23 已裁决的分享通道（SharedFlow 直投→upsert_facts 落池→挂载 sheet 唤起）与 §D 装配对齐 | 施工轮核线：分享入册→showFactMountSheet 唤起链在 Kotlin 侧复用 D23 分享总线（若有偏差登记裁决）；不阻塞清单转正 |
| F3 | 时间锚行 when 三格式与 ⚠ 判据（role 含「停止」）为派生渲染细节——规范 ui-pages §9.5 无逐字对应 | 镜像现状平移（派生渲染不入库）；ui-pages §9.5 加锚行格式组注记（随施工轮写回） |
