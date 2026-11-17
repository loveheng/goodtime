---
status: active
updated: 2026-11-16
---

# 行为清单 · reject_panel（双维拒绝面板 D 页，协作九页依赖闭包——D 档浮层件）

> §9.1.6 模板产出（2026-11-16 抽取，随协作九页轮闭包——member_calendar「拒绝并说明」硬依赖）。抽取源 = `lib/ui/collab/reject_panel.dart`（324 行全文）+ ui-pages §8.1（D 档 2 态）/§9.1（双维拒绝面板/隐私分级词条）+ 协作设计 §3.4 减层拍板（2026-10-25）+ Kotlin 就位面（RejectPanel 未施工——本清单为施工输入；FSM RejectReason 枚举名 CollabFsm.kt 已平移）。

## 七字段

| 字段 | 内容 |
|---|---|
| 交互意图 | 双维拒绝面板（§11.2 D 页 §3.4 减层版）：默认一屏完事——主维度→子类芯片→可选备注→一键拒绝；破除「有空但不想做被迫撒谎说没空」的拉锯战；隐私分级与建议转派收进【详细说明】展开态（必填正是制造敷衍的根源） |
| 触发与阈值 | member_calendar 点未确认步骤→响应浮条→「拒绝并说明」→关浮条开本面板；点按类：主维度双选大卡（schedule_outlined/block_outlined）；子类 ChoiceChip 四选一；备注 TextField（maxLines 2 选填）；「详细说明与隐私（选填）」展开折叠钮（expand_more/less 18）；隐私 SegmentedButton（全员可见/仅发起人可见）；建议转派 ChoiceChip（再点取消）；「提交异议」FilledButton（**门禁=维度+子类双选齐**，submitting 禁用+16 转圈）。无手势数值阈值 |
| 状态空间 | D 档 S1–S2：S1=默认一屏态（维度未选/半选/全选三子态——提交钮随门禁禁用）；S2=展开态（advanced 区：隐私分级+说明文+建议转派）。gallery rejectD/rejectC 变体已采（批 15；rejectD==C 视觉等价=Day-2 观察项） |
| 落库命令与拒绝码 | 零直接落库——结构化 RejectResult 经 onConfirm 回调交调用方（member_calendar 落 outbox REJECT/VOTE）；reason=FSM 枚举名逐字 **TIME_CONFLICT/ROLE_UNWILLING**；subReason 四选一 **NOT_MY_JOB/PRIORITY/MISSING_PREREQ/PERSONAL**（上屏=非我职责/优先级不符/缺前置条件/个人原因）；privacy **PUBLIC/INITIATOR_ONLY**；detail trim 空→null；suggestReassign 仅建议。零拒绝码 |
| 反馈形式 | 面板本体零 SnackBar（提交后调用方关面板+反馈「已记下，联网后自动发送」）；隐私说明文随选择切换（INITIATOR_ONLY=「理由经发起人公钥单向加密，其他成员端只看到骨架提示，永远解不开。」/PUBLIC=「理由用协同包密钥加密，所有参与成员的时间轴都能看到。」12sp） |
| 黄金向量指针 | fsm_transition_vectors.json（REJECT reason/sub_reason/privacy 载荷语义源——CollabReplay signalFromPayload 分支已对拍）；e2ee_sealing_vectors.json（INITIATOR_ONLY=发起人 IK_A 单向加密封装语义源）；零新向量 |
| 像素基线指针 | D 档 reject_panel 2 态——gallery 变体已采承载（manifest gallery 组 6 内）；基线组按本单元归位随收口轮清点 |

## §A 数据装配

| 行为 | 口径 |
|---|---|
| 主维度 | time→TIME_CONFLICT「时间冲突/这个时段我已有安排」；role→ROLE_UNWILLING「权责异议/这件事不该由我来做」——选中卡=sparkStroke 1.5px 边+sparkBg 底，未选=灰 300 1px 边 |
| 转派候选 | bundle.participants 排除 currentUserId；空=「没有可转派的成员」；单选可反选（再点置 null）；尾注「这只是一个建议，由发起人决定是否转派。」12sp |
| 结果构造 | RejectResult(reason, subReason, detail=trim 空则 null, privacy 默认 PUBLIC, suggestReassign 默认 null) |
| Kotlin 装配 | RejectResult=data class（:app ui/collab 或 :core:model——随施工轮定；FSM 枚举名引用 CollabFsm 既有常量禁字符串直写）；提交链=回调交 MemberCalendarViewModel（outbox REJECT/VOTE 分流=member_calendar.md §A） |
| 减层拍板 | §3.4（2026-10-25）：隐私分级与建议转派收展开态默认折叠——禁恢复旧版全展开形 |

## §B 骨架与组件

| 行为 | 口径 |
|---|---|
| 头部 | Row：「提出异议」17sp w600+8+「步骤 {id}」textSecondary |
| 主维度卡 | 全宽 InkWell 容器（padding 14）：图标 sparkStroke+12+列（标题 15sp+副题 12sp textSecondary）；双卡间 10 |
| 子类区 | 「更具体的原因（四选一）」13sp textSecondary+10+ChoiceChip Wrap 8/8 |
| 备注区 | TextField maxLines 2 hint「补充说明（选填，发起人会参考）」Outline 边框 |
| 展开钮 | InkWell（padding 竖 8）：expand 图标 18 textSecondary+6+「详细说明与隐私（选填）」13sp |
| 展开区 | 「谁可以看到你的理由？」13sp+10+SegmentedButton（PUBLIC=全员可见/INITIATOR_ONLY=仅发起人可见）+10+隐私说明文 12sp+14+「建议转给谁？（选填）」13sp+10+候选 ChoiceChip Wrap 8/8+10+尾注 12sp |
| 底部 | Row：Spacer+「提交异议」FilledButton（submitting=16 转圈） |
| Kotlin 骨架 | ShiguangBottomSheet 浮层族（不进 NavHost）+imePadding；主体 SingleChildScrollView（Flexible）防 IME 遮挡；返回键逐层消费（丢失点 #8） |

## §C 架构接线

- 定位：浮层件（routing_map 非路由件全集已登记 RejectPanel）；宿主=member_calendar（关响应浮条→开本面板，两层 sheet 串行）。
- 草稿态：维度/子类/隐私/转派/备注/展开态 rememberSaveable（丢失点 #9——进程重建抽屉态不丢）；detail 输入走 TextField value 提升。
- 对拍发现 F1（一致性候选）：D26 拍板「ADD_SLOT 重复候选治理」属 P5 Day-2（本面板无涉）；转派候选=participants 全列排除自己（不排除已拒绝者）——现状口径保持。
- 对拍发现 F2（Consenus 分流）：本面板输出 REJECT 结构化结果；CONSENSUS 包拒绝时调用方改走 VOTE{preference:'NO'}（member_calendar.md §A 分流）——面板自身无 proposalType 分支（保持单一职责）。
