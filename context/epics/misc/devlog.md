---
dev-loop: devlog
format: v1
epic: misc
total-merged: 0
last-merge: none
---

（散修/小改动按行追加；≥5 条自动归并进 memory.md）

- [2026-10-07] [变更]: 设置页 MCP「访问令牌」行补复制按钮（IconButton copy，复用连接地址行的「复制/已复制」既有文案，无新词不入词汇表）；新增 test/ui_settings_token_test.dart 覆盖「滚动到 MCP 区→点复制→剪贴板=完整令牌+已复制提示」；测试踩坑复证 FakeAsync 铁律——setUpUiTest（sqflite ffi 真实异步）必须放 setUp() 真实区，放 testWidgets 体内 Db.instance() 永久挂死（单测超时 10 分钟取证），且长 ListView 深位区块先 scrollUntilVisible 再断言
- [2026-10-07] [验证]: flutter analyze → 0 issue；flutter test → 136/136 全绿（新增 1 例）；arch-guard → 6/6
- [2026-10-08] [变更]: 一批 UX 修缮（UI 走查六项，拍板台账 schedule-app §11「一批 UX 修缮」行）：①时间轴今日打开定位当前时刻线+header「回到现在」钮（_Timeline 转 Stateful+ScrollController，GlobalKey 控制柄自 SchedulePage 传入）②右滑融化附「撤销」③打卡附「撤销」（新增 RestoreBlockCommand op=restore_block：human-only 不进工具面、仅 melted/done→proposed/confirmed；手势写回 messenger 改 await 前捕获——顺带修复融化后块卸载致完成提示丢失的潜在缺陷）④AI 建议双钮=✓采纳（UpdateSettingsCommand 写建议值）|✕忽略（原 ✓ 图标行为是删除的语义修复）⑤周视图列头去前导零（tryParseIsoDate!.day）⑥清单空态补「记一笔」钮（快记抽屉抽公共入口 showQuickNoteSheet）；文案六条入 ui-spec §0.4
- [2026-10-08] [验证]: flutter analyze → 0 issue；flutter test → 163/163 全绿；arch-guard → 6/6；docs-lint → 通过
- [2026-10-08] [变更]: 二批 UX 结构拆分（拍板台账 schedule-app §11「二批 UX 结构拆分」行）：①设置页目录化——编辑器簇降入口行，拆 RhythmSettingsPage/ExceptionsSettingsPage/PrefsSettingsPage/McpSettingsPage 四子页（MCP 子页=ui-spec §1 SET→MCPG 原 IA 落地），settings_page 984→402 行；②计划详情「背景」子页 PlanBackgroundPage 整区迁出；③「随行凭证」子页 PlanFactsPage 迁出+详情页查看/编辑态分离（查看态默认：roadmap 进度/notes「马上开始」高亮框/spec 正文/犒赏/open_items/子树内联；「编辑」进四字段表单、保存回查看态不关页，原「保存关页」语义修订）；plan_detail_page 1003→449 行。零命令层/schema 变更；R2 违规顺带清零（exceptions 读写改委托 parseExceptions/命令层编解码）；文案三条入 ui-spec §0.4
- [2026-10-08] [验证]: flutter analyze → 0 issue；flutter test → 163/163 全绿（5 测试文件适配新导航：ui_theme ensureVisible 完全可见才点/ui_settings_token 走 MCP 子页/ui_settings_editor 走三子页路由/plan_detail 适配态分离/ui_bg_section 走背景子页）；arch-guard → 6/6；docs-lint → 通过
- [2026-10-08] [变更]: 三批 UX 拍板回归（§11「三批 UX 拍板回归」行，六项）：①换乘抽屉=左滑过卡点呼出「换件轻松的？」候选列表显式挑选（不再自动换第一候选），空池轻提示；②否决原因框=块浮层+确认卡全部否决先弹可选原因输入（RejectBlockCommand.reason 回传 AI）；③确认卡滚动坍缩=时间轴下滑收 44dp 胶囊、回滚至顶展开（_TieredConfirmCard 改受控，状态上提 _SchedulePageState）；④MCP 未连接卡=今日页常驻→MCP 子页；⑤放工静默视图补明日概要（块数+最早一块）；⑥清单搜索框=页顶标题子串过滤含冷藏池。零命令层变更
- [2026-10-08] [验证]: flutter analyze → 0 issue；flutter test → 165/165（新增 ui_swap_sheet_test/ui_reject_reason_test 两文件，一测一文件约定——合并单文件曾致进程挂起）；arch-guard → 6/6；docs-lint → 通过
