---
status: active
updated: 2026-11-14
---

# 行为清单 · mcp_server_core（MCP 内核：jsonrpc 常量 + 协商 + handleJsonRpc）

> §9.1.6 模板产出；已平移（`:core:mcp` McpServer.kt，P1 首闭环 2026-11-14）。
> 状态：**已人工过目**（D5 拍板七条边界语义 + 17 case 向量对拍全绿 + 变异探针实证）。

| 字段 | 内容 |
|---|---|
| 交互意图 | 承载 MCP JSON-RPC 命令面：解析请求 → 分发（initialize/ping/tools/list/tools/call/resources/list/prompts/list）→ 回响应帧；传输无关（HTTP/Binder 共用内核） |
| 触发与阈值 | 无手势面；ready 竞态 5s 上限在 Binder 入口层（本单元不含） |
| 状态空间 | 请求形态 × 分发结果：坏 JSON / 非对象 / 缺 method / 通知（id=null）/ 未知方法 / tools/call 缺 name / 领域透传 / 内部兜底 / 成功——**全枚举已入向量集**（mcp_rejection_vectors 17 case + 边界 D5 拍板七条） |
| 落库命令与拒绝码 | 本单元零落库；错误码族 = -32700/-32600（双态）/-32601/-32602（缺 name·未知工具）/-32603 兜底——逐字见 `Err` 常量与向量 expect |
| 反馈形式 | JSON-RPC error{code,message,data}（message=参考文案不比对）；通知→空串（HTTP 202）；成功帧 {jsonrpc,id,result} |
| 黄金向量指针 | `mcp_rejection_vectors.json` 17 case（op=handle/negotiate）；Kotlin 对拍=`:core:mcp` GoldenMcpRejectionTest（17/17 绿+变异探针） |
| 像素基线指针 | 无 UI（内核单元） |

## 平移注记（Kotlin 侧已落地形态）

- `McpProtocol.negotiate`：支持列表原样回显、否则最新 2025-06-18（neg_supported/unknown/null 三态向量）。
- 通知判定只看 `id == null`（method 类型不影响——handle_rules 契约）。
- 协议错误 id 回显：解析失败/非对象 id=null；缺 method id 回显（B3）。
- 领域拒绝 `McpRpcError(message, code, data)` 原样透传 error 三件；普通异常 -32603 兜底（message 含异常串，参考文案不比）。
- instructions 政策精华 = BUILTIN_INSTRUCTIONS 常量整段逐字（变异探针已实证逐字比对生效）；instructionsProvider 覆盖入口保留。
