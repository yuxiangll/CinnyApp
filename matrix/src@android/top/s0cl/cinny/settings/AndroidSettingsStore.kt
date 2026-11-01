package top.s0cl.cinny.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import top.s0cl.cinny.core.settings.AppSettings
import top.s0cl.cinny.core.settings.SettingsStore

class AndroidSettingsStore(context: Context) : SettingsStore {
    private val prefs = context.applicationContext.getSharedPreferences("cinny_settings", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(load())

    override val settings: StateFlow<AppSettings> = state.asStateFlow()

    override fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(state.value)
        state.value = next
        prefs.edit()
            .putString("theme", next.themeKind)
            .putBoolean("auto_load_media", next.autoLoadMedia)
            .putBoolean("send_on_enter", next.sendOnEnter)
            .putString("message_layout", next.messageLayout)
            .putBoolean("notifications", next.notificationsEnabled)
            .putBoolean("push_enabled", next.pushEnabled)
            .putString("push_topic", next.pushTopic)
            .putString("push_gateway", next.pushGateway)
            .putString("call_url", next.callUrl)
            .apply()
    }

    private fun load(): AppSettings = AppSettings(
        themeKind = prefs.getString("theme", "System") ?: "System",
        autoLoadMedia = prefs.getBoolean("auto_load_media", true),
        sendOnEnter = prefs.getBoolean("send_on_enter", false),
        messageLayout = prefs.getString("message_layout", "Modern") ?: "Modern",
        notificationsEnabled = prefs.getBoolean("notifications", true),
        pushEnabled = prefs.getBoolean("push_enabled", false),
        pushTopic = prefs.getString("push_topic", "") ?: "",
        pushGateway = prefs.getString("push_gateway", "https://ntfy.sh") ?: "https://ntfy.sh",
        callUrl = prefs.getString("call_url", "") ?: "",
    )
}
