---
status: active
updated: 2026-11-14
---

# 行为清单 · queries 读面聚合（待平移，P2 下一块）

> §9.1.6 模板产出（2026-11-14 ::auto 盘点）。
> ScheduleQueries 是 MCP 读面工具（get_settings/get_schedule/get_history/list_plans）的数据组装层——**纯读、零落库**，依赖 Repository 只读面。Kotlin 平移形态 = :core:action `ScheduleQueries` 类（构造用 BridgeRepositoryPort）。

## 函数注册表（5 公开方法 + 内部装配辅助）

| 方法 | 行为 | 依赖 | 复杂度 |
|---|---|---|---|
| `getSettings` | 设置+fixedSlots+今日日期+today_energy 同返回体（§6） | settingsAll/listFixedSlots/scheduleDayOf | 低 |
| `globalBackgroundsPayload` | 全局背景治理装配：全量带 expired 标（AI 可发起顺手清理建议）+ 预算态（条数/字数双指标） | listBackgrounds + DateTime.isAfter 过滤 | 中 |
| `getSchedule` | 今天+明天（days ≤3 钳制）；三日容量水位线（空闲=作息−固定−human/pinned 墙）+例外日旗标+**分区背景装配**（global+计划段+祖先链继承≤3 级）+**当日凭证摘要**（四件套）+upcoming_facts 视窗外 14 天 | blocksOnDate/fixedSlotsForDate/listPlans/artifactsForPlans + 日历几何 | **高**（体量最大：时间轴渲染数据组装+跨午夜分段+火种识别+背景分区过滤+凭证搭载） |
| `getHistory` | 最近 14 天聚合：逐日 done 分钟数/totals | blocksOnDate × 14 + scheduleDayOf | 中 |
| `listPlans` | 计划列表快照 + raw_facts 计数与原文搭载 + plan 背景（祖先继承+当日过滤） | listPlans/artifactsByState/backgroundsByPlans | 中 |

## 依赖注入面

- `ScheduleQueries(this._repo)`——单依赖 Repository（与 CommandHandler 同端口）。
- `scheduleDayOf(DateTime, wakeMinute)`——作息日切割（util/schedule_day.dart 已有 Kotlin 等价）。
- `SettingsKeys.initialized/intOf`——已在 :core:model SettingsKeys。

## 平移序建议

1. `getSettings` + `getHistory`（体量小、逻辑清晰——入门件）
2. `listPlans`（搭载模式——背景/事实字段挂快照）
3. `globalBackgroundsPayload`（治理/对话通道）
4. `getSchedule`（**体量最大**——时间轴渲染+分区背景+凭证搭载+upcoming，最后做）

## Kotlin 对拍（2026-11-14 收口）

- 向量集 = mcp_toolface_vectors.json 全 23 case（读取族+写命令族——值泛化结构对拍）
- Kotlin 执行器 = **GoldenToolfaceTest（:core:mcp）**——工具面属 mcp 层（Dart lib/mcp/tools.dart 同位），经 NativeToolFace 打穿读面+命令层全链（原拟 :core:action GoldenQueriesTest，落点修正：:core:mcp api 依赖 :core:action，读面 5 件已由本执行器全量对拍）
- 值泛化：uuid/date/iso_datetime/timestamp_ms 泛化为类型标记（同 toolface 规则）

## 桥线序列化口径（2026-11-14 钉死，getSchedule 轮）

- **BridgeRepositoryPort 读面返回 = MCP JSON 口径**（commands.dart `planToJson`/`blockToJson`/`slotToJson`/`backgroundToJson`/`artifactToJson` 逐字，键全列含显式 null）——快照直通（CommandResult.snapshot = repo 读结果原样）、布尔为 JSON true/false、日期时刻字段含 `start`/`end` clockOf 字符串。Dart 侧 dispatch 以模型 toJson 族序列化 repository 返回；Kotlin 侧 :core:model 补 `toMcpJson()` 等价物。
- 既有 boolean 读法修正（DB 行 0/1 假设 → JSON bool）：`pinned` ×4（BlockOpsHandler.isAiMovable、ProposeHandler ×3）、`is_celebration` ×1（ScheduleQueries.getHistory）。
- `fixedSlotsForDate(date)` 返回钉为 `{"on_date": [...], "spillover": [...]}`（slotToJson 形）——镜像 Dart Repository 记录（onDate=coversWeekday(date)；spillover=跨午夜且 coversWeekday(前一日)）。

## getSchedule 详细行为清单（2026-11-14 抽取展开，体量最大单体）

**签名**：`getSchedule(now?, days=2)`——服务端供时刻（AI 不自算日期）；days 钳制 `<1→1, >3→3`。纯读零落库。

**装配序**：
1. `settingsAll` → wake=`intOf(wake_time)??0`；sleep=`intOf(sleep_time)??1380`；sleepAdj=`sleep<=wake ? sleep+1440 : sleep`（跨午夜作息）。
2. `parseExceptions(settings)`——`exceptions` 键 JSON 数组 `[{start,end,label?}]`，坏条目静默跳过，解析失败回空表。
3. `today = scheduleDayOf(now, wake)`——`minutesOfDay(now) < wake` → 前一日历日。
4. `structuredFacts = artifactsByState("structured")` 一次性取行；`factPlanTitles`：逐 fact 的 planId 首见查 `planById(pid)?.title ?? "未知计划"`（同 pid 只查一次）。
5. 逐日 `i in 0..n-1` 装配（见下）；返回 `{"today": iso(today), "days": out, "upcoming_facts": ...}`。

**单日装配**（`i` 日，iso=日历日）：
- `blocks = blocksOnDate(iso)`；`planIds` = blocks 非空 planId 去重集合；`plans` = 逐 id `planById`。
- 例外日：`exception = parseExceptions.where(covers(date)).map(label ?? "假期与出行")`；`suspended = 非空`；返回体 `exception` 字段 = `suspended ? labels.join("、") : null`（**显式 null**）。
- `resolved = suspended ? (onDate=[], spillover=[]) : fixedSlotsForDate(date)`——suspended 日 fixed_slots 空但 `suspended: true` 旗标在（作息边界保留、水位线抬升）。
- **容量水位线**（纯几何）：occupied = ① blocks 中 `pinned || source=="human"` → `(startMin, startMin+spanMinutes)`（AI 自有块可替换不占容）② onDate 槽 → `(startMin, crossesMidnight ? 1440 : endMin)` ③ spillover 槽 → `(0, endMin)`；`free_minutes = freeMinutesIn(wake, sleepAdj, occupied)`（窗口 [wake, sleepAdj)，占用并集裁剪求差）。
- `blocks` 输出：逐块 `{...blockToJson(b), "effective_label": b.label ?? plans[planId]?.title}`——label 回落计划标题（§4），再回落仍无则 **键显式 null**。
- `fixed_slots` 输出：`{"on_date": [slotToJson…], "spillover": [slotToJson…], "suspended": bool}`。
- `backgrounds`（分区装配，见下）；`facts`（当日凭证摘要，见下）。

**分区背景装配 `_dayBackgroundPayload(iso, planIds)`**：
- **物理过滤** `applies(b, iso)`：`applicableDates == null → 恒注入`；否则 `contains(iso)`（**日历日比对，严禁作息日**；空窗 fail-closed 不注入）。
- global 段：scope=global 且 applies → `{id, content, tags}`（tags 数组）。
- plans 段：逐 planId（**迭代序=planIds 集合序**）——`planById` null → 跳过（孤儿防御）；祖先链 `cur=plan; depth=0..`：scope=plan 且 planId=cur.id 且 applies → `{id, content, tags, "from_plan": cur.title(仅 depth>0)}`；`hasInherited |= depth>0`；`depth>=3 || cur.parentId==null → break`；`parent=planById(parentId)` null → break。
- items 空 → **不出段**（省 token）；出段 = `{"title": plan.title, "scope_note": "仅约束该计划下的活动块", "inherit_note": "祖先背景为泛化默认；当前计划背景为具体细化，局部差异以当前计划为准"(仅 hasInherited), "items": [...]}`——注记为确定性字符串常量（零智能）。
- 返回 `{"global": [...], "plans": {planId: 段}}`——分区禁平铺，日级态势句不携带任何背景。
- Kotlin 装配优化：`listBackgrounds()` 全量一次取行本地按 scope/planId 过滤（纯读等价，桥往返省 N 次）。

**当日凭证摘要 `_dayFactsPayload(facts, titles, iso)`**：
- 覆盖判定 `factCoversDate(f, iso)`：payload.`time_anchors` 数组逐锚（非 Map 跳过）——`date == iso` 命中；或 `end_date` 存在且 `date < iso <= end_date`（**字符串比较，日历日闭区间 span**）。
- item = `{id, category, title, "badge"(null 不携带——null-aware), "deadline_min"(null 不携带)}`；planId null → `unattributed`；否则按 pid 分组段 `{"title": titles[pid], "items": []}`（首见建段）。
- 返回 `{"plans": {pid: 段}, "unattributed": [...]}`——state 白名单仅 structured（raw 不进 prompt、voided 不出现）；摘要四件套 ≈100 token/日，凭证全文只进 UI。

**最早 deadline `_earliestDeadlineMin(f, iso)`**：锚 `date == iso` 的 `min`/`start_min` int 取最小；纯日期锚无分钟 → null（不携带）。

**视窗外凭证 `_upcomingFactsPayload(facts, titles, today, windowDays)`**：
- `windowEnd = today+(windowDays-1)`；`horizon = today+(windowDays+13)`（视窗外 ~14 天）。
- 逐 fact（state==structured）：`time_anchors` 非数组 → 跳过；逐锚：`date` 字符串长度 ≥10，前 10 字符 tryParse 失败 → 跳锚；`earliest = min(锚日期)`；锚在 `(windowEnd, horizon]` 时收集 `min`/`start_min` int 之最小为 `deadlineMin`。
- 过滤：earliest==null → 跳；`!earliest.isAfter(windowEnd)` → 跳（窗内锚走 facts 正常搭载）；`earliest.isAfter(horizon)` → 跳（过远冷数据）。
- 行 = `{"date": iso(earliest), "title", "deadline_min"(null 不携带), "plan_title"(planId!=null 时; titles 可能缺→null)}`，按 earliest 升序。轻字段防膨胀（不带锚数组/constraints）。

**几何辅助**（util/schedule_day.dart Kotlin 等价 → :core:model ScheduleDay）：`crossesMidnight = end<start`；`spanMinutes = 跨午夜 ? 1440-start+end : end-start`；`mergeMinutesIntervals`（排序+重叠/相接合并）；`freeMinutesIn = 窗长 − 占用并集∩窗`。

## 既有偏差修正（getSchedule 轮同族修复）

1. **getHistory 全量重写**：既有 Kotlin 是精简 camelCase 形，与黄金向量 `tf_get_history_window` 契约不符——重写为 `perDay[{date,done_count,missed_count,done_minutes,day_spark_done}](newest-first) + totals{done_count,done_minutes,day_spark_done} + calibration{deep_net_minutes, expansion_factor(null|real/estimate), segment_done_rates{morning,afternoon,evening}, quadrant_done_rates{Q1..Q4}, tolerance{minutes,source(default|detected)}, energy_recovery{celebration_done_count,celebration_minutes,fulfillment_rate}}`。规则常量：morning<720、720≤午<1080、晚≥1080（sleepAdj 上界）；耐受=次日 missed 率 ≥0.5 激增日的当日 doneMinutes 峰值，回退=(daily_new_blocks_limit??8)×(min_block_minutes??30)。
2. **ProposeHandler 例外日简化判定**（`settings["exceptions"] != null`）→ parseExceptions + covers(date) 逐字。
3. **ProposeHandler spillover 错误推导**（`filter{endMin<startMin}`）→ fixedSlotsForDate 记录口径（onDate/spillover 按 coversWeekday）。
4. **SettingsKeys.coversException 恒 false 桩** → parseExceptions 真实现。
5. **globalBackgroundsPayload 预算字面量** 8/400 → ScheduleRules 常量补齐（globalBackgroundsMax/globalBackgroundCharsMax + morningEndMin/afternoonEndMin/toleranceMissedRateSpike/toleranceFallbackBlocks/toleranceFallbackBlockMin）。
