---
status: active
updated: 2026-11-14
---

# 行为清单 · http_transport（HTTP 传输层：origin/鉴权/路由守卫 + 202/405）

> §9.1.6 模板产出；**待平移**（P1 剩余——Kotlin 侧落 :core:mcp 或 app 传输层）。
> 状态：**待人工过目**（行为语义由 mcp_server_test 钉样 + D5 拍板⑤–⑦固化进 rules.transport_guards）。

| 字段 | 内容 |
|---|---|
| 交互意图 | 把 HTTP 专属守卫（origin/鉴权/路径/方法）从内核剥离：守卫全过 → body 交内核 handleJsonRpc → 回 JSON；通知空串回 202 |
| 触发与阈值 | 端口绑定 127.0.0.1 loopback（不接受外部 address）；端口可配（默认 8765；桥形态 port=0 随机） |
| 状态空间 | 五守卫 × 响应码：① Origin 在场且非 localhost 族（localhost/127.0.0.1/[::1]/::1，任意端口，大小写不敏感）→ **403 + -32600**（MCP 规范 DNS-rebinding 防护；桌面直连不带 Origin 不受影响）② tokenProvider 非 null 且非空串时 x-api-key 不等 → **401 + -32001**（空串 token=关闭鉴权，D5⑤）③ 非 /mcp 路径 → **404 + -32600** ④ 非 POST → **405 + -32600** ⑤ 全过 → 200（内核结果）/ 通知 → **202** |
| 落库命令与拒绝码 | 零落库；错误帧形态 = {jsonrpc,id:null,error{code,message}}（守卫帧 id 恒 null） |
| 反馈形式 | HTTP 状态码 + JSON body；Content-Type application/json；客户端异常断开静默（safeJson） |
| 黄金向量指针 | rules.transport_guards（复刻清单——守卫在传输层不入内核向量对拍；token 判定式/Origin 白名单/404·405 逐条）；mcp_server_test›通知 202/token 鉴权钉样 |
| 像素基线指针 | 无 UI |

> **去留口径（2026-11-14，P1 混合态下澄清）**：HTTP 传输**保留 Dart 侧实现**（P1 范围名义含本单元，但混合态下 Kotlin 化无收益——HTTP 需前台 app 常驻，主 isolate Dart 必活，Kotlin 化纯属搬运且多一跳桥；唯一受益场景「app 被杀后 HTTP 仍服务」物理不成立）。设计稿 P1 范围行对本单元的平移由本注记覆盖；若后续 Binder-only 形态演进再议。

## 平移注记（待实现要点）

- 守卫顺序：Origin → 鉴权 → 路径 → 方法 → body（逐位保持——回显的 status/code 组合是向量外契约）。
- body 读取后全量交内核（chunked 读取语义对齐 Dart `utf8.decoder.bind(req).join()`）。
- 内核空串 → 202 无 body；非空 → 200 + jsonDecode 后写回（协议层不重排序键）。
- 500 兜底：serve 异常 → 500 + -32603 帧（safeJson 吞写失败）。
