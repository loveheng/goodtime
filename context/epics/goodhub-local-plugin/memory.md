---
dev-loop: epic-memory
format: v1
epic: goodhub-local-plugin
status: active
pairs_with: "goodshare:goodhub-local-plugin（原始范本，只读不跨改）"
related: "online（执行 epic，Step 1 接缝配方在 online/memory.md）"
---

# 🔗 shiguang × goodhub-local-plugin 绑定域（Binder 嵌入对接）

> 本域是 shiguang 侧对接 goodshare `goodhub-local-plugin` 的**绑定与评估域**。goodshare 的 `goodhub-local-plugin` 是「核心零改动、只加 Binder 传输层 + 同签名权限协调、无缺口」的已拍板范本（**只读，不跨项目改**）。shiguang 的 Binder 嵌入改造以本域为参考锚点，执行落在 `online` epic（`online/memory.md`）。任何偏离须回本域「评估留痕」与「原则」。

## 0. 绑定关系（为什么要绑）

- shiguang 与 goodshare 共用同一套无头化内核范式：命令模式 / Repository / MCP 工具面 / JSON-RPC 协议。goodshare 已先完成「拆分化 + Binder 嵌入」并拍板「无缺口」。
- shiguang 终态是把 MCP 层作为 **Binder 插件被宿主（GoodHub 式 app）进程内嵌入**——这恰是 goodshare `goodhub-local-plugin` 的已验证终点。
- 因此 shiguang 的 `online` epic 与 goodshare 的 `goodhub-local-plugin` 是**同一目标的两端**，必须绑定对齐签名与权限协调，禁止凭记忆各搞一套。

## 1. shiguang 现状评估（对齐 goodshare 改造流程的盘点）

已具备、直接复用（不重写）：命令模式 `lib/action/`、Repository `lib/data/repository.dart`（FIFO 锁 / 乐观锁 / 白名单 patch / 批原子）、MCP 工具面 `lib/mcp/tools.dart`、协议常量 `lib/mcp/jsonrpc.dart`、HTTP 总控 `lib/service/mcp_controller.dart`。这与 goodshare 搬过来的无头化骨架一致。

**唯一结构性缺口（命门）**：`lib/mcp/mcp_server.dart` 的 `McpServer` 内嵌 `dart:io` 的 `HttpServer`，HTTP 逻辑 inline 在 `_onRequest`，**没有 goodshare 那种 `String handleJsonRpc(String)` 传输无关入口**——这正是将来嵌 Binder 要复制整套分发、产生返工的根源。对应 goodshare 改造流程的「先立接缝」一步。

## 2. 评估留痕：G1–G7 修正（落地前必读）

- **G1｜漏了现有测试迁移（致命）**：`test/mcp_server_test.dart`（180 行）直接 `server.start(port:0)` / `server.port` / `server.stop()` 并断言 202 / 401 / 200 与「14 工具」tripwire。Step 1 删 `start/stop/port` 会令其编译失败，「test 全过」不可达。必须同步迁移：`spinUp` 改用 `HttpTransport.start/stop`，`rpc()` 仍走 HTTP `/mcp`。
- **G2｜轴 B「唯一解耦点 = ChangeNotifier」说窄了**：真实平台耦合有三处——(1) `ChangeNotifier`/`foundation`（`repository.dart:4`）；(2) **sqflite 原生 DB**：`Db` 是静态单例、`instance()` 硬编码 `getDatabasesPath()` + `openDatabase`，须抽象成可注入 storage 接口（现有 `Db.overridePath` 仅测试口）；(3) `tools.dart → service/weather.dart → dart:io HttpClient`，好在 `WeatherService` 已支持 `fetch` 注入（`WeatherService({Future<String> Function(Uri url)? fetch})`），须显式要求宿主注入。
- **G3｜「逐字节等价」不成立（仅 JSON body 等价）**：原 `_onRequest` 解析失败返 400、非法请求返 404/405/401/403；抽缝后解析失败由 `handleJsonRpc` 返回错误体、HttpTransport 一律 200 写回 → **400→200 漂移**。验收收紧为「响应体逐字节等价 + 状态码变化逐条登记」。
- **G4｜HttpTransport 落点矛盾**：轴 D 规定平台依赖只进 `lib/service`/`lib/update`、不渗入 `lib/mcp`；但 Step 1(b) 原把含 `dart:io` 的 `HttpTransport` 放 `lib/mcp/transport` 会反带 `dart:io` 进内核。**落点钉死 `lib/service/http_transport.dart`**。
- **G5｜HttpServer 归属二义**：(b) 说 HttpTransport 持有 McpServer、(d) 说 McpController 自己 bind。钉死：**HttpTransport 持有 `HttpServer` 并暴露 `start/stop/port`**，McpController 只注入 `McpServer` 不再碰 `HttpServer`（测试迁移最自然）。
- **G6｜兜底缺失**：原 `start()` 的 listen 外层 try/catch 兜底 500（`mcp_server.dart:66-76`）须由 HttpTransport 对等保留，避免异常请求泄漏。
- **G7｜外部假设待验**：goodshare 的 `handleJsonRpc` / `goodhub-local-plugin` 是另一仓库，开工前须实际打开核对签名、错误码与权限协调，禁止凭记忆照搬。

## 3. 对齐 goodshare 改造流程的要点

- **先立接缝后加传输**：先抽 `handleJsonRpc`（M1 加法先行），HTTP 行为零变化跑通；真有宿主再建 Binder 传输层，**绝不提前建空壳**。
- **Binder 终点（轴 A→）**：新增 `lib/service/binder_transport.dart`（纯 Dart 传输层，对应平台原生 `McpBinderService`，`onEventEmitter` → `handleJsonRpc`），权限协调走**同签名**（goodshare 拍板「无缺口」）。
- **核心零改动纪律**：协议 / `command` / `repository` 校验一律不重写，只动接缝与外壳；任何改造先加适配层跑通再摘旧依赖。
- **参考 goodshare 流程的具体落点**：goodshare `lib/mcp/mcp_server.dart` 的 `handleJsonRpc(String)` 是传输无关范本；goodshare `context/epics/goodhub-local-plugin/memory.md` 记录「核心零改动 + Binder 传输 + 同签名权限协调」的全过程拍板——开工前打开核对，不要凭本域摘要二手转述。

## 4. 参考（只读，开工前打开核对）

- goodshare `lib/mcp/mcp_server.dart` 的 `handleJsonRpc(String)`（传输无关范本）。
- goodshare `context/epics/goodhub-local-plugin/memory.md`（Binder 嵌入：核心零改动 + Binder 传输 + 同签名权限协调——本域配对范本）。
- 本仓库 `online/memory.md`（执行 epic，Step 1 接缝配方）、`docs/design/schedule-app.md`。

---

## 断点记录

- [x] 绑定关系确立 + 现状评估 + G1–G7 留痕
- [x] `online` epic 按本域修正执行 Step 1（抽 `handleJsonRpc` + `lib/service` 的 `HttpTransport` + 迁移 `test/mcp_server_test.dart`）
- [x] 轴 A→Binder 终点实装（2026-10-14，执行留痕见 `online/memory.md` 断点区）：goodhub
  宿主建成，实开核对 `android-sdk/plugin-core` AIDL 与 discovery/信任机制（G7 清偿），
  AIDL 拷入 + `ShiguangMcpBinderService`（B1/B3/ready 竞态全落）+ signature 权限 +
  `lib/mcp_binder_entrypoint.dart`（轻量装配不经 AppServices）+ busy_timeout R1 缓解 +
  binder 等价体单测 4 例；analyze 0 / 202 测试全过。**剩余**：真机联调（同签名同批出包
  + D.1/D.2 门禁），MVP 无 `_async`/onEvent 切片（工具面无 >5s 长任务）。
