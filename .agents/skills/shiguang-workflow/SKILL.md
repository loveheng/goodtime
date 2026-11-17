---
name: shiguang-workflow
description: 拾光 shiguang（Flutter Android 日程台账 + 内嵌 MCP 服务端）工作流事实源：构建/测试/运行命令唯一出处、环境硬约束、经验教训维护协议。构建报错、跑测试、换机器前先读，命令勿凭记忆写；工具链踩坑查 context/lessons.md，改前落点定位查 docs/project/index.md（shiguang-index skill）。
---

# shiguang · AI 开发工作流事实源

> 项目一句话：**拾光（shiguang）**——本地持有的日程台账 Android app + 内嵌 MCP 服务端（拓扑=手机当服务端：生产通道=Binder 宿主进程内嵌入 + HTTP 仅回环调试）。设计 SSOT = `docs/design/schedule-app.md`（§11 拍板台账留痕）。

## 定位边界（三件套分工，防双源）

| 需要什么 | 查哪里 |
|---|---|
| **写码规则**：分层依赖方向 / 新代码落点决策 / 红线正文与违例处置 | `docs/project/coding-standards.md`（§1 分层矩阵 + §2 落点决策 + §3 红线正文） |
| **改前定位**：某功能的代码落点 / 隐式契约登记 | `docs/project/index.md`（数据；登记规约 = shiguang-index skill） |
| **UI 规范与组件纪律**：视觉 tokens 取用 / 界面文案词汇表 / UI 架构红线 | `docs/project/ui-design.md`（大面规范，零外引）+ `docs/design/ui-pages.md`（页面布局稿）+ shiguang-ui skill（落地纪律） |
| **命令与环境**：本 skill | 构建/测试/运行/发布命令、环境硬约束（工具链踩坑数据查 `context/lessons.md`） |

模块清单**不在本表登记**——见 `docs/project/index.md`（代码落点表）。现行 epic 与过程态查 `context/CURRENT` / `context/epics/`，本 skill 不登记。

## 技术栈

- **纯原生 Android（P5 迁移完成后终态，2026-11-17）**：Kotlin + Jetpack Compose + Gradle-only（Flutter/Dart 已物理退役，仓库无 pubspec/lib）；目标平台：**仅 Android**（applicationId `app.shiguang.shiguang`；dogfood 隔离包 `.dogfood` 独立 SQLite 文件）
- 核心依赖：AndroidSqlStore（SQLite WAL 三表台账）、内嵌 MCP server（:core:mcp 原生内核）、goodhub-mcp-plugin-core（Binder 契约）、Compose 三层设计系统（:core:tokens → ShiguangTheme → Shiguang* 原语）

## 环境硬约束

- **构建一律 Gradle**：`cd android && ./gradlew <task>`；flutter CLI 链已退役（无 pub get/analyze）
- 本地优先：全功能离线可用；MCP 不对外暴露（HTTP 仅 127.0.0.1 回环 + adb reverse 调试，2026-10-15 拍板）；无任何遥测
- **Android 交付约束（2026-10-06 拍板）**：minSdk=34（仅支持 Android 14+）；compileSdk=37 / targetSdk=36（P5 固化于 android/app/build.gradle.kts，版本源=versionName/versionCode 行）
- 文档纪律：docs/ 改动走 docs-spec（lint 用 `toolbox run docs-lint`）
- 工具链实测踩坑的**数据**查 `context/lessons.md`，本 skill 不复制（防双源；维护协议见下节）

## 经验教训维护协议（context/lessons.md）

**数据住 `context/lessons.md`，协议住本 skill**（防双源）：本文件只定义什么时候记、按什么格式记、什么时候清零，不承载任何经验正文。

### 使用时机（三种，缺一即退化为重复排查）

| 时机 | 场景 | 动作 |
|---|---|---|
| **写前检索** | 动高风险模块前（MCP 传输 / UI 流程测试 / 构建打包 / R2 上传…） | 先查对应模块节，提取已知反模式，在写代码第一分钟避开暗坑 |
| **排障检索** | 构建 / 测试 / 真机联调报错时 | 取报错堆栈关键字（如 `requires … compile against version 34`）在 lessons.md 全字匹配，先读解法再动手，禁从零排查 |
| **升维销项** | 坑解决后 | 把解法挂上回归钉子进「回归」段——只写文档不挂钉，换人照踩 |

### 录入准则（三不记 + 录入门槛）

- **不记**临时网络/环境偶发抖动（连接超时、换个镜像站即好）——无复现价值
- **不记**情绪与破案流水账——人名、时间、排查历程一律抹除，只留技术因果
- **不记**无法落地的泛泛建议（"注意并发安全"、"多写单测"类）
- **录入门槛**：排查耗时 >15min 的确定性故障（机理清楚、可复现、有复现价值），**同轮沉淀**，不当旁观者

### 录入格式（四段式 · 单行落盘 · 显式标签）

新条目在 `context/lessons.md` 底部 `## 待归并（<模块>）` 区追加，**一段一行、四段显式标签**，禁止无标签长文本：

```
- [<模块>] 现象: <报错关键字/堆栈特征，确保 grep 精准命中> | 根因: <语言/运行时/平台机制本质> | 处置: <解法/命令/代码修改点> (Ref: <epic>)
```

- 四段 = **现象 / 根因 / 处置 / 回归**；Inbox 快速落盘允许缺「回归」，**归并准入时四段齐备**（门禁见下）
- 归并后移入对应 `## <模块>` 节并去掉 `[<模块>]` 前缀，补上回归段：`- 现象: ... | 根因: ... | 处置: ... | 回归: ... (Ref: <epic>)`
- 正文区行禁 `- [` 开头（该前缀专属底部追加区）；存量旧格式条目（`➔` 连接）被 touch 或归并时按新格式归一，不专门立项迁移

### 闭环清零纪律（Inbox → 正文）

- 新坑先落 Inbox（`## 待归并` 区），**禁直接插正文**——正文只收已审条目
- **Epic 收官 / 版本发版前必须清零 Inbox**：归入模块节、消除完全重复项、为已有回归钉子的条目精简篇幅
- **归并门禁**：缺「回归」段的条目，要么补回归钉子路径（如 `test/ui_*_test.dart` 某测试——撤修复即红；test/ 为平铺目录，UI 测试以 `ui_` 前缀命名），要么写明机械规则编号（arch-guard 规则号）或明确代码约束，否则不得进正文
- 归并后刷新文件 frontmatter 的 `total-merged` 与 `last-merge` 字段
- lessons.md 是**全局账**（frontmatter `epic: global`）：跨 epic 复用，只归并不清空

## 研发闭环命令（唯一出处，2026-11-17 P5 Gradle-only 改版实测跑通）

> 命令双入口：项目工具池 `toolbox run <工具>`（canonical，别名见注释）与裸脚本等价；无 toolbox 环境用裸脚本。

### 1. 提交前质检流水线（交付红线，按耗时从小到大）

任一步失败**禁止提交**；禁止 `--no-verify` 绕过门禁：

```bash
toolbox run arch-guard            # ag   # ① 架构硬约束护栏（P5 起为 arch-guard-kotlin 薄壳，8 条 Kotlin 规则：正文与违例处置 = docs/project/coding-standards.md §3）
cd android && ./gradlew test && cd ..   # ② 四模块+:app 全量测试 → 全绿为交付线（golden 向量对拍在 :core:mcp JVM 测试内）
```

- Kotlin 与 docs/ 混改时用聚合门禁按改动范围分派：`toolbox run pre-commit-gate`（pcg；.kt 改动→arch-guard-kotlin，docs/ 改动→docs-lint）；git 钩子已装自动拦截
- ① 命中红灯 = 按 coding-standards §3 违例处置修结构，**不得申请加白名单**（只减不增）

### 2. 本地运行与真机联调

```bash
adb devices                               # 查看在线真机 ID
toolbox run build-apk                     # apk  # APK 编译（Gradle-only：arch-guard-kotlin 门槛+产物校验+--install 真机装包；内置设备防降级守卫；--dogfood 切隔离试用包）
```

- 构建（APK）：一律走 `toolbox run build-apk`，勿裸跑 gradlew assemble*。默认 debug 包（prod flavor）；release 整包加 `--release`（P5 Gradle-only 无 ABI 分包）；内部试用隔离加 `--dogfood`

### 3. 版本与发布

```bash
toolbox run bump-version                 # bump # gradle 版本更新（versionName/versionCode）+设备防降级守卫（包名 app.shiguang.shiguang）
bash scripts/release-r2.sh               #      # 一键发布 R2 自更新源（assembleProdRelease+清单上传；-n 干跑）
```

### 4. 构建异常自救（缓存重置急救包）

```bash
cd android && ./gradlew --stop && ./gradlew clean && cd ..   # 深度构建缓存清理（--fresh 亦可用 build-apk 内建）
```
