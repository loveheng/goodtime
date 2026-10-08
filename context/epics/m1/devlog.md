---
dev-loop: devlog
format: v1
epic: m1
total-merged: 3
last-merge: 2026-10-07
---

（按行追加；≥5 条自动归并进 memory.md）
- [2026-10-07] [变更]: 发布 v1.1.0+20014 → R2 更新源（内容=背景信息七切片+计划详情全页化云端首发；bump 守卫自动抬号 18013→20014 追平真机已装 20013）；顺带修 release-r2.sh：upload-r2 两处调用加 --no-network-family-autoselection --dns-result-order=ipv4first（Node 22 Happy Eyeballs 单次连接尝试 250ms 上限 < 本机→Cloudflare RTT ~300ms，fetch 恒 AggregateError ETIMEDOUT，关竞速后秒传，详见 lessons）
- [2026-10-07] [验证]: 发布门禁 flutter test → 136/136 全绿；release-r2.sh 内置 analyze → 0 issue；线上校验=清单 versionCode 20014/changelog 正确、线上 APK 全量回下 sha256 与本地构建逐字一致（24d16ea4…，app 侧下载另有 SHA-256 校验兜底）；两笔入库（fix 脚本 76c8262 + chore release 58e22cc）
