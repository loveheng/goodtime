---
memo: todos
format: v3
---

# 待办列表

## misc
- [ ] [2026-10-11] (功能) recovery_vault 换机恢复切片：账号已登录但本机身份/MSK 丢失（卸载重装/换机/清空间）后凭服务端 recovery_vault 还原协同身份，含金库包裹钥独立于登录密码的红线；此前真机已实损一账号（身份不可找回，联调期以换名重注兜底） (功能) (层:L3) (重:重) (src: 用户, 会话) (src: ai)
- [ ] [2026-10-11] (排障) (层:L2) (重:中) 真机复验发起回执：重装后复点「发出邀请」，把新文案「没发出去：<原因>」带回定位真伤（prekey 404/401/网络/token），再修传输或会话层 (src: ai, compose 回执诚实化 2026-10-11)
- [ ] [2026-10-10] (风险) (层:L2) (重:轻) 查 adjust_blocks/propose_schedule 两个疑似 bug：move 仅 start_min 坍缩 5 分钟；melted 块仍挡 propose 占用碰撞 (src: ai, MCP 排程实战取证 2026-10-10)
- [ ] [2026-10-10] (功能) (层:L3) (重:中) 块绑定说明实体落地：说明独立挂计划层 + 块 note_id 引用共享 + 块浮层展示 + MCP schema 支持（落点四件见 §11 台账行，待细化排期） (src: 用户, 2026-10-10 拍板 schedule-app §11)
- [ ] (待拍板) Q11 图片凭证接入深度与时机（端侧 OCR 取字 / ML Kit 选型 / 批次） (层:L1) (重:轻) (src: fact 草案 §7-Q11) (2026-10-07)
- [x] (已拍板) 方向 B 启动 + §13 地基转向拍板：守零代发+内容E2EE，放开同步/账号/服务端联系人；产品灵魂四项不变 (层:L3) (重:中) (src: fact 草案 §6, schedule-app §13) (2026-10-09)
- [x] (客户端) 被邀端密钥入口：`GET /v1/bundles/{id}/key-envelopes` → 解开 K_bundle → `BundleKeySource.persist`（`lib/collab/enroll.dart` BundleKeyIntake + `CollabRuntime` onBundleSeen 触发；否则被邀端收到邀约也解不开密文，长期半盲） (层:L3) (重:高) (src: 2026-10-10 lib/collab/enroll.dart + runtime.dart) (2026-10-10 完成)
- [x] (客户端) App 冷启动挂载常驻 CollabEngine：`main.dart`/`AppServices.attachCollabLifecycle` 把回路与 vault 机态绑定（解锁即起、锁定即停）；此前只发起页手动 drainNow 弹出的缺陷闭环 (层:L2) (重:高) (src: 2026-10-10 lib/collab/runtime.dart + lib/app_services.dart + lib/main.dart) (2026-10-10 完成)
- [x] (客户端) 好友域 UI：扫码建联 + 安全码核对页（§1.6 / Phase 4.6）；发起页的好友名单依赖 (层:L3) (重:中) (src: collab-implementation §4.6 / lib/ui/collab/{friends,my_qr,scan,safety_number}_page.dart + lib/collab/friends_ticket.dart + lib/collab/relay/friends_api.dart) (2026-10-10 完成；真机扫码 + relay pod 联调待实机验收)
- [ ] (功能) 多人轨去重（重复/冲突/互补）留 Phase 4 UI 时定 (层:L3) (重:中) (src: collab 草案 §3)
- [x] (契约) Phase 0 契约冻结：proto + DDL + 双端生成 + 仓骨架 + tag（零 Go 业务代码）→ epic `online`（独立仓 /home/zzh/app/shiguang-relay，tag v0.1.6-contract，2026-10-09 已完成） (层:L3) (重:中) (src: collab-implementation §1) (2026-10-09 完成)
- [x] (服务端) Phase 1 服务端最小闭环（Go 盲网关 + PG/WSS 广播）→ epic `online`：**1.1–1.8 全部完成**（独立仓 commit `0e320ec`→`adab287`，smoke-phase1.sh 全通；Wake Push 仍为 Noop，接 FCM/APNs 时补） (层:L3) (重:高) (src: collab-implementation §2) (2026-10-09 完成)
- [x] (客户端) Phase 2.1–2.4 客户端安全底座：依赖接入 + 契约同步 + 密钥域 + 注册流 + 加密封装单点（sealPayload/openPayload + SealedPayload）→ epic `online`，落 `lib/collab/` (层:L3) (重:高) (src: collab-implementation §3) (2026-10-09 完成)
- [ ] (客户端) Phase 2.5–2.7：WSS 客户端（首帧 AUTH + 退避重连）/ 单消费管道（StreamQueue）/ 双机对测（落 `lib/collab/`，不动单机表） (层:L3) (重:高) (src: collab-implementation §3)
