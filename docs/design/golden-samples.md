---
status: active
updated: 2026-10-06
---

# 拾光金样本集（Golden Samples）

> 定位：schedule-app.md §7「验收」定义的金样本评审循环固定输入——每次改 playbook 或命令层机械校验后人工跑一遍，评审对话次序、载荷质量与 UI 走查现象。上游锚：schedule-app.md §7（蓝本）、functional-spec.md §1（里程碑验收）。本文档不引入任何新机制；实现与设计不符处在 §12 差异表如实登记。

## 样本登记表

| # | 样本 | 类型 | 考察面 | 口径出处 |
|---|---|---|---|---|
| 1 | 有个考试 | 工作冲刺类 | 澄清追问次序与 spec 产出 | schedule-app.md §7 示例（冻结） |
| 2 | 全家爬山 | 生活协调类 | 多人可用性澄清→天气窗口→漫游主题块→犒赏兑现 | schedule-app.md §7（推演补拍） |
| 3 | 约小李逛商场 | 社交协商类 | 协商叶→等待锚点→被答复升格→休闲犒赏豁免 | schedule-app.md §7（推演补拍） |
| 4 | **云南七天** | **旅行模式全流程** | 旅行模式双阶段 + M1–M4 全量能力走查 | **本文档正文** |

# 金样本 #4「云南七天」——旅行模式双阶段全流程

## 1. 场景设定

- 用户作息：wake_time=450（07:30）、sleep_time=1410（23:30），作息窗 960 分钟；工作日 fixed_slots「上班」09:00–18:00（weekdays 1–5），周末空表。
- 演练日历：2026-10-06（周二）快记捕获 → 10-10～10-16 筹备期 → **10-17（周六）～10-23（周五）在途七日**（例外日「云南行」）→ 10-25 复盘。
- 行程骨架：昆明集散（D1 滇池）→ 大理两日（D2 动车+古城、D3 洱海骑行）→ 丽江（D4 动车+古城、D5 玉龙雪山硬预约、D6 束河）→ D7 丽江返程航班。
- 走查前提：真机包经 `toolbox run build-apk --install` 刷新；桌面 AI 与手机同 LAN、MCP token 已配对（设置页）；桌面侧装载 docs/playbook/ 五文件（MCP prompts 为空实现，SOP 走 playbook——§12 差异 6）。
- 本样本与「真机四天型走查」（日常/混乱/低电量/旅途）中**旅途型**的关系：即旅途型的完整演绎，低电量与混乱型分别内嵌于 D5 累瘫与 D3 雨天剧情点。

```mermaid
flowchart LR
  A["捕获 · 快记"] --> B["澄清 · 对话"]
  B --> C["拆解 · 筹备线 T-minus 路线图"]
  C --> D["筹备期排程 · 填充率 60%"]
  D --> E["上膛 · exceptions + 天气 + 钉硬骨架"]
  E --> F["在途七日 · 填充率 35% 骨架硬锁"]
  F --> G["复盘 · get_history"]
  G --> H["宏观胜典 · reward_spec 兑现"]
```

## 2. 机制覆盖矩阵

| 机制（条款出处） | 样本落点 | 实现状态 |
|---|---|---|
| 粒度定律四级阶梯（§1） | 幕 2→幕 4 | 已实装（快记→plan→spec→块） |
| 澄清收敛/最小澄清/犒赏付出型限定（§7） | 幕 3 | 政策层已实装（open_items 无写入口——§12 差异 1） |
| 确定型路线图 + T-minus（§12/§13） | 幕 4 | spec 文本约定；过窗 digest 检测未实装（§12 差异 2） |
| 悬空警报（§4/§8） | 幕 5 剧情点 | 已实装（morningDigest.dangling） |
| 等待锚点（§7 十一轮） | 幕 5 | 政策层（10 分钟唤醒块照排） |
| 例外日旅行模式：fixed 挂起 + 填充率 35%（§13） | 幕 6 起 | 已实装（ScheduleRules.fillRateExceptionsPercent） |
| pinned 硬骨架（§13 在途①） | 幕 6 + 各日 | 已实装（adjust_blocks add 默认 pinned） |
| 半日主题粗颗粒 + 漫游态渲染（②/§10 十四轮） | D1–D7 | 已实装（例外日触发 roam 形态） |
| 整单拒绝 + available_free_windows（§6） | D1 剧情点 | 已实装 |
| 庆祝块常态（④） | D1/D2/D3/D6 | 已实装（is_celebration + 香槟金） |
| 夜块人通道（⑥） | D1 夜市 | 已实装（place_block，human 橙提示放行） |
| 天气投影（§11） | D5 态势句 | 已实装（单城市 × 3 天窗，§12 差异 7） |
| 逆向记账（⑤/§10） | D4 狮子山日落 | 已实装（无庆祝旗标——§12 差异 3） |
| 电量三档 + 水流重算（§7 十轮） | D5 累瘫剧情点 | 已实装（🪫 → reflow_day 三级阻尼） |
| 随行熔断/融化（⑤） | D6 雨天剧情点 | 已实装（melt_block，melted 绝不显红） |
| 双轨仪表盘/安全线/确认分级/放工守卫 | 走查观察点 | 已实装（见证式安全线未实装——§12 差异 4） |
| 复盘聚合 + 月历 + JSON 导出 | 幕 9 | 已实装 |
| 中性语言/叙事双语气（§7 五轮） | 幕 9 | 已实装（AI 叙事层） |

## 3. 幕 1 · 捕获（10-06，手机侧，零 AI）

用户手机快记 FAB → 输入「想去云南玩七天」→ 回车「收入清单」。

```json
{"op": "quick_capture", "title": "想去云南玩七天"}
```

返回 note：`已收入清单（默认轻松/随手可做，被排期即升格）`。plan 落愿望池（light/anywhere/不重要+不紧急）。**走查观察**：FAB 琥珀点草稿、清单「待安排」分区出现该条目。

## 4. 幕 2 · 澄清（10-06，桌面 AI 对话）

AI 执行 dump→clarify SOP：`list_plans` 命中该条目 → 按澄清收敛阈值（单轮 ≤3 问、先时间后细节）追问：

> ①什么时间出发、玩几天？②几个人去？③预算档位？

用户答：10-17 周六出发玩 7 天；两人；人均 6000。AI `update_plan` 回写（原 title 原样保留，只 patch 澄清字段）：

```json
{"op": "update_plan", "id": "<p1>",
 "spec": "2026-10-17～10-23 云南七天，两人，人均预算 6000 元。路线：昆明集散（D1）→大理（D2–D3）→丽江（D4–D6）→丽江返程（D7）。",
 "notes": "启动第一步：打开 12306 查 10-17 出发地→昆明、10-23 丽江→出发地的票。\n敲定：两人行；住昆明 1 晚+大理 2 晚+丽江 4 晚。",
 "deadline": "2026-10-17", "importance": true, "expected_version": 1}
```

派生：deadline 2026-10-17（距今 11 天未紧急，10-14 起自动升「紧急」）。休闲享乐类计划 → **跳过犒赏收尾问句**（付出型限定）；澄清全部收敛，无未决项。※ 当前实现 open_items 无写入口，未决问题须沉淀进 notes（§12 差异 1）。

## 5. 幕 3 · 拆解（10-07，AI add_plan ×N）

长线护航（旧线优先）：为 p1 挂子树，**只拆筹备线，旅途线建轻 plan 不拆步**（在途肌肉颗粒放粗到半日主题，在排程时才凝聚成块）：

- c1「云南行筹备」——确定型，spec 路线图带 T-minus 窗口：

```json
{"op": "add_plan", "title": "云南行筹备", "parent_id": "<p1>",
 "importance": true, "energy_level": "light", "tool_required": "desk",
 "estimate": 90,
 "reward_spec": "行李装箱完成当晚：看一部攒着的电影",
 "min_viable_action": "打开备忘录，把订票/住宿/索道列成三行待办",
 "spec": "2026-10-17 出发。大交通：出发地→昆明、丽江→出发地两段机票 + 昆明→大理、大理→丽江动车。\n## 路线图\n- [ ] T-7（10-10）订两段机票与两段动车票 (ACTIVE)\n- [ ] T-6（10-11）订住宿：昆明 1 晚+大理 2 晚+丽江 4 晚\n- [ ] T-4（10-13）预约玉龙雪山大索道票（10-21 上午时段）\n- [ ] T-2（10-15）列行李清单（防晒/雨具/常用药/便携氧）\n- [ ] T-1（10-16）行李装箱+线上值机+手机全量备份"}
```

- c2「昆明中转：滇池湖岸慢逛」、c3「大理：古城慢逛+洱海骑行」、c4「丽江古城+狮子山日落」、c5「玉龙雪山一日」（importance=true，min_viable_action=「山脚蓝月谷栈道走 20 分钟也算到过」，estimate=360）、c6「束河白沙慢漫游+伴手礼」——均 light/anywhere、spec 一行即可。

## 6. 幕 4 · 筹备期排程（10-09～10-16，正常日，填充率 60%）

**10-09 晚 AI `propose_schedule` 目标 10-10（周六）**：

```json
{"op": "propose_schedule", "date": "2026-10-10", "items": [
  {"plan_id": "<c1>", "label": "订两段机票与两段动车票（比价后下单）",
   "start_min": 600, "end_min": 660, "is_day_spark": true, "is_celebration": false},
  {"plan_id": "<c3>", "label": "查洱海骑行租车站点与环海路线攻略",
   "start_min": 960, "end_min": 1010, "is_day_spark": false, "is_celebration": false}]}
```

机械校验通过：填充率 110 ≤ 960×60% = 576；订票块 60min>45min → 呼吸律要求下一块 ≥11:15，攻略块 16:00 起排 ✓。返回 `protection_manifesto`（拦截其余愿望池条目、守住留白）。用户分级确认卡（火种突出）→ `confirm_block` → 完成后 `tick_block`。

**10-12 剧情点（过窗升级的兜底路径）**：10-11 忙别的事没订住宿。10-12 晨 morningDigest 悬空段报「云南七天旅行 已停滞 1 天」（roadmap 步过窗检测未实装，由悬空警报兜底——§12 差异 2）。AI 主动消化：`update_plan` c1 路线图勾掉第一行、(ACTIVE) 移第二行 → `add_plan` 叶「订齐三地住宿」（parent_id=c1，deadline=2026-10-13，自动升紧急）→ 晚间 propose `{"start_min":1170,"end_min":1210,"plan_id":"<叶>"}`（40min）。

**10-13 等待锚点**：大索道票放出时间不定 → propose 10 分钟唤醒块 `{"plan_id":"<c1>","label":"午休刷一次大索道票放出（等待锚点）","start_min":750,"end_min":760}`。当晚票放出并锁定 10-21 上午时段 → 路线图第三行勾掉。

## 7. 幕 5 · 旅行模式上膛（10-16 值机块 done 之后）

AI 一次性完成机械设置 + 钉死硬骨架：

```json
{"op": "update_settings", "values": {
  "weather_location": "丽江",
  "exceptions": [{"start": "2026-10-17", "end": "2026-10-23", "label": "云南行"}]}}
```

机械生效：期间**工作类 fixed_slots（上班 09:00–18:00）挂起**（作息边界保留）、**填充率上限自动降至 35%**、get_schedule 返回体出现 `exception: "云南行"` 旗标与水位线抬升。随后逐班次 `adjust_blocks add`（默认 pinned=true，硬行程对 AI 自己也是墙）：

```json
{"op": "adjust_blocks", "action": "add", "date": "2026-10-17", "label": "出发地→昆明航班（值机+托运）", "start_min": 480, "end_min": 690}
{"op": "adjust_blocks", "action": "add", "date": "2026-10-18", "label": "昆明→大理动车", "start_min": 540, "end_min": 690}
{"op": "adjust_blocks", "action": "add", "date": "2026-10-20", "label": "大理→丽江动车", "start_min": 540, "end_min": 680}
{"op": "adjust_blocks", "action": "add", "date": "2026-10-21", "plan_id": "<c5>", "label": "玉龙雪山大索道+蓝月谷（预约时段）", "start_min": 480, "end_min": 870}
{"op": "adjust_blocks", "action": "add", "date": "2026-10-23", "label": "丽江→出发地航班", "start_min": 780, "end_min": 990}
```

每条返回 note：`已加入单块提案（pinned=true）；硬行程不参与填充率聚合`。**走查观察**：pinned 块渲染、设置页「例外日管理」出现云南行区间、月视图 10-17～23 格子带例外微标。

## 8. 幕 6 · 在途七日（例外日，填充率 35%，骨架硬锁 + 肌肉漫游）

> 每晨 AI 的固定动作：`get_schedule`（先读后排，返回体自带例外旗标/水位线/三日天气）→ `propose_schedule` 当日肌肉 → 用户分级确认。以下日期均为 2026 年。

### D1（10-17 六 · 昆明）——整单拒绝教学点

晨 `get_schedule(days=3)` 返回体摘录：

```json
{"today": "2026-10-17", "days": [{"date": "2026-10-17",
  "exception": "云南行", "free_minutes": 750,
  "fixed_slots": {"on_date": [], "spillover": [], "suspended": true},
  "blocks": [{"label": "出发地→昆明航班（值机+托运）", "pinned": true,
              "start": "08:00", "end": "11:30", "source": "ai", "status": "proposed",
              "effective_label": "出发地→昆明航班（值机+托运）"}]}],
 "weather": {"city": "丽江", "days": [
   {"date": "2026-10-17", "summary": "晴", "t_max": 22, "t_min": 10, "precip_prob": 5}]}}
```

AI 第一次 propose 贪心塞了三项（滇池 180min + 翠湖 60min + 米线 40min = 280min）→ **整单拒绝**（错误体，domain JSON 形态）：

```json
{"code": "schedule_rejected",
 "message": "propose_schedule 整单拒绝：1 项不合法（全部合法才落库）",
 "data": {"date": "2026-10-17",
   "rejections": [{"index": -1,
     "reason": "填充率红线：AI 块总时长 280 分钟超过当日可用 750 分钟的 35%",
     "hint": "削减任务总量、扩大留白——被拒不是因为排得差，是因为排得满（§6 减震器原则）"}],
   "available_free_windows": [
     {"start_min": 450, "end_min": 480, "start": "07:30", "end": "08:00"},
     {"start_min": 690, "end_min": 1410, "start": "11:30", "end": "23:30"}]}}
```

AI 减震器响应：砍掉翠湖，二次 propose 两项 → 落库：

```json
{"op": "propose_schedule", "date": "2026-10-17", "items": [
  {"plan_id": "<c2>", "label": "沿滇池海埂大坝慢走一圈，喂红嘴鸥",
   "start_min": 810, "end_min": 990, "is_day_spark": true, "is_celebration": false},
  {"label": "过桥米线晚餐", "start_min": 1110, "end_min": 1150,
   "is_day_spark": false, "is_celebration": true}]}
```

返回 `protection_manifesto`：`{shielded_free_minutes: 530, slack_ratio: 0.71, suppressed_tasks_count: 6, narrative: "本次拦截 6 件未排入今天；守住了 530 分钟自由流动（留白比 71%）；当日核心 1 件。排程不是为了占满时间，而是为了捞起一颗珍珠。"}`。

- 用户分级确认卡（火种=滇池主题块突出，米线=香槟金庆祝态）→ 确认 → 勾选 → **安全线徽章点亮「今日底线守住啦」**。
- 21:30 临时起意夜市：夜块政策——AI 不排夜里，用户在日视图空槽点按放「金马碧鸡坊夜市」22:00–23:20（`place_block`，human/confirmed，重叠越界橙提示放行）。次日起它对 AI 是墙。
- **走查观察**：例外日两块渲染为漫游主题态（弱底色、起止 ~ 模糊标记）；米线香槟金；主题块 180min 也满足 ≥180min roam 触发。

### D2–D4 —— 天气改口、逆向记账

| 日 | 骨架（pinned） | 肌肉（AI propose，均确认） | 剧情点 |
|---|---|---|---|
| D2 10-18 日 | 动车 09:00–11:30 | 古城慢逛/找咖啡 14:00–17:00（c3，🔥）+ 乳扇砂锅鱼晚餐 18:30–19:15（🎂） | 填充率 225 ≤ 810×35%=283 ✓ |
| D3 10-19 一 | — | 洱海骑行 09:00–12:30（c3，🔥）+ 砂锅鱼晚餐 18:30–19:15（🎂） | weather_location=丽江 取不到大理 → **AI 改口问用户**，得知下午有雨 → 只排上午，下午整段自由流动区吸收天气扰动（防御日叙事） |
| D4 10-20 二 | 动车 09:00–11:20 | 丽江古城慢逛 14:00–17:00（c4，🔥） | 19:00–20:00 狮子山日落即兴发生 → 日视图点过去时段**逆向记账** `retro_log {"date":"2026-10-20","start_min":1140,"end_min":1200,"plan_id":"<c4>","label":"狮子山看日落"}` → 落 done（事实通道豁免排程校验；无庆祝旗标——§12 差异 3） |

### D5（10-21 三 · 玉龙雪山硬预约日）——天气态势句 + 累瘫水流重算

晨 `get_schedule` 三日天气：`10-21 晴 / 10-22 阵雨 80% / 10-23 多云`。AI 态势句：「今天晴天窗口不可替代（索道票也是今天的）；明天束河有阵雨 80%，户外环节全部前置到今天与明晨。」当日 propose 仅一项肌肉：白沙古镇+壁画 15:00–17:30（c6，150min；150 ≤ (960−390)×35%=199 ✓）。

**14:40 剧情点（累瘫）**：下山大腿发软，用户今日页点 🪫（today_energy=low）→ app 自动 `reflow_day` 水流重算：

```json
{"op": "reflow_day"}
→ data: {"date": "2026-10-21", "budget_minutes": 142,
         "sparked": ["<白沙块id>"], "melted": []}
note: "降档重算完成：1 项切 5 分钟启动版，0 项暂缓放回清单（检测到身体电量低，今日任务自动顺延，不产生任何惩罚）"
```

白沙块 150min>15min 且 c6 有 min_viable_action（「束河/白沙入口茶馆坐 20 分钟」）→ 二级阻尼压为 15:00–15:15 火种胶囊；晚上腊排骨火锅由用户手动放块（human 块无庆祝旗标——§12 差异 3）。**走查观察**：今日页降档中性摘要、块切微型火种态、庆祝/火种豁免于重算。

### D6（10-22 四 · 束河）——随行熔断

propose：束河老街慢逛 09:00–11:30（c6，🔥）+ 茶马古道博物馆 12:30–14:00（c6）+ 火塘咖啡馆烤茶 18:30–19:15（🎂）；285 ≤ 336 ✓。午后雨势转大 → 用户右滑博物馆块 → `melt_block` → **melted 中性蒸发回池，绝不显红**；改为客栈听雨（自由流动区）。**走查观察**：融化后块从时间轴消失（月历也不渲染 melted）、清单 c6 回落待安排。

### D7（10-23 五 · 返程）

propose：古城早市最后漫步+伴手礼查漏 08:00–09:30（c6，🔥；90 ≤ (960−210)×35%=262 ✓）→ 确认 → 勾选 → pinned 航班 13:00–16:30 → 到家。

## 9. 幕 7 · 复盘（10-25，AI get_history + 用户月历/导出）

`get_history {"days": 14}` 返回摘录（窗口 10-12～10-25，perDay 恒聚合非明细）：

```json
{"totals": {"done_count": 18, "done_minutes": 1865, "day_spark_done": 6},
 "calibration": {
   "deep_net_minutes": 500,
   "expansion_factor": 1.11,
   "segment_done_rates": {"morning": 1.0, "afternoon": 1.0, "evening": 1.0},
   "quadrant_done_rates": {"Q1": 1.0, "Q2": null, "Q3": null, "Q4": 1.0},
   "tolerance": {"minutes": 240, "source": "default"},
   "energy_recovery": {"celebration_done_count": 4, "celebration_minutes": 175,
                       "fulfillment_rate": 1.0}}}
```

口径对照：深潜净值 500 = 筹备线 c1（110min，importance）+ 雪山 390min（c5 importance，进攻日叙事「今天你把 6.5 小时投给了最重要的事」）；膨胀系数 1.11 = (110+390)/(90+360)；能量回血 4 次庆祝兑现 175 分钟。**旅途休闲块（非 importance）天然不入深潜净值——系统客观见证旅途，不考核假期完成率（§13 在途⑤）**。

AI 复盘动作：`update_plan` c1 路线图五行全部 `[x]`（宏观战役胜典判定）→ 10-24 正常日（60%）propose 兑现块 `{"plan_id":"<c1>","label":"兑现犒赏：看一部攒着的电影","start_min":1200,"end_min":1320,"is_celebration":true}`。

用户侧：月视图 10-17～23 七格（状态点阵 + 🔥/🎂/例外微标）；设置页「数据导出 JSON」全量归档。

## 10. 幕 8 · 走查脚本（真机评审按幕执行）

| 幕 | 动作 | 预期现象 |
|---|---|---|
| 1 | 快记 FAB 输入回车 | 草稿琥珀点灭、清单「待安排」出现条目 |
| 2–3 | 桌面 AI 澄清/拆解 | 清单出现父子树（≤3 级缩进）、c1 详情含路线图与犒赏锚点 |
| 4 | AI propose 筹备块 | 分级确认卡火种突出；确认后块落时间轴 |
| 5 | update_settings + 五个 add | 设置页例外日出现「云南行」；五个 pinned 块上屏；月视图例外微标 |
| 6 D1 | 贪心 propose → 拒绝 → 修正 | 桌面侧收到逐条原因+空窗；app 侧只见二次提案 |
| 6 D1 | 确认/勾选 | 漫游态+香槟金渲染；安全线「今日底线守住啦」点亮；深浅色两套都验 |
| 6 D1 | 空槽放夜市 | human 块即时 confirmed；AI 次日绕行 |
| 6 D4 | 点过去空槽记账 | 日落块落 done 带「补记」视觉；时间轴无排版破坏 |
| 6 D5 | 点 🪫 | 降档摘要中性；白沙块缩为火种胶囊；腊排骨不受影响 |
| 6 D6 | 右滑博物馆块 | 无痕消失、零红色标记；清单 c6 回落待安排 |
| 7 | get_history/月历/导出 | 聚合无明细行；月格微标齐全；导出 JSON 含三表+settings |

## 11. 评审追问清单（改 playbook/校验后必答）

1. 例外日 35% 红线是否持续拦住微观管理冲动（D1 教学点是否复现）？
2. 拒绝返回体 `available_free_windows` 是否足以让 AI 一次修正（不出现二次拒绝循环）？
3. 天气单城市局限下 AI 是否按纪律「改口问用户」而非瞎编大理天气？
4. 累瘫 reflow 是否只降肌肉、绝不动 pinned 骨架与庆祝块？
5. 复盘叙事是否守住双语气与中性语言禁令（无「假期完成率」类审判句）？
6. 所有界面串是否与 ui-spec §0.4 白话词汇表一致（例外日→「假期与出行」、坍缩→「5 分钟启动版」等）？

## 12. 实现差异备忘（走查会撞到的设计→实现缺口，2026-10-06 对照代码登记）

| # | 设计条款 | 实现现状 | 证据 |
|---|---|---|---|
| 1 | 澄清歧义管道 open_items（§4/§7） | **全链无写入口**：add_plan/update_plan 命令与工具 schema 均不携带 open_items，仅快照/查询可读；工具描述「未决问题放 open_items」与实现不符 | commands.dart UpsertPlan/UpdatePlan 字段表；tools.dart add_plan 描述 |
| 2 | roadmap 步过窗→digest 紧急段+强制实例化（§13 前置期） | morningDigest 无 spec 路线图解析；由悬空警报（dangling）兜底 | queries.dart morningDigest |
| 3 | 逆向记账/手动放块可落 done+celebration（§13 在途⑤） | retro_log 与 place_block 均无 is_celebration 入口，此类块不进能量回血聚合 | command_handler.dart _retroLog/_placeBlock |
| 4 | 漫游态见证式安全线（§13 在途⑦） | safelineLit 仅判 is_day_spark && done；例外日 retro done 不点亮徽章 | schedule_page.dart safelineLit |
| 5 | 水流重算可一键撤销（§7 六轮） | 未实装，撤销=用户逐块手动改回 | memory.md 口径决策 |
| 6 | MCP prompts 承载四流程（§7） | prompts/list 空实现，SOP 走 playbook 文件 | mcp_server.dart / memory.md |
| 7 | 天气投影为单城市×3 天窗（§11） | 旅行跨城市时其余城市取不到预报，AI 按政策改口问用户（设定内行为，走查须知） | lib/service/weather.dart forecast3；tools.dart get_schedule |
| 8 | 系统日历只读投影（§4） | 未实装，挂真机轮；本样本不依赖 | schedule-app.md §12 预期修正① |
