---
status: active
updated: 2026-11-15
---

# 行为清单 · action 命令族登记表（**已过目转正**，2026-11-14 用户授权直接决策，§9.1.6）

> §9.1.6 模板产出（P2 铺开前置盘点，2026-11-14 ::auto 5 节点 1）。
> 本文件 = **op 注册表 + 领域码表 + 依赖面**（已过目转正）。逐命令七字段展开：
> 主干 8 命令见文末「逐命令展开」节（Kotlin 平移备料）；余 18 命令下轮铺开。

## op 注册表（23 op / 26 命令类，`ScheduleCommand.fromJson` 判别）

| op | 命令类 | 语义域 | 语义清单状态 |
|---|---|---|---|
| quick_capture | QuickCaptureCommand | 快记捕获（零细节，§3.3） | 待展开 |
| quick_note_draft | QuickNoteDraftCommand | 快记草稿在盘（三字段静默 KV） | 待展开 |
| upsert_plan | UpsertPlanCommand | 计划建档（add/update 双语义） | 待展开 |
| update_plan | UpdatePlanCommand | 计划 patch + 乐观锁（version_conflict 回 latest） | 待展开 |
| delete_plan | DeletePlanCommand | 计划删除（级联口径待清单展开） | 待展开 |
| propose_schedule | ProposeScheduleCommand | 排程提案（schedule_rejected 逐条明细+空窗） | 待展开 |
| place_block | PlaceBlockCommand | 块放置 | 待展开 |
| adjust_blocks | AdjustBlocksCommand | AI 单块建/挪/缩/删（和平条款门控） | 待展开 |
| adjust_block_time | AdjustBlockTimeCommand | 人类放行通道（越界豁免） | 待展开 |
| confirm_block / reject_block | Confirm/RejectBlockCommand | 提案确认三键 / 否决（原因可选回传） | 待展开 |
| tick_block | TickBlockCommand | 打卡（done） | 待展开 |
| shift_block | ShiftBlockCommand | 顺延（postpone 计数，≥3 需人工标） | 待展开 |
| swap_block / degrade_block / melt_block | Swap/Degrade/MeltBlockCommand | 互换 / 降级 spark / 融雪（断流保护） | 待展开 |
| reflow_day | ReflowDayCommand | 低电量降档当日水流重算 | 待展开 |
| retro_log | RetroLogCommand | 复盘日志 | 待展开 |
| restore_block | RestoreBlockCommand | 归档恢复 | 待展开 |
| postpone_block | PostponeBlockCommand | 放工顺延（明日概要） | 待展开 |
| panic_clear | PanicClearCommand | 一键清空（保护期 Clean Slate） | 待展开 |
| update_settings / update_fixed_slots | UpdateSettings/FixedSlotsCommand | 设置与一周节奏（AI 权限边界：数值类键可写，user_rules 等仅用户） | 待展开 |
| upsert_background / merge_backgrounds | Upsert/MergeBackgroundsCommand | 背景双工具（预算终态双指标：条数>8 / 字数>400，仅拦 ai） | 待展开 |
| upsert_facts | UpsertFactsCommand | 硬事实通道（raw 入册/structured 回填，fact §8-4） | 待展开 |
| delete_artifact | DeleteArtifactCommand | 凭证删除（DeleteArtifact 仅 human） | 待展开 |

## 领域拒绝码表（ActionErrorCode 7 码逐字，MCP 层原样进 error.data）

| 码 | 语义 | data 附加（返回契约风格：AI 读懂原因自我纠正，无需重拉） |
|---|---|---|
| invalid_request | 参数/校验拒绝（缺字段/越界/非法值） | hint（下一步建议，人类可读） |
| not_found | 目标 id 查无 | — |
| forbidden | 越权（pinned/human 块 AI 不可动；DeleteArtifact 仅 human） | hint |
| version_conflict | 乐观锁 CAS 失败 | **latest 最新快照**（直接在其上合并重试，不重拉） |
| settings_missing | settings 未初始化（缺作息边界）——propose 整单硬拒 | — |
| schedule_rejected | 排程机械校验未过 | 逐条拒绝明细 + **available_free_windows** |
| budget_exceeded | 全局背景注入预算超限（>8 条或 >400 字；仅拦 ai） | 当前全局背景**全量摘要**（具体归并方案可直接提出） |

## 依赖注入面与结构

- `CommandHandler(this._repo)`——**单依赖** Repository（actor 由传输层注入 human/ai，不可由载荷伪造；写路径统一 `Repository.synchronized` FIFO 锁「读→校验→写」）。
- `ActionException(message, {code=invalid_request, hint, data})`——领域拒绝唯一载体；`CommandResult(op, targetId, snapshot, note, data)`——唯一成功载体（snapshot=落库后最新快照 JSON 口径）。
- `ScheduleCommand` sealed 族：`op`/`targetId`/`expectedVersion`（乐观锁 CAS 断言，空=不校验）三抽象 + `fromJson` 大 switch 判别（**坏 op → invalid_request + hint 可选 op 全列**）。
- 平移边界注记：`_int`/`_str`/`_reqStr` 解析助手 + 各命令字段抽取属**机械翻译**；`command_handler` 各 `_xxx` 私有方法的**校验规则语义**是行为清单主战场（每条规则引用设计稿段落号）。

## P2 剩余体量盘点（2026-11-14 ::auto 5 节点 3）

| 单元 | 行数 | 依赖 | 平移序与形态 |
|---|---|---|---|
| models/ 四件 | ✅ 已平移 | — | :core:model（Artifact 试点 + 三件，对拍 24 case 全绿） |
| collab/fsm.dart | ✅ 已平移 | — | :core:model/collab（54 case 对拍全绿） |
| action/commands.dart | 1,441 | models + Repository(读) | **类型骨架已落 :core:action**（7 码/ActionException/CommandResult/端口）；26 命令类字段级平移=机械（每类 ~30 行），fromJson 判别 switch 直译 |
| action/fact_rules.dart | 202 | models | 纯规则函数（ActionPass/凭证约束）——机械 |
| action/rules.dart | 91 | models | 常量与纯函数（背景预算双指标等）——机械 |
| action/queries.dart | 748 | Repository | 读面聚合（get_schedule/get_history 投影）——依赖 repository 接口 |
| **action/command_handler.dart** | **2,394** | 全部上行 + Repository | **语义主战场**：各 `_xxx` 私有方法=校验规则集（每条引用设计稿段落号）——**行为清单逐命令展开 + 人工过目后平移**（本轮停点） |
| data/repository.dart | 1,883 | Db | SQL 层 + FIFO 锁（synchronized）——最后（双轨期桥回 Dart 的接缝也是它） |

平移序结论：类型骨架（✅）→ 26 命令类字段级 → fact_rules/rules 纯函数 → queries（依赖 repository 接口先立）→ **handler 语义（清单过目后）** → repository（SQL 落地）。双轨期桥接点=CommandHandlerPort（已定义）。



## 逐命令展开（主干 8 命令——Kotlin 平移备料，2026-11-14）

### quick_capture（快记捕获）

| 字段 | 内容 |
|---|---|
| 交互意图 | 零细节捕获：快记条一行文字落愿望池（§3.3 永不关门） |
| 触发与阈值 | title 必填非空（_reqStr）；importance 缺省 false；deadline 可选 ISO |
| 状态空间 | plans 表 +1 行（min_viable_action=null 概念态） |
| 落库命令与拒绝码 | QuickCaptureCommand(title, importance, deadline)；拒绝=invalid_request(缺 title) |
| 反馈形式 | CommandResult(op, targetId=新 id, snapshot, note) |
| 黄金向量指针 | toolface tf 层经 add_plan 链路覆盖 |
| 像素基线指针 | plans_page 空态/有数据态 |

### upsert_plan（计划建档）

| 字段 | 内容 |
|---|---|
| 交互意图 | AI add_plan / 手动建计划：概念建档（本体 Soul，不含时间） |
| 触发与阈值 | title 必填；openItems 载荷坏条目静默跳过（_openItems）；energy/tool 缺省 light/anywhere |
| 状态空间 | plans +1；幂等重试友好（id 可自带） |
| 落库命令与拒绝码 | UpsertPlanCommand(11 字段)；invalid_request 族 |
| 反馈形式 | CommandResult{snapshot=planToJson} |
| 黄金向量指针 | toolface tf_add_plan_ok / tf_update_plan_ok |
| 像素基线指针 | plans_page S1/S2 |

### update_plan（计划 patch）

| 字段 | 内容 |
|---|---|
| 交互意图 | 字段级 patch（只发要改的字段，§11 并发契约）；AI 修改主路径 |
| 触发与阈值 | id 必填；expectedVersion CAS（version_conflict 回 latest）；archived 只在显式给出时进 patch |
| 状态空间 | plans 行 patch；version+1 |
| 落库命令与拒绝码 | UpdatePlanCommand(15 字段)；version_conflict(data.latest) / not_found |
| 反馈形式 | CommandResult{snapshot=最新, note} |
| 黄金向量指针 | toolface tj_version_conflict（data_code + latest 键集） |
| 像素基线指针 | plans_page |

### propose_schedule（排程提案，AI 专属）

| 字段 | 内容 |
|---|---|
| 交互意图 | 整天原子提案：全部合法才落库，否则逐条返回原因（§3.4 分级确认卡数据源） |
| 触发与阈值 | date 必填（服务端供日期口径——AI 不自己算「下周三」）；settings 未初始化整单硬拒（settings_missing）；逐项静态校验（起止合法/作息边界 start≥wake、endAbs≤sleepAdj）——**human 放行通道=place_block/adjust_block_time 校验刻度不对称** |
| 状态空间 | schedule_blocks 批量 proposed（原子写）；冲突时整单拒 |
| 落库命令与拒绝码 | ProposeScheduleCommand(date, items[ProposedItem])；schedule_rejected(data.rejections + available_free_windows) / settings_missing |
| 反馈形式 | CommandResult{data.date/blocks} |
| 黄金向量指针 | toolface tf_propose_ok / tj_schedule_rejected（空窗结构） |
| 像素基线指针 | schedule_page S1（提案块三键形态）/S6 |

### place_block（人直接放块，human 专属）

| 字段 | 内容 |
|---|---|
| 交互意图 | 点空槽创建/从清单选的命令化：**human 允许任性**（重叠/越界 UI 橙色提示放行，命令层只拦起止非法）；source=human、status=confirmed（人放下的即是已定之事） |
| 触发与阈值 | date/start_min/end_min 必填；起止非法才拒（_proposeStaticError 同款起止段，作息边界豁免——校验刻度不对称） |
| 状态空间 | schedule_blocks +1（confirmed human） |
| 落库命令与拒绝码 | PlaceBlockCommand；invalid_request（起止非法/零长块） |
| 反馈形式 | CommandResult{snapshot.block} |
| 黄金向量指针 | toolface tf_adjust_add_pinned（pinned 默认 true 是 adjust_blocks 语义；place_block pinned=false 语义 human） |
| 像素基线指针 | schedule_page S1（human 块形态） |

### adjust_blocks（AI 单块建/挪/缩/删）

| 字段 | 内容 |
|---|---|
| 交互意图 | 火车行程等硬日程入口；受**和平条款门控**（human/pinned 不可动）；remove 对 confirmed=熔断同效（melted） |
| 触发与阈值 | action ∈ add/move/resize/remove；add 必填 date/start/end；move 至少 start/end 其一；pinned 缺省 true（add 无 plan 硬行程，§4） |
| 状态空间 | schedule_blocks 增/patch/删；base 不动 |
| 落库命令与拒绝码 | AdjustBlocksCommand；forbidden（pinned/human）/ invalid_request（越作息/撞墙/缺参）/ not_found |
| 反馈形式 | CommandResult{snapshot.block, note} |
| 黄金向量指针 | toolface tf_adjust_add_pinned / tf_adjust_move_ok / tj_forbidden |
| 像素基线指针 | schedule_page S1（AI 块） |

### update_settings（设置写，人/AI 权限不对称）

| 字段 | 内容 |
|---|---|
| 交互意图 | 键注册制设置写（传 null 清空）；**AI 权限边界**：数值类键与 today_energy 可写，user_rules/weather_location/exceptions/identity_prompt/theme_mode 仅用户（§7 政策精华 7） |
| 触发与阈值 | values Map（settings 键注册表）；today_energy ∈ high/normal/low |
| 状态空间 | app_settings upsert |
| 落库命令与拒绝码 | UpdateSettingsCommand；invalid_request（非自设键 AI 写 / today_energy 非法值——**真机钉样 'low-but-not' 拒**） |
| 反馈形式 | CommandResult{data.settings} |
| 黄金向量指针 | toolface tf_get_settings_seeded / tj_invalid_request |
| 像素基线指针 | settings_page S1/S2 |

### update_fixed_slots（一周节奏批量原子替换）

| 字段 | 内容 |
|---|---|
| 交互意图 | 一周节奏整批原子替换（§3 UpdateFixedSlots）；空表合法（自由职业预设） |
| 触发与阈值 | slots[].weekdays ⊆ 1..7（越界拒——真机钉样 weekdays[9]）；start/end 分钟制 |
| 状态空间 | fixed_slots 整批替换 |
| 落库命令与拒绝码 | UpdateFixedSlotsCommand(slots[FixedSlot])；invalid_request（weekdays 越界，码值=实现快照） |
| 反馈形式 | CommandResult{data.slots} |
| 黄金向量指针 | toolface tf_update_fixed_slots_ok / tj_fixed_slots_invalid |
| 像素基线指针 | rhythm_settings S1/S2 |


### quick_note_draft（快记草稿静默持久化，human-only）

不进 MCP 工具面（§10 快记入口拍板）；三字段整体覆写（text 空=整组清除——保存/清空收起是唯一清除通道）；键 quick_note_draft_* 内部 KV。拒绝=invalid_request（缺 text）。向量指针：无（UI-only）。基线：quick_note_fab 画廊（fabDraft 草稿点灯态）。

### delete_plan（计划删除，human 专属不可逆）

物理删除；有日程块/子计划引用时被外键拒绝（foreign_keys=ON，处置=先处理引用再删——命令层转可读错误 invalid_request）。targetId=id。基线：plans_page S1/S2。

### adjust_block_time（改时间键，human）

patch 起止；**重叠/越界橙色提示放行**（校验刻度不对称 §3——human 允许任性）；两值至少一个（null=不改该端）；CAS expectedVersion。拒绝=version_conflict/not_found/invalid_request（零长块）。基线：schedule_page S1。

### shift_block（+30min 顺延微调，human）

级联顺延同日后续 AI 块；human/pinned 原地不动（§10）；minutes 缺省 30。拒绝=version_conflict/not_found。基线：schedule_page S3（派生保护区渲染联动）。

### tick_block（勾选，human）

proposed/confirmed → done；executionQuality full（默认）/spark——spark 执行内容取 plan.min_viable_action（playbook 口径，§4）。拒绝=not_found/version_conflict。基线：schedule_page S1（done 块 ✓ 角标）。

### restore_block（恢复，human 专属 UI 撤销通道）

melted（右滑误触）/done（打卡误触）→ proposed/confirmed；**仅这两个中性/终态可回活跃**（状态机不放开通用回退——「减震器」语义）；toStatus 命令层校验其余拒绝。基线：schedule_page（UI 撤销 SnackBar 形态）。

### postpone_block（遗留区顺延今日，human 专属）

missed 块整体搬到目标日，起止钟点不变、身份不变（postpone_count+1）；**仅 missed 可顺延**。基线：schedule_page S2（遗留区态）。

### panic_clear（现实熔断，human 专属批量原子）

mode：clear_remaining（今日未开始 AI 块非 pinned 整体 melted 无痕回池）/ push_2h（整体 +120min，撞手动/pinned/固定占用或越作息**整单拒**）；human 块与 pinned 硬行程永不动（和平条款）；nowMin=「未开始」判定基准（缺省执行时刻）。拒绝=invalid_request（mode 非法）/ schedule_rejected 族（push_2h 撞墙）。基线：schedule_page S6 邻接（panic sheet）。

**实现级语义（N5.5 抽取，2026-11-15，待人工过目）**：
- envelope：settingsAll→未初始化（缺 wake_time/sleep_time）即拒 settings_missing；date=作息日今日（scheduleDayOf(now, wake)，时钟=系统钟字面量）；nowMin=cmd.nowMin ?? minutesOfDay(now)。
- targets=blocksOnDate(date) 过滤 `source==ai && !pinned && status∈{proposed,confirmed} && startMin>nowMin` 按 startMin 升序；空集→note「今天剩余没有需要熔断的 AI 安排」零写零 bump。
- clear_remaining：逐块 patchBlock{status:melted} CAS(expectedVersion=b.version)；**单块冲突即中断抛 version_conflict+latest（原子性，非批量吞错）**；note「已把 N 项放回清单待安排（无痕，零心理负债）」data{melted:[ids]}。
- push_2h（**先整单校验后统一写**）：walls=同日 (source==human||pinned) 且 status∉{melted,archived} 的 (startMin,endMin) + fixedOnDate (start,end) + fixedSpillover (max(0,start),end)；逐 target 校验 ns/ne=±120：`ns<wake || ne>sleepAdj`→拒「后移 2 小时会越出今日作息边界（ns–ne），试试「全清空」」；撞墙（ws<ne && ns<we）→拒「后移 2 小时会撞 ws–we 的手动安排或固定占用」hint「可先对单项用「改时间」微调」；校验全过才进写循环 patchBlock{start_min,end_min,postpone_count+1} CAS；note「已把 N 项整体后移 2 小时」data{moved:[ids]}。
- 未知 mode→拒「未知 mode：X」hint「可选 clear_remaining / push_2h」。

### retro_log（逆向记账，human 专属）

空白时段补记 done 块——**事实通道豁免排程校验**（记录现实不是制定计划），计入深潜净值；凡真实专注全部承认。拒绝=invalid_request（起止非法）。基线：schedule_page（过去时段 sheet 形态）。

### swap_block（换乘，human 专属）

原地换入待安排池时长相近的 light/anywhere 琐事，原任务无痕回池（化解精力死锁）；targetPlanId 可选（缺省自动就近匹配）。拒绝=not_found/version_conflict。基线：schedule_page（换乘抽屉态）。

### degrade_block（坍缩降级，human 专属）

块缩为 5 分钟火种胶囊（剩余时间释放为补给带），执行内容切 min_viable_action；**human 块不可坍缩**（§6.4）。拒绝=forbidden（human 块）/not_found。基线：schedule_page（火种胶囊形态——spark 块基线同源）。

### melt_block（融化，human 专属）

右滑化为水汽——melted 中性态无痕回池绝不显红；**仅物理位移/用户主动触发**，绝不因「没打卡」触发（§4）。拒绝=not_found。基线：schedule_page（melted 淡出——S2 邻接）。

### reflow_day（水流模型确定性重算，human 专属）

现实扰动后当日重算——**三级阻尼**：①淹没留白（免动作）→②后序 AI 块降 spark（压 min_viable_action）→③溢出 melted 蒸发回池；牺牲顺序=非核心最先、火种靠后、庆祝块豁免（仅全局熔断可动）；date 缺省今日作息日。基线：schedule_page S1（重算后形态）。

**实现级语义（N5.5 抽取，2026-11-15，待人工过目）**：
- envelope 同 panic；date=cmd.date ?? 作息日今日；**energy!=low → 短路 note「当前电量非低档，无需降档重算」零写**（独立调用幂等守门；UI 电量接线先 update_settings low 再 reflow 恒过门）。
- 例外日：parseExceptions(settings) covers(date) → fixed 取 (onDate:[], spillover:[]) 全空（漫游日无固定占用墙）。
- budget：occupied=pinned||human 块 span + fixedOnDate（跨午夜钳 1440）+fixedSpillover (0,end)；available=freeMinutesIn(wake,sleepAdj,occupied)；**budget=available×25 ~/ 100**（fillRateLowEnergyPercent=25 整除）。
- movable=blocks 过滤 `source==ai && !pinned && !isCelebration && status∈{proposed,confirmed}`；plan 逐个 planById 缓存（min_viable_action 判定用）；**牺牲顺序=startMin 降序（开始时间靠后者最先）**。
- 预算收敛循环（movable 快照原地替换）：alive=非 melted 子集；`totalOf(alive)≤budget → break`；b=movable[i]：span>sparkMaxMinutes(15) 且 plan.min_viable_action 非空 → **二级压火种** patchBlock{end_min:normEnd(startMin+15), execution_quality:spark}（normEnd=end>1440?end−1440:end）；否则 **三级融化** patchBlock{status:melted}；CAS 拒=静默跳过不计数不降级（if/else 互斥）。
- note「降档重算完成：N 项切 5 分钟启动版，M 项暂缓放回清单（检测到身体电量低，今日任务自动顺延，不产生任何惩罚）」data{date,budget_minutes,sparked:[ids],melted:[ids]}。

### upsert_background（背景写，人/AI 双通道）

id 缺省=建档/id 存在=编辑；**手编契约**：scope/plan_id 不可变（载荷与现值不符即拒）、raw_source_text 永不变（编辑忽略传入）；source 由 actor 派生（human→user/ai→ai_derived 溯源不可伪造）；scope=global 受预算双指标（仅拦 ai）。拒绝=budget_exceeded（data 全量摘要）/invalid_request（契约字段不符）。向量指针：toolface tf_upsert_background_ok/tf_merge_backgrounds/tj_budget_exceeded。基线：plan_background PLAN_S1。

### merge_backgrounds（归并原子批处理，人/AI）

ops 数组（delete={id}/upsert={...}）**单事务执行、终态校验全过才落库否则整批拒**（防「先插 C 再被拦」死锁与「先删 A」有损中间态）；预算终态校验仍仅拦 ai。拒绝=budget_exceeded/invalid_request（kind 非法/delete 缺 id/upsert 缺 content）。向量指针：toolface tf_merge_backgrounds/tj_budget_exceeded。基线：plan_background（归并后）。

### upsert_facts（事实凭证写，人/AI 双通道）

id 缺省=建档（**raw 建档零解析恒 verbal/verbal 占位**——命令层拒非占位的 raw 建档，出生确认首次回填定终值）；id 给定=编辑（字段级 merge 只覆盖传入槽位，**raw_text 追加双段只增不清**——改签 SOP 溯源）；badge 超 12 字符截断（截断优于拒绝）；heroMetrics ≤3 截前 3；category/source_kind/origin/captured_by 出生不可变（编辑忽略）；作废=state=voided（存证保留）。拒绝=invalid_request（缺 title/raw 建档非占位/rule 类缺 rule_scope）。向量指针：toolface tf_upsert_facts_ok（raw 占位出生）。基线：plan_facts PLAN_S1（凭证行形态）。

### delete_artifact（凭证物理删除，human 专属）

误分享文本清理；退票/作废走 upsert_facts state=voided（存证保留）；**AI 无删除权只可提议**；删凭证绝不删块——关联块失去挂载并从 pinned 降格（同事务 §1.4 反向销毁防护）。拒绝=not_found/forbidden（AI）。基线：plan_facts PLAN_S2（删除后空态）。
