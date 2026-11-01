package top.s0cl.cinny.platform

import androidx.compose.runtime.Composable

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    // iOS uses the system swipe-back gesture / navigation stack.
}
