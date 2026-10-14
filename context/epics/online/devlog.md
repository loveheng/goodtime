---
dev-loop: devlog
format: v1
epic: online
total-merged: 0
last-merge: none
---

（增量日志按行追加；≥5 条自动归并进 memory.md）

- [2026-10-14] [变更]: 轴 A→Binder 终点·goodhub SDK 接入实装（宿主已建成，G7 实开核对 plugin-core AIDL/discovery/信任机制后动手，断点区有全程留痕）：AIDL 两份拷入 `android/app/src/main/aidl/com/zzh/goodhub/mcp/` + gradle `buildFeatures{aidl=true}`；`ShiguangMcpBinderService.kt`（goodshare 范本模式，通道 `shiguang/mcp_binder`，B1 ≤5s per-call 超时/B3 id 回显/ready 竞态/FlutterInjector loader 坑全落；MVP 无 `_async`+onEvent 切片）；Manifest 加 `<permission MCP_SERVER signature>`（声明方=本 app）+ exported service（action+SERVER_ID/TITLE meta-data+signature 护门）；`lib/mcp_binder_entrypoint.dart`（轻量装配：显式注册存储工厂+Repository+零鉴权 McpServer+真实 WeatherService，**不经 AppServices.init** 防 Binder isolate 起 HTTP）；`db.dart` onOpen 加 `PRAGMA busy_timeout=5000`（R1 双 isolate WAL 缓解）
- [2026-10-14] [验证]: flutter analyze → 0 issue；flutter test → 202/202 全绿（新增 test/binder_entrypoint_test.dart 4 例：零鉴权握手/tools 面不挡/add_plan 落库全链路/坏 JSON 与未知方法 B3 id 回显）；arch-guard 未跑（binder 改动集中在 android/ 与新 entrypoint，无 lib 内核越界——entrypoint 在 lib/ 顶层仅 import data/mcp/service.weather，与 HttpTransport 同层级）
- [2026-10-14] [风险]: 真机联调未跑——两 app 必须同机同批 debug 签名出包（same_sign_coord 方案 1），否则 bind 直接 SecurityException；D.1 keytool 指纹比对 + D.2 bind 探针过门禁后才算 e2e 闭环。MVP 差异已留痕：shiguang 工具面无 >5s 长任务，`_async`+onEvent 终帧切片未做（answerReverse 契约位保留兜底静默）
- [2026-10-14] [验证]: 轴 A→Binder 真机联调 e2e 闭环（OPPO CPH2767，同签名同批 debug 包）：D.1 指纹比对过 → discovery 解析 ShiguangMcpBinderService → goodhub bind 探针 → add_plan 经 Binder 落库核验（plans 表 1 行）。途中修两 bug：① main.dart 补 import mcp_binder_entrypoint.dart（AOT tree-shake 致 Dart_LookupLibrary not found）；② db.dart PRAGMA busy_timeout 改 rawQuery（带返回值 PRAGMA 被 Android execSQL 拒绝）。修复后 analyze 0 / 202 测试全过
