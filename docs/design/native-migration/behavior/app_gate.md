---
status: active
updated: 2026-11-15
---

# 行为清单 · app_gate（入口闸门 + 分享转投，N8c 施工输入，2026-11-15 D23 过目转正）

> §9.1.6 模板产出（2026-11-15 N8c 前置抽取）。抽取源 = `lib/ui/app_gate.dart`（84 行全文）+ `lib/app_services.dart`（pendingShare 通道 + runPendingShare）+ `android/.../MainActivity.kt`（SHARE_CHANNEL 原生侧已平移，:32/:207-213）+ `docs/design/ui-pages.md` §2 + fact 草案 §1.7（分享入口宿主）。**范围 = 闸门三态决策 + 分享转投消费**；引导页本体归 onboarding 清单，主框架归 app_shell 清单。

## 七字段

| 字段 | 内容 |
|---|---|
| 交互意图 | 入口闸门：settings.initialized 未置 → 首启引导；已置 → 主框架（AppShell）；另承载系统分享转投的入池与挂载 sheet 唤起（引导是 propose 硬拒 §6 校验铁律的 UI 前置） |
| 触发与阈值 | 决策响应式：初始查一次 getSettings，此后随 repo 写入通知（revision 变化）重查——不依赖回调续跑；分享消费=首帧后 postFrame 执行，转投文本首行截 40 字为 title |
| 状态空间 | ui-pages §8 D 档 2 态（S1 内容展示/S2 无数据）——实为三态：载入中（CircularProgressIndicator）/引导态/主框架态；**基线 manifest 无 app_gate 条目（D 档 84 组余量）**，采图窗口随 P4 收尾轮定 |
| 落库命令与拒绝码 | 分享转投 → `upsert_facts`（category=verbal/source_kind=verbal 占位——命令层强制出生确认条款 §1.2，raw 建档 origin=shared/state=raw/actor=human，零解析纪律在命令层）；拒绝=命令层族已平移 |
| 反馈形式 | 无成功反馈——入册后唤挂载 sheet（showFactMountSheet，归册面）；失败静默（未归属池兜底，sheet 可「先记下」）；闸门翻转无过渡动画 |
| 黄金向量指针 | upsert_facts 严进族（command vectors，已平移）；verbal 出生占位语义随 facts 域向量 |
| 像素基线指针 | manifest 无 app_gate 组；D 档 2 态采图随基线收尾轮（本页多为承载壳，视觉主体是引导页/主框架） |

## §A 闸门决策（丢失点 #4 同款，Kotlin 用 LaunchedEffect）

| 行为 | 口径 |
|---|---|
| 初始装载 | getSettings → initialized（缺键=false）；装载中渲染 CircularProgressIndicator 居中空 Scaffold |
| revision 驱动重查 | build 侧 ListenableBuilder(repo) 比较 revision != _seenRevision → scheduleMicrotask(_reload)（Dart build 中副作用模式）——Kotlin 改 `LaunchedEffect(repo.revision){ reload }` + initialized StateFlow；引导完成播种/设置被清空均即时翻转 |
| 三态分派 | initialized==null → 载入态；false → OnboardingPage(onDone=重查)；true → AppShell |

## §B 分享转投（§2.3，Kotlin 侧重建消费面）

| 行为 | 口径 |
|---|---|
| 通道 | 原生侧只转投 EXTRA_TEXT 字节串：ACTION_SEND → BasicMessageChannel `app.shiguang/sharing` 投 JSON 串 {"text": …}（零解析纪律在命令层）——Kotlin 发送端已平移（MainActivity SHARE_CHANNEL）；**Dart 接收端/消费面无 Kotlin 对应物：N8c 重建** |
| 接收 | 文本挂 pendingShare 等首帧消费；初始值非空直接排消费（原生消息可早于监听注册到达）；非 JSON 直通兜底（测试注入）；空/空白丢弃 |
| 并发守卫 | runPendingShare 内部 _shareRunning 防重入；挂起语义留池 |
| 消费 | trim → 首行（按 \r\n 切）截 ≤40 字为 title → upsert_facts（verbal/verbal 占位+origin=shared+state=raw，human actor）→ 入册；context 未就绪（首帧前）只入册不唤 sheet，留池下轮 |
| 挂载 | 按 targetId 取 artifactById → showFactMountSheet（归属面，浮层族清单管辖）；finally 清 pendingShare |

## §C 架构接线（N8c 预案，过目后定）

- GateViewModel：initialized StateFlow（collect repo.revision 重查）+ pendingShare SharedFlow/StateFlow（MainActivity SHARE_CHANNEL 侧 Kotlin 进程内直投——**无 Dart 层，通道改为进程内事件**，过目点 §D.1）。
- 单 Activity 首屏即 Gate composable；引导/主框架互斥渲染。

## §D 过目点（须人工拍板）

1. **分享通道形态**：原生 app 无 Dart MethodChannel——`app.shiguang/sharing` 二进制消息通道在纯原生侧应改为 MainActivity → AppContainer/Gate 的进程内直投（SharedFlow）。语义不变（零解析+首帧后消费+并发守卫照抄）。**默认建议=进程内 SharedFlow 直投**。
2. **「首帧后消费」等价物**：Dart postFrameCallback 在 Compose 侧对应「composition 完成后」——用 `LaunchedEffect(Unit)` 于 Gate 挂载时消费 pendingShare 即达同义（sheet 唤起需 window 就绪）。**默认建议=LaunchedEffect 消费，不引 postFrame 概念**。
3. **未归属兜底面**：artifactById 失败/targetId null 时静默留池（未归属凭证面板挂账随浮层族轮）——照抄静默，不补 UI。
