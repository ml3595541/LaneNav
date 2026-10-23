#!/usr/bin/env bash
# LaneNav 本地编译脚本（已在本机验证通过）
set -e
cd "$(dirname "$0")"

export JAVA_HOME=$(dirname $(dirname $(readlink -f $(command -v java))))
export GRADLE_USER_HOME=/vol1/@apphome/trim.openclaw/data/home/.gradle
export ANDROID_HOME="$PWD/.toolchain/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"

./.toolchain/gradle-8.7/bin/gradle :phone:assembleDebug :box:assembleDebug \
  --no-daemon --console=plain "$@"

mkdir -p dist
cp phone/build/outputs/apk/debug/phone-debug.apk dist/LaneNav-phone-0.3.1.apk
cp box/build/outputs/apk/debug/box-debug.apk     dist/LaneNav-box-0.3.1.apk
echo
echo "产物:"
ls -lh dist/*.apk
