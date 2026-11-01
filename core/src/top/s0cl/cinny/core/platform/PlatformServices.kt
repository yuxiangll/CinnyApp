package top.s0cl.cinny.core.platform

/** Platform capabilities shared by Android and iOS. iOS will provide Swift implementations. */
interface PlatformServices {
    val secureStorage: SecureStorage
    val mediaPicker: MediaPicker
    val notifier: Notifier
    val callHost: CallHost
}

interface SecureStorage {
    suspend fun put(key: String, value: String)
    suspend fun get(key: String): String?
    suspend fun remove(key: String)
}

data class PickedMedia(
    val uri: String,
    val mimeType: String?,
    val displayName: String?,
    val sizeBytes: Long?,
)

interface MediaPicker {
    suspend fun pickImages(maxItems: Int = 10): List<PickedMedia>
    suspend fun pickDocuments(): List<PickedMedia>
}

interface Notifier {
    fun showMessage(roomId: String, eventId: String?, title: String, body: String)
    fun cancel(roomId: String)
}

interface CallHost {
    fun openCall(roomId: String, video: Boolean)
    fun closeCall()
}
