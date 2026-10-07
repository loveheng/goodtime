#!/bin/bash
# release-r2 — 拾光一键发布到 Cloudflare R2（应用内自更新源）
#
# 流程: 读 pubspec 版本 → flutter build apk --release（仅 arm64-v8a，注入 UPDATE_SOURCE_URL）
#       → 算 sha256 → 生成 updates/shiguang-update.json → 上传 apk+json 到 R2 公开域
#
# 前置:
#   1) Cloudflare 后台对该 bucket 开启「公开访问」并绑定自定义域（如 update.example.com），
#      确保 https://<域>/updates/... 可公开 GET（r2.dev 默认域有限速，不建议作生产更新源）
#   2) 在仓库根 .env（参考 .env.example）或环境变量中配置 R2_* 与域名
#
# 用法: bash scripts/release-r2.sh [-n|--dry-run] [--commit]
#   --commit   发布成功后把 updates/shiguang-update.json 提交进仓库（便于追溯）
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT"

# 加载本地 .env：仓库根优先，回退 scripts/.env；均不存在则仅用环境变量
if [ -f .env ]; then
  source .env
elif [ -f scripts/.env ]; then
  source scripts/.env
fi

# ---- 可配置项（环境变量优先）----
R2_ACCOUNT_ID="${R2_ACCOUNT_ID:-}"
R2_ACCESS_KEY_ID="${R2_ACCESS_KEY_ID:-}"
R2_SECRET_ACCESS_KEY="${R2_SECRET_ACCESS_KEY:-}"
R2_BUCKET="${R2_BUCKET:-shiguang-updates}"
R2_PUBLIC_DOMAIN="${R2_PUBLIC_DOMAIN:-}"   # 自定义域，如 update.example.com（不含 https://）
REMOTE_PREFIX="${REMOTE_PREFIX:-updates}"   # R2 内对象前缀（对应 App 更新源子目录）
CHANGELOG="${CHANGELOG:-}"                  # 留空则取最近一条 git commit 信息

# 导出给 upload-r2.mjs 子进程读取
export R2_ACCOUNT_ID R2_ACCESS_KEY_ID R2_SECRET_ACCESS_KEY R2_BUCKET

DRY_RUN=0
DO_COMMIT=0
while [ $# -gt 0 ]; do
  case "$1" in
    -n|--dry-run) DRY_RUN=1 ;;
    --commit) DO_COMMIT=1 ;;
    -h|--help) sed -n '2,16p' "$0"; exit 0 ;;
    *) echo "未知参数: $1"; exit 2 ;;
  esac
  shift
done

export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
export PATH="$HOME/flutter/bin:$ANDROID_HOME/platform-tools:$PATH"

# ---- 校验 ----
missing=()
[ -z "$R2_ACCOUNT_ID" ] && missing+=(R2_ACCOUNT_ID)
[ -z "$R2_ACCESS_KEY_ID" ] && missing+=(R2_ACCESS_KEY_ID)
[ -z "$R2_SECRET_ACCESS_KEY" ] && missing+=(R2_SECRET_ACCESS_KEY)
[ -z "$R2_PUBLIC_DOMAIN" ] && missing+=(R2_PUBLIC_DOMAIN)
if [ ${#missing[@]} -gt 0 ]; then
  echo "FAIL: 缺少 R2 配置: ${missing[*]}"
  echo "      在仓库根 .env 或环境变量中设置（参考 .env.example）"
  exit 1
fi
command -v node >/dev/null 2>&1 || { echo "FAIL: 需要 node（本机应已具备）"; exit 1; }
command -v flutter >/dev/null 2>&1 || command -v "$HOME/flutter/bin/flutter" >/dev/null 2>&1 || { echo "FAIL: flutter 不可用"; exit 1; }

# ---- 版本 ----
VERSION_LINE="$(grep -m1 '^version:' pubspec.yaml)"
VERSION_NAME="$(echo "$VERSION_LINE" | sed -E 's/.*:\s*([0-9.]+)\+.*/\1/')"
VERSION_CODE="$(echo "$VERSION_LINE" | sed -E 's/.*\+([0-9]+).*/\1/')"
[ -z "$CHANGELOG" ] && CHANGELOG="$(git log -1 --pretty=%s 2>/dev/null || echo '')"
# 转义 json 字符串中的反斜杠与双引号
CHANGELOG_ESC="${CHANGELOG//\\/\\\\}"
CHANGELOG_ESC="${CHANGELOG_ESC//\"/\\\"}"

APK="build/app/outputs/flutter-apk/app-arm64-v8a-release.apk"
PUBLIC_BASE="https://${R2_PUBLIC_DOMAIN}"
APK_URL="${PUBLIC_BASE}/${REMOTE_PREFIX}/app-release.apk"
SRC_URL="${PUBLIC_BASE}/${REMOTE_PREFIX}"

echo "版本: v${VERSION_NAME} (build ${VERSION_CODE})"
echo "更新源 URL（填 App 更新页）: ${SRC_URL}"

if [ "$DRY_RUN" = 1 ]; then
  echo "[dry-run] 将执行:"
  echo "  flutter pub get"
  echo "  flutter analyze"
  echo "  flutter build apk --release --split-per-abi --target-platform android-arm64 -P force-version-code-ignoring-abi=true --dart-define=UPDATE_SOURCE_URL=\"$SRC_URL\""
  echo "  sha256sum $APK"
  echo "  生成 updates/shiguang-update.json (apkUrl=$APK_URL)"
  echo "  node scripts/upload-r2.mjs $APK $REMOTE_PREFIX/app-release.apk application/vnd.android.package-archive"
  echo "  node scripts/upload-r2.mjs updates/shiguang-update.json $REMOTE_PREFIX/shiguang-update.json application/json"
  exit 0
fi

# ---- 构建 ----
flutter pub get
flutter analyze
flutter build apk --release --split-per-abi --target-platform android-arm64 -P force-version-code-ignoring-abi=true --dart-define=UPDATE_SOURCE_URL="$SRC_URL"

[ -f "$APK" ] || { echo "FAIL: 构建产物不存在: $APK"; exit 1; }
SHA="$(sha256sum "$APK" | cut -d' ' -f1)"

# ---- 生成清单 ----
mkdir -p updates
cat > updates/shiguang-update.json <<EOF
{
  "versionCode": ${VERSION_CODE},
  "versionName": "${VERSION_NAME}",
  "apkUrl": "${APK_URL}",
  "sha256": "${SHA}",
  "changelog": "${CHANGELOG_ESC}"
}
EOF
echo "清单已生成: updates/shiguang-update.json"

# ---- 上传（零依赖 Node 脚本，AWS SigV4 直传 R2）----
# --no-network-family-autoselection：Node 22 Happy Eyeballs 每次连接尝试默认 250ms 上限，
# 本机到 Cloudflare RTT ~300ms 时 fetch 必超时；关闭竞速后按解析序直连（2026-10-07 实测）
NODE_UP=(node --no-network-family-autoselection --dns-result-order=ipv4first)
echo "上传 APK → s3://$R2_BUCKET/$REMOTE_PREFIX/app-release.apk"
"${NODE_UP[@]}" scripts/upload-r2.mjs "$APK" "$REMOTE_PREFIX/app-release.apk" "application/vnd.android.package-archive"
echo "上传清单 → s3://$R2_BUCKET/$REMOTE_PREFIX/shiguang-update.json"
"${NODE_UP[@]}" scripts/upload-r2.mjs updates/shiguang-update.json "$REMOTE_PREFIX/shiguang-update.json" "application/json"

echo "OK: 发布完成"
echo "  更新源 URL（App 更新页填写）: ${SRC_URL}"
echo "  APK 公开地址: ${APK_URL}"
if [ "$DO_COMMIT" = 1 ]; then
  git add updates/shiguang-update.json
  git commit -m "chore: release v${VERSION_NAME} (${VERSION_CODE})" && echo "已提交清单"
fi
