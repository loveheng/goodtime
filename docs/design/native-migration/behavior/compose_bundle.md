---
status: active
updated: 2026-11-16
---

# 行为清单 · compose_bundle（发起协同，协作九页施工输入）

> §9.1.6 模板产出（2026-11-16 抽取）。抽取源 = `lib/ui/collab/compose_bundle_page.dart`（558 行全文）+ `lib/collab/propose.dart`（BundleProposalService/composeSentOut/composeFailureText 语义源）+ ui-pages §8.3/§9.1 + Kotlin 就位面（CollabPropose.kt=BundleProposalService/BundleDraft/ProposalStepDraft/ProposalKind 全量平移；Outbox.kt=OutboxDrainer/DrainReport/composeSentOut 同文件承载）。

## 七字段

| 字段 | 内容 |
|---|---|
| 交互意图 | 发起协同（§11.2 A 页发起端）：三块表单三件事——**和谁一起**（只能挑双向 accepted 好友，服务端 PROPOSE 复检准入）/ **怎么定**（各司其职 Directive / 大家投票 Consensus）/ **做什么**（步骤或候选时段）；「发出邀请」后全部 work 交 BundleProposalService（造 K_bundle→逐成员封信封→落发件箱→尝试弹出），UI 只翻译结局 |
| 触发与阈值 | 点按类：FilterChip 好友多选（选中即全步骤必答默认加入，仅 directive）；SegmentedButton 双选 kind（consensus 切换重置步骤必答/阈值/可选态）；步骤卡「设置时段」TextButton→DatePicker（firstDate=今天，lastDate=+365 天）→TimePicker 开始（默认 10:00）→TimePicker 结束（默认=开始+1h，(hour+1)%24 同分钟）；「再加一步/再加一个候选」TextButton；步骤卡 close 钮（仅 steps>1）；「发出邀请」FilledButton（sending 禁用）。无手势数值阈值 |
| 状态空间 | B 档 S1–S4：S1=表单态（好友名单加载完）；S2=空态（零好友：「还没有互相确认的好友」13sp+「去好友里加一位」TextButton person_add_alt_1 16——发起页名单永空则协同不可达的引导口）；S3=断连（好友加载失败：顶部 error 行「暂时连不上中继，取不到好友名单」13sp，表单仍可用）；S4=发出中（提交钮「正在发出…」禁用；loading 态=整页 CircularProgressIndicator） |
| 落库命令与拒绝码 | 零本地 ScheduleCommand——BundleProposalService.propose（装箱：取成员公钥联网+K_bundle 落户+发件箱落盘，任一步失败=**箱里一条都没有**）；此后 drainNow 尝试弹出（失败条目原样保留）。失败翻译 composeFailureText：RelayAuthException/RelayPeersException 状态码 401=「没发出去：登录态已过期，请到「设置 → 协同账号」重新登录」/404=「没发出去：取不到对方的通信公钥，让对方重新登录一次再约」/429=「没发出去：操作太频繁，稍后再试」/其余=「没发出去：中继打回了这次邀请（{code}），稍后再试」；DioException=「没发出去：暂时连不上中继，联网后再试一次」；ArgumentError=e.message 原文；其他=`没发出去：{runtimeType} {e}`（release 无日志，屏面句即现场证据） |
| 反馈形式 | 落箱成功：SnackBar floating「已发出，对方会收到一条邀约」（drain 未停且 created 非空）或「已记下，联网后自动发出」+pop 返回；装箱失败：SnackBar floating 错误翻译（上行）停留本页；drain 异常=条目仍在箱，按「已记下，联网后自动发出」反馈（不许说已发出） |
| 黄金向量指针 | e2ee_sealing_vectors.json（K_bundle 信封逐成员封装）；relay_proto_vectors.json（PROPOSE 建包帧抬字段）；fsm_transition_vectors.json（落包初态 proposed）；零新向量 |
| 像素基线指针 | B 档 compose_bundle 4 态——**manifest 现无本组，协作轮补采**（好友名单态需 relay 登录态资源） |

## §A 数据装配

| 行为 | 口径 |
|---|---|
| 名单源 | kit.loadFriends()（RelaySession.acceptedFriends——双向 accepted 才进名单）；失败置 error 不清名单 |
| 步骤草稿 | 开局自动加一条空步骤（stepId='s{n+1}' 局部序号，不留空白 workflow）；draft.localId='local-{微秒36进制}'；标题提交时 trim |
| 成员联动 | 选中新邀好友→directive 下默认加入**每个已存在步骤**必答名单；取消选中→从全部步骤必答名单移除；逐步骤可再勾/取消（FilterChip required-{i}-{uid}） |
| kind 切换 | consensus→所有步骤重置（requiredUids/threshold/isOptional 清空=FSM 缺省全员）；section 标题随之「要做什么」↔「候选时间」；加步钮文案「再加一步」↔「再加一个候选」；输入 hint「这一步做什么」↔「候选时段名（如「周六下午」）」 |
| 时段格式 | 已设置显示「M/d HH:mm – HH:mm」（时补零）；步骤块仅显示必答名单（consensus 不显示名单区） |
| 回执判定 | composeSentOut(report)=report!=null && !stoppedByNetwork && created.isNotEmpty——纯函数 JVM 可测（Kotlin Outbox.kt 已平移） |
| Kotlin 装配 | BundleProposalService/BundleDraft/ProposalStepDraft 已平移（CollabPropose.kt 336 行）；容器协同栈装配后 VM 经 kit 提交（drainNow 钩子注入） |

## §B 骨架与组件

| 行为 | 口径 |
|---|---|
| AppBar | 「发起协同」 |
| 区块标签 | 14sp w600+可选 hint 12sp textSecondary（「和谁一起」+未选时 hint「至少选一位」）；区块间距 20 |
| 好友区 | FilterChip Wrap 8（key=contact-{uid}，label=uid）；零好友空态双行（文案+引导钮 key=compose-add-friend） |
| kind 区 | SegmentedButton「各司其职」/「大家投票」 |
| 步骤卡 | Card margin-bottom 8 padding(12,8,12,12)：标题 TextField（无边框 isDense+hint）+close 钮 18；「设置时段」TextButton schedule 16；directive 名单 FilterChip Wrap 6 |
| 提交钮 | FilledButton key=compose-submit 全宽（文案「发出邀请」/「正在发出…」） |
| Kotlin 骨架 | ShiguangScaffold+TopAppBar；FilterChip=material3 FilterChip；时段 picker=DatePicker/TimePickerDialog 原生（返回链逐层消费=丢失点 #8）；输入暂存 rememberSaveable（丢失点 #9）；标题 List 保持控制器生命周期对位（Kotlin 用 remember + TextField value 态提升，禁 TextEditingController 逐卡泄漏形） |

## §C 架构接线

- 路由：`CollabComposeBundle`（data object；proposer/meId/onAddFriend 经 VM+容器注入，路由零参）。
- VM：名单加载/草稿态/发送三段 StateFlow；propose 链放 IO；失败翻译纯函数 JVM 测试面（composeFailureText/composeSentOut 已在 Kotlin Outbox.kt 平移）。
- 会话门禁：本页仅经 member_calendar kit 门禁进入（kit null 不导航）；零好友引导 onAddFriend→好友页（CollabFriends）。
- 对拍发现 F1（结束时间跨日）：开始 23:xx 时结束默认=(0:xx 次日)——(hour+1)%24 无跨日标记，DateTime(y,m,d,h,m) 同日构造→**23:30 开始时结束默认落同日 00:30（早于开始）**。现状怪点登记，待裁决（修实现 or 修规范注记）。
