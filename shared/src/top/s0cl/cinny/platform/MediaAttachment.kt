package top.s0cl.cinny.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import top.s0cl.cinny.matrix.MatrixEngine
import top.s0cl.cinny.matrix.TimelineItem

/**
 * M2：消息附件渲染。
 *
 * - `m.image` / `m.sticker`：解码显示，可保存到下载目录；
 * - `m.video`：下载后内联播放；
 * - `m.audio`：下载后播放/暂停；
 * - 其他：显示下载按钮，保存到系统下载目录；
 * - 加密事件的附件通过 rust-sdk 的 MediaSource 下载并解密。
 */
@Composable
expect fun MediaAttachment(
    engine: MatrixEngine,
    roomId: String,
    item: TimelineItem,
    autoLoad: Boolean,
    modifier: Modifier = Modifier,
)
