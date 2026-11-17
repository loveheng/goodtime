---
status: active
updated: 2026-11-15
---

# 行为清单 · 后台服务规格 + 管家日切（P3②，待人工过目）

> §9.1.6 模板产出（2026-11-15 晨间补走抽取，抽取源=lib/service/{foreground_task_init,housekeeper}.dart + lib/action/{queries,rules}.dart + mcp_controller.dart enable 段；§9 后台服务规格/时钟基建行）。
> 两件套：**Housekeeper 域件**（→ :core:action，纯 JVM 可测）+ **FGS 平台壳**（→ :app）。日切调度现状缺口（仅冷启动触发）由 TimeProvider ticks + 精确闹钟补齐（设计既定，非新增需求）。

## 函数注册表（域件 3 方法 + 常量 3）

| 方法 | 行为 | 依赖 | 复杂度 |
|---|---|---|---|
| `dailyCut(now?)` | 日切扫描（幂等，wake_time 切割 §11）：wake=`intOf(wake_time)??420`；today=scheduleDayOf(now,wake)；stale=blocksInRange(isoDate(today−60), isoDate(today−1))（回扫 60 天，设备数日未开机也能补齐）；protected=isBreakdown(now)；逐块三分派（见下） | blocksInRange/patchBlock/deleteBlock + ScheduleQueries.isBreakdown | 中 |
| `dailyCutIfNeeded(now?)` | 每作息日至多一次：`settings['last_daily_cut']==isoDate(today)` → 跳过；否则 dailyCut + settingsSet({'last_daily_cut': today}) | settingsAll/settingsSet | 低 |
| `isBreakdown(now?)`（ScheduleQueries 第 6 件） | Clean Slate 判定：history=blocksInRange(today−30, today)；missed 计数 ≥ `breakdownMissedTotal` → true；lastActive=max(done\|confirmed 块 date)（字典序）；无 active → false；`today−lastActive ≥ breakdownDays` → true | blocksInRange + rules 常量 | 低 |

**dailyCut 三分派**（逐语义）：
- `confirmed` 块：celebration **或** protected（Clean Slate 保护期）→ `archived`（中性淡出，不计入复盘聚合——「破罐破摔」防线，绝不显红）；否则 → `missed`（可补勾宽容视窗，补记走 tick_block）
- `proposed` 块：celebration → `archived`；否则 → **物理删除**（提案从未成为现实，AI 可整单重提；melted 仅物理位移触发、archived 属断流保护，均不适用）
- 顺延记账（postpone_count）在 shift_block 命令内完成，此处不重复
- 返回 `(missed, voided, archived, protected)` 计数体

**rules 常量补齐**（Kotlin ScheduleRules 现缺三条）：`DAILY_CUT_LOOKBACK_DAYS=60` / `BREAKDOWN_DAYS=3` / `BREAKDOWN_MISSED_TOTAL=5`。⚠ 既有 wake 缺省差逐字保留：dailyCut 用 `??420`，isBreakdown 用 `??0`（Dart 原样，不归一）。

## FGS 平台壳（:app ShiguangForegroundService）

| 字段 | 内容 |
|---|---|
| 服务形态 | **specialUse** FGS（Android 15 dataSync 6h/24h 配额规避——§9 拍板；现 Dart dataSync 属过渡期形态） |
| 权限族 | FOREGROUND_SERVICE + FOREGROUND_SERVICE_SPECIAL_USE + POST_NOTIFICATIONS（manifest 已有）+ SCHEDULE_EXACT_ALARM（Android 13+ 用户授权）+ REQUEST_IGNORE_BATTERY_OPTIMIZATIONS（电池白名单引导=设置页引导，P4 接线） |
| 通知通道 | id=`shiguang_fg` / 名=拾光后台任务 / LOW importance + LOW priority（逐字）；运行态通知 title=「拾光 · MCP 服务运行中」text=「仅本机回环（内部调试）；桌面先 adb reverse tcp:<port> tcp:<port>」 |
| 锁纪律 | CPU 锁（PARTIAL_WAKE_LOCK）+ Wi-Fi 锁（WifiLock FULL_HIGH_PERF）**服务存续期全程持有**——Doze 应答红线（拾贝实测：无锁即假死超时，MCP accept 线程被挂起/Wi-Fi 休眠断链） |
| 日切驱动 | 过夜日切 = AlarmManager `setExactAndAllowWhileIdle` 于**下一作息日 wake 边界**（触发→dailyCutIfNeeded→排下一日）+ TimeProvider ticks（TIME_CHANGED/TIMEZONE_CHANGED 重排闹钟）；冷启动 dailyCutIfNeeded 保留——幂等闸三重防护 |
| 生命周期 | autoRunOnBoot=false（对齐现态）；启动入口=MCP enable（P4 设置页开关接线）；POST_NOTIFICATIONS 运行时申请随 P4 Activity（未授权=通知不可见但服务仍运行） |
| 服务职责 | **承载件**：锁+闹钟+通知，业务零逻辑——日切调容器内 Housekeeper；MCP 应答由 binder 通道自带生命周期 |

## 平移注记（待实现要点）

- Housekeeper/isBreakdown 落 `:core:action`（repo=BridgeRepositoryPort 同端口）；JVM 单测=内存库替身：dailyCut 三分派/保护期/celebration/幂等闸 + isBreakdown 边界（missed=5 阈值、lastActive=3 天阈值、无 active、celebration 提案）。
- morningDigest（queries.dart 614，含 clean_slate 字段）**不在本轮**——全仓零消费面（MCP 工具面不暴露），挂 P4 复核（UI 化时再抽）。
- Kotlin 常量三条补进 FixedSlot.kt 的 object ScheduleRules（既有落点）。
- 精确闹钟重排触发面：dailyCut 执行后 + TIME_CHANGED/TIMEZONE_CHANGED tick；wake_time 设置变更重排（P4 设置页接线时挂）。
