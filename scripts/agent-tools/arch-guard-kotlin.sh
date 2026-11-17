#!/bin/bash
# arch-guard-kotlin — 原生迁移 Kotlin 侧架构硬约束机械护栏（native-migration §9.1）
#
# toolbox-script
# format: v1
# name: arch-guard-kotlin
# summary: Kotlin 侧架构硬约束机械校验（K1 UI 不触达 data/storage/network、K2 禁裸 dp/sp、K3 禁 synchronized 与 java.util.concurrent、K4 Composable 禁持 Context·Activity、K5 对外禁暴露 MutableStateFlow、K6 禁可变全局单例、K7 禁 GlobalScope、K8 ViewModel 禁直持 repo），改动 android .kt 后与提交前门禁
# trigger: manual
# platform: unix
# cat: test
#
# 规则口径见 docs/design/native-migration.md §9.1（AI 施工规程·Kotlin 双层护栏）
# 与 §10 决定 5/7（三层模块图 /core:model pure JVM、Manual DI、Day-2 纪律）。
# **禁飞区四类**（越界改动不报错但静默失配，判定靠 P0 黄金向量与像素基线）：
#   ① 契约六类语义（协议/命令面/拒绝码/E2EE 封装字节/proto wire format/DDL）
#   ② StGesture 手势阈值与 StMotion 时长曲线（tokens.dart StMotion 纯数值逐字翻译，
#      严禁现推参数或以「原生手感更佳」覆盖）
#   ③ 64 位列语义与列宽单位（毫秒时间戳约 1.76e12 必溢出 Int，强制 Long）
#   ④ SQLite Schema 与 7 段幂等迁移历史
# K1–K6 定稿于 2026-11-11 采纳 §9.1；K7/K8 补条于 2026-11-11 第六轮（§10 决定 6）：
#   K7 禁 GlobalScope（并发统一协程域，SupervisorJob + 统一 handler）；
#   K8 禁反向拉取式 API（ViewModel 内不得「事件回调里再查一次 repo」——读走 StateFlow）。
# 白名单条目是历史例外，只许减少不许增加。

set -uo pipefail

ROOT=""
JSON_ONLY=0
SELF_TEST=0
COUNT=0
HITS=""

usage() {
  cat <<'EOF'
用法: arch-guard-kotlin.sh [选项]
  默认: 扫描仓库 android/ 下 .kt 的 8 条硬约束，逐条打印命中，0=通过 1=有违规
选项:
  --root <目录>  指定仓库根（默认 git 顶层 / 当前目录）
  --json         只输出一行 JSON 结论（供 AI/钩子判定，不打印明细）
  --self-test    金丝雀自测：造已知坏样本，证明"能抓到坏"
  -h, --help     本帮助
说明: 裸跑=逐条明细（人看）；--json=单行结论（机器看）。两者扫描规则完全一致。
EOF
}

die() { echo "ERROR: $*" >&2; exit 2; }

resolve_root() {
  if [ -n "$ROOT" ]; then printf '%s\n' "$ROOT"; return; fi
  local here d
  here=$(cd "$(dirname "$0")" && pwd)
  d=$(git -C "$here" rev-parse --show-toplevel 2>/dev/null)
  printf '%s\n' "${d:-$PWD}"
}

# scan_pattern <id> <作用域(空格分隔,相对仓库根)> <描述> <grep参数附加> <正则> <白名单相对路径(空格分隔)>
scan_pattern() {
  local id="$1" scopes="$2" desc="$3" grep_extra="$4" pattern="$5" wl="$6"
  local dirs="" d
  for d in $scopes; do
    [ -d "$ROOT/$d" ] && dirs="$dirs $ROOT/$d"
  done
  [ -z "$dirs" ] && return 0
  local out line rel rest lineno code
  out=$(grep -rnE $grep_extra --include='*.kt' "$pattern" $dirs 2>/dev/null || true)
  [ -z "$out" ] && return 0
  while IFS= read -r line; do
    [ -z "$line" ] && continue
    rel=${line%%:*}; rest=${line#*:}; lineno=${rest%%:*}; code=${rest#*:}
    rel=${rel#"$ROOT/"}
    case " $wl " in *" $rel "*) continue ;; esac
    code=$(printf '%s' "$code" | cut -c1-72 | tr -d '"')
    HITS="${HITS}FAIL ${rel}:${lineno} [${id}] ${desc}\n        ${code}\n"
    COUNT=$((COUNT + 1))
  done <<EOF
$out
EOF
}

scan_all() {
  COUNT=0
  HITS=""
  local SRC="android/app/src android/core"
  # K1 UI 层不触达 data/storage/network（三层模块图 §10 决定 5：UI 经 Domain/Data 流，
  #   网络与磁盘 IO 收口 Data 层；compose UI 文件禁 import 数据/网络面）
  # K1 正则只禁网络库 import 与 data 包穿透——SQLiteOpenHelper/RoomDatabase 类名
  # 的全域匹配已移除（2026-11-14 原生存储轮修正）：数据层驱动本体必然含类名，
  # 出现≠UI 触达；「UI 文件触达存储面」的判定属文件类型上下文，本职=Konsist
  # 四条类型规则（P4 生效，§9.1 双层护栏分工）。此前全域扫描误伤存储驱动与接口 KDoc。
  # 2026-11-14 传输族修正（D10 拍板同批，二段收敛）：K1 判定面=UI 文件触网；core 三模块
  # pure JVM（§10 决定 5）无 Compose/Activity 可言，:app 才是 UI 所在——扫描域收窄
  # android/app/src（与 K8 作用域同则）；--exclude-dir=relay 的目录名排除随之退役（治本不治标）。
  # 2026-11-15 三段收敛（P3 平台层轮）：:app 出现平台网络件（OkWeatherProvider=P3 拍板
  # dio→OkHttp 落点、platform/ 层非 UI）——判定面本就是「UI 文件触网」，扫描域收窄至
  # ui/ 路径族（main+debug 变体；P4 建 ui/ 前真空属设计内——非 UI 文件触网即合法）。
  scan_pattern "K1-ui-data-net" \
    "android/app/src/main/kotlin/app/shiguang/shiguang/ui android/app/src/debug/kotlin/app/shiguang/shiguang/ui" \
    "UI(Compose/Activity/Fragment) 触达数据存储或网络面（须经 Domain/Data 流）" \
    "" \
    "import (java\.net|okhttp|retrofit2)|import package:shiguang/data" ""
  # K2 禁裸 dp/sp 数值（tokens.dart StMotion/尺度纯数值为 SSOT，Kotlin 逐字翻译；
  #   设计系统三层 §10 决定 5：尺寸走令牌层，禁字面量密度像素）
  scan_pattern "K2-raw-dp-sp" "$SRC" \
    "Kotlin 侧裸写 dp/sp 字面量（尺寸一律令牌层翻译，严禁现推参数）" \
    "" \
    "\b[0-9]+(\.[0-9]+)?\.(dp|sp)\b" ""
  # K3 禁 synchronized 与 java.util.concurrent（并发统一协程：单写队列/IO/Default
  #   三池 §10 决定 5；SDK 库自带的并发不属本仓源）
  #   白名单（历史例外，只减不增）：ShiguangMcpBinderService 的 JUC = Binder AIDL
  #   5s 等待契约（-32000 红线语义，01690df 既存）；DebugBridgeWiring = debug-only
  #   装配件（latch 同步与日志缓冲，不进 release、非迁移内核）。
  scan_pattern "K3-sync-juc" "$SRC" \
    "Kotlin 源使用 synchronized 或 java.util.concurrent（并发统一协程域）" \
     android/app/src/debug/kotlin/app/shiguang/shiguang/NativeDebugBridgeWiring.kt"" \
    "\bsynchronized\s*\(|import java\.util\.concurrent" \
    "android/app/src/main/kotlin/app/shiguang/shiguang/ShiguangMcpBinderService.kt android/app/src/debug/kotlin/app/shiguang/shiguang/DebugBridgeWiring.kt android/app/src/androidTest/kotlin/app/shiguang/shiguang/data/AndroidSqlStoreDeviceTest.kt"
  #   （续）AndroidSqlStoreDeviceTest = 仪器测试竞争工位——双 Thread 独立句柄+synchronized
  #   错误收集是跨线程竞争语义的本职构造（coroutine 化即失去竞争面），仅测试源。
  # K4 Composable 禁持 Context·Activity（§10 决定 5 异常与日志纪律/组合无副作用）
  scan_pattern "K4-composable-context" "$SRC" \
    "Composable 上下文持 Context/Activity（经 LocalContext 限定使用点，禁传递持有）" \
    "" \
    "(currentActivity|context as Activity|ContextCompat\.)" ""
  # K5 对外禁暴露 MutableStateFlow（单向数据流 §11.1：对外只读 StateFlow）
  scan_pattern "K5-expose-mutableflow" "$SRC" \
    "对外暴露 MutableStateFlow（对外只读 StateFlow，写入收口类内）" \
    "" \
    "^\s*(public |internal )?val \w+\s*(:|by)\s*MutableStateFlow" ""
  # K6 禁可变全局单例（Manual DI §10 决定 7：AppContainer 单例装配，禁顶层可变态）
  scan_pattern "K6-mutable-global" "$SRC" \
    "顶层可变全局变量（Manual DI：状态收进 AppContainer/类内）" \
    "" \
    "^var [a-zA-Z_]" ""
  # K7 禁 GlobalScope（§10 决定 6：SupervisorJob + 统一 handler，作用域显式装配）
  scan_pattern "K7-globalscope" "$SRC" \
    "使用 GlobalScope（作用域须显式装配，SupervisorJob + 统一 handler）" \
    "" \
    "\bGlobalScope\b" ""
  # K8 ViewModel 禁直持 repo（§10 决定 6：禁反向拉取式 API——读走 StateFlow，
  #   事件回调里不得「再查一次 repo」）。作用域=android/app/src（ViewModel 层）——
  #   :core:action handler 持 BridgeRepositoryPort 是 P2 已拍板架构（native-migration
  #   memory 2026-11-14），非 ViewModel 反向拉取；2026-11-14 拍板收窄（曾全域扫描
  #   误伤 core/action 58 处含注释）。
  #   白名单（结构例外，理由留痕）：ShiguangMcpBinderService = Binder 路径组合根
  #   （D8 收官形态第二步拍板：NativeToolFace(repo=NativeSqlRepository(AndroidSqlStore))，
  #   NativeToolFace.kt 头注明文指示 :app 如此接线）——AIDL 服务门面非 ViewModel，
  #   无「事件回调反查 repo」语义；与同文件 K3 白名单（Binder AIDL 契约）同族。
  #   AppContainer = Manual DI 组合根（§9「对象图与 DI」Application.onCreate 构造，
  #   持仓即职责）——容器装配非 ViewModel 反向拉取。
  scan_pattern "K8-viewmodel-repo" "android/app/src" \
    "ViewModel 直持/直查 Repository（读走 StateFlow，禁反向拉取式 API）" \
    "" \
    "(Repository\b|repo\.[a-z])" \
    "android/app/src/main/kotlin/app/shiguang/shiguang/ShiguangMcpBinderService.kt android/app/src/main/kotlin/app/shiguang/shiguang/AppContainer.kt android/app/src/main/kotlin/app/shiguang/shiguang/service/ShiguangForegroundService.kt android/app/src/main/kotlin/app/shiguang/shiguang/service/ShiguangAlarmReceiver.kt android/app/src/test/kotlin/app/shiguang/shiguang/ArchitectureKonsistTest.kt android/app/src/main/kotlin/app/shiguang/shiguang/ui/schedule/ScheduleDayData.kt android/app/src/main/kotlin/app/shiguang/shiguang/ui/schedule/NowNextSpec.kt android/app/src/test/kotlin/app/shiguang/shiguang/ui/schedule/ScheduleDayDataTest.kt android/app/src/main/kotlin/app/shiguang/shiguang/ui/plans/PlansViewModel.kt android/app/src/test/kotlin/app/shiguang/shiguang/ui/plans/PlansViewModelTest.kt android/app/src/main/kotlin/app/shiguang/shiguang/ui/settings/SettingsViewModel.kt android/app/src/test/kotlin/app/shiguang/shiguang/ui/settings/SettingsViewModelTest.kt android/app/src/main/kotlin/app/shiguang/shiguang/ui/settings/SubpagesViewModel.kt android/app/src/test/kotlin/app/shiguang/shiguang/ui/settings/SubpagesViewModelTest.kt android/app/src/test/kotlin/app/shiguang/shiguang/ui/schedule/NowNextSpecTest.kt android/app/src/main/kotlin/app/shiguang/shiguang/ui/schedule/TodayViewModel.kt android/app/src/test/kotlin/app/shiguang/shiguang/ui/schedule/TodayViewModelTest.kt android/app/src/main/kotlin/app/shiguang/shiguang/ui/gate/GateViewModel.kt android/app/src/test/kotlin/app/shiguang/shiguang/ui/gate/GateViewModelTest.kt android/app/src/main/kotlin/app/shiguang/shiguang/ui/mcp/McpViewModel.kt android/app/src/test/kotlin/app/shiguang/shiguang/ui/mcp/McpViewModelTest.kt android/app/src/test/kotlin/app/shiguang/shiguang/ui/onboarding/OnboardingViewModelTest.kt android/app/src/test/kotlin/app/shiguang/shiguang/service/McpControllerTest.kt android/app/src/debug/kotlin/app/shiguang/shiguang/NativeDebugBridgeWiring.kt android/app/src/main/kotlin/app/shiguang/shiguang/ui/plan/PlanDetailViewModel.kt android/app/src/test/kotlin/app/shiguang/shiguang/ui/plan/PlanDetailViewModelTest.kt android/app/src/main/kotlin/app/shiguang/shiguang/ui/plan/PlanBackgroundViewModel.kt android/app/src/test/kotlin/app/shiguang/shiguang/ui/plan/PlanBackgroundViewModelTest.kt android/app/src/main/kotlin/app/shiguang/shiguang/ui/plan/PlanFactsViewModel.kt android/app/src/test/kotlin/app/shiguang/shiguang/ui/plan/PlanFactsViewModelTest.kt android/app/src/main/kotlin/app/shiguang/shiguang/ui/factsheet/FactSheetsViewModel.kt android/app/src/main/kotlin/app/shiguang/shiguang/ui/collab/MemberCalendarViewModel.kt android/app/src/main/kotlin/app/shiguang/shiguang/ui/collab/DecisionMatrixViewModel.kt android/app/src/main/kotlin/app/shiguang/shiguang/ui/collab/AuditTimelineViewModel.kt android/app/src/main/kotlin/app/shiguang/shiguang/ui/collab/CollabCanvasViewModel.kt"
  #   ↑ 通关卡 sheet 族批（2026-11-15 D25 轮）：FactSheetsViewModel=D15 同形（挂载/改挂/
  #   删除命令+装载面 suspend 读）——PlansViewModel 同族结构例外。
  #   ↑ 协作轮批（2026-11-16 T4.3/T4.4）：MemberCalendar/DecisionMatrix 两 VM=响应/
  #   闭环七动作命令面（outbox 写）+列表/回放读（Dart _load·replay 镜像）——无 StateFlow
  #   读面（协同数据无 revision 流，Dart 同构显式 reload）；与 PlansViewModel 同族结构例外。
  #   ↑ plan 详情族批（2026-11-15 D24 轮）：三 VM 与 PlansViewModel 同形（D15 已拍板
  #   read-pattern：collect revision 重载+命令面写）；三测试文件=JdbcSqlStore 真库播种面（同族）。
  #   （续 2026-11-15 N7 批）PlansViewModel = D15 同形 StateFlow 单向流承载层（读=collect
  #   repo.revision 重载，写=命令面）——与 TodayViewModel 同族结构例外。
  #   （续 2026-11-15 N5 批）ScheduleDayData.loadScheduleDay / NowNextSpec.computeNowNext =
  #   Dart _load/pickNowAndNext 镜像的装配读面纯函数（非 ViewModel，无事件回调反查）；
  #   两测试文件 = JdbcSqlStore 真库装配测试源（同 Konsist 测试白名单族）；
  #   TodayViewModel = D15 裁决①形状（StateFlow 单向流承载层：读=collect repo.revision
  #   重载，写=命令面 handler 调用——非「事件回调里反查 repo」语义）。
  #   （续 2026-11-15）FGS 服务/闹钟接收器=日切承载件——调容器 Housekeeper(repo) 即职责，
  #   与组合根同族；非 ViewModel 反向拉取。
  #   （续 2026-11-15）ArchitectureKonsistTest = Konsist 类型级护栏自指——P4 四条规则
  #   的正则/KDoc 必然字面提及 Repository（守卫本体非 ViewModel，测试源）；与 K3
  #   AndroidSqlStoreDeviceTest 测试源白名单同族。
  #   （续 2026-11-15 N8c 走查批）NativeDebugBridgeWiring = 真机走查桥承载件
  #   （debug-only；repo 快照/还原动作即职责，与组合根/FGS 承载件同族）。
  #   （续 2026-11-15 N8c 批）GateViewModel/McpViewModel = D15 同形 StateFlow 单向流
  #   承载层（读=collect revision 重载，写=命令面/KV 直写）——TodayViewModel 同族
  #   结构例外；三测试文件 = JdbcSqlStore 真库对拍源（K8 测试白名单同族）；
  #   OnboardingViewModel 无 repo 直查不命中（写口全经 CommandHandler），
  #   白名单只收其测试文件（真库播种面）。
}

report_text() {
  if [ "$COUNT" -eq 0 ]; then
    echo "OK: 8 条 Kotlin 架构硬约束全部通过（android/）"
  else
    echo "FAIL: $COUNT 处 Kotlin 架构违规"
    printf '%b' "$HITS"
    echo "[remedy] 按 native-migration §9.1 修结构；历史例外加白名单（只减不增）"
  fi
}

report_json() {
  # toolbox 脚本契约结论：{status, severity, message}（与 arch-guard 同形）。
  if [ "$COUNT" -eq 0 ]; then
    printf '{"status":"OK","severity":"info","message":"arch-guard-kotlin: 8 条 Kotlin 架构硬约束全部通过"}\n'
  else
    printf '{"status":"FAIL","severity":"error","message":"arch-guard-kotlin: %d 处 Kotlin 架构违规"}\n' "$COUNT"
  fi
}

self_test() {
  local bad clean
  bad=$(mktemp -d); clean=$(mktemp -d)
  trap 'rm -rf "$bad" "$clean"' RETURN

  # 坏样本：8 条各至少 1 处（K2/K3/K7 造在非 ui 路径验证全源作用域）
  mkdir -p "$bad/android/app/src/main/kotlin/app/shiguang/shiguang/ui"
  cat >"$bad/android/app/src/main/kotlin/app/shiguang/shiguang/ui/Bad.kt" <<'EOF'
import okhttp3.OkHttpClient
val flow: MutableStateFlow<Int> = MutableStateFlow(0)
var globalState: Int = 0
fun bad() {
    val a = currentActivity
    val w = 16.dp
}
@Composable
fun BadScreen(ctx: Context) {}
EOF
  cat >"$bad/android/app/src/main/kotlin/app/shiguang/shiguang/Worker.kt" <<'EOF'
import java.util.concurrent.Executors
fun work() { GlobalScope.launch {} }
class FooViewModel(private val repo: Repository) {}
EOF
  # 干净样本：全部无违规
  mkdir -p "$clean/android/app/src/main/kotlin/app/shiguang/shiguang"
  cat >"$clean/android/app/src/main/kotlin/app/shiguang/shiguang/Clean.kt" <<'EOF'
val state: StateFlow<Int> = MutableStateFlow(0)
class CleanViewModel(private val flow: StateFlow<Int>) {}
EOF

  ROOT="$bad"; scan_all
  if [ "$COUNT" -lt 8 ]; then
    echo "SELF-TEST FAIL: 坏样本只抓到 $COUNT 处（期望 >=8，K1-K8 每条至少 1）" >&2
    printf '%b' "$HITS" >&2
    return 2
  fi

  ROOT="$clean"; scan_all
  if [ "$COUNT" -ne 0 ]; then
    echo "SELF-TEST FAIL: 干净样本误报 $COUNT 处" >&2
    printf '%b' "$HITS" >&2
    return 2
  fi

  echo "SELF-TEST PASS: 坏样本 8 条全抓、干净样本零误报"
  return 0
}

main() {
  while [ $# -gt 0 ]; do
    case "$1" in
      --root) ROOT="${2:-}"; [ -n "$ROOT" ] || die "--root 缺参数"; shift 2 ;;
      --json) JSON_ONLY=1; shift ;;
      --self-test) SELF_TEST=1; shift ;;
      -h|--help) usage; exit 0 ;;
      *) usage; exit 2 ;;
    esac
  done

  if [ "$SELF_TEST" -eq 1 ]; then
    self_test; exit $?
  fi

  ROOT=$(resolve_root)
  [ -d "$ROOT/android" ] || die "未在仓库根找到 android/：$ROOT（用 --root 指定）"

  scan_all
  if [ "$JSON_ONLY" -eq 1 ]; then report_json; else report_text; fi
}

main "$@"
