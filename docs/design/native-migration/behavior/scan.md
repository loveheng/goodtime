---
status: active
updated: 2026-11-16
---

# 行为清单 · scan（扫一扫建联，P4 扫码页施工输入——夜间档抽取，D26 自裁默认待晨间复核）

> §9.1.6 模板产出（2026-11-16 ::auto night 抽取）。抽取源 = `lib/ui/collab/scan_page.dart`（209 行全文）+ `docs/design/ui-pages.md` §8.3（B 档 4 态）+ `lib/collab/friends_ticket.dart`（Ticket 解析）+ `lib/collab/relay/friends_api.dart`（邀请错误码）+ Kotlin 现状（FriendsTicket.fromQrString/RelaySession.submitInvite/RelayFriendsException 已平移；容器协同栈=协作轮装配挂账）。**范围 = 扫码页本体（相机预览/权限态/手动兜底/错误映射/提交链）**；协同栈装配面随协作轮（本页 submit 经容器 lazy 会话，未登录走 401 文案路径）。

## 七字段

| 字段 | 内容 |
|---|---|
| 交互意图 | 扫一扫建联（§1.6）：扫对方名片二维码 → 解析 FriendshipTicket → submitInvite 好友邀请；扫码失败/无相机时「手动填入名片串」兜底（同为 Ticket JSON） |
| 触发与阈值 | 相机检测到二维码（rawValue 非空）即处理；**_done/_submitting 双闸防重**（一码一次）；手动提交=trim 非空才走 |
| 状态空间 | B 档 4 态映射：S1 相机预览态（+「把对方名片放进框里」提示）/ S2 权限未授予态（camera_alt 图标+「需要相机权限才能扫码」+「授予相机权限」钮）/ S3 提交中态（预览上层 CircularProgressIndicator）/ S4 错误条态（底部黑底白字 Toast 条）；手动区恒在（Divider+「手动填入名片串」+输入框+「提交」钮） |
| 落库命令与拒绝码 | 零落库（网络命令：session.submitInvite(ticket)）；FormatException → 「这串不是有效的好友名片，换一张二维码试试」；RelayFriendsException 状态码映射：409=「你们已经是好友，或申请已在处理中」/400=「这张名片已过期或被用过，让对方刷新一下」/401=「登录态已过期，请到「设置 → 协同账号」重新登录」/403=「名片不对，换一张试试」/其余=「发送失败，稍后再试」；其他异常 → 「暂时连不上中继，稍后再试」 |
| 反馈形式 | 成功：SnackBar floating「已发出好友申请，等对方确认」+ pop 返回；失败：错误条常驻（非 toast），_done 复位可重扫 |
| 黄金向量指针 | 零新向量（Ticket 解析/邀请提交=collab 行为测试面已覆盖；扫码解码=设备仪器能力） |
| 像素基线指针 | B 档 scan 4 态——**manifest 现无本组，84 组收口轮补采**（相机态采图需权限预置） |

## §A 数据装配

| 行为 | 口径 |
|---|---|
| 权限 | 进入页即请求 CAMERA；granted=granted/limited → 相机态；拒绝 → 权限态（再请求钮） |
| 解析 | FriendshipTicket.fromQrString(code)（JSON 对象：issuer_user_id/subject_user_id/nonce/expires_at/signature/encKeyPub）——解析失败抛 FormatException |
| 提交 | session.submitInvite(ticket)（Kotlin RelaySession 已平移）——成功返回后 pop；错误按状态码映射 |
| Kotlin 装配 | 容器协同栈未装配（协作轮挂账）——ScanViewModel 经 lazy 会话提供者提交；未登录/未装配 → 401 文案路径（**装配差异留痕，协作轮收口**） |

## §B 骨架与组件

| 行为 | 口径 |
|---|---|
| AppBar | 「扫一扫建联」 |
| 相机区 | MobileScanner 全幅预览（Kotlin=CameraX PreviewView + ZXing ImageAnalysis 逐帧解码，一维/二维码全格式）；submitting 时上层转圈；错误条=黑底(87%)radius 12 白字居中 bottom 24 横向 24；正常提示=白 70%「把对方名片放进框里」 |
| 权限态 | 居中列：camera_alt_outlined 56 textSecondary + 16 + 「需要相机权限才能扫码」16sp textPrimary + 12 + FilledButton「授予相机权限」 |
| 手动区 | Divider + 4 + 「手动填入名片串」13sp textSecondary + 6 + Row（TextField isDense hint「粘贴对方名片二维码内容」OutlineInputBorder + 8 + FilledButton「提交」busy 禁用） |
| Kotlin 骨架 | ShiguangScaffold+TopAppBar（返回钮）；相机=AndroidView(PreviewView)+ImageAnalysisAnalyzer(ZXing)；权限=Accompanist 不引——ActivityResult API 原生请求 |

## §C 架构接线

- 依赖新增（R6 评估留痕）：androidx.camera:camera-camera2/camera-lifecycle/camera-view + com.google.zxing:core（纯 Java；Flutter 侧 mobile_scanner 同层能力——移动端相机栈为扫码页必需，无替代面）。
- ScanViewModel（D15 形）：StateFlow（permission/submitting/error）+ handle(code) 纯逻辑（错误映射 JVM 可测）+ submit 经容器会话。
- 路由：ShiguangRoute.CollabScan 已注册（协作轮接入口；本页可经 debug nav 到达）。
- 丢失点 #9：手动输入暂存 rememberSaveable（进程重建不丢——本页唯一可编辑态）。
