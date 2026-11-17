---
status: active
updated: 2026-11-16
---

# 行为清单 · audit_timeline（审计时间轴 F 页，协作九页施工输入）

> §9.1.6 模板产出（2026-11-16 抽取）。抽取源 = `lib/ui/collab/audit_timeline_page.dart`（379 行全文）+ ui-pages §8.3/§9.1（审计时间轴/步骤便签/私密骨架提示词条）+ `lib/data/collab_repository.dart` loadEvents（seq_id ASC 实证）+ Kotlin 就位面（CollabRepository.loadEvents/OutboxRepository 已平移 :core:model）。

## 七字段

| 字段 | 内容 |
|---|---|
| 交互意图 | 无输入框的只读垂直时间树（§11.2 F 页）：方案从发起、响应到终态全程留痕；事件气泡结构化（动作图标+执行者+时间+拓扑引用）；异议附半盲骨架提示；AppBar「步骤便签」钮→选步骤+≤50 字覆盖式更新抽屉 |
| 触发与阈值 | 点按类：AppBar note_add 图标钮（tooltip「步骤便签」）→便签抽屉；抽屉内 ChoiceChip 选步骤+TextField（maxLength 50，maxLines 2）+「保存便签」FilledButton（**门禁=已选步骤 && trim 非空**，saving 禁用）。无手势数值阈值 |
| 状态空间 | B 档 S1–S4：S1=事件气泡树渲染；S2=空态（timeline_outlined 56+「还没有动态」17sp w600+「方案从发起、响应到终态的全过程都会留在这里。」）；S3=断连（壳层横幅；本页零网络）；S4=加载中（「加载中…」居中）；便签抽屉=浮层态不进路由 |
| 落库命令与拒绝码 | 零本地 ScheduleCommand——OutboxRepository.enqueue `STEP_NOTE{step_id,note}`（覆盖式更新：单步骤一条，密文随事件流入库）。零拒绝码 |
| 反馈形式 | 便签保存成功：关抽屉+SnackBar「已记下，联网后自动发送」（默认样式）；事件气泡=只读零反馈；时间格式 MM-dd HH:mm（ms<=0 显示空） |
| 黄金向量指针 | fsm_transition_vectors.json（17 类动作枚举与半盲投影语义源）；e2ee_sealing_vectors.json（STEP_NOTE/理由密文封装语义源）；零新向量 |
| 像素基线指针 | B 档 audit_timeline 4 态——**manifest 现无本组，协作轮补采**（事件流需播种协同包事件） |

## §A 数据装配

| 行为 | 口径 |
|---|---|
| 事件源 | CollabRepository.loadEvents(bundleId)——**seq_id ASC 权威序**（仓储实证；UI 不重排不倒序）；只渲染明文骨架（action_type/sender_id/server_at_ms/topology_ref），密文理由/便签不在本页解密（§4.2 铁律 1 半盲投影） |
| 执行者显示 | senderId==currentUserId→「你」，否则原样 uid |
| 半盲提示 | 仅 actionType=='REJECT' 且 senderId!=currentUserId→附「已私密反馈发起人，等待调整」12sp textSecondary（本人可见自己理由故不附） |
| 动作映射 | 17 项逐字：PROPOSE=发起了方案(rocket_launch)/ACCEPT=同意了这一步(check_circle)/REJECT=提出了异议(report_problem)/PATCH_TIME=调整了时间(schedule)/REASSIGN=转派了这一步(swap_horiz)/MAKE_OPTIONAL=将这一步设为可选(remove_circle)/BYPASS=跳过了这一步(skip_next)/PRUNE=裁撤了这一步(cut)/REOPEN=重新打开了方案(replay)/CANCEL=取消了方案(cancel)/VOTE=投了一票(how_to_vote)/ADD_SLOT=增加了候选时段(add_circle)/FORCE_FINALIZE=落槌定了时段(gavel)/NUDGE=催促了待确认成员(notifications_active)/BRIEFING_UPDATE=更新了备忘(sticky_note_2)/STEP_NOTE=记录了便签(note)/未知=原样动作名(circle) |
| 便签步骤源 | 页面构造传入 steps（member_calendar 的 listSteps 投影）；非本页自取 |
| Kotlin 装配 | loadEvents 已平移（CollabRepository.kt）；VM=events StateFlow 单向流；STEP_NOTE 经 OutboxRepository |

## §B 骨架与组件

| 行为 | 口径 |
|---|---|
| AppBar | 「时间轴」+便签图标钮 |
| 气泡行 | IntrinsicHeight Row：左轨=动作图标 18 sparkStroke+连接线 2dp 灰 300（末条无线）；右侧=气泡容器（sparkBg 圆角 12 padding 12）：「{who} {动作标签}」14sp+4+时间 12sp textSecondary+可选骨架提示 12sp |
| 便签抽屉 | 底部 sheet imePadding：标题「步骤便签」17sp w600+说明「给某一步加一条 ≤50 字的备注，覆盖式更新，经加密随事件流入库。」12sp+14+步骤 ChoiceChip Wrap 8/8（「步骤 {id}」）+12+TextField（hint「备注内容（选填要点，不形成聊天流）」Outline 边框 maxLength 50）+14+「保存便签」FilledButton（saving=16 转圈） |
| 空态 | timeline_outlined 56+标题+说明（居中列横向 32） |
| Kotlin 骨架 | ShiguangScaffold+TopAppBar；时间树=Column 左轨+Canvas 线或 Box 连接线（禁 CustomPaint 过度设计——2dp Box 即可）；抽屉=ShiguangBottomSheet 浮层族；maxLength 计数=material3 TextField supportingText 自带 counter |

## §C 架构接线

- 路由：`CollabAuditTimeline(bundleId)`——routing_map 契约面为 data object，**参数化随施工轮同步注记**（bundleId 必需；steps 由 VM 从仓储取）。
- VM：loadEvents 单向流；便签草稿（stepId+text）rememberSaveable（丢失点 #9——抽屉重建不丢）。
- 对拍发现 F1（拓扑引用未渲染）：事件气泡现状不含 topology_ref 内容（结构化注释含但 UI 只渲染 who/动作/时间/骨架提示）；语义面留半盲口径不泄露内容——现状抽取，零裁决项。
- 对拍发现 F2（本地事件时间戳）：serverAtMs<=0（本地未上行事件）显示空时间——现状口径保持（禁以本地 now 回填，防时钟直取违例）。
