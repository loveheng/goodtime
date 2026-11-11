---
dev-loop: lessons
format: v1
epic: global
total-merged: 1
last-merge: 2026-10-06
---

# lessons

按模块归类的避坑规则正文（正文行禁用 `- [` 开头——该前缀专属底部追加区）

UI测试：testWidgets(FakeAsync) × sqflite ffi 组合下流程测试卡死互扰 ➔ FakeAsync 直连 await 真实 DB future 自锁、回调跨 zone 续跑不可靠、pumpAndSettle 被光标闪烁拖死 ➔ 真实异步全包 tester.runAsync、pumpAndSettle 换固定次数 pump、副作用断言轮询 DB、一测一文件隔离 (Ref: m1)

手势widget测试：testWidgets 里拖拽触发命令链后单次长 runAsync 窗口不推进多跳 I/O，跨 zone 续跑堆积致 isolate 崩溃 ➔ pumpFlush 多轮 runAsync+pump 逐跳冲刷（见 [UI测试] 三板斧）；仍崩则撤下 widget 级改真机走查（熔断） (Ref: misc)

android构建：AGP 报多条「Dependency androidx.* requires … compile against version 34 or later; :插件 is currently compiled against android-33」➔ 缓存内陈旧插件硬编码 compileSdkVersion 33，其 androidx 依赖元数据要求 minCompileSdk≥34；Flutter 工具链只拦插件高于 app、不抬低者（FlutterPluginUtils 仅 higher 校验）➔ 根 android/build.gradle.kts 对 com.android.library 子项目钳制 compileSdk≥36；根脚本期 :app 已被 evaluationDependsOn 评估完，直接 subprojects{afterEvaluate} 会抛 already evaluated，须按 state.executed 分两路 (Ref: m1)

Node 22 fetch 上传恒 ETIMEDOUT 而 curl/裸 net.connect 均通：autoSelectFamily（Happy Eyeballs）单次连接尝试默认 250ms 上限，RTT ~300ms 的远端（如 Cloudflare S3 API *.r2.cloudflarestorage.com）每次尝试必被杀，AggregateError 列出全部候选 IP 超时 ➔ `node --no-network-family-autoselection --dns-result-order=ipv4first` 关竞速按解析序直连；release-r2.sh 上传调用已固化此参数 (Ref: m1)
- [mcp-binder] 并行调用汇入单 Dart isolate 串行竞争，同步 call 的 per-call 超时（B1 5s）下后到调用排队必超时，呈现为通道中断 ➔ per-call 超时只能防挂死、不能扩吞吐；「工具面无长任务」假设在并行负载下不成立 ➔ 跨进程异步协议有 _async+onEvent 终帧机制时优先整体启用切片，且宿主侧等待端必须配 idle 超时兜底（插件保证必回 ≠ 永不漏推） (Ref: online)

## 待归并（collab）
- [collab] 现象: 直连真 relay 时 POST /v1/* 报 400 INVALID_REQUEST "missing X-Client-Msg-Id/X-Client-Ts"；WSS 收到的事件 cipher 为空致回放 FormatException；多设备测试 setUpAll 注册报 429 RATE_LIMITED | 根因: 真 relay Idem 中间件强制每个 POST 带 X-Client-Msg-Id + X-Client-Ts（relay_mock 未复刻）；WSS 广播是零知识唤醒（hub.go Publish 只发 bundle/seq/action/sender，不带 cipher，密文须经 /v1/schedule/sync Wake Pull 补齐；relay_mock 直接广播带密文）；单 IP 注册限流 3/min 对多测试文件并行注册生效 | 处置: HttpOutboxTransport 已补 X-Client-Ts 头（lib/collab/relay/action_api.dart）；测试侧 Dio 拦截器自动补两头部（test/collab_live_relay_test.dart `_dio()`）；WSS 帧空 cipher → 触发 pullAll 补拉（test/collab_live_scenario_test.dart 焊接段）；注册加 429 退避重试（限流按尝试计数且对齐分钟边界 unix/60、429 也 INCR 白烧配额——退避须对齐下一窗口边界加抖动，20s 固定步进可三连落同窗全废）+ 库级 @Timeout | 回归: test/collab_live_scenario_test.dart 完整场景例（撤 X-Client-Ts 头/唤醒补拉即红；服务不在场自动 skip） (Ref: online)
