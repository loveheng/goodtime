---
status: draft
updated: 2026-10-06
---

# 背景信息记录（Background Context）设计草案

> **状态**：草案（DRAFT，基于对话推断，待校准）。
> **配套**：`fact-user-relay-draft.md`（事实挂载 + 用户/好友 + 盲中继）。
> **核心命题**：拾光处理两类用户零散输入——**硬事实**与**软背景**，必须分清，否则会把软偏好变成硬墙，违背 §13 反压迫条款。

---

## §1 定位：背景信息 ≠ 事实

| 维度 | 事实（Artifacts，见主草案 §1） | 背景信息（Background） |
|---|---|---|
| 性质 | 硬约束：时间/空间锚定的客观凭证 | 软上下文：叙事性、定性的行程背景 |
| 例子 | 车票车次、检票口、闭馆日、需带身份证 | 「陪父母看诊顺带旅游」「妈妈膝盖不好少走路」「上次大理高反严重」 |
| 对排程 | 刚性边界，引擎强制（pinned / 硬红线） | 柔性提示，仅影响 AI 软提案权重 |
| 结构化 | 强结构 JSON（hero / time / constraints） | 弱结构：自由文本 + 可选标签 |
| 主要读者 | 排程校验器 + AI | 主要 AI（带理由提案时参考） |

**一句话区分**：事实是「引擎必须遵守的规则母体」；背景是「AI 应记在心里的上下文」。

---

## §2 数据模型（✅ 方向）

挂载在 `plans` 的 `background_notes` 字段（或独立轻量表），plan 级作用域；另设可选**全局背景**（用户级，类似 `identity_prompt` 但更结构化）。

```
BackgroundNote {
  id
  scope: plan | global
  plan_id?            # scope=plan 时
  content: String     # 自由文本
  tags: List<String>  # 可选：#健康 #偏好 #历史 #目的 #环境
  created_by          # roster.id / user_id（多人下溯源）
  created_at
  ai_visible: bool    # 是否注入 AI 排程上下文（默认 true）
  source: user | ai_derived   # 用户手记 or AI 从对话提炼
}
```

- ❓ 独立表 vs `plans` JSON 字段（见 §7-Q1）。
- ❓ 全局背景与 `identity_prompt` 分工（见 §7-Q2）。

---

## §3 捕获 UX（✅ 方向）

- **入口 1：计划详情「背景」区** —— 与「事实资产」「spec」「路线图」并列的分区，用户随手记。
- **入口 2：快记 FAB 长文本** —— 用户倾倒一段背景叙述，AI/正则异步提炼为带 tag 的背景条目（复用 facts 的快记抽屉思路）。
- **入口 3：AI 对话流提炼** —— 用户对桌面 AI 说「这次行程主要是陪父母」，AI 调用 `update_plan` 写入 `background_notes`（类比 facts 的 `update_plan` 路径）。

---

## §4 AI 消费方式（✅ 方向）

- 排程时，plan 的 `background_notes`（`ai_visible=true`）作为**软上下文**注入 AI 提示词，与 `identity_prompt`（用户级）、`spec`（计划纲要）并列。
- 仅影响**提案权重与理由**，不生成硬约束：如「妈妈膝盖不好」→ AI 提案倾向少步行/多休息，但仍可被用户覆盖（不钉死）。
- 提案理由区须显式引用背景（「因背景提及高反史，已预留氧气购买缓冲」），契合 §13 反压迫条款「提案带理由」。

---

## §5 与事实体系的边界（✅ 重点）

判据：**该信息是否产生刚性时空边界？**

- 「玉龙索道 10:30 进闸」 → **facts**（硬时间锚）。
- 「这次主要看雪山，体力有限」 → **background**（软偏好）。
- 灰色带（如「建议提前 45 分到场」）→ 可同时落：facts.constraints.advance_arrival_minutes（硬派生缓冲）+ background 叙述（让人懂为什么）。

多用户下：background 同样可 `shared_scope`（public 团队背景 / private 个人健康隐私）。健康类背景默认 private（呼应主草案隐私分级）。

---

## §6 隐私与同步（❓ 待定）

- 本地优先：background 默认本地，不离开设备。
- 若启用主草案 §5 盲中继：background 作为 plan E2E 载荷一部分同步；private 标签项在本地剥离（同 facts 隐私分级）。
- ❓ 健康/隐私类背景是否默认 private 且不同步（见 §7-Q4）。

---

## §7 待定清单

- **Q1 ❓** background 独立表 vs `plans` JSON 字段？
- **Q2 ❓** global 背景与 `identity_prompt` 分工边界？
- **Q3 ❓** AI 提炼背景的准确率与误提炼回滚（是否需类似 facts.`raw_text` 兜底）？
- **Q4 ❓** 健康/隐私类背景默认 private 且不同步？
- **Q5 ❓** 背景是否参与主草案 §4 重叠解析？一般否（背景无 `entity_key`，属互补性上下文而非可去重实体）。

---

## §8 与主草案关系

- 事实（主草案 §1）= **硬**；背景（本草案）= **软**。二者互补，构成「结构化事实 + 叙事上下文」的完整用户零散信息层。
- 均经主草案 §3 用户体系归属、`§5` 盲中继同步（private 项本地剥离）。
- 两草案拍板后一并并入 SSOT；§13 修正账（主草案 §6）同时覆盖背景的同步边界。
