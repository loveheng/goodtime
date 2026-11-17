---
dev-loop: devlog
format: v1
epic: misc
total-merged: 1
last-merge: 2026-10-10
---

（散修/小改动按行追加；≥5 条自动归并进 memory.md）
- [2026-11-14] [变更]: 日程页头部 overflow 修复（真机调试桥基线截图发现，74/82/177/66/78px 多处）：① 日期头部行两段式拆分——导航+工具行（‹前一天/后一天›/回到现在/⚠，TextButton compact）独占一行，日期标题+今日核心项独占一行完整显示（363dp 逻辑宽下一行 7 项物理放不下，语义零损失）② _Dashboard 仪表盘行去 Spacer 全行紧凑+电量三档 compact（30dp 约束）+徽章 Flexible ellipsis（§3.1 三段并列无 spacer 语义）③ 今日核心卡按钮行提示文本 Expanded ellipsis。基线 schedule 三组已重采（溢出现状不入库）
- [2026-11-14] [验证]: toolbox run arch-guard → 6/6 过；flutter analyze → No issues；UI 流程测试 14/14 绿；golden 155/155 绿；真机重采 schedule S1/S2/S3 三组目检溢出黄纹全消（283KB/108KB/185KB，基线 manifest 已增量更新）
