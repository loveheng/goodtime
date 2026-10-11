#!/usr/bin/env bash
# 把中继独立仓（../shiguang-relay）生成的 Dart 只读契约代码同步进本仓 lib/collab/proto/。
#
# 纪律（协同设计 §8.4 双仓边界）：契约源头只有一份 —— shiguang-relay/relay_protocol.proto。
# 本仓 lib/collab/proto/ 下的 .pb.dart 一律为生成物，**禁止手改**；
# 改契约请改 proto 后在独立仓跑 scripts/gen-proto.sh，再跑本脚本同步。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RELAY="${RELAY_DIR:-$ROOT/../shiguang-relay}"
DEST="$ROOT/lib/collab/proto"

if [[ ! -d "$RELAY/gen/dart" ]]; then
  echo "未找到 $RELAY/gen/dart —— 先在独立仓跑 scripts/gen-proto.sh（或设置 RELAY_DIR）"
  exit 1
fi

mkdir -p "$DEST"
cp "$RELAY"/gen/dart/relay_protocol.pb.dart \
   "$RELAY"/gen/dart/relay_protocol.pbenum.dart \
   "$RELAY"/gen/dart/relay_protocol.pbjson.dart \
   "$DEST"/

echo "已同步契约生成物 → lib/collab/proto/："
ls -1 "$DEST"
echo
echo "提示：生成物入仓，随本仓一并提交；改动只应来自契约变更。"
