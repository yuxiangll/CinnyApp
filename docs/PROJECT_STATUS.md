# jCinnyClient 移动端移植项目状态（M0–M5）

> 更新时间：2026-11-01
> 项目根目录：`C:\Users\Administrator\Desktop\CinnyClient`
> Web 基准：`cinny/`（未修改）
> 当前阶段：M2 已补全、M3 已完成、M4 进行中（响应式/RTL/性能/无障碍主体完成）

## 1. 阶段总览

| 阶段 | 状态            | 说明                                                                                             |
| ---- | --------------- | ------------------------------------------------------------------------------------------------ |
| M0   | ✅ 完成         | 工程基线、模块拆分、设计 token、导航壳、WebView 桥接、16KB/ABI                                   |
| M1   | ✅ Android 完成 | 认证、会话、sync、房间列表、时间线、纯文本发送、已读回执                                         |
| M2   | ✅ 已补全（少量遗留）| 媒体播放/保存/加密媒体、Thread 视图、转发、编辑历史、搜索过滤与跳转、消息布局与 Enter、Spaces 拖拽/嵌套/添加房间、富文本与贴纸；遗留见 §5 |
| M3   | ✅ 完成（受限项）| E2EE、密钥备份、SAS 验证 UI、推送、通话能力岛、开发者工具；受限项见 §6                                          |
| M4   | 🟡 进行中       | 600/840dp 断点与双栏、横竖屏、RTL、字体缩放、图片缓存、基础无障碍已完成；折叠屏/TalkBack 待补 |
| M5   | ⬜ 未开始       | Release、签名、TestFlight/App Store、发布文档                                                    |

## 2. 构建与依赖

- 构建：Kotlin Toolchain 0.13.0（`kotlin` / `kotlin.bat`）
- 日志：统一输出到 `logs/`（已加入 `.gitignore`）；封装脚本 `scripts/build.sh|test.sh|run.sh` 与 `scripts/build.bat|test.bat`
- 模块：`core`、`matrix`、`shared`、`androidApp`、`iosApp`
- Android：`compileSdk 37`、`targetSdk 37`、`minSdk 33`（`miuix-blur` 限制）
- ABI：`arm64-v8a`、`x86_64`
- 版本目录：`libs.versions.toml`

关键版本：

```toml
androidx-activity-compose = "1.13.0"
androidx-lifecycle = "2.11.0"
androidx-webkit = "1.18.0"   # 当前仅 catalog，暂未使用
kotlinx-coroutines = "1.11.0"
matrix-rust-sdk = "26.10.23" # 当前仅 catalog，M2 已移除实际依赖
miuix = "0.9.4"
```

MIUIX 依赖保持不变：

```text
miuix-core / miuix-ui / miuix-preference / miuix-icons
miuix-blur / miuix-squircle / miuix-nav / miuix-shader
```

> 构建环境曾因 Maven Central/Google 连接超时，在 `module.yaml` 中配置了华为云镜像；正式 CI 可替换或移除。

## 3. M0：工程基线

### 完成内容

- 模块拆分：`core` / `matrix` / `shared`，`project.yaml` 注册。
- 设计 token 生成器：`tools/design/generate-design-tokens.mjs`，从 folds 2.7.2 与 `cinny/src/colors.css.ts` 生成 `CinnyColors.kt`、`CinnyScales.kt`。
- 响应式导航壳：底部导航、横屏 NavigationRail、深色模式。
- `MatrixEngine` / `PlatformServices` 接口。
- M0 阶段曾接入 matrix-rust-sdk Android AAR，验证 native lib 加载；M2 改用 WebView 传输后移除该依赖。
- WebView JS bridge 最小握手。
- 16KB / ABI 校验：`zipalign -c -P 16` 通过。
- iOS 接口文档。

### 验证结果

| 项                         | 结果                                                                           |
| -------------------------- | ------------------------------------------------------------------------------ |
| `kotlin build`           | ✅                                                                             |
| `kotlin test`（M0 时点） | 12 tests 全部通过                                                              |
| `show settings`          | ✅ compileSdk 37 / targetSdk 37 / minSdk 33 / abiFilters                       |
| 设计 token `--check`     | ✅                                                                             |
| 16KB 对齐                  | ✅                                                                             |
| WebView bridge             | ✅`m0-bridge-handshake-ok`                                                   |
| 截图                       | `build/m0-screens/nav-compact.png`、`nav-wide-v3.png`、`nav-dark-v2.png` |

> M0 的 `M0SmokeActivity` 与本地桥接资源已在 M2 收尾时移除，避免导出调试入口；日志与截图保留。

## 4. M1：认证与基础消息

### 完成内容

- 登录页：Homeserver、用户名/MXID、密码。
- 启动时会话恢复、退出登录、access token 自动刷新。
- 真实 Matrix API：
  - `/_matrix/client/v3/login`
  - `/_matrix/client/v3/sync`
  - 房间列表
  - `/_matrix/client/v3/rooms/{roomId}/messages`
  - `/_matrix/client/v3/rooms/{roomId}/send/m.room.message/{txnId}`
  - 已读回执
- Home / Direct 房间列表。
- 房间详情：基础时间线、纯文本发送、返回。
- 设置壳：账号信息、Homeserver、设备、退出登录。
- 传输层：`WebViewTransport` + `WebViewMatrixEngine`，使用 Chrome/WebView 网络栈。
- Fake Engine：`InMemoryMatrixEngine`，用于预览/单测/UI 验证。

### 关键背景：服务器 TLS 指纹

- Chrome/WebView 可连接 `matrix.s0cl.top`；
- curl / OpenSSL / Java TLS / rustls 在 TLS 握手阶段被重置；
- 因此 M1 使用 WebView/Chrome 传输；
- UA 与常见请求头已加入，但无法解决 TLS 层拦截。

### 验证结果

| 功能      | 结果 | 证据                                                   |
| --------- | ---- | ------------------------------------------------------ |
| 真实登录  | ✅   | HTTP 200，保存 token/device                            |
| 首次 sync | ✅   | `sync status=200`                                    |
| 房间列表  | ✅   | `build/m0-screens/m1-final-rooms.png`                |
| 时间线    | ✅   | `build/m0-screens/m1-final-room.png`                 |
| 发送文本  | ✅   | `hello_from_webview_transport`、`m1_acceptance_ok` |
| 会话恢复  | ✅   | 重启后进入 Home                                        |
| 退出登录  | ✅   | `build/m0-screens/m1-logout-real.png`                |
| 测试      | ✅   | M1 时点 15 tests 全部通过                              |

文档：`docs/M1_VERIFICATION.md`

## 5. M2：当前切片（已记录）

### 已完成

#### Spaces

- 解析 `m.room.create` 的 `m.space`；
- 解析 `m.space.child`；
- Spaces 列表与 Lobby 子房间列表；
- 创建空间：`createRoom` + `creation_content.type=m.space`；
- 空间排序：`in.cinny.spaces` 账户数据，提供上移/下移；
- 拖拽排序手势、嵌套空间折叠、添加已有房间未实现。

#### 消息操作

- 长按消息操作面板；
- 回复：`m.in_reply_to`；
- 编辑：`m.replace`，本地 `(已编辑)`；
- 删除：redact；
- 表情回应：`m.reaction`，同步聚合；
- 置顶/取消置顶：`m.room.pinned_events`；
- Thread：操作面板“在 Thread 中回复”，发送 `m.thread`；独立 Thread 视图未实现。

#### 媒体

- `+` 选择图片，发送 `m.image`；
- `📎` 选择任意文件，按 MIME 发送 `m.image`/`m.video`/`m.audio`/`m.file`；
- 图片下载并通过 Compose `Image` 显示；
- 视频/音频/文件显示占位标签，暂无播放/下载保存；
- 媒体自动加载开关已接入设置。

#### 搜索 / Inbox / Explore

- 全服搜索 `POST /_matrix/client/v3/search`；
- 搜索结果房间、发送者、消息体；点击进入房间；
- Inbox：邀请列表、接受/拒绝、未读房间；
- Explore：`GET /_matrix/client/v3/publicRooms`、加入房间；
- 搜索房间/发送者过滤、跳转具体事件未实现。

#### 设置

- `AndroidSettingsStore`（SharedPreferences）持久化：
  - 主题
  - 自动加载媒体
  - Enter 发送
  - 消息布局
  - 通知开关
- 主题同步 MIUIX 明暗色板；
- 重启后主题与设置保持；
- 消息布局/Enter/通知的实际行为尚未完全应用。

#### 消息渲染

- `formatted_body` 在 Android 使用 `AnnotatedString.fromHtml`；
- iOS 暂时回退纯文本；
- Markdown/mention/spoiler/代码高亮未完成。

#### 其他

- 系统返回键 `PlatformBackHandler`；
- WebView 请求超时修复；
- 搜索响应嵌套 `result` 解析修复；
- 二进制下载/上传桥接。

### 实机验证

测试账号：`@test:s0cl.top`
测试房间：`yes`

| 功能               | 结果 | 证据                                         |
| ------------------ | ---- | -------------------------------------------- |
| 发送消息           | ✅   | `build/m0-screens/m2-yes-sent.png`         |
| 表情回应           | ✅   | `build/m0-screens/m2-yes-reaction.png`     |
| 回复预览/发送      | ✅   | `m2-reply-preview.png`、`m2-replied.png` |
| 图片选择/上传/显示 | ✅   | `m2-picker2.png`、`m2-media-sent2.png`   |
| 全服搜索           | ✅   | `m2-search-results3.png`                   |
| 主题持久化         | ✅   | `m2-settings-persisted.png`                |
| 消息操作面板       | ✅   | `m2-actions.png`                           |
| Spaces 空状态      | ✅   | `m2-spaces.png`                            |
| Inbox 空状态       | ✅   | `m2-inbox.png`                             |
| Settings 列表      | ✅   | `m2-settings.png`                          |

> `yes` 房间历史包含 `m.room.encrypted`（Megolm）事件；M2 未实现 E2EE，无法解密他人消息，只能验证自己发送的明文/媒体消息。

### M2 补全（2026-11-01）

- 媒体：`MediaAttachment`（图片保存、视频内联播放、音频播放/暂停、文件下载到 Downloads），
  加密媒体经 rust-sdk `MediaSource` 下载解密；明文加密附件 AES-CTR 解密；
  上传/下载改为 192KB 分块经 JS bridge 传输，4.6MB MP3 实测可发送与播放。
- 消息：独立 `ThreadScreen`、转发（房间选择）、编辑历史弹窗、动作面板扩展。
- 搜索：房间 chips + 发送者过滤；结果点击 `loadEventContext` 后滚动到目标事件。
- 布局：Modern/Compact/Bubble 生效；`Enter 发送` 生效。
- Spaces：`≡` 长按拖拽排序、嵌套折叠、添加入已加入房间（`m.space.child`）。
- 富文本：`renderMatrixHtml`（mention 高亮、spoiler 遮挡/点击展开、等宽代码、列表/引用/标题）；`m.sticker` 渲染；
  新增 `RichTextTest` 6 个用例。
- URL 预览：`preview_url`（v1 + v3 回退、按 URL 缓存）；`s0cl.top` 关闭该功能（403），客户端按无预览处理。
- Emoji 选择器；转发保留媒体（重新上传）；`m.room.power_levels` 权限判断（编辑/删除按钮按权限显示）。

### M2 仍未完成

- 自定义 emoji / 贴纸包选择器（`im.ponies.user_emotes` 账号数据）；
- 加密附件**发送**（当前仅接收/解密展示，转发也按明文上传）；
- 表情回应完整聚合（历史消息 `m.annotation` 未回填）；
- 视频内联播放未实机复验；
- Markdown 表格与代码语法高亮。

文档：`docs/M2_VERIFICATION.md`（见文末“M2 补全记录”）

## 6. M3–M5：计划与状态

### M3：E2EE、推送、通话、开发者工具

状态：✅ 完成（部分项受服务器/模拟器限制，详见 `docs/M3_VERIFICATION.md`）。

完成内容：

- **传输层**：`LocalRelayServer` 本机 HTTP 中继 + `WebViewTransport.relay()`，让 rust-sdk（rustls）复用
  Chromium TLS 指纹绕过 WAF；`ClientBuilder` 仅 `homeserverUrl` 可用（5 组配置探测结论）。
- **E2EE**：matrix-rust-sdk 26.10.23，`restoreSession` + `syncV2` classic sync，
  交叉签名自动建立、设备指纹展示、加密房间收发与解密（含聚焦 timeline 解密历史事件）。
- **密钥备份 / Secret Storage**：`enableRecovery()` 生成恢复密钥；`recover()` 在新设备恢复。
- **SAS 验证**：`SessionVerificationController` + 委托回调 + 表情比对 UI（待双设备人工验证）。
- **双账号对话测试**：进程内第二个 rust-sdk 客户端（独立设备/存储/中继），
  一键完成“建加密房 → 邀请 → 加入 → 双方加密发送 → 互相解密”。
- **推送**：通知渠道（消息/提及/通话）、POST_NOTIFICATIONS、本地通知（加密消息解密后通知）、
  UnifiedPush topic 管理、HTTP pusher 注册 API（服务器返回 405，受限）。
- **通话能力岛**：WebView widget 宿主、`toWidget/fromWidget` 桥、摄像头/麦克风权限代理、
  音频焦点、画中画按钮（模拟器镜像禁用 PiP）。
- **开发者工具**：发送事件/状态事件/账号数据读写、rust-sdk 加密路径发送、401 自动重试。
- **token 一致性**：`ClientSessionDelegate` 回写刷新后的 token；重新登录清理旧设备加密存储。

### M3 已知限制

1. 服务器 `POST /_matrix/client/v3/pushers` 返回 `405 M_UNRECOGNIZED`，无后台离线推送。
2. 模拟器镜像 `mDisablePip=true`，画中画未能实测。
3. Element Call 未部署，通话仅验证宿主链路（本地 stub）。
4. 中继为单请求连接，大文件经 base64 传递受 Binder 限制。
5. rust-sdk SQLite 存储未加密。

### M4：响应式、平板/折叠屏、RTL、性能、无障碍

状态：🟡 进行中（详见 `docs/M4_VERIFICATION.md`）

已完成：

- 断点 600dp/840dp：Compact 底部导航、Medium 侧边 NavigationRail（图标+文字）、
  Expanded 列表-详情双栏（列表 320dp，详情内嵌，未选房间显示占位）；
- 横竖屏切换与 `safeDrawing`/`imePadding` insets；
- RTL：阿拉伯语环境整体镜像，返回操作按 `LayoutDirection` 翻转；
- 字体缩放 1.3 验证无破版；
- 图片 LRU 缓存（24 张）、时间线稳定 key；冷启动 `TotalTime ≈ 1.75s`（debug/模拟器）；
- 关键控件 48dp 触控热区与 contentDescription。

未完成：折叠屏铰链/后置屏幕、TalkBack 全量走查、对比度审计、Baseline Profile（M5）。

### M5：发布

计划：

- Android Release 签名、R8、Baseline Profile、AAB/ABI split、16KB 校验；
- 隐私清单、崩溃/性能监控、Beta 渠道；
- iOS 签名、TestFlight、App Store、APNs/CallKit 配置；
- 发布文档与合规。

状态：⬜ 未开始。

## 7. 验证汇总

### 构建与测试

```text
kotlin build -p android   -> Build successful
kotlin test -p android    -> 16 tests, 0 failed（M3 未改动 core/matrix 契约测试）
```

测试分布：

| 测试                               | 数量 |
| ---------------------------------- | ---- |
| DesignTokensTest                   | 5    |
| MainDestinationTest                | 2    |
| MatrixContractTest                 | 2    |
| InMemoryMatrixEngineTest           | 4    |
| PlatformTest / PlatformAndroidTest | 3    |

### 真实服务器

- 账号：`@test:s0cl.top`、`@test2:s0cl.top`（密码 `<redacted>`）
- 登录、sync、房间列表、时间线、发送、会话恢复、退出登录：✅
- 传输：WebView/Chrome（rust-sdk 经本机中继）
- 服务器 TLS 指纹拦截 rustls/Java/curl：仍存在（中继已绕过）
- `yes` 房间他人加密历史：缺少会话密钥（预期）；自建加密房间可正常解密

## 8. 已知问题与风险

1. **服务器 TLS 指纹/WAF**：WebView 传输可用；rust-sdk 通过本机中继复用 Chromium TLS。
2. **WebView 传输是临时方案**：大文件 base64、长连接、性能需后续优化。
3. **E2EE 已实现**：encrypted 事件可解密展示；历史他人消息因无密钥显示“缺少会话密钥”。
4. **媒体能力不完整**：视频/音频播放、文件下载保存、加密媒体未做。
5. **Spaces/Thread/Search 仍为部分实现**：拖拽、嵌套折叠、Thread 视图、搜索过滤/跳转未做。
6. **minSdk 33**：受 `miuix-blur` 限制；如需覆盖 Android 8–12，需要专项改造。
7. **构建镜像**：`module.yaml` 中配置了华为云 Maven 镜像；CI 可替换。
8. **Matrix rust SDK 已重新引入**（`matrix` 模块 `dependencies@android`），M3 起用于 E2EE。

## 9. 下一步建议

1. 补全 M2 剩余项，优先：
   - 视频/音频播放与文件保存；
   - Spaces 拖拽/嵌套；
   - 独立 Thread 视图；
   - 搜索过滤与跳转；
   - 消息布局/通知行为。
2. M3 受限项复测（需 M4/M5 前处理）：
   - 支持 HTTP pusher 的网关或 UnifiedPush distributor；
   - 真机验证画中画；
   - 部署 Element Call 后联调 widget；
   - 双设备 SAS 交叉签名。
3. iOS 仍只维护 `docs/ios/README.md`，不进行测试开发。

## 10. 文档索引

- `README.md`：项目介绍、构建 / 运行 / 测试说明
- `AGENTS.md`：面向 AI 编码助手的工程约定与提交检查清单
- `docs/M0_VERIFICATION.md`：M0 验证记录
- `docs/M1_VERIFICATION.md`：M1 验证记录
- `docs/M2_VERIFICATION.md`：M2 当前切片记录
- `docs/M3_VERIFICATION.md`：M3 验证记录（E2EE/推送/通话/开发者工具）
- `docs/ios/README.md`：iOS 唯一适配文档（按 M0–M5 维护）
- `docs/PROJECT_STATUS.md`：本文件，M0–M5 总览
