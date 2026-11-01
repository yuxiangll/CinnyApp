@echo off
rem 运行测试，输出写入 logs\test.log
setlocal
cd /d "%~dp0.."
if not exist logs mkdir logs
call kotlin.bat test -p android > logs\test.log 2>&1
if errorlevel 1 (
    echo 测试失败，日志：logs\test.log
    powershell -NoProfile -Command "Select-String -Path logs\test.log -Pattern 'Failed ','ERROR' | Select-Object -First 40"
    exit /b 1
)
echo 测试通过，日志：logs\test.log
