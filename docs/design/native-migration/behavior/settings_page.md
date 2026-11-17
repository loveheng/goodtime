---
status: active
updated: 2026-11-15
---

# 行为清单 · settings_page（设置主页，N8a 施工输入，待人工过目）

> §9.1.6 模板产出（2026-11-15 N8a 前置抽取）。抽取源 = `lib/ui/settings_page.dart`（474 行全文）+ `docs/design/ui-pages.md` §1（SET 子页化 IA）+ `docs/guide/self-update.md`（更新入口指针）+ schedule-app §11「MCP 暴露面」行。**范围 = 设置主页全件（AI 建议区/作息边界/外观三档/子页入口行/更新区/协同入口/数据出口/关于+调试菜单）+ 子页路由面（Rhythm/Exceptions/Prefs/McpDebug/Update/Account 六 destination 接装占位）**；rhythm/exceptions/prefs/update/mcp 五子页+onboarding+app_gate 本体清单随 N8b/N8c 各轮另抽（plans_page 先例）。

## 七字段

| 字段 | 内容 |
|---|---|
| 交互意图 | 目录形态（2026-10-08 子页化拍板）：本页只留高频项（作息边界/外观）+ AI 建议决断面 + 编辑器簇入口 + 更新/数据出口/关于；MCP 调试服务改隐藏入口（长按「关于」整卡唤调试菜单，照 goodshare 范本——MCP 不对外展示仅内部调试，2026-10-15 拍板） |
| 触发与阈值 | 入口行点按→子页路由；建议采纳=写入建议值（check ✓ 语义 2026-10-08 拍板修复：原 check 行为是删除与肌肉记忆相反）；TimePicker/SegmentedButton 即改即存 |
| 状态空间 | AI 建议列表态（空隐藏全节）/外观三档选中态（system/light/dark）/账号摘要两态（已登录 username/未登录）/远程公告缓存态（空隐藏行）/调试菜单浮层态 |
| 落库命令与拒绝码 | `update_settings`（wake_time/sleep_time/theme_mode/采纳建议值——uiOnly 键 theme_mode 仅人可写，AI 触碰即拒）+ `settingsSet`（ai_setting_suggestions 内部键直写不移除条目走 KV 过滤回写）；拒绝=命令层键白名单/uiOnly 越权族 |
| 反馈形式 | 采纳成功 SnackBar 2s「已采纳：key = value」；失败 ActionException 3s message；导出复制成功 SnackBar「已复制到剪贴板」（时长档缺省=Dart 默认 4s→原生 SEC3 归档注记） |
| 黄金向量指针 | update_settings 键白名单/uiOnly 越权（CommandHandler gate 已平移）；toolface suggest_user_setting 建言落键（NativeToolFace 直写 KV 已平移） |
| 像素基线指针 | settings 组（ui-pages §8 manifest，C 档）；目录行/分区卡形态随基线轮采图 |

## §A 数据装配

| 行为 | 口径 |
|---|---|
| 三源合并监听 | repo+mcp+account 三 Listenable 合并（account 登录/退出回页刷新摘要行）——Kotlin=VM collect repo.revision+ sugerBus+账号会话态（账号面挂账随协作轮，先挂 repo.revision+suggestionBus 双源） |
| 设置快照 | ScheduleQueries.getSettings（wake_time 缺省 420/sleep 1380/theme_mode 缺省 system） |
| AI 建议列表 | settingsGet(ai_setting_suggestions)→JSON 数组解析（id/key/reason/suggested_value/severity/status），空/缺省=空列表节隐藏 |
| 远程公告 | RemoteConfigStore.announcement（缓存随检查更新刷新）——**Kotlin 无对应物：N8a 公告行挂账随 update 子页轮（RemoteConfigStore Kotlin 化同批）** |
| 账号摘要 | isLoggedIn?已登录：username:登录后与好友协同日程——AccountSession :core:model 已平移但 AppContainer 未装配（协作轮接线）——**N8a 摘要行挂账随协作轮，先渲染「登录后与好友协同日程」静态面** |

## §B 页骨架（目录形态，ListView padding 16）

| 行 | 口径 |
|---|---|
| AI 建议区 | 非空显现：Card surfaceContainerHighest+「AI 建议（待你决断）」titleSmall+逐条 ListTile（lightbulb_outline leading；title「建议修改「key」」；subtitle 建议值非空=「建议值：v\n原因：r」否则「原因：r」isThreeLine；trailing=采纳 check_circle_outline（有建议值时）+忽略 close） |
| 采纳 | update_settings{key: suggested}（命令层白名单/uiOnly 拦截）→ 按 key 移除建议条目 → SnackBar 2s「已采纳：key = value」 |
| 忽略 | 按 id 移除建议条目（无 SnackBar） |
| 移除通道 | settingsGet→JSON 过滤→settingsSet 回写（采纳/忽略共用） |
| 作息边界 | Card：起床/睡觉双列 ListTile（subtitle clockOf fontSize 20）→ showTimePicker → update_settings{wake_time|sleep_time 分钟数}（引导①必填项的日常微调口） |
| 入口行三枚 | Card（bottom 4）ListTile leading 22+title+subtitle+chevron_right 20 textSecondary：一周节奏 view_week_outlined「有固定安排的时段」/假期与出行 beach_access_outlined「例外日：期间挂起工作节奏」/排程偏好 tune「粒度/排量/填充率/我的偏好」→ 各子页路由 |
| 外观三档 | SegmentedButton 跟随系统/浅色/深色 → update_settings{theme_mode}（uiOnly 键） |
| 更新区 | Card：「应用更新」+检查更新 ListTile（system_update_alt「应用内自更新 + 远程公告/MCP 指令热更」chevron）→ Update 路由；公告非空=campaign_outlined 18+bodySmall 行（N8a 挂账） |
| 协同区 | 「协同」+协同账号入口行（account_circle_outlined；摘要两态；N8a 静态面挂账）→ Account 路由 |
| 数据出口 | Card「数据出口」+「导出 JSON（全量）」ListTile（ios_share「计划/一周节奏/日程块/设置，本地持有随导随走」）→ exportAll 六键（plans/fixed_slots/schedule_blocks/backgrounds/artifacts/app_settings）+exported_at → AlertDialog 全量 JSON（SelectableText 11pt 单滚）+复制全部（Clipboard+pop+「已复制到剪贴板」）/关闭——**Kotlin 端口缺 exportAll：N8a 补（Expand：plans/fixed_slots/schedule_blocks/backgrounds/artifacts/app_settings 全 query+exported_at）** |
| 关于+调试菜单 | Card 整卡 GestureDetector opaque 长按（真机容错：标题小目标被滚动抢手势）→「用户本地持有的日程台账。」11pt textSecondary；长按=ModalBottomSheet「调试菜单」w600 居中+MCP 调试服务行（developer_mode；subtitle running?「运行中 · 端口 N」:「未运行（仅 loopback，内部调试用）」——**MCP HTTP 回环调试面 Kotlin 化随 mcp 子页轮，N8a subtitle 静态「未运行（仅 loopback，内部调试用）」挂账**）→ McpDebug 路由 |

## §C 架构接线（N8a）

- SettingsViewModel（D15 同形）：StateFlow（settings 快照+建议列表）+collect repo.revision+suggestionBus 双源重载+命令（applySuggestion/dismissSuggestion/setTime/setThemeMode）+事件面；壳层 Settings destination 接装。
- Kotlin 端口扩展：exportAll()（Expand——NativeSqlRepository 六键全 query 聚合+exported_at ISO 时间戳；DartBridgeRepository call 同步）。
- 路由：RhythmSettings/ExceptionsSettings/McpDebug/Update/Account 五 destination 占位接装（PrefsSettings 入口行=排程偏好——**routing_map 候选 21 无 PrefsSettings 目的地**：Dart 入口在而路由缺——N8a 补第六目的地 PrefsSettings（Expand 契约注记）或入口行挂账？**默认建议=补目的地**（子页轮要接装，路由先注册防半截入口）。
- 挂账：远程公告行（RemoteConfigStore Kotlin 化随 update 轮）/账号摘要静态面（协作轮）/MCP 调试行静态 subtitle（mcp 子页轮）/五子页+onboarding+app_gate 本体（N8b/N8c 轮抽取施工）。
