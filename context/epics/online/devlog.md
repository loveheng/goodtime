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
