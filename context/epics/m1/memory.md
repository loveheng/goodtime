---
dev-loop: memory
format: v1
epic: m1
total-merged: 3
last-merge: 2026-10-07
---

- [挂载] docs/design/functional-spec.md

# m1 —— M1「行走骨架」

状态记忆只记进度/决策/承诺；内容 SSOT 在挂载文档（functional-spec §1 蓝图、schedule-app.md 全局 SSOT + §12 领料清单），严禁双写。

## 进度

- [2026-10-05] 自 misc 转正建档（挂载 functional-spec.md）。承接：dev-init 接入 + flutter create（android，3.47.5）+ ui-spec §0 tokens 落 `lib/theme/tokens.dart`。
- [2026-10-05] 三表 SQLite：`lib/data/db.dart` v2（plans/fixed_slots/schedule_blocks + app_settings KV；分段幂等迁移；外键 NO ACTION 兜底 plan 存在性/引用完整性，删除处置归命令层）+ `repository.dart`（FIFO 写锁/字段级 patch+乐观锁 CAS/replaceFixedSlots 原子替换/fixedSlotsForDate 当天+前日溢出解析/settings KV）+ `lib/models/` 三实体 + `lib/util/schedule_day.dart`（作息日 wake 切割/跨午夜几何/ISO/overlapsMinutes/clockOf）。
- [2026-10-05] 命令层：`lib/action/commands.dart`（协议：CommandActor.human|ai 传输层注入防伪/拒绝码+hint+结构化 data/sealed 命令+fromJson/plan-block-slot 快照 JSON 唯一口径）+ `command_handler.dart`（唯一写入口 14 命令）+ `queries.dart`（get_settings/get_schedule 服务端供作息日+label 回落/get_history 基础聚合恒非明细）。
- [2026-10-05] MCP 框架层搬运：`lib/mcp/jsonrpc.dart` 原样 + `mcp_server.dart`（Streamable HTTP/token 鉴权/Origin 防护原样；instructions=政策精华六条；prompts 先空实现）+ `tools.dart` 十工具 schema 全量注册、八工具接线命令层与查询层；`_guarded` 透传 ActionException.data；`mcp-bridge/` 三件自拾贝原样搬（无 mDNS 与盘点一致）。
- [2026-10-05] M1 UI 段落地：main.dart（Material 3 固定种子/Light 锁定）+ app_gate（revision 驱动闸门，引导播种后翻转）+ app_shell（双 Tab+快记条常驻）+ onboarding 三步（作息边界/一周节奏预设/排程偏好）+ quick_note_bar + schedule_page 日视图（1dp/min 时间轴/车道分栏/确认三键/+30m/空槽创建）+ block_sheet + plans_page 三分区 + settings_page（作息/节奏/MCP）；命令层补 place_block/delete_plan（human 专属）+ repository.blocksInRange。
- [2026-10-05] M2 减震系统：propose 机械校验全量（填充率 60% 红线/低电量 25%/呼吸律 45+15/deep 禁排+火种豁免，常量单一出处 rules.dart）+ adjust_blocks 实装（add 默认 pinned 硬行程豁免填充率；move/resize 和平条款）+ tick 补勾 missed + Housekeeper 日切（回扫 60 天幂等+冷启动）+ morningDigest + flutter_foreground_task 前台服务 + Manifest 权限。
- [2026-10-05] M3 心理层：get_history 校准指标（深潜净值/膨胀系数/时段+分象限完成率/耐受阈值含回退/能量回血）+ Clean Slate 断流保护（连续 3 作息日或 missed≥5，archived 中性淡出）+ 庆祝豁免全链 + protection_manifesto 下发 + UI（双轨仪表盘/保护区三级绘制过滤/分级确认卡/轻装重启条）+ docs/playbook 五文件。
- [2026-10-06] M4 现实接口：例外日旅行模式（固定占用挂起/填充率 min 合成/旗标水位线）+ 天气投影（Open-Meteo 无 key/TTL 1h/静默降级）+ 三日容量水位线 + reflow_day 水流重算（淹没留白→压 15min 火种→melted 蒸发三级阻尼）+ retro_log/swap_block/degrade_block/melt_block 四命令 + JSON 导出 + mDNS bonsoir 广播（shiguang-mcp._tcp fail-open，待真机实证）。
- [2026-10-06] 日/周/月三视图并存（SSOT 修正落地）：日参数化回看/周 7 列网格/月格子摘要，weekOverview/monthOverview 派生查询同源零第二真相。
- [2026-10-06] M4 UI 尾巴收官（functional-spec §1 M4 全清）：三手势（长按 400ms 坍缩/左滑 35% 换乘/右滑 45% 融化+手势锁+触觉）+ 四形态专项渲染（deep/spark/roam/庆祝香槟金）+ 逆向记账入口（空槽点过去时段→retro_log）+ Landing Gear「马上开始」headline + 放工守卫（sleep 前 2h 静默）+ swapCandidates 查询共享。
- [2026-10-06] 拾贝好用脚本迁移 5 件入项目池：arch-guard（六条拾光规则）/build-apk/bump-version/commit-msg-gate/pre-commit-gate + git 钩子垫片（好放行/坏拦截实测）；release-r2 分发链路已定稿并实测（docs/guide/self-update.md；10-07 两版发布 12011/20014）。
- [2026-10-06] build-apk 首次真机构建打通：根 android/build.gradle.kts 钳制 library 子项目 compileSdk≥36（修 bonsoir_android 5.1.6 硬编码 33 的 15 条编译错误）+ app minSdk=34（仅支持 Android 14+）。
- [2026-10-06] 快记入口形态拍板（评审后用户裁定）：顶部常驻输入→右下角 FAB+键盘吸附展开抽屉；草稿三字段（text/星/死线）静默 KV+FAB 琥珀点；TextInputAction.done 回车即存；放工守卫态 FAB 恒可用（平移「快记永不关门」）。M1 封板后切 feature 分支独立切片落地（M1.1，约 0.5 天），执行清单挂 todos.md；方向 B（title/spec 分层+AI 唯一读 spec+open_items 歧义管道）评估=与 SSOT §4/§5 同构且已实装，零改动零文档动作。
- [2026-10-06] M1 封板版提交 e48da23（81 文件 12389 行，M1–M4 全量首入库；gitignore 补 /android/build 与 .gradle）。
- [2026-10-06] M1.1 快记 FAB 切片落地（259cc3f）：QuickNoteFab+键盘吸附抽屉+QuickNoteDraftCommand（human-only，不进工具面）+quick_note_draft_* 三内部键（不经 update_settings 白名单）；层叠细则落定=确认卡实为 Column 内联件不与 FAB 竞 z、fabClearanceDp=88 净空令牌；测试拆双文件守「一测一文件」约定（此前 3 testWidgets 同文件致跨测试 FIFO 异步残留挂死，helpers/ui.dart 头注即此约定的事实源）。
- [2026-10-06] 夜间模式落地（136c461）：解锁 Light Mode 锁定，外观三档（跟随系统/浅/深，默认跟随系统）；theme_mode=uiOnly 键（_gate 新增 ai 越权拒）+update_settings 补 theme_mode 枚举校验（踩坑：开关类新键必须同步补 _updateSettings 的 per-key switch 分支，否则静默丢弃不报错）；StColors 转全局激活面板（applyBrightness 单点置位业务只读），ThemeExtension 否决记录 §11；深色映射逐值拍板入 ui-spec §0.5。
- [2026-10-06] 手势化第二期落地（237aca9）：日/月视图背景横滑翻页（>60dp pageSwipeDp 不设速度门槛；周视图横向可滚 112dp×7 故横滑归滚动）+清单左滑 35% 卡点归档+必附撤销 SnackBar（undo 走乐观锁新 version）；壳层带动作 SnackBar 浮动+fabClearance 净空（撤销钮曾被 FAB 遮挡）；右滑挂账待排期提名流；确认三键/快记 FAB/设置/块表单明确不滑动化。
- [2026-10-06] UI 测试两坑沉淀：①卡片类元素在命令后重建卸载——messenger 必须在 await 命令前捕获（快记面板与清单撤销同款）；②plans 页有常驻帧源 pumpAndSettle 永不收敛——一律固定 pump（helpers pumpFlush 同思想）。
- [2026-10-06] 功能完备性对账：6 簇缺口挂账 todos（遗留区/冷藏池已落地，余 4 簇：全局动作 sheet/清单详情簇/设置编辑器簇/MCP prompts）；捏合缩放梯子（日/周/月切换手势）评估完→拍板点已列、挂账走查后做。
- [2026-10-06] 全局动作 sheet 落地（5c6c10a）：PanicClear 两模式原子批量+两段确认 UI+警示图标钮入口；**坑7（挂账）**：UI 测试中「写+notify 之后的 tester.runAsync 轮询」会无限卡死（单窗口/循环均复现），UI 断言收窄为取消路径、执行语义由命令测试覆盖，根因待查（怀疑 sqflite_ffi 完成通道与 FakeAsync 的交互）。
- [2026-10-06] 遗留区+冷藏池切片落地（b044fa1）：PostponeBlock 新命令（missed 专用/钟点不变/pc+1/冲突拒）+settingsClearAll（单事务单 notify）；顺延后短 missed 块入时间轴的渲染在 600dp 测试视口会溢出——setSurfaceSize(800,1400) 规避，真机纵向空间充裕；深挖_pending timer_：UI 测试 pending-timer 断言与 FutureBuilder 孤儿 future 的 notify 风暴相关，收敛 notify 次数（settingsClearAll）即愈。
- [2026-10-06] 清单详情簇切片落地（uncommitted）：plans_page 编辑 sheet 升为详情页（roadmap 进度/open_items 手答敲定/子树 ≤3 级/reward_spec/「排期」）+命令层 openItems 字段（upsert/update 双通道+fromJson 容错）+add_plan 工具面补 open_items 入参（人机澄清闭环：AI 建档可留未决问题，人在 app 内手答敲定）；蓝本校准移除长按「挂起」（数据模型无此状态）。坑8：sheet 内连续写须用 snapshot 回填 version，否则乐观锁陈旧冲突。
- [2026-10-06] 设计草案起草（未定稿）：事实挂载(artifacts JSON)+Fact Capsule UI+用户/好友体系(本地roster+服务器用户/好友图)+多事实源重叠解析(重复/冲突/互补)+外部盲中继(E2E内容)。**已实质越过 §13 四大铁律**：破「无同步/无账号/服务端零联系人」三条，守「零代发+内容隐私(E2E)」。全文（含 §13 修正账 + 10 项待定清单）见 `docs/design/fact-user-relay-draft.md`；待用户逐条拍板后并入 SSOT（§13 修正需正式授权）。
- [2026-10-06] 背景信息记录草案（未定稿）：`docs/design/background-context-draft.md`。核心区分「事实(硬约束) vs 背景(软上下文)」——背景为叙事性行程上下文，仅影响 AI 软提案权重不生成硬墙，契合 §13 反压迫条款；含数据模型/捕获UX/AI消费/与facts边界/隐私同步。与主草案并列为「硬事实+软背景」完整零散信息层。待校准「背景」界定是否准确。
- [2026-10-06] 设置页编辑器簇切片落地（uncommitted）：settings_page 升级——一周节奏 fixed_slots 增删改（编辑 sheet：名称/ISO 星期 1–7 多选/起止，整单 UpdateFixedSlotsCommand 原子替换）/例外日 exceptions 增删改（起止日期+标签，JSON 落 settings.exceptions）/排程偏好（最小块粒度+单日排量上限+填充率上限+weather_location+user_rules，字段级 UpdateSettingsCommand）；命令层与十工具本就具备（M2 已通），本次纯 UI 收口。坑9：长 ListView 懒构建深位控件须先滚后点。
- [2026-10-07] 事实挂载评审收敛（多轮对话）：契约定稿——5 类 category + source_kind 三级可靠性 + state/origin + time_anchors（moment/span/rule 三类锚，支持跨日）+ constraints 三数组（required_items/rules/notices）+ badge + attachments 预留列（恒空）；独立 artifacts 表 v3 + upsert_facts 辅助工具（suggest_user_setting 先例）；三刀路由（时空锚点/行动升格/规则补全 + background 兜底）；摄入三通道（桌面对话主通道/快记粘贴旁路/系统分享仅文本 ACTION_SEND+挂载 sheet+未归属池；图片本期不做挂 Q11）；随行凭证 UI（三入口+四层槽位，不新增 Tab，计划详情「随行凭证」区=聚合主场）。多人/盲中继强制解耦待单独拍板（§13 转向不搭车）。`fact-user-relay-draft.md` 全面重写（§0 收敛记录 + §1/§2 重写）+ background 草案补 frontmatter + README 索引补登两草案；docs-lint 过。待用户对 §0 七项正式拍板后并入 SSOT 排施工（依赖序见草案 §8）。同轮补充：用户确认产品路线=**本地先行、多人后推**——预留原则=形状钉子现在进表（captured_by DEFAULT 'me' 入 artifacts/backgrounds）、机器字段延后进预留清单（主草案 §3.4，逐项可空列迁移+回填零破坏）；外部评审背稿核实后背景草案同轮定稿（Q1–Q5 决议、backgrounds 9+1 字段模型、碰撞即澄清+注入预算双纪律、三通道全局路由）。再补三轮外部建议收敛：Q6=日期角标 `[起–止]`+超期沉「过去的背景」折叠组（修正自原地置灰案）；全局预算=命令层机械拦截（budget_exceeded 仅拦 ai actor、拒绝体携 8 条现场供归并询问、human 放行——校验不对称）；提炼纪律=捕获原子化、治理归并化（原子判据=可独立删除不损义）。四轮工程暗坑卡死：预算=终态双指标统一式（条数>8 或 字数>400）+归并走原子批处理命令（单事务终态校验，先例 update_fixed_slots/propose_schedule）；applicable_dates=TEXT JSON 单日数组+长窗(>14天)劝改+双通道装配（排程物理过滤/治理全量带[已过期]标——机械集合运算非召回层）；外键 NO ACTION 护栏+delete_plan 命令层处置（背景级联销毁/artifacts detach 未归属池+note 交代去向，否决 DDL CASCADE；block_id 软引用不设 FK——凭证比块长寿）；手编契约=raw 永不变、source 出生来源不可变（否决翻转）。五轮：全链路推演收编金样本 #4 附录「事实与背景链路推演」（待施工样本，A1–A8：四通道输入/落库载荷/预算拦截/排程反哺/三层显示/生命周期三连/走查脚本 8 行；§10 表补两行待施工占位）——拍板前是设计推演不作为验收依据，施工后 A7 升正式走查。六轮（多计划并发评估）：背景草案 §4 补「多计划分区装配」（分组禁平铺+日级态势句不带背景+硬切领地软定松紧）与「祖先链继承」（≤3 级——bg_knee 挂 p1 根、块在 c4/c6，字面只注入涉事 plan 会漏根背景）与纵深三道/不做使用追踪/不加第二预算；主草案 §1.6 凭证摘要同款分组注记；金样本附录 A4bis 多计划同日对照（商务+家庭分区载荷）。七轮（施工钉子）：预算度量口径=命令层 Dart String.length 单点/只计 content（raw/tags 不进分子）/常量入 rules.dart+工具描述写明；applicable_dates=正则+tryParse 双检挡非法日历日+空数组归一 null+去重排序+读侧 [DEGRADE]+**一律日历日严禁作息日过滤**（§1.3 同款钉子）；payload 首字段 "v":1 契约版本；badge 超 12 字符命令层截断；特异性优先=背景专属软政策（inherit_note 静态注记+playbook，范围细化覆写泛化默认、互斥仍走纪律一、覆写理由须注明、不外溢 artifacts）。
- [2026-10-07] devlog 第3轮归并（15 条核对）：坑1–9、四提交链、六切片（金样本#4/遗留区冷藏池/熔断 sheet/清单详情/设置编辑器/蓝本校准）均已由上文进度行承载；唯一补遗——蓝本校准另一半：ui-spec §4 词汇表入「排期/当前进度/敲定/子计划/犒赏」五行。背景施工已切绑独立 epic background-context-draft（挂载 background-context-draft.md）。

## 口径决策（实现层拍板留痕，回 SSOT 前有效）

- settings 与三表同库（app_settings KV，db v2）——保「导出 JSON 全量」单库齐全、备份一个文件。
- propose 重复提案只替换当日 **proposed** AI 块；confirmed/done AI 块视同现实参与占位校验（防已确认块被二次提案无痕冲掉）。设计未显式定义，与意图不符回 schedule-app §11 补拍板。
- `FixedSlot.fromMap` 双形态：DB 行（id 恒在、weekdays CSV）/ 工具载荷（id 可省、weekdays 数组），缺 id 由 replaceFixedSlots 补配。
- 越权门在 FIFO 锁内异步抛——execute 恒返 Future，不向调用方同步泄异常。
- 日切对过期 **proposed 块作废=物理删除**（提案从未成为现实，AI 可重提；melted 仅物理位移触发、archived 属断流保护，均不适用——设计未显式定义，回 SSOT §11 补拍板点）。
- adjust_blocks **add 单块硬行程豁免填充率聚合**（pinned=现实属性，硬行程不该被 60% 拦）；move/resize 仍受重叠/边界校验。
- 低电量火种豁免要求 plan.min_viable_action 非空（执行内容有着落才豁免）。
- 水流降档重算（reflow_day）返回受影响清单（sparked/melted）供中性摘要；「一键撤销」通道挂账未实装（撤销=用户逐块手动改回，M4 UI 尾巴一并评估）。
- swap_block 忙碌扫描锚定块自身日期（作息日≠日历日）；List.sort 非稳定序，同分候选顺序不可依赖。
- mDNS（bonsoir）与日历投影（device_calendar）均为零实装参照的新集成：mDNS 已 fail-open 接线待真机实证；日历投影挂账真机轮（权限/RRULE/时区）。
- 内部键（mcp_token/mcp_enabled/last_daily_cut）走 app_settings KV、不经 update_settings 白名单。
- 「M4 月历替换周视图」已废弃 ➔ 日/周/月三段并存（2026-10-06 用户拍板：周视图恢复 7 列网格原案，月历以格子摘要加入，日视图日期参数化回看；台账 schedule-app §11『日/周/月切换』行）。
- Android 交付约束：minSdk=34（仅支持 Android 14+，2026-10-06 拍板）；根 gradle 钳制 library 子项目 compileSdk≥36 兜陈旧插件（bonsoir_android 5.1.6=33），bonsoir 6.x/7.x 升级挂账 todos.md。
- 快记 FAB 切片勘误/复核点（2026-10-06）：琥珀点色值=tokens.sparkStroke(0xFFFFB300)，拟引 sparkCapsuleBorder 不存在；ui-spec §3.3 拟议全局 z 阶梯（确认卡10/FAB20/Sheet30/抽屉40）为新规则，落笔需与既有 44dp 钳制「轴内 z 最高」局部规则并存，并补「分级确认卡在场时 FAB 避让」细则；§11 台账日期按实际拍板日 2026-10-06（评审稿误写 10-05）。

## 近期验证状态

- 2026-10-06：flutter analyze 0 issue；flutter test 91/91；bridge e2e PASS（5 项）；arch-guard 6/6；脚本五件 self-test 全 PASS；docs-lint 过（playbook 五件+ui-spec §0.4）；toolbox run build-apk → OK（arm64 release 分包 19M，aapt compileSdk36/minSdk34 + sha256 产物校验过）；build-apk --install 装上真机 3B161700Y0600000 成功（versionCode 4002，防降级守卫自动抬号 pubspec 1.0.0+2002）。
- 2026-10-06（M1.1+夜间+手势后）：flutter analyze 0 issue；flutter test 95/95；arch-guard 6/6；docs-lint 过；四提交链 e48da23→259cc3f→136c461→237aca9。快记 FAB/夜间三档/横滑翻页/清单左滑归档撤销均已上机待走查。

## 断点

- 2026-10-06（清单详情簇后）：flutter analyze 0 issue；flutter test 103/103（新增 test/plan_detail_test.dart）；arch-guard 6/6；docs-lint 过（golden-samples/functional-spec §2/ui-spec §4/README 索引均 2026-10-06）。
- 2026-10-06（设置编辑器簇后）：flutter analyze 0 issue；flutter test 104/104（新增 test/ui_settings_editor_test.dart，顺带修 ui_theme_test 深位控件先滚后点）；arch-guard 6/6；docs-lint 过。
- [断点] 下一步：设置页编辑器簇切片已提交（a6abe20）；金样本 #4「云南七天」已备（docs/design/golden-samples.md，含走查脚本与实现差异表）；功能完备性对账剩余：MCP prompts 实装、bonsoir 升级；后续：用户自 build-apk --install 刷新真机包 → 人工侧验收（真机四天型走查+手势体感+金样本 #4 评审；桌面 AI 全链路 MCP token 在设置页；mDNS 发现+日历投影实证挂真机轮）
