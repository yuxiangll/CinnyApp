package top.s0cl.cinny.platform

import androidx.compose.runtime.Composable

@Composable
expect fun PlatformBackHandler(enabled: Boolean = true, onBack: () -> Unit)
