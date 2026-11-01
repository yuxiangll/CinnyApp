package top.s0cl.cinny.matrix

import android.app.Activity
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import top.s0cl.cinny.matrix.crypto.MatrixCryptoService
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * M1 MatrixEngine using the Chrome/WebView network stack.
 *
 * This is a temporary transport workaround for the s0cl.top WAF, which resets
 * rustls/Java TLS clients at the TLS handshake but accepts Chrome.
 */
class WebViewMatrixEngine(
    private val activity: Activity,
    private val scope: CoroutineScope,
    private val onNotify: (NotificationEvent) -> Unit = {},
) : MatrixEngine {
    private val prefs = activity.getSharedPreferences(PREFS_NAME, Activity.MODE_PRIVATE)
    private val transport = WebViewTransport(activity)

    /** M3：rust-sdk E2EE 服务；网络经由 [transport] 的本机中继。 */
    private val crypto = MatrixCryptoService(
        context = activity.applicationContext,
        scope = scope,
        onSessionSaved = { refreshed ->
            // rust-sdk 刷新了 access token：同步内存状态、持久化，并让 UI 使用新会话。
            accessToken = refreshed.accessToken
            refreshToken = refreshed.refreshToken
            saveSession(refreshed)
            (sessionState.value as? SessionState.LoggedIn)?.let { current ->
                sessionState.value = SessionState.LoggedIn(
                    current.session.copy(
                        accessToken = refreshed.accessToken,
                        refreshToken = refreshed.refreshToken,
                    ),
                )
            }
        },
    )

    private val sessionState = MutableStateFlow<SessionState>(SessionState.LoggedOut)
    private val sync = MutableStateFlow(SyncState.Idle)
    private val roomsState = MutableStateFlow<List<RoomSummary>>(emptyList())
    private val spacesState = MutableStateFlow<List<SpaceSummary>>(emptyList())
    private val invitesState = MutableStateFlow<List<RoomSummary>>(emptyList())
    private val spaceChildren = ConcurrentHashMap<String, List<String>>()

    private val roomMap = ConcurrentHashMap<String, RoomSummary>()
    private val timelines = ConcurrentHashMap<String, MutableStateFlow<TimelinePage>>()
    private val loadedTimelines = ConcurrentHashMap.newKeySet<String>()
    private var syncJob: Job? = null

    @Volatile private var baseUrl: String? = null
    @Volatile private var accessToken: String? = null
    @Volatile private var refreshToken: String? = null
    @Volatile private var currentUserId: String? = null
    @Volatile private var currentDeviceId: String? = null
    @Volatile private var spaceOrder: List<String> = emptyList()
    @Volatile private var activeRoomId: String? = null
    private val notifiedEvents = java.util.Collections.synchronizedSet(LinkedHashSet<String>())

    override val session: StateFlow<SessionState> = sessionState.asStateFlow()
    override val syncState: StateFlow<SyncState> = sync.asStateFlow()

    override suspend fun restoreSession(): Result<Session> {
        val saved = loadSession()
        if (saved == null) {
            sessionState.value = SessionState.LoggedOut
            return Result.failure(IllegalStateException("No saved session"))
        }
        sessionState.value = SessionState.Restoring
        return runCatching {
            baseUrl = saved.homeserverUrl
            accessToken = saved.accessToken
            refreshToken = saved.refreshToken
            currentUserId = saved.userId
            currentDeviceId = saved.deviceId
            transport.useBaseUrl(saved.homeserverUrl)
            sessionState.value = SessionState.LoggedIn(saved)
            startSync()
            scope.launch {
                crypto.attach(saved, transport)
                decryptPendingTimelines()
            }
            saved
        }.onFailure {
            Log.w(TAG, "restoreSession failed", it)
            sessionState.value = SessionState.Failed(it.message ?: "Failed to restore session")
        }
    }

    override suspend fun login(server: String, credentials: Credentials): Result<Session> = runCatching {
        val resolvedBase = resolveBaseUrl(server)
        transport.useBaseUrl(resolvedBase)
        val payload = JSONObject()
            .put("type", "m.login.password")
            .put(
                "identifier",
                JSONObject().put("type", "m.id.user").put("user", credentials.userId),
            )
            .put("password", credentials.password)
            .put("initial_device_name", "CinnyClient Android (M1 WebView)")
            .put("refresh_token", true)
        val response = transport.request(
            method = "POST",
            path = "/_matrix/client/v3/login",
            body = payload.toString(),
            timeoutMs = 25_000,
        )
        if (!response.isSuccess) {
            error("登录失败：HTTP ${response.status} ${response.body.take(300)}")
        }
        val json = JSONObject(response.body)
        val token = json.optString("access_token").takeIf { it.isNotBlank() }
            ?: error("登录响应缺少 access_token")
        val userId = json.optString("user_id").takeIf { it.isNotBlank() }
            ?: error("登录响应缺少 user_id")
        val deviceId = json.optString("device_id").takeIf { it.isNotBlank() } ?: "UNKNOWN"
        val session = Session(
            homeserverUrl = resolvedBase,
            userId = userId,
            deviceId = deviceId,
            accessToken = token,
            refreshToken = json.optString("refresh_token").takeIf { it.isNotBlank() },
        )
        saveSession(session)
        baseUrl = resolvedBase
        accessToken = token
        refreshToken = session.refreshToken
        currentUserId = userId
        currentDeviceId = deviceId
        sessionState.value = SessionState.LoggedIn(session)
        startSync()
        scope.launch {
            crypto.attach(session, transport)
            decryptPendingTimelines()
        }
        session
    }.onFailure {
        Log.w(TAG, "login failed", it)
        sessionState.value = SessionState.Failed(it.message ?: "Login failed")
    }

    private suspend fun resolveBaseUrl(input: String): String {
        val normalized = normalizeServer(input)
        val uri = runCatching { android.net.Uri.parse(normalized) }.getOrNull()
        val host = uri?.host ?: return normalized
        val candidates = mutableListOf(normalized)
        if (uri.port == -1 && !host.startsWith("matrix.") && !host.isIpAddress()) {
            candidates.add("https://matrix.$host")
        }
        var lastError: Throwable? = null
        candidates.forEach { candidate ->
            runCatching {
                transport.useBaseUrl(candidate)
                val wellKnown = transport.request(
                    method = "GET",
                    path = "/.well-known/matrix/client",
                    timeoutMs = 10_000,
                )
                if (wellKnown.isSuccess) {
                    val base = JSONObject(wellKnown.body)
                        .optJSONObject("m.homeserver")
                        ?.optString("base_url")
                        .orEmpty()
                    if (base.isNotBlank()) return base.trimEnd('/')
                }
            }.onFailure { lastError = it }
        }
        Log.i(TAG, "well-known discovery failed; fallback to ${candidates.last()}", lastError)
        return candidates.last()
    }

    override suspend fun logout(): Result<Unit> = runCatching {
        stopSync()
        crypto.wipe()
        runCatching {
            transport.request("POST", "/_matrix/client/v3/logout", accessToken, "{}", 10_000)
        }
        clearSession()
        roomMap.clear()
        roomsState.value = emptyList()
        timelines.clear()
        loadedTimelines.clear()
        baseUrl = null
        accessToken = null
        refreshToken = null
        currentUserId = null
        currentDeviceId = null
        sync.value = SyncState.Idle
        sessionState.value = SessionState.LoggedOut
    }

    override fun rooms(): Flow<List<RoomSummary>> = roomsState.asStateFlow()

    override fun spaces(): Flow<List<SpaceSummary>> = spacesState.asStateFlow()

    override fun invites(): Flow<List<RoomSummary>> = invitesState.asStateFlow()

    override fun timeline(roomId: String): Flow<TimelinePage> = flow {
        val state = timelines.getOrPut(roomId) { MutableStateFlow(TimelinePage(roomId)) }
        if (loadedTimelines.add(roomId)) {
            scope.launch { loadTimeline(roomId) }
        }
        emitAll(state)
    }

    override suspend fun send(roomId: String, draft: MessageDraft): Result<EventId> = runCatching {
        val token = accessToken ?: error("Not logged in")
        val txnId = "cinny_${System.currentTimeMillis()}"
        val payload = JSONObject()
            .put("msgtype", "m.text")
            .put("body", draft.body)
        val relation = when {
            draft.threadRootEventId != null -> JSONObject()
                .put("rel_type", "m.thread")
                .put("event_id", draft.threadRootEventId)
            draft.replyToEventId != null -> JSONObject()
                .put("m.in_reply_to", JSONObject().put("event_id", draft.replyToEventId))
            else -> null
        }
        if (relation != null) payload.put("m.relates_to", relation)
        val response = withAuthRetry { token ->
            transport.request(
                method = "PUT",
                path = "/_matrix/client/v3/rooms/${encode(roomId)}/send/m.room.message/${encode(txnId)}",
                accessToken = token,
                body = payload.toString(),
                timeoutMs = 25_000,
            )
        }
        if (!response.isSuccess) {
            error("发送失败：HTTP ${response.status} ${response.body.take(200)}")
        }
        val eventId = JSONObject(response.body).optString("event_id").ifBlank { "local-$txnId" }
        val state = timelines.getOrPut(roomId) { MutableStateFlow(TimelinePage(roomId)) }
        state.value = state.value.copy(
            items = state.value.items + TimelineItem(
                eventId = eventId,
                senderId = currentUserId,
                body = draft.body,
                timestampMillis = System.currentTimeMillis(),
                msgType = "m.text",
                replyToEventId = draft.replyToEventId,
                threadRootId = draft.threadRootEventId,
            ),
        )
        EventId(eventId)
    }

    override suspend fun sendReadReceipt(
        roomId: String,
        eventId: String,
        private: Boolean,
    ): Result<Unit> = runCatching {
        val token = accessToken ?: error("Not logged in")
        val receiptType = if (private) "m.read.private" else "m.read"
        val response = withAuthRetry { token ->
            transport.request(
                method = "POST",
                path = "/_matrix/client/v3/rooms/${encode(roomId)}/receipt/${encode(receiptType)}/${encode(eventId)}",
                accessToken = token,
                body = "{}",
                timeoutMs = 10_000,
            )
        }
        if (!response.isSuccess) error("已读回执失败：HTTP ${response.status}")
    }

    override suspend fun edit(roomId: String, eventId: String, body: String): Result<Unit> = runCatching {
        val token = accessToken ?: error("Not logged in")
        val txnId = "edit_${System.currentTimeMillis()}"
        val newContent = JSONObject().put("msgtype", "m.text").put("body", body)
        val payload = JSONObject()
            .put("msgtype", "m.text")
            .put("body", "* $body")
            .put("m.new_content", newContent)
            .put("m.relates_to", JSONObject().put("rel_type", "m.replace").put("event_id", eventId))
        val response = withAuthRetry { token ->
            transport.request(
                method = "PUT",
                path = "/_matrix/client/v3/rooms/${encode(roomId)}/send/m.room.message/${encode(txnId)}",
                accessToken = token,
                body = payload.toString(),
                timeoutMs = 20_000,
            )
        }
        if (!response.isSuccess) error("编辑失败：HTTP ${response.status}")
        timelines[roomId]?.let { state ->
            state.value = state.value.copy(items = state.value.items.map {
                if (it.eventId == eventId) it.copy(body = body, isEdited = true) else it
            })
        }
    }

    override suspend fun redact(roomId: String, eventId: String, reason: String?): Result<Unit> = runCatching {
        val token = accessToken ?: error("Not logged in")
        val txnId = "redact_${System.currentTimeMillis()}"
        val payload = JSONObject().put("reason", reason ?: "")
        val response = withAuthRetry { token ->
            transport.request(
                method = "PUT",
                path = "/_matrix/client/v3/rooms/${encode(roomId)}/redact/${encode(eventId)}/${encode(txnId)}",
                accessToken = token,
                body = payload.toString(),
                timeoutMs = 20_000,
            )
        }
        if (!response.isSuccess) error("删除失败：HTTP ${response.status}")
        timelines[roomId]?.let { state ->
            state.value = state.value.copy(items = state.value.items.filterNot { it.eventId == eventId })
        }
    }

    override suspend fun toggleReaction(roomId: String, eventId: String, key: String): Result<Boolean> = runCatching {
        val token = accessToken ?: error("Not logged in")
        val txnId = "reaction_${System.currentTimeMillis()}"
        val payload = JSONObject().put(
            "m.relates_to",
            JSONObject().put("rel_type", "m.annotation").put("event_id", eventId).put("key", key),
        )
        val response = withAuthRetry { token ->
            transport.request(
                method = "PUT",
                path = "/_matrix/client/v3/rooms/${encode(roomId)}/send/m.reaction/${encode(txnId)}",
                accessToken = token,
                body = payload.toString(),
                timeoutMs = 15_000,
            )
        }
        if (!response.isSuccess) error("表情回应失败：HTTP ${response.status}")
        timelines[roomId]?.let { state ->
            state.value = state.value.copy(items = state.value.items.map { item ->
                if (item.eventId != eventId) item else {
                    val existing = item.reactions.firstOrNull { it.key == key }
                    val reactions = when {
                        existing == null -> item.reactions + Reaction(key, 1, true)
                        existing.reactedByMe -> item.reactions.mapNotNull { if (it.key == key) null else it }
                        else -> item.reactions.map {
                            if (it.key == key) it.copy(count = it.count + 1, reactedByMe = true) else it
                        }
                    }
                    item.copy(reactions = reactions)
                }
            })
        }
        true
    }

    override suspend fun setPinned(roomId: String, eventId: String, pinned: Boolean): Result<Unit> = runCatching {
        val token = accessToken ?: error("Not logged in")
        val statePath = "/_matrix/client/v3/rooms/${encode(roomId)}/state/m.room.pinned_events"
        val current = runCatching {
            val response = transport.request("GET", statePath, token, timeoutMs = 15_000)
            if (!response.isSuccess) emptyList() else {
                val array = JSONObject(response.body).optJSONArray("pinned")
                buildList { if (array != null) for (i in 0 until array.length()) add(array.optString(i)) }
            }
        }.getOrDefault(emptyList())
        val updated = if (pinned) (current + eventId).distinct() else current.filterNot { it == eventId }
        val response = transport.request("PUT", statePath, token, JSONObject().put("pinned", JSONArray(updated)).toString(), 15_000)
        if (!response.isSuccess) error("置顶失败：HTTP ${response.status}")
        timelines[roomId]?.let { state ->
            state.value = state.value.copy(items = state.value.items.map {
                if (it.eventId == eventId) it.copy(isPinned = pinned) else it
            })
        }
    }

    override suspend fun joinRoom(roomIdOrAlias: String): Result<String> = runCatching {
        val token = accessToken ?: error("Not logged in")
        val response = withAuthRetry { token ->
            transport.request(
                method = "POST",
                path = "/_matrix/client/v3/join/${encode(roomIdOrAlias)}",
                accessToken = token,
                body = "{}",
                timeoutMs = 20_000,
            )
        }
        if (!response.isSuccess) error("加入房间失败：HTTP ${response.status}")
        JSONObject(response.body).optString("room_id").ifBlank { roomIdOrAlias }
    }

    override suspend fun leaveRoom(roomId: String): Result<Unit> = runCatching {
        val token = accessToken ?: error("Not logged in")
        val response = transport.request("POST", "/_matrix/client/v3/rooms/${encode(roomId)}/leave", token, "{}", 15_000)
        if (!response.isSuccess) error("离开房间失败：HTTP ${response.status}")
        roomMap.remove(roomId)
        roomsState.value = roomMap.values.filter { !it.isSpace }
        timelines.remove(roomId)
        loadedTimelines.remove(roomId)
    }

    override suspend fun acceptInvite(roomId: String): Result<Unit> = runCatching {
        val token = accessToken ?: error("Not logged in")
        val response = transport.request("POST", "/_matrix/client/v3/rooms/${encode(roomId)}/join", token, "{}", 20_000)
        if (!response.isSuccess) error("接受邀请失败：HTTP ${response.status}")
        invitesState.value = invitesState.value.filterNot { it.roomId == roomId }
    }

    override suspend fun declineInvite(roomId: String): Result<Unit> = runCatching {
        val token = accessToken ?: error("Not logged in")
        val response = transport.request("POST", "/_matrix/client/v3/rooms/${encode(roomId)}/leave", token, "{}", 15_000)
        if (!response.isSuccess) error("拒绝邀请失败：HTTP ${response.status}")
        invitesState.value = invitesState.value.filterNot { it.roomId == roomId }
    }

    override suspend fun search(term: String, roomId: String?): Result<List<SearchResult>> = runCatching {
        val token = accessToken ?: error("Not logged in")
        val filter = JSONObject()
        if (roomId != null) filter.put("rooms", JSONArray().put(roomId))
        val payload = JSONObject().put(
            "search_categories",
            JSONObject().put(
                "room_events",
                JSONObject().put("search_term", term).put("order_by", "recent").put("filter", filter),
            ),
        )
        val response = transport.request("POST", "/_matrix/client/v3/search", token, payload.toString(), 25_000)
        if (!response.isSuccess) error("搜索失败：HTTP ${response.status}")
        val results = JSONObject(response.body)
            .optJSONObject("search_categories")
            ?.optJSONObject("room_events")
            ?.optJSONArray("results")
        buildList {
            if (results != null) {
                for (index in 0 until results.length()) {
                    val wrapper = results.optJSONObject(index) ?: continue
                    val item = wrapper.optJSONObject("result") ?: wrapper
                    val resultRoom = item.optString("room_id")
                    val content = item.optJSONObject("content") ?: JSONObject()
                    add(
                        SearchResult(
                            roomId = resultRoom,
                            roomName = roomMap[resultRoom]?.name ?: resultRoom,
                            eventId = item.optString("event_id"),
                            senderId = item.optString("sender").takeIf { it.isNotBlank() },
                            body = content.optString("body"),
                            timestampMillis = item.optLong("origin_server_ts"),
                        ),
                    )
                }
            }
        }
    }

    override suspend fun publicRooms(server: String?): Result<List<PublicRoom>> = runCatching {
        val token = accessToken ?: error("Not logged in")
        val query = buildString {
            append("/_matrix/client/v3/publicRooms?limit=50")
            if (!server.isNullOrBlank()) append("&server=").append(encode(server))
        }
        val response = transport.request("GET", query, token, timeoutMs = 25_000)
        if (!response.isSuccess) error("探索失败：HTTP ${response.status}")
        val chunk = JSONObject(response.body).optJSONArray("chunk")
        buildList {
            if (chunk != null) {
                for (index in 0 until chunk.length()) {
                    val room = chunk.optJSONObject(index) ?: continue
                    add(
                        PublicRoom(
                            roomIdOrAlias = room.optString("room_id").ifBlank { room.optString("canonical_alias") },
                            name = room.optString("name").ifBlank { room.optString("canonical_alias") },
                            topic = room.optString("topic").takeIf { it.isNotBlank() },
                            avatarUrl = room.optString("avatar_url").takeIf { it.isNotBlank() },
                            joinedMembers = room.optInt("num_joined_members"),
                        ),
                    )
                }
            }
        }
    }

    override suspend fun loadMedia(roomId: String, item: TimelineItem): Result<MediaPayload> = runCatching {
        if (item.isEncrypted) {
            val source = crypto.loadMedia(roomId, item.eventId).getOrThrow()
            val bytes = crypto.mediaBytesOf(source).getOrThrow()
            MediaPayload(bytes, item.mediaMime ?: mimeOf(item.msgType), item.body)
        } else {
            val url = item.mediaUrl ?: error("该消息不含媒体")
            val bytes = mediaBytes(url, item.mediaKey, item.mediaIv).getOrThrow()
            MediaPayload(bytes, item.mediaMime ?: mimeOf(item.msgType), item.body)
        }
    }

    override suspend fun mediaBytes(mxcUrl: String, key: String?, iv: String?): Result<ByteArray> = runCatching {
        val raw = mediaBytes(mxcUrl).getOrThrow()
        if (key.isNullOrBlank() || iv.isNullOrBlank()) raw else decryptAesCtr(raw, key, iv)
    }

    /** 矩阵加密附件：AES-CTR + 事件里的 key/iv。 */
    private fun decryptAesCtr(data: ByteArray, keyBase64: String, ivBase64: String): ByteArray {
        val key = android.util.Base64.decode(keyBase64, android.util.Base64.DEFAULT)
        val iv = android.util.Base64.decode(ivBase64, android.util.Base64.DEFAULT)
        val cipher = javax.crypto.Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(
            javax.crypto.Cipher.DECRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(key, "AES"),
            javax.crypto.spec.IvParameterSpec(iv),
        )
        return cipher.doFinal(data)
    }

    private fun mimeOf(msgType: String?): String? = when (msgType) {
        "m.image" -> "image/*"
        "m.video" -> "video/*"
        "m.audio" -> "audio/*"
        else -> null
    }

    override suspend fun mediaBytes(mxcUrl: String): Result<ByteArray> = runCatching {
        val token = accessToken ?: error("Not logged in")
        val trimmed = mxcUrl.removePrefix("mxc://")
        val parts = trimmed.split("/", limit = 2)
        if (parts.size != 2) error("Invalid mxc URL: $mxcUrl")
        val server = parts[0]
        val mediaId = parts[1]
        val primary = "/_matrix/client/v1/media/download/${encode(server)}/${encode(mediaId)}"
        val first = transport.requestBytes(primary, token)
        if (first.isSuccess) return@runCatching first.bytes
        val legacy = "/_matrix/media/v3/download/${encode(server)}/${encode(mediaId)}"
        val second = transport.requestBytes(legacy, token)
        if (!second.isSuccess) error("媒体下载失败：HTTP ${second.status}")
        second.bytes
    }

    override suspend fun sendImage(
        roomId: String,
        bytes: ByteArray,
        mimeType: String,
        fileName: String,
    ): Result<EventId> = runCatching {
        val token = accessToken ?: error("Not logged in")
        val uploadPath = "/_matrix/media/v3/upload?filename=${encode(fileName)}"
        val upload = transport.uploadBytes(uploadPath, token, bytes, mimeType)
        if (!upload.isSuccess) error("媒体上传失败：HTTP ${upload.status}")
        val contentUri = JSONObject(upload.body).optString("content_uri")
        if (contentUri.isBlank()) error("上传响应缺少 content_uri")
        val txnId = "media_${System.currentTimeMillis()}"
        val msgType = when {
            mimeType.startsWith("image/") -> "m.image"
            mimeType.startsWith("video/") -> "m.video"
            mimeType.startsWith("audio/") -> "m.audio"
            else -> "m.file"
        }
        val payload = JSONObject()
            .put("msgtype", msgType)
            .put("body", fileName)
            .put("url", contentUri)
            .put("info", JSONObject().put("mimetype", mimeType).put("size", bytes.size))
        val response = withAuthRetry { token ->
            transport.request(
                method = "PUT",
                path = "/_matrix/client/v3/rooms/${encode(roomId)}/send/m.room.message/${encode(txnId)}",
                accessToken = token,
                body = payload.toString(),
                timeoutMs = 30_000,
            )
        }
        if (!response.isSuccess) error("图片发送失败：HTTP ${response.status}")
        val eventId = JSONObject(response.body).optString("event_id").ifBlank { "local-$txnId" }
        val state = timelines.getOrPut(roomId) { MutableStateFlow(TimelinePage(roomId)) }
        state.value = state.value.copy(
            items = state.value.items + TimelineItem(
                eventId = eventId,
                senderId = currentUserId,
                body = fileName,
                timestampMillis = System.currentTimeMillis(),
                msgType = msgType,
                mediaUrl = contentUri,
            ),
        )
        EventId(eventId)
    }

    override suspend fun createSpace(name: String): Result<String> = runCatching {
        val token = accessToken ?: error("Not logged in")
        val payload = JSONObject()
            .put("name", name)
            .put("preset", "private_chat")
            .put("visibility", "private")
            .put("creation_content", JSONObject().put("type", "m.space"))
        val response = withAuthRetry { token ->
            transport.request(
                method = "POST",
                path = "/_matrix/client/v3/createRoom",
                accessToken = token,
                body = payload.toString(),
                timeoutMs = 20_000,
            )
        }
        if (!response.isSuccess) error("创建空间失败：HTTP ${response.status}")
        JSONObject(response.body).optString("room_id").ifBlank { error("创建空间响应缺少 room_id") }
    }

    override suspend fun addRoomToSpace(spaceId: String, roomId: String): Result<Unit> = runCatching {
        val serverName = roomId.substringAfter(':', "").ifBlank {
            currentUserId?.substringAfter(':', "s0cl.top") ?: "s0cl.top"
        }
        val payload = JSONObject()
            .put("via", JSONArray().put(serverName))
            .put("order", System.currentTimeMillis().toString())
        val response = withAuthRetry { token ->
            transport.request(
                method = "PUT",
                path = "/_matrix/client/v3/rooms/" + encode(spaceId) + "/state/m.space.child/" +
                    encode(roomId),
                accessToken = token,
                body = payload.toString(),
                timeoutMs = 20_000,
            )
        }
        if (!response.isSuccess) error("添加房间到空间失败：HTTP " + response.status)
        val children = spaceChildren[spaceId].orEmpty().toMutableList()
        if (!children.contains(roomId)) children.add(roomId)
        spaceChildren[spaceId] = children
        spacesState.value = spacesState.value.map {
            if (it.spaceId == spaceId) it.copy(childRoomIds = children) else it
        }
    }

    override suspend fun reorderSpaces(spaceIds: List<String>): Result<Unit> = runCatching {
        val token = accessToken ?: error("Not logged in")
        val userId = currentUserId ?: error("Not logged in")
        val payload = JSONObject().put("sidebar", JSONArray(spaceIds))
        val response = withAuthRetry { token ->
            transport.request(
                method = "PUT",
                path = "/_matrix/client/v3/user/${encode(userId)}/account_data/in.cinny.spaces",
                accessToken = token,
                body = payload.toString(),
                timeoutMs = 15_000,
            )
        }
        if (!response.isSuccess) error("空间排序保存失败：HTTP ${response.status}")
        spaceOrder = spaceIds
        spacesState.value = spacesState.value.sortedWith(
            compareBy<SpaceSummary> {
                val index = spaceIds.indexOf(it.spaceId)
                if (index < 0) Int.MAX_VALUE else index
            }.thenBy { it.name.lowercase() },
        )
    }

    // ---- M3：E2EE / 验证 / 密钥备份 ----

    override fun cryptoStatus(): StateFlow<CryptoStatus> = crypto.status

    override fun verificationState(): StateFlow<VerificationUiState> = crypto.verification

    override suspend fun decryptEvent(roomId: String, eventId: String): Result<DecryptedEvent> =
        crypto.decrypt(roomId, eventId)

    override suspend fun enableKeyBackup(): Result<String> = crypto.enableRecovery()

    override suspend fun restoreKeyBackup(recoveryKey: String): Result<Unit> = crypto.recover(recoveryKey)

    override suspend fun sendEncryptedText(roomId: String, body: String): Result<EventId> =
        crypto.sendText(roomId, body).map { EventId(it) }

    override suspend fun requestVerification(): Result<Unit> = crypto.requestVerification()

    override suspend fun acceptVerification(): Result<Unit> = crypto.acceptVerification()

    override suspend fun startSasVerification(): Result<Unit> = crypto.startSas()

    override suspend fun approveSasVerification(): Result<Unit> = crypto.approveSas()

    override suspend fun declineVerification(): Result<Unit> = crypto.declineVerification()

    override suspend fun sendRawThroughCrypto(
        roomId: String,
        eventType: String,
        contentJson: String,
    ): Result<DevEventResult> = crypto.sendViaCrypto(roomId, eventType, contentJson)

    // ---- M3：开发者工具（走 WebView 传输，无需加密层）----

    override suspend fun sendCustomEvent(
        roomId: String,
        eventType: String,
        contentJson: String,
    ): Result<DevEventResult> = runCatching {
        val token = accessToken ?: error("Not logged in")
        val txnId = "dev_${System.currentTimeMillis()}"
        val response = withAuthRetry { token ->
            transport.request(
                method = "PUT",
                path = "/_matrix/client/v3/rooms/${encode(roomId)}/send/${encode(eventType)}/${encode(txnId)}",
                accessToken = token,
                body = contentJson.ifBlank { "{}" },
                timeoutMs = 25_000,
            )
        }
        if (!response.isSuccess) error("发送事件失败：HTTP ${response.status} ${response.body.take(200)}")
        DevEventResult(
            eventId = runCatching { JSONObject(response.body).optString("event_id") }.getOrNull(),
            message = "事件已发送",
        )
    }

    override suspend fun sendStateEvent(
        roomId: String,
        eventType: String,
        stateKey: String,
        contentJson: String,
    ): Result<DevEventResult> = runCatching {
        val token = accessToken ?: error("Not logged in")
        val response = withAuthRetry { token ->
            transport.request(
                method = "PUT",
                path = "/_matrix/client/v3/rooms/${encode(roomId)}/state/${encode(eventType)}/${encode(stateKey)}",
                accessToken = token,
                body = contentJson.ifBlank { "{}" },
                timeoutMs = 25_000,
            )
        }
        if (!response.isSuccess) error("发送状态事件失败：HTTP ${response.status} ${response.body.take(200)}")
        DevEventResult(
            eventId = runCatching { JSONObject(response.body).optString("event_id") }.getOrNull(),
            message = "状态事件已发送",
        )
    }

    override suspend fun setAccountData(eventType: String, contentJson: String): Result<Unit> = runCatching {
        val token = accessToken ?: error("Not logged in")
        val userId = currentUserId ?: error("Not logged in")
        val response = withAuthRetry { token ->
            transport.request(
                method = "PUT",
                path = "/_matrix/client/v3/user/${encode(userId)}/account_data/${encode(eventType)}",
                accessToken = token,
                body = contentJson.ifBlank { "{}" },
                timeoutMs = 20_000,
            )
        }
        if (!response.isSuccess) error("写入账号数据失败：HTTP ${response.status} ${response.body.take(200)}")
    }

    override suspend fun getAccountData(eventType: String): Result<String> = runCatching {
        val token = accessToken ?: error("Not logged in")
        val userId = currentUserId ?: error("Not logged in")
        val response = withAuthRetry { token ->
            transport.request(
                method = "GET",
                path = "/_matrix/client/v3/user/${encode(userId)}/account_data/${encode(eventType)}",
                accessToken = token,
                timeoutMs = 15_000,
            )
        }
        if (!response.isSuccess) error("读取账号数据失败：HTTP ${response.status}")
        response.body
    }

    private fun decryptPendingTimelines() {
        timelines.forEach { (roomId, state) ->
            scheduleDecryption(roomId, state.value.items)
        }
    }




    // ---- M2：URL 预览 ----

    private val previewCache = ConcurrentHashMap<String, UrlPreview>()

    override suspend fun urlPreview(url: String): Result<UrlPreview> = runCatching {
        previewCache[url]?.let { return@runCatching it }
        val encoded = encode(url)
        val response = withAuthRetry { token ->
            transport.request(
                method = "GET",
                path = "/_matrix/client/v1/media/preview_url?url=" + encoded,
                accessToken = token,
                timeoutMs = 15_000,
            )
        }
        val fallback = if (response.isSuccess) {
            response
        } else {
            withAuthRetry { token ->
                transport.request(
                    method = "GET",
                    path = "/_matrix/media/v3/preview_url?url=" + encoded,
                    accessToken = token,
                    timeoutMs = 15_000,
                )
            }
        }
        Log.i(TAG, "preview url=" + url + " status=" + fallback.status + " body=" + fallback.body.take(300))
        if (!fallback.isSuccess) error("预览失败：HTTP " + fallback.status)
        val json = JSONObject(fallback.body)
        val preview = UrlPreview(
            url = url,
            title = json.optString("og:title").takeIf { it.isNotBlank() },
            description = json.optString("og:description").takeIf { it.isNotBlank() },
            imageUrl = json.optString("og:image").takeIf { it.isNotBlank() },
            siteName = json.optString("og:site_name").takeIf { it.isNotBlank() },
        )
        previewCache[url] = preview
        preview
    }
    // ---- M2：Thread / 编辑历史 / 转发 ----

    private val threadStates = ConcurrentHashMap<String, MutableStateFlow<List<TimelineItem>>>()

    override fun thread(roomId: String, rootEventId: String): Flow<List<TimelineItem>> = flow {
        val key = roomId + "|" + rootEventId
        val state = threadStates.getOrPut(key) { MutableStateFlow(emptyList()) }
        scope.launch { loadThread(roomId, rootEventId, state) }
        emitAll(state)
    }

    private suspend fun loadThread(
        roomId: String,
        rootEventId: String,
        state: MutableStateFlow<List<TimelineItem>>,
    ) {
        runCatching {
            val response = withAuthRetry { token ->
                transport.request(
                    method = "GET",
                    path = "/_matrix/client/v1/rooms/" + encode(roomId) +
                        "/relations/" + encode(rootEventId) + "/m.thread?dir=b&limit=60",
                    accessToken = token,
                    timeoutMs = 20_000,
                )
            }
            if (!response.isSuccess) error("加载 Thread 失败：HTTP " + response.status)
            val chunk = JSONObject(response.body).optJSONArray("chunk") ?: JSONArray()
            state.value = parseEvents(chunk).sortedBy { it.timestampMillis }
            scheduleDecryption(roomId, state.value)
        }.onFailure { Log.w(TAG, "loadThread failed", it) }
    }

    override suspend fun editHistory(roomId: String, eventId: String): Result<List<String>> = runCatching {
        val response = withAuthRetry { token ->
            transport.request(
                method = "GET",
                path = "/_matrix/client/v1/rooms/" + encode(roomId) +
                    "/relations/" + encode(eventId) + "/m.replace?dir=b&limit=50",
                accessToken = token,
                timeoutMs = 20_000,
            )
        }
        if (!response.isSuccess) error("读取编辑历史失败：HTTP " + response.status)
        val chunk = JSONObject(response.body).optJSONArray("chunk") ?: JSONArray()
        buildList {
            for (index in 0 until chunk.length()) {
                val event = chunk.optJSONObject(index) ?: continue
                val newContent = event.optJSONObject("content")?.optJSONObject("m.new_content")
                val body = newContent?.optString("body").orEmpty()
                if (body.isNotBlank()) add(body)
            }
        }
    }

    override suspend fun loadEventContext(roomId: String, eventId: String): Result<Unit> = runCatching {
        val response = withAuthRetry { token ->
            transport.request(
                method = "GET",
                path = "/_matrix/client/v3/rooms/" + encode(roomId) + "/context/" +
                    encode(eventId) + "?limit=30",
                accessToken = token,
                timeoutMs = 20_000,
            )
        }
        if (!response.isSuccess) error("加载事件上下文失败：HTTP " + response.status)
        val json = JSONObject(response.body)
        val events = JSONArray()
        (json.optJSONArray("events_before") ?: JSONArray()).let { before ->
            for (i in before.length() - 1 downTo 0) before.optJSONObject(i)?.let { events.put(it) }
        }
        json.optJSONObject("event")?.let { events.put(it) }
        (json.optJSONArray("events_after") ?: JSONArray()).let { after ->
            for (i in 0 until after.length()) after.optJSONObject(i)?.let { events.put(it) }
        }
        val state = timelines.getOrPut(roomId) { MutableStateFlow(TimelinePage(roomId)) }
        val merged = (state.value.items + parseEvents(events))
            .distinctBy { it.eventId }
            .sortedBy { it.timestampMillis }
        state.value = state.value.copy(items = merged)
        scheduleDecryption(roomId, merged)
        Unit
    }

    override suspend fun forward(roomId: String, eventId: String, targetRoomId: String): Result<Unit> = runCatching {
        val item = timelines[roomId]?.value?.items?.firstOrNull { it.eventId == eventId }
            ?: error("找不到要转发的消息")
        val mediaType = item.decryptedMsgType ?: item.msgType
        if (isMediaItem(item, mediaType)) {
            // 转发保留媒体：先下载（必要时解密），再在目标房间重新上传。
            val payload = loadMedia(roomId, item).getOrThrow()
            sendImage(
                roomId = targetRoomId,
                bytes = payload.bytes,
                mimeType = payload.mimeType ?: "application/octet-stream",
                fileName = payload.fileName ?: item.body ?: "file",
            ).getOrThrow()
        } else {
            val text = item.decryptedBody ?: item.body ?: error("该消息没有可转发的正文")
            send(targetRoomId, MessageDraft(body = text)).getOrThrow()
        }
        Unit
    }

    private fun isMediaItem(item: TimelineItem, mediaType: String?): Boolean =
        item.mediaUrl != null || (mediaType != null && mediaType in MEDIA_MESSAGE_TYPES)

    override suspend fun roomPermissions(roomId: String): Result<RoomPermissions> = runCatching {
        val response = withAuthRetry { token ->
            transport.request(
                method = "GET",
                path = "/_matrix/client/v3/rooms/" + encode(roomId) + "/state/m.room.power_levels",
                accessToken = token,
                timeoutMs = 15_000,
            )
        }
        if (!response.isSuccess) return@runCatching RoomPermissions()
        val json = JSONObject(response.body)
        val me = currentUserId
        val users = json.optJSONObject("users")
        val myLevel = if (me != null && users != null && users.has(me)) {
            users.optInt(me, 0)
        } else {
            json.optInt("users_default", 0)
        }
        val events = json.optJSONObject("events")
        val redactLevel = events?.optInt("m.room.redact", json.optInt("state_default", 50))
            ?: json.optInt("state_default", 50)
        RoomPermissions(
            myPowerLevel = myLevel,
            redactLevel = redactLevel,
            canRedactOthers = myLevel >= redactLevel,
        )
    }
    // ---- M3：双账号对话测试 ----

    private var altSession: Session? = null

    private val altCrypto by lazy {
        MatrixCryptoService(
            context = activity.applicationContext,
            scope = scope,
            onSessionSaved = { refreshed -> altSession = refreshed },
            storeName = "matrix-sdk-alt-store",
            cacheName = "matrix-sdk-alt-cache",
            prefsName = "cinny_crypto_alt",
        )
    }

    override suspend fun altLogin(user: String, password: String): Result<String> = runCatching {
        val payload = JSONObject()
            .put("type", "m.login.password")
            .put(
                "identifier",
                JSONObject().put("type", "m.id.user").put("user", user.trim()),
            )
            .put("password", password)
            .put("initial_device_name", "CinnyClient M3 alt")
            .put("refresh_token", true)
        val response = transport.request(
            method = "POST",
            path = "/_matrix/client/v3/login",
            body = payload.toString(),
            timeoutMs = 25_000,
        )
        if (!response.isSuccess) {
            error("第二账号登录失败：HTTP ${response.status} ${response.body.take(200)}")
        }
        val json = JSONObject(response.body)
        val session = Session(
            homeserverUrl = baseUrl ?: "https://matrix.s0cl.top",
            userId = json.optString("user_id"),
            deviceId = json.optString("device_id"),
            accessToken = json.optString("access_token"),
            refreshToken = json.optString("refresh_token").takeIf { it.isNotBlank() },
        )
        altSession = session
        altCrypto.attach(session, transport).getOrThrow()
        Log.i(TAG, "alt account attached: ${session.userId} / ${session.deviceId}")
        session.userId
    }

    override suspend fun altCreateEncryptedRoom(roomName: String): Result<String> = runCatching {
        val token = accessToken ?: error("Not logged in")
        val alt = altSession ?: error("第二账号未登录")
        val initialState = JSONArray().put(
            JSONObject()
                .put("type", "m.room.encryption")
                .put("state_key", "")
                .put("content", JSONObject().put("algorithm", "m.megolm.v1.aes-sha2")),
        )
        val payload = JSONObject()
            .put("name", roomName.ifBlank { "M3 E2EE 对话测试" })
            .put("preset", "private_chat")
            .put("invite", JSONArray().put(alt.userId))
            .put("initial_state", initialState)
        val response = withAuthRetry { token ->
            transport.request(
                method = "POST",
                path = "/_matrix/client/v3/createRoom",
                accessToken = token,
                body = payload.toString(),
                timeoutMs = 25_000,
            )
        }
        if (!response.isSuccess) error("创建加密房间失败：HTTP ${response.status} ${response.body.take(200)}")
        JSONObject(response.body).optString("room_id").ifBlank { error("创建房间响应缺少 room_id") }
    }

    override suspend fun altJoinRoom(roomId: String): Result<Unit> = runCatching {
        val alt = altSession ?: error("第二账号未登录")
        val response = transport.request(
            method = "POST",
            path = "/_matrix/client/v3/join/${encode(roomId)}",
            accessToken = alt.accessToken,
            body = "{}",
            timeoutMs = 20_000,
        )
        if (!response.isSuccess) error("第二账号加入房间失败：HTTP ${response.status} ${response.body.take(200)}")
    }

    override suspend fun altSendEncrypted(roomId: String, body: String): Result<String> =
        altCrypto.sendText(roomId, body)

    override suspend fun altDecryptEvent(roomId: String, eventId: String): Result<String> =
        altCrypto.decrypt(roomId, eventId).map { it.body ?: (it.note ?: "无可显示内容") }

    override fun altUserId(): String? = altSession?.userId

    /**
     * 一键双账号对话测试：
     * ① test2 登录并启动独立加密客户端 → ② test 建加密房间并邀请 → ③ test2 加入
     * → ④ test2 加密发送 → ⑤ test 加密发送 → ⑥ 双方各自解密对方的消息。
     */
    override suspend fun runConversationTest(user: String, password: String): Result<String> = runCatching {
        val report = StringBuilder()
        val altUserId = altLogin(user, password).getOrThrow()
        report.append("① 第二账号登录：").append(altUserId).append('\n')

        val roomId = altCreateEncryptedRoom("M3 E2EE 对话测试").getOrThrow()
        report.append("② 加密房间：").append(roomId).append('\n')

        altJoinRoom(roomId).getOrThrow()
        report.append("③ 第二账号已加入\n")

        if (!altCrypto.joinAndAwait(roomId, 60_000)) error("第二客户端 60s 内未能加入房间")
        report.append("   第二客户端已同步到房间" + NL)
        if (!crypto.awaitRoom(roomId, 60_000)) error("主客户端 60s 内未同步到房间")
        report.append("   主客户端已同步到房间" + NL)

        val fromAlt = altSendEncrypted(roomId, "hello_from_test2").getOrThrow()
        report.append("④ test2 加密发送：").append(fromAlt).append('\n')

        val fromMain = sendEncryptedText(roomId, "hello_from_test").getOrThrow()
        report.append("⑤ test 加密发送：").append(fromMain.value).append('\n')

        kotlinx.coroutines.delay(3_000)

        val mainSees = runCatching { decryptLatestFrom(roomId, altUserId) }.getOrElse { "失败：" + it.message }
        report.append("⑥ test 解密 test2 的消息：").append(mainSees).append('\n')

        val altSees = runCatching { altDecryptLatestFrom(roomId, currentUserId ?: "") }.getOrElse { "失败：" + it.message }
        report.append("⑦ test2 解密 test 的消息：").append(altSees).append('\n')

        report.toString()
    }

    /** 主账号解密房间里由 [senderId] 发送的最新加密事件。 */
    private suspend fun decryptLatestFrom(roomId: String, senderId: String): String {
        val eventId = latestEncryptedEventFrom(roomId, senderId) ?: error("未找到来自 $senderId 的加密事件")
        val decrypted = crypto.decrypt(roomId, eventId).getOrThrow()
        return decrypted.body ?: (decrypted.note ?: "无可显示内容")
    }

    private suspend fun altDecryptLatestFrom(roomId: String, senderId: String): String {
        val eventId = latestEncryptedEventFrom(roomId, senderId) ?: error("未找到来自 $senderId 的加密事件")
        val decrypted = altCrypto.decrypt(roomId, eventId).getOrThrow()
        return decrypted.body ?: (decrypted.note ?: "无可显示内容")
    }

    private val NL: Char = 10.toChar()

    private suspend fun latestEncryptedEventFrom(roomId: String, senderId: String): String? {
        val response = withAuthRetry { token ->
            transport.request(
                method = "GET",
                path = "/_matrix/client/v3/rooms/" + encode(roomId) + "/messages?dir=b&limit=50",
                accessToken = token,
                timeoutMs = 20_000,
            )
        }
        if (!response.isSuccess) error("读取房间消息失败：HTTP " + response.status)
        val chunk = JSONObject(response.body).optJSONArray("chunk") ?: JSONArray()
        for (index in 0 until chunk.length()) {
            val event = chunk.optJSONObject(index) ?: continue
            if (event.optString("type") != "m.room.encrypted") continue
            if (event.optString("sender") != senderId) continue
            return event.optString("event_id")
        }
        return null
    }

    override suspend fun altDecryptLatest(roomId: String): Result<String> = runCatching {
        val token = accessToken ?: error("Not logged in")
        val response = withAuthRetry { token ->
            transport.request(
                method = "GET",
                path = "/_matrix/client/v3/rooms/${encode(roomId)}/messages?dir=b&limit=20",
                accessToken = token,
                timeoutMs = 20_000,
            )
        }
        if (!response.isSuccess) error("读取房间消息失败：HTTP ${response.status}")
        val chunk = JSONObject(response.body).optJSONArray("chunk") ?: JSONArray()
        var target: String? = null
        val altUser = altSession?.userId
        for (index in 0 until chunk.length()) {
            val event = chunk.optJSONObject(index) ?: continue
            if (event.optString("type") != "m.room.encrypted") continue
            if (event.optString("sender") == altUser) continue
            target = event.optString("event_id")
            break
        }
        val eventId = target ?: error("没有可解密的加密事件")
        altCrypto.decrypt(roomId, eventId).getOrThrow().let {
            "解密 $eventId -> " + (it.body ?: (it.note ?: "无可显示内容"))
        }
    }
    // ---- M3：推送 ----

    override fun setActiveRoom(roomId: String?) {
        activeRoomId = roomId
    }

    override suspend fun registerHttpPusher(topic: String, gatewayUrl: String): Result<Unit> = runCatching {
        val token = accessToken ?: error("Not logged in")
        val gateway = gatewayUrl.trimEnd('/') + "/" + topic
        val payload = JSONObject()
            .put("app_id", PUSH_APP_ID)
            .put("pushkey", topic)
            .put("kind", "http")
            .put("app_display_name", "CinnyClient")
            .put("device_display_name", "CinnyClient Android (M3)")
            .put("lang", "zh")
            .put("data", JSONObject().put("url", gateway).put("format", "event_id_only"))
        val response = withAuthRetry { token ->
            transport.request(
                method = "POST",
                path = "/_matrix/client/v3/pushers",
                accessToken = token,
                body = payload.toString(),
                timeoutMs = 20_000,
            )
        }
        if (!response.isSuccess) error("注册推送失败：HTTP ${response.status} ${response.body.take(200)}")
    }

    override suspend fun unregisterPusher(appId: String, pushkey: String): Result<Unit> = runCatching {
        val token = accessToken ?: error("Not logged in")
        val payload = JSONObject()
            .put("app_id", appId)
            .put("pushkey", pushkey)
            .put("kind", JSONObject.NULL)
        val response = withAuthRetry { token ->
            transport.request(
                method = "POST",
                path = "/_matrix/client/v3/pushers",
                accessToken = token,
                body = payload.toString(),
                timeoutMs = 20_000,
            )
        }
        if (!response.isSuccess) error("取消推送失败：HTTP ${response.status} ${response.body.take(200)}")
    }

    override suspend fun listPushers(): Result<List<PusherInfo>> = runCatching {
        val token = accessToken ?: error("Not logged in")
        val response = withAuthRetry { token ->
            transport.request(
                method = "GET",
                path = "/_matrix/client/v3/pushers",
                accessToken = token,
                timeoutMs = 20_000,
            )
        }
        if (!response.isSuccess) error("读取推送列表失败：HTTP ${response.status}")
        val pushers = JSONObject(response.body).optJSONArray("pushers") ?: JSONArray()
        buildList {
            for (index in 0 until pushers.length()) {
                val pusher = pushers.optJSONObject(index) ?: continue
                add(
                    PusherInfo(
                        appId = pusher.optString("app_id"),
                        pushkey = pusher.optString("pushkey"),
                        kind = pusher.optString("kind").takeIf { it.isNotBlank() && it != "null" },
                        url = pusher.optJSONObject("data")?.optString("url"),
                        appDisplayName = pusher.optString("app_display_name").takeIf { it.isNotBlank() },
                        deviceDisplayName = pusher.optString("device_display_name").takeIf { it.isNotBlank() },
                    ),
                )
            }
        }
    }


    /**
     * M3：带 401 重试的授权请求。
     *
     * Synapse 在开启 refresh_token 后 access token 只有几分钟有效期，
     * rust-sdk 与 WebView 同步都会各自刷新；这里保证开发者工具/推送等
     * 一次性请求不会因为 token 过期直接失败。
     */
    private suspend fun withAuthRetry(block: suspend (String) -> HttpResult): HttpResult {
        var token = accessToken ?: error("Not logged in")
        var response = block(token)
        if (response.status == 401 && refreshAccessToken()) {
            token = accessToken ?: error("Not logged in")
            response = block(token)
        }
        return response
    }

    fun close() {
        stopSync()
        crypto.detach()
        transport.close()
    }

    private fun startSync() {
        stopSync()
        syncJob = scope.launch {
            sync.value = SyncState.Connecting
            var since: String? = null
            while (isActive) {
                try {
                    val token = accessToken ?: break
                    val path = buildString {
                        append("/_matrix/client/v3/sync?timeout=30000")
                        if (!since.isNullOrBlank()) append("&since=").append(encode(since))
                    }
                    val response = withAuthRetry { requestToken ->
                        transport.request(
                            method = "GET",
                            path = path,
                            accessToken = requestToken,
                            timeoutMs = 40_000,
                        )
                    }
                    Log.i(TAG, "sync status=" + response.status + " len=" + response.body.length)
                    if (response.status == 401) {
                        if (refreshAccessToken()) {
                            since = null
                            sync.value = SyncState.Syncing
                            continue
                        }
                        sync.value = SyncState.Error
                        sessionState.value = SessionState.Failed("登录已过期，请重新登录")
                        break
                    }
                    if (!response.isSuccess) {
                        sync.value = SyncState.Offline
                        delay(3_000)
                        continue
                    }
                    val json = JSONObject(response.body)
                    since = json.optString("next_batch").takeIf { it.isNotBlank() } ?: since
                    applySync(json)
                    sync.value = SyncState.Syncing
                } catch (error: Throwable) {
                    Log.w(TAG, "sync loop failed", error)
                    sync.value = SyncState.Offline
                    delay(3_000)
                }
            }
        }
    }

    private fun stopSync() {
            syncJob?.cancel()
            syncJob = null
        }
    
        private suspend fun refreshAccessToken(): Boolean {
            val refresh = refreshToken ?: return false
            return runCatching {
                val response = transport.request(
                    method = "POST",
                    path = "/_matrix/client/v3/refresh",
                    body = JSONObject().put("refresh_token", refresh).toString(),
                    timeoutMs = 15_000,
                )
                if (!response.isSuccess) return@runCatching false
                val json = JSONObject(response.body)
                val newAccess = json.optString("access_token").takeIf { it.isNotBlank() }
                    ?: return@runCatching false
                val newRefresh = json.optString("refresh_token").takeIf { it.isNotBlank() } ?: refresh
                accessToken = newAccess
                refreshToken = newRefresh
                val current = (sessionState.value as? SessionState.LoggedIn)?.session
                if (current != null) {
                    val updated = current.copy(accessToken = newAccess, refreshToken = newRefresh)
                    saveSession(updated)
                    sessionState.value = SessionState.LoggedIn(updated)
                    // 两个传输层（WebView / rust-sdk）保持同一 token。
                    crypto.updateSession(updated)
                }
                Log.i(TAG, "access token refreshed")
                true
            }.getOrElse {
                Log.w(TAG, "token refresh failed", it)
                false
            }
        }
    
        private fun applySync(root: JSONObject) {
            val directRooms = parseDirectRooms(root)
            val join = root.optJSONObject("rooms")?.optJSONObject("join") ?: JSONObject()
            Log.i(TAG, "applySync joinRooms=${join.length()} directRooms=${directRooms.size}")
            val keys = join.keys()
            while (keys.hasNext()) {
                val roomId = keys.next()
                val room = join.optJSONObject(roomId) ?: continue
                val stateEvents = room.optJSONObject("state")?.optJSONArray("events")
                var name = ""
                var alias = ""
                var topic = ""
                var isSpace = false
                var hasChildState = false
                val children = mutableListOf<String>()
                fun readStateEvents(events: JSONArray?) {
                    if (events == null) return
                    for (index in 0 until events.length()) {
                        val event = events.optJSONObject(index) ?: continue
                        val content = event.optJSONObject("content") ?: JSONObject()
                        when (event.optString("type")) {
                            "m.room.name" -> name = content.optString("name")
                            "m.room.canonical_alias" -> alias = content.optString("alias")
                            "m.room.topic" -> topic = content.optString("topic")
                            "m.room.create" -> isSpace = content.optString("type") == "m.space"
                            "m.room.type" -> if (content.optString("type") == "m.space") isSpace = true
                            "m.space.child" -> {
                                hasChildState = true
                                event.optString("state_key").takeIf { it.isNotBlank() }?.let(children::add)
                            }
                        }
                    }
                }
                readStateEvents(stateEvents)
                readStateEvents(room.optJSONObject("timeline")?.optJSONArray("events"))
                if (hasChildState) spaceChildren[roomId] = children.distinct()
                val unread = room.optJSONObject("unread_notifications")
                val existing = roomMap[roomId]
                val lastMessage = room.optJSONObject("timeline")
                    ?.optJSONArray("events")
                    ?.let { array ->
                        for (i in array.length() - 1 downTo 0) {
                            val body = array.optJSONObject(i)?.optJSONObject("content")?.optString("body")
                            if (!body.isNullOrBlank()) return@let body
                        }
                        null
                    }
                roomMap[roomId] = RoomSummary(
                    roomId = roomId,
                    name = name.ifBlank { alias.ifBlank { existing?.name?.takeIf { it != roomId } ?: roomId } },
                    isDirect = directRooms.contains(roomId),
                    isSpace = isSpace,
                    unreadTotal = unread?.optInt("notification_count") ?: 0,
                    unreadHighlight = unread?.optInt("highlight_count") ?: 0,
                    topic = topic.takeIf { it.isNotBlank() } ?: existing?.topic,
                    lastMessage = lastMessage ?: existing?.lastMessage,
                )
                appendSyncTimeline(roomId, room)
                notifyFromSync(
                    roomId = roomId,
                    roomName = roomMap[roomId]?.name ?: roomId,
                    events = room.optJSONObject("timeline")?.optJSONArray("events"),
                )
            }
            parseSpaceOrder(root)?.let { spaceOrder = it }
            spacesState.value = roomMap.values
                .filter { it.isSpace }
                .map {
                    SpaceSummary(
                        spaceId = it.roomId,
                        name = it.name,
                        topic = it.topic,
                        avatarUrl = it.avatarUrl,
                        childRoomIds = spaceChildren[it.roomId].orEmpty(),
                    )
                }
                .sortedWith(
                    compareBy<SpaceSummary> {
                        val index = spaceOrder.indexOf(it.spaceId)
                        if (index < 0) Int.MAX_VALUE else index
                    }.thenBy { it.name.lowercase() },
                )
            roomsState.value = roomMap.values
                .filter { !it.isSpace }
                .sortedWith(compareByDescending<RoomSummary> { it.unreadTotal }.thenBy { it.name.lowercase() })
            parseInvites(root)
        }
    
        /**
         * M3：sync 到达后为「非当前房间、非本人」的新事件发系统通知。
         * 已通知的 event_id 去重，避免 sync 重放导致重复通知。
         */
        private fun notifyFromSync(roomId: String, roomName: String, events: JSONArray?) {
            if (events == null) return
            Log.i(TAG, "notify check room=" + roomId + " events=" + events.length() + " active=" + activeRoomId)
            if (roomId == activeRoomId) return
            for (index in 0 until events.length()) {
                val event = events.optJSONObject(index) ?: continue
                val sender = event.optString("sender").takeIf { it.isNotBlank() } ?: continue
                if (sender == currentUserId) continue
                val eventId = event.optString("event_id")
                if (eventId.isBlank()) continue
                when (event.optString("type")) {
                    "m.room.message" -> {
                        val content = event.optJSONObject("content")
                        val body = content?.optString("body").orEmpty()
                        if (body.isBlank()) continue
                        if (!notifiedEvents.add(eventId)) continue
                        val mentioned = content?.optJSONObject("mentions")?.optJSONArray("user_ids")
                            ?.let { ids ->
                                (0 until ids.length()).any { ids.optString(it) == currentUserId }
                            } ?: false
                        onNotify(NotificationEvent(roomId, roomName, sender, body, mentioned))
                    }
                    "m.room.encrypted" -> {
                        if (!notifiedEvents.add(eventId)) continue
                        // 加密消息：先用 rust-sdk 解密再通知（失败时给出占位文案）。
                        scope.launch {
                            val text = crypto.decrypt(roomId, eventId).getOrNull()?.body ?: "加密消息"
                            onNotify(NotificationEvent(roomId, roomName, sender, text, false))
                        }
                    }
                    "m.call.invite" -> {
                        if (!notifiedEvents.add(eventId)) continue
                        onNotify(NotificationEvent(roomId, roomName, sender, "通话邀请", isCall = true))
                    }
                }
                if (notifiedEvents.size > 512) {
                    val iterator = notifiedEvents.iterator()
                    repeat(128) { if (iterator.hasNext()) { iterator.next(); iterator.remove() } }
                }
            }
        }
    
        private fun parseSpaceOrder(root: JSONObject): List<String>? {
            val events = root.optJSONObject("account_data")?.optJSONArray("events") ?: return null
            for (index in 0 until events.length()) {
                val event = events.optJSONObject(index) ?: continue
                if (event.optString("type") != "in.cinny.spaces") continue
                val array = event.optJSONObject("content")?.optJSONArray("sidebar") ?: continue
                return buildList {
                    for (i in 0 until array.length()) {
                        array.optString(i).takeIf { it.isNotBlank() }?.let(::add)
                    }
                }
            }
            return null
        }
    
        private fun parseDirectRooms(root: JSONObject): Set<String> {
            val result = mutableSetOf<String>()
            val events = root.optJSONObject("account_data")?.optJSONArray("events") ?: return result
            for (index in 0 until events.length()) {
                val event = events.optJSONObject(index) ?: continue
                if (event.optString("type") != "m.direct") continue
                val content = event.optJSONObject("content") ?: continue
                val users = content.keys()
                while (users.hasNext()) {
                    val rooms = content.optJSONArray(users.next()) ?: continue
                    for (roomIndex in 0 until rooms.length()) {
                        rooms.optString(roomIndex)?.takeIf { it.isNotBlank() }?.let(result::add)
                    }
                }
            }
            return result
        }
    
        private fun parseInvites(root: JSONObject) {
            val invite = root.optJSONObject("rooms")?.optJSONObject("invite") ?: JSONObject()
            val result = mutableListOf<RoomSummary>()
            val keys = invite.keys()
            while (keys.hasNext()) {
                val roomId = keys.next()
                val room = invite.optJSONObject(roomId) ?: continue
                val events = room.optJSONObject("invite_state")?.optJSONArray("events")
                var name = ""
                var inviter: String? = null
                if (events != null) {
                    for (index in 0 until events.length()) {
                        val event = events.optJSONObject(index) ?: continue
                        val content = event.optJSONObject("content") ?: JSONObject()
                        when (event.optString("type")) {
                            "m.room.name" -> name = content.optString("name")
                            "m.room.member" -> if (content.optString("membership") == "invite") {
                                inviter = event.optString("sender").takeIf { it.isNotBlank() }
                            }
                        }
                    }
                }
                result.add(RoomSummary(roomId, name.ifBlank { roomId }, false, false, isInvite = true, inviter = inviter))
            }
            invitesState.value = result
        }
    
        private fun appendSyncTimeline(roomId: String, room: JSONObject) {
            val events = room.optJSONObject("timeline")?.optJSONArray("events") ?: return
            if (loadedTimelines.contains(roomId)) {
                val state = timelines[roomId] ?: return
                val incoming = parseEvents(events)
                if (incoming.isNotEmpty()) {
                    val merged = (state.value.items + incoming).distinctBy { it.eventId }.sortedBy { it.timestampMillis }
                    state.value = state.value.copy(items = merged)
                    scheduleDecryption(roomId, incoming)
                }
            }
            applyReactions(roomId, events)
        }
    
        private fun applyReactions(roomId: String, events: JSONArray) {
            val state = timelines[roomId] ?: return
            for (index in 0 until events.length()) {
                val event = events.optJSONObject(index) ?: continue
                if (event.optString("type") != "m.reaction") continue
                val relation = event.optJSONObject("content")?.optJSONObject("m.relates_to") ?: continue
                if (relation.optString("rel_type") != "m.annotation") continue
                val targetId = relation.optString("event_id")
                val key = relation.optString("key").ifBlank { "👍" }
                val sender = event.optString("sender")
                state.value = state.value.copy(items = state.value.items.map { item ->
                    if (item.eventId != targetId) item else {
                        val existing = item.reactions.firstOrNull { it.key == key }
                        val reactions = if (existing == null) {
                            item.reactions + Reaction(key, 1, sender == currentUserId)
                        } else {
                            item.reactions.map {
                                if (it.key == key) it.copy(count = it.count + 1, reactedByMe = it.reactedByMe || sender == currentUserId) else it
                            }
                        }
                        item.copy(reactions = reactions)
                    }
                })
            }
        }
    
        private suspend fun loadTimeline(roomId: String) {
            val state = timelines.getOrPut(roomId) { MutableStateFlow(TimelinePage(roomId)) }
            val token = accessToken ?: return
            val pinned = fetchPinnedEventIds(roomId, token)
            runCatching {
                val response = transport.request(
                    method = "GET",
                    path = "/_matrix/client/v3/rooms/${encode(roomId)}/messages?dir=b&limit=50",
                    accessToken = token,
                    timeoutMs = 25_000,
                )
                if (!response.isSuccess) error("加载时间线失败：HTTP ${response.status}")
                val json = JSONObject(response.body)
                val chunk = json.optJSONArray("chunk") ?: JSONArray()
                val items = parseEvents(chunk, pinned).sortedBy { it.timestampMillis }
                state.value = TimelinePage(roomId, items, json.optString("end").isNotBlank())
                scheduleDecryption(roomId, items)
            }.onFailure {
                Log.w(TAG, "loadTimeline failed for $roomId", it)
            }
        }
    
        /**
         * M3：把时间线里的 `m.room.encrypted` 事件交给 rust-sdk 解密，并回填到状态流。
         * 解密是异步的：UI 先显示「等待解密」，结果到达后自动刷新。
         */
        private fun scheduleDecryption(roomId: String, items: List<TimelineItem>) {
            if (!crypto.isReady()) return
            val targets = items.filter { it.isEncrypted && it.decryptedBody == null && it.decryptionError == "等待解密" }
            if (targets.isEmpty()) return
            scope.launch {
                targets.forEach { item ->
                    crypto.decrypt(roomId, item.eventId).onSuccess { decrypted ->
                        val state = timelines[roomId] ?: return@onSuccess
                        state.value = state.value.copy(
                            items = state.value.items.map { existing ->
                                if (existing.eventId != item.eventId) existing else existing.copy(
                                    decryptedBody = decrypted.body,
                                    decryptedMsgType = decrypted.msgType,
                                    isEdited = existing.isEdited || decrypted.isEdited,
                                    decryptionError = when {
                                        decrypted.unableToDecrypt -> decrypted.note ?: "无法解密"
                                        decrypted.body != null -> null
                                        else -> decrypted.note ?: "无可显示内容"
                                    },
                                )
                            },
                        )
                    }.onFailure { error ->
                        val state = timelines[roomId] ?: return@onFailure
                        state.value = state.value.copy(
                            items = state.value.items.map { existing ->
                                if (existing.eventId != item.eventId) existing
                                else existing.copy(decryptionError = error.message ?: "解密失败")
                            },
                        )
                    }
                }
            }
        }
    
        private suspend fun fetchPinnedEventIds(roomId: String, token: String): Set<String> = runCatching {
        val response = transport.request(
            method = "GET",
            path = "/_matrix/client/v3/rooms/" + encode(roomId) + "/state/m.room.pinned_events",
            accessToken = token,
            timeoutMs = 10_000,
        )
        if (!response.isSuccess) return@runCatching emptySet<String>()
        val array = JSONObject(response.body).optJSONArray("pinned") ?: return@runCatching emptySet<String>()
        buildSet { for (i in 0 until array.length()) array.optString(i).takeIf { it.isNotBlank() }?.let(::add) }
    }.getOrDefault(emptySet())

    private fun parseEvents(array: JSONArray, pinned: Set<String> = emptySet()): List<TimelineItem> {
        val items = mutableListOf<TimelineItem>()
        for (index in 0 until array.length()) {
            val event = array.optJSONObject(index) ?: continue
            if (event.optString("type") == "m.room.encrypted") {
                val eventId = event.optString("event_id")
                items.add(
                    TimelineItem(
                        eventId = eventId,
                        senderId = event.optString("sender").takeIf { it.isNotBlank() },
                        body = null,
                        timestampMillis = event.optLong("origin_server_ts"),
                        isEncrypted = true,
                        decryptionError = "等待解密",
                    ),
                )
                continue
            }
            if (event.optString("type") == "m.sticker") {
                val eventId = event.optString("event_id")
                val content = event.optJSONObject("content") ?: continue
                items.add(
                    TimelineItem(
                        eventId = eventId,
                        senderId = event.optString("sender").takeIf { it.isNotBlank() },
                        body = content.optString("body").takeIf { it.isNotBlank() } ?: "贴纸",
                        timestampMillis = event.optLong("origin_server_ts"),
                        msgType = "m.sticker",
                        mediaUrl = content.optString("url").takeIf { it.isNotBlank() },
                        mediaMime = content.optJSONObject("info")?.optString("mimetype"),
                    ),
                )
                continue
            }
            if (event.optString("type") != "m.room.message") continue
            val content = event.optJSONObject("content") ?: continue
            val body = content.optString("body").takeIf { it.isNotBlank() } ?: continue
            val relates = content.optJSONObject("m.relates_to")
            val reply = relates?.optJSONObject("m.in_reply_to")?.optString("event_id")?.takeIf { it.isNotBlank() }
            val thread = relates?.takeIf { it.optString("rel_type") == "m.thread" }?.optString("event_id")?.takeIf { it.isNotBlank() }
            val file = content.optJSONObject("file")
            val media = content.optString("url").ifBlank {
                file?.optString("url").orEmpty()
            }.takeIf { it.isNotBlank() }
            val mediaKey = file?.optJSONObject("key")?.optString("k")?.takeIf { it.isNotBlank() }
            val mediaIv = file?.optString("iv")?.takeIf { it.isNotBlank() }
            val mediaMime = content.optJSONObject("info")?.optString("mimetype")
                ?.takeIf { it.isNotBlank() }
                ?: file?.optString("mimetype")?.takeIf { it.isNotBlank() }
            val eventId = event.optString("event_id")
            items.add(
                TimelineItem(
                    eventId = eventId,
                    senderId = event.optString("sender").takeIf { it.isNotBlank() },
                    body = body,
                    timestampMillis = event.optLong("origin_server_ts"),
                    formattedBody = content.optString("formatted_body").takeIf { it.isNotBlank() },
                    msgType = content.optString("msgtype").takeIf { it.isNotBlank() },
                    mediaUrl = media,
                    mediaKey = mediaKey,
                    mediaIv = mediaIv,
                    mediaMime = mediaMime,
                    isEdited = content.has("m.new_content"),
                    isPinned = pinned.contains(eventId),
                    replyToEventId = reply,
                    threadRootId = thread,
                ),
            )
        }
        return items
    }

    private fun saveSession(session: Session) {
        prefs.edit()
            .putString(KEY_HOMESERVER, session.homeserverUrl)
            .putString(KEY_USER_ID, session.userId)
            .putString(KEY_DEVICE_ID, session.deviceId)
            .putString(KEY_ACCESS_TOKEN, session.accessToken)
            .putString(KEY_REFRESH_TOKEN, session.refreshToken)
            .apply()
    }

    private fun loadSession(): Session? {
        val homeserver = prefs.getString(KEY_HOMESERVER, null) ?: return null
        val userId = prefs.getString(KEY_USER_ID, null) ?: return null
        val deviceId = prefs.getString(KEY_DEVICE_ID, null) ?: return null
        val accessToken = prefs.getString(KEY_ACCESS_TOKEN, null) ?: return null
        return Session(
            homeserverUrl = homeserver,
            userId = userId,
            deviceId = deviceId,
            accessToken = accessToken,
            refreshToken = prefs.getString(KEY_REFRESH_TOKEN, null),
        )
    }

    private fun clearSession() {
        prefs.edit().clear().apply()
    }

    private fun normalizeServer(input: String): String {
        val trimmed = input.trim().trimEnd('/')
        if (trimmed.isBlank()) return "https://matrix.org"
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "https://$trimmed"
    }

    private fun String.isIpAddress(): Boolean =
        count { it == '.' } == 3 && all { it.isDigit() || it == '.' }

    private fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    companion object {
        private const val TAG = "WebViewMatrixEngine"
        private const val PREFS_NAME = "cinny_m1_webview_session"
        const val PUSH_APP_ID = "top.s0cl.cinny"
        private val MEDIA_MESSAGE_TYPES = setOf("m.image", "m.video", "m.audio", "m.file", "m.sticker")
        private const val KEY_HOMESERVER = "homeserver"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
    }
}
