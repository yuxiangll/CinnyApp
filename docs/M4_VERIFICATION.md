# M4 验证记录：响应式、RTL、字体缩放、性能与无障碍

> 日期：2026-11-01
> 设备：sdk_gphone16k_x86_64（Android 16 / API 37 / 420dpi / 16KB 页）

## 1. 响应式断点与双栏

沿用 `CinnyBreakpoints`（Compact < 600dp，Medium < 840dp，Expanded ≥ 840dp）。

| 宽度 | 布局 | 证据 |
| ---- | ---- | ---- |
| 305dp（800×1200px） | Compact：底部导航 + 单栏 | `build/m4-medium.png` |
| 686dp（1800×1200px） | Medium：左侧 NavigationRail（图标+文字）+ 单栏内容 | `build/m4-medium2.png` |
| 923dp（横屏 2424×1080px） | Expanded：Rail + 列表栏(320dp) + 详情栏；未选房间时显示“选择一个房间开始聊天” | `build/m4-land1.png`、`build/m4-land2.png`、`build/m4-land3.png` |

实现要点：
- `MainShell` 用 `BoxWithConstraints` 计算 `compact/expanded`；
- Expanded 下房间详情嵌入右栏（不再整屏替换），左侧列表保持可见；
- 新增 `CinnyDimensions.DetailListWidth = 320.dp`；侧栏抽成 `NavigationRail` 组合函数。

## 2. 横竖屏 / Insets

- 横竖屏切换正常（`user_rotation` 0/1 实测）。
- 根布局统一 `windowInsetsPadding(WindowInsets.safeDrawing)` + `imePadding()`，刘海、手势导航与键盘不会遮挡内容。

## 3. RTL

- 以 `ar-EG` 系统语言（`persist.sys.locale`）重启框架验证：整界面镜像（侧栏在右、列表右对齐、底部导航顺序反转）——`build/m4-rtl3.png`。
- 返回操作按方向翻转：`LayoutDirection.Rtl` 时显示“返回 ›”。
- 切回 `en-US` 后恢复正常（`build/m4-ltr5.png`）。
- 注意：模拟器 `debug.force_rtl` 系统属性会强制 RTL，验证后需 `setprop debug.force_rtl false` 并重启。

## 4. 字体缩放

- `font_scale = 1.3`：列表、标题、导航均随字号放大且不重叠/截断——`build/m4-font13.png`。

## 5. 性能

- 时间线使用 `LazyColumn` + 稳定 key（eventId），滚动按需组合。
- 新增 `ImageCache`（`LruCache<String, ImageBitmap>`，24 张）避免滚动时重复下载/解码。
- 大文件传输已改为 192KB 分块（M2 收尾），19MB/4.6MB 媒体不再受 Binder 限制。
- 冷启动：`am start -W` → `TotalTime: 1747ms`（debug 包、模拟器）。

## 6. 无障碍

- 关键控件触控热区 ≥ 48dp（`CinnyDimensions.MinTouchTarget`）：表情、发图、发文件、下载。
- 为图标型控件补充 `contentDescription`：表情 / 发送图片 / 发送文件；侧栏图标带标题。
- 侧栏与底部导航同时提供图标 + 文字标签，避免仅图标歧义。

## 7. 顺带修复

- **E2EE 解密句柄生命周期**：之前 `decrypt()` 在 `finally` 里销毁聚焦 timeline 后才读取
  `item.content`，导致消息显示“Timeline object has already been destroyed”；
  现改为在销毁前完成内容读取（`descToEvent()`），并区分“自建 timeline”与“缓存 timeline”，避免误销毁。

## 8. 未完成

1. 折叠屏铰链区域 / 后置屏幕适配（需真机）；
2. TalkBack 全量走查（当前仅关键控件语义）；
3. 对比度审计（部分次要文字为 70% 透明度）；
4. Baseline Profile / 启动优化（规划在 M5）；
5. 大列表图片缓存上限与内存峰值调优。
