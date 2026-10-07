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
