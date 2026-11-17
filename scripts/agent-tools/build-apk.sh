#!/bin/bash
# build-apk — 拾光 Android 编译脚本（P5 Gradle-only：默认 debug 包，--release release 整包）
#
# toolbox-script
# format: v1
# name: build-apk
# summary: 拾光 Android 编译（Gradle-only）：arch-guard-kotlin+全测门槛+构建+产物校验，默认 debug 包，--release 切 release
# trigger: manual
# platform: unix
# alias: apk

set -euo pipefail

BUILD_RELEASE=0
INSTALL_AFTER=0
RUN_GUARD=1
FRESH_DAEMON=0
DRY_RUN=0
BUILD_DOGFOOD=0

usage() {
  cat <<'EOF'
用法: build-apk.sh [选项]        （toolbox run build-apk [选项] 等价）
  默认: arch-guard-kotlin + debug 包构建（产物 app-debug.apk）
选项:
  --debug       构建 debug 包（现为默认，保留兼容显式调用）
  --release     构建 release 整包（P5 Gradle-only：无 ABI 分包，整包即唯一产物）
  --dogfood     切 dogfood flavor（内部试用隔离：applicationId .dogfood + 独立 SQLite 文件）
  --install     构建成功后 adb 安装到已连接手机（多产物时装第一个匹配的）
  --skip-test   跳过 arch-guard-kotlin 门槛（P5 前为 flutter analyze 0 issue 线）
  --fresh       先停掉 gradle 守护进程（构建锁被占/产物串台时用）
  -n, --dry-run 只打印将执行的步骤，不真正构建
  --json        环境快速预检（一行 JSON 结论，不执行构建）
  -h, --help    本帮助
说明: --json 只做环境预检；真正构建用裸跑（无参数或带选项）。
      P5 变更：Flutter CLI 链退役——pub get/analyze/split-per-abi/--abi/--fat 随删。
EOF
}

fail_remedy() {
  case "$1" in
    *"Timeout waiting to lock"*|*"waiting for a lock"*)
      echo "[remedy] gradle 项目锁被占：加 --fresh 重跑本脚本（或 pkill -f '[G]radleDaemon' 后重试）" ;;
    *"Connection timed out"*|*"Failed to establish a new connection"*|*"Connection reset"*)
      echo "[remedy] maven 网络偶发停滞：直接重跑本脚本" ;;
  esac
}

precheck_json() {
  if [ ! -d "${ANDROID_HOME:-$HOME/android-sdk}" ]; then
    printf '{"status":"FAIL","severity":"error","message":"android-sdk 不存在","remedy":"设置 ANDROID_HOME 或安装 Android SDK"}\n'
    exit 1
  fi
  printf '{"status":"OK","severity":"info","message":"环境预检通过：android-sdk 就绪（Gradle-only），可裸跑构建"}\n'
  exit 0
}

while [ $# -gt 0 ]; do
  case "$1" in
    --abi) echo "FAIL: --abi 已退役（P5 Gradle-only 无 ABI 分包，release 整包三架构全含）"; exit 2 ;;
    --fat) BUILD_RELEASE=1 ;;
    --debug) ;;
    --release) BUILD_RELEASE=1 ;;
    --dogfood) BUILD_DOGFOOD=1 ;;
    --install) INSTALL_AFTER=1 ;;
    --skip-test) RUN_GUARD=0 ;;
    --fresh) FRESH_DAEMON=1 ;;
    -n|--dry-run) DRY_RUN=1 ;;
    --json) precheck_json ;;
    -h|--help) usage; exit 0 ;;
    *) usage; exit 2 ;;
  esac
  shift
done

export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
export PATH="$ANDROID_HOME/platform-tools:$PATH"

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$REPO_ROOT"
LOG="build/apk-build.log"

# P5 dogfood 隔离：--dogfood 切 dogfood flavor（独立 applicationId .dogfood + 独立 SQLite 文件）
if [ "${BUILD_DOGFOOD:-0}" = 1 ]; then
  FLAVOR="dogfood"
else
  FLAVOR="prod"
fi
if [ "$BUILD_RELEASE" = 1 ]; then
  BUILD_CMD="./gradlew :app:assemble${FLAVOR^}Release"
  ARTIFACTS="build/app/outputs/apk/${FLAVOR}/release/app-${FLAVOR}-release.apk"
  STEP_NAME="release 整包（$FLAVOR）"
else
  BUILD_CMD="./gradlew :app:assemble${FLAVOR^}Debug"
  ARTIFACTS="build/app/outputs/apk/${FLAVOR}/debug/app-${FLAVOR}-debug.apk"
  STEP_NAME="debug 包（全架构，默认，$FLAVOR）"
fi

STEPS=(
  "版本防降级守卫（bump-version --guard，设备已装版本更高时自动抬 build 号）"
  "$BUILD_CMD"
  "产物校验（aapt 版本 + sha256）"
)
[ "$FRESH_DAEMON" = 1 ] && STEPS=("停掉 gradle 守护进程（--fresh）" "${STEPS[@]}")
[ "$RUN_GUARD" = 1 ] && STEPS=("arch-guard-kotlin（架构硬约束门槛）" "${STEPS[@]}")
[ "$INSTALL_AFTER" = 1 ] && STEPS+=("adb install -r 到已连接手机")

if [ "$DRY_RUN" = 1 ]; then
  echo "[dry-run] 将执行以下步骤（未动真目标）："
  i=1
  for s in "${STEPS[@]}"; do echo "  [$i/${#STEPS[@]}] $s"; i=$((i+1)); done
  echo "  预期产物:$ARTIFACTS"
  echo "  构建日志: $LOG"
  exit 0
fi

i=1; total=${#STEPS[@]}

if [ "$RUN_GUARD" = 1 ]; then
  echo "[$i/$total] arch-guard-kotlin"
  TOOLBOX_DIR="$(cd "$(dirname "$0")" && pwd)"
  if [ -x "$TOOLBOX_DIR/arch-guard-kotlin.sh" ]; then
    "$TOOLBOX_DIR/arch-guard-kotlin.sh" --root "$REPO_ROOT" || { echo "FAIL: arch-guard-kotlin 未过（门槛），先修复再编译"; exit 1; }
  else
    echo "  ⚠ arch-guard-kotlin.sh 缺失，跳过（fail-open）"
  fi
  i=$((i+1))
fi

echo "[$i/$total] 版本防降级守卫"
TOOLBOX_DIR="$(cd "$(dirname "$0")" && pwd)"
if [ -x "$TOOLBOX_DIR/bump-version.sh" ]; then
  "$TOOLBOX_DIR/bump-version.sh" --guard || { echo "FAIL: 版本守卫异常，中止构建（防装包降级）"; exit 1; }
else
  echo "  跳过：未找到 bump-version.sh"
fi
i=$((i+1))
[ "$FRESH_DAEMON" = 1 ] && {
  echo "[$i/$total] 停掉 gradle 守护进程"
  (cd android && ./gradlew --stop >/dev/null 2>&1 || true)
  i=$((i+1))
}

echo "[$i/$total] $BUILD_CMD（日志: $LOG）"
mkdir -p build
if ! $BUILD_CMD > "$LOG" 2>&1; then
  tail -25 "$LOG"
  echo "FAIL: 构建失败，完整日志在 $LOG"
  fail_remedy "$(tail -25 "$LOG")"
  exit 1
fi
echo "构建完成"
i=$((i+1))

echo "[$i/$total] 产物校验"
VERIFY_FAILED=0
for ART in $ARTIFACTS; do
  if [ ! -f "$ART" ]; then
    echo "  FAIL: 预期产物不存在: $ART"
    VERIFY_FAILED=1
    continue
  fi
  SHA="$(sha256sum "$ART" | cut -d' ' -f1)"
  BADGE="$("$AAPT" dump badging "$ART" 2>/dev/null | grep -E '^package:' | tr '\n' ' ')"
  ls -lh "$ART" | awk -v a="$ART" '{print "  " a "  大小: "$5}'
  echo "  sha256: $SHA"
  echo "  $BADGE"
done
[ "$VERIFY_FAILED" = 1 ] && exit 1

if [ "$INSTALL_AFTER" = 1 ]; then
  if adb devices | grep -q "device$"; then
    FIRST_ART="$(echo $ARTIFACTS | awk '{print $1}')"
    adb install -r "$FIRST_ART" && echo "已安装到手机: $FIRST_ART"
  else
    echo "未检测到已连接手机，跳过安装；手动安装: adb install -r${ARTIFACTS%% *}"
  fi
fi

echo "OK: $STEP_NAME 编译完成 →$ARTIFACTS"
