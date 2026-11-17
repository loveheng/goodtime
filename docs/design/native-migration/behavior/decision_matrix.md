---
status: active
updated: 2026-11-16
---

# 行为清单 · decision_matrix（决策矩阵 C 页，协作九页施工输入）

> §9.1.6 模板产出（2026-11-16 抽取）。抽取源 = `lib/ui/collab/decision_matrix_page.dart`（598 行全文）+ ui-pages §8.3/§9.1（决策矩阵/门限进度条/离线催办/闭环三键/备忘置顶词条）+ Kotlin 就位面（CollabFsm/CollabReplay/CollabMaterialize/OutboxRepository 已平移 :core:model；容器协同栈装配=协作轮收口）。

## 七字段

| 字段 | 内容 |
|---|---|
| 交互意图 | 发起人聚合决策面板（§11.2 C 页）：每步 M-of-N 门限进度条 + 逐成员表决徽标 + 待确认成员催办铃铛（Nudge）+ 冲突步骤发起人闭环三键（转派/降级/裁撤）+ 顶部备忘置顶（Briefing Sticky 可编辑）+ 底部方案级 Reopen/Cancel（仅发起人有操作权，均经离线发件箱落库） |
| 触发与阈值 | 点按类：成员徽标删除钮（=Nudge，仅 pending 且未 READY）；三键 OutlinedButton；「编辑」备忘 TextButton（AlertDialog 单输入 maxLines 2）；「重新打开方案」FilledButton.tonal（仅 confirmed）；「取消方案」OutlinedButton（非 cancelled/expired）；转派/降级先弹 SimpleDialog 选人。无手势数值阈值 |
| 状态空间 | B 档 S1–S4：S1=回放派生态渲染（steps 按 stepId 字典序）；S2=空态（密钥未接入回退 empty：visibility_off 48+「暂无步骤数据」+「该包密钥尚未取到，联网补齐后即可解密回放」，头部与三键入口仍渲染）；S3=断连（壳层横幅）；S4=解密回放中（「加载中…」居中——loadEvents+replayBundle+materializeBundle 全链完成前） |
| 落库命令与拒绝码 | 零本地 ScheduleCommand——OutboxRepository.enqueue 七动作：`NUDGE{step_id,nudge_uids:[uid]}`/`REASSIGN{step_id,reassign_to:[targetUid]}`/`BYPASS{step_id,bypass_uids:[uid]}`/`PRUNE{step_id}`/`CANCEL{}`/`REOPEN{}`/`BRIEFING_UPDATE{briefing:text}`（trim 后）。零拒绝码（发件箱无即时拒绝面） |
| 反馈形式 | 七动作成功统一 SnackBar（默认样式非 floating）：NUDGE=「已催办，联网后自动发送」，其余六动作=「已记下，联网后自动发送」 |
| 黄金向量指针 | fsm_transition_vectors.json（StepFsmStatus/BundleFsmStatus 枚举→中文标签与三键显示条件语义源；REOPEN 门禁=C 类 FSM 实装拍板）；e2ee_sealing_vectors.json（replayBundle 解密回放）；零新向量 |
| 像素基线指针 | B 档 decision_matrix 4 态——**manifest 现无本组，协作轮补采**（发起人/非发起人两视图差异需登录态资源） |

## §A 数据装配

| 行为 | 口径 |
|---|---|
| 数据源 | CollabRepository.loadEvents(bundleId)→replayBundle（bundleKey 可用时）解密落库得 CollabBundleState→materializeBundle 顺手回填 collab_steps 物化表（B 页时间槽/A 页画板自此读物化表，不必持密钥） |
| 门禁 | bundleKey 不可用→回退 CollabBundleState.empty（仅头部与三键入口，逐成员表决需解密回放留显示）；isInitiator=currentUserId!=null && ==initiatorId——非发起人 actions=null 全禁用（Nudge/三键/Cancel/Reopen/编辑） |
| 进度条 | need=threshold<=0?requiredUids.size:threshold；have=acceptCount；ratio=(have/need).clamp(0,1)；READY=进度条与「N/M 人同意」用 sparkStroke，否则 textSecondary |
| 步骤标签 | ready→已达标/timeClash→时间冲突/roleClash→权责异议/optional→可选/pruned→已裁撤/其余→待确认；包标签：proposed→协商中/confirmed→已确认/stalled→待闭环/expired→已流局/cancelled→已取消/archived→已归档/abandonedLocally→本地已释放/其余→草稿 |
| 类型标签 | consensus→生活预约，否则→团队调度 |
| Kotlin 装配 | CollabReplay/CollabMaterialize 已平移（:core:model/collab）；bundleKey 经容器 KeyRing 提供（协同栈装配轮接线） |

## §B 骨架与组件

| 行为 | 口径 |
|---|---|
| AppBar | 「决策」 |
| 头部卡 | Card padding 14：类型 Chip（sparkBg）+状态 Chip（灰底）+Spacer+「N 人协同」13sp |
| 备忘置顶 | Card sparkBg padding 12：sticky_note_2 18+「备忘：发起人可置顶一段对全员的说明」13sp+发起人「编辑」TextButton→AlertDialog「编辑备忘」（TextField maxLines 2 hint「对全员的说明（选填）」+取消/保存） |
| 步骤瓦片 | Card padding 14 margin-bottom 12：「步骤 {stepId}」15sp w600+右侧状态 tag（色 12% alpha 底 6 圆角 12sp w600）；LinearProgressIndicator 高 8（灰 200 底）；「{have}/{need} 人同意」12sp；成员徽标 Wrap 6/6 |
| 成员徽标 | Chip compact：圆点 10（accepted=绿/rejected=红/pendingRecheck=琥珀/其余=灰）+label 12sp（自己=「你」，否则 uid）；canNudge 时 deleteIcon=notifications_active 14（点按=Nudge） |
| 闭环三键 | 仅 isInitiator && (timeClash||roleClash) 时渲染 Wrap 8：转派（swap_horiz 16）/降级（skip_next 16，仅存在 pending 时可点）/裁撤（cut 16）；前两键先 SimpleDialog 选人（标题「转派给」/「降级成员」，自己显示「你 (uid)」） |
| 底部 | confirmed→FilledButton.tonal「重新打开方案」；非 cancelled/expired→OutlinedButton「取消方案」 |
| Kotlin 骨架 | ShiguangScaffold+TopAppBar；进度条=LinearProgressIndicator(strokeHeight 显式)；徽标=InputChip 带尾缀图标位；对话框=ShiguangDialog 族；VM=回放派生态 StateFlow 单向流 |

## §C 架构接线

- 路由：`CollabDecisionMatrix(bundleId)`——routing_map 契约面为 data object，**参数化随施工轮同步注记**（需 bundleId+initiatorId 上下文，经 VM 从仓储补齐 initiatorId，路由只带 bundleId）。
- VM：回放链放 IO dispatcher（解密+物化）；派生态只读 StateFlow；七动作经 OutboxRepository。
- 对拍发现 F1（催办时限）：设计词条「待确认超 2h 才显铃铛+经 Wake Push 高优通知」——实现=任意 pending 即显（无 2h 时限）且经 outbox NUDGE 落库（非 Wake Push 直投）。现状口径抽取，2h 时限与 Wake Push 通道差异待裁决（规范写回 or 实现补齐）。
- 对拍发现 F2（三键显示条件）：设计词条「STALLED 步骤挂三键」——实现=步骤级 timeClash/roleClash 才挂（bundle 级 STALLED 不触发）。现状口径抽取，待裁决。
- 对拍发现 F3（备忘回显）：BriefingSticky 现状=静态提示文案+编辑入口，**不回显已存 briefing 内容**（replay 派生态有 briefing 字段但头部不消费）。现状口径抽取，回显目标态待裁决。
