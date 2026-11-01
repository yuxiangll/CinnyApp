package top.s0cl.cinny.platform

import androidx.compose.runtime.Composable

@Composable
actual fun rememberMediaPicker(onPicked: (PickedMedia) -> Unit): () -> Unit = {}


@Composable
actual fun rememberFilePicker(onPicked: (PickedMedia) -> Unit): () -> Unit = {}
