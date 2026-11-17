---
status: active
updated: 2026-11-16
---

# 行为清单 · friends（好友域主页，协作九页施工输入）

> §9.1.6 模板产出（2026-11-16 抽取）。抽取源 = `lib/ui/collab/friends_page.dart`（508 行全文）+ `lib/collab/relay_session.dart`（好友九业务面）+ ui-pages §8.3/§9.1 + Kotlin 就位面（RelaySession.listFriends/acceptInviteFromPeer/removeFriend/blockFriend/requestFriendByUsername 已平移；RelayFriendsException/RelayAuthException 状态码族已平移 FriendsApi.kt/AuthApi.kt）。

## 七字段

| 字段 | 内容 |
|---|---|
| 交互意图 | 好友域主页：我的名片 / 扫一扫建联 / 搜用户名加好友 / 名单三分区（待确认/已互加/已拉黑）/ 待确认处置 / 安全码核对入口——发起协同的可邀名单依赖本页建联 |
| 触发与阈值 | 点按类：「我的名片」OutlinedButton（qr_code_2 18）→MyQrPage；「扫一扫」FilledButton+页 FAB（qr_code_scanner 18）→ScanPage（返回后刷新名单）；搜索框 TextInputAction.done 提交+「加好友」FilledButton.tonal（busy 禁用+16 转圈，trim+toLowerCase 后空则忽略，searching 双闸防重）；待确认行「忽略」TextButton/「通过」FilledButton（零二次确认）；已互加行「核对安全码」TextButton+更多菜单 PopupMenu（解除好友/拉黑）；拉黑行「解除拉黑」TextButton；破坏性操作（remove/block/unblock）AlertDialog 二次确认。无手势数值阈值 |
| 状态空间 | B 档 S1–S4：S1=名单三分区渲染；S2=空态（零好友：people_outline 56+「还没有好友」17sp w600+「扫一扫出示名片，或让对方扫你的名片加一位」）与会话门禁态（session null：居中引导文「到「设置 → 协同账号」完成登录与协同身份注册，才能管理好友」，FAB 隐藏）；S3=断连（listFriends 失败：名单区顶部 error 行「暂时连不上中继，取不到好友名单」13sp，FAB 仍在）；S4=名单刷新中（loading 且 session 在=列表内静默刷新，首次进页=整页转圈） |
| 落库命令与拒绝码 | 零本地 ScheduleCommand——网络命令经 RelaySession：acceptInviteFromPeer(peer,accept)（404 特判=「这是你发出的邀请，等待对方确认」非错误，仍刷新）；removeFriend/blockFriend（错误映射 404=「这条关系已不存在，刷新一下」/400=「拉黑列表已满（上限 500），先清理一下」/401=「登录态已过期，请到「设置 → 协同账号」重新登录」/其余=「操作失败，稍后再试」；DioException=「暂时连不上中继，稍后再试」）；requestFriendByUsername（RelayAuthException 404=「没有找到这个用户名」/429=「操作太频繁，稍后再试」/401=「登录态已过期，…重新登录」/其余=「发送失败，稍后再试」；RelayFriendsException 409=「关系已存在」/429=「操作太频繁，稍后再试」/401=同上/其余=「发送失败，稍后再试」） |
| 反馈形式 | 全 SnackBar floating：成功=「已发出好友申请，等对方确认」/「已解除好友」/「已拉黑」/「已解除拉黑」；失败=上行错误映射逐字；搜索成功后清空输入框并刷新名单 |
| 黄金向量指针 | 零新向量（好友域=传输层行为测试已平移 CollabFriendsApiTest 8 例+CollabAuthApiTest 8 例承载）；FriendsTicket 解析面=CollabFriendsTicketTest |
| 像素基线指针 | B 档 friends 4 态——**manifest 现无本组，协作轮补采**（名单态需 relay 登录态资源+双账号播种） |

## §A 数据装配

| 行为 | 口径 |
|---|---|
| 会话门禁 | loadSession 钩子返回 null→引导态（文案两可：未登录/已登录无身份记录同指「设置 → 协同账号」入口，防死循环） |
| 名单 | session.listFriends() 全量，按 status 三分区：'pending'/'accepted'/'blocked'；刷新=整名单重拉（无增量） |
| 搜索 | requestFriendByUsername(trim().toLowerCase())——账号体系搜索路径，与扫码双通道并存；成功清框+刷新 |
| 关系处置 | remove/unblock 走 removeFriend、block 走 blockFriend（Dart switch 合流口径；Kotlin 同语义两 API）；处置成功后整名单刷新 |
| Kotlin 装配 | RelaySession 九业务面已平移（RelaySession.kt:63–132）；容器协同栈装配后 VM 经 lazy 会话提供者；401 桩消标随装配轮（scan.md §A 同款收口） |

## §B 骨架与组件

| 行为 | 口径 |
|---|---|
| AppBar | 「好友」；FAB extended「扫一扫建联」（仅 session 在） |
| 动作行 | 双 Expanded 按钮横排 12：「我的名片」Outlined/「扫一扫」Filled（icon 18） |
| 搜索行 | TextField（search 前缀图标 20+hint「输入对方用户名」+Outline 边框 isDense）+12+「加好友」FilledButton.tonal |
| 分区 | 「待确认」hint「对方确认后才算互加好友」/「已互加」/「已拉黑」hint「无法与其协同」——14sp w600+hint 12sp textSecondary；区块间距 20 |
| 待确认行 | Card padding(12,8)：person_outline+12+userId Expanded+「忽略」TextButton+4+「通过」FilledButton（key=accept-/decline-{uid}） |
| 好友行 | Card+ListTile：person_outline leading+userId；非拉黑=「核对安全码」TextButton+PopupMenu（more_vert 20：解除好友/拉黑）；拉黑=「解除拉黑」TextButton |
| 二次确认 | AlertDialog：标题=解除好友/拉黑/解除拉黑；正文=「解除后你们回到陌生状态，之后可以重新加」/「拉黑后对方无法再向你发起任何申请」/「解除后对方可以重新申请加你」；取消/确认 |
| 空态 | people_outline 56+「还没有好友」+引导文（居中列横向 32） |
| Kotlin 骨架 | ShiguangScaffold+TopAppBar+FAB；ListTile→ShiguangListItem 形；PopupMenu=DropdownMenu 原生面；返回后名单刷新（SavedStateHandle 结果或 onResume 重拉——随施工轮定，禁全局事件总线） |

## §C 架构接线

- 路由：`CollabFriends`（data object 零参；会话经 VM 容器取用）。
- VM：session/list/friends/error 四态 StateFlow；错误映射纯函数 JVM 可测（状态码→文案表）。
- 入口门禁对齐：member_calendar「好友」入口与会话 null 引导同口径；scan 返回刷新同款。
- 对拍发现 F1（通过/忽略零确认）：待确认处置无二次确认（直接 acceptInviteFromPeer）——设计未约定确认层，现状抽取；404 特判文案=「这是你发出的邀请，等待对方确认」（我方发出视角非错误）。
