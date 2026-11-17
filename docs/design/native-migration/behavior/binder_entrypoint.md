---
status: active
updated: 2026-11-15
---

# 行为清单 · binder_entrypoint（Binder 入口：ready 竞态 / _async 切片 / 错误码红线）

> §9.1.6 模板产出；**已平移（混合态，2026-11-14 D8 拍板①）**：协议面 Kotlin（:core:mcp McpServer）+ 业务面 channel `toolface_schemas`/`toolface_call` 桥回 Dart（二元协议 {ok,content}/{ok:false,error}——领域拒绝 Dart 组 error 体 Kotlin 透传）。
> **P2 切换收口（2026-11-15，D8 收官形态第二步）**：tools/call 走 `NativeToolFace` 原生内核（repo=NativeSqlRepository(AndroidSqlStore)，写经单线程写队列=R3 双写者口径；golden toolface 23 case 对拍护航）——`toolface_call` 桥通道与 Dart 侧 handler 退役挂 P5 清理（Dart 物理删除统一 P5 前夜）；tools/list 的 schemas 仍沿桥（工具描述文案未平移）。天气搭载=P3（OkHttp 接线，现 null=不搭载，契约形一致）。
> 状态：**待人工过目**（红线面保持注记：ready 5s / F1 专用 worker 线程 / -32000·-32603 逐位；dispatch 全量转发已退役）。
> 真机验证：Binder 链路需 goodhub 宿主拉起联调（挂账用户侧）；Dart 侧 binder_entrypoint/parallel_sim 测试 5/5 回归绿。
>
> **启动时序分层核验（2026-11-14，P1 混合态）**：✅ 已满足——Binder 被拉起走独立 headless
> engine + `mcpBinderMain` 最小 entrypoint（Db 工厂注册 + Repository + 工具面，**不经
> AppServices.init**，零 UI/HTTP/前台服务初始化），5s ready 预算守护成立；Kotlin 侧
> Application 层待 P5 原生 UI 落地时才实质化（现 `${applicationName}` 默认 FlutterApplication
> 空转，无违规面）。

| 字段 | 内容 |
|---|---|
| 交互意图 | goodhub 宿主经 AIDL `call` 拉起 MCP：ready 等待 Dart 侧装配 → dispatch 请求 → 回帧；宿主并行调用汇入单 Dart isolate 串行竞争的根治路径 |
| 触发与阈值 | **ready 5s 竞态等待上限**（超时即 `-32000` 呈现为中断）；per-call 5s 超时 → `-32000`；**宿主侧另有 120s idle 超时兜底**（义务①，goodhub 仓） |
| 状态空间 | ① ready 前 call → 等待（≤5s）② ready 后 call → dispatch ③ tools/call 在宿主回调已注册 → **F1 一律走 `_async` 占位帧 + `onEvent` 同 id 终帧切片**（契约 thirdconnect.md §1.2.2）④ dispatch 异常 → 原生 Result 回调兜底成 error 终帧（防宿主 `_pendingTerminal` 悬挂）⑤ 宿主未注册回调 → 同步路径 |
| 落库命令与拒绝码 | `-32000`（ready 超时 / per-call 超时）/ `-32603`（dispatch 异常）/ `-32001`（X-Api-Key 鉴权，传输层）——**三码语义红线逐位不变** |
| 反馈形式 | AIDL IMcpChannel.call 返回占位帧；终帧经 `IMcpCallback.onEvent` 转发（同 id 匹配） |
| 黄金向量指针 | mcp-rejection（协议面）+ 本清单红线段（-32000/-32001 语义在 rules.error_codes 与 transport_guards）；**Binder 链路无向量集**（AIDL 契约面，验证=binder_entrypoint_test Dart 侧 + 真机 goodhub 联调） |
| 像素基线指针 | 无 UI |

## 平移注记（待实现要点——红线逐位不变）

- `ShiguangMcpBinderService.kt`（302 行）的 `handleJsonRpc` 路由骨架**保留**，把 MethodChannel `dispatch` 换成 `:core:mcp` McpServer 直接调用（协议面 Kotlin 化）。
- **tools/call 业务面双轨期桥回 Dart**（channel `toolface_call`）——P1 收官形态的前提是 Dart 工具面仍活着（业务工具平移在 P2）；⚠ 此混合态的设计含义待拍板确认（见 pending-confirm D8）。
- 启动时序分层：Application.onCreate 只 Core（SQLite 连接/命令面/AIDL 路由器）；MCP 被 bind 拉起**不得经过任何 UI 初始化路径**（5s ready 预算）。
- `mcp_binder_entrypoint` 独立装配不经 `AppServices`（既有分离延续）。
