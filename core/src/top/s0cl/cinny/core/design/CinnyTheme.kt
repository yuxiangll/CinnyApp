package top.s0cl.cinny.core.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp

enum class CinnyThemeKind {
    Light,
    Silver,
    Dark,
    Butter,
    System,
}

val LocalCinnyColors = staticCompositionLocalOf { CinnyThemes.Light }

@Composable
fun CinnyTheme(
    kind: CinnyThemeKind = CinnyThemeKind.System,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val colors = when (kind) {
        CinnyThemeKind.Light -> CinnyThemes.Light
        CinnyThemeKind.Silver -> CinnyThemes.Silver
        CinnyThemeKind.Dark -> CinnyThemes.Dark
        CinnyThemeKind.Butter -> CinnyThemes.Butter
        CinnyThemeKind.System -> if (systemDark) CinnyThemes.Dark else CinnyThemes.Light
    }
    CompositionLocalProvider(LocalCinnyColors provides colors, content = content)
}

/**
 * Web breakpoints come from cinny/src/app/hooks/useScreenSize.ts.
 * Mobile breakpoints follow the M0 plan and Android WindowSizeClass semantics.
 */
object CinnyBreakpoints {
    const val WebMobilePx = 750
    const val WebTabletPx = 1124
    val CompactMax = 600.dp
    val MediumMax = 840.dp
}

object CinnyDimensions {
    val MinTouchTarget = 48.dp
    val ListRow = 64.dp
    val BottomBar = 64.dp
    val TopBar = 56.dp
    val NavRailWidth = 96.dp
    /** M4：Expanded 双栏布局的列表栏宽度。 */
    val DetailListWidth = 320.dp
}
