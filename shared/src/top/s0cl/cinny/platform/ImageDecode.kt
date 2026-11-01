package top.s0cl.cinny.platform

import androidx.compose.ui.graphics.ImageBitmap

expect fun decodeImageBitmap(bytes: ByteArray): ImageBitmap?
