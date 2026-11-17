#!/bin/bash
# arch-guard — 拾光架构硬约束机械护栏（P5 Gradle-only 薄壳）
#
# toolbox-script
# format: v1
# name: arch-guard
# summary: 拾光架构硬约束机械校验——P5 Dart 侧退役后规则面收敛为 Kotlin 护栏（arch-guard-kotlin），本脚本保留 toolbox alias 兼容
# trigger: manual
# platform: unix
# cat: test
# alias: ag
#
# 历史口径：P5 前本脚本承载 6 条 Dart 规则（R1 UI 禁直连 repo 写 / R2 禁内联 JSON /
# R3 禁平台分支 / R4 mcp+action 禁 import Flutter UI / R5 禁直连 Db / R6 弃用
# textScaleFactor）——lib/ 随 Dart 物理删除退役，规则面由 arch-guard-kotlin.sh
# 承接（Kotlin 分层/命令面/日志纪律）。白名单纪律不变：只减不增。

set -uo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"

usage() {
  cat <<'EOF'
用法: arch-guard.sh [选项]        （toolbox run arch-guard [选项] 等价）
  P5 起为 arch-guard-kotlin 薄壳（Dart 规则面随 lib/ 退役）。
选项:
  --root <目录>  指定仓库根（默认 git 顶层 / 当前目录）
  --json         只输出一行 JSON 结论
  --self-test    转发 arch-guard-kotlin 自测
  -h, --help     本帮助
EOF
}

ROOT=""
while [ $# -gt 0 ]; do
  case "$1" in
    --root) ROOT="${2:-}"; shift 2 ;;
    --json) JSON_ONLY=1; shift ;;
    --self-test) SELF_TEST=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) usage; exit 2 ;;
  esac
done

ARGS=()
[ -n "$ROOT" ] && ARGS+=(--root "$ROOT")
[ "${JSON_ONLY:-0}" = 1 ] && ARGS+=(--json)
[ "${SELF_TEST:-0}" = 1 ] && ARGS+=(--self-test)

exec bash "$DIR/arch-guard-kotlin.sh" "${ARGS[@]}"
