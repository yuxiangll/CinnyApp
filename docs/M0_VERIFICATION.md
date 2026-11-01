# M0 验证记录

日期：2026-11-01  
环境：Windows 11 / JDK 25 / Kotlin Toolchain 0.13.0 / Android Emulator 1080x2424 @420dpi

## 验证项

| 编号 | 验证项 | 结果 | 证据 |
|---|---|---|---|
| M0-1 | 基线构建与测试 | 通过 | `kotlin build`、`kotlin test`（3 tests） |
| M0-2 | 工具链能力确认 | 通过 | `show settings`：compileSdk 37、targetSdk 37、minSdk 33、abiFilters arm64-v8a/x86_64 |
| M0-3 | 模块拆分 core/matrix/shared | 通过 | `show modules` 5 个模块；`kotlin build -p android` 成功 |
| M0-4 | 设计 token 生成与校验 | 通过 | `node tools/design/generate-design-tokens.mjs --check`；5 个 token 测试 |
| M0-5 | 响应式导航壳 | 通过 | 竖屏底部导航、横屏导航栏；截图 nav-compact.png / nav-wide-v3.png；深色模式 nav-dark-v2.png |
| M0-6 | matrix-rust-sdk Android 接入 | 通过 | emulator 运行 M0SmokeActivity；logcat `RustSDK: client built and closed` |
| M0-7 | 16KB / ABI 校验 | 通过 | AAR 内 arm64 `.so` LOAD Align 0x4000；APK `zipalign -c -P 16` 成功；native-code arm64-v8a/x86_64 |
| M0-8 | Element Call WebView 最小握手 | 通过 | WebViewAsset 本地页面 + JS bridge；logcat `m0-bridge-handshake-ok` |
| M0-9 | iOS 桥接接口与文档 | 部分通过（环境限制） | 接口与文档已建立；Windows 无 `xcode-select`，iOS 编译需 macOS |

## 已知问题

- M0 导航壳为占位页面，未接入真实业务。
- MIUIX 深色方案与 Cinny token 尚未完全融合，M1 将做映射。
- `WebViewAssetLoader` 需要 `androidx.webkit`，当前网络无法稳定拉取；M0 使用本地 HTML + `loadDataWithBaseURL` 验证桥接，M3 接入真实 Element Call 时再启用。
- iOS 构建必须在 macOS 上复验 M0-9。

## 构建命令

```bash
./kotlin build -p android
./kotlin test -p android
./kotlin run -m androidApp
adb shell am start -n top.s0cl.cinny/.M0SmokeActivity
adb logcat -s CinnyM0
```
