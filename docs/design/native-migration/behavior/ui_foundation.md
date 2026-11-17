---
status: active
updated: 2026-11-15
---

# 行为清单 · ShiguangTheme + 原语组件族（P4①-b/c，已过目转正 2026-11-15）

> §9.1.6 模板产出（2026-11-15 抽取）。抽取源 = `docs/project/ui-design.md` §2/§3.2/§3.3 + native-migration §9 丢失点 #1/#5/#6/#7/#8/#9 + `lib/main.dart` 主题装配段（诊断草稿，seed 值已提 design_tokens.md F4）+ ui-pages §8.4 D 档清单。生成通道施工只吃本清单 + 规范文档，禁读 Flutter 组件实现体。

## 基建前提（施工轮同批件）

| 件 | 内容 |
|---|---|
| Compose 启用 | `:app/build.gradle.kts` 现无 compose 配置——补 Kotlin Compose compiler plugin + BOM + `composeCompiler { reportsDestination }`（P4 稳定性基建 todo 既定） |
| 新依赖报备 | androidx.compose BOM / navigation-compose / kotlinx-serialization（路由）/ kotlinx-collections-immutable（稳定性 todo）——R6 构建链影响评估随施工轮一并报备 |
| Konsist 四条 | UI 包不依赖 data·storage·network ／ ViewModel 对外只暴露 StateFlow ／ Composable 不直调 Repository·Dao ／ 仓库层不 import Compose——P4 起生效，地基轮首件 UI 代码落地时装上（todo 既定） |

## ShiguangTheme（:app 主题包裹器）

| 字段 | 内容 |
|---|---|
| 交互意图 | 三档外观锁定 + 令牌绑定入口：`:core:tokens` 双面板绑进 CompositionLocal，业务只读 |
| 外观三档 | 跟随系统/浅色/深色，默认跟随系统；`theme_mode` 为 **uiOnly 设置键**（AI 经 update_settings 触碰即拒——Kotlin 分键校验既有语义对齐） |
| ColorScheme | M3 `fromSeed(seed=#283593, brightness)`（F4 过目转正）——**实现过渡态 2026-11-15**：material3 1.4.0 稳定版实查无 fromSeed，现以 light/darkColorScheme + primary/onPrimary 直取语义 deepFocus 对承载，material3 稳定版提供 fromSeed 后切换（终态口径=ui-design §3.1 不变） |
| 语义面板 | `LocalStColors`（staticCompositionLocalOf&lt;StColorsPalette&gt;）——面板不可变，外观切换=换实例；禁 mutable 面板泄漏进组合 |
| 排版基线 | 系统默认字体 + M3 尺度档；全库 `PlatformTextStyle(includeFontPadding=false)`（丢失点 #7，与 ui-design §2 排印 token 一并验收）；「马上开始」titleLarge = 全 app 最大字 |

## 原语组件四件（丢失点一次性内建修复——新页面自动继承合规）

| 组件 | 内建行为（逐条） |
|---|---|
| `ShiguangScaffold` | ① edge-to-edge + systemBars 安全区视觉避让；② SnackbarHost **任意时长自建**（丢失点 #1：1s/2s/3s/8s 四档——Compose 原生仅 4s/10s/Indefinite，改写为原生档属节奏变更须产品确认，禁默认归并）；③ FAB 滚动净空 `fabClearanceDp=88` 暴露给滚动体（末尾内容可滚出 FAB 覆盖区） |
| `ShiguangButton` | ① 48dp 最小触控区显式（`minimumInteractiveComponentSize`，丢失点 #5）；② 圆角/间距/排印全取 token（胶囊 999 语义禁他用） |
| `ShiguangCard` | ① radius 16；② margin 显式（Flutter Card 默认 margin 4 对照，禁隐式依赖）；③ inset 阶梯取 token（F2 过目后） |
| `ShiguangBottomSheet` | ① `sheetMaxWidth` 显式放开（Compose 默认 640dp 与移动端全宽不符，丢失点 #6）；② 含输入 sheet 统一 `Modifier.imePadding()` + `WindowInsets.ime` 联动（快记抽屉键盘吸附=Z40 语义）；③ 浮层 Z 序（ui-design §3.2：sheet Z30 / 快记抽屉 Z40；分级确认卡=Column 内联件不进 Z 竞争）；④ **逐层返回拦截**：PredictiveBackHandler/BackHandler 压栈消费禁透传页面级返回 + 左右边缘 24dp 系统手势保留区（丢失点 #8） |

## 七字段核（§9.1.6）

| 字段 | 内容 |
|---|---|
| 交互意图 | 地基组件层：让后续每一页**自动继承**安全区/触控/IME/返回/Z 序合规，不靠人记得 |
| 触发与阈值 | 无手势阈值（手势族属 BlockRenderer/主干页轮）；度量值全取 token（design_tokens.md） |
| 状态空间 | D 档组件外壳 5 件 × 2 态（app_shell / app_gate / action_pass_bar / quick_note_fab / reject_panel；ui-pages §8.4 S1 内容态 / S2 空态·权限态） |
| 落库命令与拒绝码 | 不适用（呈现层零命令面触达；无头系统原则——写操作经命令面既有件） |
| 反馈形式 | SnackbarHost 任意时长接口形状（四档 1/2/3/8s）由 Scaffold 内建；文案串一律引 ui-pages §9（随消费页轮落） |
| 黄金向量指针 | 不适用；丢失点 #8 的 35%/45% 不被系统抢夺 = P4 验收走查三项之一（todo 既定），非向量 |
| 像素基线指针 | D 档 10 组已入存量 31 组（`test/golden/baseline/manifest.json`）；B 档 36 组 relay 资源挂账（todos P0 像素基线签存档行） |

## 平移注记

- Scaffold/原语组件只内建**结构与度量**，零业务逻辑——页面内容经参数槽注入（State Hoisting）。
- 快记 FAB 键盘吸附展开抽屉属 quick_note_fab 单元（随主干页轮），本件只锁 Z40 语义与 imePadding 接口形状。
- app_gate 的 revision 闸门副作用改写（丢失点 #4：build 内副作用 → LaunchedEffect/remember/derivedStateOf）随主干页轮落地，本件只提供组件底座。
