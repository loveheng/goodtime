#!/bin/bash
# bump-version — 拾光版本号更新脚本（P5 Gradle-only：版本源=android/app/build.gradle.kts）
#
# toolbox-script
# format: v1
# name: bump-version
# summary: 更新 android/app/build.gradle.kts 版本（versionName x.y.z + versionCode 整数）：bump 版本段/仅加 code/--set 显式；自动探测已连接设备的已装版本，降级风险时自动追平
# trigger: manual
# platform: unix
# cat: build
# alias: bump

set -euo pipefail

MODE=""        # major|minor|patch|build(仅加 versionCode)
SET_SPEC=""    # 显式 x.y.z+w
DRY_RUN=0
GRADLE_FILE="android/app/build.gradle.kts"
PKG="app.shiguang.shiguang"   # applicationId
SKIP_DEVICE=0
GUARD=0

usage() {
  cat <<'EOF'
用法: bump-version.sh [选项]        （toolbox run bump-version [选项] 等价）
作用: 修改 <repo>/android/app/build.gradle.kts 的 versionName/versionCode 行
      （P5 Gradle-only：pubspec.yaml 已随 Dart 侧退役，版本源固化在 gradle）
选项:
  --patch       版本段 +0.0.1（默认）
  --minor       版本段 +0.1.0
  --major       版本段 +1.0.0
  --build       版本段不动，仅 versionCode +1（补发场景）
  --set x.y.z+w 显式指定完整版本（w 可省，省略则保留原 code）
  --no-device   跳过设备已装版本探测（CI/无 adb 环境）
  --guard       只读+按需守卫：仅当设备已装版本会导致降级时才抬高 versionCode，否则不动文件
                （供 build-apk 等脚本构建前调用，平时 bump 用 --patch/--build/--set）
  -f <file>     指定 gradle 文件路径（默认 android/app/build.gradle.kts）
  -n, --dry-run 只打印将写入的新版本，不落盘
  --json        快速预检（一行 JSON 结论，读当前版本，不修改）
  -h, --help    本帮助
说明: 裸跑（带 --patch/--minor/--major/--build/--set 之一）才修改文件；
      --json 与 --dry-run 均只读。幂等可重跑。
EOF
}

json_out() { # status severity message remedy
  if [ -n "${4:-}" ]; then
    printf '{"status":"%s","severity":"%s","message":"%s","remedy":"%s"}\n' "$1" "$2" "$3" "$4"
  else
    printf '{"status":"%s","severity":"%s","message":"%s"}\n' "$1" "$2" "$3"
  fi
}

# 从 gradle 提取当前版本：versionName "x.y.z"（或 = x.y.z）+ versionCode 整数（引号可选）
read_version() {
  local vn vc
  vn=$(grep -m1 -E '^[[:space:]]*versionName[[:space:]]*=' "$GRADLE_FILE" | sed -E 's/^[^"]*"?([0-9]+\.[0-9]+\.[0-9]+)"?.*/\1/')
  vc=$(grep -m1 -E '^[[:space:]]*versionCode[[:space:]]*=' "$GRADLE_FILE" | sed -E 's/^[^0-9]*([0-9]+).*/\1/')
  [ -n "$vn" ] && [ -n "$vc" ] && printf '%s+%s\n' "$vn" "$vc"
}

while [ $# -gt 0 ]; do
  case "$1" in
    --patch) MODE="patch" ;;
    --minor) MODE="minor" ;;
    --major) MODE="major" ;;
    --build) MODE="build" ;;
    --set)
      [ $# -ge 2 ] || { echo "FAIL: --set 需要 x.y.z+w 参数"; usage; exit 2; }
      SET_SPEC="$2"; shift
      ;;
    -f) [ $# -ge 2 ] || { echo "FAIL: -f 需要文件路径"; exit 2; }; GRADLE_FILE="$2"; shift ;;
    --no-device) SKIP_DEVICE=1 ;;
    --guard) GUARD=1 ;;
    -n|--dry-run) DRY_RUN=1 ;;
    --json)
      if [ ! -f "$GRADLE_FILE" ]; then
        json_out FAIL error "gradle 文件不存在: $GRADLE_FILE" "确认在 repo 根运行或用 -f 指定路径"
        exit 1
      fi
      CUR="$(read_version || true)"
      if [ -z "$CUR" ]; then
        json_out FAIL error "gradle 中未找到 versionName/versionCode 行" "检查 $GRADLE_FILE defaultConfig 格式"
        exit 1
      fi
      json_out OK info "当前版本 $CUR，可裸跑 --patch/--minor/--major/--build/--set 更新"
      exit 0
      ;;
    -h|--help) usage; exit 0 ;;
    *) usage; exit 2 ;;
  esac
  shift
done

# ---- 校验入参与现状 ----
[ -n "$MODE" ] || [ -n "$SET_SPEC" ] || [ "$GUARD" = 1 ] || {
  echo "FAIL: 未指定更新模式（--patch/--minor/--major/--build/--set 之一）"
  echo "[remedy] 先 toolbox run bump-version --json 读当前版本，再带模式裸跑"
  usage
  exit 2
}
[ -f "$GRADLE_FILE" ] || { echo "FAIL: gradle 文件不存在: $GRADLE_FILE"; exit 2; }

CUR="$(read_version || true)"
[ -n "$CUR" ] || { echo "FAIL: 未在 $GRADLE_FILE 找到 versionName/versionCode 行"; exit 2; }

CUR_VER="${CUR%%+*}"
CUR_BUILD="${CUR#*+}"
IFS='.' read -r MA MI PA <<< "$CUR_VER"

# ---- 设备已装版本探测（防 INSTALL_FAILED_VERSION_DOWNGRADE）----
DEVICE_CODE=""
if [ "$SKIP_DEVICE" = 0 ] && command -v adb >/dev/null 2>&1 && adb devices 2>/dev/null | grep -q 'device$'; then
  DEVICE_CODE="$(adb shell dumpsys package "$PKG" 2>/dev/null | grep -m1 -oE 'versionCode=[0-9]+' | cut -d= -f2 || true)"
  if [ -n "$DEVICE_CODE" ]; then
    echo "设备 $PKG 已装 versionCode: $DEVICE_CODE"
  else
    echo "设备已连接但未安装 $PKG，跳过追平"
  fi
fi

MIN_BUILD=""
[ -n "$DEVICE_CODE" ] && MIN_BUILD=$(( DEVICE_CODE + 1 ))

# ---- 计算新版本 ----
if [ "$GUARD" = 1 ]; then
  NEW_VER="$CUR_VER"
  NEW_BUILD=$(( CUR_BUILD + 1 ))
elif [ -n "$SET_SPEC" ]; then
  NEW_VER="${SET_SPEC%%+*}"
  case "$SET_SPEC" in
    *+*) NEW_BUILD="${SET_SPEC#*+}" ;;
    *)   NEW_BUILD="$CUR_BUILD" ;;
  esac
else
  case "$MODE" in
    major) NEW_VER="$((MA+1)).0.0" ;;
    minor) NEW_VER="$MA.$((MI+1)).0" ;;
    patch) NEW_VER="$MA.$MI.$((PA+1))" ;;
    build) NEW_VER="$CUR_VER" ;;
  esac
  NEW_BUILD=$(( CUR_BUILD + 1 ))
fi

# ---- 降级防护：目标 code 追不上设备已装版本时自动抬高 ----
if [ -n "$MIN_BUILD" ] && [ "$NEW_BUILD" -lt "$MIN_BUILD" ]; then
  echo "⚠ 目标 versionCode $NEW_BUILD 在设备上会装配降级（设备已装 $DEVICE_CODE），自动抬高至 $MIN_BUILD"
  NEW_BUILD="$MIN_BUILD"
fi

# guard 且无降级风险且 code 未变 → 不动文件，快速返回
if [ "$GUARD" = 1 ] && [ -z "$MIN_BUILD" ]; then
  echo "OK: 设备未连接/未安装，无降级风险，版本保持 $CUR"
  exit 0
fi
if [ "$GUARD" = 1 ] && [ -n "$MIN_BUILD" ] && [ "$CUR_BUILD" -ge "$MIN_BUILD" ]; then
  echo "OK: 当前 versionCode $CUR_BUILD ≥ 已装 $DEVICE_CODE，无降级风险，版本保持 $CUR"
  exit 0
fi

if ! printf '%s' "$NEW_VER" | grep -qE '^[0-9]+\.[0-9]+\.[0-9]+$'; then
  echo "FAIL: 新版本段非法: '$NEW_VER'"
  exit 2
fi

echo "当前: $CUR"
echo "目标: $NEW_VER+$NEW_BUILD"

if [ "$DRY_RUN" = 1 ]; then
  echo "[dry-run] 将把 $GRADLE_FILE 的 versionName/versionCode 替换为: $NEW_VER / $NEW_BUILD（未动真目标）"
  exit 0
fi

# ---- 写入（sed �准替换两行，其余原样）----
TMP="$GRADLE_FILE.bump-tmp"
sed -E \
  -e "s/^([[:space:]]*versionCode[[:space:]]*=[[:space:]]*)[0-9]+([[:space:]]*.*)$/\1$NEW_BUILD\2/" \
  -e "s/^([[:space:]]*versionName[[:space:]]*=[[:space:]]*)\"[0-9]+\.[0-9]+\.[0-9]+\"([[:space:]]*.*)$/\1\"$NEW_VER\"\2/" \
  "$GRADLE_FILE" > "$TMP"

VN_OK=$(grep -cE "^[[:space:]]*versionName[[:space:]]*=[[:space:]]*\"$NEW_VER\"" "$TMP" || true)
VC_OK=$(grep -cE "^[[:space:]]*versionCode[[:space:]]*=[[:space:]]*$NEW_BUILD" "$TMP" || true)
if [ "$AFTER" != "$NEW_VER+$NEW_BUILD" ]; then  rm -f "$TMP"
  echo "FAIL: 写入后复核不一致（期望 $NEW_VER / $NEW_BUILD），文件未改动"
  exit 2
fi
mv "$TMP" "$GRADLE_FILE"
echo "OK: $GRADLE_FILE 版本已更新为 $NEW_VER+$NEW_BUILD"
