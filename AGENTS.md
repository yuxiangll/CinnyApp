# AGENTS.md

本文件面向所有 AI 编码助手（Claude Code、Codex、Cursor、Copilot 等）。
开始任何任务前请先通读本文件；涉及阶段目标与验收标准时，再读 [`docs/PROJECT_STATUS.md`](docs/PROJECT_STATUS.md)。

---

## 0. 项目速览

| 项 | 值 |
| --- | --- |
| 项目 | CinnyClient — [Cinny](https://github.com/cinnyapp/cinny) 的 Kotlin Multiplatform 移动端移植 |
| 协议 | Matrix Client-Server API（`/_matrix/client/v3`） |
| 技术栈 | Kotlin Multiplatform · Compose Multiplatform · MIUIX · matrix-rust-sdk |
| 构建工具 | Kotlin Toolchain 0.13.0（`kotlin` / `kotlin.bat`，**不是 Gradle**） |
| 目标平台 | Android（主要，`minSdk 33` / `compileSdk 37`）；iOS 复用共享 UI |
| 包名 | `top.s0cl.cinny` |
| 当前阶段 | M0–M3 完成，M4 进行中，M5 未开始 |
| 远程仓库 | `https://github.com/yuxiangll/CinnyApp.git`（分支 `main`） |

---

## 1. 硬性规则（必须遵守）

1. **文件路径**：在 Windows 上执行文件操作时必须使用带盘符的完整绝对路径，例如
   `C:\Users\Administrator\Desktop\CinnyClient\shared\src\App.kt`；
   不要使用 `/c/...`、`./...` 或相对路径（编辑器插件存在路径解析缺陷）。
2. **只读目录**：`cinny/` 是上游 Cinny Web 客户端的参考检出，**禁止修改、禁止提交**（已在 `.gitignore` 中）。
3. **不入库内容**：`build/`、`logs/`、`resources/`、`.idea/`、`cinny/`、`*.log`、`*.apk`、`*.aab` 一律不提交。
   如需新增被忽略的产物，先更新 `.gitignore`。
4. **禁止提交凭据**：不要把真实账号、密码、access token、refresh token、设备 ID、恢复密钥写进源码、日志或文档。
   文档示例统一使用 `example.org`、`@user:example.org`、`<redacted>`。
5. **日志位置**：所有构建 / 测试 / 运行日志写入 `logs/`，不要污染仓库根目录。
6. **生成文件**：`core/.../design/CinnyColors.kt`、`CinnyScales.kt` 由脚本生成，禁止手工编辑；
   修改请改 `tools/design/generate-design-tokens.mjs` 或 `tools/design/folds-2.7.2-tokens.json`，然后重新生成并 `--check`。
7. **依赖管理**：版本统一写在 `libs.versions.toml`；模块依赖写在对应 `module.yaml`。
   **不要创建 `build.gradle.kts`、`settings.gradle.kts` 或 `gradlew`**。
8. **平台源码**：`shared/src`、`matrix/src`、`core/src` 是 common 代码；
   `src@android` / `src@ios` 是平台专属代码（`expect`/`actual` 或接口实现）。不要把 Android API 写进 common。
9. **改动即验证**：提交前至少运行一次相关构建与测试，并把关键结论写进 `logs/`（见 §2）。
10. **文档同步**：功能状态变化时同步更新 `docs/PROJECT_STATUS.md` 与对应 `docs/Mx_VERIFICATION.md`。

---

## 2. 构建、测试与运行

```sh
# 构建 Android（CI / 本地首次会下载工具链）
./kotlin build -p android

# 运行测试
./kotlin test -p android

# 单模块测试
./kotlin test -m matrix

# 安装并启动 Android App
./kotlin run -m androidApp
```

Windows（cmd / PowerShell）把 `./kotlin` 换成 `kotlin.bat`；Git Bash 可直接用 `./kotlin`。

推荐使用封装脚本（自动写日志、失败时打印摘要）：

```sh
./scripts/build.sh     # logs/build.log
./scripts/test.sh      # logs/test.log
./scripts/run.sh       # logs/install.log、logs/logcat.log
```

| 环境 | 命令 |
| --- | --- |
| Linux / macOS / Git Bash | `./scripts/build.sh`、`./scripts/test.sh`、`./scripts/run.sh` |
| Windows cmd / PowerShell | `scripts\build.bat`、`scripts\test.bat` |

排障时先看 `logs/build.log` / `logs/test.log`；`logs/` 可安全清空，构建产物在 `build/`。

**环境要求**：JDK 21；Android SDK（`compileSdk 37`、`targetSdk 37`、`minSdk 33`）。
首次构建需要网络下载 Kotlin Toolchain 与依赖。

---

## 3. 模块地图

| 模块 | 类型 | 职责 | 关键文件 |
| --- | --- | --- | --- |
| `core` | KMP lib | 设计 token、主题、设置模型、`PlatformServices` | `core/src/top/s0cl/cinny/core/design/*`、`core/src/top/s0cl/cinny/core/settings/AppSettings.kt` |
| `matrix` | KMP lib | Matrix 能力契约、内存引擎、WebView 传输、中继、加密、通知 | `matrix/src/top/s0cl/cinny/matrix/MatrixEngine.kt`、`matrix/src@android/top/s0cl/cinny/matrix/WebViewMatrixEngine.kt`、`matrix/src@android/top/s0cl/cinny/matrix/crypto/MatrixCryptoService.kt`、`matrix/src@android/top/s0cl/cinny/matrix/net/LocalRelayServer.kt` |
| `shared` | KMP lib | Compose UI、页面、导航、平台能力实现 | `shared/src/top/s0cl/cinny/app/CinnyApp.kt`、`shared/src/top/s0cl/cinny/app/M2Screens.kt`、`shared/src/top/s0cl/cinny/app/M3Screens.kt`、`shared/src/top/s0cl/cinny/platform/*` |
| `androidApp` | Android app | 应用壳、权限、通知、引擎装配 | `androidApp/src/MainActivity.kt`、`androidApp/src/AndroidManifest.xml` |
| `iosApp` | iOS app | SwiftUI 壳 + `ComposeUIViewController` | `iosApp/src/iosApp.swift`、`iosApp/src/ViewController.kt` |
| `tools/design` | Node 脚本 | 设计 token 生成与校验 | `tools/design/generate-design-tokens.mjs` |
| `scripts` | Shell / BAT | 构建、测试、运行封装 | `scripts/*.sh`、`scripts/*.bat` |

依赖方向：`androidApp` → `shared` → `matrix` → `core`；`shared` 也直接依赖 `core`。
`androidApp` 同时依赖 `core` / `matrix` / `shared`。

---

## 4. 运行时架构与数据流

```
MainActivity
  ├─ AndroidSettingsStore (SharedPreferences)
  ├─ NotificationHelper (通知渠道 / 本地通知)
  └─ MatrixEngine
       ├─ InMemoryMatrixEngine      # 预览、单测、--fake 模式
       └─ WebViewMatrixEngine       # 真实网络
            ├─ WebViewTransport     # Chromium 网络栈处理 HTTPS / 大文件分块
            ├─ LocalRelayServer     # 127.0.0.1 中继，供 rust-sdk 复用 Chromium TLS
            └─ MatrixCryptoService  # matrix-rust-sdk：E2EE / 备份 / 验证
```

关键约定：

- UI 层只依赖 `MatrixEngine` 接口，**不要**在 Compose 页面里直接引用 `WebView`、`OkHttp`、`Ktor` 或 rust-sdk 类型。
- 预览与测试使用 `InMemoryMatrixEngine`，保证无需网络即可渲染页面。
- `WebViewMatrixEngine` 通过 JS bridge 与注入脚本通信；二进制数据按 **192KB 分块** 传输。
- 受限网络下，rust-sdk 的 `homeserverUrl` 指向 `LocalRelayServer`，由 `WebViewTransport.relay()` 转发；
  不要改回直连，否则会触发 WAF 的 TLS 重置。

---

## 5. 代码与格式约定

- 包名：`top.s0cl.cinny.<module>[.<feature>]`。
- Kotlin：4 空格缩进，行宽约 120（见 `.editorconfig`）。
- UI 文案使用中文；代码注释中英文均可，但同一文件内保持一致。
- 组件拆分：页面级 Composable 放 `shared/src/top/s0cl/cinny/app/`；可复用控件放 `shared/src/top/s0cl/cinny/ui/`。
- 平台差异优先用 `expect`/`actual` 或接口 + 工厂，不要在 common 中做平台判断分支。
- 新增依赖：先加 `libs.versions.toml` 的 `[versions]` / `[libraries]`，再在 `module.yaml` 用 `$libs.xxx` 引用。
- 新增仓库镜像 / 编译参数：改对应 `module.yaml`，不要引入 Gradle 文件。
- 不要删除或重命名已有测试；契约变更需同步更新 `MatrixContractTest`、`InMemoryMatrixEngineTest`。
- 避免在仓库中新增大于 1MB 的二进制文件；测试用媒体放本地 `resources/`（已忽略）。

---

## 6. 常见任务手册

### 6.1 新增页面 / 导航项

1. 页面 Composable 放 `shared/src/top/s0cl/cinny/app/`。
2. 底部 / 侧边导航项与路由见 `shared/src/top/s0cl/cinny/navigation/MainDestination.kt`。
3. 补充 `MainDestinationTest`；确保 Compact / Medium / Expanded 三种断点都可到达。
4. 返回逻辑使用 `PlatformBackHandler`，不要直接调用 Android `OnBackPressedDispatcher`。

### 6.2 新增 Matrix API

1. 在 `matrix/src/top/s0cl/cinny/matrix/MatrixEngine.kt` 增加接口方法（返回 `Result<T>` 或 Flow）。
2. 在 `InMemoryMatrixEngine` 提供可测试的假实现。
3. 在 `WebViewMatrixEngine` 实现真实请求（优先复用现有 `requestJson` / 分块上传下载封装）。
4. 若涉及加密事件，走 `MatrixCryptoService`，不要绕过 rust-sdk。
5. 补充 `MatrixContractTest` 用例，并同步 `docs/PROJECT_STATUS.md`。

### 6.3 修改主题 / 设计 token

```sh
node tools/design/generate-design-tokens.mjs
node tools/design/generate-design-tokens.mjs --check
./kotlin test -m core
```

`CinnyTheme.kt` 负责把 token 映射到 Compose / MIUIX 色板；新增色板时同步测试 `DesignTokensTest`。

### 6.4 调试网络与 WebView

- 抓包与日志：`logs/logcat.log`；WebView 控制台输出走 `android.util.Log`。
- 登录 / sync 失败先确认 homeserver 是否触发 WAF；必要时启用 `LocalRelayServer` 路径。
- 大文件失败优先检查 192KB 分块与 base64 编解码边界。

### 6.5 媒体与加密附件

- 明文附件：AES-CTR 解密后写入缓存再展示 / 保存。
- 加密附件：通过 rust-sdk `MediaSource` 下载解密。
- 上传路径：`MediaPicker` / `MediaAttachment` → 分块 bridge → `m.room.message`。
- 当前加密附件**发送**尚未实现，转发按明文重新上传；修改时需同步文档中的限制说明。

### 6.6 推送与通话

- 推送：`NotificationHelper` 建渠道；UnifiedPush topic 与 HTTP pusher 注册在 `matrix` 模块。
- 通话：`CallIsland` 宿主 WebView widget，权限代理在 `androidApp`，PiP 入口需真机验证。

---

## 7. 测试策略

| 位置 | 内容 |
| --- | --- |
| `core/test/top/s0cl/cinny/core/design/DesignTokensTest.kt` | 设计 token 与移动端布局常量 |
| `matrix/test/top/s0cl/cinny/matrix/MatrixContractTest.kt` | `MatrixEngine` 契约 |
| `matrix/test/top/s0cl/cinny/matrix/InMemoryMatrixEngineTest.kt` | 内存引擎行为 |
| `shared/test/top/s0cl/cinny/navigation/MainDestinationTest.kt` | 导航目标 |
| `shared/test/top/s0cl/cinny/ui/RichTextTest.kt` | HTML / mention / spoiler 渲染 |
| `shared/test/PlatformTest.kt`、`shared/test@android/PlatformAndroidTest.kt` | 平台能力 |

要求：

- 新功能至少补 1 个可离线运行的测试；优先使用 `InMemoryMatrixEngine`。
- 测试命名用 `fun 行为描述()` 或反引号描述，避免依赖执行顺序。
- 提交前运行 `./kotlin test -p android`（或 `./scripts/test.sh`），确认 0 failed。
- 真机 / 服务器验证结果记录到 `docs/Mx_VERIFICATION.md`，截图放 `build/m0-screens/`（不提交）。

---

## 8. 已知坑与限制（不要顺手修复）

1. **TLS 指纹 / WAF**：部分服务器只接受 Chromium TLS。现有方案是 `WebViewTransport` + `LocalRelayServer`；
   替换为 OkHttp / Ktor / 原生 rustls 直连会直接失败。
2. **`minSdk 33`**：由 `miuix-blur` 0.9.4 声明决定，降低会导致 Manifest 合并失败，需要专项改造。
3. **Maven 镜像**：`module.yaml` 使用华为云镜像；改回官方仓库前先确认网络可达。
4. **推送**：服务器若对 `POST /_matrix/client/v3/pushers` 返回 `405 M_UNRECOGNIZED`，无后台离线推送，属环境限制。
5. **rust-sdk 存储未加密**：SQLite 加密库尚未接入，不要声称本地存储已加密。
6. **模拟器**：镜像可能禁用 PiP（`mDisablePip=true`），画中画需真机验证。
7. **16KB 页对齐**：发布构建需保持 `zipalign -c -P 16` 通过；新增 native 依赖后必须复验。
8. **截图与日志不入库**：`build/`、`logs/` 已被忽略；README 使用 `docs/screenshots/` 中的精选图片。
9. **历史加密消息**：缺少会话密钥时显示“缺少会话密钥”是预期行为，不是 bug。
10. **iOS**：只维护接口与文档，不做 iOS 测试开发；不要为了统一而重写 Android 已验证逻辑。

---

## 9. 提交前检查清单

- [ ] `./scripts/build.sh`（或 `./kotlin build -p android`）通过
- [ ] `./scripts/test.sh`（或 `./kotlin test -p android`）通过，0 failed
- [ ] `git status` 中没有 `build/`、`logs/`、`.idea/`、`cinny/`、`resources/`、`*.apk`
- [ ] 没有新增账号、密码、token、恢复密钥
- [ ] 新增 / 变更依赖已写入 `libs.versions.toml` 与 `module.yaml`
- [ ] 生成的设计 token 已执行 `--check`
- [ ] 相关文档（`docs/PROJECT_STATUS.md` / `docs/Mx_VERIFICATION.md`）已更新
- [ ] 提交信息格式：`type(scope): summary`，例如 `feat(m2): 支持视频内联播放`

---

## 10. 文档地图

| 文档 | 用途 |
| --- | --- |
| [`README.md`](README.md) | 项目介绍、构建与使用 |
| [`docs/PROJECT_STATUS.md`](docs/PROJECT_STATUS.md) | M0–M5 总览、验证汇总、风险与下一步 |
| [`docs/M0_VERIFICATION.md`](docs/M0_VERIFICATION.md) | 工程基线验证 |
| [`docs/M1_VERIFICATION.md`](docs/M1_VERIFICATION.md) | 认证与基础消息验证 |
| [`docs/M2_VERIFICATION.md`](docs/M2_VERIFICATION.md) | 媒体 / 消息操作 / 搜索 / Spaces 验证 |
| [`docs/M3_VERIFICATION.md`](docs/M3_VERIFICATION.md) | E2EE / 推送 / 通话 / 开发者工具验证 |
| [`docs/M4_VERIFICATION.md`](docs/M4_VERIFICATION.md) | 响应式 / RTL / 性能 / 无障碍验证 |
| [`docs/ios/README.md`](docs/ios/README.md) | iOS 唯一适配文档 |

修改代码后，若行为、限制或验证结论发生变化，请更新上表对应文档，保持“代码 = 文档”。
