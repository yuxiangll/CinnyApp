package top.s0cl.cinny

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import org.jetbrains.compose.reload.DevelopmentEntryPoint
import top.s0cl.cinny.app.CinnyApp
import top.s0cl.cinny.core.settings.SettingsStore
import top.s0cl.cinny.matrix.MatrixEngine

@Composable
@Preview
@DevelopmentEntryPoint
fun App(engine: MatrixEngine? = null, settingsStore: SettingsStore? = null) {
    CinnyApp(engine = engine, settingsStore = settingsStore)
}
