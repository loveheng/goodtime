---
dev-loop: memory
format: v1
epic: collab-review
total-merged: 0
---

# collab-review

- [2026-10-24] 临时 epic（常驻挂靠，不绑 CURRENT）：承载 2026-10-24 多人协同功能评审的待拍板项。无正式底子文档——挂载指针=context/epics/collab-review/todos.md（memo-collector §0 防双写：拆解归 todos，不建伪 docs/ 文档装挂载底子）。评审上下文：Directive 全动作面维持一期已拍板（collab-schedule-draft.md §7-7）；架构对账修正已落稿（schedule-app.md §11「协同对账修正」行）。

## 断点
- 下一步：**四项已全部代码落地（2026-10-25，留痕=collab-schedule-draft.md §7-8 + collab-implementation.md §5「落地对账」+ relay README v0.1.9-contract）**——契约双端 codegen / 服务端门禁白名单+发起人校验 / FSM recheck+LEAVE+FORCE_FINALIZE 死路径清理 / proposalAliveTtlMs / 拒绝面板减层；全量 464/464 绿、relay PG 集成测试绿（Guards/并发回归含内）。本临时 epic 收尾就绪（等用户指令删除）。连带发现（服务端既有缺口，不在本 epic）：模拟套件 7 条 DEFECT + REOPEN 终态门禁缺口 + identity.dart PoP 注释。
