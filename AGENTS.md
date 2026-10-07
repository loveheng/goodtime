# shiguang · AI 开发约定

拾光（shiguang）：本地持有的日程台账 + MCP 服务端 + 确认 UI 的 Flutter Android app。设计 SSOT：`docs/design/schedule-app.md`。

## 会话初始化（新窗口开场执行一次）

- 开场含「继续/开工/初始化」或首次指令时：先跑 `toolbox run-hooks bootstrap --quiet`（非 0 仅附一行 ⚠，不阻塞；无 toolbox 跳过）→ 跑 `toolbox run panel`（无 toolbox 则读 context/CURRENT → 绑定 epic 的 memory.md 断点）→ 输出会话绑定卡（域/类型/挂载/任务全景/状态）→ 按断点开工或等指令；多任务切换 `::board`（绑定纪律与恢复规约：~/.agents/skills/dev-loop/SKILL.md §3，存在时以其为准）
- CURRENT 缺失/none → 列 context/epics/ 候选请用户选，严禁自选开工；无 context/ → 提示走 dev-init 接入；命令速查 ~/.agents/COMMANDS.md

## 项目硬约束（详情见事实源 skill）

- flutter 不在默认 PATH：先 `export PATH="$HOME/flutter/bin:$PATH"`
- 设计变更回 `docs/design/schedule-app.md` §11 拍板台账留痕；界面文案先入 ui-spec §0.4 词汇表再上屏
- docs/ 改动跑 `toolbox run docs-lint`

## 项目 skill

- `shiguang-workflow`：环境/命令事实源（开工先加载）
- `shiguang-index`：功能 → 代码/文档落点归属索引
- `shiguang-docs`：docs/ 域表与项目数据
