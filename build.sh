#!/usr/bin/env bash
# LaneNav 本地编译脚本（已在本机验证通过）
#
# 用法:
#   ./build.sh            编译 debug（默认）
#   ./build.sh --release  编译正式签名版（需签名密钥库）
#
set -e
cd "$(dirname "$0")"

export JAVA_HOME=$(dirname $(dirname $(readlink -f $(command -v java))))
export GRADLE_USER_HOME=/vol1/@apphome/trim.openclaw/data/home/.gradle
export ANDROID_HOME="$PWD/.toolchain/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"

MODE="debug"
if [ "$1" = "--release" ]; then
  MODE="release"
  SIGDIR="$HOME/.lanenav-signing"
  KS="$SIGDIR/lanenav-release.jks"
  if [ ! -f "$KS" ]; then
    echo "❌ 找不到签名密钥库: $KS" >&2
    exit 1
  fi
  export LANENAV_KEYSTORE="$KS"
  export LANENAV_STOREPASS="$(cat "$SIGDIR/keystore.pass")"
  export LANENAV_KEYPASS="$LANENAV_STOREPASS"
  export LANENAV_KEYALIAS="lanenav"
  echo "签名模式: release（密钥库已加载）"
fi

CAP="$(echo ${MODE^})"   # debug -> Debug, release -> Release

GRADLE_ARGS=()
for a in "$@"; do
  [ "$a" = "--release" ] && continue
  GRADLE_ARGS+=("$a")
done

./.toolchain/gradle-8.7/bin/gradle \
  ":phone:assemble${CAP}" ":box:assemble${CAP}" \
  --no-daemon --console=plain ${GRADLE_ARGS[@]+"${GRADLE_ARGS[@]}"}

VER=$(grep -m1 'versionName' phone/build.gradle.kts | sed -E 's/.*"([0-9.]+)".*/\1/')

mkdir -p dist
cp "phone/build/outputs/apk/${MODE}/phone-${MODE}.apk" "dist/LaneNav-phone-${VER}.apk"
cp "box/build/outputs/apk/${MODE}/box-${MODE}.apk"     "dist/LaneNav-box-${VER}.apk"

echo
echo "产物 (${MODE}):"
ls -lh dist/LaneNav-*-${VER}.apk
