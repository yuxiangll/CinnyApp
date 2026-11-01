# M2 验证记录

日期：2026-11-01  
环境：Windows 11 / Kotlin Toolchain 0.13.0 / Android Emulator 1080x2424 @420dpi  
测试账号：`@test:s0cl.top`

## 1. 已完成功能

### Spaces

- 解析 `m.room.create` 的 `m.space` 类型；
- 解析 `m.space.child` 子房间；
- Spaces Tab 空间列表；
- 点击空间进入 Lobby，展示已加入子房间；
- 点击子房间进入房间时间线。

### 消息操作

- 长按消息打开操作面板；
- 回复：composer 显示回复预览，发送时写入 `m.in_reply_to`；
- 编辑：发送 `m.replace`，本地更新并显示 `(已编辑)`；
- 删除：调用 redact，本地移除；
- 表情回应：发送 `m.reaction`，同步聚合显示；
- 置顶/取消置顶：读写 `m.room.pinned_events`；
- 系统返回键：房间/搜索/探索/Space Lobby 可返回。

### 搜索

- 全服搜索 `POST /_matrix/client/v3/search`；
- 结果展示房间、发送者、消息体；
- 点击结果进入房间（暂不跳转到具体事件）。

### Inbox

- 解析 `rooms.invite`；
- 邀请列表，接受/拒绝；
- 未读房间列表。

### Explore

- 调用 `GET /_matrix/client/v3/publicRooms`；
- 展示公共房间；
- 加入房间并打开。

### 设置

- 主题切换：浅色/银色/深色/黄油/跟随系统；
- MIUIX 主题控制器随选择同步；
- 账号信息、退出登录。

### 消息渲染

- `formatted_body` 在 Android 使用 `AnnotatedString.fromHtml` 渲染；
- iOS 暂时回退纯文本；
- 回复、编辑、置顶、表情回应状态展示。

### 媒体（图片 + 通用文件）

- `+`：选择图片，上传到 `/_matrix/media/v3/upload`，发送 `m.image`；
- `📎`：选择任意文件，按 MIME 发送 `m.image`/`m.video`/`m.audio`/`m.file`；
- 时间线通过 `/_matrix/client/v1/media/download` 下载并显示图片；
- 视频/音频/文件显示占位标签，暂不播放/下载保存；
- 媒体自动加载开关已接入设置。

### 设置持久化

- `AndroidSettingsStore` 使用 SharedPreferences 持久化；
- 主题、自动加载媒体、Enter 发送、消息布局、通知开关；
- 重启后主题与设置保持。

### Spaces 管理

- 创建空间：`createRoom` + `creation_content.type=m.space`；
- 空间排序：`in.cinny.spaces` 账户数据，提供上移/下移；
- 拖拽排序手势未实现，使用上移/下移作为可访问替代。

### Thread

- 消息操作面板增加“在 Thread 中回复”；
- 发送 `m.thread` relation；
- 独立 Thread 视图未实现。

## 2. 验证结果

构建与测试：

```text
kotlin build -p android   -> Build successful
kotlin test -p android    -> 16 tests, 0 failed
```

实机验证：

| 功能 | 结果 | 截图 |
|---|---|---|
| 首页搜索/探索入口 | ✅ | `build/m0-screens/m2-home.png` |
| 消息操作面板 | ✅ | `build/m0-screens/m2-actions.png` |
| 表情回应 | ✅ | `build/m0-screens/m2-reaction5.png` |
| 回复预览与发送 | ✅ | `build/m0-screens/m2-reply-preview.png`、`m2-replied.png` |
| 全服搜索 | ✅ | `build/m0-screens/m2-search-results3.png` |
| 深色主题 | ✅ | `build/m0-screens/m2-dark2.png` |
| Spaces 空状态 | ✅ | `build/m0-screens/m2-spaces.png` |
| Inbox 空状态 | ✅ | `build/m0-screens/m2-inbox.png` |
| Settings 主题列表 | ✅ | `build/m0-screens/m2-settings.png` |
| 主题持久化 | ✅ | `build/m0-screens/m2-settings-persisted.png` |
| 图片选择/上传/显示 | ✅ | `build/m0-screens/m2-picker2.png`、`m2-media-sent2.png` |

## 2.1 测试房间 `yes`

已使用用户提供的测试房间 `yes`：

- 成功发送 `m2_yes_send`；
- 长按消息打开操作面板；
- 表情回应成功，时间线显示 `😀 1  👍 3`；
- 回复预览与发送成功；
- 该房间历史中包含 `m.room.encrypted`（Megolm）事件；当前 M2 未实现 E2EE，无法解密他人消息，只能验证自己发送的明文消息。
- 因此 `yes` 房间中“看不到历史消息”是预期现象，M3 接入 rust SDK/E2EE 后才能解密。

截图：`build/m0-screens/m2-yes-sent.png`、`m2-yes-reaction.png`。

## 3. 未完成 / 已知限制

- 媒体：图片上传/下载/渲染已实现；视频/音频播放、文件下载保存、加密媒体未实现。
- 自定义 emoji/贴纸、URL preview、代码高亮未实现。
- Spaces：创建、上移/下移排序已实现；拖拽手势、嵌套空间折叠、添加已有房间未实现。
- 消息：独立 Thread 视图、编辑历史、完整表情回应聚合、权限判断、转发未实现。
- 搜索：房间/发送者过滤、跳转到具体事件未实现。
- Explore：公共房间依赖服务器联邦/目录，当前测试服务器可能返回空；UI 已具备空状态。
- 设置：已持久化主题/媒体/Enter/布局/通知开关；消息布局与通知行为尚未完全应用到 UI/系统通知。
- 媒体文件大小受 WebView base64 传输限制，大文件需改用原生流式上传。
- WebView 传输仍是 M1 的临时方案；M3 E2EE 需要 rust SDK 或服务器放行。

## 4. M2 状态

M2 为**部分完成**：

- 已交付 Spaces 只读+创建/排序、消息操作、图片/文件上传显示、搜索、Inbox、Explore、设置持久化、HTML 渲染、Thread 回复。
- 视频/音频播放、文件保存、Spaces 拖拽/嵌套、独立 Thread 视图、搜索过滤/跳转等仍待补齐。
- 因未达到完整 M2 范围，暂不进入 M3；等待确认是否接受当前 M2 切片或继续补齐。

---

# M2 补全记录（2026-11-01）

在 M3 验收完成后回补 M2 剩余项。以下为本次新增/修复内容与验证状态。

## 已补全

### 1. 媒体：播放、下载保存、加密媒体

- `MatrixEngine.loadMedia(roomId, item)`：统一入口
  - 普通媒体：走 WebView 传输下载；若事件带 `content.file.key/iv` 则用 **AES-CTR** 解密；
  - 加密媒体（`m.room.encrypted` 解出的 image/file/video/audio）：通过 rust-sdk
    `MessageType.Image/File/Video/Audio` 的 `MediaSource` + `client.getMediaContent(source)` 下载并解密。
- UI `MediaAttachment`（`shared/src@android/platform/MediaAttachment.android.kt`）：
  - `m.image`/`m.sticker`：解码显示，点击保存到系统“下载”目录（MediaStore，Android 10+ 免权限）；
  - `m.video`：下载到缓存后内联 `VideoView` 播放（含 MediaController）；
  - `m.audio`：下载后 `MediaPlayer` 播放/暂停 + 进度；
  - 其它文件：显示“下载”按钮，保存到 Downloads。
- 媒体渲染条件由 `isMediaMessage(item)` 判定（`m.image/m.video/m.audio/m.file/m.sticker`），
  避免把纯文本加密消息误判为文件。

### 2. Thread / 编辑历史 / 转发

- `MatrixEngine.thread(roomId, rootEventId)`：`GET /_matrix/client/v1/rooms/{id}/relations/{eventId}/m.thread`，
  独立 `ThreadScreen`（列表 + 回复框，发送带 `m.relates_to rel_type=m.thread`）。
- `editHistory(roomId, eventId)`：`.../relations/{eventId}/m.replace`，动作面板“编辑历史”弹窗展示历史版本。
- `forward(roomId, eventId, targetRoomId)`：动作面板“转发” → 房间选择弹窗 → 目标房间发送文本。
- 动作面板新增：“打开 Thread”“转发”“编辑历史”。

### 3. 搜索过滤与跳转

- 搜索页新增房间过滤 chips（前 8 个房间 + 全部）与发送者包含过滤。
- 点击结果 → `onOpenRoom(roomId, eventId)`；房间页 `loadEventContext()` 拉取
  `/rooms/{id}/context/{eventId}` 合并到时间线后滚动到目标事件。

### 4. 消息布局 / Enter 发送

- `Modern` / `Compact` / `Bubble` 三种布局真正应用到时间线：
  - Compact：发送者与正文同行、行距更小；
  - Bubble：自己的消息右对齐、带圆角背景，他人的左对齐。
- `Enter 发送` 开关生效：开启后 Enter（非 Shift）直接发送，Shift+Enter 换行。

### 5. Spaces

- 长按 `≡` 拖拽排序（松手写入 `in.cinny.spaces` 账户数据）；
- 嵌套空间识别与折叠/展开；
- Lobby 页“添加房间”：把已加入房间以 `m.space.child` 状态事件挂到空间下（写入 `via` 服务器名）。

### 6. 富文本 / 贴纸

- 新增 `renderMatrixHtml`（common）：支持 b/i/u/s、code/pre（等宽 + 背景）、a、
  `span data-mx-spoiler`（默认遮挡 █，点击整条消息展开）、br/p、常用 HTML 实体；
  matrix.to 用户链接按 mention 高亮。
- `m.sticker` 事件解析与展示（走媒体渲染）。

## 验证（模拟器 sdk_gphone16k_x86_64）

| 功能 | 结果 | 证据 |
| ---- | ---- | ---- |
| 消息布局切换 Bubble | ✅ | `build/m2-layout.png`、`build/m2-bubble2.png` |
| 动作面板（打开 Thread / 转发 / 编辑历史） | ✅ | `build/m2-sheet2.png` |
| Thread 视图打开 + 回复发送 | ✅（回复写入房间时间线） | `build/m2-thread4.png`、`build/m2-thread5.png` |
| Spaces 拖拽手柄 / 空间 Lobby | ✅ | `build/m2-spaces2.png`、`build/m2-lobby.png` |
| 向空间添加已加入房间 | ✅（“已添加：…”） | `build/m2-addroom3.png` |
| 弹窗/底部面板配色 | ✅（修复浅底浅字） | `build/m2-sheet2.png` |

> 媒体播放/保存与加密媒体路径已实现，但本次会话未在实机上逐项点验
> （图片选择器在模拟器上需要多次确认、视频/音频需本地素材）；列为待复验项。

## 附：大文件音频实测（4.6 MB MP3）

| 步骤 | 结果 | 证据 |
| ---- | ---- | ---- |
| 选择 `dongmian.mp3`（4.60 MB）| ✅ | `build/big-dl2.png` |
| 分块上传（24 × 192KB，规避 Binder 1MB 限制）| ✅ | 消息成功写入，`build/big-sent3.png` |
| 消息类型自动判定为 `m.audio` + MIME `audio/mpeg` | ✅ | 显示“播放”按钮与 `3:11` 时长 |
| 分块下载 + 解码 | ✅ | `0:00 / 3:11`（MediaPlayer 已 prepare）|
| 播放 | ✅ | 点击后显示“暂停”，进度 `0:04 / 3:11`（`build/big-play5.png`）|
| 文件消息另存到系统下载目录 | ✅ | 列表出现“→ 已保存”，`/sdcard/Download/` 新增 4.6MB 文件 |

**顺带修复**：
- 上传/下载改为**分块经 JS bridge 传输**（`nativeUploadChunk/Assemble`、`nativeFetchBytesChunked`、
  `onBytesChunk`），单块 192KB，彻底解除 4MB+ 媒体受 Binder 限制的问题。
- 文件选择器改用 `OpenableColumns.DISPLAY_NAME` 取文件名，并在 MIME 为空/`application/octet-stream`
  时按扩展名推断（mp3→audio/mpeg 等），避免音频被当作普通文件。
- 音频播放改为“播放/暂停”按钮（行内点击在 LazyColumn 中被父级 combinedClickable 抢占）。

> 注：`adb push` 中文文件名会丢扩展名（`冬眠.mp3` → `冬眠.`），测试时使用 ASCII 文件名 `dongmian.mp3`。

## 仍未完成（后续）

1. URL preview（`/_matrix/client/v1/media/preview_url`）；
2. 自定义 emoji / 贴纸包选择器；
3. 完整 Markdown（列表/表格/引用）与代码高亮；
4. 加密附件**发送**（目前仅支持接收与解密展示；发送仍走明文上传）；
5. 大文件原生流式上传（现为 base64 经 JS bridge）；
6. 表情回应聚合的完整同步语义、转发保留媒体、权限判断（power level）。

---

# M2 小项补全（2026-11-01，第二轮）

## 已补全

### 1. URL 预览

- 引擎：`urlPreview(url)` 调用 `/_matrix/client/v1/media/preview_url`，失败回退 `/_matrix/media/v3/preview_url`，
  按 URL 缓存；解析 `og:title / og:description / og:image / og:site_name`。
- UI：消息正文只包含一个链接时，在气泡下方显示预览卡片（缩略图 + 标题 + 描述 + 站点），点击展开/收起。
- **实测**：`s0cl.top` 返回 `403 {"errcode":"M_FORBIDDEN","error":"URL Previews are disabled"}`，
  客户端按“无预览”处理（不显示卡片），日志已记录：`preview url=… status=403`。

### 2. Emoji 选择器

- 输入框左侧 `😊` 按钮展开表情面板（20 个常用 emoji，横向滚动），点击追加到草稿；实测输入框得到 `😀https://matrix.org`。

### 3. 富文本增强（Markdown 子集）

`renderMatrixHtml` 新增：`ul/ol/li`（• / 1.）、`blockquote`（▎前缀）、`h1…h6`（加粗 + 换行）。
同时修复标签前缀冲突（`<span>` 被误判为 `<s>`、`<strong>` 被误判为 `<s>`）。
新增 `RichTextTest`（6 个用例）：加粗/斜体/实体、无序/有序列表、引用/标题、等宽代码、剧透遮挡与展开、mention 颜色。

### 4. 转发保留媒体

- `forward()`：媒体消息先 `loadMedia()`（必要时解密）→ 在目标房间重新 `sendImage()`；文本消息仍按文本转发。
- **实测**：把 4.6 MB 的 `dongmian.mp3` 从「Cinny M1 Test」转发到「Main」，目标房间出现可播放的音频消息（`build/m2r-main.png`）。

### 5. Power level 权限判断

- 引擎：`roomPermissions(roomId)` 读取 `m.room.power_levels`（users/events/state_default），计算
  `myPowerLevel / redactLevel / canRedactOthers`。
- UI：动作面板中「编辑」只对自己的消息显示；「删除」需为自己消息或 `canRedactOthers`。
- **实测**：
  - 自己的消息 → 面板含「编辑」「删除」（`build/m2r-sheet2.png`）；
  - 他人消息（test2）→ 面板无「编辑」（`build/m2r-neg2.png`）。

## 仍未完成

1. 自定义 emoji / 贴纸包选择器（`im.ponies.user_emotes` 等账号数据）；
2. 加密附件**发送**（当前仅接收/解密展示；转发也按明文上传）；
3. 表情回应完整聚合（目前依赖 sync 事件；历史消息的 `m.annotation` 未回填）；
4. 视频内联播放未实机复验（代码已实现，Downloads 里有 19 MB mp4 可测）；
5. Markdown 表格与代码语法高亮。
