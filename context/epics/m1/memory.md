---
dev-loop: memory
format: v1
epic: m1
total-merged: 2
last-merge: 2026-10-06
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
- [2026-10-06] 拾贝好用脚本迁移 5 件入项目池：arch-guard（六条拾光规则）/build-apk/bump-version/commit-msg-gate/pre-commit-gate + git 钩子垫片（好放行/坏拦截实测）；release-r2 分发链路挂账待发布方式定。
- [2026-10-06] build-apk 首次真机构建打通：根 android/build.gradle.kts 钳制 library 子项目 compileSdk≥36（修 bonsoir_android 5.1.6 硬编码 33 的 15 条编译错误）+ app minSdk=34（仅支持 Android 14+）。
- [2026-10-06] 快记入口形态拍板（评审后用户裁定）：顶部常驻输入→右下角 FAB+键盘吸附展开抽屉；草稿三字段（text/星/死线）静默 KV+FAB 琥珀点；TextInputAction.done 回车即存；放工守卫态 FAB 恒可用（平移「快记永不关门」）。M1 封板后切 feature 分支独立切片落地（M1.1，约 0.5 天），执行清单挂 todos.md；方向 B（title/spec 分层+AI 唯一读 spec+open_items 歧义管道）评估=与 SSOT §4/§5 同构且已实装，零改动零文档动作。

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

- 2026-10-06：flutter analyze 0 issue；flutter test 91/91；bridge e2e PASS（5 项）；arch-guard 6/6；脚本五件 self-test 全 PASS；docs-lint 过（playbook 五件+ui-spec §0.4）；toolbox run build-apk → OK（arm64 release 分包 19M，aapt compileSdk36/minSdk34 + sha256 产物校验过）；build-apk --install 装上真机 3B161700Y0600000 成功（versionCode 4002，防降级守卫自动抬号 pubspec 1.0.0+2002）。四天型走查/桌面 AI 全链路/金样本/手势体感/mDNS 日历实证全部待真机人工+桌面 AI 侧。

## 断点

- [断点] 下一步：封板候选已装真机（3B161700Y0600000，versionCode 4002），剩余验收全部在人工/桌面 AI 侧：真机四天型走查+手势体感+金样本评审（用户持机）；桌面 AI 全链路（MCP token 在设置页，bridge e2e 已 PASS）；mDNS 发现+日历投影实证（挂真机轮）。M1 封板后切 feature 分支落 M1.1 快记 FAB 切片（todos.md 有清单）；蓝本 functional-spec §1 验收
