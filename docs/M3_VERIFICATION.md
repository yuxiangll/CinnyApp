# M3 验证记录：E2EE、推送、通话与开发者工具

> 日期：2026-11-01
> 账号：`@test:s0cl.top` / `@test2:s0cl.top`（密码 `<redacted>`）
> 服务器：`https://matrix.s0cl.top`（`s0cl.top` 的 Matrix 端点）
> 设备：`sdk_gphone16k_x86_64`（Android 16 / API 37 / 16KB 页），`top.s0cl.cinny`

## 1. 范围

M3 计划项：E2EE、SAS 验证、交叉签名、密钥备份、Secret Storage、推送、通话能力岛（Element Call widget）、开发者工具。

## 2. 关键架构：本地中继 + rust-sdk

**问题**：`s0cl.top` 前置 WAF 只放行 Chrome 的 TLS ClientHello；`curl/OpenSSL/JSSE/rustls` 在握手阶段被 RST。
M3 需要 matrix-rust-sdk 做 Olm/Megolm，而它内置 rustls，无法直连。

**方案**：

```
rust-sdk ──明文 HTTP──▶ 127.0.0.1:<随机端口> ──WebView fetch──▶ matrix.s0cl.top（Chromium TLS）
```

- `matrix/.../net/LocalRelayServer.kt`：最小 HTTP/1.1 服务端（请求行 + 头部 + Content-Length / chunked / 100-continue，响应 `Connection: close`）。
- `WebViewTransport.relay(...)`：把中继收到的请求转成 WebView 内的一次 `fetch`（含二进制 body/响应，base64 通过 JS bridge 传递）。
- `MatrixCryptoService`：用 `ClientBuilder().homeserverUrl("http://127.0.0.1:<port>")` 构建 rust-sdk 客户端，`restoreSession` 复用 M1 登录得到的 access token。

### 2.1 构建配置探测（重要）

`ClientBuilder` 的 5 种组合实测（日志 tag `CryptoProbe`）：

| variant | 配置 | 结果 |
| ------- | ---- | ---- |
| 1 | `homeserverUrl` + `serverName` | ❌ `ServerUnreachable`：强制对 `s0cl.top` 做 `.well-known`（rustls → 被 RST） |
| 2 | variant 1 + `disableWellKnownLookup(true)` | ❌ `WellKnownLookupDisabled` |
| 3 | `serverNameOrHomeserverUrl("s0cl.top")` | ❌ 同上 `.well-known` |
| 4 | `serverName` + `disableWellKnownLookup` | ❌ `WellKnownLookupDisabled` |
| 5 | **只设置 `homeserverUrl`** | ✅ 构建成功（`server=null`，服务端名称由用户 ID 推导） |

结论：**只能使用 variant 5**。

### 2.2 房间 ID 形式

因为 `server=null`，rust-sdk 内部房间 ID 为“本地形式”（不含 `:server`），而 `/sync`、`/messages` 返回完整 ID。
`MatrixCryptoService.localRoomId()` 负责两者互转（`!xxx:s0cl.top → !xxx`）。

## 3. E2EE 验证结果

### 3.1 设备与会话

| 项 | 结果 | 证据 |
| -- | ---- | ---- |
| rust-sdk 客户端构建 / 会话恢复 | ✅ | `crypto attached: relay=… store=/data/user/0/top.s0cl.cinny/files/matrix-sdk-store` |
| classic sync（`client.syncV2`） | ✅ | `rust-sdk sync #N rooms=4` |
| 设备密钥上传 | ✅ | `device_one_time_keys_count.signed_curve25519 = 50` |
| 交叉签名（`autoEnableCrossSigning`） | ✅ | 设备状态 `已验证`（截图 `build/m3-crypto1.png`） |
| 设备指纹展示 | ✅ | Ed25519 / Curve25519 指纹见截图 |

> 注：`SyncService`/`RoomListService` 依赖 sliding sync（`VersionIsMissing`），因此改用 `client.syncV2(SyncSettingsV2, listener)` 的 classic sync 循环。

### 3.2 密钥备份与 Secret Storage

| 项 | 结果 | 证据 |
| -- | ---- | ---- |
| `enableRecovery()` 引导 Secret Storage + 备份 | ✅ | 备份/恢复状态 `已启用`，恢复密钥 `<redacted>`（截图 `build/m3-backup1.png`） |
| 服务器账号数据 | ✅ | `/sync` account_data 出现 `m.secret_storage.key.*`、`m.key_backup.enabled=true` |
| 新设备恢复（`recover()`） | ✅ | 恢复密钥输入 → 恢复状态从 `不完整` → `已启用` |
| 备份状态监听 | ✅ | `backupState` / `recoveryState` / `verificationState` 监听器接入 |

### 3.3 加密消息收发

| 场景 | 结果 | 证据 |
| ---- | ---- | ---- |
| 加密房间历史（他人消息） | ✅ 正确显示“🔒 缺少会话密钥” | `build/m3-room-yes1.png` |
| 本机加密发送 → 本机解密显示 | ✅ | `m3_e2ee_roundtrip` 显示“🔒 已解密”，`build/m3-room-e2ee.png` |
| 跨账号双向 E2EE 对话 | ✅ | 见下 |

**跨账号双向测试**（一键测试按钮，截图 `build/m4-conv6.png`）：

```
① 第二账号登录：@test2:s0cl.top
② 加密房间：!ewFIG1RzY-XBnwMyXDMjS_xwhDHVwHHTvcJ5BziaiZo
③ 第二账号已加入（第二客户端 / 主客户端均已同步）
④ test2 加密发送：$6WYvoTbXqOqUsubumLriHIPYNyxOvwnlCC56EaepJVyE
⑤ test 加密发送：$io_fQcW-PhCfQGD_a8EofPyf8JBx9YRtSR6dNBylUmE
⑥ test 解密 test2 的消息：hello_from_test2
⑦ test2 解密 test 的消息：hello_from_test
```

实现方式：进程内启动**第二个** rust-sdk 客户端（独立 relay、独立 SQLite 存储、独立设备），
主账号建加密房并邀请，第二账号加入后双方各发一条 Megolm 加密消息，再各自解密对方消息。

### 3.4 SAS 验证

- `SessionVerificationController` + `SessionVerificationControllerDelegate` 已接入（请求、接受、SAS 表情、确认/拒绝、取消/失败）。
- UI：加密页“SAS 设备验证”区展示状态、表情/数字、确认按钮。
- 当前账号只有一个在线设备，**未做双设备人工比对**（需另一台设备配合）。

## 4. 推送

| 项 | 结果 | 证据 |
| -- | ---- | ---- |
| 通知渠道（消息 / 提及 / 通话） | ✅ | `dumpsys notification` 显示 `messages`(IMPORTANCE_DEFAULT)、`mentions`、`calls`(HIGH) |
| 通知权限 | ✅ | `POST_NOTIFICATIONS granted=true`，启动时请求 |
| 本地通知（含加密消息解密后通知） | ✅ | 通知栏 5 条：`M3 E2EE 对话测试 @test2:s0cl.top: 加密消息`、`yes @admin:s0cl.top: …`（`build/m3-notifications.png`） |
| 当前房间抑制 / 本人消息抑制 | ✅ | 打开房间时 `activeRoomId` 抑制通知 |
| HTTP pusher 注册 API | ⚠️ 服务器不支持 | `POST /_matrix/client/v3/pushers` 返回 `405 M_UNRECOGNIZED`；`GET /pushers` 正常（0 个） |
| UnifiedPush topic 生成/持久化 | ✅ | `cinny-<random>` 生成并保存到设置 |

结论：客户端侧推送（渠道、通知、pusher 注册代码、topic 管理）完成；
**服务端未提供 pusher 接口**，因此没有“后台离线推送”链路；需在支持 pusher 的服务器或自建推送网关上复测。

## 5. 通话能力岛（Element Call widget）

| 项 | 结果 | 证据 |
| -- | ---- | ---- |
| WebView widget 宿主 + `toWidget` 上行桥 | ✅ | stub 页面 `toWidget: supported_api_versions` 显示在宿主日志（`build/m4-call5.png`） |
| 摄像头/麦克风权限代理 | ✅ | 系统权限弹窗由 `WebChromeClient.onPermissionRequest` 触发并授权 |
| 音频焦点（VOICE_COMMUNICATION） | ✅ | `AudioSvc.FocusCtrl: requestAudioFocus() … USAGE_VOICE_COMMUNICATION` |
| 画中画 | ⚠️ 未生效 | 该模拟器镜像 `mDisablePip=true`，`enterPictureInPictureMode` 无效果 |
| 真实 Element Call | ⏳ 未部署 | 服务器未提供 Element Call 端点；使用 `androidApp/assets/call_stub.html` 验证桥接 |

配置入口：我的 → 通话 → 通话能力岛；`AppSettings.callUrl` 留空时加载本地 stub。

## 6. 开发者工具

| 项 | 结果 |
| -- | ---- |
| 发送任意消息事件（`PUT /rooms/{id}/send/{type}/{txn}`） | ✅ |
| 发送状态事件（`PUT /rooms/{id}/state/{type}/{state_key}`） | ✅ |
| 账号数据写入/读取（`PUT/GET /user/{id}/account_data/{type}`） | ✅ |
| 通过 rust-sdk 发送（加密路径） | ✅ |
| 一键双账号对话测试 | ✅ |
| 401 自动重试 | ✅ `withAuthRetry`（token 5 分钟过期，rust-sdk 与 WebView 双通道刷新） |

## 7. 集成修复记录

1. **token 轮换**：rust-sdk 自动刷新 access/refresh token。新增 `ClientSessionDelegate`：
   - SDK 刷新 → 回写内存 + SharedPreferences + `SessionState`；
   - WebView 刷新 → `crypto.updateSession()`（SDK 已初始化时忽略 `AlreadyInitializedError`）。
2. **设备切换清理**：重新登录得到新 `device_id` 时清空 rust-sdk 存储，避免复用旧设备密钥。
3. **退出登录**：`crypto.wipe()` 清理 E2EE 存储。
4. **解密窗口**：事件可能不在 live 窗口，使用 `room.timelineWithConfiguration(TimelineFocus.Event(eventId))` 精确读取。

## 8. 已知问题 / 限制

1. 中继为 HTTP/1.1 单请求连接（`Connection: close`），吞吐低于原生传输；大文件二进制经 base64 传递受 Binder 限制。
2. 服务器不支持 HTTP pusher（405），无后台离线推送；FCM 在无 GMS 环境不可用。
3. 模拟器镜像禁用 PiP；真机需复测。
4. Element Call 未部署，通话仅验证了能力岛宿主链路。
5. SAS 双设备比对、跨用户密钥请求（`m.room_key_request`）依赖真实第二设备。
6. `rust-sdk` 存储未加密（sqlite 明文）；后续可启用 passphrase。
7. 本机中继监听 127.0.0.1，仅进程内可见。

## 9. 复现步骤（Android）

1. `./kotlin build -p android`，安装 `build/tasks/_androidApp_buildAndroidDebug/gradle-project-debug.apk`。
2. 登录 `@test:s0cl.top`（服务器填 `s0cl.top`）。
3. 我的 → E2EE 与验证：查看设备指纹；点“启用密钥备份”获得恢复密钥；
4. 我的 → 开发者工具 → 一键双账号对话测试（第二账号 `test2/<redacted>`）→ 查看 ①–⑦ 报告。
5. 我的 → 推送：发送消息后查看通知栏。
6. 我的 → 通话：打开能力岛，观察 `toWidget` 桥日志与权限弹窗。
