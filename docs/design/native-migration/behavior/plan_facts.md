---
status: active
updated: 2026-11-15
---

# 行为清单 · plan_facts（计划随行凭证子页，P4 收尾件施工输入，已过目转正 2026-11-15 D24——生成通道可作施工输入）

> §9.1.6 模板产出（2026-11-15 抽取）。抽取源 = `lib/ui/plan_facts_page.dart`（270 行全文）+ `lib/ui/fact_sheet.dart`（factExpired/factLatestAnchorDate 纯函数口径 + FactCard 页上渲染形态——实现落点随浮层族轮）+ `docs/design/ui-pages.md` §9.5（凭证区文案组）/§8.4（C 档 2 态）+ fact 草案 §2.1（入口 1）+ 命令面行为清单（delete_artifact 已平移）。**范围 = 凭证子页本体（子树聚合装配 / 分区派生 / 聚合卡 / 折叠组 / 删除）**；通关 sheet（showVoucherSheet）随浮层族轮（fact_sheet/block_sheet 清单届时抽取过目）——本页只消费其回调契约。

## 七字段

| 字段 | 内容 |
|---|---|
| 交互意图 | 计划「随行凭证」子页=子树派生聚合（根+全部子孙 ≤3 级；一趟旅行的根计划=行程凭证总览页）：raw 琥珀聚合卡「N 条原文待提炼」（金样本 A1）+ 结构化 FactCard 列表 +「过去的凭证」「已作废」两折叠组；**本区无 human 添加钮**（写通道=AI 命令层+分享/快记入口）；voided 不进主卡面沉底折叠（§1.2 双通道：排程读侧静默、UI 通道单独放行） |
| 触发与阈值 | 本页零 StGesture 手势；FactCard 点按=通关 sheet / 长按=删除二次确认（删除仅 human，§0.4——删凭证绝不删块）；折叠组头点按展开/收起 |
| 状态空间 | S1 内容展示态 / S2 无数据态（空态文案居中，ui-pages §8.4）；局部态=raw 列表 sheet（多条时）+两折叠组展开态（默认收起） |
| 落库命令与拒绝码 | `delete_artifact`（actor=human；块处置归命令层）→ not_found 等；成功 r.note SnackBar 2s（note null 兜底「已删除」） |
| 反馈形式 | 删除确认弹框「删除「{title}」？」content「删除后无法恢复。」取消 TextButton/删除 FilledButton；失败 ActionException message SnackBar 默认 4s；点按/长按触觉无页面级标注（FactCard 内建随浮层族轮核） |
| 黄金向量指针 | delete_artifact 已平移（GoldenCommandParseTest）；本页零新向量 |
| 像素基线指针 | plan_facts 组 PLAN_S1/PLAN_S2（2026-11-14 已采） |

## §A 数据装配

| 行为 | 口径 |
|---|---|
| 子树 | subtreeIds：root + 逐层 walk（depth>3 return）listPlans(parentId)——与详情页「子计划」同口径 |
| 主数据 | artifactsForPlans(ids, includeVoided: true)（**端口缺口 G2**：Kotlin artifactsForPlans(planIds) 无 includeVoided 参数——Expand 补参） |
| 分区派生 | voided=state=='voided' 单列；live=非 voided→active（非过期，createdAt 升序 null 作 0）+ expired（factExpired）；raw=active 中 state=='raw'；structured=active 非 raw |
| 过期口径 | factLatestAnchorDate：anchors 逐条取 end_date ?? date 最大字典序 ISO（无锚→null，raw 未解析恒 null）；factExpired：latest==null→false（无锚=长期恒不过期），latest<今日→true（**纯函数源在 fact_sheet.dart——Kotlin 落点建议 :core:model ArtifactRules 伴生，随浮层族轮统一**） |
| 刷新面 | ListenableBuilder+FutureBuilder → VM collect repo.revision 重载（D15/D20 形） |

## §B 页面骨架

| 行为 | 口径 |
|---|---|
| AppBar | title「随行凭证」 |
| 空态 | 全空居中「还没有凭证——车票/门票/预订信息分享给拾光就会出现在这」textAlign center bodySmall textSecondary |
| raw 聚合卡 | 列表首件：voucherBg 底 radiusCard + InkWell padding h12 v10：receipt_long_outlined 18 + 8 +「{N} 条原文待提炼」bodyMedium（单数文案「1 条原文待提炼」）；1 条点按直开通关 sheet；多条开列表 sheet（titleSmall「待提炼的原文」padding LTRB(12,8,12,4) + 逐条 FactCard：pop 后开通关 sheet） |
| 结构化列表 | FactCard 逐条：onTap=通关 sheet / onLongPress=删除确认 |
| 过去组 | 「过去的凭证」=全部锚点最晚日历日早于今日的派生折叠（span 取 end_date）；「已作废」=state=voided 存证组（§1.2）；同形态 Card top 4：头行 expand_less/more 18 textSecondary +「{title} · N 条」labelMedium textSecondary（默认收起）；展开=逐条 FactCard（onTap 开 sheet / onLongPress 删） |

## §C FactCard 页上渲染形态（组件本体随浮层族轮，本页消费面）

| 行为 | 口径 |
|---|---|
| 形态 | Material（raw=voucherBg 底，其余透明）radiusCard + InkWell padding h12 v10：类别图标 18（verbal=sparkStroke 色，余 textSecondary）+ 8 + Expanded 列：title bodyMedium maxLines 2 ellipsis（verbal=bodySmall textSecondary）+ 状态小字 10sp（raw「原文待提炼」sparkStroke / voided「已作废」textSecondary / verbal「口头信息 · 待核实」sparkStroke） |
| 右侧 | fact.badge 非空：maxWidth 96 内 10sp textSecondary ellipsis（left 6）；factDateBadge 非空：[MM.dd] 式 11sp textSecondary（left 6）——均为锚点派生渲染不入库 |
| 回调契约 | onTap / onLongPress 双回调；本页不传触觉（组件内建随浮层族轮核） |

## §D 删除

| 行为 | 口径 |
|---|---|
| 确认弹框 | 「删除「{fact.title}」？」content「删除后无法恢复。」取消 TextButton/删除 FilledButton |
| 执行 | delete_artifact(fact.id, actor=human) → 成功 SnackBar 2s（r.note ?? '已删除'）；失败 message SnackBar 默认 4s |

## §E 架构接线

- PlanFactsViewModel（D15 形）+ ShiguangRoutes.PlanFacts(planId) 接装（路由已注册）。
- 端口缺口 G2：artifactsForPlans(includeVoided) Expand——双实现同步镜像 Dart WHERE 语义。
- **依赖序（D24 已裁决 2026-11-15）**：plan_facts 先施工，FactCard 以本清单 §C 形态规格最小实现接入；通关 sheet（showVoucherSheet）挂浮层族轮——本页点按暂挂空壳回调（UNCERTAIN 注记，消标随浮层族轮 fact_sheet/block_sheet 施工轮），形态对拍随浮层族轮统一收口。
