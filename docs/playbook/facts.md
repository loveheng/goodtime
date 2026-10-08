---
status: active
updated: 2026-10-08
---

# facts —— 事实凭证（playbook）

> 上游：fact-user-relay-draft.md §1.6（三刀路由）/§1.7（生命周期与人机分工）/§8-5；工具=`upsert_facts`（硬事实唯一写入口，物理删除你无权做）；读侧搭载=get_schedule `facts` 摘要（窗口对齐）+ list_plans `raw_facts`（N 条原文待提炼）。

## 三刀路由（先路由，再动手）

任何零散信息（短信/邮件/公告/截图转述/口信）先过三刀，命中即停：

| # | 判据 | 去向 |
|---|---|---|
| ① | 有刚性时空边界（发车 09:00 / 入住 10-17 / 周一闭馆） | `upsert_facts` time_anchors（moment.role 受控枚举：departure/deadline/checkin/checkout=行动类触发临场条，arrival/reference=信息类仅展示） |
| ② | 需要用户**做一次专门行动**（提前一天去银行换外币） | add_plan / 路线图步骤 / 等待锚点（10 分钟唤醒块） |
| ③ | 到场遵守/携带/注意即可（凭身份证进站 / 禁三脚架） | `upsert_facts` constraints 三数组 |
| ④ | 兜底：叙事性软上下文 | `upsert_background`（捕获纪律见 dump.md） |

- **规则类（③）永不生成任务、永不打勾**——日程的注释层，唯一离开注释层的出口是第②刀；「提前 45 分钟到站」是规则（`constraints.advance_arrival_minutes`），「提前一天去银行」是行动（②）——判据=是否要专门跑一趟；
- **verbal 口头信息三去向**：带时间锚（「老板说周三索道检修」）→ verbal 凭证（软权重，永不硬拦）；承诺/等待（「小李说周五答复」）→ 等待锚点（10 分钟唤醒块，fact 留档溯源）；纯偏好 → background。verbal 卡自带「口头信息 · 待核实」角标，核实的主动权永远留给用户。

## 消化 raw SOP（分享/快记原文 → 结构化）

系统分享/快记进来的原文已由 app 入册（state=raw、一字不解析），`list_plans` 返回体 `raw_facts` 就是你的待提炼队列：

1. 逐条读 `raw_text`，过三刀路由判定性质；
2. 是事实 → `upsert_facts` **回填同 id**（state=structured，逐槽位填：title 摘要化 / time_anchors 机读 / badge 仅 booking 类显式给 / hero_metrics ≤3 组 / constraints 三数组）；**rule 类锚（周一闭馆/每日截止）必须显式给 `rule_scope{valid_from,valid_to}`**（单日规则两者相等），缺则命令层 `invalid_request` 拒——杜绝红线永久生效误杀；星期约束 100% 落 `constraints.forbidden_weekdays`（不另写一遍 `days_of_week`）；
3. 是计划意图（快记粘的是想法不是凭证）→ add_plan 建真计划，并**归档原快记畸形 plan**（见快记收尾）；
4. **低置信槽位宁可缺省不猜**——空槽位 UI 逐层隐藏，编造时刻=造第二真相；拿不准就问用户；
5. 归属：plan_id=null（未归属池）的按上下文主动提议归属（「这条 D8724 短信看起来属于云南行，已挂到筹备计划」），用户否决即改挂或退回未归属；改挂=upsert_facts 传 plan_id；
6. **回显义务**：每条回填后显式回显（「已提炼：发车 09:00、05车12F、2B 检票口——不对就说我改」）。

## 快记收尾（零残留）

快记 FAB 整段粘贴=原文原样落 plan title。消化后**必须同轮处置原快记 plan**：事实类已提炼、计划意图类已建真计划 → `update_plan` `archived=true` 归档原快记 plan（原文永存于凭证 raw_text，一字不丢）。计划列表不留长文本孤岛。

## 改签 SOP（先更 fact，再报新起止；挪块=人）

1. 新短信=行程有变 → `upsert_facts` **同一 id**：锚点/车次更新，`raw_text` 传新原文（自动追加双段只增不清，不要重发旧文）；
2. **挪块由人执行**——乘车块是 pinned（人终审件，和平条款：AI 不可挪）：你报出新起止（「改签到 D8732 09:30 开了，请把日程里的乘车块改到 09:30–11:50」），用户点块改时间；缓冲块（不 pinned）被水流融化后晨间 propose 自愈重排；
3. 遗漏兜底：app 派生比对在凭证卡顶亮「与日程时间不一致，点此核对」琥珀条——看到琥珀提示，先读 fact 锚点再引导用户对齐块；
4. 退票/作废：**必须先获用户同意**再 `upsert_facts state=voided`（存证保留、读侧全通道静默；关联 pinned 块自动解除钉定）；误分享文本的物理删除你无权做，只能提议用户删。

## 提案反哺纪律

- propose 前必读 get_schedule：`facts` 摘要（category/title/badge/最早 deadline_min，随视窗逐日搭载）=硬约束已到你眼前——**提案必须避开锚点时刻**，赶路缓冲按 `advance_arrival_minutes` 自带（缓冲块不 pinned）；
- 理由区显式引用数据源（「09:00 前收束是因为 D8724 08:45 止检——来自你的车票凭证，不是我排的」）；
- rule 锚（周一闭馆/每日 16:00 停止入场）主动规避；verbal 永不做硬红线，只在理由区注明「据口头信息，建议核实」。
- **硬红线是机械的、非你臆断**（V1.5，§9.3）：propose/adjust 时 app 会比对关联计划及递归子树的 rule 凭证——命中即 `schedule_rejected`，拒绝体带回 `rejected_by_facts` 逐条原因（含 artifact_id/title/reason）。你看到拒绝先读原因、不要再编一套「避开」理由；human 手动放块绝不阻断，只在块卡右上亮琥珀「规则提醒：…」角标（用户意志高于一切）。

## 临场通关条与硬红线（V1.5 / 分期二 行为口径）

- **临场通关条（分期二，§9.1/§9.4）**：app 按「本地物理时钟」在 `[T_trigger, T_dismiss]` 窗口内把凭证顶到今日页（系统 Banner 下、遗留区上）。触发=行动类 role；公式分层时相推进：`T_target = T_action − advance_arrival_minutes`（仅 departure/checkin 减，deadline 已含提前量不重复扣），`T_trigger = T_target − 120min`，`T_dismiss = T_action + 30min`（仅 departure/checkout 到点后收折，deadline/checkin 到点即收）。你只需把 moment 锚的 `role` 填对、把 `advance_arrival_minutes` 写在 constraints——呈现交给 app。
- **硬红线（V1.5，§9.2/§9.3）**：rule 凭证必带 `rule_scope` 生效窗（`valid_from→valid_to`，超窗自动失活、不入库改 state）；星期判据来自 `constraints.forbidden_weekdays`（与生效窗共存）。app 校验范围=本计划及递归子树，跨午夜块按开始日归属星期。你提案时照常读 get_schedule 搭载规避即可，真正拦截由 app 做。
