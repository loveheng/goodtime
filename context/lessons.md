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
