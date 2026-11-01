package top.s0cl.cinny

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import top.s0cl.cinny.matrix.InMemoryMatrixEngine
import top.s0cl.cinny.matrix.MatrixEngine
import top.s0cl.cinny.matrix.WebViewMatrixEngine
import top.s0cl.cinny.push.NotificationHelper
import top.s0cl.cinny.settings.AndroidSettingsStore

class MainActivity : ComponentActivity() {
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var engine: MatrixEngine
    private var webEngine: WebViewMatrixEngine? = null
    private lateinit var settingsStore: AndroidSettingsStore
    private lateinit var notifications: NotificationHelper

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* 结果在发送通知时再检查 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        settingsStore = AndroidSettingsStore(applicationContext)
        notifications = NotificationHelper(applicationContext)
        notifications.ensureChannels()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        engine = if (intent.getBooleanExtra(EXTRA_FAKE_ENGINE, false)) {
            InMemoryMatrixEngine()
        } else {
            WebViewMatrixEngine(this, engineScope) { event ->
                if (settingsStore.settings.value.notificationsEnabled) {
                    if (event.isCall) {
                        notifications.notifyCall(event.roomId, event.roomName, event.senderId ?: "未知用户")
                    } else {
                        notifications.notifyMessage(
                            roomId = event.roomId,
                            roomName = event.roomName,
                            sender = event.senderId ?: "未知用户",
                            body = event.body,
                            highlight = event.highlight,
                        )
                    }
                }
            }.also { webEngine = it }
        }
        setContent {
            App(engine = engine, settingsStore = settingsStore)
        }
    }

    override fun onDestroy() {
        webEngine?.close()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_FAKE_ENGINE = "top.s0cl.cinny.extra.FAKE_ENGINE"
    }
}
