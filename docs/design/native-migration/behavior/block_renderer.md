---
status: active
updated: 2026-11-15
---

# 行为清单 · BlockRenderer 组件族（P4②，已过目转正 2026-11-15）

> §9.1.6 模板产出（2026-11-15 `::auto night` 抽取；**已过目转正 2026-11-15——pending-confirm D12 四问全默认通过，F1–F12 处置见对拍发现表**，生成通道可作施工输入）。抽取源 = `docs/project/ui-design.md` §5 四层矩阵 + §3.4 手势竞技场 + §4 术语映射 + `docs/design/ui-pages.md` §3.5/§6/§8.2/§9.3 + `lib/ui/schedule_page.dart` 实现体**诊断草稿**（`_BlockCard`/`_GestureBlock`/时间轴挂载段）。范围 = 块卡片四形态×状态×角标 + 手势外挂 + 时间轴挂载语境；页面编排（今日页纵向结构/空槽点按提名/月视图）归主干页③，本件只登记交界指针。

## 七字段核（§9.1.6）

| 字段 | 内容 |
|---|---|
| 交互意图 | 「任何块在任何页面渲染」的形态契约 + 块级三手势命令入口：用户在一眼内读懂块的身份与状态，手势全部映射既有命令（零新语义） |
| 触发与阈值 | `StGesture` 逐字（`:core:tokens` StTokens.kt 已直译）：横滑锁 `horizontalSlopDp=20` + `axisRatioMin=2.0`；右滑融化 `meltFraction=0.45`、左滑换乘 `swapFraction=0.35`；长按 `longPressMs=400` + `longPressScale=0.98`；背景翻页 `pageSwipeDp=60`；保护区三级 `bandFullMin=30`/`bandIconMin=15`。触觉：融化/换乘过卡点均 `HapticFeedback.mediumImpact`（对应 Compose `HapticFeedbackType.LongPress` 级，施工时按平台枚举对位） |
| 状态空间 | 今日页 A 档 S1–S6（ui-pages §8.2，不新增）；块级状态层 7 态见下「状态层」。S4 手势中间态**不采图**→交互对拍断言 2 项（0.98 呼吸态 + 45% 吸附临界，P4 todo 既定） |
| 落库命令与拒绝码 | 手势→命令：`degrade_block`（长按，仅 AI 块）/ `melt_block`（右滑）/ `swap_block`（左滑显式挑选后）/ `restore_block`（融化撤销）。块操作族拒绝码：`not_found`（块不存在）/ `version_conflict`（乐观锁，data 回 latest）/ `invalid_request`（终态块不可融化等）；呈现层不直接触达其余码。命令面语义归 commands_registry.md（本件不复读） |
| 反馈形式 | ① 完成提示 SnackBar 2s（`r.note`，命令侧文案=「已暂缓，放回清单待安排（无痕，零心理负债）」等，引 ui-pages §9.3）；② 融化撤销钮 → 同 SnackBar 升 5s **floating + FAB 净空**（margin 16,0,16,`fabClearanceDp`），点按 `restore_block` 后「已恢复」2s；③ 换乘空池不开抽屉、轻提示一条；④ 过卡点意图提示浮层（见手势层）；⑤ 失败 `ActionException.message` 直显 SnackBar。时长全部走 ShiguangScaffold 自建四档（丢失点 #1 内建件） |
| 黄金向量指针 | 呈现层不适用；手势触达的命令面由既有向量集覆盖——`test/golden/README.md`（toolface 23 case / mcp_rejection 17 case）；S4 断言的拒绝码口径与 §8.2 一致 |
| 像素基线指针 | `test/golden/baseline/manifest.json`：`schedule_page_S1/S2/S3` 已入存量（31 组内）；A 档剩余 S5/S6 挂账（84 组缺口）；S4 不采图 |

## 形态层（§5.1 四形态 + 实现 precedence）

判定序（**庆祝 > 火种 > 深度 > 漫游 > human > 默认**，实现诊断草稿与规范一致，施工按此优先级）：

| 形态 | 触发 | 视觉（token 名逐字） |
|---|---|---|
| 庆祝勋章态 | `is_celebration` | `celebrationBg` 实底 + `celebrationStroke` 边 + textPrimary；🎂 右上；过期→archived 淡出，绝不显红 |
| 微型火种态 | `executionQuality=spark` | `sparkBg`+`sparkStroke` 描边窄胶囊；文字切 `plan.min_viable_action`（缺则 label/title/(未命名)）；无倒计时无进度条；物理 5min 渲染经 §3.1 钳制 min 44dp |
| 深度攻坚态 | 非 human 且 `plan.energyLevel=deep` 且 `data.energy≠low` 且非 spark | `deepFocusBg` 实底+`deepFocusText` 白字；右下尾注「[+15m 呼吸留白]」8sp α0.8 |
| 漫游主题态 | 例外日（`data.roam`）或时长 ≥180min | `roamBg` 弱底+`roamStroke` 弱框；起止 `HH:MM~HH:MM`（`~` 仅渲染层，数据精确起止） |
| human 默认 | `source=human` | `humanBlockBg`；无主色时 `primaryContainer`/`onPrimaryContainer` 兜底 |

块内文本：`HH:MM ` 前缀 + label（maxLines 2, ellipsis, 12sp）；时间与标题拼接单 Text。

## 状态层（§5.2 七态 → 叠加）

| 状态 | 渲染行为 |
|---|---|
| proposed | 边框 1.2dp 主色（虚线语义见对拍 F1）+ 右上「提案」chip（9sp 白字胶囊） |
| confirmed | 默认实线无叠加 |
| done | 右下 ✓ 圆标 14dp `scheme.primary`，原色不灰化不庆祝 |
| skipped | 文本 lineThrough（⊘ 语义差异见对拍 F2） |
| missed | **今日时间轴现状无 missed 专属叠加**——昨日 missed 走遗留区处置面（米黄标+三键）。规范叠加（降饱和 40%+左米黄 4dp 标+尾注「未打卡」）适用面见对拍 F3 |
| archived | Opacity 0.15 + `onTap=null` 禁交互（仍占位） |
| melted | **不渲染**（`visibleBlocks` 过滤，仅水流摘要提及） |

## 来源角标（§5.3，右上错位链）

角标共存偏移（实现诊断）：提案 chip 右上 top0；🎂 在提案存在时右移 40dp；📌 在提案/庆祝存在时依次错位（40/16/0dp）；📋「来自清单」左上（`planId!=null && !pinned`）；🎫 凭证微标左下挂角（planId 徽标下方 top12），票形 emoji 恒显、badge 文本非空才显（**maxWidth 见对拍 F4**）+ellipsis；微标不挡块主文本。投影块（灰底+外部图标+禁手势）现状未见（对拍 F5）。

## 手势层（§5.4 + §3.4 竞技场）

**挂载闸**（实现诊断）：`canSwipe = status ∈ {proposed, confirmed}`；`canDegrade = source=AI && status ∈ {proposed, confirmed}`；两者皆否不挂手势层（human 块长按不可达=「human 块不可坍缩」的 UI 闸）。

| 手势 | 触发与执行序列 |
|---|---|
| 轻点 | 打开块浮层（block_sheet，浮层族④——本件只登记指针） |
| 长按 | `longPressMs=400` → `degrade_block(expectedVersion)`；按住期间 scale 0.98 呼吸态**现状未实现**（对拍 F6，S4 断言 1 依赖）；触发后 300ms 坍缩动画属动效三处首次实现（P4 todo，非对拍件） |
| 右滑融化 | 手势锁过→`_dx` 跟手（Transform.translate 平移不重排）；过 `0.45×块宽` 出意图提示层（`freeFlowBg` 左对齐「暂缓并收回清单」11sp）；松手过线 → `HapticFeedback.mediumImpact` + `melt_block(expectedVersion)` + 撤销 Snackbar 5s；未过线弹回（瞬时，无回弹动画） |
| 左滑换乘 | 过 `0.35×块宽` 出提示层（`supplyBandBg` 右对齐「换件轻松的？」）；松手过线 → mediumImpact + 候选查询（待安排池 light/anywhere、时长相近优先）→ 非空开 bottom sheet（标题「换件轻松的？」titleLarge + 副文「挑一件换进这个时段，原任务放回清单待安排」+ 候选行 coffee 图标/「约 N 分钟」）；显式挑选 → `swap_block(targetPlanId, expectedVersion)` + 「已换乘」2s；**空池不开抽屉**轻提示；原任务无痕回池（命令侧语义） |
| proposed 块内三键 | 确认/否决/改时间——block_sheet 行动区（浮层族④），本件指针 |
| confirmed AI 块 +30min | block_sheet 行动区（human 块仅提示不级联），本件指针 |
| 背景横滑翻页 | `pageSwipeDp=60` 累计位移不设速度门槛；周视图内容横向滚归滚动、翻周走 ‹› 按钮；块卡片手势优先（竞技场内层胜）——主干页③挂载语境，本件登记阈值 |
| 清单条目左滑 | `UpdatePlan(archived)` 过 35% 卡点+触觉+必附撤销——清单页③单元，本件指针（手势矩阵行归 BlockRenderer 族） |

**手势锁实现口径**（§3.4 逐字）：水平初位移 >20dp 且 |Δx/Δy|>2.0 才锁轴（dy<1 按 1 除防除零）；不锁则不消费、无条件交还外层垂直滚动。**重入闸**：命令在飞（busy）忽略新触发。**异步安全**：messenger 在 await 前捕获（async gap 后禁用 context）。

## 时间轴挂载语境（交界件，主干页③施工、本件登记行为口径）

| 件 | 行为 |
|---|---|
| 车道分栏 | 重叠块按开始时间聚类分车道，等宽 `timelineWidth/maxLane`，卡宽 −2dp 间隙；规范「50% 等宽双列」与实现「N 车道泛化」对拍见 F7；碰撞亮橙提示线现状无（F8） |
| 派生保护区 | 占用空隙推导：🛡️ 留白缓冲 = AI 长块（>45min）后 15min 呼吸位；🍃 自由流动区 = 其余空隙；三级绘制过滤 `bandFullMin=30`（全渲染「🛡️ 留白缓冲 N 分钟」）/ `bandIconMin=15`（仅图标）/ <15 静默不渲染；边框现状=supply 实线 0.6dp、freeFlow 无框（规范「虚线框」对拍 F9） |
| 固定占用背景带 | `fixedOnDate` 当日投射 + `fixedSpillover` 前夜溢出段（钳制窗内），`fixedSlotBg` 灰带 labelSmall 文本 |
| 当前时刻线 | 「主题色 1dp 细线（不用红）」规范有、实现未见（对拍 F10，S1 基线图可证伪） |
| 「回到现在」 | 公式：`target = (now − wake)×dpPerMinute − viewport/2`，钳 `[0, maxHeight−viewport]`；animate 250ms easeOut / 定位 jumpTo；仅今日 initState 后定位，历史日从窗首（丢失点 #3 换算与单测断言随主干页轮，见下 #3 注记） |
| 跨午夜块 | 按开始日归属不拆段（S3 定义）；固定占用跨午夜钳 1440；「昨夜」头部灰带规范有、实现未见（对拍 F11） |

## 丢失点 #2/#3/#4/#9 对拍注记

| # | 对拍结论（施工随页轮落，本件留痕） |
|---|---|
| #2 Stack→Box | schedule_page 4 处全在本族/挂载语境：时间轴层叠（Positioned 子件→Box+offset 对位）/ 块卡角标层（Center+Positioned→Box+align）/ 手势层（Positioned.fill→matchParentSize）/ 周视图车道（同 lane 对位）；逐处显式化禁隐式依赖 |
| #3 滚动对齐 | goNow 的 animateTo「相对位置」语义换算为 Compose 像素语义：上表公式直接产出像素目标，Compose 侧 `animateScrollToItem`/`animateScrollBy` 按 dpPerMinute 换算；**单测断言目标像素值**（P4 todo 既定）随主干页轮 |
| #4 build 副作用 | app_gate 模式件随⑥；本族对应注记：派生清单（保护区/车道/固定带）Composition 内重算改 `remember(data){}` 派生；initState postFrameCallback→`LaunchedEffect`；无 setState 心智 |
| #9 进程查杀 | timeline 滚动偏移 `rememberSaveable`；**goNow 与恢复态的优先级需规则**：无 saved offset 才自动定位（restore 后被 goNow 覆盖=行为回归）——见对拍 F12；浮层展开态随浮层族④；核心编辑暂存 SavedStateHandle 随块浮层 |

## 对拍发现（F1–F12，D12 已裁决 2026-11-15：四问全默认通过）

> 语义评审闸门（§9.1.5）闭环：以下候选经 pending-confirm D12 裁决（2026-11-15，A 组批量+F7+F5+F12 四问全「同意默认」），裁决结果为施工口径。

| # | 发现 | 裁决（D12 2026-11-15） |
|---|---|---|
| F1 | proposed 边框规范「虚线」，实现=1.2dp 实线 | **规范为准补虚线**（随②族施工） |
| F2 | skipped 规范「⊘+降饱和」，实现=lineThrough 无⊘无降饱和 | **规范为准补 ⊘ 徽章+降饱和，lineThrough 保留**（随②族施工） |
| F3 | missed 叠加实现无；裁决前核验精化：missed 由 housekeeper 日切产生（仅昨日及更早 confirmed→missed），今日时间轴无 missed 块，**历史日回看会出现**且零专属渲染 | **补实现叠加层，全日期适用**；今日页 missed 仍经昨日遗留区处置面（随②族施工） |
| F4 | 凭证微标 badge maxWidth 规范 96dp，实现 60dp | **规范为准 96dp**（随②族施工） |
| F5 | 投影块渲染实现无；投影概念仅协作域（collab_canvas/audit_timeline 有投影） | **规范行加适用面注记，归属协作⑤轮**，本族不实现 |
| F6 | 长按 0.98 呼吸态：token `longPressScale=0.98` 有、§3.4 有、实现零使用 | **补实现**（S4 交互对拍断言 1 依赖，必补；随②族施工） |
| F7 | 重叠分栏规范「50% 等宽双列」，实现泛化 N 车道等宽 | **镜像现状改规范**（§3.4 行改「车道等宽分栏（N≥2，双列为特例）」，已写回） |
| F8 | 重叠碰撞亮橙 1dp 提示线实现无 | **补实现**（随②族施工） |
| F9 | 保护区「底色+虚线框」，实现 supply 实线 0.6dp / freeFlow 无框 | **规范为准补虚线框（含 freeFlow）**（时间轴层，随③施工） |
| F10 | 当前时刻线（主题色 1dp 细线）实现未见——S1 基线像素级复核确认（疑影=文字字形行+块间暗隙网格线，网格线在块下绘制不穿透） | **补实现**（时间轴层，随③施工） |
| F11 | 跨午夜块「昨夜」头部灰带实现未见 | **补实现**（时间轴层，随③施工） |
| F12 | 融化绑 plan 附问「已花 X 分钟未入账，补记吗」规范有、Dart 参考实现无（含业务计算非纯渲染） | **挂 Day-2**（P5 后补，对拍基线保持纯净；规范行已标注） |

另注：动效三处（坍缩 300ms/融化 400ms/安全线 spring）Flutter 现状零动画实现——**非缺口**，P4 todo 既定「首次实现」，按 §3.3 StMotion 数值施工，不属对拍件。

## 平移纪律注记

- 本族施工输入 = 本清单（过目转正后）+ ui-design §5/§3.4 + tokens 令牌；**禁读 schedule_page.dart 实现体**。
- 手势竞技场用 `pointerInput + awaitPointerEventScope` 显式抢占；块手势优先于背景翻页（内层胜）；屏幕左右边缘 24dp 系统手势保留区随主干页轮（丢失点 #8）。
- 三命令（degrade/melt/swap）均带 `expectedVersion` 乐观锁；restore 仅 melted/done→proposed/confirmed 两来源态。
