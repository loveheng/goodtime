#!/bin/bash
# arch-guard — 拾光分层架构 / Human-AI 对称性硬约束机械护栏
#
# toolbox-script
# format: v1
# name: arch-guard
# summary: 拾光架构硬约束机械校验（UI 禁直连 repo 写/UI 禁内联 JSON/UI 禁平台分支/UI 禁直连 Db/mcp+action 层禁 import flutter UI/禁弃用 textScaleFactor），改动 lib/ 后与发布前门禁
# trigger: manual
# platform: unix
# cat: test
# alias: ag
#
# 规则口径见 .agents/skills/shiguang-workflow/SKILL.md（无头系统：删掉整个
# Flutter UI，MCP 命令面应能完整运行——因此 UI 不得绕过命令层、mcp/action
# 层不得反向依赖 Flutter UI）；白名单条目是**历史例外**，只许减少不许增加。

set -uo pipefail

ROOT=""
JSON_ONLY=0
SELF_TEST=0
COUNT=0
HITS=""

usage() {
  cat <<'EOF'
用法: arch-guard.sh [选项]        （toolbox run arch-guard [选项] 等价）
  默认: 扫描仓库 lib/ 下 6 条硬约束，逐条打印命中，0=通过 1=有违规
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

# scan_pattern <id> <作用域(空格分隔)> <描述> <正则> <白名单相对路径(空格分隔)>
scan_pattern() {
  local id="$1" scopes="$2" desc="$3" pattern="$4" wl="$5"
  local dirs="" d
  for d in $scopes; do
    [ -d "$ROOT/$d" ] && dirs="$dirs $ROOT/$d"
  done
  [ -z "$dirs" ] && return 0
  local out line rel rest lineno code
  out=$(grep -rnE --include='*.dart' --exclude='*.g.dart' --exclude='*.freezed.dart' \
        "$pattern" $dirs 2>/dev/null || true)
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
  # R1 UI 层禁止直连 Repository 写方法 —— 写必走 CommandHandler.execute（§3 唯一写入口）
  scan_pattern "R1-ui-repo-write" "lib/ui" \
    "UI 层直连 Repository 写方法，写路径必须组装 ScheduleCommand 交 CommandHandler" \
    '\brepo\.(addPlan|patchPlan|deletePlan|addBlock|patchBlock|deleteBlock|replaceFixedSlots|settingsSet|settingsClear)\(' ""
  # R2 UI 层禁止内联 JSON 编解码（编解码委托数据/命令层；导出预览口径豁免）
  scan_pattern "R2-ui-json-codec" "lib/ui" \
    "UI 层内联 jsonDecode/jsonEncode，应委托数据/命令层" \
    '\bjson(Decode|Encode)\(' "lib/ui/settings_page.dart"
  # R3 UI 层禁止平台分支（平台差异收敛到接口实现/工厂）
  scan_pattern "R3-ui-platform" "lib/ui" \
    "UI 层出现 Platform.isX 分支，平台差异须收敛到接口实现/工厂" \
    'Platform\.is[A-Z]' ""
  # R4 mcp/action 层禁 import Flutter UI 库 —— 无头系统硬约束（删 UI 全链路可跑）
  # （foundation/services 属非 UI 基建，不在此列）
  scan_pattern "R4-headless-ui-import" "lib/mcp lib/action" \
    "mcp/action 层 import flutter material/widgets，无头系统禁止反向依赖 UI" \
    "import 'package:flutter/(material|widgets)\.dart'" ""
  # R5 UI 层禁止直连 Db.instance —— 数据访问一律走 Repository
  scan_pattern "R5-ui-db-direct" "lib/ui" \
    "UI 层直连 Db.instance，数据访问一律走 Repository" \
    '\bDb\.instance\(' ""
  # R6 排版禁用已弃用 textScaleFactor（M3 尺度走 textTheme + textScaler）
  scan_pattern "R6-textscale-deprecated" "lib" \
    "使用已弃用 textScaleFactor，须改 textScaler 并映射 M3 textTheme" \
    '(textScaleFactor:)' ""
  return 0
}

report_text() {
  if [ "$COUNT" -eq 0 ]; then
    echo "OK: 6 条架构硬约束全部通过（$ROOT）"
    return 0
  fi
  printf '%b' "$HITS"
  echo "----"
  echo "FAIL: $COUNT 处违规"
  echo "[remedy] 逐条对照 .agents/skills/shiguang-workflow/SKILL.md 的模块结构口径改；"
  echo "[remedy] 确认是历史遗留且暂不迁移的，加进脚本白名单并注明原因（只减不增）"
  return 1
}

report_json() {
  local msg remedy
  if [ "$COUNT" -eq 0 ]; then
    printf '{"status":"OK","severity":"info","message":"arch-guard: 6 条架构硬约束全部通过"}\n'
    return 0
  fi
  msg="arch-guard: $COUNT 处架构硬约束违规（R1 UI直连repo写 / R2 UI内联json / R3 UI平台分支 / R4 mcp+action import UI / R5 UI直连Db / R6 弃用textScaleFactor）"
  remedy="裸跑 toolbox run arch-guard 看逐条明细；对照 shiguang-workflow SKILL.md 模块口径修正，历史遗留加白名单并注明原因"
  printf '{"status":"FAIL","severity":"error","message":"%s","remedy":"%s"}\n' "$msg" "$remedy"
  return 1
}

self_test() {
  local bad clean rc
  bad=$(mktemp -d); clean=$(mktemp -d)
  trap 'rm -rf "$bad" "$clean"' RETURN

  mkdir -p "$bad/lib/ui" "$bad/lib/mcp"
  cat >"$bad/lib/ui/bad_page.dart" <<'EOF'
await repo.addPlan(Plan(title: 'x'));
final m = jsonDecode(s);
if (Platform.isAndroid) {}
final db = Db.instance();
style: TextStyle(textScaleFactor: 1.2),
EOF
  cat >"$bad/lib/mcp/bad_tools.dart" <<'EOF'
import 'package:flutter/material.dart';
EOF

  mkdir -p "$clean/lib/ui"
  cat >"$clean/lib/ui/ok_widget.dart" <<'EOF'
final r = await handler.execute(const ConfirmBlockCommand(id: 'x'));
final rows = await repo.blocksOnDate('2026-10-06');
EOF

  ROOT="$bad"; scan_all
  if [ "$COUNT" -lt 6 ]; then
    echo "SELF-TEST FAIL: 坏样本只抓到 $COUNT 处（期望 >=6）" >&2
    printf '%b' "$HITS" >&2
    return 2
  fi

  ROOT="$clean"; scan_all
  if [ "$COUNT" -ne 0 ]; then
    echo "SELF-TEST FAIL: 干净样本误报 $COUNT 处" >&2
    printf '%b' "$HITS" >&2
    return 2
  fi

  echo "SELF-TEST PASS: 坏样本抓到违规、干净样本零误报"
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
  [ -d "$ROOT/lib" ] || die "未在仓库根找到 lib/：$ROOT（用 --root 指定）"

  scan_all
  if [ "$JSON_ONLY" -eq 1 ]; then report_json; else report_text; fi
}

main "$@"
