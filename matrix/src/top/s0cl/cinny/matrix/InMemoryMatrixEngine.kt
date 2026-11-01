package top.s0cl.cinny.matrix

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

/**
 * M1 placeholder engine used by previews, unit tests and iOS until the Swift
 * MatrixEngine implementation is wired. It intentionally mirrors the same
 * contract as the Android Rust engine.
 */
class InMemoryMatrixEngine : MatrixEngine {
    private val sessionState = MutableStateFlow<SessionState>(SessionState.LoggedOut)
    private val sync = MutableStateFlow(SyncState.Idle)
    private val roomState = MutableStateFlow(
        listOf(
            RoomSummary(
                roomId = "!cinny:example.org",
                name = "Cinny",
                isDirect = false,
                isSpace = false,
                unreadTotal = 2,
                unreadHighlight = 1,
                topic = "Welcome to Cinny",
                lastMessage = "M1 timeline preview",
            ),
            RoomSummary(
                roomId = "!alice:example.org",
                name = "Alice",
                isDirect = true,
                isSpace = false,
                unreadTotal = 0,
                lastMessage = "See you later",
            ),
        ),
    )
    private val timelines = mutableMapOf<String, MutableStateFlow<TimelinePage>>()

    override val session: StateFlow<SessionState> = sessionState.asStateFlow()
    override val syncState: StateFlow<SyncState> = sync.asStateFlow()

    override suspend fun restoreSession(): Result<Session> {
        sessionState.value = SessionState.LoggedOut
        return Result.failure(IllegalStateException("No saved session in preview engine"))
    }

    override suspend fun login(server: String, credentials: Credentials): Result<Session> {
        if (credentials.userId.isBlank() || credentials.password.isBlank()) {
            return Result.failure(IllegalArgumentException("Username and password are required"))
        }
        val session = Session(
            homeserverUrl = server.ifBlank { "https://matrix.org" },
            userId = if (credentials.userId.startsWith("@")) credentials.userId else "@${credentials.userId}:example.org",
            deviceId = "PREVIEW",
            accessToken = "preview-token",
        )
        sessionState.value = SessionState.LoggedIn(session)
        sync.value = SyncState.Syncing
        return Result.success(session)
    }

    override suspend fun logout(): Result<Unit> {
        sessionState.value = SessionState.LoggedOut
        sync.value = SyncState.Idle
        return Result.success(Unit)
    }

    override fun rooms(): Flow<List<RoomSummary>> = roomState.asStateFlow()

    override fun spaces(): Flow<List<SpaceSummary>> = MutableStateFlow(
        listOf(SpaceSummary("!space:example.org", "Cinny Space", childRoomIds = listOf("!cinny:example.org"))),
    ).asStateFlow()

    override fun invites(): Flow<List<RoomSummary>> = MutableStateFlow(emptyList<RoomSummary>()).asStateFlow()

    override fun timeline(roomId: String): Flow<TimelinePage> =
        timelines.getOrPut(roomId) {
            MutableStateFlow(
                TimelinePage(
                    roomId = roomId,
                    items = listOf(
                        TimelineItem("$1", "@alice:example.org", "Hello from the M1 preview engine", 1L),
                        TimelineItem("$2", "@you:example.org", "This is a local timeline item", 2L),
                    ),
                    canPaginateBackwards = false,
                ),
            )
        }.map { it }

    override suspend fun send(roomId: String, draft: MessageDraft): Result<EventId> {
        val flow = timelines.getOrPut(roomId) { MutableStateFlow(TimelinePage(roomId)) }
        val next = flow.value.items + TimelineItem(
            eventId = "local-${flow.value.items.size + 1}",
            senderId = "@you:example.org",
            body = draft.body,
            timestampMillis = System.currentTimeMillis(),
        )
        flow.value = flow.value.copy(items = next)
        return Result.success(EventId("local-${next.size}"))
    }

    override suspend fun sendReadReceipt(roomId: String, eventId: String, private: Boolean): Result<Unit> =
        Result.success(Unit)

    override suspend fun mediaBytes(mxcUrl: String): Result<ByteArray> = Result.success(ByteArray(0))

    override suspend fun sendImage(roomId: String, bytes: ByteArray, mimeType: String, fileName: String): Result<EventId> {
        val state = timelines.getOrPut(roomId) { MutableStateFlow(TimelinePage(roomId)) }
        val eventId = "image-${System.currentTimeMillis()}"
        state.value = state.value.copy(
            items = state.value.items + TimelineItem(
                eventId = eventId,
                senderId = "@you:example.org",
                body = fileName,
                timestampMillis = System.currentTimeMillis(),
                msgType = "m.image",
                mediaUrl = "mxc://example.org/$eventId",
            ),
        )
        return Result.success(EventId(eventId))
    }

    override suspend fun edit(roomId: String, eventId: String, body: String): Result<Unit> {
        val state = timelines.getOrPut(roomId) { MutableStateFlow(TimelinePage(roomId)) }
        state.value = state.value.copy(
            items = state.value.items.map {
                if (it.eventId == eventId) it.copy(body = body, isEdited = true) else it
            },
        )
        return Result.success(Unit)
    }

    override suspend fun redact(roomId: String, eventId: String, reason: String?): Result<Unit> {
        val state = timelines.getOrPut(roomId) { MutableStateFlow(TimelinePage(roomId)) }
        state.value = state.value.copy(items = state.value.items.filterNot { it.eventId == eventId })
        return Result.success(Unit)
    }

    override suspend fun toggleReaction(roomId: String, eventId: String, key: String): Result<Boolean> {
        val state = timelines.getOrPut(roomId) { MutableStateFlow(TimelinePage(roomId)) }
        state.value = state.value.copy(
            items = state.value.items.map { item ->
                if (item.eventId != eventId) item else {
                    val existing = item.reactions.firstOrNull { it.key == key }
                    val reactions = if (existing == null) {
                        item.reactions + Reaction(key, 1, true)
                    } else if (existing.reactedByMe) {
                        item.reactions.mapNotNull { if (it.key == key) null else it }
                    } else {
                        item.reactions.map { if (it.key == key) it.copy(count = it.count + 1, reactedByMe = true) else it }
                    }
                    item.copy(reactions = reactions)
                }
            },
        )
        return Result.success(true)
    }

    override suspend fun setPinned(roomId: String, eventId: String, pinned: Boolean): Result<Unit> {
        val state = timelines.getOrPut(roomId) { MutableStateFlow(TimelinePage(roomId)) }
        state.value = state.value.copy(
            items = state.value.items.map { if (it.eventId == eventId) it.copy(isPinned = pinned) else it },
        )
        return Result.success(Unit)
    }

    override suspend fun joinRoom(roomIdOrAlias: String): Result<String> = Result.success(roomIdOrAlias)
    override suspend fun createSpace(name: String): Result<String> = Result.success("!new-space:example.org")
    override suspend fun reorderSpaces(spaceIds: List<String>): Result<Unit> = Result.success(Unit)
    override suspend fun leaveRoom(roomId: String): Result<Unit> = Result.success(Unit)
    override suspend fun acceptInvite(roomId: String): Result<Unit> = Result.success(Unit)
    override suspend fun declineInvite(roomId: String): Result<Unit> = Result.success(Unit)

    override suspend fun search(term: String, roomId: String?): Result<List<SearchResult>> {
        val results = roomState.value.flatMap { room ->
            timelines[room.roomId]?.value?.items.orEmpty()
                .filter { it.body?.contains(term, ignoreCase = true) == true }
                .map {
                    SearchResult(room.roomId, room.name, it.eventId, it.senderId, it.body.orEmpty(), it.timestampMillis)
                }
        }
        return Result.success(results)
    }

    override suspend fun publicRooms(server: String?): Result<List<PublicRoom>> = Result.success(
        listOf(PublicRoom("#cinny:example.org", "Cinny", "Public room", joinedMembers = 42)),
    )
}
