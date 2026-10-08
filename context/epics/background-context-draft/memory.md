---
dev-loop: memory
format: v1
epic: background-context-draft
total-merged: 0
last-merge: none
---

- [挂载] docs/design/background-context-draft.md

# background-context-draft —— 背景信息记录（backgrounds）施工

状态只记进度/决策/指针，内容 SSOT=挂载文档（2026-10-07 定稿：Q1–Q6 全决议＋七轮施工钉子）。主草案 `fact-user-relay-draft.md` 2026-10-07 评估+拍板落定（七项+四洞察通过、D2 未归属池宿主=挂载 sheet 常驻组、schema 校准 v4），fact 侧**施工仍另批**待开工指令；两草案交叉引用以挂载文档 §8 为准。

## 进度

- [2026-10-07] 主草案评估+拍板（用户指令挂载本 epic，对照已落地实现交叉评审）：账实对照过（db v3 已被背景侧占用→artifacts 校准 **v4**；快记通道前提=QuickCaptureCommand title 无限长原样落 plan+list_plans 携 title，机械验证成立）；发现 F1–F6（F1 未归属池宿主→D2 裁决、F2 v4 校准、F3 event_date 残留→锚点最晚日历日派生折叠、F4 artifacts 手编契约缺节→施工钉子轮对齐背景侧补节、F5 未归属池无预算/F6 plan_id 列与 payload 双写=观察项）；用户裁决 **D2=未归属池宿主挂载 sheet 常驻组**、**D1=§0 七项+四洞察全部拍板通过** → 主草案转 active 定稿+回改 10 处、背景草案 §2 联动句回改、schedule-app §11 新增「事实挂载（artifacts）」「事实摄入与凭证 UI（upsert_facts）」两行、README 状态行同步；pending-confirm D2/D1 清零。fact 施工切片等开工指令（依赖序见主草案 §8）。
- [2026-10-07] 详情页形态拍板（用户实测反馈：愿望池计划背景多而杂乱，sheet 位置不合适）：**ui-spec §5 全页详情页提前落地**——编辑 sheet 浮层退役，点卡进 `PlanDetailPage` 全页路由（四字段/open_items/子树/排期/保存+背景区随迁），长按归档/删除不变；背景区文案零变动，台账行已留痕（schedule-app §11）。
- [2026-10-07] fact 草案开工前评审定稿修正（用户确认外部评估报告后回改 8 项，前轮 F6 双写观察项随之闭环）：①未归属池增计划列表页「N 条未归属凭证」派生提示行+管理面板（复用挂载 sheet 常驻组形态，D2 宿主不变）②plan_id/block_id 双写铁律（detach/改挂/AI 关联块同事务同步列与 JSON）③get_schedule 搭载窗对齐 days 视窗（3 天上限外=已知边界）④删除/作废三件套（state 增 voided 存证保留+DeleteArtifact 仅 human+删凭证绝不删块=降格 pinned）⑤v3 残留 4 处校 v4/v5⑥badge UI 防御 maxWidth 96dp⑦跨午夜绝对分钟折算⑧hero_metrics>3 截断；fact 草案+schedule-app §11 两行同步。
- [2026-10-08] **切片 6（凭证 UI）完工**：①契约留痕四处（fact 草案 §1.2/§1.7 等出生确认条款+schedule-app §11 台账行）②命令层「出生确认」——raw 建档强制 verbal/verbal 占位（零解析猜类别=第二真相，拒）、首次 raw→structured 回填允许定 category/source_kind 终值（单事务同写列）、structured 后恒不可变（patch 白名单外）；UpsertFactsCommand category/sourceKind 改可空（建档必填校验移至命令层，编辑缺省=沿用现值；fromJSON/MCP schema 必填口径不变）③`voucherSurface` token 双主题（近 spark 琥珀非庆祝金）+ui-spec §0.1/§0.5 留痕 ④`lib/ui/fact_sheet.dart` 新建——Slot Assembler 通关卡（Header 药丸/Hero voucherSurface 空整层消失/时空网格 deadline ⚠/约束 Chip 🚫中性灰绝不用红/动作行复制·拨号·导航零权限/查看原文手风琴）+FactCard 紧凑卡（raw 琥珀/verbal 弱化）+showVoucherSheet+挂载 sheet（预填原文+计划列表+「先记下，稍后归属」+「未归属 N 条」常驻组）+未归属面板（Dismissible 删除+长按改挂）⑤计划详情「随行凭证」区（子树聚合/raw 聚合琥珀卡「N 条原文待提炼」/「过去的凭证」折叠组/长按删除二次确认）⑥计划列表顶部「N 条未归属凭证 〉」派生提示行（N=0 隐藏）⑦日程页 🎫 微标（badge 文本）+block_sheet 块浮层嵌通关卡（同源 FactDetailSheet，「马上开始」下首屏）⑧分享入口——AndroidManifest ACTION_SEND+text/plain intent-filter（零新权限）+MainActivity onNewIntent 转投（UTF-8 JSON 串过 StandardMessageCodec，原生零解析）+Dart BasicMessageChannel 消费→AppGate 首帧后 raw 入册（origin=shared）→挂载 sheet ⑨ui-spec §0.4 补 5 词条（通关卡动作行/挂载 sheet/改挂 sheet/未归属面板空态/删除二次确认）。
- [2026-10-07] 自 m1 切绑建档（用户指令：按挂载文档施工）。施工拆解（照主草案 §8 依赖序裁剪为背景侧）：
  1. 文档先行：schedule-app §11 台账行＋ ui-spec §0.4 词汇/§5 计划详情「背景」区＋ golden-samples #4 增补背景走查幕
  2. db v3：backgrounds 单表先行（9+1 字段＋captured_by 钉子；「与 artifacts 同批 v3」调整为分期迁移）＋ 导出 JSON 带全
  3. 命令层：UpsertBackground（applicable_dates 正则+tryParse 双检/空数组归一 null/去重排序）＋ 全局预算终态双指标（条数>8 或 content 总字数>400，String.length 单点，仅拦 ai actor、拒绝体携全量现场，human 放行）＋ 归并原子批处理命令（ops 数组单事务终态校验）＋ delete_plan 扩展级联销毁背景
  4. 工具面：upsert_background schema（工具描述写明 applicable_dates 格式与预算口径）＋ get_settings 携全局背景/预算态＋ plan 快照携 plan 背景
  5. 装配层：排程装配物理过滤（当日 ∈ applicable_dates，无窗恒注入）＋ 治理/对话装配全量带 [已过期] ＋ 祖先链继承 ≤3 级 ＋ 多计划分区装配（分组禁平铺/日级态势句不带背景）＋ 特异性优先 inherit_note 静态注记 ＋ 预算常量入 rules.dart 单一出处
  6. UI：计划详情「背景」区（日期角标 [起–止] 派生渲染/超期沉「过去的背景」折叠组/组内逐条删+批量清理）＋ 手编契约（raw 永不变/source 出生来源不可变/version CAS）
  7. playbook 收录使用纪律 ＋ 测试（一测一文件，repository/commands/tools/ui 分层）
  入口 2（快记 FAB 长文本/系统分享三刀路由兜底落背景）依赖主草案摄入管道，挂账待其拍板后接入；入口 1（计划详情区）与入口 3（AI 提炼回显）本 epic 内闭环。

## 口径决策

- 迁移批次调整（2026-10-07，随开工指令生效）：挂载文档 §2「与 artifacts 同批 schema v3 迁移」→「backgrounds 先行 v3 单表迁移，artifacts 拍板落地时另批」。依据：用户指令背景侧先行施工，主草案 §0 七项尚未拍板；db.dart 分段幂等迁移为既有能力，CREATE TABLE schema 两案全同，仅批次号与时点变化。主草案拍板并入 SSOT 时回改挂载文档该句。

## 近期验证状态

- [2026-10-08] 切片 6 全量验证：`flutter analyze` 0 issue（修掉 26 个编译错，含 app_services 分享通道 API、StColors 非 const 不能进 const TextStyle、SelectableText 无 overflow/maxLines、UpsertFactsCommand 可空适配）；`flutter test` **163/163 全绿**（161→163：新增「raw 建档非 verbal 被拒」「建档缺 category 被拒」「structured 后类别恒不可变」3 条出生确认用例，3 条 AI 结构化建档补 `state:'structured'`）；arch-guard 6/6；docs-lint 过（updated 软自查已刷 4 文件）。

## 断点

- [断点] **fact 六切片全完工**（1 文档/2 db v4/3 命令层/4 工具面/5 playbook/6 凭证 UI），全部工作区未提交。下一步：①用户验收凭证 UI（真机分享走查：微信分享短信→拾光→挂载 sheet 归属→凭证区「N 条原文待提炼」→AI 提炼→通关卡，金样本 #4 幕）②提交（commit-msg-gate 已过口径）③背景侧真机验收仍挂账。已知边界：get_schedule 搭载窗 3 天上限、`hero_metrics` 微标 96dp 硬截断、🎫 微标仅 booking 类显 badge。
