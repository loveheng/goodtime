---
status: active
updated: 2026-11-16
---

# 行为清单 · safety_number（核对安全码，协作九页施工输入）

> §9.1.6 模板产出（2026-11-16 抽取）。抽取源 = `lib/ui/collab/safety_number_page.dart`（142 行全文）+ `lib/collab/friends_ticket.dart`（safetyNumberDigits 双端在位：Dart:130/Kotlin FriendsTicket.kt:136）+ ui-pages §8.3/§1.6 + Kotlin 就位面（RelaySession.myIdentityPublicKey/peerIdentityPublicKey 已平移 RelaySession.kt:71/:117）。

## 七字段

| 字段 | 内容 |
|---|---|
| 交互意图 | 安全码核对（§1.6 Safety Number）：双方身份公钥哈希指纹 6 组 5 位数字；当面或微信核对一致即确认对方身份未被中间人替换（中继不可信，端侧可验证） |
| 触发与阈值 | 进页即计算（initState 双端公钥拉取）；「重试」FilledButton.icon（refresh 18，仅错误态）。无手势数值阈值 |
| 状态空间 | B 档 S1–S4：S1=数字网格态（6 组双列）；S2=错误态（「暂时取不到对方公钥，连上中继后再试」textSecondary+16+重试钮）；S3=断连（公钥拉取失败归同错误态——本页单一失败口径）；S4=计算中（Center CircularProgressIndicator） |
| 落库命令与拒绝码 | 零落库——网络读取 session.myIdentityPublicKey()+session.peerIdentityPublicKey(friend.userId)；失败归一错误文案。零拒绝码 |
| 反馈形式 | 零 SnackBar；三态切换即全部反馈；说明文（17sp w600 标题行+textSecondary 警示行）常驻 |
| 黄金向量指针 | 零新向量——**边界候选登记待评审**：safetyNumberDigits(固定 myIk,peerIk) 输入输出对可入 e2ee 族或独立组（AI 候选，语义人工确认后固化，禁自证闭环） |
| 像素基线指针 | B 档 safety_number 4 态——**manifest 现无本组，协作轮补采**（数字内容可固定种子公钥播种） |

## §A 数据装配

| 行为 | 口径 |
|---|---|
| 指纹 | safetyNumberDigits(myIkPub, peerIkPub)→List<String> 6 组（每组 5 位数字；派生纯函数双端已平移同构） |
| 顺序 | 第 i 格 label=(i+1)（1 基序号）；网格=两列行序（行内左 i 右 i+1，奇数总长末位空） |
| 会话前提 | 本页仅经 friends「核对安全码」入口进入（friend 参数必带）；公钥拉取失败→错误态 |
| Kotlin 装配 | RelaySession.myIdentityPublicKey/peerIdentityPublicKey 已平移；peerIdentityPublicKey 走 KeysApi 联网——IO dispatcher；错误态重试=整链重跑 |

## §B 骨架与组件

| 行为 | 口径 |
|---|---|
| AppBar | 「核对安全码」 |
| 说明区 | 「和 {friend.userId} 当面或微信核对以下数字」17sp w600+6+「六组数字完全一致，即确认对方身份未被中间人替换；不一致请勿继续协同。」textSecondary |
| 数字网格 | 两列 Row（间距 12 行距 12）：格=sparkBg 圆角 12 padding(16,12) 行内两端——label 13sp textSecondary+数值 22sp w700 letterSpacing 2 |
| 错误态 | 居中列：错误文+16+「重试」FilledButton.icon refresh 18 |
| Kotlin 骨架 | ShiguangScaffold+TopAppBar；网格=Column+Row weight 双列（奇末位 Spacer）；格=ShiguangCard 变体或 Box+StColors.sparkBg |

## §C 架构接线

- 路由：`CollabSafetyNumber(friendUserId)`——routing_map 契约面为 data object，**参数化随施工轮同步注记**（friend.userId 必需；friend 元数据由 VM 经 listFriends 过滤或最小参传递——随施工轮定，禁整对象路由序列化）。
- VM：digits/error/busy 三态 StateFlow；公钥双拉放 IO 并行或顺序（现 Dart 顺序 await——保持顺序口径免并发语义改动）。
- 边界候选（登记待评审，不自行固化）：固定公钥种子的 6 组期望值向量——破「对拍自证闭环」需人工确认哈希派生语义后入集。
