package top.s0cl.cinny.matrix

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.StateFlow

interface MatrixEngine {
    val session: StateFlow<SessionState>
    val syncState: StateFlow<SyncState>

    suspend fun restoreSession(): Result<Session>
    suspend fun login(server: String, credentials: Credentials): Result<Session>
    suspend fun logout(): Result<Unit>

    fun rooms(): Flow<List<RoomSummary>>
    fun spaces(): Flow<List<SpaceSummary>>
    fun invites(): Flow<List<RoomSummary>>
    fun timeline(roomId: String): Flow<TimelinePage>

    suspend fun send(roomId: String, draft: MessageDraft): Result<EventId>
    suspend fun edit(roomId: String, eventId: String, body: String): Result<Unit>
    suspend fun redact(roomId: String, eventId: String, reason: String? = null): Result<Unit>
    suspend fun toggleReaction(roomId: String, eventId: String, key: String): Result<Boolean>
    suspend fun setPinned(roomId: String, eventId: String, pinned: Boolean): Result<Unit>
    suspend fun sendReadReceipt(roomId: String, eventId: String, private: Boolean): Result<Unit>

    suspend fun mediaBytes(mxcUrl: String): Result<ByteArray>

    /** M2：下载媒体，并在需要时用事件里的 AES key/iv 解密（加密媒体）。 */
    suspend fun mediaBytes(mxcUrl: String, key: String?, iv: String?): Result<ByteArray> =
        mediaBytes(mxcUrl)

    /**
     * M2：按时间线条目加载媒体。
     * 加密事件走 rust-sdk 的 MediaSource（SDK 负责下载 + 解密）；普通事件走 WebView 传输。
     */
    suspend fun loadMedia(roomId: String, item: TimelineItem): Result<MediaPayload> =
        mediaBytes(item.mediaUrl.orEmpty()).map { MediaPayload(it, null, item.body) }
    suspend fun sendImage(roomId: String, bytes: ByteArray, mimeType: String, fileName: String): Result<EventId>

    suspend fun joinRoom(roomIdOrAlias: String): Result<String>
    suspend fun createSpace(name: String): Result<String>
    suspend fun reorderSpaces(spaceIds: List<String>): Result<Unit>

    /** M2：把已加入的房间挂到空间下（`m.space.child` 状态事件）。 */
    suspend fun addRoomToSpace(spaceId: String, roomId: String): Result<Unit> =
        Result.failure(UnsupportedOperationException("空间操作不可用"))
    suspend fun leaveRoom(roomId: String): Result<Unit>
    suspend fun acceptInvite(roomId: String): Result<Unit>
    suspend fun declineInvite(roomId: String): Result<Unit>

    suspend fun search(term: String, roomId: String? = null): Result<List<SearchResult>>
    suspend fun publicRooms(server: String? = null): Result<List<PublicRoom>>

    // ---- M3：E2EE / 验证 / 密钥备份 ----

    fun cryptoStatus(): StateFlow<CryptoStatus> = MutableStateFlow(CryptoStatus())

    fun verificationState(): StateFlow<VerificationUiState> = MutableStateFlow(VerificationUiState())

    /** 用 matrix-rust-sdk 解密一条 `m.room.encrypted` 事件。 */
    suspend fun decryptEvent(roomId: String, eventId: String): Result<DecryptedEvent> =
        Result.failure(UnsupportedOperationException("E2EE 未启用"))

    /** 启用密钥备份（Secret Storage + 服务器备份），返回恢复密钥。 */
    suspend fun enableKeyBackup(): Result<String> =
        Result.failure(UnsupportedOperationException("E2EE 未启用"))

    /** 用恢复密钥恢复备份与交叉签名。 */
    suspend fun restoreKeyBackup(recoveryKey: String): Result<Unit> =
        Result.failure(UnsupportedOperationException("E2EE 未启用"))

    /** 通过 rust-sdk 发送一条（在加密房间中自动加密的）消息。 */
    suspend fun sendEncryptedText(roomId: String, body: String): Result<EventId> =
        Result.failure(UnsupportedOperationException("E2EE 未启用"))

    suspend fun requestVerification(): Result<Unit> =
        Result.failure(UnsupportedOperationException("E2EE 未启用"))

    suspend fun acceptVerification(): Result<Unit> =
        Result.failure(UnsupportedOperationException("E2EE 未启用"))

    suspend fun startSasVerification(): Result<Unit> =
        Result.failure(UnsupportedOperationException("E2EE 未启用"))

    suspend fun approveSasVerification(): Result<Unit> =
        Result.failure(UnsupportedOperationException("E2EE 未启用"))

    suspend fun declineVerification(): Result<Unit> =
        Result.failure(UnsupportedOperationException("E2EE 未启用"))

    // ---- M3：开发者工具 ----

    suspend fun sendCustomEvent(roomId: String, eventType: String, contentJson: String): Result<DevEventResult> =
        Result.failure(UnsupportedOperationException("开发者工具不可用"))

    suspend fun sendStateEvent(
        roomId: String,
        eventType: String,
        stateKey: String,
        contentJson: String,
    ): Result<DevEventResult> = Result.failure(UnsupportedOperationException("开发者工具不可用"))

    suspend fun setAccountData(eventType: String, contentJson: String): Result<Unit> =
        Result.failure(UnsupportedOperationException("开发者工具不可用"))

    suspend fun getAccountData(eventType: String): Result<String> =
        Result.failure(UnsupportedOperationException("开发者工具不可用"))

    /** 通过 rust-sdk（加密层）往房间发送原始事件，用于验证加密发送路径。 */
    suspend fun sendRawThroughCrypto(roomId: String, eventType: String, contentJson: String): Result<DevEventResult> =
        Result.failure(UnsupportedOperationException("E2EE 未启用"))

    // ---- M3：推送 ----

    /** 当前正在查看的房间：不为该房间发通知。 */
    fun setActiveRoom(roomId: String?) {}

    /** 注册 HTTP pusher（UnifiedPush / ntfy 网关）。 */
    suspend fun registerHttpPusher(topic: String, gatewayUrl: String): Result<Unit> =
        Result.failure(UnsupportedOperationException("推送不可用"))

    suspend fun unregisterPusher(appId: String, pushkey: String): Result<Unit> =
        Result.failure(UnsupportedOperationException("推送不可用"))

    suspend fun listPushers(): Result<List<PusherInfo>> =
        Result.failure(UnsupportedOperationException("推送不可用"))

    // ---- M3：双账号对话测试（第二账号在同一进程内运行独立加密客户端）----

    suspend fun altLogin(user: String, password: String): Result<String> =
        Result.failure(UnsupportedOperationException("第二账号不可用"))

    suspend fun altCreateEncryptedRoom(roomName: String): Result<String> =
        Result.failure(UnsupportedOperationException("第二账号不可用"))

    suspend fun altJoinRoom(roomId: String): Result<Unit> =
        Result.failure(UnsupportedOperationException("第二账号不可用"))

    suspend fun altSendEncrypted(roomId: String, body: String): Result<String> =
        Result.failure(UnsupportedOperationException("第二账号不可用"))

    suspend fun altDecryptEvent(roomId: String, eventId: String): Result<String> =
        Result.failure(UnsupportedOperationException("第二账号不可用"))

    /** 第二账号解密房间中最新的一条 `m.room.encrypted`。 */
    suspend fun altDecryptLatest(roomId: String): Result<String> =
        Result.failure(UnsupportedOperationException("第二账号不可用"))

    fun altUserId(): String? = null

    // ---- M2：Thread / 编辑历史 / 转发 ----

    /** Thread 回复列表（`m.thread` 关系）。每次收集都会刷新。 */
    fun thread(roomId: String, rootEventId: String): Flow<List<TimelineItem>> = flowOf(emptyList())

    /** 消息的编辑历史（`m.replace` 的正文，按时间倒序）。 */
    suspend fun editHistory(roomId: String, eventId: String): Result<List<String>> =
        Result.success(emptyList())

    /** M2：读取当前用户的房间权限。 */
    suspend fun roomPermissions(roomId: String): Result<RoomPermissions> =
        Result.success(RoomPermissions())

    /** M2：获取链接预览（按 URL 缓存）。 */
    suspend fun urlPreview(url: String): Result<UrlPreview> =
        Result.failure(UnsupportedOperationException("URL 预览不可用"))

    /** M2：把某个事件前后的上下文合并进时间线（搜索跳转用）。 */
    suspend fun loadEventContext(roomId: String, eventId: String): Result<Unit> =
        Result.success(Unit)

    /** 转发一条消息到另一个房间（当前实现转发纯文本）。 */
    suspend fun forward(roomId: String, eventId: String, targetRoomId: String): Result<Unit> =
        Result.failure(UnsupportedOperationException("转发不可用"))

    /** 一键双账号 E2EE 对话测试：返回可读的验收报告。 */
    suspend fun runConversationTest(user: String, password: String): Result<String> =
        Result.failure(UnsupportedOperationException("第二账号不可用"))
}

data class Session(
    val homeserverUrl: String,
    val userId: String,
    val deviceId: String,
    val accessToken: String,
    val refreshToken: String? = null,
)

data class Credentials(
    val userId: String,
    val password: String,
)

data class EventId(val value: String)

data class RoomSummary(
    val roomId: String,
    val name: String,
    val isDirect: Boolean,
    val isSpace: Boolean,
    val unreadTotal: Int = 0,
    val unreadHighlight: Int = 0,
    val avatarUrl: String? = null,
    val topic: String? = null,
    val lastMessage: String? = null,
    val isInvite: Boolean = false,
    val inviter: String? = null,
)

data class SpaceSummary(
    val spaceId: String,
    val name: String,
    val topic: String? = null,
    val avatarUrl: String? = null,
    val childRoomIds: List<String> = emptyList(),
)

data class SearchResult(
    val roomId: String,
    val roomName: String,
    val eventId: String,
    val senderId: String?,
    val body: String,
    val timestampMillis: Long,
)

data class PublicRoom(
    val roomIdOrAlias: String,
    val name: String,
    val topic: String? = null,
    val avatarUrl: String? = null,
    val joinedMembers: Int = 0,
)

data class Reaction(
    val key: String,
    val count: Int,
    val reactedByMe: Boolean = false,
)

data class TimelinePage(
    val roomId: String,
    val items: List<TimelineItem> = emptyList(),
    val canPaginateBackwards: Boolean = false,
)

data class TimelineItem(
    val eventId: String,
    val senderId: String?,
    val body: String?,
    val timestampMillis: Long,
    val formattedBody: String? = null,
    val msgType: String? = null,
    val mediaUrl: String? = null,
    /** 加密媒体：AES-CTR key / iv / mimetype（来自事件 content.file）。 */
    val mediaKey: String? = null,
    val mediaIv: String? = null,
    val mediaMime: String? = null,
    val isEdited: Boolean = false,
    val isPinned: Boolean = false,
    val replyToEventId: String? = null,
    val threadRootId: String? = null,
    val reactions: List<Reaction> = emptyList(),
    /** M3：是否为 `m.room.encrypted`（内容由 rust-sdk 解密）。 */
    val isEncrypted: Boolean = false,
    /** 解密后的正文（仅当 rust-sdk 成功解密时存在）。 */
    val decryptedBody: String? = null,
    val decryptedMsgType: String? = null,
    /** 解密失败原因，例如缺少会话密钥。 */
    val decryptionError: String? = null,
)

/** M2：当前用户在房间里的权限（来自 m.room.power_levels）。 */
data class RoomPermissions(
    val myPowerLevel: Int = 0,
    val redactLevel: Int = 50,
    val canRedactOthers: Boolean = false,
)

/** M2：URL 预览（Matrix media preview_url）。 */
data class UrlPreview(
    val url: String,
    val title: String? = null,
    val description: String? = null,
    val imageUrl: String? = null,
    val siteName: String? = null,
)

/** M2：下载（并解密）后的媒体内容。 */
class MediaPayload(
    val bytes: ByteArray,
    val mimeType: String? = null,
    val fileName: String? = null,
)

data class MessageDraft(
    val body: String,
    val formattedBody: String? = null,
    val replyToEventId: String? = null,
    val threadRootEventId: String? = null,
)

enum class SyncState {
    Idle,
    Connecting,
    Syncing,
    Offline,
    Error,
}

sealed interface SessionState {
    data object LoggedOut : SessionState
    data object Restoring : SessionState
    data class LoggedIn(val session: Session) : SessionState
    data class Failed(val message: String) : SessionState
}
