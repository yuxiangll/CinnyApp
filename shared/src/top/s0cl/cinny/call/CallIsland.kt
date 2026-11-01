package top.s0cl.cinny.call

import androidx.compose.runtime.Composable

/**
 * M3：通话能力岛。
 *
 * Element Call 以 widget 形式运行在 WebView 中，宿主负责：
 *  - 加载 widget 页面并提供 widget API 桥（toWidget / fromWidget）；
 *  - 摄像头/麦克风权限代理；
 *  - 音频焦点与画中画（Android）。
 *
 * 未配置 Element Call 地址时，加载本地 `call_stub.html` 演示页，
 * 用于验证 widget 桥、权限与 PiP 链路。
 */
@Composable
expect fun CallIslandHost(
    url: String,
    roomId: String,
    onClose: () -> Unit,
)
