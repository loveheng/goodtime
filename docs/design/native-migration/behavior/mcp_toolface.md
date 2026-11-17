---
status: active
updated: 2026-11-14
---

# 行为清单 · mcp_toolface（工具面：14 工具 + 领域拒绝族）

> §9.1.6 模板产出；**P2 已平移（2026-11-14）**：`:core:mcp` NativeToolFace（call 分发 14 工具，
> 依赖 `:core:action` 命令层/读面）——toolface 向量 23 case Kotlin 对拍全绿（GoldenToolfaceTest）。
> D8 分叉 ②（提前 P2 原生）落定：call 原生可切，schemas 暂沿桥（描述文案未平移）。
> 行为语义=mcp_tools_test/mcp_facts_tools_test 钉样 + toolface 向量 23 case。

| 字段 | 内容 |
|---|---|
| 交互意图 | AI 经 tools/call 消费的全部业务能力：计划 CRUD / 设置与作息 / 排程提案与调整 / 读面聚合 / 建言通道 / 背景双工具 / 硬事实通道 |
| 触发与阈值 | days 钳制 ≤3（get_schedule）/ 历史 14 天窗口 / 背景全局预算 8 条（终态双指标）/ suggest 仅用户自设键五枚 / 乐观锁 version CAS |
| 状态空间 | **14 工具注册 tripwire**（list_plans/add_plan/update_plan/get_settings/update_settings/update_fixed_slots/propose_schedule/adjust_blocks/get_schedule/get_history/suggest_user_setting/upsert_background/merge_backgrounds/upsert_facts——增减必须过 mcp_tools_test 显式）；领域拒绝族：version_conflict（附 latest）/ schedule_rejected（附明细+available_free_windows）/ invalid_request / forbidden（pinned 和平条款）/ budget_exceeded（附全量现场）/ Unknown tool |
| 落库命令与拒绝码 | 全部写经 ScheduleCommand → CommandHandler（唯一写入口）；领域码 `ActionErrorCode` 枚举逐字（invalid_request/version_conflict/forbidden/budget_exceeded/schedule_rejected/settings_missing…）；**领域拒绝统一 ActionException → McpRpcError(-32602, message, {code, hint?, ...data})**，HTTP 层仍 200 |
| 反馈形式 | 成功 = {content:[{type:text,text:<json>}], isError:false}；content JSON 键集随工具（向量 tool_call 值泛化结构对拍） |
| 黄金向量指针 | `mcp_toolface_vectors.json` 23 case（tool_call 15 值泛化 + tool_reject 8 领域码值级）；Kotlin 执行器 = GoldenToolfaceTest（:core:mcp，2026-11-14 全绿） |
| 像素基线指针 | 无直接 UI（业务面；其渲染面在各页基线） |

## 平移注记（分叉点，2026-11-14 落定）

- **已平移**：`NativeToolFace`（:core:mcp，api 依赖 :core:action）——call 分发 14 工具走 CommandHandler.gate+dispatch 统一入口（AI actor），读面走 ScheduleQueries+搭载装配；建议键直写 app_settings（ai_setting_suggestions）。ActionException → McpRpcError(-32602, {code, hint?, ...data})。
- **切换点**：:app `McpServer(toolFace = dartBridge)` 换 NativeToolFace（repo=NativeSqlRepository）——goodhub 联调轮执行；schemas 暂沿桥（描述文案未平移，不属契约语义）。
- **本轮补齐的原缺口**：背景族预算双指标（upsert/merge 终态校验）、update_settings 分键校验+null 清键、update_fixed_slots 三检、CommandResult.toJson、adjust_blocks 四动作原生（add/move/resize/remove）——均由 toolface 向量钉样护航。
- 值泛化对拍规则（rules.generalize_rule）与 `$ref` 种子解引用（rules.ref_rule）见向量集。
