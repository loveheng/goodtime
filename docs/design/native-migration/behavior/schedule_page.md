---
status: active
updated: 2026-11-15
---

# 行为清单 · schedule_page（今日页 + 周视图 + 简易月历，P4③主干页施工输入，已过目转正 2026-11-15）

> §9.1.6 模板产出（2026-11-15 `::auto` 抽取；**已过目转正 2026-11-15——D13 三问全「同意默认」**：P1=规范为准补实现随 N4 ｜ P2=修规范对齐实现（ui-pages §3.1 同轮修订为「紧凑三档横排」）｜ P3=查询面补口已完成（gapsIn/weekOverview/monthOverview/TimelineMath，23 测全绿）｜ P4=按 D12 处置随 N2/N3 ｜ P5=字面量平移现状（15min 吸附/2h 守卫窗不随设置联动）——生成通道可作施工输入）。抽取源 = `docs/design/ui-pages.md` §3/§4/§8.1–8.2/§9.1/§9.3 + `docs/project/ui-design.md` §3（动效/手势/层叠）+ `lib/ui/schedule_page.dart` 实现体诊断（2191 行全文）+ `lib/widgets/now_next_card.dart` + `lib/action/queries.dart`（weekOverview/monthOverview 段）+ `lib/util/schedule_day.dart`（纯函数）。范围 = 今日页纵向全件（三段切换/双轨仪表盘/NowNext/确认卡/时间轴/遗留区/守卫态/空态）+ 周视图 + 简易月历 + 三视图数据投影；块卡四形态×手势已由 `block_renderer.md` 登记完毕（交界指针见 §F 头注），清单页归 `plans_page.md`（清单页施工时另抽）。

## 七字段

| 字段 | 内容 |
|---|---|
| 交互意图 | 今日页=执行主场：一眼看清今天推进什么（🔥核心×🛡️留白×🏅底线）+ AI 提案页内低成本确认 + 时间轴上推进块（打卡/顺延/换乘/融化/坍缩）；周/月=只读账本（历史回看、点选跳日，同源派生投影零第二真相） |
| 触发与阈值 | 见 §A–§F 行为细节表（逐字 StGesture/StScale/StMotion/StUi token 名，禁现推） |
| 状态空间 | 今日页=A 档 S1–S6（ui-pages §8.2：S1 正常/S2 空态+守卫/S3 边界（跨午夜/最小块/保护区三级）/S4 手势中间态→交互对拍断言不采图/S5 浮层+IME/S6 冲突错误）；周/月=只读派生视图，随日视图跳转无独立态枚举（C/D 档 2 态口径）；门控矩阵见 §G |
| 落库命令与拒绝码 | `tick_block`/`postpone_block`/`melt_block`（遗留区三键）+ `confirm_block`/`reject_block`（确认卡批量，单块冲突不中断）+ `swap_block`/`degrade_block`/`melt_block`/`restore_block`（手势，block_renderer.md 已登记）+ `update_settings`（today_energy）+ `reflow_day`（低电量降档重算）；拒绝码逐字 = commands_registry.md「领域拒绝码表」7 码，禁翻译改写 |
| 反馈形式 | ① 命令成功 SnackBar 2s（`r.note`）——融化撤销钮升 5s floating+FAB 净空（block_renderer.md ②）；② 遗留区/确认卡失败 `ActionException.message` 直显 SnackBar；③ 电量降档 SnackBar 3s（`r.note ?? '已降档'`）；④ 换乘空池轻提示不开抽屉；⑤ 否决原因弹框（可选输入，取消=不动）；时长全走 ShiguangScaffold 四档（丢失点 #1 内建件） |
| 黄金向量指针 | toolface 对拍（GoldenQueriesTest：getSchedule/weekOverview/monthOverview 投影 case）+ 拒绝码族 + time-basis（goNow/NowNext 的 now 语义=本地物理时钟，main 2026-10-15 拍板） |
| 像素基线指针 | A 档 10 组：schedule_page × {S1,S2,S3,S5,S6} + fact_sheet × {S1,S2,S3,S5,S6}（ui-pages §8.2）；manifest = `test/golden/baseline/manifest.json`；S4 交互对拍断言 2 项（呼吸态/右滑吸附临界，真机轮） |

## §A 页面骨架与导航

| 行为 | 口径 |
|---|---|
| 顶部三段切换 | `日｜周｜月` segmented（顶行左段 Expanded）+ 右侧协同日程入口（图标钮 tooltip「协同日程」→ MemberCalendarPage，路由归 routing_map） |
| 视图横滑翻页 | 累计位移 `≥ StGesture.pageSwipeDp`（60dp）不设速度门槛；右滑=回退一周期；仅 day（±1 日）/month（±1 月，锚日钳月末）生效；week 横滑归列内容滚动（112dp×7 列），翻周走 ‹› 钮；块卡片手势优先（竞技场内层胜，block_renderer.md）；边缘 24dp 系统手势保留区随主干页轮（丢失点 #8） |
| 翻日 | header `‹ 前一天`/`后一天 ›` 文字钮（compact 密度）+ 横滑；日切换复位确认卡展开态 |
| 回到现在 | header 文字钮（仅今日）→ goNow(animate: true)；时间轴首帧自动 goNow（§F） |
| 回到今天 | 非今日时日期行下「回到今天」文字钮 → 选中日=今天+确认卡复位 |
| 日期行 | `今天 · M月D日 周X`（非今日=`日程 · …`，完整显示禁 ellipsis）+ 今日有火种时附 `🔥 今日核心 1 项`（primary 色 bodySmall） |

## §B 今日页数据装配（DayData）

| 行为 | 口径 |
|---|---|
| 作息窗 | `wake = settings['wake_time'] ?? 420`、`sleep = settings['sleep_time'] ?? 1380`；`sleepAdj = sleep ≤ wake ? sleep+1440 : sleep`（跨午夜聚焦窗终点 >1440） |
| 作息日「今天」 | `scheduleDayOf(now, wake)`——凌晨（<wake）归昨日；选中日未显式设置时跟随作息日今天 |
| 单查询分流 | `blocksInRange(昨日ISO, 选中ISO)` 一次取回：date==选中日 → 今日块；昨日且 status==missed → 遗留区处置面（§G） |
| 关联凭证 | blockId → artifact（最多一枚/块），供 🎫 微标与块浮层通关卡同源 |
| 派生旗标 | `cleanSlate = initialized==true && isBreakdown()`（断流保护期）；`roam = exceptions 覆盖选中日`（漫游主题态触发之一）；`energy = today_energy ?? 'normal'` |
| 派生 getter | spark=首个 isDaySpark 且非 melted；proposedAi=source==ai 且 status==proposed 全集；safelineLit=存在 isDaySpark 且 status==done；visibleBlocks=status≠melted（archived 淡出仍占位） |
| 占用区间 | visibleBlocks span + fixedOnDate（跨午夜钳 1440）+ fixedSpillover(0,endMin)——保护区/自由留白/周月共用 |

## §C 双轨仪表盘条（ui-pages §3.1）

| 行为 | 口径 |
|---|---|
| 三段并列 | `🔥 今日核心 N 项`（N=非 melted 火种数）｜`🛡️ 自由留白 X.Xh`（freeMinutesIn(wake,sleepAdj,占用) ÷60 一位小数）｜安全线徽章——窄屏各段 ellipsis 有界，信息完整优先于两端对齐 |
| 安全线徽章 | 点亮=`今日底线守住啦`（StColors.safelineOn 金）/未点亮=`底线待守`（safelineOff 灰）；**规范形态=徽章件（未点亮灰 outline/点亮金色填充）+点亮单次微动效——spring 单次 dampingRatio 0.75/stiffness 400（StMotion.springDampingRatio/springStiffness），感知窗 ≈250ms（StMotion.safelineMs）**；实现现状=着色静态文本无动效（对拍 P1） |
| NowNext 卡 | 今日顶部挂载（repo 监听+每分钟墙钟 tick）：`进行中: <label> HH:MM–HH:MM`/`下一个: …`；判定=pickNowAndNext 纯函数——仅 pending（非 done/skipped/missed/archived/melted），current=罩住 now 者取最晚开始（跨午夜含：now≥start ∥ now<end），next=不罩 now 且 startMin>now 最早者；昨夜跨午夜块仅当仍罩住 now（今晨未结束）才算进行中；任一侧缺失隐藏该行 |

## §D 电量三档（仪表盘旁列）

| 行为 | 口径 |
|---|---|
| 三档 | ⚡ high「满血」/🔋 normal「平稳」/🪫 low「低电量（自动降档）」——手动点选、永不接传感器；当前档 primary 高亮、同档禁点 |
| 降档联动 | 点 🪫 → `update_settings{today_energy: low}` + `reflow_day`（水流确定性重算，含晚点选场景）→ SnackBar 3s（`r.note ?? '已降档'`）；日切重置为 🔋（housekeeper 域） |
| 形态偏差 | 规范=「电量三档 segmented」；实现=紧凑 IconButton 行（窄屏 363dp 信息完整优先）（对拍 P2） |

## §E 分级确认卡（§3.4 两态收缩）

| 行为 | 口径 |
|---|---|
| 触发条件 | 今日 && proposedAi 非空（历史日回看不渲染提案卡） |
| 展开态 | 火种突出：`🔥 今日核心` + `「label」 HH:MM–HH:MM` + 理由句「这是今天最重要的一件事，其余都为它让路。」；无火种=标题 `AI 排程建议 · N 项`；`其余 N 项日常推进` 折叠副行；三键=`全部确认`（filled）/`全部否决`（outlined，先弹原因弹框可选输入）/提示文`逐块调整：点时间轴上的提案块` |
| 收缩态 | 44dp 吸顶胶囊（blockMinHeightDp）`⚡ AI 排程建议：[重点攻坚 1 项，]顺带推进 N 项 · 点击查看理由` + `全部确认` 快捷通道；点胶囊展开 |
| 滚动驱动 | NotificationListener 捕时间轴竖滚：forward → 收缩；reverse 且 pixels<24 → 展开；返回 false 不拦截滚动；手动收起钮同通道 |
| 批量语义 | 逐块顺序执行 confirm_block（expectedVersion）/reject_block（reason 可空）；单块 ActionException 吞错不中断——失败块留提案态逐块处理（点块浮层）；busy 期按钮禁用 |

## §F 时间轴（§3.5 主体）

> 块卡本体（四形态/角标/三手势）= block_renderer.md 已登记，本节只登记时间轴装配语境与几何。

| 行为 | 口径 |
|---|---|
| 聚焦窗与像素 | 竖轴 = wake−0h ~ sleepAdj；1 分钟 = StScale.dpPerMinute（1.0dp）；窗高 = (sleepAdj−wake)×dpPerMinute； gutter 52dp 时钟列（画布宽 = 容器宽 − 52 − 16）；块高 = span×dpPerMinute 钳下限 StScale.blockMinHeightDp（44dp） |
| goNow（丢失点 #3） | `target = (now − wake)×dpPerMinute − viewport/2`，钳 `[0, max(0, 窗高−viewport)]`；animate 250ms easeOut / 定位 jumpTo；仅今日首帧后自动定位，历史日从窗首；窗外（凌晨/深夜）钳到首尾——**像素换算与钳制公式需 JVM 单测钉死** |
| 小时网格 | 整点自 (wake 整点后第一个整点) 起、步进 60min 至 sleepAdj：gutter 时钟 label（textSecondary labelSmall）+ 全宽 1dp 分隔线 |
| 时刻线（F10） | 当前时刻=主题色 1dp 细线（不用红）横贯画布——**规范有实现无，D12 已裁决补实现随③施工** |
| 昨夜带（F11） | 跨午夜块按开始日归属不拆段（S3 定义）；跨午夜块头部渲染顶部「昨夜」灰带——**规范有实现无，D12 已裁决补实现随③施工** |
| 固定占用带 | fixedOnDate（跨午夜钳 1440、end>wake、start<wake 钳 wake）+ fixedSpillover(0,endMin)：fixedSlotBg 圆角 radiusBlock 底+名称 label（labelSmall textSecondary） |
| 派生保护区 | 🛡️ 留白缓冲=AI 块 span>ScheduleRules.breathMin(45) 后 breathBufferMin(15) 呼吸位——对占用取差集（现实挤进缓冲只渲染自由部分）；🍃 自由流动区=占用+补给带之外空隙；三级绘制过滤 bandFullMin=30 全渲染 `🛡️ 留白缓冲 N 分钟`/bandIconMin=15 仅图标（🛡️/🍃）/<15 静默；**规范=底色+虚线框（含 freeFlow），实现=supply 实线 0.6dp/freeFlow 无框——F9 已裁决补虚线框随③施工** |
| 车道分栏 | 可见块按 startMin 排序切重叠簇（簇内任一块开始 ≥ 前簇最大结束则切簇）；簇内 greedy 车道（排进第一条「车道末 ≤ 本块开始」的车道，跨午夜 endAbs=end≤start?end+1440:end）；等宽分栏 laneWidth=画布宽/最大车道数，块宽−2 留缝（ui-design §3.4 D12 写回：等宽 N≥2） |
| 空槽点按 | 背景点击 → minute=wake+round(dy/dpPerMinute)，15min 吸附钳 `[wake, sleepAdj−15]`；过去时段（今日 && snapped<now）→ 逆向记账 sheet（「刚才做了什么」），未来 → 新建事项 sheet（提名「新建事项/从清单选」）——sheet 本体归 ④浮层族，本件登记触发语义 |

## §G 遗留区 / 守卫态 / 空态（门控矩阵）

| 行为 | 口径 |
|---|---|
| 昨日遗留区 | 渲染门控=今日 && 非 windDown && 非 cleanSlate && leftovers 非空（有 missed 才渲染空态消失）；missedAccent 米黄卡（绝不红）；标题`昨日遗留 · N 条`；逐条 `label · HH:MM–HH:MM` 三键=补勾（tick_block，自动带补记标）/顺延今日（postpone_block date=今日）/退回愿望池（melt_block）；断流保护期整区转 archived 淡出视图（cleanSlate 门控） |
| 放工守卫 | 触发=今日 && sleepAdj−120 ≤ now < sleepAdj（2h 窗字面量）：遗留区/清单入口/推进提示隐藏，转 `_WindDownBanner`=`放工了：今天完成 N 项 / M 分钟。` + 明日概要（明日非 melted 块数+最早一块 label 解析同块浮层口径）：`明天有 N 项安排，最早 HH:MM「label」。`/count=0：`明天还没有安排——想到什么随时记，快记条不打烊。`；快记永不关门（FAB 恒在，app_shell 常驻不撤） |
| Clean Slate | cleanSlate && 今日 → `轻装重启：生活有时需要喘息，过去的事已安全归档，今天从一件小事开始。`（surfaceContainerHighest 底）；与守卫横幅互斥（守卫优先） |
| 临场通关条 | 今日 && dailyActionPasses 非空：系统 Banner 下、遗留区上 ActionPassBar（点条 → 凭证 sheet）；本地物理时钟判定 |
| 空态 | 今日：`留白的一天，自由支配时间已备好。`+`想让 AI 排一排？打开桌面 AI 说「帮我排今天」`+tonal 按钮`自己放一件事进来`（→ 新建 sheet）；历史日：`这一天没有日程块。` |

## §H 周视图（§4 只读）

| 行为 | 口径 |
|---|---|
| 数据 | weekOverview(anchor)：含选中日所在周周一..周日；wake/sleepAdj 同源 settings；每日=块全集（effective_label=label??plan.title）+ fixedOnDate/Spillover 投射 |
| 头部 | ‹ `M月D日 – M月D日` › 翻周（±7 天）；列头 `D日 周X`（今日 primary+bold）+ 例外日 `🏖` |
| 列体 | 列宽 112dp 横向可滚；列时间窗同日视图聚焦窗；小时分隔线（无时钟 label）；fixed 带纯色无 label；块=紧凑卡：source==human→humanBlockBg 否则 primaryContainer、proposed 主色 1dp 边、celebration 庆祝描边、label 9pt 单行 2 行截断、块高钳下限 24dp；车道 greedy 分栏同日视图算法 |
| 交互 | 点块 → 跳日视图定位该日（onPick date）；零编辑零手势零浮层；翻周仅 ‹› 钮 |

**实现级微度量（N6 抽取，2026-11-15，待人工过目；值源=实现现状逐字）**：
- 布局：列头行高+28dp；双滚容器（外层竖滚 bottom=fabClearanceDp、内层横滚总宽=112×7）；列内 Stack 窗高=window×dpPerMinute；小时线自 (wake 整点后第一个整点) 步进 60 至窗尾（Divider 1dp 无 label）；fixed 带=纯色 fixedSlotBg 无圆角无 label（onDate top 钳窗内、spillover top=0）；块高钳下限 24dp。
- 紧凑块：圆角 6、margin 0.5+padding 2；着色 source==human→humanBlockBg 否则 primaryContainer；proposed→primary 1dp 边 / is_celebration→celebrationStroke 1dp 边（互斥，proposed 优先）；label fontSize 9/height 1.1/onPrimaryContainer，maxLines 2 ellipsis，空 label 回落「(未命名)」；车道 laneWidth−1 留缝。
- 车道 greedy（列内独立实现，同日视图算法语义）：按 startMin 排序扫车道（laneEnds[lane]>start 则右移），endAbs=跨午夜+1440；laneWidth=列宽/最大车道数。
- 头部：‹ chevron_left「上一周」/右 chevron_right「下一周」+ 居中 titleMedium「M月D日 – M月D日」（weekStart/weekEnd=周一/周日）。

## §H.1 月历实现级微度量（同批抽取，待人工过目）

- 网格：GridView crossAxisCount 7/childAspectRatio 0.82/padding (4,4,4,fabClearanceDp)；leadingBlanks=首日 weekday−1（周一起始）；格子 margin 2+padding 3、圆角 radiusBlock；今日=primary 1.4dp 描边圈+bold。
- 点阵：点径 7dp 圆形（Wrap spacing 2/runSpacing 2）；顺序 done(safelineOn 实心)→missed(safelineOff)→proposed(primary)→human(textSecondary)；超出 4 收敛 `+N`（fontSize 8/height 1.2）；**P7 对拍发现：§I 规范 proposed=primary 空心，实现=全实心**（空心属视觉缺位待裁决）。
- 微标：🔥（spark_done）/🎂（celebration）fontSize 9；例外日小标签 fontSize 8/height 1.2 tertiary 单行截断；空日期号 textSecondary。
- 头部：‹「上一月」/「下一月」+ 居中「YYYY年M月」；翻月钮=保留几号钳月末（31→月末）；周头「一 二 三 四 五 六 日」（horizontal padding 8）+Divider。
- 横滑：month 视图横滑翻月 selected=目标月 1 号（不保留几号）。

## §I 简易月历（§4 只读）

| 行为 | 口径 |
|---|---|
| 数据 | monthOverview(year,month)：单月 blocksInRange 单查询；melted 不进月历；按日聚合 done/missed/proposed/human 计数 + spark_done（isDaySpark&&done）/celebration/exception 标签（多例外「、」连接） |
| 头部 | ‹ `YYYY年M月` › 翻月（保留「几号」，月末钳制：31→钳到目标月末）；周头 `一 二 三 四 五 六 日`（周一起始） |
| 格子 | 日期号（今日 primary+bold+1.4dp 描边圈）+ 状态点阵（done=safelineOn 实心/missed=safelineOff 灰/proposed=primary 实心/human=textSecondary 灰；超出 4 点收敛 `+N`；P7 裁决 2026-11-15 修规范对齐实现——原「空心」为未落地视觉缺位）+ 🔥（spark_done）/🎂（celebration）微标 + 例外日小标签（tertiary 8pt 单行截断） |
| 交互 | 点格 → 跳日视图（日视图日期参数化，可回看历史账本）；格子摘要渲染不承载块全量 |

## §J 三视图同源纪律与数据面缺口

- 同源纪律（ui-pages §4）：三视图与三日水位线同源——同一 SQLite 派生投影（blocksInRange 单查询分组），零第二真相；全部只读（编辑走日视图表单/手势）。
- **Kotlin 查询缺口（对拍 P3）**：`:core:action` ScheduleQueries 现有 getSettings/getHistory/getSchedule/isBreakdown，**weekOverview/monthOverview 未平移**；`freeMinutesIn`/`gapsIn` 纯函数（schedule_day.dart）未平移——随周月节点先补查询面+JVM 对拍测试，再接 UI。
- pickNowAndNext/containsMinute（now_next_card.dart）纯函数可 JVM 直译单测。

## 对拍发现（P1–P5，D13 已裁决 2026-11-15）

| # | 发现 | 处置（已裁决） |
|---|---|---|
| P1 | 安全线徽章规范=徽章件（未点亮灰 outline/点亮金色填充）+点亮单次 spring 微动效（dampingRatio 0.75/stiffness 400/safelineMs 250）；实现=着色静态文本、无动效、无 🏅 徽章形 | 规范为准补徽章形态+spring 动效（随 N4 施工，StMotion 三 token 逐字取用） |
| P2 | 电量三档规范=「segmented」；实现=紧凑 IconButton 行（⚡🔋🪫 横排，窄屏 363dp 信息完整优先） | **修规范对齐实现**——ui-pages §3.1 已同轮修订为「紧凑三档横排」，Kotlin 照实现平移 |
| P3 | Kotlin 查询缺口：weekOverview/monthOverview/freeMinutesIn/gapsIn/pickNowAndNext 未平移 | **已完成**（2026-11-15）：gapsIn/freeMinutesIn（前轮已在）/weekOverview/monthOverview/TimelineMath 三件+23 测全绿；pickNowAndNext 随 NowNext 节点 |
| P4 | F9 虚线框（含 freeFlow）/F10 时刻线/F11 昨夜带：D12 已裁决「补实现，随③施工」 | 按 D12 处置执行（时间轴层，N2/N3 节点） |
| P5 | 时间轴 15min 吸附与守卫 2h 窗为实现字面量（非 token、不随最小块粒度设置联动；设置只约束 propose 机械校验） | **平移现状**（UI 吸附与 propose 粒度解耦）；如需 token 化/设置联动另立提案 |
