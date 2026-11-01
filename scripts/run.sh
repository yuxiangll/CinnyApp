#!/usr/bin/env sh
# 安装并启动 Android App，logcat 输出写到 logs/logcat.log。
set -eu
cd "$(dirname "$0")/.."
mkdir -p logs
adb install -r build/tasks/_androidApp_buildAndroidDebug/gradle-project-debug.apk > logs/install.log 2>&1
adb logcat -c
adb shell am start -n top.s0cl.cinny/.MainActivity >> logs/install.log 2>&1
adb logcat -d > logs/logcat.log 2>&1
echo "已启动，logcat：logs/logcat.log"
