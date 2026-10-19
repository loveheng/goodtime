---
memo: todos
format: v3
---

# 待办列表

## misc
- [ ] (功能) 分期二 临场通关条：moment role 受控枚举化（工具面+读侧兼容旧中文 role→[DEGRADE]）+ ArtifactRules 增 120/30 常量 + 今日页组件（Banner 下遗留区上/「+2」折叠/本地时钟判定禁 toUtc/跳块兜底） (层:L3) (重:中) (src: fact 草案 §9.1/§9.4, ui-pages §9.6) (2026-10-08)
- [ ] (功能) V1.5 硬红线机械校验：rule_scope{valid_from,valid_to} 建档必填 + 派生失活 + 本 plan 及递归子树校验（复用 PlanTree）+ rejected_by_facts 逐条原因 + human 通道琥珀角标放行 (层:L3) (重:中) (src: fact 草案 §9.2/§9.3) (2026-10-08)
- [ ] (功能)[小] voided 可见出口（新增切片）：repository 加含 voided 的查询变体 + 凭证子页底部「已作废 · N 条」折叠组（查看/长按删除，human）+ 排程读侧白名单不改 (层:L3) (重:中) (src: fact 草案 §1.2, ui-pages §9.5) (2026-10-08)
- [ ] (功能)[小] 回改 raw→structured 严进：category 与 source_kind 必填，缺失 invalidRequest+hint（防漏填不防填错） (层:L3) (重:中) (src: fact 草案 §9.5-7) (2026-10-08)
- [ ] (功能)[小] 回改 多 span 一致性比对=重叠度贪心（重叠最大优先，均不重叠取起始最早） (层:L3) (重:轻) (src: fact 草案 §9.5-5) (2026-10-08)
- [ ] (功能)[小] 回改 delete_plan 解挂三分支：块随删→plan_id/block_id 双清；块保留→plan_id 清、留 block_id；日切悬空→不写库静默降级 (层:L3) (重:中) (src: fact 草案 §9.5-8) (2026-10-08)
- [ ] (待拍板) Q11 图片凭证接入深度与时机（端侧 OCR 取字 / ML Kit 选型 / 批次） (层:L1) (重:轻) (src: fact 草案 §7-Q11) (2026-10-07)
