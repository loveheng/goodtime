---
dev-loop: decisions
format: v1
epic: global
total-merged: 0
last-merge: none
---

# decisions

## native-migration

### [2026-11-11] 转原生 Android：档位选型（走 B3 全量迁移） (src: 用户)

**场景/痛点**：拾光是 Flutter Android app（`lib/` 116 文件 / 35,524 行）+ 内嵌 MCP 服务端（MCP 100% 跑在 Dart VM，由 goodhub 跨进程拉起的 headless FlutterEngine 承载）。诉求为「彻底摆脱 Flutter」。同期 V1.5 候补（通知 / 日历拖拽 / 周月视图 / Markdown 导出）与协同五阶段施工序均未完成，且为单人项目（唯一作者，近 30 天 20 次提交）——全量迁移意味着 12–18 人月的功能冻结。

**可选方案**：

- `B0` 维持 Flutter + 局部减负（替换 3 个高风险平台插件）：5–8 人日，零功能冻结。**否决理由**：不回应「摆脱 Flutter」这一诉求本身，只降低插件升级风险。
- `B1` 仅做 P0 契约固化与黄金向量：10–15 人日，无条件成立但非终态。**否决理由**：单独投入不构成对诉求的完整回应，但可作为 `B3` 的第一阶段并入。
- `B2` P0+P1（MCP 内核平移 + 原生内核接入）：22–33 人日。**否决理由**：产生「原生内核 + Flutter UI」混态，两套实现长期并存必然漂移（R7），不可作为终态交付；其边际收益低于直接推进 `B3`。
- `B3` 全量迁移 P0–P5：242–353 人日 / 12–18 人月。**采纳**。

**最终决定**：走 `B3`。以 `P0` 契约固化（黄金向量 + DDL 冻结 + relay proto 双副本收敛）前置，`G0`/`G1`/`G2` 三道决策门递进，`G2` 为唯一止损点（内核平移守住预算与信心才继续 `P3`/`P4`）。方案、成本与风险明细见 `docs/design/native-migration.md`；任务拆解落 `context/epics/native-migration/todos.md`。

**AI 洞察**：

- 决策之所以成立，是因为项目架构自带迁移接缝——`coding-standards.md` §1 的「无头系统」原则 + `arch-guard` R1–R6 机械守护，使 UI 层已是纯呈现层（`lib/ui/` 对数据层仅 10 个读方法 + 17 处订阅，写全走命令面）。这是早先「轴 B 纯 Dart 化」的存量投资，不是运气。
- 真正的风险不在 UI 视觉细节：A 类 tokens（`tokens.dart` 155 行）已是独立于 Flutter 的纯数值常量，配合规范 SSOT 与 debug 桥已有的整屏截图能力（可做像素基线），视觉细节基本不丢。风险集中在三类**静默失败**的线级契约——E2EE 封装字节级（Argon2id 在 Kotlin 需 BouncyCastle，JCA/Tink 均无）、relay protobuf wire format、goodhub AIDL 语义（`_async` 占位帧 + `onEvent` 终帧 + 三个错误码）。这三类错了不报错，只能靠 `P0` 黄金向量挡住。
- 成本估算经历一次上修：P4 UI 自顶向下估 90–140 人日，按「设计系统 + 组件族 + 主干页 + 浮层族 + 协作页 + 设置引导 + 基线基建 + 走查」自底向上拆解为 120–175，总成本由 212–318 上修至 242–353 人日。教训：「按规范重画」与「按代码翻译」两种口径混用时必须用后者自底向上校准，前者系统性低估。

## misc
