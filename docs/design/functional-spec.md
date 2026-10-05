---
status: active
updated: 2026-10-05
---

# 拾光功能设计（施工蓝图）

> 上游 SSOT：[schedule-app.md](schedule-app.md)（十四轮评审冻结稿）。本文档只做机械翻译：功能清单 → 页面分解 → 命令清单 → 里程碑切片。**本文档不引入任何新机制**；实现中发现设计缺口，回上游文档补拍板记录。

## 1. 里程碑切片

### M1 行走骨架（可用闭环，可演示）

- flutter create + dev-init（context 框架/workflow/index/toolbox）
- 三表 SQLite + Repository + 命令层（FIFO 锁/乐观锁/CommandActor.human|ai）
- MCP 服务框架层搬运（jsonrpc/mcp_server，instructions+十工具 schema 全量注册，prompts 先空实现）
- UI：首启引导 3 步、快记条（title+星+可选截止）、清单三分区、日程日视图（点空槽创建/点块表单编辑/确认三键/单块+30min）
- 工具链路：list_plans/add_plan/update_plan/get_settings/get_schedule/propose_schedule/get_history（基础聚合）+ stdio-bridge 联调
- **验收**：桌面 AI「帮我排明天」→ 提案写回 → app 确认 → 勾选 → get_history 看到聚合。全链路无 UI 也可跑（MCP 优先，Human-AI 对称性从第一天成立）

### M2 减震系统（机械防线全量）

- Housekeeper：日切（wake_time 切割）、日切扫描（missed/作废）、顺延记账（postpone_count）、悬空巡检（含 roadmap 步过窗未完成→digest 紧急段+强制实例化带 deadline 叶块）、晨间 digest（孤儿/悬空两段）
- 块状态机全量：proposed/confirmed/done/skipped/missed/archived/melted + execution_quality(full/spark)
- 机械校验全量：填充率 60%（low 电量 25%）、呼吸律 45/15、deep 禁排（low 电量；火种豁免刻度=is_day_spark 块 ≤15min 且执行内容取 min_viable_action）、和平条款（human/pinned 不可覆盖）、available_free_windows 拒绝返回
- pinned/source 字段生效；乐观锁冲突回快照
- 剩余三工具链路联调：`update_settings`（user_rules/填充率上限等可调项）、`update_fixed_slots`（一周节奏模板改）、`adjust_blocks`（AI 建/挪/缩/删单块——火车行程入口、熔断同效动作；受和平条款门控）
- **验收**：故意打乱一天→次晨遗留正确呈现；AI 撞 human 块被拒且收到空窗列表

### M3 心理层（护航与庆祝）

- get_history 校准指标全量（膨胀系数/时段热力/耐受阈值/深潜净值/能量回血）
- 分级确认（火种突出+Routine 折叠）、双轨仪表盘、安全线徽章（含漫游态见证式判定）、能量补给带/自由流动区派生渲染、中性文案规范落地
- 庆祝块全链路（reward_spec 收集→香槟金渲染→豁免→能量回血）、Clean Slate 静默保护、冷藏池
- 护航式提案叙事 + protection_manifesto + playbook 初版五文件（clarify/decompose/schedule/govern/dump，含多人假设标注、叙事双语气）+ 金样本评审跑通（#1 有个考试 + #2 全家爬山）
- **验收**：金样本「有个考试」全流程 + 断流 3 日后 recovery 握手体验

### M4 现实接口（物理拼图）

- 日历只读投影（device_calendar，内存硬墙）、天气投影（无 key JSON 源、内存 TTL 缓存、get_schedule 返回体搭载、settings.weather_location 手动城市）、settings.exceptions 例外日（旅行模式：fixed_slots 挂起+填充率 35%；夜块走手动/记账通道，睡眠包络不漂移）
- today_energy 三档（含电量晚点选触发当日水流降档重算）、Landing Gear 依时态抽屉、逆向记账（事实通道）、换乘、四形态渲染、三手势、放工守卫、三日水位线、简易周视图（只读 7 列、复用 BlockRenderer、点块跳日视图）
- 大脑倾倒 SOP 联调、JSON 导出、mDNS 自动发现（bridge+NSD）
- **验收**：真机全场景走查（日常/混乱/低电量/旅途四天型）

### V1.5 候补（拍板在案，不进 MVP）

拖拽时间轴、本地通知提醒、Markdown 导出、pinned 块推送系统日历（独立「拾光」本地日历+读投影过滤防回环）、spec 路线图 UI 化编辑

## 2. 页面与功能清单

### 首启引导（一次性，3 步）

①作息边界（必填不可跳）→②一周节奏（fixed_slots 模板：上班族/学生/自由职业，可空过）→③排程偏好（粒度/单日排量上限）。完成即写 settings+fixed_slots；未完成时 propose 硬拒。

### 今日页（执行主场）

| 区域 | 功能 | 机制出处 |
|---|---|---|
| 顶部仪表盘 | 🔥火种 N 项｜🛡️自由保护 X 小时｜安全线徽章（火种 done/spark 点亮） | 八轮 |
| 昨日遗留区 | 日切 missed 逐条处置：顺延/放弃/补勾 | 一轮 |
| 电量点选 | ⚡/🔋/🪫 三档，日切重置 | 十轮 |
| 时间轴 | 四形态渲染、空槽点按创建（新建/从清单选）、点块浮层（Landing Gear/roadmap/notes）、确认三键（确认/否决/改时间）、+30min | 一/七/十二/十四轮 |
| 周视图切换 | 日/周顶部切换；周=只读 7 列网格（AI 块+human 块+fixed_slots+日历投影同栅渲染），点块跳日视图 | 2026-10-05 拍板 |
| 手势 | 长按坍缩/左滑换乘/右滑融化 | 十四轮 |
| 全局动作 | 现实熔断键（清空今日/下午 AI 块）、「今天状态差」降级入口 | 五/六轮 |
| 放工守卫 | sleep 前 2h 转静默视图；**快记条不关门** | 八轮 |
| 快记条 | title+重要星+可选截止，零门槛常驻 | 全局 |

### 清单页（灵魂的家）

- 三分区：今天 / 当下推进中（有未来块）/ 待安排（按四象限分组，空组隐藏；冷藏池折叠区）
- 条目卡片：title+象限标记+「待确认 N 项」角标+火种/庆祝标记；点开详情：spec（含 roadmap 当前进度）、notes、open_items（可手答）、reward_spec、子树（≤3 级缩进）、「排期」快捷动作
- 待安排条目长按：归档/删除/挂起

### 设置页

作息边界、一周节奏（fixed_slots 工作日/周末两套模板增删改——首启后唯一编辑入口）、最小块粒度、单日排量上限、user_rules（自然语言编辑）、例外日管理（增删改日期范围）、MCP 服务（状态灯/局域网 IP/一键唤醒/token）、数据导出 JSON。

## 3. 命令清单（命令层，CommandActor.human|ai 共用）

| 命令 | 触发方 | 说明 |
|---|---|---|
| QuickCapture | 人 | 快记→plan（默认 light/anywhere/愿望池） |
| UpsertPlan / UpdatePlan(patch) | 人/AI | 计划增改；AI 走 MCP add_plan/update_plan |
| ProposeSchedule | AI | 整天原子写，全量机械校验 |
| ConfirmBlock / RejectBlock / AdjustBlockTime | 人 | 确认三键 |
| ShiftBlock(+30min) | 人 | 级联顺延 AI 块 |
| DegradeBlock(spark) | 人/AI | 坍缩/降级，文本切 min_viable_action |
| SwapBlock | 人 | 换乘（app 内挑 light 候选） |
| MeltBlock / PanicClear | 人 | 右滑融化 / 熔断键批量 |
| TickBlock / RetroLog | 人 | 打勾（触发接棒检查）/ 逆向记账（事实通道） |
| UpdateSettings / UpdateFixedSlots / UpdateExceptions | 人/AI | 配置写 |
| AI 同域写路径 | AI | 经 MCP 工具映射到上述命令，CommandActor.ai 门控 |

## 4. 非功能约束

- 本地优先：全功能离线可用；MCP 仅 LAN
- 性能：日视图滚动 60fps；list 查询走派生视图 SQL；启动 <2s
- 权限：日历只读（失败静默降级）；天气无 key JSON 源+手动城市，零定位权限；通知 V1.5 才申请
- 数据：导出 JSON 全量；无任何遥测
