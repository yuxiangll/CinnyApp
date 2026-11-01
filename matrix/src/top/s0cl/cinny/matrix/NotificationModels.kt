package top.s0cl.cinny.matrix

/** M3：一条可展示为系统通知的事件。 */
data class NotificationEvent(
    val roomId: String,
    val roomName: String,
    val senderId: String?,
    val body: String,
    val highlight: Boolean = false,
    val isCall: Boolean = false,
)

/** M3：服务器上已注册的推送器。 */
data class PusherInfo(
    val appId: String,
    val pushkey: String,
    val kind: String?,
    val url: String?,
    val appDisplayName: String?,
    val deviceDisplayName: String?,
)
