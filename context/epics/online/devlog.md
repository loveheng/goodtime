---
dev-loop: devlog
format: v1
epic: online
total-merged: 1
last-merge: 2026-10-15
---

（增量日志按行追加；≥5 条自动归并进 memory.md）

- [2026-10-15] [变更]: MCP 隐藏入口形态改「关于」长按调试菜单（用户指令，照 goodshare 范本实开核对后落地）：settings_page 撤「点设置标题 5 次」手势，页尾增「关于」区（标题+「用户本地持有的日程台账。」），长按标题唤 showModalBottomSheet「调试菜单」→「MCP 调试服务」（developer_mode 图标 + 运行态「运行中 · 端口 N / 未运行（仅 loopback，内部调试用）」）→ McpSettingsPage；ui_settings_token_test 改走长按路径；五处留痕同步（ui-spec §0.4 设置页行/schedule-app §11「MCP 暴露面」行/online memory 断点区/workflow skill/pending-confirm）
- [2026-10-15] [验证]: flutter analyze → 0 issue；flutter test → 202/202 全绿（ui_settings_token 走「关于」长按→调试菜单→MCP 子页新路径）；arch-guard 6/6 过
- [2026-10-15] [变更]: R2 发布 v1.2.1+28020（MCP 暴露面收口内容；基线 68e201a + 版本抬升 8fd5568）：`release-r2.sh --commit` 全流程（analyze 0 issue → arm64 release 21.0MB → sha256 e63de6e8… → apk+清单传 R2 公开域 r2.oklhj.eu.org）；清单提交 e9f56d6；changelog 走 CHANGELOG 环境变量传入（.env 空行坑 10-07 已注释，实测未覆盖）
- [2026-10-15] [验证]: R2 远端核验：公开清单 GET 过（versionCode 28020/versionName 1.2.1/applicationId/sha256/changelog 五字段齐）+ APK HEAD 200（20,964,788 字节与构建产物一致、content-type apk、Cloudflare 域）
- [2026-10-15] [变更]: 设置页双「设置」标题修复（用户反馈"设置页面有两个设置"）：根因=10-08 Tab 迁移遗留——SettingsPage 自有 AppBar 标题与 AppShell 三 Tab 标题（设置 Tab=「设置」）叠双杠。SettingsPage 摘自有 AppBar（标题栏由外壳提供）；app_shell「去设置」SnackBar 动作由全页 push 改 `setState(_tab=2)` 跳 Tab（全页路由彻底退役）；§11「设置入口迁底部第三 Tab」行补漏
- [2026-10-15] [验证]: flutter analyze → 0 issue；flutter test → 202/202 全绿；arch-guard 6/6 过
