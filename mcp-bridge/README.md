# mcp-bridge：桌面 AI ↔ 手机 MCP 的两个桥

## stdio-bridge.mjs（内部调试，2026-10-15 口径）

stdio ↔ HTTP 直连：`--url http://127.0.0.1:8765/mcp --token <访问令牌>`；
服务仅绑手机回环（2026-10-15 拍板：MCP 不对外暴露，生产通道走 Binder
宿主进程内嵌入），桌面侧须先 `adb reverse tcp:8765 tcp:8765`。零依赖（Node ≥18 内建 fetch）。

## relay-bridge.mjs（外网可达，轴 C 新增）

stdio ↔ relay 中转：手机与桌面各持 relay 账号且**互为好友**，MCP 请求经 relay
定向中转（契约 v0.1.8，`kind="mcp"`）。手机不在线即失败（-32002），与
schedule-app.md:331「手机不在线 AI 失联」同口径。

```bash
# 1) 首跑（不带 --peer）打印本端 user_id——手机端加好友时用
node relay-bridge.mjs --relay https://relay.example.com --user desktop_bot --pass 'secret'

# 2) 与手机端互加好友后，正式接入（Claude/Cursor 等 MCP 客户端把它配成 stdio 命令）
node relay-bridge.mjs \
  --relay https://relay.example.com \
  --user desktop_bot --pass 'secret' \
  --peer <手机端 user_id> \
  --token <app「MCP 服务」页的访问令牌>
```

鉴权双保险：relay 账号 JWT（好友 ACL 由服务端强制）+ MCP x-api-key（第二道）。
应答匹配按 JSON-RPC `id` 回显（REST pending 不回 header，零 DDL 口径）。
请求串行保序（与 stdio-bridge 同纪律）。

## e2e

`e2e-check.mjs` 自检 stdio-bridge（mock 上游）。relay-bridge 的端到端联调需
relay 服务（PG/Redis）运行中：`docker compose up -d` 后按上法对跑。
