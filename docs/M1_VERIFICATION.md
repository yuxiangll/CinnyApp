# M1 验证记录

日期：2026-11-01  
环境：Windows 11 / Kotlin Toolchain 0.13.0 / Android Emulator 1080x2424 @420dpi  
测试服务器：`s0cl.top` / `https://matrix.s0cl.top/` / `@test:s0cl.top`

## 1. M1 实现范围

- 登录页：Homeserver、用户名/MXID、密码。
- 会话恢复、退出登录、access token 刷新。
- Matrix HTTP：登录、sync、房间列表、消息历史、发送、已读回执。
- Home / Direct 房间列表。
- 房间详情：基础时间线、纯文本发送、返回。
- 设置壳：账号信息、Homeserver、设备、退出登录。
- Fake Engine：预览、单元测试、UI 全流程验证。

## 2. 传输层：Chrome/WebView

由于 `matrix.s0cl.top` 的 WAF 只接受 Chrome 的 TLS ClientHello，rustls / OpenSSL / Java TLS 都会被重置，M1 增加：

- `WebViewTransport`：使用 WebView 的 Chrome 网络栈。
- 本地 HTML 通过 `loadDataWithBaseURL(homeserverUrl, ...)` 以服务器 origin 加载。
- JS `fetch` 调用 `/_matrix/*`，同源、无 CORS。
- JS bridge `CinnyNative.onResponse(id, status, body)` 回到 Kotlin。
- `WebViewMatrixEngine` 基于该 transport 实现 M1 Matrix API。
- M1 实现曾保留 `RustMatrixEngine`；M2 切换 WebView 后已移除该类与 Android rust-sdk 依赖，`libs.versions.toml` 中保留 alias 供 M3 重新引入。

### TLS 指纹证据

| 客户端 | 结果 |
|---|---|
| Chrome Headless（全新 profile + cache-busting） | ✅ 成功返回 versions JSON；netlog 显示直连、HTTP/2 |
| curl / OpenSSL | ❌ TLS 握手重置，`write:errno=10054` |
| Android HttpsURLConnection（带浏览器 UA） | ❌ `SocketException: Connection reset` |
| matrix-rust-sdk（带浏览器 UA） | ❌ `ConnectionReset` |

UA 属于 HTTP 层，无法改变 TLS ClientHello；因此改用 WebView/Chrome 网络栈。

## 3. 构建与测试

```text
kotlin build -p android   -> Build successful
kotlin test -p android    -> 15 tests, 0 failed
```

测试分布：

- `DesignTokensTest`：5/5
- `MainDestinationTest`：2/2
- `MatrixContractTest`：2/2
- `InMemoryMatrixEngineTest`：3/3
- `PlatformTest` / `PlatformAndroidTest`：3/3

## 4. 真实服务器验证（WebView 传输）

账号：`@test:s0cl.top` / `<redacted>`

| 流程 | 结果 | 证据 |
|---|---|---|
| 登录 | ✅ | HTTP 200，保存 access_token/refresh_token/device_id |
| 首次 sync | ✅ | `sync status=200`，`applySync joinRooms=1` |
| 房间列表 | ✅ | `build/m0-screens/m1-final-rooms.png` |
| 打开时间线 | ✅ | `build/m0-screens/m1-final-room.png` |
| 发送消息 | ✅ | `hello_from_webview_transport`、`m1_acceptance_ok` |
| 会话恢复 | ✅ | 重启后自动进入 Home，房间列表恢复 |
| 退出登录 | ✅ | 清除会话，回到登录页 |

关键截图：

- 登录成功：`build/m0-screens/m1-webview-login.png`
- 房间列表：`build/m0-screens/m1-final-rooms.png`
- 时间线：`build/m0-screens/m1-final-room.png`
- 发送成功：`build/m0-screens/m1-final-sent.png`
- 我的/账号：`build/m0-screens/m1-me-real.png`
- 退出登录：`build/m0-screens/m1-logout-real.png`

## 5. 已知限制

- WebView 传输是 M1 临时方案，用于绕过服务器 TLS 指纹拦截。
- M3 的 E2EE/验证/密钥备份需要 rust SDK；届时必须让服务器放行 rustls，或实现 TLS 终止代理。
- 当前 WebView 传输未实现：媒体、E2EE、搜索、Space、完整消息类型、分页、通知。
- 客户端仍保留浏览器 User-Agent 和常见请求头，便于后续服务器调整。
- M0 的 `M0SmokeActivity` 与本地桥接资源已在 M1 收尾时移除，避免导出调试入口。

## 6. M1 验收结论

- Android 代码、构建、15 个单元测试：通过。
- Fake Engine UI 全流程：通过。
- 真实服务器登录、sync、房间列表、时间线、发送、会话恢复、退出登录：通过（使用 Chrome/WebView 传输）。
- rust SDK 直连仍受服务器 TLS 指纹拦截；待服务器放行后切回 rust SDK。
- M1 Android 验收通过，可进入 M2；iOS 仍只维护文档。
