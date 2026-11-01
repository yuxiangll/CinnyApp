package top.s0cl.cinny.core.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertEquals

class DesignTokensTest {
    @Test
    fun lightThemeMatchesFolds272() {
        assertEquals(Color(0xFFF2F2F2), CinnyThemes.Light.background.container)
        assertEquals(Color(0xFFFFFFFF), CinnyThemes.Light.surface.container)
        assertEquals(Color(0xFF1858D5), CinnyThemes.Light.primary.main)
        assertEquals(Color(0x80000000), CinnyThemes.Light.other.focusRing)
    }

    @Test
    fun cinnyThemesMatchColorsCssTs() {
        assertEquals(Color(0xFFDEDEDE), CinnyThemes.Silver.background.container)
        assertEquals(Color(0xFF1245A8), CinnyThemes.Silver.primary.main)
        assertEquals(Color(0xFF1A1A1A), CinnyThemes.Dark.background.container)
        assertEquals(Color(0xFFBDB6EC), CinnyThemes.Dark.primary.main)
        assertEquals(Color(0xFF1A1916), CinnyThemes.Butter.background.container)
        assertEquals(Color(0xFF262621), CinnyThemes.Butter.surface.container)
    }

    @Test
    fun typeScaleMatchesFolds272() {
        assertEquals(14.sp, CinnyFontSize.T300)
        assertEquals(20.sp, CinnyLineHeight.T300)
        assertEquals(12.sp, CinnyFontSize.B300)
        assertEquals(18.sp, CinnyLineHeight.B400)
        assertEquals(35.sp, CinnyFontSize.H1)
        assertEquals(52.sp, CinnyLineHeight.D400)
    }

    @Test
    fun spacingAndRadiiMatchFolds272() {
        assertEquals(4.dp, CinnySpacing.S100)
        assertEquals(20.dp, CinnySpacing.S500)
        assertEquals(32.dp, CinnySpacing.S700)
        assertEquals(8.dp, CinnyRadii.R400)
        assertEquals(4.dp, CinnyBorderWidth.B700)
    }

    @Test
    fun mobileLayoutTokensMatchM0Plan() {
        assertEquals(750, CinnyBreakpoints.WebMobilePx)
        assertEquals(1124, CinnyBreakpoints.WebTabletPx)
        assertEquals(600.dp, CinnyBreakpoints.CompactMax)
        assertEquals(840.dp, CinnyBreakpoints.MediumMax)
        assertEquals(48.dp, CinnyDimensions.MinTouchTarget)
        assertEquals(64.dp, CinnyDimensions.ListRow)
    }
}
