---
status: active
updated: 2026-11-15
---

# 行为清单 · onboarding（首启引导 4 步，N8c 施工输入，2026-11-15 D23 过目转正）

> §9.1.6 模板产出（2026-11-15 N8c 前置抽取）。抽取源 = `lib/ui/onboarding_page.dart`（274 行全文）+ `docs/design/ui-pages.md` §2（首启引导 4 步）+ §9.2（框架与引导文案）+ `lib/data/settings.dart`（SettingsKeys）。**范围 = 引导四步全件（①作息边界/②一周节奏/③排程偏好/④关于你）+ 提交口**；闸门翻转归 app_gate 清单。

## 七字段

| 字段 | 内容 |
|---|---|
| 交互意图 | 首启 4 步播种：作息边界（排程硬校验的 UI 前置，必填不可跳）→一周节奏模板→排程数值偏好→身份画像与偏好（AI 排程地基）；完成即写 settings+fixed_slots，未完成无法进主框架 |
| 触发与阈值 | 步进=主按钮「下一步」×3 → 末步「开始使用」；跳过钮仅步②③（`step>0 && step<3`）；步①②③仅本地暂存不落库，④一次性提交；TimePicker/SegmentedButton/Slider 即改即存本地态 |
| 状态空间 | ui-pages §8 C 档 2 态（S1 内容展示/S2 空态）；基线实采 `onboarding base` 1 组（manifest）——S2 引导无空态语义，过目时确认按 1 组豁免或补采 |
| 落库命令与拒绝码 | ②非空非「自由职业」→ `update_fixed_slots`（整单批量替换，预设两段）；④→ `update_settings{wake_time/sleep_time/min_block_minutes/daily_new_blocks_limit (+user_rules/identity_prompt 非空才写)}`——identity_prompt/user_rules 走 human 通道不受 uiOnly 限制；拒绝=命令层键白名单/字段校验族（已平移） |
| 反馈形式 | 无 SnackBar/横幅——静默写入后 onDone 回调交闸门重查翻转主框架；步①预填使必填恒满足，规范「主按钮禁用/可用随①步联动」无实际分支（见 §D 过目点） |
| 黄金向量指针 | 无专集；update_settings 键白名单/uiOnly 越权族 + slots 语义随既有向量集覆盖（CommandHandler gate 已平移） |
| 像素基线指针 | `onboarding base`（test/golden/baseline/manifest.json，31 组内已采）；C 档登记 2 态 vs 实采 1 组待过目定豁免 |

## §A 步骤行为（UI 本地态 → 末步统一提交）

| 步 | 口径 |
|---|---|
| ① 作息边界 | 预填 wake=420（07:00）/sleep=1380（23:00）；双时间卡（ListTile title 起床/睡觉 + trailing clockOf 24pt bold）→ showTimePicker（TimeOfDay→分钟数直存）；标题「几点起床？几点睡觉？」22pt bold + 注「一切安排以此为界」textSecondary；**必填不可跳=无跳过钮** |
| ② 一周节奏 | 三预设单选卡（ListTile leading radio_button_checked/off）：上班族「工作日白天在班上」/学生「工作日有课」/自由职业「没有固定安排是完全正常的」；主按钮推进时非空且非「自由职业」→ update_fixed_slots：上班族=[上班 一~五 09:00-12:00, 上班（下午） 一~五 14:00-18:00]，学生=[上课 一~五 08:00-12:00, 上课（下午） 一~五 14:00-17:00]；自由职业=空表合法直接过（不写 slots） |
| ③ 排程偏好 | 「最小块粒度」SegmentedButton 15/30/60 分钟（预填 30）；「单日新增排量上限：N 项」Slider min 3/max 12/divisions 9/label（预填 8）；注「上限是休息权的模型支撑，不是效率指标」textSecondary；仅本地暂存 |
| ④ 关于你 | 「关于你」22pt + 注「这两项是 AI 排程的地基，建议填一下；可稍后在「设置」页随时修改」；两多行域（maxLines 4 + OutlineInputBorder + alignLabelWithHint + 示例占位长文案）：身份画像（系统提示词）/我的偏好（自然语言）；内容包 SingleChildScrollView（小屏/键盘挤压不溢出——测试视口 800×600 即溢出，回归钉子所在）；可跳但推荐 |
| 提交 | update_settings 一次性：wake_time/sleep_time/min_block_minutes/daily_new_blocks_limit 恒写；user_rules/identity_prompt trim 后非空才写（空值不写）；随后 onDone() → 闸门重查 settings.initialized |

## §B 结构与文案

- Scaffold + SafeArea + padding 24 竖排：「拾光」headlineMedium → 「第 N 步，共 4 步」bodySmall → 步骤体 Expanded → 主按钮 FilledButton（「下一步」/末步「开始使用」）→ 跳过 TextButton（仅步②③）。
- 文案串逐字 = ui-pages §9.2 表（引导①~④行）+ ③注「上限是休息权的模型支撑，不是效率指标」+ ④注「这两项是 AI 排程的地基…」。

## §C 架构接线（N8c 预案，过目后定）

- OnboardingViewModel 或页内 remember 状态：step/预填值/两文本域；命令执行走 AppContainer 注入 handler（D15 同形：无独立数据流监听——引导页一次性写入，无需 revision collect）。
- 提交成功事件 → 通知闸门（单 Activity 下 gate 以 settings.initialized StateFlow 驱动，onDone 回调可省——闸门清单 §A 已含 revision 驱动翻转）。

## §D 过目点（规范 vs 实现差异，须人工拍板）

1. **①步按钮联动**：ui-pages §2「主按钮态（禁用/可用）随第①步填写联动」——实现侧预填 420/1380 使①步恒满足、按钮恒可用，无禁用分支。规范照实改（预填即必填满足）还是实现补禁用态？**默认建议=规范改注「预填即满足，按钮恒可用」**（保持行为不变，消规范幻影）。
2. **步③暂存口**：min_block/daily_limit 不在第③步落库，随④一次性提交——中途杀进程（丢失点 #9）③步选择全丢。**默认建议=照抄**（引导是单次流程，回滚成本可接受；不上 SavedStateHandle）。
3. **无回退步**：实现只有前进/跳过，无上一步——ui-pages §2 未提及回退，照抄不补。
