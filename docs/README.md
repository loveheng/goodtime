---
status: active
updated: 2026-10-08
---

# docs

工程文档索引（机制规范见全局 docs-spec skill）。

## design/

- [schedule-app.md](design/schedule-app.md) —— 拾光产品与架构设计（SSOT，2026-10-05 立项冻结稿）
- [functional-spec.md](design/functional-spec.md) —— 拾光功能设计施工蓝图（M1–M4 里程碑切片/页面功能清单/命令清单，零新机制，2026-10-05 冻结稿）
- [ui-spec.md](design/ui-spec.md) —— 拾光 UI 设计规格（视觉 tokens/导航/页面布局/BlockRenderer 渲染矩阵/交互流，2026-10-05 三拍板定稿）
- [golden-samples.md](design/golden-samples.md) —— 金样本评审循环固定输入集合（#4 云南七天=旅行模式双阶段全流程走查样本+实现差异备忘，2026-10-06）
- [fact-user-relay-draft.md](design/fact-user-relay-draft.md) —— 事实挂载草案（artifacts 契约/摄入管道/随行凭证 UI+多人轨用户/好友/盲中继，2026-10-07 七项拍板通过待施工，拍板留痕=schedule-app §11「事实挂载/事实摄入与凭证 UI」两行；多人轨另批）
- [background-context-draft.md](design/background-context-draft.md) —— 背景信息记录设计（软上下文 vs 硬事实分流判据/backgrounds 表模型/AI 消费双纪律；2026-10-07 定稿并已按此施工落地，拍板留痕=schedule-app §11「背景信息记录」行）
- [collab-schedule-draft.md](design/collab-schedule-draft.md) —— 协同日程设计（多人协同层：E2EE 盲中继身份与好友/密文信令中转/盲状态机与树状方案包/BYOS 三层存储容灾；2026-10-08 九轮评审全闭环：鉴权/PoP+OPK/Ticket/双层 ACL+MSK 双层密钥+E2EE 三层密钥+五表 Schema 与接口清单+双模状态机（threshold/Consensus 平权投票/ADD_SLOT 上限 8/REOPEN 强通知）+HCI 交互补齐+客户端架构（Local-First 单向流/六页/下沉衔接 v5+Outbox）+八轮五补丁（seq_id 行锁/FreeMask 量化桶/X3DH 单主设备/落槌门禁/Outbox），§12 五阶段契约先行施工序待开工）
- [collab-implementation.md](design/collab-implementation.md) —— 协同日程开发文档（设计稿的施工执行件：五阶段步骤表与验收线/Phase 0 契约冻结与五表 DDL/联调清单五项/单机 app 改造点汇总/里程碑甘特图，约 3 周单人量；2026-10-08 随九轮评审定稿）

## playbook/

AI 行为层内容源（MCP prompts 与分发 SKILL 同源不分叉，schedule-app.md §7）：

- [clarify.md](playbook/clarify.md) —— 澄清流程（问答次序/最小澄清/spec 规范/犒赏收尾/多人假设标注/背景碰撞即澄清）
- [decompose.md](playbook/decompose.md) —— 拆解流程（粒度阶梯/两类长任务出鞘/Landing Gear）
- [schedule.md](playbook/schedule.md) —— 排程流程（先读后排/校准指标解读/护航邀功/叙事双语气/背景软政策与特异性优先）
- [govern.md](playbook/govern.md) —— 清单治理与复苏（清淤/悬空话术/Clean Slate SOP/中性语言禁令/背景预算与过期清理）
- [dump.md](playbook/dump.md) —— 大脑倾倒（整段文本批量入池/零散信息三通道路由/背景捕获纪律）
