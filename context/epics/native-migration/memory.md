---
dev-loop: memory
format: v1
epic: native-migration
total-merged: 0
last-merge: none
---

# native-migration

- [挂载] docs/design/native-migration.md（内容 SSOT：评估 + 阶段 P0–P5 + 风险 R1–R8 + 判据 C1–C4）
- 任务拆解：context/epics/native-migration/todos.md（P0–P5 逐条，SSOT）
- 档位选型 ADR：context/decisions.md 的 `## native-migration` 节（2026-11-11）

## 状态

- 档位：`B3` 全量迁移（2026-11-11 用户拍板「彻底摆脱 Flutter」）
- 当前阶段：`P0` 契约固化与黄金向量（未开工）
- 门禁：`G0` → `G1` → `G2`，`G2` 为唯一止损点（内核平移守住预算与信心才继续 `P3`/`P4`）
- 成本：242–353 人日 / 12–18 人月（单人全职；双人并行 9–11 人月）
- 铁律：迁到 Kotlin 的是**实现**，不是**契约**——协议 / 命令面 / 拒绝码 / E2EE 封装字节 / proto wire format / DDL 六类语义逐位不变，判定靠 `P0` 黄金向量对拍
- 红线：`_async` 占位帧 + `onEvent` 终帧切片、`-32000`/`-32603`/`-32001` 错误码、goodhub AIDL 契约冻结（schedule-app §11「Binder _async 切片」行）

## 断点
- [断点] 下一步：开 P0 第一项——抽取黄金向量六类中的 E2EE 封装往返（lib/collab/sealing.dart + freemask.dart）落 test/golden/，同时建 Dart 侧基准执行器锁定期望值（不写 Kotlin）
