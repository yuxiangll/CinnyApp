#!/usr/bin/env sh
# 运行测试，并把完整输出写到 logs/test.log。
set -eu
cd "$(dirname "$0")/.."
mkdir -p logs
./kotlin test -p android > logs/test.log 2>&1 || {
    echo "测试失败，日志：logs/test.log" >&2
    grep -nE "Failed |AssertionFailedError|ERROR" logs/test.log | head -n 40 >&2
    exit 1
}
echo "测试通过，日志：logs/test.log"
