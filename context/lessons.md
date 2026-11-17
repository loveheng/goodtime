---
dev-loop: lessons
format: v1
epic: global
total-merged: 12
last-merge: 2026-11-17
---

# lessons

按模块归类的避坑规则正文（正文行禁用 `- [` 开头——该前缀专属底部追加区）

UI测试：testWidgets(FakeAsync) × sqflite ffi 组合下流程测试卡死互扰 ➔ FakeAsync 直连 await 真实 DB future 自锁、回调跨 zone 续跑不可靠、pumpAndSettle 被光标闪烁拖死 ➔ 真实异步全包 tester.runAsync、pumpAndSettle 换固定次数 pump、副作用断言轮询 DB、一测一文件隔离 (Ref: m1)

手势widget测试：testWidgets 里拖拽触发命令链后单次长 runAsync 窗口不推进多跳 I/O，跨 zone 续跑堆积致 isolate 崩溃 ➔ pumpFlush 多轮 runAsync+pump 逐跳冲刷（见 [UI测试] 三板斧）；仍崩则撤下 widget 级改真机走查（熔断） (Ref: misc)

android构建：AGP 报多条「Dependency androidx.* requires … compile against version 34 or later; :插件 is currently compiled against android-33」➔ 缓存内陈旧插件硬编码 compileSdkVersion 33，其 androidx 依赖元数据要求 minCompileSdk≥34；Flutter 工具链只拦插件高于 app、不抬低者（FlutterPluginUtils 仅 higher 校验）➔ 根 android/build.gradle.kts 对 com.android.library 子项目钳制 compileSdk≥36；根脚本期 :app 已被 evaluationDependsOn 评估完，直接 subprojects{afterEvaluate} 会抛 already evaluated，须按 state.executed 分两路 (Ref: m1)

Node 22 fetch 上传恒 ETIMEDOUT 而 curl/裸 net.connect 均通：autoSelectFamily（Happy Eyeballs）单次连接尝试默认 250ms 上限，RTT ~300ms 的远端（如 Cloudflare S3 API *.r2.cloudflarestorage.com）每次尝试必被杀，AggregateError 列出全部候选 IP 超时 ➔ `node --no-network-family-autoselection --dns-result-order=ipv4first` 关竞速按解析序直连；release-r2.sh 上传调用已固化此参数 (Ref: m1)

mcp-binder：并行调用汇入单 Dart isolate 串行竞争，同步 call 的 per-call 超时（B1 5s）下后到调用排队必超时，呈现为通道中断 ➔ per-call 超时只能防挂死、不能扩吞吐；「工具面无长任务」假设在并行负载下不成立 ➔ 跨进程异步协议有 _async+onEvent 终帧机制时优先整体启用切片，且宿主侧等待端必须配 idle 超时兜底（插件保证必回 ≠ 永不漏推） (Ref: online)

collab relay 联调：现象: 直连真 relay 时 POST /v1/* 报 400 INVALID_REQUEST "missing X-Client-Msg-Id/X-Client-Ts"；WSS 收到的事件 cipher 为空致回放 FormatException；多设备测试 setUpAll 注册报 429 RATE_LIMITED | 根因: 真 relay Idem 中间件强制每个 POST 带 X-Client-Msg-Id + X-Client-Ts（relay_mock 未复刻）；WSS 广播是零知识唤醒（hub.go Publish 只发 bundle/seq/action/sender，不带 cipher，密文须经 /v1/schedule/sync Wake Pull 补齐；relay_mock 直接广播带密文）；单 IP 注册限流 3/min 对多测试文件并行注册生效 | 处置: HttpOutboxTransport 已补 X-Client-Ts 头（lib/collab/relay/action_api.dart）；测试侧 Dio 拦截器自动补两头部（test/collab_live_relay_test.dart `_dio()`）；WSS 帧空 cipher → 触发 pullAll 补拉（test/collab_live_scenario_test.dart 焊接段）；注册加 429 退避重试（限流按尝试计数且对齐分钟边界 unix/60、429 也 INCR 白烧配额——退避须对齐下一窗口边界加抖动，20s 固定步进可三连落同窗全废）+ 库级 @Timeout | 回归: test/collab_live_scenario_test.dart 完整场景例（撤 X-Client-Ts 头/唤醒补拉即红；服务不在场自动 skip） (Ref: online)

debug 桥与基线采集·FK 清理顺序：现象: 基线 seed 清库后 addArtifact/addBackground 抛 FOREIGN KEY constraint failed (DELETE FROM plans)，采集全崩回首页（错误被 channel fire-and-forget 吞，截图=停留页极具迷惑性） | 根因: _clearBusiness 表清理顺序错——artifacts/backgrounds/schedule_blocks 持 plans 外键却排在 plans 后删；真机连接 onOpen 开了 PRAGMA foreign_keys=ON，sqflite_ffi 测试库默认不开故既有测试全绿 | 处置: 子表先删 plans 最后；debug 通道的 channel error 必须回读（Kotlin Log.w + 采集器校验 result）而非静默 | 回归: 清库类工具的表顺序=FK 依赖反序；debug 通道失败必须可见 (Ref: native-migration)

debug 桥与基线采集·HTTP 定长与 pid 过滤：现象: 采集器 POST /debug/action 恒 400 invalid_json（同 body curl/python 200），且采集器可能抓错桥（设备双 app 集成同 tag debug-bridge） | 根因: ① Dart HttpClient 未设 content-length 时默认 transfer-encoding: chunked，SDK body 读取不认 chunked（定长即通）；② logcat -s debug-bridge 全进程混流，last 匹配可能命中 GoodToolsApp 的桥 | 处置: 请求体 utf8 bytes + r.contentLength 显式定长；logcat 加 --pid=<pidof 拾光> 过滤 | 回归: 跨进程/跨端 HTTP 调试通道两条——开发机直发 device HTTP 必设定长；多 app 同 tag 日志必 pid 过滤 (Ref: baseline-capture-http) (Ref: native-migration)

debug 桥与基线采集·Flutter 宿主三纪律：现象: SDK DebugScreenshot.capture（window 级 PixelCopy）对 Flutter app 截图恒全黑（13966 字节固定），且 navigate 动作 ok:true 但 Dart 页面不跳、无任何报错 | 根因: ① Flutter 内容在独立 Surface 合成（SurfaceView），window 级 PixelCopy 只取 View 层拿不到 Flutter 帧；② MethodChannel.invokeMethod 强制主线程，DebugActions 在 HTTP worker 线程分发 navigate，fire-and-forget 无回调时异常全吞；③ StandardMethodCodec 把 Map 解为 Map<Object?,Object?>，Dart 侧 as Map<String,Object?> 抛 TypeError 走 error 回调（无回调时静默） | 处置: 截图改 Flutter RepaintBoundary 经 channel 同步 latch（DPR 由 Dart 固定）；navigate 包 Handler(main).post + 全回调诊断；Dart 侧 cast 改 as Map? 后取键 | 回归: 跨语言通道三纪律——channel 参数一律 as Map? 宽 cast；invokeMethod 必须主线程 post 且带 Result 回调（勿 fire-and-forget）；Flutter app 的原生截图勿用 window 级 PixelCopy (Ref: flutter-bridge-pitfalls) (Ref: native-migration)

schema 迁移验证·全结构契约：现象: schema 快照生成器同构断言拦停——升级终态缺 7 个索引（idx_blocks_date 等），而 collab_db_migration_test 4/4 一直绿 | 根因: 迁移测试的「新装同构」只比 PRAGMA table_info 列名集合（Set 无序），CREATE INDEX 从未进对比面——v1–v4 索引只在 onCreate 建，migrateV5 幂等六步漏补，存量升级库静默走全表扫；列序陷阱：ALTER TABLE ADD COLUMN 只能尾部追加，物理 cid 序不可作契约 | 处置: schema 验证必须全结构（列/索引/外键三方 PRAGMA）+ 列按名字典序规范化对比；migrateV5 尾部补 7 条幂等 CREATE INDEX（D6 拍板） | 回归: 「新装≡升级」不变量是迁移链的完整契约，只比列集=半盲验证；回归钉=test/golden/golden_schema_test.dart 同构断言（撤索引补丁即红） (Ref: schema-full-structure) (Ref: native-migration)

golden 向量镜像·逐字段核对：现象: gen_fsm_vectors 产出 d_patch_time_echo 终态 CONFIRMED，与 collab_fsm_test 断言 PROPOSED 失配（p_pst_stray 同理 STALLED→PROPOSED） | 根因: 镜像既有测试时凭「s1 单步」直觉复用 kOne 骨架，而源测试用例的局部骨架是 s1(a,b) 双人——单人骨架下 a 一票即达标，转移语义整体走样 | 处置: 补 kOneAB 骨架 + 40 case 断言脚本全过 | 回归: 黄金向量镜像规约——生成器 expect 落盘前必须与源测试断言逐字段核对（status/revision/ballots/participants/base_revision，断言脚本或等价机械核对），仅看终态 status 不放行 (Ref: fsm-vector-mirror) (Ref: native-migration)

propose 落库布尔位·混形直传：现象: propose 落库块 is_day_spark 布尔丢失（true→false，toolface 向量 tf_propose_ok 红） | 根因: ScheduleBlock.fromMap 布尔位按 DB 行 0/1 口径（long(k)!=0），JSON overlay 传 JsonPrimitive(true) 时 longOrNull=null→0——MCP JSON 形与 DB 行形在 addBlock values 混用 | 处置: handler 落库一律模型构造+toMap()（布尔位经 jsonToSqlValue 转 1/0），禁 JSON overlay 直传布尔；propose ⑧ 改模型构造+单事务（Dart _repo.transaction 逐字，replaceable 删除同事务） | 回归: GoldenToolfaceTest.tf_propose_ok（撤即红） (Ref: native-migration)

Dart→Kotlin 协程化 Stream 三重语义差：现象: engine/pipeline 测试三连翻车——applied 事件丢失/收集协程饿死/WSS 重连偶发无状态 | 根因: ①Dart broadcast listen 同步订阅，Kotlin SharedFlow 无 replay 且 launch 订阅异步（订阅前发射即丢）②Dart await delay 让出事件循环，Kotlin runBlocking 下 Thread.sleep 阻塞主线程饿死全部收集协程 ③OkHttp WebSocket 收对端关帧须客户端在 onClosing 回应 close 才完成握手，不回应悬挂到 EOF | 处置: 订阅侧 onSubscription 屏障+engine.start 用 CoroutineStart.UNDISPATCHED；测试轮询一律挂起式 delay；RelayWssClient.onClosing 回应 close+dispose 用 cancel abrupt | 回归: relay 族全部测试（CollabEngineTest/CollabWssTest/CollabPipelineTest/CollabWakeTest 撤任一屏障即红） (Ref: native-migration)

AndroidSqlite 双坑（Cursor 懒执行+池化回调漏覆盖）：现象: 真机仪器测试 PRAGMA 读回异常——journal_mode=memory（期望 wal）、池化读连接 busy_timeout=2500（期望 5000） | 根因: ①Android Cursor 懒执行——rawQuery(...).close() 不触发语句，PRAGMA 根本没跑（Dart sqflite rawQuery await 即执行，口径不可直译）②框架 ENABLE_WRITE_AHEAD_LOGGING flag 开连接池，池化读连接不过 SQLiteOpenHelper 回调，busy_timeout 回落框架默认 2500 | 处置: 单连接制（不开 flag，对齐 sqflite 每引擎单连接=R3 实测受测形状）+ rawQuery moveToFirst 显式触发 + onConfigure 探针日志取证（journal/busy 双读回 wal/5000） | 回归: AndroidSqlStoreDeviceTest 四测（fresh/reopen/real/dual，撤 moveToFirst 即红） (Ref: native-migration)

Konsist 0.17.0 API 面考古：现象: konsist 0.17.0 断言/过滤 API 大面积 Unresolved reference（assert/resideInPackage/properties()/类级 imports/scopeFromProject）| 根因: 0.17.0 API 面与 0.16 时代及常见文档样本不同——断言收编 verify.assertTrue；包过滤=withPackage（无 resideInPackage）；imports 仅住 KoFileDeclaration（类/接口声明不实现 KoImportProvider）；scopeFromProject 移除（scopeFromDirectory 路径按工程根=android/ 相对解析）；修饰语=is* 改 has*Modifier（hasPrivateModifier）；provider 成员带必填 bool 参需显式传 | 处置: javap 缓存 jar 实查 API 面（KoScope.getFiles/classes(false,false)/functions(false,false)/interfaces(false)/classesAndInterfaces；assertTrue 尾随 lambda 按位绑 function 参）；护栏正则字符串触发 K8 文本扫描→K8 白名单留痕（守卫自指） | 回归: android/app/src/test/kotlin/app/shiguang/shiguang/ArchitectureKonsistTest.kt（API 形状样板，重写引用此文件） (Ref: native-migration)

AndroidHttp：现象: Kotlin 侧 `Unresolved reference 'HttpExchange'`（com.sun.net.httpserver 全族不可解析） | 根因: Android SDK android.jar 不含 JDK 的 com.sun.net.httpserver（JVM-only 包，运行时亦无） | 处置: 零依赖 ServerSocket 手写极简 HTTP/1.1 单请求会话（Connection: close + Content-Length 定长体，MCP POST 标准帧足够；`app/service/HttpTransport.kt`） | 回归: android/app/src/test/kotlin/app/shiguang/shiguang/service/HttpTransportTest.kt（真 socket 对拍 8 例） (Ref: native-migration)

KotlinJson：现象: HttpTransportTest 断言坏 JSON 回 -32700 实收 -32600（"仅支持单条 JSON-RPC 消息"） | 根因: kotlinx `Json.parseToJsonElement("not-json")` 默认宽松解析裸词成功得 JsonPrimitive（Dart jsonDecode 严格抛 FormatException）→ 内核 msg!isJsonObject 分支先于 PARSE 命中；内核语义黄金向量面已冻结勿改 | 处置: 测试触发 -32700 须用真语法错体（如 `{"id":1,` 截断体） | 回归: android/app/src/test/kotlin/app/shiguang/shiguang/service/HttpTransportTest.kt tokenGateReturns401Verbatim（撤断言即红） (Ref: native-migration)

AndroidLoopback：现象: 真机 mcp 回环 ping ConnectException ECONNREFUSED（ss 实证 LISTEN 仅 [::1]:8765） | 根因: 部分设备 InetAddress.getLoopbackAddress() 解析 ::1——ServerSocket 仅绑 IPv6 环回，IPv4 127.0.0.1 连接被拒（Dart InternetAddress.loopbackIPv4 显式 IPv4 无此面） | 处置: HttpTransport.start 显式 InetAddress.getByName("127.0.0.1") 绑 IPv4（与 endpoint 字面量/adb reverse 同口径） | 回归: android/app/src/test/.../service/HttpTransportTest.kt（绑定即 IPv4 语义；走查钉 scripts/agent-tools/walkthrough-n8c.py ⑤） (Ref: native-migration)

ComposeA11y：现象: 桥语义走查 tap 通道通而 ui_contains 找不到 ModalBottomSheet 内节点（调试菜单） | 根因: Compose ModalBottomSheet 在独立 dialog 窗口——Activity decorView 的语义树不含其 AndroidComposeView；且 provider.createAccessibilityNodeInfo(NO_ID)/view 级节点均不带虚拟子树 | 处置: 走查语义收集改 WindowManagerGlobal.mViews 反射扫全部窗口根 + AndroidComposeView.semanticsOwner 反射直取 SemanticsNode 树（动作直调 SemanticsActions；SemanticsConfiguration.get 缺键即抛须 contains 守卫） | 回归: scripts/agent-tools/walkthrough-n8c.py ⑤调试菜单唤出（撤全窗口扫描即红） (Ref: native-migration)

JvmTestWallClock：现象: PanicReflowTest 三例夜跑恒红（21:28 nowMin=1288：播种 nowMin+360=1648>1440 越界→push_2h 拒绝原因变形，白绿夜红）➔ 根因: 测试播种用 LocalDateTime.now() 推算相对窗，panicClear 本身注入 nowMin 参数——播种时刻与断言的拒绝路径强耦合真实时钟，跨过 1440/1380 边界后语义分支变形 ➔ 规则: JVM 测试凡有「时刻+相对窗」注入口的，播种一律定钟（per-test 600/1200 等按用例语义选值），禁依赖真实时钟；回归钉=PanicReflowTest 全文件（定钟后任何时刻绿） (Ref: native-migration)

KotlinUpdatePlan：现象: 手答后 UI 态已更新但 planById 回读 open_items[0].answer 恒 null，patchPlan 无该列写点 ➔ 根因: Kotlin updatePlan 平移漏行——Dart values 组装有 if (cmd.openItems != null) 'open_items': Plan.encodeOpenItems(...) 整表替换写库，Kotlin 只写 12 个标量字段；同源缺 values.isEmpty 拒（「没有可更新的字段」INVALID_REQUEST+§11 hint）；命令 JSON 对拍覆盖 fromJson 面不覆盖 handler 写列缺行 ➔ 规则: 命令面平移对拍必须逐字段核对 handler values 组装与 Dart values 字典（不只 fromJson 面）；回归钉=PlanDetailViewModelTest.手答整表替换与空答案不提交（撤修复即红） (Ref: native-migration)

debug 桥与基线采集·navigate 白名单双通道：现象: capture navigate fact_sheet/block_sheet 回 ok:true 但帧=壳层 Today——Dart debugPages 白名单未登记页静默不跳 | 根因: Flutter 宿主 navigate 有双通道——Kotlin DebugBridgeWiring 先查自有 registry（命中=fire-and-forget ok:true，state 播种仍透传 Dart）未命中才转发 Dart navigateToDebugPage（返回 false 被外层吞）；白名单缺项=播种跑了、页面没跳、外层仍 ok | 处置: 采集器断言不能只看外层 ok——须按页名核对帧内容（pull 帧目检）；新增基线页必须同步登记 Dart debugPages 白名单 | 回归: debug_pages.dart fact_sheet/block_sheet/action_pass_bar/app_gate 四条目（撤即回退） (Ref: native-migration)

debug 桥与基线采集·锚日作息日错配：现象: stage4 APB_S1/S2 两帧同 hash——通关条不顶出（帧=留白空态） | 根因: 播种锚 date 用作息日 today（清晨 00:00–wake 间 scheduleDayOf 归昨日）而 nowMin 属真实今天——错配即锚时刻落 T_trigger/T_dismiss 窗外，computeActionPasses 过滤后空集 | 处置: 通关条/时刻类播种锚日期一律用真实日历日（LocalDate.now/DateTime.now），作息日口径只归「页面归属」不归「时刻窗」 | 回归: capture.dart --stage4 --page action_pass_bar 两态帧独立（S2 带 +N 胶囊） (Ref: native-migration)

debug 桥与基线采集·合成态截图时序：现象: stage3 浮层合成态采图（S5 block_sheet:baseline_ov_a）帧内容=无浮层宿主页——浮层组全帧失真 | 根因: capture.dart 主循环 shot-before-overlay——navigate 后立即截图、open_overlay 在截图之后才唤起，写盘的 png 是唤起前帧；文档口径（debug_overlays.dart「整屏截图即宿主页+浮层合成态」）与实现序相反 | 处置: 合成态采集=先唤起后截图，凡「A+叠加 B」类采集时序必须与语义文档对账 | 回归: capture.dart stage4 浮层组帧含 sheet 内容（撤序即回退） (Ref: native-migration)

ComposeVmTest 事件流竞态：现象: ScanErrorMapTest 双闸用例全量套件偶发 TimeoutCancellationException（events.first 超时）而单跑恒绿 | 根因: SharedFlow 无 replay（emit 先于订阅即丢件）+ viewModelScope 依赖 Dispatchers.Main，跨测试类 Main 委托泄漏使调度行为随类序漂移 | 处置: VM 事件流测试显式 setMain(UnconfinedTestDispatcher)+finally resetMain；订阅侧 onSubscription 挂CompletableDeferred 门后才触发（先订阅后发射） | 回归: ScanErrorMapTest.双闸防重状态机（撤门即红） (Ref: native-migration)

ConscryptEdCurve 真机 JCA 分叉：现象: Android 真机注册链崩 EdECPrivateKey cast / X509EncodedKeySpec / XECPrivateKeySpec 串 | 根因: 桌面 JDK 与 Android Conscrypt 的 JCA 行为分叉——Conscrypt Ed25519 私钥类不实现 EdECPrivateKey（getBytes 取种子不可用）、KeyFactory 只收 X509/PKCS8/Raw 不收 EdEC/XEC spec 类，纯 JVM 全绿掩盖真机路径 | 处置: Ed25519/X25519 全链 BouncyCastle 低阶承载（Ed25519Signer/X25519Agreement+KeyParameters），私钥携带物一律 raw seed，删 JDK KeyFactory/Signature/KeyAgreement 路径 | 回归: CollabIdentityPowTest `ed25519 全链 BC 签名验签闭环与确定性` + GoldenE2eeTest RFC 7748 向量逐字节锚 (Ref: native-migration)

RelayBinStale 二进制陈旧：现象: 注册回 400 INVALID_REQUEST "user_id required"，而契约明言 user_id 服务端发号 | 根因: shiguang-relay/bin/relayd 二进制落后于服务端校验变更——错误串 grep 源码无命中即陈旧铁证 | 处置: cd shiguang-relay && go build -o bin/relayd ./cmd/relayd 重编重启（go 在 /home/zzh/sdk/go1.23.6/bin） | 回归: relay 联调前置核对 bin/relayd mtime 与契约变更先后；错误串 grep 源码法判陈旧 (Ref: native-migration)

现象: NativeMainActivity 冷启动 Compose 页顶栏「拾光」系统条压住 segmented 上半截，内容顶部被裁取（截屏横跨式裁切）| 根因: debug manifest 该 activity 与 application 均未声明 android:theme，继承框架默认主题自带窗口 ActionBar（label「拾光」），targetSdk 36 edge-to-edge 下覆盖式压内容；Flutter 主入口显式 LaunchTheme/NormalTheme 故不受累 | 处置: debug manifest NativeMainActivity 补 android:theme=@style/NormalTheme（既有 NoTitleBar 主题）| 回归: 冷启动 NativeMainActivity 截图 segmented 完整；ui-tree 无 ActionBarContainer/Toolbar 拾光 (Ref: native-migration)

现象: tap_bounds 落点总在屏外（4452>1272 屏宽）点不中目标 ➔ 根因: 脚本把 ui-tree/uiautomator bounds 当逻辑坐标 ×dpr(3.5)，但 wm size=1272×2800 证明 bounds 本就是物理像素 ➔ 规则: tap_bounds 去 ×dpr 直用物理像素（scripts/agent-tools/debug-bridge.sh）；回归=tap_bounds "1272,1832" 输出 device(1272,1832) (Ref: native-migration)

现象: debug-bridge.sh 报「发现不到调试桥（端口/token）」但 logcat 明有 listening 行 ➔ 根因: 原生宿主把 listening on 127.0.0.1:port 与 [debug-bridge][native] token= 分两行打，resolve_bridge 取最后一行只含 token 无端口→PORT 空 ➔ 规则: resolve_bridge 过滤改 token 与 127.0.0.1:port 同行（scripts/agent-tools/debug-bridge.sh）；回归=toolbox run debug-bridge state 返回 ok (Ref: native-migration)

（空——2026-11-17 归并：Compose宿主主题缺失/debug-bridge-sh 双行拆分/debug-bridge-sh tap_bounds 物理像素口径 三条进正文；前次 2026-11-16：ComposeVmTest/ConscryptEdCurve/RelayBinStale）

## 待归并

