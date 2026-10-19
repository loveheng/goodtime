---
name: shiguang-ui
description: 拾光 shiguang UI 开发与规范落点：视觉令牌（tokens.dart）取用纪律、界面文案术语映射前置、UI 架构红线指针、验收顺序、用户新设计提案的规范更新程序（ui-design/ui-pages）。改页面、加组件、上屏文案、用户提出新设计/新视觉/新交互时必读。
---

# shiguang-ui · UI 规范与开发纪律

> **规范正文 SSOT**：[`docs/project/ui-design.md`](../../../docs/project/ui-design.md)（零外引大面规范：色彩/排印 tokens、布局标尺与物理约束、文案术语映射、BlockRenderer 形态契约）；页面布局稿 = [`docs/design/ui-pages.md`](../../../docs/design/ui-pages.md)（IA、各页面编排与各页文案清单，单向指入 ui-design）。本 Skill 负责**代码落地纪律、规范更新程序（用户新设计提案 → 修订规范）与验收闭环**，不复制规范正文；命令唯一出处 = shiguang-workflow skill、红线正文 = coding-standards §3——均只留指针，防双源。

## 1. 查阅指引（按改动范围定向查阅）

| 准备修改的内容 | 规范正文查阅位置 | 核心实现/落点 |
|---|---|---|
| 色彩语义（形态/状态色、深色面板、用色纪律） | `ui-design.md` §1 | `lib/theme/tokens.dart`（色彩 token） |
| 排印（M3 尺度档 / 「马上开始」最大字 / 圆角） | `ui-design.md` §2 | 同上 |
| 布局标尺与物理约束（1dp/min、44dp 钳制、Z 序、动效、手势锁） | `ui-design.md` §3 | 同上（duration/radius/gesture token）+ 组件内 EdgeInsets |
| 文案（词汇表 / 中性语言三准则） | `ui-design.md` §4 | 各页字符串；词汇表 = `ui-design.md` §4 |
| 页面编排（IA 导航 / 引导 / 今日页纵向 / 周月 / 清单 / 块浮层 / 空态） | `docs/design/ui-pages.md` §1–§8（布局稿，指入 ui-design） | `lib/ui/app_shell.dart` 等（逐一落点查 `docs/project/index.md`） |
| BlockRenderer 矩阵（形态×状态×来源×手势，组件形态契约） | `ui-design.md` §5 | `lib/ui/block_sheet.dart` 等 |

## 2. 编码纪律与高压红线

### A. 零魔法值（本 skill 专属执行细则）

- **tokens 取用唯一范式**：色彩/尺度/动效/手势常量一律取 `lib/theme/tokens.dart`（ui-design §1–§3 落地件），严禁裸写 `Color(0xFF...)`、内联数值动效时长/手势阈值。
- **主题态**：三档外观（跟随系统/浅/深，默认跟随系统）；深色经 ui-design §1.2 深色映射面板整体切换，禁运行时任意新配色；`theme_mode` 为 uiOnly 键，AI 触碰即拒。

### B. 架构红线（指针——规则正文与违例处置 = `docs/project/coding-standards.md` §1/§3，本 skill 不复制）

- **无头系统原则**：UI 是纯呈现层，写操作经 `lib/action/command_handler.dart` 唯一写入口（R1/R5）、序列化下沉命令/数据层（R2）、平台差异收敛到 service 接口（R3）、UI 允许出向查 coding-standards §1 分层矩阵。
- 机械闸 = `toolbox run arch-guard`（R1–R6），**红灯按 coding-standards §3 违例处置修结构，不申请白名单**（只减不增）；禁弃用 `textScaleFactor`（R6）。

### C. 文案与组件登记（指针——登记规约在 shiguang-index skill）

- **文案先入表再上屏**：新**概念词**先入 `ui-design.md` §4 术语映射，页面级**具体文案串**先入 `ui-pages.md` §9 对应组（零黑话、中性语言），再写进字符串——映射/清单 ↔ 界面字符串为跨域隐式契约（见 `docs/project/index.md`）。
- 新增页面/组件**同轮**登记 `docs/project/index.md` 代码落点表；表结构与登记纪律见 shiguang-index skill，不在本 skill 重复。

## 3. UI 验收闭环（顺序要求；命令原文唯一出处 = shiguang-workflow skill）

改 UI 后按序自证，**命令原文以 workflow §1 质检流水线为准**，本 skill 只锁顺序与口径：

1. **架构护栏**：`arch-guard`（ag）秒过——红灯即修结构，不加白名单。
2. **静态与测试**：`flutter analyze` 0 issue + 相关 `test/ui_*` 流程测试全绿（UI 测试 FakeAsync 铁律等坑先查 `context/lessons.md` [UI测试] 节，禁从零排查）。
3. **视觉验证**：真机侧载走查（命令与装机纪律查 workflow §2）。

## 4. 维护与扩展纪律（程序归本 skill，规范正文不自带管理程序）

`ui-design.md` 是**零外引即成规范**（现行规则的终态陈述），自身不承载维护/扩展程序——一切「怎么扩、冲突听谁的」在本节执行，原则 **规范先行**：

| 场景 | 程序 |
|---|---|
| 新增界面 / 组件 | 页面编排先归入 `ui-pages.md` 既有页面模式；组件形态/词汇先归入 `ui-design.md` 既有契约（§5 矩阵 / §4 术语映射）；无先例**先扩对应文档再实现** |
| 新增文案 | 概念词先入 ui-design §4 术语映射、页面串先入 ui-pages §9 对应组再上屏；禁跳表直写字符串 |
| 新增色彩 / 尺度 / 动效档位 | 落 `lib/theme/tokens.dart` 既有阶梯；阶梯外先扩 ui-design §1–§3 表（并同步 token）再使用 |
| 规范与实现冲突 | 以 ui-design / ui-pages 为准改实现；确需突破先修订规范——禁实现单方面领先规范 |
| 机制级设计变更 | 回 `docs/design/schedule-app.md` §11 拍板台账留痕，拍板后同轮把 UI 侧终态改写进 ui-design（规范）/ ui-pages（页面编排），二者均不引入新机制 |

## 5. 规范更新程序（用户新设计提案驱动）

用户提出**新设计/新视觉/新交互/新文案**时，走本程序：**先改规范再写码**（规范先行，规范由用户拍板）——

1. **分诊**（判定提案性质，决定入口）：
   - 涉新机制 / 数据模型 / 命令层 / AI 行为 → **机制级**：先回 schedule-app §11 拍板台账留痕，拍板后按 §4 程序表把 UI 侧终态落进对应文档，再实现。
   - 纯 UI 层（色彩/尺度/动效/手势/文案/页面编排/组件形态）→ **UI 级**：直接按 §4 程序表修订文档（用户在本轮对话中拍板即算数）。
2. **修订文档**（按变更类型对号入座，同 §4 程序表）：token 档位 → ui-design §1–§3（并**同轮同步 `lib/theme/tokens.dart`**）；新概念词 → ui-design §4；组件形态/手势语义 → ui-design §5；页面编排/页面串 → ui-pages 对应节。
3. **收尾三件**：① 修订文档 frontmatter `updated` 刷新；② `toolbox run docs-lint` 过；③ 涉代码的再按 §3 验收闭环写码自证。

> 判定「是不是规范级」的速查：改完后**别的界面会不会也要跟着这样**？会 → ui-design（大面规范）；只影响某一页怎么摆 → ui-pages（页面稿）。拿不准时按 ui-design 处理（约束力更强，错杀成本低于漏管）。
