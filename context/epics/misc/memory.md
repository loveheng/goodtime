---
dev-loop: memory
format: v1
epic: misc
total-merged: 1
last-merge: 2026-10-10
---

# misc

- [2026-10-05] M1「行走骨架」已转正为 epic `m1`（挂载 docs/design/functional-spec.md，进度与断点随迁）。

## 已完成工作
- [2026-10-07] 设置页 MCP「访问令牌」行补复制按钮（复用「复制/已复制」既有文案）；新增 test/ui_settings_token_test.dart；136/136 绿。
- [2026-10-08] UX 三批修缮+结构拆分（§11 台账三行留痕）：一批六项（时间轴回到现在钮/融化附撤销/打卡附撤销 RestoreBlockCommand op=restore_block human-only/AI 建议双钮 ✓采纳✕忽略/周视图列头去前导零/清单空态记一笔 showQuickNoteSheet）；二批结构拆分（设置页目录化拆 Rhythm/Exceptions/Prefs/Mcp 四子页 984→402 行；PlanBackgroundPage、PlanFactsPage 整区迁出；详情页查看/编辑态分离 1003→449 行；R2 违规顺带清零）；三批六项（换乘抽屉显式挑候选/否决原因框 RejectBlockCommand.reason/确认卡滚动坍缩 44dp 胶囊/MCP 未连接卡迁 MCP 子页/放工静默视图补明日概要/清单搜索标题过滤含冷藏池）。终态 165/165 + arch-guard 6/6 + docs-lint 通过。
- [2026-10-08] 设置入口迁底部第三 Tab（IA 变更，§11 台账行）：AppShell 底部「日程｜清单｜设置」三 Tab，SettingsPage 内嵌 Tab 页，AppBar 右上角设置钮退役；快记 FAB 两内容 Tab 常驻口径不变。
- [2026-10-10] shiguang MCP 桌面桥接入 ~/.atomcode/mcp.json（node stdio-bridge.mjs + SHIGUANG_URL/TOKEN，LAN 192.168.1.40:8765 直连为主、USB adb reverse 兜底）；「周日见面」计划 MCP 全链路排程实战首验通过（先读后排/呼吸律/和平条款/融化重建全流程；终态 13:00-15:30 河南博物院 · 15:45-17:30 紫荆山公园 · 18:00-19:30 鱼库烤鱼）。

## 约定与拍板
- [2026-10-10] 计划攻略写法约定（拍板「维持设计不变，把 note 写好」）：块级不加备注字段；攻略写计划层——spec=敲定细节 / notes=启动第一步+【块名 @时间】要点分段（一段对一块）/ 长攻略挂背景条目带 applicable_dates；重复任务写稳定版，单次例外靠背景日期挂载。
- [2026-10-10] 块绑定说明实体拍板（§11 台账行）：说明=独立实体挂计划层、块按引用绑定、多块共享一条说明+块级单次覆盖（override）回落；解决块级无备注字段+重复任务每块复制两缺陷；落点四件（说明表或复用 backgrounds / 块模型 note_id 可空引用 / UI 块浮层展示 / MCP propose+adjust schema）待细化排期；过渡期走上一条 notes 首行约定，实现后迁移。待办已登记全局 todos（misc）。

## 踩坑与教训
- [UI 测试] FakeAsync 铁律复证：setUpUiTest（sqflite ffi 真实异步）必须放 setUp() 真实区，放 testWidgets 体内 Db.instance() 永久挂死（单测 10 分钟超时取证）；长 ListView 深位区块先 scrollUntilVisible 再断言。
- [MCP 运维] 桥 fetch failed 复现取证：手机端 ss 8765 监听正常、ping 通但 TCP 不通——`adb kill-server && adb start-server` 即恢复；先 curl 探活再重启，勿盲试。
- [MCP 排程] confirmed 块不能改 label，走融化重建；melted 残块仍占时段（见待办 bug②），重建前须先挪离目标窗口；呼吸律拒 120min 连排，段间留 ≥15min。

## 待办风险
- [风险] 两个疑似 bug 待查（全局 todos 已登记）：①adjust_blocks move 仅给 start_min 时块坍缩 5 分钟（19:00-20:30 → 18:00-18:05，应保持时长）②melted 块仍参与 propose_schedule 占用碰撞检查（与「无痕蒸发回愿望池」语义矛盾）。

## 断点
- [断点] 下一步：等待散修任务
