---
status: active
updated: 2026-11-15
---

# 行为清单 · plan_detail（计划详情页，P4 收尾件施工输入，已过目转正 2026-11-15 D24——生成通道可作施工输入）

> §9.1.6 模板产出（2026-11-15 抽取）。抽取源 = `lib/ui/plan_detail_page.dart`（449 行全文）+ `docs/design/ui-pages.md` §5（详情页行）/§8.4（C 档 2 态）/§9.4·§9.5（文案组）+ `behavior/routing_map.md`（详情族三路由）+ 命令面行为清单（update_plan/place_block 已平移 dispatch 26/26，只核语义）。**范围 = 详情页本体（查看态全件 + 编辑态四字段 + 排期快捷动作 + open_items 手答 + 子树 + 两子页入口行）**；背景/凭证两子页本体随 plan_background / plan_facts 两清单。

## 七字段

| 字段 | 内容 |
|---|---|
| 交互意图 | 计划详情=查看态默认全页路由：roadmap 进度行 / notes 首行「马上开始」高亮框 / open_items 手答（人机澄清闭环）/ 子树 ≤3 级 /「背景」「随行凭证」两子页入口行（CRUD 整区迁出）/「排期」快捷动作；右上「编辑」进四字段表单编辑态，保存回查看态不关页（2026-10-08 二批 UX 拆分拍板） |
| 触发与阈值 | 本页零 StGesture 手势；AppBar actions=排期（plan null 禁用）+ 编辑/完成 toggle；open_items「敲定」=TextButton（答案 trim 空不提交）；两入口行点按；排期=日期/时间双 picker 串行（取消任一步中止） |
| 状态空间 | S1 查看态（内容展示）/ S2 编辑态（四字段表单，ui-pages §8.4 C 档 2 态）；派生态=plan 不存在（居中「这条计划不存在了」，首帧装载闪现见 §F2） |
| 落库命令与拒绝码 | `place_block`（排期：date/startMin/endMin=startMin+estimate??60/planId）→ INVALID_REQUEST 族（date 非法/起止越界/零长块，逐字镜像命令面行为清单）；`update_plan`（编辑保存：title/spec/notes/rewardSpec trim 空传 null=不改动；openItems 整表替换；均 CAS expectedVersion）→ version_conflict/not_found |
| 反馈形式 | 排期成功 SnackBar 2s「已排入 {date} {clockOf(start)}，可在日程页调整」（不关页）；保存成功 SnackBar 1s「已保存」+ 回查看态；失败 ActionException message SnackBar 3s（排期/保存），手答失败 message SnackBar 默认 4s；version 每次成功写从 snapshot['version'] 回填（同会话连续操作不被乐观锁卡住） |
| 黄金向量指针 | update_plan/place_block 命令面 Kotlin 已平移（GoldenCommandParseTest，dispatch 26/26）；本页零新向量 |
| 像素基线指针 | plan_detail 组 PLAN_S1/PLAN_S2（2026-11-14 已采；**两态 sha256 相同疑点——S1 内容态 vs S2 空态不应同图，基线收口轮核对**） |

## §A 数据装配（_load 镜像）

| 行为 | 口径 |
|---|---|
| 主数据 | planById(planId)（null→不存在态）；title/spec/notes/rewardSpec 预填编辑控制器；openItems 快照；version=p.version |
| 子树 | 逐层 walk（depth 从 1 起，depth>3 return）：listPlans(parentId) 递归收集 (depth, plan)——≤3 级缩进 |
| roadmap 进度 | 纯函数：定位 `## 路线图`→截到下一 `\n## `（无则到尾）→`^\s*-\s+\[x\]`/`^\s*-\s+\[ \]`（multiLine）计数 done/open→done+open==0→null；显示「当前进度 {done}/{done+open}」 |
| 排期默认日 | settingsGet('wake_time') int 解析失败回落 420 → scheduleDayOf(now, wake)（作息日口径——00:00–wake 间「今天」=作息日历今天，与 get_schedule 同源）；picker 窗口=作息日..+30 天；时间默认 09:00 |

## §B 查看态骨架（默认）

| 行为 | 口径 |
|---|---|
| AppBar | title「计划详情」；actions=排期 TextButton.icon（event_available 18+「排期」）+「编辑」TextButton（编辑态翻「完成」） |
| 进度行 | roadmap 非空时 spec 区上方 bottom 8：「▶ 当前进度 N/M」12sp primary |
| 标题 | titleLarge |
| 马上开始框 | notes trim 非空时 top 12：primaryContainer 底 radiusCard 圆角 padding 12——「马上开始」labelSmall textSecondary → 首行 titleMedium → 余行 bodySmall top 6（首行=ui-pages §5 Landing Gear 第一步） |
| spec | trim 非空时 bodyMedium top 12 **整段纯文本渲染**（对拍发现 F1，见 §F） |
| 犒赏 | trim 非空时 bodySmall textSecondary top 12「犒赏：{reward}」 |
| 手答条目 | 未答 top 10：「？{question}」bodySmall textSecondary + TextField（isDense OutlineInputBorder hint「写下答案，敲定它」）+「敲定」TextButton；已答 top 6：「✓ {question} → {answer}」bodySmall textSecondary |
| 子计划 | 树非空时 top 12：「子计划」labelMedium textSecondary + 逐条「└ {title}」bodySmall 左缩进 depth×16dp top 2 |
| 子页入口行 | 底部 SizedBox 16 后两行 Card（margin bottom 8）+ListTile：背景（info_outline 22 /「身体状况、偏好、注意事项」）→ PlanBackground(planId)；随行凭证（confirmation_number_outlined 22 /「车票/门票/预订与须知」）→ PlanFacts(planId)；trailing chevron_right 20 textSecondary（目录形态同设置页 _entryRow 口径） |
| 不存在态 | plan null 居中「这条计划不存在了」 |

## §C 编辑态（四字段表单）

| 行为 | 口径 |
|---|---|
| 表单 | 四 TextField（OutlineInputBorder）间距 12：标题 / 精确描述（spec）maxLines 3 / 备注（首行写「马上开始」的第一步）maxLines 2 / 犒赏（打完这一仗怎么奖励自己）；padding LTRB(20,8,20,24) 同查看态 |
| 保存 | FilledButton「保存」（top 16）→ update_plan（四字段 trim 空传 null=不改动，expectedVersion=_version）→ 成功回查看态+「已保存」1s；**清空字段在本表单不可达**（trim 空=null=不改动，F3 现状平移） |
| 退出 | 「完成」toggle 回查看态不保存（无脏态确认——现状） |

## §D 排期快捷动作（functional-spec §2）

| 行为 | 口径 |
|---|---|
| 流 | 日期 picker（initial=作息日，first=作息日，last=+30 天）→ 时间 picker（initial 09:00）→ place_block{date, startMin=h*60+m, endMin=startMin+estimate??60, planId} |
| 成功 | 不关页（全页形态下用户常继续编辑/记背景）+ SnackBar 2s「已排入 {iso} {clockOf(start)}，可在日程页调整」（clockOf=h:mm 补零 %1440） |
| 失败 | ActionException message SnackBar 3s（place_block 既有拒绝码族逐字镜像命令面） |

## §E 架构接线（D15/D20 同形）

- PlanDetailViewModel（轻 VM 同 PlansViewModel 形）：StateFlow（loading/content/editing/missing 四态 UiState）+ collect repo.revision 重载 + 命令方法（save/answer/schedule）+ 事件面（SnackBar 单发流）+ Factory 注入。
- ShiguangRoutes.PlanDetail(planId) 接装（N7 已挂导航占位）；两入口行 navigate → PlanBackground(planId)/PlanFacts(planId)（三独立路由已注册，routing_map OP3）。
- 端口现状：planById/listPlans(parentId)/settingsGet 均已具备零缺口；命令面已平移。

## §F 对拍发现（过目裁决项）

| # | 发现 | 默认建议 |
|---|---|---|
| F1 | ui-pages §5 详情页 spec 描述「`## 路线图` 渲染：Checklist，`(ACTIVE)` 步 ▶ 高亮、`[x]` 划线」——实现无 Markdown 渲染：spec 整段纯文本 + 顶部派生进度行「▶ 当前进度 N/M」（Checklist 记号实为 AI 生成 spec 的内容书写约定） | 修规范对齐实现（D13-b/D19 先例）：§5 改「spec 整段纯文本渲染 + `## 路线图` 章派生进度行；Checklist 记号=spec 内容书写约定非渲染形态」 |
| F2 | 首帧 quirk：_load 异步回填前 plan==null，首帧短暂显示「这条计划不存在了」后翻正 | Kotlin 装配用 loading/content/missing 三态 UiState 消除首帧闪现（装配形态自由度非行为差异，基线采稳定态不受影响）——不裁决不阻塞 |
| F3 | 编辑态清空字段不可达（trim 空=null=不改动，镜像 update_plan 已对拍命令面语义） | 现状字面平移（纯体验项不占迁移期缺陷修复开口）；Day-2 观察项 |
