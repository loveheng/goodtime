---
status: active
updated: 2026-11-15
---

# 行为清单 · settings 子页三件（rhythm/exceptions/prefs，N8b 施工输入，待人工过目）

> §9.1.6 模板产出（2026-11-15 N8b 前置抽取）。抽取源 = `lib/ui/rhythm_settings_page.dart`（251 行全文）+ `lib/ui/exceptions_settings_page.dart`（228 行全文）+ `lib/ui/prefs_settings_page.dart`（182 行全文）+ `docs/design/ui-pages.md` §1 子页化 IA + schedule-app §13（例外日域语义）+ R2 口径（UI 禁内联 JSON 编解码）。**范围 = 编辑器三子页全件**；onboarding/app_gate/update/mcp 随 N8c 另抽。命令面 update_fixed_slots/update_settings 已平移（dispatch 收口；非法值命令层拦截）。

## 七字段

| 字段 | 内容 |
|---|---|
| 交互意图 | 三编辑器子页（2026-10-08 子页化迁出，目录页只留入口行）：一周节奏=fixed_slots 增删改（首启后唯一编辑入口，整单批量替换、条目独立工作日/周末可并存）；假期与出行=例外日窗口+标签（期间工作类节奏挂起、填充率降至 35%——§13 域语义本页只管窗口数据）；排程偏好=六字段独立提交（改一个存一个） |
| 触发与阈值 | 编辑 sheet=ModalBottomSheet isScrollControlled+IME 补垫；TimePicker/DatePicker 即选；保存三校验（rhythm）/单校验（exceptions）/数字校验（prefs intField） |
| 状态空间 | 各页装载态（列表 null=转圈）/编辑 sheet 两态（新建/编辑回填）/prefs 表单态（key=label|initial 重载重置） |
| 落库命令与拒绝码 | rhythm=`update_fixed_slots`（整单替换，命令层三检）；exceptions=`update_settings{exceptions}`（**写侧只传结构化值，JSON 编解码归命令层 R2 口径**）；prefs=`update_settings` 字段级（min_block_minutes/daily_new_blocks_limit/fill_rate_limit/weather_location/user_rules/identity_prompt）；拒绝=invalid_request 族（命令层拦截） |
| 反馈形式 | rhythm 删除 SnackBar「已删除「name」」/保存校验失败三条逐字（名称不能为空/至少选一个适用星期/结束须晚于开始）；exceptions 删除 SnackBar「已删除该例外日」/「结束日期须不早于开始」；prefs 保存 SnackBar 1s「已保存」/「请填数字」1s/失败 Exception message 3s（去前缀） |
| 黄金向量指针 | update_fixed_slots 三检/update_settings exceptions 编解码（命令面已对拍） |
| 像素基线指针 | 各子页组（ui-pages §8 manifest，C 档） |

## §A 一周节奏（fixed_slots 增删改）

| 行为 | 口径 |
|---|---|
| 列表 | listFixedSlots→ListTile 逐条：title=name；subtitle=`周一、周二 09:00–18:00`（weekdays ISO 中文名「、」连接+clockOf 区间）；trailing=编辑（edit 18）/删除（delete 18→UpdateFixedSlots 整单 rest→SnackBar「已删除「name」」）；导语「没有固定安排是完全正常的（自由职业预设）」（空=纯 textSecondary/非空=bodySmall textSecondary）；尾部 OutlinedButton.icon「添加」（add 18） |
| 编辑 sheet | titleLarge「添加固定安排」/「编辑固定安排」；名称 TextField（labelText`名称（如「上班」「健身」）`isDense）；「适用星期（可多选）」Wrap FilterChip×7（spacing 6，ISO 周一..周日中文）；开始/结束双 ListTile（TimePicker；新建缺省 09:00/18:00；编辑回填 endMin%1440）；FilledButton「保存」三校验（名称空「名称不能为空」/星期空「至少选一个适用星期」/endMin≤startMin「结束须晚于开始」——SnackBar 后不关 sheet）→通过 pop FixedSlot（id=existing?.id 保留、weekdays 升序） |
| 提交 | _editSlot：sheet 返回→current.where(id≠slot.id)+add→`update_fixed_slots`（整单批量替换）→reload |

## §B 假期与出行（例外日窗口）

| 行为 | 口径 |
|---|---|
| 读 | settingsAll→parseExceptions（**R2：读侧委托数据层解析器，UI 禁内联 JSON 编解码**——Kotlin SettingsKeys.parseExceptions 镜像已有） |
| 列表 | ListTile 逐条：title=label??「假期与出行」；subtitle=`start → end`；trailing=编辑/删除（→_save(rest)→SnackBar「已删除该例外日」）；导语「没有特别安排是完全正常的」双形同 §A；OutlinedButton.icon「添加」 |
| 编辑 sheet | titleLarge「添加假期与出行」/「编辑假期与出行」；从/到双 ListTile（DatePicker firstDate 2020/lastDate 2035；新建缺省 now）；标签 TextField（labelText`标签（如「云南行」「春节」）`）；FilledButton「保存」单校验（endIso<startIso「结束日期须不早于开始」→SnackBar）→pop（label trim 空=null） |
| 写 | `_save(list)`：`update_settings{exceptions: [{start,end,label?}]}`（**结构化值直传，编解码归命令层**）→reload |

## §C 排程偏好（六字段独立提交）

| 行为 | 口径 |
|---|---|
| 六字段 | 最小块粒度（分钟）intField / 单日新增排量上限（块数）intField / 填充率上限（%）intField hint`默认 60，留白是吸收意外的护城河` / 天气城市（手动填，零定位权限）textField / 我的偏好（自然语言，如「周五晚上不排深度工作」）textField maxLines 3 / 身份画像（系统提示词）：描述你是谁、处境与硬约束，随 MCP 注入 AI 排程上下文 textField maxLines 4 |
| intField 形 | Row：TextField（keyboardNumber、labelText+hintText、isDense）+TextButton「保存」——int.tryParse null→SnackBar 1s「请填数字」；key=ValueKey(label|initial)（重载重置表单） |
| textField 形 | Column：TextField（maxLines、isDense）+右对齐 TextButton「保存」（trim 提交） |
| _save | update_settings 字段级→SnackBar 1s「已保存」；Exception→SnackBar 3s（toString 去「Exception: 」前缀）→reload |

## §D 架构接线（N8b）

- SubpagesViewModel（D15 同形）：revision 驱动重载三组状态（slots/exceptions/settings）+命令面（saveFixedSlots 整单/deleteFixedSlot/saveExceptions/savePrefs 字段级）+事件面（校验/删除/已保存 SnackBar——rhythm 编辑 sheet 三校验在 UI 内联拦（Dart 同构：SnackBar 后不关 sheet））。
- 校验位置注记：rhythm 三校验/exceptions 单校验在 **sheet 内联**（Dart 同构——不关 sheet 原地提示）；prefs 数字校验同 UI 内联。
- 挂账：onboarding/app_gate/update/mcp 随 N8c。
