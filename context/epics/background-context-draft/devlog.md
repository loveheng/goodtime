---
dev-loop: devlog
format: v1
epic: background-context-draft
total-merged: 0
last-merge: none
---

（按行追加；≥5 条自动归并进 memory.md）
- [2026-10-07] [变更]: 切片 1 文档先行：schedule-app §11 增「背景信息记录（backgrounds）」拍板行（定稿契约并入+施工时序调整=backgrounds 先行 v3 单表迁移）；ui-spec §0.4 增背景区文案组+§5 详情页补「背景」分区；golden-samples 附录状态行校准（背景侧已批施工）；background-context-draft 转 active+§2 迁移时序注记；shiguang-index 增域行与别名映射
- [2026-10-07] [验证]: toolbox run docs-lint → 通过（git 工作区 6 docs 改动为软提示，不拦截）
- [2026-10-07] [变更]: 切片 2 db v3：backgrounds 单表迁移（v2→v3 分段幂等+建库同批，FK NO ACTION 护栏）+ Background 实体（构造单点归一 applicable_dates 去重排序/空数组归 null；读侧双检畸形过滤打 [DEGRADE] fail-closed 空窗；手编契约收口=copyWith 仅 content/tags/dates、patch 白名单同）+ repository CRUD（add/get/list scope 过滤/patch CAS/delete）+ exportAll 带 backgrounds；repository_test 增 backgrounds 9 例
- [2026-10-07] [验证]: flutter analyze → 0 issue；flutter test → 111 过 / 2 败（两败均为 m1 遗留：stash 后 HEAD 基线复现，非本轮引入；repository 组 9 例全绿）
- [2026-10-07] [越界暂停]: mcp_server_test「tools/list 注册十工具」（实返回 11——suggest_user_setting 入面后计数测试未跟上）与 ui_onboarding_test「三步可走完」（引导④关于你入链后步进脚本未更新）在 HEAD 即红，失败文件均不在本窗口 diff 内，按并行窗口纪律⑥不顺手修绿，等待用户指令
- [2026-10-07] [变更]: 修复 m1 遗留两红测（用户授权跨界）：①mcp_server_test 工具面 tripwire 10→11（suggest_user_setting 入面显式过测）；②ui_onboarding_test 升四步走查（引导④关于你）+补「空值不写」断言——顺带修出真缺陷：引导④ Column 在 800×600 测试视口溢出 3px，包 SingleChildScrollView（真机键盘弹出同受益）
- [2026-10-07] [验证]: flutter analyze → 0 issue；flutter test → 114/114 全绿；arch-guard → 6/6；注：ui_quick_note_test 单轮偶发红后复跑自愈，属坑7 挂账同族既有抖动（单文件两连跑一败一过取证），非本轮引入
- [2026-10-07] [变更]: 切片 3 命令层：rules 增全局背景预算常量（8 条/400 字/长窗 14 天劝改线）；ActionErrorCode.budget_exceeded；UpsertBackgroundCommand（id 缺省建档、编辑路径手编契约=scope/plan_id 不可变+raw 永不变忽略传入原话、source 由 actor 派生防伪造、applicable_dates 逐元素双检拒畸形、长窗劝改 note）+ MergeBackgroundsCommand（ops delete/upsert 单事务内模拟终态→校验→落库，校验不过整批拒=事务回滚；编辑行 version+1、批内不做逐行 CAS）；_requireGlobalBudget 终态统一式（additions/replace 两形态同规则，拒绝体携 limit/final_state/current 全量现场）；human 通道写后超刻度温和提示放行；delete_plan 改同事务级联销毁名下背景（块/子计划引用仍硬失败且回滚）；_dispatch 签名带 actor；commands_test 增背景组 12 例
- [2026-10-07] [验证]: flutter analyze → 0 issue；flutter test → 126/126 全绿（背景组 12 例+此前 114）；arch-guard → 6/6
- [2026-10-07] [口径决策]: ①backgrounds.source 不作命令载荷字段、由 CommandActor 派生（human→user/ai→ai_derived）——溯源不可伪造，AI 转述用户原话归 ai_derived（原话本身在 raw_source_text 永存）；②merge 编辑行只做 version+1 不做逐行 CAS——批命令在 FIFO 锁+单事务内串行，「终态校验全过才落库」由事务承担，逐行 CAS 无增益且有摩擦
- [2026-10-07] [变更]: 切片 4 工具面：tools.dart 注册 upsert_background/merge_backgrounds 双工具（描述写明软硬边界/编辑契约/applicable_dates 格式与长窗劝改/预算终态双指标与 budget_exceeded 处置路径/三通道全局路由/回显义务）；get_settings 响应搭载 global_backgrounds 治理载荷（queries.globalBackgroundsPayload：全量带 expired 标——无窗恒 false/整窗在过去 true，预算态随行供 AI 自测）；§11 台账行校准工具面 +2 与 source 派生口径；mcp_tools_test 增背景组 3 例、mcp_server_test tripwire 11→13
- [2026-10-07] [验证]: flutter analyze → 0 issue；flutter test → 129/129 全绿；arch-guard 6/6；docs-lint 通过
- [2026-10-07] [变更]: 切片 5 装配层：queries.getSchedule 每日搭载 backgrounds 排程装配（_dayBackgroundPayload）——物理过滤「当日∈applicable_dates」（无窗恒注入/一律日历日/fail-closed 空窗不注入）；分区禁平铺（global 段+各涉事计划段带 scope_note「仅约束该计划下的活动块」，无背景计划不出段省 token）；祖先链继承≤3 级（挂根计划的背景到达子计划的块，继承条目带 from_plan 溯源）；特异性优先=段内存在继承条目时整段携带 inherit_note 静态常量注记（单层不携带防噪音）；与容量水位线/天气严格分区（日级态势句不带背景）。list_plans 快照携 plan 直属背景（日期窗原样随行，搭载模式）；list_plans 工具描述提示先读背景。commands_test 增装配组 4 例+mcp_tools_test 增 1 例
- [2026-10-07] [验证]: flutter analyze → 0 issue；flutter test → 134/134 全绿；arch-guard 6/6
- [2026-10-07] [变更]: 切片 6 UI：计划详情 sheet 增「背景」区（_BackgroundSection）——条目卡=content+标签小字+日期角标 [MM.DD–MM.DD]（派生渲染不入库、无窗不显示）；超期沉「过去的背景 · N 条」折叠组（组内逐条可点开编辑+逐条删/清空已过期均二次确认，批量走 merge 批命令单事务）；添加/编辑共用 sheet（背景内容/标签空格分隔/作用日期窗起止日期选择器枚举逐日落库，不选=长期，结束早于开始拦截）；human 全走命令层（UpsertBackground/merge delete ops），写后 note（长窗劝改/预算温和提示）经 SnackBar；ui-spec §0.4 背景区文案组补「添加背景/编辑 sheet」两行（文案先行）；坑9 再现=折叠组深位删除钮先滚后点（scrollUntilVisible）
- [2026-10-07] [验证]: flutter analyze → 0 issue；flutter test → 135/135 全绿（新增 test/ui_bg_section_test.dart 覆盖添加/角标/折叠/二次确认删除全流程）；arch-guard 6/6；docs-lint 通过
- [2026-10-07] [变更]: 切片 7 playbook 收录：dump.md 增「零散信息三通道路由」表（identity_prompt/user_rules 建言通道 vs backgrounds global/plan）与「背景捕获纪律」（三刀路由兜底/捕获原子化治理归并化判据/回显义务/global 预算口径与 applicable_dates 格式/raw 仅建档一次）；schedule.md 增「背景软政策」（软上下文不生成硬墙+理由溯源/分区纪律/特异性优先与覆写注明「叶计划背景优先于全程默认」）；clarify.md 增「背景碰撞即澄清」（互斥先问清与特异性分界）；govern.md 增「背景治理」（预算撞墙归并询问 SOP/过期清理建议/治理归并化）；golden-samples 附录状态=背景侧已落地（A3/A4/A6/A7 第3、4行可走查）；README 索引同步（草案状态+四 playbook 描述）；shiguang-index 状态行改落地
- [2026-10-07] [验证]: toolbox run docs-lint → 通过；代码侧无改动（本轮纯 docs+索引），flutter test 维持 135/135 基线
- [2026-10-07] [变更]: 用户实测反馈「愿望池计划背景多而杂乱、当前位置不合适」→ 拍板方案 A=ui-spec §5 全页详情页提前落地（编辑 sheet 浮层退役）：新增 `lib/ui/plan_detail_page.dart`（PlanDetailPage 全页路由=四字段编辑+open_items 手答+子树+排期 appbar 入口+保存+背景区随迁；_BackgroundSection 转 StatefulWidget 持 controller，_PastBackgroundsGroup/背景编辑 sheet 原样迁入）；plans_page 卡片点按改推全页路由、_editSheet/_BackgroundSection/_PastBackgroundsGroup 删除、import 收敛（背景模型不再依赖）；长按归档/删除不变（删除二次确认抽 _confirmDelete 供 action sheet 调）；文档四件套留痕（schedule-app §11 台账行追加拍板+functional-spec §2 清单页条目+ui-spec §5 实现注与 §0.4 背景区文案组注；背景区文案零变动）；测试适配=plan_detail_test 去重开 sheet 步骤（全页直改第 4 字段→保存关页）+ui_bg_section_test 注释校准
- [2026-10-07] [验证]: flutter analyze → 0 issue；flutter test → 136/136 全绿（plan_detail/ui_bg_section 两流程测试按全页形态适配通过）；arch-guard → 6/6；docs-lint 通过
- [2026-10-07] [验证]: 桌面 AI 全链路 MCP 验收（A7 第 3、4 行 AI 侧）通过：建档/编辑回显快照齐全、raw 永不变（传入新原话被忽略+note 明示）、scope/plan_id 不可变拒、version CAS 递增；applicable_dates 双检（2026-02-30/2026/10/08 拒）+空数组归 null+乱序重复去重排序+15 天长窗放行附劝改 note；预算终态双指标=第 9 条 155 字拒（总量制实锤）+超长单条 8386 字拒；merge 原子批删 5 增 1 落库+bogus id 整批拒回滚（批内诱饵 upsert 未生效）；plan 作用域 535 字无预算放行+list_plans 快照携带（含日期窗）+FK 护栏「计划不存在」可读拒；get_settings 治理载荷全量带 expired 标+预算态随行。测试数据全清（0/8 条 0/400 字复核），云南旅游未触碰。两项观察：①budget_exceeded 的 data 全量现场 app 侧已附（command_handler 两处+代码核实）、本会话工具结果仅回显 message——mcp-bridge 桌面链路可取完整 JSON-RPC data，真机走查时留意；②400 字整边界放行留单测覆盖（MCP 端不传超长串复验）
