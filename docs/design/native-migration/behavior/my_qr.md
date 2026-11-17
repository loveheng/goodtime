---
status: active
updated: 2026-11-16
---

# 行为清单 · my_qr（我的名片，协作九页施工输入）

> §9.1.6 模板产出（2026-11-16 抽取）。抽取源 = `lib/ui/collab/my_qr_page.dart`（94 行全文）+ `lib/collab/friends_ticket.dart`（toQrString）+ ui-pages §8.3 + Kotlin 就位面（RelaySession.issueTicket/FriendshipTicket.toQrString 已平移 FriendsTicket.kt；qr_flutter→ZXing 换轨=P3⑥ 既定，zxing-core 3.5.3 已随扫码轮入 R6）。

## 七字段

| 字段 | 内容 |
|---|---|
| 交互意图 | 我的名片（§1.6 Friendship Ticket）：端侧签发的可扫码名片含 TTL；好友扫后收到你的申请（服务端验签+过期+nonce 一次性）；签不出时「刷新名片」重试 |
| 触发与阈值 | 进页即签发（initState issueTicket，busy 转圈）；「刷新名片」FilledButton.icon（refresh 18，仅错误态显示）。无手势数值阈值 |
| 状态空间 | B 档 S1–S4：S1=名片态（QR 240 白底容器 16 圆角 padding 16）；S2=空态/错误态（「暂时签不出名片，先到「设置 → 协同账号」登录或注册」textSecondary+刷新钮）；S3=断连（签发失败归同错误态——本页单一失败口径）；S4=签发中（Center CircularProgressIndicator） |
| 落库命令与拒绝码 | 零落库——网络命令 session.issueTicket()（端侧签发 Ticket：身份私钥经安全区解封）；失败归一错误文案（无状态码细分）。零拒绝码 |
| 反馈形式 | 零 SnackBar；三态切换即全部反馈（QR/转圈/错误文+刷新钮） |
| 黄金向量指针 | 零新向量（Ticket 结构与签名=CollabFriendsTicketTest 行为测试承载；e2ee_sealing_vectors.json 覆盖信封族不含 Ticket 签名） |
| 像素基线指针 | B 档 my_qr 4 态——**manifest 现无本组，协作轮补采**（QR 内容动态，采图=容器+提示文区域，数据区随介质固定 DPR 可复采） |

## §A 数据装配

| 行为 | 口径 |
|---|---|
| 签发 | session.issueTicket()→ticket.toQrString()（JSON：issuer_user_id/subject_user_id/nonce/expires_at/signature/encKeyPub——Kotlin FriendshipTicket 已平移同构）；异常归一错误态 |
| 刷新 | 错误态点刷新钮重走 issueTicket（busy 态防重——转圈期间无钮可点） |
| Kotlin 装配 | RelaySession.issueTicket 已平移（RelaySession.kt:79）；QR 生成=ZXing QRCodeWriter（P3⑥ 换轨既定：qr_flutter→ZXing；240 逻辑像素→AndroidView 或 Compose Image 位图渲染，尺寸对位 240） |
| 会话前提 | 本页仅经 friends 会话门禁进入（session 必在）；签发失败仍可能（身份记录缺失）→错误态文案指向「设置 → 协同账号」 |

## §B 骨架与组件

| 行为 | 口径 |
|---|---|
| AppBar | 「我的名片」 |
| 说明区 | 「让好友扫这张名片」17sp w600+6+「对方会收到你的好友申请，确认后即互加成功。名片带有效期，过期重新点一下即可刷新。」textSecondary；padding 24 |
| QR 区 | Center：白底 Container（16 圆角 padding 16）内 QrImageView 240 白底；busy=转圈；错误=文案（替代 QR 位置） |
| 刷新钮 | 错误态才渲染（24 间距后 Center FilledButton.icon refresh 18「刷新名片」） |
| Kotlin 骨架 | ShiguangScaffold+TopAppBar；QR=Bitmap 渲染进 Box（zxing QRCodeWriter→ImageBitmap）；错误/忙碌互斥三态=when 单点分流 |

## §C 架构接线

- 路由：`CollabMyQr`（data object 零参；session 经 VM 容器取用）。
- VM：qr/error/busy 三态 StateFlow；issueTicket 放 IO。
- 依赖留痕：ZXing QR 生成=纯 Java core 已入（扫码轮 R6 留痕同族）；无新增依赖。
- 丢失点 #9：本页无可编辑态，零 saveable 面。
