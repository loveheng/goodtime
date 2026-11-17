---
status: active
updated: 2026-11-16
---

# 行为清单 · member_calendar（协同日程 B 页，协作九页施工输入）

> §9.1.6 模板产出（2026-11-16 抽取）。抽取源 = `lib/ui/collab/member_calendar_page.dart`（567 行全文）+ `lib/ui/collab/reject_panel.dart`（D 页闭包，另出 reject_panel.md）+ ui-pages §8.3（B 档 4 态）/§9.1 协同文案节 + Kotlin 就位面（CollabRepository/OutboxRepository/RelaySession/BundleComposeKit 全量已平移 :core:model；容器协同栈装配=协作轮收口）。

## 七字段

| 字段 | 内容 |
|---|---|
| 交互意图 | 协同日程页（§11.2 B 页）：好友发来的协同包按**三态预占**列出（待确认/候选/已确认）；点未确认步骤弹一键响应浮条（同意/拒绝并说明，拒绝走双维拒绝面板）；卡片「时间轴」「决策」双入口；AppBar「好友」入口 + FAB「发起协同」 |
| 触发与阈值 | 点按类：未确认/候选步骤瓦片点按→响应浮条（CONFIRMED 态瓦片 onTap=null 不可点）；「时间轴」「决策」TextButton；AppBar 好友图标钮；FAB 发起协同。无手势数值阈值 |
| 状态空间 | B 档 S1–S4：S1=包列表加载完（加载中态=「加载中…」居中文本）；S2=空态（groups_56 图标+「还没有协同日程」+「和朋友约起来，邀约会混合显示在这里——待确认、候选、已确认三态一眼分清。」）；S3=断连与重试（本页无显式横幅——relay 断连横幅属壳层全局件）；S4=解密回放中（本页读物化表不解密，无独立呈现） |
| 落库命令与拒绝码 | 零本地 ScheduleCommand——响应经**离线发件箱** OutboxRepository.enqueue：CONSENSUS 同意=`AVAILABILITY{step_id,decision:'OK'}` / DIRECTIVE 同意=`ACCEPT{step_id,decision:'OK'}`；CONSENSUS 拒绝=`VOTE{step_id,preference:'NO',reason,detail?}`（detail 非空才带）；DIRECTIVE 拒绝=`REJECT{step_id,reason,sub_reason,detail?,privacy,suggest_reassign?}`；联网由常驻调度弹出。零拒绝码（发件箱无即时拒绝面） |
| 反馈形式 | 同意/拒绝成功：关浮条+SnackBar「已记下，联网后自动发送」；kit null：未登录→「先到「设置 → 协同账号」登录，才能发出邀约」/已登录无身份→「本机协同身份未就绪，到「设置 → 协同账号」重新注册」（floating）；好友会话 null→「先到「设置 → 协同账号」登录，才能管理好友」（floating） |
| 黄金向量指针 | fsm_transition_vectors.json（PROPOSED/CONFIRMED/STALLED/EXPIRED/CANCELLED 与步骤态→中文标签映射语义源）；零新向量（响应动作=outbox 落库，传输面已覆盖） |
| 像素基线指针 | B 档 member_calendar 4 态——**manifest 现无本组，协作轮补采**（relay 登录态资源前置；40/84 现状） |

## §A 数据装配

| 行为 | 口径 |
|---|---|
| 列表源 | CollabRepository.listBundles() 全量包 + 逐包 listSteps(id)（两次查询，非 JOIN）；无分页 |
| 三态映射 | tier：status=='CONFIRMED'→confirmed；否则 proposalType=='DIRECTIVE'→hardTentative；否则→softCandidate（proposalType 存储字面量 'CONSENSUS'/'DIRECTIVE'） |
| 状态标签 | 'PROPOSED'→协商中/'CONFIRMED'→已确认/'STALLED'→待发起人闭环/'EXPIRED'→已流局/'CANCELLED'→已取消/其余原样显示 |
| 类型标签 | 'DIRECTIVE'→团队调度，否则→生活预约 |
| 发起入口门禁 | FAB 仅 loadComposeKit 可用时显示（kit=身份私钥+MSK 双齐）；好友入口同理（loadFriendsSession）——缺任一**不显示按钮**（不给必然失败的入口） |
| 会话刷新 | 返回本页（compose pop 后）重拉 listBundles/listSteps（认祖归宗成功的包立刻出现） |
| Kotlin 装配 | 容器协同栈未装配（协作轮收口）：buildBundleComposeKit/buildFriendsSession 已平移（RelaySession.kt:154/:197），入 AppContainer 后 VM 经 lazy 会话提供者取用 |

## §B 骨架与组件

| 行为 | 口径 |
|---|---|
| AppBar | 「协同日程」+ actions 好友图标钮（people_outline，tooltip「好友」，仅会话钩子可用时显示） |
| 包卡 | Card elevation 1 padding 14：头行=类型 Chip（sparkBg 底）+状态 Chip（灰底）+Spacer+「N 人协同」（13sp textSecondary）；次行右对齐双 TextButton「时间轴」（timeline_outlined 16）「决策」（account_tree_outlined 16）；步骤区=逐瓦片或「暂无步骤」 |
| 步骤瓦片 | 圆角 10 容器 margin-bottom 8 padding 横12竖10：confirmed=实底 sparkBg+1px sparkStroke 边；hardTentative=透明底+1.5px sparkStroke 边；softCandidate=透明底+1px 灰边；右侧徽标（已确认/待确认=sparkStroke，候选=textSecondary；12sp w600，色 12% alpha 底 6 圆角）+可点时 chevron_right 18 |
| 响应浮条 | 底部 sheet「回应这一步」（17sp w600）+「步骤 {id} · {类型}」（textSecondary）+16+FilledButton「同意」+8+OutlinedButton「拒绝并说明」；imePadding 避让 |
| 空态 | groups_outlined 56 textSecondary+16+「还没有协同日程」（17sp w600 textPrimary）+8+引导文（textSecondary 居中，横向 32） |
| Kotlin 骨架 | ShiguangScaffold+TopAppBar；瓦片=ShiguangCard+BorderStroke（1dp/1.5dp 显式）；响应浮条=ShiguangBottomSheet（浮层族，不进 NavHost）；返回键逐层消费（丢失点 #8 已建机制） |

## §C 架构接线

- 路由：`CollabMemberCalendar`（routing_map 契约面既有）；入参=零（页内自取全量包列表）。
- VM（D15 形）：StateFlow（loading/bundles/steps）+ 命令经 OutboxRepository 直落（非 CommandHandler——协同响应不走本地命令面，属协同编排面）。
- 分期对拍 F1：ui-pages §9.1「邀约与本地日程混看」目标态=时间轴叠块；实现现状=**列表卡片形态**（施工钉子=collab_steps 时间槽物化后叠块另切片）。清单按现状抽，叠块目标态登记 ui-pages 分期注记随施工轮写回（D25 M1/M4 同款口径）。
- reject_panel 依赖：D 页双维拒绝面板随本页施工闭包（清单另出 reject_panel.md）。
