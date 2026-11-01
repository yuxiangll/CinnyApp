package top.s0cl.cinny.core.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppSettings(
    val themeKind: String = "System",
    val autoLoadMedia: Boolean = true,
    val sendOnEnter: Boolean = false,
    val messageLayout: String = "Modern",
    val notificationsEnabled: Boolean = true,
    /** M3：推送（UnifiedPush / HTTP pusher 网关）。 */
    val pushEnabled: Boolean = false,
    val pushTopic: String = "",
    val pushGateway: String = "https://ntfy.sh",
    /** M3：通话能力岛使用的 Element Call 地址（可留空，使用房间 widget）。 */
    val callUrl: String = "",
)

interface SettingsStore {
    val settings: StateFlow<AppSettings>
    fun update(transform: (AppSettings) -> AppSettings)
}

class InMemorySettingsStore : SettingsStore {
    private val state = MutableStateFlow(AppSettings())
    override val settings: StateFlow<AppSettings> = state.asStateFlow()
    override fun update(transform: (AppSettings) -> AppSettings) {
        state.value = transform(state.value)
    }
}
