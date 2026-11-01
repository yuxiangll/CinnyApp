package top.s0cl.cinny.platform

import androidx.compose.runtime.Composable

data class PickedMedia(
    val bytes: ByteArray,
    val mimeType: String,
    val fileName: String,
)

@Composable
expect fun rememberMediaPicker(onPicked: (PickedMedia) -> Unit): () -> Unit

@Composable
expect fun rememberFilePicker(onPicked: (PickedMedia) -> Unit): () -> Unit
