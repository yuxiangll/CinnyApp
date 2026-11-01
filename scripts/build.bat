@echo off
rem 构建 Android 工程，输出写入 logs\build.log（logs\ 已在 .gitignore 中）
setlocal
cd /d "%~dp0.."
if not exist logs mkdir logs
call kotlin.bat build -p android > logs\build.log 2>&1
if errorlevel 1 (
    echo 构建失败，日志：logs\build.log
    powershell -NoProfile -Command "Get-Content logs\build.log -Tail 40"
    exit /b 1
)
echo 构建成功，日志：logs\build.log
