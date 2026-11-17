---
status: active
updated: 2026-11-15
---

# 行为清单 · 设计令牌层 `:core:tokens`（P4①-a，已过目转正 2026-11-15）

> §9.1.6 模板产出（2026-11-15 抽取）。抽取源 = `lib/theme/tokens.dart`（155 行，ui-design §1.1 拍板的**唯一出处**——本件属规范资产直译而非 Flutter 实现体行为抽取）+ `docs/project/ui-design.md` §1–§3 逐值对账。
> 前提纪律：G2 已过、Dart 侧绝对只读——`tokens.dart` 本体不再改；对账补缺一律落 Kotlin 侧 + ui-design 修订（shiguang-ui §4 程序表）。
> **夜间档执行注记（2026-11-15 ::auto night）**：F1–F4 已按默认建议写回 ui-design（留痕晨间复核）；`:core:tokens` 模块已落盘（StTokens.kt 双面板 immutable + 58 值直译 + archivedFadeAlpha，StTokensValueTest 6/6 绿 + arch-guard-kotlin 8 条过）——本清单过目范围收窄为结构/映射形状，晨间复核。

## 落点与模块形状

| 项 | 定案（建议） |
|---|---|
| 模块 | `:core:tokens` **新建**（settings.gradle.kts 注册；pure JVM 零依赖——P2 三层模块图既定） |
| StColors 表达 | **双面板 immutable**：`StColorsPalette` data class（21 字段）+ `LIGHT`/`DARK` 两个常量实例；激活面板经 CompositionLocal 提供（设计稿「ShiguangTheme 把令牌绑进 CompositionLocal」既定）——Flutter mutable static `applyBrightness` 是单窗口取舍，Kotlin 侧不复制 mutable 方案 |
| StScale / StGesture | `object` 纯数值常量逐字翻译 |
| StMotion | 数值常量（时长/弹簧/曲线控制点）住 `:core:tokens`；`CubicBezierEasing`/`Spring` 实例在 :app 装配层组装（`:core:tokens` 不依赖 compose，保 pure JVM） |
| 值纪律 | 逐字直译**严禁现推**；下节对账发现过目后才可动规范 |

## 逐字翻译清单（4 对象 58 值）

### StColors（21 字段，浅色基线 / 深色映射）

| 字段 | 浅色 | 深色 | 语义 |
|---|---|---|---|
| deepFocusBg | #283593 | 同值 | 深度攻坚块底 |
| deepFocusText | #FFFFFF | 同值 | 深度攻坚块文字 |
| sparkStroke | #FFB300 | 同值 | 火种胶囊描边 |
| sparkBg | #FFF8E1 | #332C10 | 火种胶囊底 |
| roamBg | E3F2FD·12% | 90CAF9·12% | 漫游块底 |
| roamStroke | 90CAF9·40% | 同值 | 漫游块边框 |
| celebrationBg | #FFF3D6 | #3B3323 | 庆祝勋章底（犒劳时刻专属） |
| celebrationStroke | #D4AF37 | 同值 | 庆祝金描边 |
| voucherBg | #FFECB3 | #3A2F12 | 凭证通关卡 Hero 面（色系近火种，非庆祝金） |
| supplyBandBg | #E8F5E9 | #1E3324 | 补给带底（留白缓冲） |
| supplyBandStroke | #81C784 | 同值 | 补给带虚线描边 |
| freeFlowBg | #F1F8E9 | #1F2E1B | 自由流动区底 |
| humanBlockBg | 78909C·40% | 78909C·33% | human 块底 |
| overlapHint | #FF9800 | 同值 | 重叠橙线提示（对账发现 F3） |
| fixedSlotBg | #ECEFF1 | #26292B | 固定安排背景带 |
| projectionBg | #F5F5F5 | #2A2D2F | 系统日历投影块 |
| missedAccent | #FFF3E0 | #3A2F1B | missed 中性左标（绝不红） |
| safelineOff | #BDBDBD | #616161 | 安全线 outline |
| safelineOn | #D4AF37 | 同值 | 安全线填充 |
| textPrimary | #1A1C1E | #E3E2E0 | 正文（中性灰非纯黑） |
| textSecondary | #606468 | #9CA0A5 | 次要文字 |

双主题同值 8 项与 ui-design §1.2 登记一致；换值 13 项逐值核对通过（含 humanBlockBg 40%→33% 对应 0x66→0x54）。

### StScale（11）/ StMotion（17）/ StGesture（9）

| 对象 | 值（逐字） |
|---|---|
| StScale | dpPerMinute=1.0 ｜ blockMinHeightDp=44 ｜ radiusBlock=12 ｜ radiusCard=16 ｜ radiusCapsule=999 ｜ insetXs=4 / insetSm=8 / insetMd=12 / insetLg=16 / insetXl=20 ｜ fabClearanceDp=88 |
| StMotion | collapseMs=300 ｜ meltMs=400 ｜ safelineMs=250（名义感知窗口径，非动画时长参数）｜ springDampingRatio=0.75 ｜ springStiffness=400 ｜ easeOut=Cubic(0,0,0.58,1) ｜ easeInOut=Cubic(0.42,0,0.58,1)（**不是** FastOutSlowIn）｜ fastOutSlowIn=Cubic(0.4,0,0.2,1) |
| StGesture | horizontalSlopDp=20 ｜ axisRatioMin=2.0 ｜ meltFraction=0.45 ｜ swapFraction=0.35 ｜ longPressMs=400 ｜ longPressScale=0.98 ｜ pageSwipeDp=60 ｜ bandFullMin=30 ｜ bandIconMin=15 |

## 对账发现（4 条，已按夜间默认写回 ui-design（2026-11-15 ::auto night），晨间复核）

| # | 发现 | 现状 | 建议 |
|---|---|---|---|
| F1 | `archivedFade` 15% 淡出无 token | ui-design §1.1 有行，tokens.dart 无对应常量；实现裸写 `schedule_page.dart:1377` `opacity: isArchived ? 0.15 : 1` | Kotlin 侧补 `archivedFadeAlpha = 0.15f`（归 StColors 族）；ui-design §1.1 行注记 token 落点 |
| F2 | inset 阶梯未入规范 | tokens.dart 有 4/8/12/16/20（拾贝 Insets 惯例），ui-design §2 无登记 | ui-design §2 补行「间距阶梯 XS4/SM8/MD12/LG16/XL20」 |
| F3 | `overlapHint` 未入 §1.1 表 | tokens.dart 有（#FF9800 双主题同值），ui-design §1.1 表无行（仅 §3.4 语义「亮橙色 1dp 细线」） | ui-design §1.1 补行 |
| F4 | M3 seed 值未入规范 | `lib/main.dart:122` `ColorScheme.fromSeed(seedColor: StColors.deepFocusBg)`（#283593，浅/深同 seed 换 brightness）；ui-design §3.1 只写 fromSeed 未写 seed 值 | ui-design §3.1 拍板行补 seed=#283593（诊断草稿值，过目转正） |

## 七字段核（§9.1.6）

| 字段 | 内容 |
|---|---|
| 交互意图 | 无交互——纯数值常量层，为一切 UI 提供取值唯一源（零魔法值执行底座） |
| 触发与阈值 | StGesture 9 值逐字（见上表）；数值类字段禁现推 |
| 状态空间 | 不适用（无页面态）；浅/深双面板切换由 ShiguangTheme 承载（见 ui_foundation.md） |
| 落库命令与拒绝码 | 不适用（零命令面触达） |
| 反馈形式 | 不适用 |
| 黄金向量指针 | 不适用（纯数值层）；对拍 = 施工轮附 Kotlin↔tokens.dart 逐值 diff 断言测试 |
| 像素基线指针 | 不适用（间接经 D 档组件外壳呈现，指针见 ui_foundation.md） |
