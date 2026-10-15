---
dev-loop: epic-memory
format: v1
epic: online
status: active
pairs_with: "goodhub-local-plugin（Binder 嵌入对接域，见 context/epics/goodhub-local-plugin/memory.md）"
breakpoint: "MCP 暴露面收口已实装并发布 R2 v1.2.1 (28020)（2026-10-15，远端清单/APK 核验过）；下一步：真机验证（app 内自更新到 28020 → 验自更新+隐藏入口+adb reverse 回环+宿主 bind）或轴 B 第二段 `shiguang_mcp` 纯 Dart 包（待宿主驱动）"
---

# ⚡ 给「拾光」AI 代理的整体改造提示词（让它自己完成拆分化）

> 本文件是 shiguang 当前 epic `online` 的开工指令。读到即执行下述方案；每完成一步在末尾断点区更新进度。任何偏离须先回到本方案的「原则」与「禁止项」。

> **绑定域（硬约束优先）**：本 epic 的 Binder 嵌入改造已绑定到 shiguang 侧 `goodhub-local-plugin` 域（`context/epics/goodhub-local-plugin/memory.md`）——该域是 goodshare 同名范本的对接锚点，含现状评估与 **G1–G7 修正留痕**。执行本方案时，以下与域对齐的硬约束**优先于本文件下文措辞**：(1) `HttpTransport` 落点钉死 `lib/service`（不进 `lib/mcp`）；(2) Step 1 必须同步迁移 `test/mcp_server_test.dart`（否则 test 全过不可达）；(3) 轴 B 解耦**三处**平台耦合（含 sqflite 存储抽象）；(4) 验收 = 响应体逐字节等价 + HTTP 状态码漂移逐条登记。

---

## 0. 角色与目标

你是「拾光」（shiguang）这个 Flutter/Dart app 的改造代理。目标：**把 shiguang 的 MCP 能力拆分化，使其既能在本 app 内以 HTTP 运行，又能作为 Binder 插件被宿主（GoodHub 式 app）进程内嵌入，并复用 relay 走内网/公网可达**——而这一切以「零返工、行为零变化、复用已验证骨架」为前提。

这不是重写，是**结构性拆分**：把已建好的无头化内核从传输与平台外壳中分离出来，让内核可独立被嵌入。

## 1. 现状盘点（基于本仓库真实代码，勿假设）

已具备（直接复用，**不要重写**）：
- 命令模式：`lib/action/command_handler.dart`（`CommandHandler.execute(ScheduleCommand, actor: CommandActor.ai)`）、`commands.dart`、`queries.dart`、`rules.dart`——与 UI 组装同源的 `ScheduleCommand.fromJson` 单一入口。
- 数据仓库：`lib/data/repository.dart`——FIFO 串行锁 `synchronized`、乐观锁 `version`、patch 白名单 `_whitelist`（防注入）、`transaction` 批原子写。业务校验在命令层，仓库只保台账。
- MCP 工具面：`lib/mcp/tools.dart`（`toolSchemas()` + `callTool(name, args, repo)`）——只做 JSON↔命令 的翻译，不做业务判断（防呆全在命令层）。
- 协议常量：`lib/mcp/jsonrpc.dart`（`McpProtocol`、`McpRpcError`、错误码）。
- HTTP 总控：`lib/service/mcp_controller.dart`（`ChangeNotifier`，装配 token/mDNS/前台服务 + 起 `McpServer`）。
- 本地桥：`mcp-bridge/stdio-bridge.mjs`（stdio↔HTTP :8765，token 鉴权）；relay 服务：`shiguang-relay/`（Go）。

**唯一的结构性缺口（本次改造的命门）**：
- `lib/mcp/mcp_server.dart` 的 `McpServer` **内嵌 `dart:io` 的 `HttpServer`**，并把 HTTP 专属逻辑 inline 在 `_onRequest` 里（origin/DNS-rebinding 校验、`x-api-key` 校验、`/mcp` 路径与 `POST` 方法检查、202 通知处理、`_json`/`_safeJson` 写回）。
- 它没有 goodshare 那种 **`String handleJsonRpc(String)` 传输无关入口**。这导致：现在想把它嵌成 Binder 插件，必须复制整套 JSON-RPC 分发逻辑——返工由此而生。

## 2. 已确认方向（钉死，不做二次讨论）

1. **Binder 插件嵌入**：shiguang 的 MCP 层终态是「可作为 Binder 插件被宿主进程内嵌入」。这**不是外挂，而是本次拆分的內禀终点**——`shiguang_mcp` 内核包就是那个被嵌入的单元。
2. **复用 relay**（公网）：relay 只做 TCP/JSON 中转，不碰业务；内网 bonsoir 发现已退役（2026-10-15 [SSOT 修正] 旧「内网 bonsoir 发现 + 公网 relay 中转」➔ 新「仅公网 relay + 暴露面收口为 Binder 宿主 + 回环调试」，见 schedule-app §11「MCP 暴露面」行）。
3. 参考样板（**只读，不跨项目改**）：goodshare 的 `lib/mcp/mcp_server.dart` 中 `handleJsonRpc(String)` 是已验证的传输无关范本；goodshare `context/epics/goodhub-local-plugin/memory.md` 记录了 Binder 嵌入「核心零改动，只加 Binder 传输层 + 同签名权限协调，无缺口」。

## 3. 原则（来自 goodshare 的血泪，务必遵守）

- **复用骨架与纪律，重写领域血肉**：协议逻辑、`command`、`repository` 校验一律不重写；只动「接缝」与「外壳」。
- **先立接缝、后加传输（M1 加法先行）**：先做最小接缝（抽 `handleJsonRpc`），HTTP 行为字面上零变化跑通；真要有宿主时再建 `BinderTransport`，**绝不提前建空壳**。
- **任何改造先加后减**：先做适配层跑通闭环 → 再摘旧依赖，不一次性大改接线。
- **护栏前移为交付线**：每个改动须过 `flutter analyze`（0 issue）+ `flutter test`（全过），否则不算完成。

## 4. 四轴改造方案（按杠杆从高到低）

### 轴 A — 传输无关内核（最高杠杆，先做，见第 5 节 Step 1）
把 `McpServer` 从 `HttpServer` 中解放，暴露 `Future<String> handleJsonRpc(String rawBody)`。HTTP 专属逻辑移到 `HttpTransport`。这是 Binder 嵌入的前提，也是返工的唯一来源点。

### 轴 B — 内核包边界（`shiguang_mcp` 可嵌入单元）
待轴 A 稳定后，把 `lib/mcp/` + `lib/action/` + `lib/data/` 收为纯 Dart 内核包，供宿主依赖。
- **唯一需解耦点**：`lib/data/repository.dart` 现 `extends ChangeNotifier`（Flutter 依赖）。把 `notifyListeners`/`revision` 信号机制抽成可选回调或抽象接口（如 `void Function()? onChange`），让 `Repository` 在纯 Dart 下可编译。命令层/仓库业务逻辑不动。
- 终态：宿主 `import 'package:shiguang_mcp/...'` 即可拿 `handleJsonRpc` + `tools`，只需注入自己的 `Repository`（DB 实现由宿主提供）。

### 轴 C — relay 复用（内网/公网可达）
复用 `shiguang-relay`（Go）。relay 仅做中转，无需改造内核；配置层（`RelayClient`）挂在 `McpController` 之后，与 `HttpTransport` 并列作为另一种传输（2026-10-15 暴露面收口：默认关、显式配置才挂载，bonsoir 发现引用退役）。

### 轴 D — 平台能力接口化
前台保活（`flutter_foreground_task`）、自更新（`open_filex`/`package_info_plus`）、网络广播（`bonsoir`，2026-10-15 随暴露面收口退役）已是抽象。确认这些**只出现在 `lib/service` 与 `lib/update`，不渗入 `lib/mcp`、`lib/action`、`lib/data`**。如有越界，在轴 A/B 时一并收敛。

## 5. Step 1 详细配方（立即执行，风险极低，行为零变化）

在 `lib/mcp/mcp_server.dart` 中：

**(a) 新增传输无关入口**（搬运现有 `_dispatch` 与其周围 try/catch，去掉所有 `HttpRequest` 依赖）：
```dart
/// 传输无关：输入 JSON-RPC 请求体，输出响应体字符串。
/// 通知（id==null）返回空串，由传输层决定回 202。
Future<String> handleJsonRpc(String rawBody) async {
  Object? msg;
  try {
    msg = jsonDecode(rawBody);
  } catch (_) {
    return jsonEncode(_rpcError(null, errParse, 'JSON 解析失败'));
  }
  if (msg is! Map<String, Object?>) {
    return jsonEncode(_rpcError(null, errInvalidRequest, '仅支持单条 JSON-RPC 消息'));
  }
  final method = msg['method'];
  final id = msg['id'];
  if (id == null) return ''; // 通知：无需应答
  if (method is! String) {
    return jsonEncode(_rpcError(id, errInvalidRequest, '缺少 method'));
  }
  try {
    final result = await _dispatch(method, msg['params']);
    return jsonEncode({'jsonrpc': '2.0', 'id': id, 'result': result});
  } on McpRpcError catch (e) {
    return jsonEncode({'jsonrpc': '2.0', 'id': id, 'error': e.toMap()});
  } catch (e) {
    return jsonEncode(_rpcError(id, errInternal, '工具执行失败: $e'));
  }
}
```
> `_dispatch`、`_rpcError`、`McpRpcError`、错误码常量均保留原位，不需改。`tokenProvider`/`instructionsProvider`/`onSuggestion` 仍由 `McpServer` 持有（它们是服务策略，非传输）。

**(b) 抽出 HTTP 专属到 `lib/mcp/transport/http_transport.dart`**（或留在 `lib/service`）：把 origin/DNS-rebinding 校验、`x-api-key` 校验、`/mcp` 路径与 `POST` 检查、`utf8` 解码、202 通知处理、`_json`/`_safeJson` 写回全部搬到这里。它持有 `McpServer` 实例，对每请求：`serve(req)` → 校验 → `final resp = await server.handleJsonRpc(body)` → 若 `resp.isEmpty` 回 202，否则 `Content-Type: json` 写回。

**(c) `McpServer` 瘦身**：删掉 `dart:io` 的 `HttpServer`、`start()`/`stop()`、`_onRequest`、`_json`、`_safeJson`、`_isAllowedOrigin`；保留 `handleJsonRpc`、`_dispatch`、构造参数、`builtinInstructions`。`McpServer` 变为**纯 Dart、无 Flutter/无 dart:io 依赖**。

**(d) `McpController` 调整**（`lib/service/mcp_controller.dart`）：它仍 `extends ChangeNotifier`，仍负责 token/mDNS/前台服务；但不再 `new McpServer().start()`，改为：`new McpServer(repo:...)` 后，自己 `HttpServer.bind` 并把请求交给 `HttpTransport`（或把 `HttpServer` 起在 `HttpTransport` 内，`McpController` 调用 `HttpTransport.serve(server)`）。

**(e) 验收（行为零变化）**：
- `flutter analyze` 0 issue；`flutter test` 全过。
- 端到端：`node mcp-bridge/stdio-bridge.mjs --token <app 内 token>` 经 `adb reverse tcp:8765 tcp:8765` 连 `:8765`，`tools/list`、`add_plan`、`get_schedule`、`propose_schedule` 等输出须与改造前**逐字节等价**（用 `golden-samples.md` 或既有 e2e 比对）。
- 通知（如 `notifications/initialized`）仍正确回 202、不写响应体。

## 6. 后续轴（轴 A 验收后再排期，勿提前动工）

- **轴 A→Binder 终点**：仅当真有宿主要嵌入时，新增 `lib/mcp/transport/binder_transport.dart`（纯 Dart）+ Android 原生 `McpBinderService`（`onEventEmitter` → `handleJsonRpc`），权限协调走**同签名**（参照 goodshare `goodhub-local-plugin` 拍板「无缺口」）。无真实宿主前**禁止建空壳**。
- **轴 B**：抽 `Repository` 的 Flutter 信号依赖为可选回调，建 `shiguang_mcp` 包；宿主侧提供 `Repository` 适配。
- **轴 C**：接 `shiguang-relay` 作为 `McpController` 之后的另一种传输。
- **轴 D**：巡检并收敛平台依赖越界。

## 7. 禁止项

- 禁止重写 `McpServer` 的协议/分发逻辑、`tools.dart`、`command_handler`、`repository` 校验。
- 禁止把 `dart:io`/`HttpServer`/Flutter 依赖留在拆分后的 `McpServer`（内核须纯 Dart）。
- 禁止在轴 A 完成前建 `BinderTransport` 空壳。
- 禁止一次性大改接线；任何改造先加适配层跑通闭环再摘旧依赖。
- 禁止修改 goodshare 仓库（参考仅只读）。

## 8. 参考（只读）

- goodshare `lib/mcp/mcp_server.dart` 的 `handleJsonRpc(String)`（传输无关范本）。
- goodshare `context/epics/goodhub-local-plugin/memory.md`（Binder 嵌入：核心零改动 + Binder 传输 + 同签名权限协调）。
- 本仓库 `docs/design/schedule-app.md`、`functional-spec.md`、`ui-spec.md`（既有设计 SSOT）。

---

## 断点记录（每步完成后更新此处）

- [x] **轴 A→Binder 终点（goodhub SDK 接入）**：2026-10-14 实装，analyze 0 / 202 测试全过（+4）。
  goodhub 宿主已建成，按 G7 先实开核对 goodhub `android-sdk/plugin-core` AIDL 签名、
  discovery（intent action `com.zzh.goodhub.action.MCP_SERVER` + SERVER_ID/TITLE meta-data）、
  信任模型（同签名零弹窗）与 goodshare 范本 B1/B3 修复清单后再动手。走 goodshare 同款
  Flutter 路线（工具面在 Dart，非原生 @McpTool 路线）：
  - AIDL：`IMcpChannel.aidl`/`IMcpCallback.aidl` 原文拷入
    `android/app/src/main/aidl/com/zzh/goodhub/mcp/` + `buildFeatures { aidl = true }`。
  - 原生：`ShiguangMcpBinderService.kt`（照抄 goodshare McpBinderService，通道
    `shiguang/mcp_binder`、entrypoint `mcpBinderMain`；B1 per-call ≤5s 超时、B3 id 回显、
    ready 竞态 5s、FlutterInjector loader 坑全落）。差异：shiguang 工具面无 >5s 长任务，
    MVP 不走 `_async`+onEvent 终帧切片（answerReverse 兜底静默，契约位保留）。
  - Manifest：`<permission com.zzh.goodhub.permission.MCP_SERVER signature>`（声明方=本 app）
    + exported service（signature 护门 + action + SERVER_ID=shiguang/TITLE=拾光）。
  - Dart：`lib/mcp_binder_entrypoint.dart`（@pragma vm:entry-point）——**不经
    AppServices.init**（那会装配 McpController 起 HTTP/前台服务/远程配置，Binder isolate
    只要台账内核）：显式 `Db.registerDefaultStorageFactory()`（轴 B 纪律：不能靠模块加载
    副作用）+ `Repository()` + `McpServer(tokenProvider: () => null, weather: WeatherService())`。
  - R1 缓解：`db.dart` onOpen 加 `PRAGMA busy_timeout = 5000`（主/binder 双连接共写 WAL）。
  - 测试：`test/binder_entrypoint_test.dart`（binder 等价体=entrypoint 同构装配）——
    零鉴权 initialize/tools/list/add_plan 落库/坏 JSON 与未知方法 B3 id 回显。
  - **遗留**：真机联调未跑——两 app 须同机同批 debug 签名出包（same_sign_coord 方案 1），
    D.1 keytool 指纹比对 + D.2 bind 探针过门禁后才算 e2e 闭环。
- [x] **Step 1**：抽 `handleJsonRpc`，HTTP 行为零变化验收通过（analyze 0 / test 全过 / e2e 等价）
- [x] **轴 A→Binder 终点真机联调**：2026-10-14 完成，D.1/D.2 门禁全过，e2e 闭环。
  两 app 同签名（~/.android/debug.keystore）同批 debug 出包装机（OPPO CPH2767）；
  discovery 解析到 `app.shiguang.shiguang/.ShiguangMcpBinderService`；goodhub 调试页
  bind + add_plan 经 Binder 全链路落库核验（plans 表 1 行）。途中修两 bug：
  ① `main.dart` 漏 import `mcp_binder_entrypoint.dart` → 库被 AOT tree-shake，
  `Dart_LookupLibrary not found`（goodshare 范本有此步，补 import + ignore 注释）；
  ② `db.dart` `PRAGMA busy_timeout` 用 `db.execute` 报
  "Queries can be performed using SQLiteDatabase query or rawQuery methods only"——
  带返回值的 PRAGMA 须走 `rawQuery`。修复后 analyze 0 / 202 测试全过。
- [x] **Step 2（轴 B 第一段·就地纯 Dart 化）**：三处平台依赖解耦完成（2026-10-14），
  全仓 analyze 0 issue + 194 测试全过：
  - ① `Repository`：`extends ChangeNotifier` → `implements Listenable`（自实现
    listener 列表 + revision 自增；15 处 `ListenableBuilder` 消费面零改动）。
    注意：因 UI 全量依赖 `Listenable`，`flutter/foundation` 仅余 `Listenable` 类型依赖。
  - ② 存储注入：新建 `lib/data/storage.dart`（`ScheduleStorage`/`StorageTxn` 纯 Dart
    接口，含 query/insert/delete/rawUpdate/update 子集——命令层事务需要 read-your-writes）；
    `SqfliteStorage` 适配器留在 `db.dart`（平台依赖收口）；`Repository._database()` →
    惰性 `_store`。工厂注册**不能**用顶层 IIFE（Dart 顶层 final 惰性初始化不会执行！），
    改为 `Db.instance()` 幂等注册 + `AppServices.init()` 显式先注册。
  - ③ 天气注入：新建 `lib/mcp/weather_provider.dart`（`WeatherProvider` + NullWeatherProvider），
    `tools.dart` 去 `service/weather.dart` 依赖；`McpServer` 增 `weather` 字段，
    `McpController` 注入真实 `weatherService`；测试直接调 `callTool` 的用默认空实现。
  - 消费面改动：0（构造签名全兼容）。遗留决策：是否抽 `shiguang_mcp` 包待真实宿主驱动。
- [ ] 轴 B 第二段：`shiguang_mcp` 纯 Dart 包（待宿主）
- [ ] 轴 C：relay 传输接入
- [x] **Step 3（轴 D·平台依赖越界收敛）**：2026-10-14 完成，analyze 0 / 194 测试全过。
  - models 纯化：`artifact.dart`/`background.dart` 的 `debugPrint` → `dart:developer log`
    （`[DEGRADE]` 标记保留，无测试捕获 debugPrint 输出，已核实）。
  - `util/lan_ip.dart`（dart:io）归位 `lib/service/lan_ip.dart`（唯一消费者 mcp_controller）。
  - 保留项（已记录决策）：`repository.dart` 的 `Listenable` 类型依赖（轴 B ①；
    15 处 UI ListenableBuilder 消费）；`db.dart` 的 sqflite/path（合法收口点）。
  - 收敛后内核依赖面：lib/mcp+action+models+util 纯 Dart；lib/data 仅 Listenable 类型。
- [x] **Step 4（轴 C·MCP over relay 最小闭环）**：2026-10-14 完成。拍板：复用 RelayMessage
  扩展（v0.1.8 加性 header map）、好友 ACL + x-api-key 双保险、全链路最小闭环。
  - 契约：`RelayMessage.header`（role/corr_id/token；cipher=JSON-RPC 文本），
    **MVP 应用层明文依赖 TLS+ACL，E2EE 后续切片**（勿在此通道传非 MCP 敏感数据）；
    gen/dart 按本机 protoc_plugin 25.1.0 重生成（旧产物配不上 protobuf 4.x-6.x 任意运行时，
    最终定 `protobuf ^6.1.0`）。
  - relay 侧：`Hub.PushRelay` 定向直推 + `handleRelaySend` 落库后直推（所有 kind 通用，
    响应加 `delivered_online`）；**收方 WSS 收到后 REST ack 消费防 pending 重放**（语义已在
    Hub 注释钉死）。零 DDL。
  - app 侧：`lib/service/relay_client.dart`（WSS+AUTH→收单→handleJsonRpc→REST 回发+ack；
    connect/rest 可注入已单测）；`McpController` enable 时 fail-open 挂载（配置在
    mcp_settings_page relay 区，RelayKeys 内部键）；契约产物随仓副本 `lib/relay_proto/`
    （proto 变更后须从 relay 仓重拷）。
  - 桌面侧：`mcp-bridge/relay-bridge.mjs`（零依赖；应答匹配按 JSON-RPC id 回显——
    REST pending 不回 header，零 DDL 口径；`--peer` 缺省打印本端 user_id 供建好友）。
  - 验收：relay build/vet/test 绿（PG 集成测试按 RELAY_TEST_PG_DSN 约定跳过——含新增
    在线直推/离线留 pending 两个用例）；app analyze 0 issue + 198 测全过（+4 relay）。
    **遗留**：真机/真 relay e2e 联调未跑（本环境 docker 不可用）；顺手修复 sim_activity_test.go
    五处存量编译错误（该文件从未编译通过）。
- [x] **MCP 暴露面收口（2026-10-15）**：用户拍板「MCP 服务隐藏、不对外开放、只内部调试，
  机制全部走 bind（Binder 宿主通道）」+ relay 处置裁决（代码保留、随隐藏入口收起、默认关）：
  - HTTP：`HttpServer.bind(InternetAddress.loopbackIPv4)` 仅回环（原 anyIPv4 全网卡）；
    桌面侧经 `adb reverse tcp:8765 tcp:8765` 接入；`endpoint()` 改回环常量展示。
  - mDNS：bonsoir 依赖摘除（pubspec）、mcp_controller 广播块与 lan_ip.dart 删除；
    schedule-app §11「网络配对」行标废弃。
  - UI：设置页「MCP 服务」入口行移除→隐藏入口（页尾「关于」区长按标题唤出「调试菜单」
    →「MCP 调试服务」带运行态，照 goodshare 范本，用户指令改形态）；今日页
    「桌面 AI 未连接」配对卡摘除；MCP 子页地址改回环展示+提示改调试口径；relay 区保留。
  - 保留项：token 鉴权（Android 同设备回环不隔离）、前台服务保活（Binder 与调试均需
    进程存活，通知文案改调试口径）。
  - 验收：flutter analyze 0 issue / flutter test 202/202 / arch-guard 6/6 / docs-lint 过
    （ui_settings_token_test 改走隐藏入口手势）；m1 bonsoir 升级挂账作废归档 done.md；
    workflow/index skill + README/mcp-bridge README + ui-spec §0.4 两行 + schedule-app
    §11「MCP 暴露面」新行同步。
  - **遗留**：真机验证未跑（装包+隐藏入口手势+adb reverse 回环可达+宿主仍可 bind 核验）。
- [x] **R2 发布 v1.2.1 (28020)（2026-10-15）**：MCP 暴露面收口内容发布（基线 68e201a + 版本抬升 8fd5568 + 清单 e9f56d6）；
  `release-r2.sh --commit` 全流程过（analyze 0 → arm64 release 21.0MB → sha256 e63de6e8… → R2 公开域）。
  远端核验：公开清单 GET 过（五字段齐）+ APK HEAD 200（20,964,788 字节与构建产物一致）。
  **遗留**：真机自更新流程未验（app「检查更新」→ 装 28020 → 隐藏入口/回环/宿主 bind 一并核）。
