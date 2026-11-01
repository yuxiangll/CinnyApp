# CinnyClient iOS 适配说明（按阶段维护）

> 本文件是 iOS 侧唯一的适配文档。后续每完成一个阶段，会自动更新本文件。
> 当前原则：iOS 只维护接口与文档，暂不进行 iOS 测试开发；Android 先行，iOS 后续按本文接入。

## 总体架构

- `core`：设计 token、公共模型、`PlatformServices` 接口。
- `matrix`：`MatrixEngine` 接口；Android 使用 matrix-rust-sdk Kotlin bindings；iOS 使用 MatrixRustSDK Swift Package。
- `shared`：Compose Multiplatform UI、导航、页面；Android 与 iOS 共用。
- `iosApp`：SwiftUI 壳 + `ComposeUIViewController`。

iOS 接入方式：在 Swift 中实现 `MatrixEngine` 与 `PlatformServices`，注入 `CinnyApp(engine:services:)`，复用全部 Compose 页面。

---

## M0：工程基线、设计系统、导航壳

### Android 已完成

- 模块拆分：`core` / `matrix` / `shared` / `androidApp` / `iosApp`。
- Kotlin Toolchain 0.13.0 + `libs.versions.toml` + `module.yaml`。
- MIUIX 0.9.4 依赖保持不变；`minSdk 33`（miuix-blur 限制）。
- 设计 token 从 `cinny/src/colors.css.ts` 与 folds 2.7.2 生成。
- 响应式导航壳：竖屏底部导航、横屏 NavigationRail、深色模式。
- matrix-rust-sdk Android AAR 曾接入并验证 native lib 加载成功；M2 切换 WebView 后移除依赖，M3 再引入。
- 16KB / ABI 校验通过；WebView JS bridge 最小握手通过。
- M0 验证记录见 `docs/M0_VERIFICATION.md`。

### iOS 需要完成

1. 建立 Xcode 工程构建链：
   - macOS + Xcode。
   - 链接 `KotlinModules` framework（`shared` 产出），配置 `FRAMEWORK_SEARCH_PATHS` 与 `Embed & Sign`。
   - 安装 `MatrixRustSDK` Swift Package。
2. 安全区：
   - 当前 `iosApp/src/iosApp.swift` 使用 `ComposeView().ignoresSafeArea()`。
   - 建议移除 `.ignoresSafeArea()`，由 Compose `WindowInsets.safeDrawing` 统一处理刘海、底部 Home Indicator 与键盘。
3. 入口注入：
   - `iosApp/src/ViewController.kt` 改为接收 `MatrixEngine` 与 `PlatformServices`。
   - Swift 侧创建 `MatrixEngineIOS` / `PlatformServicesIOS` 后传入。
4. 验证：
   - 在 macOS 上执行 `./kotlin build -p iosSimulatorArm64`。
   - 运行 iOS 模拟器，确认共享 Compose 导航壳与深色模式。
5. 文档：
   - 本 README 已包含 M0 需要做的工作；M0 不要求 iOS 测试开发。

---

## M1：认证、会话、同步、Home/Direct、基础时间线、纯文本发送

### Android 已完成（M1，真实服务器已验证）

- 登录页：homeserver / 用户名 / 密码。
- 会话恢复与退出登录。
- M1 使用 WebView/Chrome 传输；matrix-rust-sdk 直连因 TLS 指纹被拒，M3 再评估。
- 房间列表：Home（非 Space 房间）与 Direct（`isDirect`）。
- 房间详情：基础时间线、事件文本、发送纯文本、已读回执。
- 设置壳：账号信息、退出登录。
- 真实服务器已用 `@test:s0cl.top` 验证：登录、sync、房间列表、时间线、发送、会话恢复、退出登录。
- 由于 `s0cl.top` 的 WAF 只接受 Chrome TLS，M1 使用 `WebViewTransport`（Chrome/WebView 网络栈）完成真实连接。
- M2 已移除 `RustMatrixEngine` 与 Android rust-sdk 依赖；`libs.versions.toml` 保留 alias，M3 再按需引入。
- 会话 token 先使用 Android `SharedPreferences`，后续 M3 迁移到 Keystore。
- Fake Engine 启动方式：`adb shell am start -n top.s0cl.cinny/.MainActivity --ez top.s0cl.cinny.extra.FAKE_ENGINE true`。
- 详见 `docs/M1_VERIFICATION.md`。

### iOS 需要完成

> 若 `s0cl.top` 的 WAF 仍未放行原生 TLS，iOS M1 建议与 Android 一致，先使用 **WKWebView 传输**：
> 本地 HTML 以 `baseURL = homeserver` 加载，JS `fetch` 调 `/_matrix/*`，`WKScriptMessageHandler` 把响应回传 Kotlin/Swift。
> 若服务器已放行，则优先使用 MatrixRustSDK。

1. `MatrixEngineIOS` 实现：
   - `restoreSession()`：从 Keychain 读取 `accessToken`、`userId`、`deviceId`、`homeserver`。
   - `login()`：调用 `Client.login(username:password:initialDeviceName:deviceId:)`。
   - `syncState`：包装 `SyncService.state` / SDK 回调为 `StateFlow` 或 callback。
   - `rooms()`：使用 `client.rooms()` 或 `RoomListService`，映射为 `RoomSummary`。
   - `timeline(roomId)`：`room.timeline()` + `TimelineListener`，映射 `TimelineDiff`。
   - `send()`：`timeline.createMessageContent(MessageType.text)` + `timeline.send()`。
   - `sendReadReceipt()`：`timeline.sendReadReceipt` 或 `room.sendSingleReceipt`。
2. `SessionStoreIOS`：
   - Keychain 保存 access token / refresh token / device id。
   - 禁用 iCloud Keychain 同步，避免 token 跨设备。
3. `PlatformServicesIOS`：
   - `SecureStorage` → Keychain。
   - `Notifier` → UNUserNotificationCenter（M1 只需前台本地通知占位）。
   - `MediaPicker` / `CallHost` 可先提供空实现，M2/M3 补齐。
4. UI：
   - 直接复用 shared 的登录、Home/Direct、房间、设置页面。
   - 不新增 SwiftUI 业务页面。
5. 验证：
   - iOS 模拟器登录、同步、房间列表、发送文本。
   - 由于当前只做文档，iOS 实机/模拟器验证留到后续 iOS 开发阶段。
6. 若采用 WKWebView 传输：
   - `WKWebView.loadHTMLString(html, baseURL: homeserverURL)`；
   - `evaluateJavaScript("nativeFetch(...)")`；
   - `WKScriptMessageHandler` 接收 `onResponse`；
   - 注意 `WKNavigationDelegate` 禁止外部导航，只允许本地页面与 `/_matrix/*` 请求。

### 接口约定

```kotlin
interface MatrixEngine {
    val session: StateFlow<SessionState>
    val syncState: StateFlow<SyncState>
    suspend fun restoreSession(): Result<Session>
    suspend fun login(server: String, credentials: Credentials): Result<Session>
    suspend fun logout(): Result<Unit>
    fun rooms(): Flow<List<RoomSummary>>
    fun timeline(roomId: String): Flow<TimelinePage>
    suspend fun send(roomId: String, draft: MessageDraft): Result<EventId>
    suspend fun sendReadReceipt(roomId: String, eventId: String, private: Boolean): Result<Unit>
}
```

---

## M2：Spaces、完整消息操作、媒体、搜索、Inbox、Explore、设置

### Android 已完成（M2 部分）

- Spaces/Lobby（只读）、子房间列表；自定义顺序、拖拽排序待补。
- 消息回复/编辑/删除/表情回应/置顶已实现；thread UI、编辑历史待补。
- 图片上传/下载/显示已实现；视频/音频/文件播放、贴纸、自定义 emoji 未实现。
- URL preview、媒体自动加载、加密媒体未实现。
- 全局搜索、Inbox 邀请/未读、Explore 公共房间已实现；房间过滤/跳转事件待补。
- 设置已持久化：主题、媒体自动加载、Enter、消息布局、通知开关；消息布局/通知行为待完善。
- Android HTML 富文本已接入；Markdown/mention/spoiler/代码高亮待补。

### iOS 需要完成

1. `MediaPicker`：PHPickerViewController + UIDocumentPickerViewController。
2. `FileSaver`：UIActivityViewController / Share Sheet。
3. `MediaLoader`：使用 MatrixRustSDK 的 `MediaSource` + 解密；图片走 AsyncImage/Coil KMP。
4. `Notifier`：UNUserNotificationCenter 前台通知；M3 再接 APNs。
5. `CallHost`：暂不实现，M3 用 WKWebView + CallKit。
6. UI：复用 shared 的 Spaces、消息、媒体、搜索、Inbox、Explore、设置页面。
7. 输入：
   - 复用 Compose 输入法；iOS 键盘安全区由 Compose Insets 处理。
   - 附件选择通过 `MediaPicker` 注入。
8. 手势：
   - 长按消息操作面板、图片双指缩放、下滑关闭等由共享 Compose 实现。
   - 返回手势使用 iOS 原生滑动返回 / Compose Navigation 返回栈。

---

## M3：E2EE、验证、密钥备份、推送、通话、开发者工具

### Android 将完成

- matrix-rust-sdk 加密、SAS 验证、交叉签名、密钥备份、Secret Storage。
- FCM / UnifiedPush 推送、通知渠道、通知模式、关键词、声音。
- Element Call WebView 能力岛：widget API 桥、前台服务、PiP、音频焦点。
- 开发者工具：发送事件、状态事件、账户数据。

### iOS 需要完成

1. 加密与验证：
   - 全部使用 MatrixRustSDK，不自行实现密码学。
   - `SessionVerificationController` 映射到 shared 验证页面。
   - Keychain 存储恢复密钥；禁用备份到 iCloud。
2. 推送：
   - APNs + `UIBackgroundModes: remote-notification`。
   - Notification Service Extension 处理富通知/解密通知。
   - VoIP 使用 PushKit + CallKit。
   - 注册 pusher 与 Android 相同的 Matrix push gateway。
3. 通话：
   - `WKWebView` 承载 Element Call。
   - `WKScriptMessageHandler` 实现 widget API 桥。
   - `AVAudioSession`、CallKit、前台音频、PiP（iOS 15+）。
4. 开发者工具：复用 shared 页面；原生 JSON 编辑器由 Compose 实现。
5. 安全：
   - Keychain、Associated Domains、App Groups。
   - 通知扩展不落盘敏感内容。

---

## M4：响应式、平板/折叠屏、横竖屏、RTL、性能、无障碍

### Android 将完成

- 600dp / 840dp 断点、列表-详情双栏、NavigationRail、折叠屏。
- 刘海/挖孔/手势导航/键盘 Insets。
- 深色模式、字体缩放、RTL。
- 时间线性能、图片缓存、内存、启动优化。
- TalkBack、48dp 触控热区、对比度。

### iOS 需要完成

1. 安全区与横竖屏：
   - 移除 `ignoresSafeArea()`，使用 Compose Insets。
   - 支持 iPhone 刘海/灵动岛、iPad 多任务、分屏。
2. 布局：
   - iPad 使用列表-详情双栏；iPhone 单栏。
   - 横屏时复用 NavigationRail。
3. 字体缩放：
   - 支持 Dynamic Type；Compose 字号映射到 `sp`，不写死像素。
4. 无障碍：
   - VoiceOver 语义、焦点顺序、触控热区。
5. 性能：
   - 时间线虚拟化沿用 shared；图片缓存使用 KMP 方案。
   - iOS 使用 Instruments 检查内存/滚动。

---

## M5：发布、TestFlight、App Store、文档收尾

### Android 将完成

- Release 构建、签名、R8、Baseline Profile。
- AAB/ABI split、16KB 校验、隐私清单。
- 崩溃/性能监控、Beta 渠道。

### iOS 需要完成

1. 签名与证书：
   - Apple Developer Team、Provisioning Profile、APNs 证书/Key。
2. TestFlight：
   - Archive、Export、上传 App Store Connect。
3. App Store 合规：
   - 隐私政策、数据收集声明、加密出口合规。
   - 通知、相机、麦克风、相册权限文案。
4. 后台能力：
   - `remote-notification`、`voip`、`audio`。
5. 文档：
   - 更新本 README 的 M5 验收结果。
   - 记录 iOS 构建、推送、通话、深链配置。

---

## 当前状态

- M0：Android 完成；iOS 仅接口/文档，未做测试开发。
- M1：Android 已完成并通过真实服务器验证（登录、sync、房间、时间线、发送、会话恢复、退出），使用 WebView/Chrome 传输绕过 TLS 指纹限制；M2 已移除 rust SDK 依赖，M3 视服务器 TLS 放行情况重新引入。iOS 仍只维护文档。
- M2：Android 大部分完成（Spaces 只读+创建/排序、消息操作、图片/文件上传显示、搜索、Inbox、Explore、设置持久化、HTML、Thread 回复）；视频/音频播放、拖拽排序、独立 Thread 视图、搜索过滤仍待补。iOS 仍只维护文档。
- M3–M5：待后续阶段。

---

## M3：E2EE、推送、通话能力岛、开发者工具（Android 已完成）

### Android 实现要点

- **传输**：`LocalRelayServer`（127.0.0.1 明文 HTTP）+ `WebViewTransport.relay()`，让 matrix-rust-sdk（rustls）
  复用 Chromium TLS 指纹，绕过 `s0cl.top` WAF；`ClientBuilder` 只能使用「仅 homeserverUrl」配置。
- **E2EE**：`MatrixCryptoService`（matrix-rust-sdk 26.10.23）：`restoreSession` + `syncV2`、交叉签名、
  密钥备份（`enableRecovery` / `recover`）、SAS 验证控制器与表情比对 UI、聚焦 timeline 解密。
- **双账号对话测试**：进程内第二个 rust-sdk 客户端（独立存储/设备/中继），一键完成双向加密收发验证。
- **推送**：通知渠道（消息/提及/通话）、本地通知（加密消息解密后通知）、UnifiedPush topic、
  HTTP pusher 注册（服务器返回 405，受限）。
- **通话能力岛**：`shared/src@android/CallIsland.android.kt`，WebView widget 宿主 + `toWidget/fromWidget`
  桥 + 摄像头/麦克风权限代理 + 音频焦点 + 画中画按钮；`shared/src@ios` 为占位 actual。
- **开发者工具**：事件/状态事件/账号数据、rust-sdk 加密发送、401 自动重试。

### iOS 需要完成

1. `MatrixEngine` 的 M3 扩展方法：`cryptoStatus()`、`decryptEvent()`、`enableKeyBackup()`、
   `restoreKeyBackup()`、`sendEncryptedText()`、`requestVerification()/acceptVerification()/startSasVerification()`、
   `sendCustomEvent()/sendStateEvent()/setAccountData()/getAccountData()`（均有默认实现，可不实现）。
2. 使用 MatrixRustSDK（Swift）实现同样的 E2EE 与备份能力；iOS 无 WAF 问题，可直接 https 连接。
3. `CallIslandHost` 的 iOS actual 已在 `shared/src@ios` 提供占位；接入时用 `WKWebView` + `WKScriptMessageHandler`
   实现 widget 桥、`AVAudioSession` 处理音频焦点、`AVPictureInPictureController` 处理 PiP。
4. 推送：iOS 使用 APNs + `POST /pushers`（`kind: "apns"`），需服务器支持。
5. M3 验证记录见 `docs/M3_VERIFICATION.md`。
