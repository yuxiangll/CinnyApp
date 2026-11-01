#!/usr/bin/env sh
# 构建 Android 工程，并把完整输出写到 logs/build.log（logs/ 已在 .gitignore 中）。
set -eu
cd "$(dirname "$0")/.."
mkdir -p logs
./kotlin build -p android > logs/build.log 2>&1 || {
    echo "构建失败，日志：logs/build.log" >&2
    tail -n 40 logs/build.log >&2
    exit 1
}
echo "构建成功，日志：logs/build.log"
