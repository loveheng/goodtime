---
status: active
updated: 2026-11-15
---

# 行为清单 · mcp_settings（MCP 调试子页 + 控制器行为面，N8c 施工输入，2026-11-15 D23 过目转正）

> §9.1.6 模板产出（2026-11-15 N8c 前置抽取）。抽取源 = `lib/ui/mcp_settings_page.dart`（217 行全文）+ `lib/service/mcp_controller.dart`（176 行全文）+ `lib/service/relay_client.dart`（RelayKeys 配置键）+ schedule-app §11「MCP 暴露面」行 + ui-pages §8（mcp_settings C 档）。**范围 = 调试页全件（服务开关/连接地址/访问令牌/relay 区）+ McpController 行为面（restore/enable/disable/token/instructions/suggestion 广播）**；MCP 内核（McpServer/NativeToolFace）与 relay 协议已平移（P1），HttpTransport Kotlin 侧待核（core/mcp 现仅 2 文件）。

## 七字段

| 字段 | 内容 |
|---|---|
| 交互意图 | MCP 调试服务控制面（隐藏入口：设置页长按「关于」整卡唤调试菜单→MCP 调试服务，goodshare 范本）：服务开关/连接地址/访问令牌/relay 中转区；仅内部调试——生产通道走 Binder 宿主进程内嵌入不经本页（2026-10-15 拍板） |
| 触发与阈值 | 开关即切（enable/disable 异步）；复制钮点击即 Clipboard；relay 三域 onSubmitted 才保存（非即改即存）；token 重新生成即落盘 |
| 状态空间 | ui-pages §8 C 档 2 态，基线 `mcp S1` 已采（manifest；S2 未采——挂账）；页内 running 两态（运行中/已停止 + 状态圆点）+ relay 区 enabled 展开/收起两态 + 未载入隐藏态 |
| 落库命令与拒绝码 | 无 ScheduleCommand——`settingsSet` 直写 KV 内部键：mcp_token/mcp_enabled（服务自身状态，**不经 update_settings 命令白名单**）+ relay 四键（relay_endpoint/relay_username/relay_password/relay_enabled）；enable 失败返回错误串非 ActionErrorCode |
| 反馈形式 | enable 失败 SnackBar（错误串，无 error 底色——Flutter 侧未设 error 样式）；复制成功 SnackBar 1s「已复制」；relay 保存 SnackBar 1s「已保存（下次打开服务生效）」 |
| 黄金向量指针 | mcp_rejection（JSON-RPC 协议码 17 cases）/mcp_toolface（23 cases）/relay_proto（8 cases）三集已平移对拍；本页为控制面无新向量 |
| 像素基线指针 | `mcp S1`（manifest 已采）；S2（服务停止态）未采——基线收尾轮补 |

## §A 页面行为

| 行为 | 口径 |
|---|---|
| 服务开关 | SwitchListTile：leading 10dp 圆点（running=绿 0xFF81C784/停止=safelineOff token）+「运行中/已停止」；开 → enable() 返回错误串非空即 SnackBar；关 → disable()（静默） |
| 连接地址 | ListTile「连接地址」subtitle=`http://127.0.0.1:8765/mcp`（仅回环常量）；trailing 复制钮 18dp → Clipboard + SnackBar 1s「已复制」 |
| 访问令牌 | ListTile「访问令牌」subtitle=token ??「…」；复制钮（token null 禁用）+「重新生成」TextButton → regenerateToken（24 位 hex Random.secure → mcp_token 落盘 + 刷新 UI） |
| 回环说明 | 「仅内部调试：服务只监听本机回环，不暴露局域网。桌面 AI 先执行 adb reverse tcp:8765 tcp:8765，再用上方地址接入。」11pt textSecondary |
| relay 区 | 自治 StatefulWidget：settingsGet 四键回填（enabled=='1'）；未载入渲染 SizedBox.shrink；开关「relay 中转（外网可达）」subtitle「需与桌面端互为 relay 好友；保存后下次打开服务生效」→ 立即 settingsSet enabled '1'/'0' + SnackBar 1s「已保存（下次打开服务生效）」；展开后三域 onSubmitted 保存：endpoint（trim）/username（trim）/password（**不 trim** obscureText）+ 门禁文案「MCP 请求门禁：relay 好友 ACL + 本页访问令牌双保险；桌面端用 relay-bridge.mjs 接入（手机不在线即失败）。」11pt |
| 页骨架 | AppBar「MCP 服务」+ ListenableBuilder(mcp) 驱动 ListView padding 16——Kotlin=VM collect running 状态流 |

## §B McpController 行为面（Kotlin 化范围随施工轮定深度）

| 行为 | 口径 |
|---|---|
| restoreIfNeeded（冷启动） | token 缺失/空 → 生成 24 位 hex 落盘；mcp_enabled=='1' → 自动 enable（失败 debugPrint 不阻断）——装配顺序在 app_services bootstrap（Android 侧=AppContainer/Application onCreate，守启动分层第四轮拍板⑤：UI 推迟首 Activity，服务恢复面是否进 Core 层过目 §D.2） |
| enable | 前台服务保活（Flutter 侧 dataSync 类型——**Android 15 dataSync 6 小时配额 → 原生侧改 specialUse/connectedDevice，第四轮拍板④已定**；通知「拾光 · MCP 服务运行中」+回环说明）→ HTTP transport start 8765 回环 → relay 装配（enabled=='1' 且三域齐备才建连，fail-open 不阻断回环）+ drainPending 重连清积压 → mcp_enabled='1'；任一异常 → _shutdown 全停 + lastError 返回 |
| disable | transport/relay 全停（已停吞异常）+ mcp_enabled='0' |
| suggestion 广播 | AI suggest_user_setting → onSuggestion 回调 → broadcast stream（app UI 弹提示，设置主页 AI 建言决断面已接——Kotlin 侧 SharedFlow，SettingsViewModel 双源已留位） |
| instructionsProvider | 远程 mcpInstructions 非空（trim）覆盖 builtinInstructions；identity_prompt 非空（trim）拼接「【用户身份画像】（由用户在 app 内填写，描述其自身身份与处境，排程时务必尊重）\n{prompt}」段尾——即配即用（每次 initialize 现取） |
| endpoint | 常量 `http://127.0.0.1:8765/mcp`（defaultPort 8765）；mDNS 广播已随 2026-10-15 拍板退役 |

## §C Kotlin 现状与缺口

- **已平移**：McpServer + NativeToolFace（core/mcp）；Binder 生产通道（ShiguangMcpBinderService）；relay 协议内核（core/model collab relay 族）；settings KV 端口。
- **N8c 缺口**：HttpTransport（回环 HTTP 面）/McpController（总控+前台服务联动）/本页 UI/relay 客户端运行时装配（CollabTransport 已有内核件，装配层待核）——**控制器与传输的平移深度（P1 遗留 vs 本轮随行）须过目拍板**。

## §D 过目点（须人工拍板）

1. **HttpTransport/McpController 平移深度**：P1 内核平移只落 McpServer/NativeToolFace，回环 HTTP 调试面（transport+controller+前台服务）未落——本页无后端则开关/地址/令牌全静态。选项：A=本轮随行平移（页面+控制器+transport 全件，真机可调试）；B=页面先行、控制器挂账随 P4 收尾（开关渲染态跑通但 enable 空转）。**默认建议=A**（本页存在的意义就是调试服务面，空转页无验收价值；前台服务类型按拍板④ specialUse 落）。
2. **restoreIfNeeded 装配时机**：Dart 在 bootstrap await 恢复——原生按启动分层⑤（Application.onCreate 只做 Core）应推迟到首 Activity/服务启动时。**默认建议=首 Activity 装配段恢复**（mcp_enabled 持久，开机不自动拉起 HTTP 服务，行为差异=Dart 侧冷启动即恢复 vs 原生首帧才恢复——对回环调试无感）。
3. **relay 密码不 trim**：endpoint/username trim、password 原文保存——照抄（密码含首尾空格合法语义）。
