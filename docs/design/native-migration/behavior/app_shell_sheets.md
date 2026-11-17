---
status: active
updated: 2026-11-15
---

# 行为清单 · app_shell 壳与浮层三件（N5 施工输入，已过目转正 2026-11-15——D15/D16/D17 全默认）

> §9.1.6 模板产出（2026-11-15 N5 前置抽取；**已过目转正（2026-11-15 用户「默认转正」整批：清单+D15/D16/D17 全默认）**——生成通道可作施工输入）。抽取源 = `lib/ui/app_shell.dart`（85 行全文）+ `lib/ui/global_action_sheet.dart`（137 行全文）+ `lib/ui/quick_note_fab.dart`（311 行全文）+ `lib/ui/block_sheet.dart` §39–69（showRejectReasonDialog）+ `docs/design/ui-pages.md` §1/§3.4/§3.6/§3.7/§9 文案清单对位 + `docs/project/ui-design.md` §3.2 层叠规则。范围 = AppShell 壳（三 Tab/标题/快记 FAB 宿主/AI 建言推送面）+ GlobalActionSheet（全局动作）+ QuickNoteFab/Panel（快记条族）+ RejectReasonDialog（否决原因弹框）。确认卡/遗留区/守卫态/时间轴已在 `schedule_page.md` 转正（本清单不重复）；ActionPassBar+computeActionPasses 随通关条轮另抽；NewBlockSheet/逆向记账/BlockSheet/FactSheet 随浮层族后续轮。

## 七字段

| 字段 | 内容 |
|---|---|
| 交互意图 | 壳=三 Tab 导航骨架+快记常驻入口（快记永不关门）+AI 建言推送面；全局动作 sheet=突发情况逃生门（熔断优先于微调，两段式确认防误触，human 块与 pinned 硬行程永不动=和平条款）；快记=30 秒抓一条（即写即存、中途收起零丢失）；否决弹框=原因可选收集（AI 下轮排程参考） |
| 触发与阈值 | FAB tooltip「记一笔」；草稿文本 300ms 防抖即写即存（星/死线即改即存）；DatePicker firstDate=now、lastDate=+365 天；SnackBar 四档 1s/2s/3s/8s（ShiguangScaffold 内建，丢失点 #1）；横滑/滚动阈值归 schedule_page.md |
| 状态空间 | FAB 琥珀点两态（草稿在盘/无，6dp sparkStroke 圆点+2dp surface 描边）；三 sheet 均 ModalBottomSheet 浮层态不进 NavHost（routing_map F1 归一）；快记抽屉=IME 吸附（viewInsets bottom 补垫）；全局动作三选项 enabled 态（remaining=0 禁用前两项） |
| 落库命令与拒绝码 | `quick_note_draft`（human-only，三键整组覆写：text/importance/deadline，text 空=整组清除）/ `quick_capture`（human-only，收入清单）/ `panic_clear`（mode=clear_remaining｜push_2h 两模式+nowMin）/ `update_settings{today_energy}`+`reflow_day`（降档联动）；拒绝码逐字 = commands_registry.md 七码 |
| 反馈形式 | 提交成功 SnackBar 1s「已收入清单」floating+FAB 净空（margin bottom=StScale.fabClearanceDp）；全局动作成功 2s（r.note ?? 完成降级）；ActionException 3s message 直显；降档 3s（r.note ?? 已降档）；AI 建言 8s+「去设置」动作钮；拒绝弹框取消=不动（无反馈） |
| 黄金向量指针 | toolface 对拍（quick_capture/panic_clear/quick_note_draft 载荷与拒绝码）；命令面 26 命令 Kotlin 已平移（dispatch 27/27 收口） |
| 像素基线指针 | 浮层态归 A 档 S5（fact_sheet × S5 已登记）；壳面随 B/C 档页面基线轮；FAB 琥珀点/收起态不单独采图（随所在页 S1） |

## §A AppShell 壳（对位 app_shell.dart；routing_map OP1/OP4 拍板=bottomBar+FAB 宿主组件）

| 行为 | 口径 |
|---|---|
| 三 Tab | 底部 NavigationBar「日程｜清单｜设置」（calendar/checklist/settings outline 图标）；Tab 切换=路由 navigateTab（popUpTo+saveState，routing_map OP1）；AppBar 标题随 Tab（日程/清单/设置） |
| 快记 FAB 宿主 | 右下角（insetLg，16dp）常驻叠于页面内容之上；**两 Tab 恒在**（Today/Plans），设置页无（OP1）；守卫态不撤（快记永不关门，schedule_page.md §G 平移） |
| AI 建言推送面 | 监听 suggestionStream（McpController 广播；McpServer.onSuggestion 钩子 ← suggest_user_setting 工具调用）→ SnackBar 8s：`AI 建议修改「key」：reason` + 动作钮「去设置」→ 跳设置 Tab；Kotlin 现状=NativeToolFace 直写 ai_setting_suggestions KV + **McpServer.kt 无 onSuggestion 钩子（缺口，需内核扩参——handleJsonRpc 语义零变化，P0 向量不受影响）** |
| 语境装配差异 | Dart 侧 MemberCalendarPage 装配（collabDatabase/身份密钥/ComposeKit 现查不快照）随协作页轮接线，本清单只登记壳面 |

## §B GlobalActionSheet（对位 global_action_sheet.dart；ui-pages §3.6）

| 行为 | 口径 |
|---|---|
| 触发 | 今日页 header ⚠ 图标钮（warning_amber outlined 20dp）tooltip「遇到突发情况？」；仅今日装配语境（Dart 传 data.blocks+now=minutesOfDay(now)） |
| remaining 计数 | `source==ai && !pinned && status∈{proposed,confirmed} && startMin>now` 的块数（三选项 subtitle 共用：`N 项未开始的 AI 安排`） |
| 三选项 | ☕ `今天下午全清空（剩余计划退回清单）` / ⏳ `整体往后推 2 小时` / 🪫 `今天精力见底，全部切换为 5 分钟启动版`（subtitle=`按低电量重算今天：压缩火种、溢出融化`）；前两项 enabled=remaining>0，第三项恒可点 |
| 两段式确认 | 点选项 → AlertDialog（title=`全清空今天剩余安排？`/`整体往后推 2 小时？`/`切换为 5 分钟启动版？`；impact 三段逐字=实现体；按钮=取消 TextButton/确认执行 FilledButton）→ 确认后才收 sheet 执行 |
| 命令执行 | 清空/后移=`panic_clear{mode: clear_remaining|push_2h, nowMin}`；降档=先 `update_settings{today_energy: low}` 再 `reflow_day`（水流确定性重算）；成功 SnackBar 2s（r.note ?? 完成）、ActionException 3s message |
| 和平条款底注 | 底部 `你的固定日程和自己排的事项不会被改动。`（bodySmall textSecondary 居中）；showDragHandle=true，padding(20,8,20,20)，选项间 SizedBox(4) |

## §C QuickNoteFab + Panel（对位 quick_note_fab.dart；ui-pages §1/§3）

| 行为 | 口径 |
|---|---|
| FAB | Icons.edit_note，tooltip「记一笔」；草稿在盘 → 右上角 6dp 琥珀点（sparkStroke，surface 描边 2dp，key=quick_note_draft_dot）；冷启动回读草稿点亮（回读失败静默=无草稿，已有 DEGRADE 标注） |
| 草稿仓 | 三字段持久化 app_settings KV（quick_note_draft_text/important/deadline 三内部键，AI 不可达）；写走 `quick_note_draft` 命令（R1：UI 不直连 repo 写）；hasDraft 信号驱动琥珀点 |
| 抽屉打开 | FAB tap → 预载草稿（await 防异步回填覆盖输入）→ showModalBottomSheet isScrollControlled + bottom viewInsets 补垫（IME 吸附） |
| 面板 | TextField autofocus 1–3 行 hint`记一笔，30 秒的事`+ 星标 IconButton（star/star_border，点亮 primary，tooltip 标重要）+ 截止日（无=event 图标钮 tooltip 截止弹 DatePicker；有=日期文字钮 44dp 点击清除）+ Spacer + FilledButton.icon `收入清单`（44dp 触控钳制，64dp 宽，提交中禁用） |
| 即写即存 | 文本 300ms 防抖 persist；星/死线即改即存；草稿回填光标落末尾；**中途收起（下滑/遮罩/切走）零询问**：未落盘防抖尾巴立即冲刷再持久化；清空后收起=显式清草稿（persist 空 text）；保存成功/清空收起才清除 |
| 提交 | `quick_capture{title: trim, importance, deadline(ISO)}` → 清草稿 → pop → SnackBar 1s「已收入清单」floating+FAB 净空；空文本/提交中禁提交；清单空态共用同一入口（2026-10-08 拍板） |

## §D RejectReasonDialog（对位 block_sheet.dart §39–69；ui-pages §3.4 拍板回归）

| 行为 | 口径 |
|---|---|
| 触发 | 确认卡「全部否决」（title=`全部否决这 N 项提案？`）/ 块浮层单块否决（默认 title=`否决这条提案？`） |
| 形态 | AlertDialog + TextField autofocus 2 行 hint`为什么不想做？（可选，AI 下轮排程会参考）`+ 取消 TextButton / 否决 FilledButton（trim 回传） |
| 返回语义 | null=取消（不动）/空串=无原因否决（reason 不带）/非空=带原因（RejectBlockCommand.reason；AI 下轮排程参考） |

## §E 命令接线与刷新面（架构裁决指针，D15/D16 裁决后由施工轮落 Kotlin）

- UI→命令面通道形状（Composable 直调 CommandHandler vs ViewModel 层）与 repo 变更刷新面（端口 revision 流 vs 独立扩展）= pending-confirm.md D15/D16，裁决后本节由施工轮补 Kotlin 落点注记。
- 电量三档接线（§D schedule_page.md 已登记）：onEnergySelect → update_settings + low→reflow_day → SnackBar 3s。
- 混态期语义注记：Dart isolate 写不通知 Kotlin 刷新面（跨运行时无总线）——混态期 Kotlin UI 对 Dart 侧写入不自动刷新（混态期 Kotlin UI 未投产，P5 Dart 退役后单写者无此差异；非阻塞，登记为已知行为差异）。
