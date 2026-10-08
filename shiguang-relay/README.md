# shiguang-relay

拾光（shiguang）多人协同的**中继服务端**契约仓（Go 实现将在 Phase 1 落地，本仓仅含冻结契约）。

> 设计 SSOT：`docs/design/schedule-app.md`；协同设计：`docs/design/collab-schedule-draft.md` 与 `docs/design/fact-user-relay-draft.md`。

## 本仓内容（契约层，零 Go 代码）

| 文件 | 作用 |
|---|---|
| `relay_protocol.proto` | 全部消息信封、ActionType 枚举、鉴权/好友/密钥/瞬时信令协议 |
| `schema.sql` | PostgreSQL DDL（users / friendships / bundles / bundle_skeletons / team_schedule_events / key_envelopes / relay_messages） |

## 2026-10-09 审查修正要点（已内建）

1. **废除独立 Mailbox API**：事实同步并入 bundle 事件流（`ATTACH_FACT` / `UPDATE_FACT` / `DETTACH_FACT`），与排程共用全局 `seq_id` 与 WSS 广播。
2. **零明文昵称**：注册/好友接口剔除 `display_name`；昵称/备注仅存 `encrypted_profile`（端侧全密文）。`friendships` 仅 4 列。
3. **零 `entity_key` 暴露**：服务端不接收/不存储任何业务代号（车次、预约码）。事实级去重由**客户端 resolver（纯本地）**解密后完成；传输级去重由 `client_msg_id` 幂等保证。
4. **好友 Ticket 制**：`POST /v1/friends/invite {ticket}` 替代 `{to_user}`，防全库遍历撞库。
5. **双 Token + 重放防护**：详见协同设计 §1.4；`client_msg_id` + 时间戳 Redis `SETEX 300` 去重。
6. **网关硬约束**：单包 Payload **≤256KB**；P2P 瞬时信令 `relay_messages` **TTL 即焚** + 后台清理 cron。

## 契约版本

- `v0.1.0-contract`：首版冻结（含上述审查修正）。Phase 1 起按此实现，未拍板前不改动枚举/DDL 结构。
