---
dev-loop: devlog
format: v1
epic: m1
total-merged: 2
last-merge: 2026-10-06
---

（按行追加；≥5 条自动归并进 memory.md）
- [2026-10-06] 封板+三切片：e48da23 M1–M4 全量首入库（81 文件）→ 259cc3f M1.1 快记 FAB+抽屉+草稿 KV → 136c461 夜间模式三档+深色面板 → 237aca9 手势化（横滑翻页+清单左滑归档撤销）。analyze 0 / test 95/95 / arch-guard 6/6 / docs-lint 过。
- [2026-10-06] 坑1：开关类 settings 新键须同步补 _updateSettings per-key switch 分支，漏补=静默丢弃（theme_mode 踩过）。
- [2026-10-06] 坑2：UI 流程测试「一测一文件」是硬约定——同文件多 testWidgets 会因跨测试 FIFO 真实异步残留挂死（test1 卡死 test2 复现，拆文件即愈）。
- [2026-10-06] 坑3：卡片类组件 await 命令后自身会被重建卸载——ScaffoldMessenger 必须在 await 前捕获；plans 页有常驻帧源，pumpAndSettle 永不收敛，测试一律固定 pump。
- [2026-10-06] 坑4：带动作 SnackBar 会被右下角 FAB 遮挡动作钮——壳层 SnackBar 浮动+fabClearanceDp 边距。
- [2026-10-06] 功能完备性对账（蓝本↔代码）：核心闭环完备；6 簇缺口挂账 todos.md——遗留区 UI/冷藏池/全局动作 sheet/清单详情簇/设置编辑器簇/MCP prompts；日历投影与真机实证类维持原挂账不入 todos（已在断点）。
- [2026-10-06] [变更]: 金样本 #4「云南七天」落地 docs/design/golden-samples.md——旅行模式双阶段全流程（快记捕获→澄清→拆解 T-minus 路线图→筹备期 propose→exceptions 上膛+五段 pinned 硬骨架→例外日在途七日（整单拒绝/天气改口/累瘫 reflow/随行熔断/逆向记账/夜块人通道）→get_history 复盘+月历+导出），载荷逐字段对照命令层与十工具 schema 取证，附机制覆盖矩阵、真机走查脚本与 8 条实现差异备忘（open_items 全链无写入口/roadmap 过窗检测未实装/retro·place 无庆祝旗标/见证式安全线未实装等）；docs/README 索引与 shiguang-index 增行同步
- [2026-10-06] [验证]: toolbox run docs-lint → 通过（docs/design/golden-samples.md 新建 + README.md 索引，frontmatter/updated 均已刷新）
- [2026-10-06] 遗留区+冷藏池切片（b044fa1）：PostponeBlock/设置页冻结期门控/冷藏池恢复；坑5——UI 测试 pending-timer 偶发与「一次命令多次 notify → FutureBuilder 孤儿 future 风暴」相关，批量清除类操作合并单 notify 即愈；坑6——600dp 测试视口对多行卡片区过矮，用 setSurfaceSize 贴真机空间。
- [2026-10-06] 熔断 sheet 切片（5c6c10a）：PanicClear clear_remaining/push_2h 原子批量+两段确认；坑7挂账——UI 测试「写+notify 后 runAsync 轮询」无限卡死（单窗口/循环皆复现，LOOP0 能读到写后状态、第二次 runAsync 挂起），UI 断言收窄取消路径，根因待查。
- [2026-10-06] 清单详情簇切片（uncommitted）：plans_page 编辑 sheet 升级（『## 路线图』checklist 解析进度「▶ 当前进度 x/N」/open_items 手答→「敲定」→已答转「✓ 问题 → 答案」/子树 ≤3 级缩进/reward_spec 字段/「排期」→PlaceBlockCommand）；命令层 UpsertPlan+UpdatePlan 增 openItems（fromJson 解析坏条目静默跳过）；十工具 add_plan 补 open_items 入参声明（原仅 update_plan 有，add_plan 描述早已承诺可带问题建档=工具面漏项）。analyze 0 / test 103/103 / arch-guard 6/6 / docs-lint 过。
- [2026-10-06] 蓝本校准：functional-spec §2 清单页长按「挂起」移除（数据模型无对应状态，属蓝本超写，就地注明 2026-10-06 对账校准）；ui-spec §4 词汇表入「排期/当前进度/敲定/子计划/犒赏」五行。
- [2026-10-06] 坑8：同一 sheet 内连续写（先「敲定」再「保存」）必须用 CommandResult.snapshot 回填 version，否则第二次写因乐观锁 version 陈旧冲突——可变 version 变量随每次成功写更新（此前 sheet 用 plan.version 一次性快照，单写无感、连续写即踩）。
- [2026-10-06] 设置页编辑器簇切片（uncommitted）：settings_page 升级——一周节奏 fixed_slots 增删改（编辑 sheet：名称/ISO 星期 1–7 多选/起止 TimePicker，整单 UpdateFixedSlotsCommand 原子替换）/例外日 exceptions 增删改（起止日期+标签，JSON 落 settings.exceptions）/排程偏好（最小块粒度+单日排量上限+填充率上限+weather_location+user_rules，字段级 UpdateSettingsCommand）。命令层与工具面本就具备（M2 已通），本次纯 UI 收口；analyze 0 / test 104/104（新增 test/ui_settings_editor_test.dart）/ arch-guard 6/6 / docs-lint 过。
- [2026-10-06] 坑9：设置页是长 ListView（children 懒构建），深处「外观」分段按钮未在首帧入树 → ui_theme_test 原 `find.text('深色')` 报 0 匹配；改为 `_scrollToText` 先滚入视口（fling + 固定 pump，因设置页有常驻帧源 pumpAndSettle 永不收敛，同坑3）。新增段越多越易踩，深位控件一律先滚后点。

