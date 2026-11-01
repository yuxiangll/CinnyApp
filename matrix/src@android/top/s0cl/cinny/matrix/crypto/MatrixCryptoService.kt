package top.s0cl.cinny.matrix.crypto

import android.content.Context
import android.util.Log
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import org.matrix.rustcomponents.sdk.BackupState
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.ClientBuilder
import org.matrix.rustcomponents.sdk.MsgLikeKind
import org.matrix.rustcomponents.sdk.MessageType
import org.matrix.rustcomponents.sdk.SessionVerificationController
import org.matrix.rustcomponents.sdk.SessionVerificationControllerDelegate
import org.matrix.rustcomponents.sdk.SessionVerificationData
import org.matrix.rustcomponents.sdk.SessionVerificationRequestDetails
import org.matrix.rustcomponents.sdk.SlidingSyncVersion
import org.matrix.rustcomponents.sdk.SlidingSyncVersionBuilder
import org.matrix.rustcomponents.sdk.SqliteStoreBuilder
import org.matrix.rustcomponents.sdk.SyncService
import org.matrix.rustcomponents.sdk.Timeline
import org.matrix.rustcomponents.sdk.TimelineItemContent
import org.matrix.rustcomponents.sdk.VerificationState
import org.matrix.rustcomponents.sdk.Session as SdkSession
import top.s0cl.cinny.matrix.CryptoStatus
import top.s0cl.cinny.matrix.DecryptedEvent
import top.s0cl.cinny.matrix.DevEventResult
import top.s0cl.cinny.matrix.Session
import top.s0cl.cinny.matrix.VerificationUiState
import top.s0cl.cinny.matrix.WebViewTransport
import top.s0cl.cinny.matrix.net.LocalRelayServer

/**
 * M3：matrix-rust-sdk E2EE 服务。
 *
 * 架构：
 *   rust-sdk  --HTTP-->  127.0.0.1:relay  --WebView fetch-->  homeserver(Chromium TLS)
 *
 * 这样 rust-sdk 只处理明文 HTTP，TLS 交给 Chrome 网络栈，绕过 WAF 的指纹拦截。
 * CSS（crypto state store）+ sync 都在 rust-sdk 内部完成，UI 侧只读取状态与解密结果。
 */
class MatrixCryptoService(
    private val context: Context,
    private val scope: CoroutineScope,
    /**
     * rust-sdk 会自动刷新 access token（refresh_token 轮换）。
     * 刷新后必须回写给 WebView 传输层，否则两边 token 不一致会 401。
     */
    private val onSessionSaved: (Session) -> Unit = {},
    /** 允许同一进程内跑第二个加密客户端（双账号对话测试）。 */
    private val storeName: String = "matrix-sdk-store",
    private val cacheName: String = "matrix-sdk-cache",
    private val prefsName: String = "cinny_crypto",
) {
    private val statusState = MutableStateFlow(CryptoStatus())
    val status: StateFlow<CryptoStatus> = statusState.asStateFlow()

    private val verificationUi = MutableStateFlow(VerificationUiState())
    val verification: StateFlow<VerificationUiState> = verificationUi.asStateFlow()

    private var relay: LocalRelayServer? = null
    private var client: Client? = null
    private var syncService: SyncService? = null

    @Volatile
    private var syncTicks: Int = 0
    private var verificationController: SessionVerificationController? = null
    private val taskHandles = mutableListOf<org.matrix.rustcomponents.sdk.TaskHandle>()
    private val timelines = HashMap<String, Timeline>()

    @Volatile
    private var attachedToken: String? = null

    @Volatile
    private var currentSession: Session? = null

    @Volatile
    private var realHomeserver: String = ""

    private val sessionDelegate = SessionDelegate()

    val homeserverOverride: String?
        get() = relay?.let { "http://127.0.0.1:${it.port}" }

    fun isReady(): Boolean = client != null

    suspend fun attach(session: Session, transport: WebViewTransport): Result<Unit> = runCatching {
        detachInternal()
        attachedToken = session.accessToken
        currentSession = session
        realHomeserver = session.homeserverUrl
        statusState.value = CryptoStatus(phase = "启动本地中继…")
        val relayServer = LocalRelayServer { request ->
            transport.relay(request.method, request.path, request.headers, request.body)
        }
        val port = relayServer.start(scope)
        relay = relayServer
        val localUrl = "http://127.0.0.1:" + port
        prepareStore(session.deviceId)
        val storePath = File(context.filesDir, storeName).apply { mkdirs() }.absolutePath
        val cachePath = File(context.cacheDir, cacheName).apply { mkdirs() }.absolutePath
        statusState.value = CryptoStatus(
            phase = "连接 rust-sdk…",
            relayPort = port,
            deviceId = session.deviceId,
        )
        val serverName = serverNameOf(session.userId)
        val built = buildClient(localUrl, serverName, storePath, cachePath, preferredVariant())
        client = built
        statusState.update { it.copy(phase = "恢复会话…") }
        built.restoreSession(
            SdkSession(
                accessToken = session.accessToken,
                refreshToken = session.refreshToken,
                userId = session.userId,
                deviceId = session.deviceId,
                homeserverUrl = localUrl,
                oauthData = null,
                slidingSyncVersion = SlidingSyncVersion.NONE,
            ),
        )
        statusState.update { it.copy(phase = "初始化加密…") }
        val encryption = built.encryption()
        withTimeoutOrNull(E2EE_INIT_TIMEOUT_MS) { encryption.waitForE2eeInitializationTasks() }
        statusState.update {
            it.copy(
                initialized = true,
                phase = "加密已就绪",
                identityEd25519 = runCatching { encryption.ed25519Key() }.getOrNull(),
                identityCurve25519 = runCatching { encryption.curve25519Key() }.getOrNull(),
                verificationState = encryption.verificationState().label(),
                backupState = encryption.backupState().label(),
                recoveryState = encryption.recoveryState().toString(),
            )
        }
        startSync(built)
        startStateListeners(encryption)
        runCatching {
            val controller = built.getSessionVerificationController()
            controller.setDelegate(Delegate())
            verificationController = controller
        }.onFailure { Log.w(TAG, "verification controller unavailable", it) }
        Log.i(TAG, "crypto attached: relay=" + port + " store=" + storePath)
        Unit
    }.onFailure { error ->
        Log.w(TAG, "crypto attach failed", error)
        val port = relay?.port ?: 0
        val report = if (port > 0) {
            runCatching {
                probeBuilds(
                    "http://127.0.0.1:" + port,
                    serverNameOf(session.userId),
                    File(context.filesDir, "matrix-sdk-store-probe").apply { mkdirs() }.absolutePath,
                    File(context.cacheDir, "matrix-sdk-cache-probe").apply { mkdirs() }.absolutePath,
                )
            }.getOrElse { "probe failed: " + it.userMessage() }
        } else {
            ""
        }
        probeReport = report
        statusState.update { state ->
            state.copy(initialized = false, phase = "初始化失败", error = error.userMessage())
        }
    }


    @Volatile
    var probeReport: String? = null

    private var variantOverride: Int? = null

    /**
     * 只有「仅设置 homeserver_url」的配置可用：
     * 一旦同时设置 server_name，rust-sdk 会强制对真实域名做 .well-known 发现，
     * 而该请求走 rustls，会被 WAF 重置。variant=5 时 server 为 null，
     * SDK 会从用户 ID（@user:s0cl.top）推导服务端名称。
     */
    fun preferredVariant(): Int = variantOverride ?: 5

    /**
     * 不同版本的 rust-sdk 对 homeserver URL / server name 的组合要求不同：
     * 有的版本强制 `.well-known` 发现，有的版本要求显式 URL。
     * 这里依次尝试若干组合，记录结果并返回第一个可用配置。
     */
    suspend fun probeBuilds(localUrl: String, serverName: String, storePath: String, cachePath: String): String {
        val report = StringBuilder()
        for (variant in 1..5) {
            val started = System.currentTimeMillis()
            val result = runCatching {
                val client = buildClient(localUrl, serverName, storePath, cachePath, variant, keepStore = false)
                val info = "homeserver=" + client.homeserver() + " server=" + client.server()
                runCatching { client.close() }
                info
            }
            val line = "variant=$variant " +
                result.fold(
                    onSuccess = { "OK " + it },
                    onFailure = { "FAIL " + (it::class.simpleName ?: "?") + ": " + (it.message ?: "") },
                ) + " (" + (System.currentTimeMillis() - started) + "ms)"
            Log.i(PROBE_TAG, line)
            report.append(line).append('\n')
        }
        return report.toString()
    }

    suspend fun buildClient(
        localUrl: String,
        serverName: String,
        storePath: String,
        cachePath: String,
        variant: Int,
        keepStore: Boolean = true,
    ): Client {
        var builder = ClientBuilder()
            .userAgent("CinnyClient Android M3")
            .slidingSyncVersionBuilder(SlidingSyncVersionBuilder.NONE)
            .autoEnableCrossSigning(true)
            .setSessionDelegate(sessionDelegate)
        builder = when (variant) {
            1 -> builder.homeserverUrl(localUrl).serverName(serverName)
            2 -> builder.homeserverUrl(localUrl).serverName(serverName).disableWellKnownLookup(true)
            3 -> builder.serverNameOrHomeserverUrl(serverName)
            4 -> builder.serverName(serverName).disableWellKnownLookup(true)
            else -> builder.homeserverUrl(localUrl)
        }
        builder = builder.sqliteStore(SqliteStoreBuilder(storePath, cachePath))
        return builder.build()
    }

    /** rust-sdk 会话（含刷新后的 token）与本应用的会话存储之间的桥。 */
    /**
     * rust-sdk 的加密存储与本机设备绑定。重新登录（新 device_id）时必须清理，
     * 否则会复用旧设备的 Olm/Megolm 身份，导致验证与解密状态混乱。
     */
    private fun prepareStore(deviceId: String) {
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_STORE_DEVICE, null)
        if (stored != null && stored != deviceId) {
            Log.i(TAG, "device changed (" + stored + " -> " + deviceId + "), wiping rust-sdk store")
            runCatching { File(context.filesDir, storeName).deleteRecursively() }
            runCatching { File(context.cacheDir, cacheName).deleteRecursively() }
        }
        prefs.edit().putString(KEY_STORE_DEVICE, deviceId).apply()
    }

    /** 退出登录时清空加密存储。 */
    fun wipe() {
        detach()
        runCatching { File(context.filesDir, storeName).deleteRecursively() }
        runCatching { File(context.cacheDir, cacheName).deleteRecursively() }
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().clear().apply()
    }

    private inner class SessionDelegate : org.matrix.rustcomponents.sdk.ClientSessionDelegate {
        override fun retrieveSessionFromKeychain(accessToken: String): SdkSession {
            val session = currentSession ?: throw IllegalStateException("no cached session")
            return sdkSessionOf(session)
        }

        override fun saveSessionInKeychain(session: SdkSession) {
            val mapped = Session(
                homeserverUrl = realHomeserver,
                userId = session.userId,
                deviceId = session.deviceId,
                accessToken = session.accessToken,
                refreshToken = session.refreshToken,
            )
            currentSession = mapped
            attachedToken = mapped.accessToken
            runCatching { onSessionSaved(mapped) }
                .onFailure { Log.w(TAG, "persist refreshed session failed", it) }
            Log.i(TAG, "access token refreshed by rust-sdk")
        }
    }

    private fun sdkSessionOf(session: Session): SdkSession = SdkSession(
        accessToken = session.accessToken,
        refreshToken = session.refreshToken,
        userId = session.userId,
        deviceId = session.deviceId,
        homeserverUrl = relay?.let { "http://127.0.0.1:" + it.port } ?: session.homeserverUrl,
        oauthData = null,
        slidingSyncVersion = SlidingSyncVersion.NONE,
    )

    /** WebView 传输层刷新 token 后，把新 token 同步给 rust-sdk。 */
    suspend fun updateSession(session: Session) {
        val built = client ?: return
        currentSession = session
        attachedToken = session.accessToken
        // rust-sdk 已经持有会话时再 restoreSession 会抛 AlreadyInitialized，可安全忽略：
        // 它自己会刷新 token，并通过 SessionDelegate 回写。
        runCatching { built.restoreSession(sdkSessionOf(session)) }
            .onFailure { Log.i(TAG, "rust-sdk session update skipped: " + it.userMessage()) }
    }
    fun detach() {
        scope.launch { detachInternal() }
    }

    private suspend fun detachInternal() {
        taskHandles.forEach { runCatching { it.close() } }
        taskHandles.clear()
        runCatching { syncService?.stop() }
        syncService = null
        runCatching { verificationController?.setDelegate(null) }
        verificationController = null
        timelines.clear()
        runCatching { client?.close() }
        client = null
        relay?.stop()
        relay = null
        if (attachedToken != null) {
            attachedToken = null
            statusState.value = CryptoStatus()
            verificationUi.value = VerificationUiState()
        }
    }

    /**
     * 使用 classic sync（SyncService/RoomListService 需要 sliding sync，
     * 而 s0cl.top 是固定版本 Synapse，这里直接用 syncV2 后台循环）。
     */
    private suspend fun startSync(built: Client) {
        runCatching {
            val settings = org.matrix.rustcomponents.sdk.SyncSettingsV2(SYNC_TIMEOUT_MS, false)
            val handle = built.syncV2(
                settings,
                object : org.matrix.rustcomponents.sdk.SyncListenerV2 {
                    override fun onUpdate(response: org.matrix.rustcomponents.sdk.SyncResponseV2) {
                        syncTicks += 1
                        if (syncTicks <= 3 || syncTicks % 20 == 0) {
                            val rooms = runCatching { built.rooms().size }.getOrDefault(-1)
                            Log.i(TAG, "rust-sdk sync #" + syncTicks + " rooms=" + rooms + " ids=" + runCatching { built.rooms().map { room -> room.id() } }.getOrDefault(emptyList<String>()))
                        }
                        statusState.update {
                            it.copy(phase = "同步中（rust-sdk #" + syncTicks + "）")
                        }
                    }
                },
            )
            taskHandles += handle
            statusState.update { it.copy(phase = "同步中（rust-sdk）") }
            Log.i(TAG, "rust-sdk classic sync started")
        }.onFailure {
            Log.w(TAG, "rust-sdk sync failed to start", it)
            statusState.update { state -> state.copy(error = "同步启动失败：" + it.userMessage()) }
        }
    }

    private fun startStateListeners(encryption: org.matrix.rustcomponents.sdk.Encryption) {
        runCatching {
            taskHandles += encryption.verificationStateListener(
                object : org.matrix.rustcomponents.sdk.VerificationStateListener {
                    override fun onUpdate(state: VerificationState) {
                        statusState.update { it.copy(verificationState = state.label()) }
                    }
                },
            )
            taskHandles += encryption.backupStateListener(
                object : org.matrix.rustcomponents.sdk.BackupStateListener {
                    override fun onUpdate(state: BackupState) {
                        statusState.update { it.copy(backupState = state.label()) }
                    }
                },
            )
            taskHandles += encryption.recoveryStateListener(
                object : org.matrix.rustcomponents.sdk.RecoveryStateListener {
                    override fun onUpdate(state: org.matrix.rustcomponents.sdk.RecoveryState) {
                        statusState.update { it.copy(recoveryState = state.label()) }
                    }
                },
            )
        }.onFailure { Log.w(TAG, "state listeners failed", it) }
    }

    /**
     * 等待 rust-sdk 把房间状态推进到 Joined（用于双账号对话测试的时序控制）。
     * 收到邀请时直接用 SDK 的 room.join()，确保加密状态与成员状态一致。
     */
    suspend fun joinAndAwait(roomId: String, timeoutMs: Long = 60_000): Boolean {
        val localId = localRoomId(roomId)
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastMembership: org.matrix.rustcomponents.sdk.Membership? = null
        while (System.currentTimeMillis() < deadline) {
            val room = client?.let { runCatching { it.getRoom(localId) }.getOrNull() }
            val membership = room?.let { runCatching { it.membership() }.getOrNull() }
            lastMembership = membership
            if (membership == org.matrix.rustcomponents.sdk.Membership.JOINED) return true
            if (room != null && membership == org.matrix.rustcomponents.sdk.Membership.INVITED) {
                runCatching { room.join() }
            }
            kotlinx.coroutines.delay(700)
        }
        Log.w(TAG, "joinAndAwait timeout, last membership=" + lastMembership)
        return false
    }

    /** 等待 rust-sdk 同步到某个房间（用于双账号对话测试的时序控制）。 */
    suspend fun awaitRoom(roomId: String, timeoutMs: Long = 30_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        val localId = localRoomId(roomId)
        while (System.currentTimeMillis() < deadline) {
            val built = client ?: return false
            if (runCatching { built.getRoom(localId) }.getOrNull() != null) return true
            kotlinx.coroutines.delay(400)
        }
        return false
    }

    /** 把 EventTimelineItem 转换为可展示的 DecryptedEvent（需在 timeline 销毁前调用）。 */
    private fun descToEvent(eventId: String, item: org.matrix.rustcomponents.sdk.EventTimelineItem): DecryptedEvent {
        val content = item.content
        return when (content) {
            is TimelineItemContent.MsgLike -> {
                when (val kind = content.content.kind) {
                    is MsgLikeKind.Message -> DecryptedEvent(
                        eventId = eventId,
                        body = kind.content.body,
                        msgType = kind.content.msgType.matrixType(),
                        isEdited = kind.content.isEdited,
                    )
                    is MsgLikeKind.UnableToDecrypt -> DecryptedEvent(
                        eventId = eventId,
                        unableToDecrypt = true,
                        note = "缺少会话密钥",
                    )
                    else -> DecryptedEvent(eventId = eventId, note = "非文本消息")
                }
            }
            else -> DecryptedEvent(eventId = eventId, note = "非消息事件")
        }
    }

    suspend fun decrypt(roomId: String, eventId: String): Result<DecryptedEvent> = runCatching {
        val built = client ?: error("E2EE 未初始化")
        val localId = localRoomId(roomId)
        val room = built.getRoom(localId) ?: error("rust-sdk 尚未同步该房间")
        // 事件可能不在 live 窗口内：用聚焦 timeline 读取解密结果。
        // 注意：必须在 destroy() 之前读取 item.content，否则 FFI 句柄已失效。
        var created = false
        val focused = runCatching {
            room.timelineWithConfiguration(
                org.matrix.rustcomponents.sdk.TimelineConfiguration(
                    focus = org.matrix.rustcomponents.sdk.TimelineFocus.Event(
                        eventId = eventId,
                        numContextEvents = 2u,
                        threadMode = uniffi.matrix_sdk_ui.TimelineEventFocusThreadMode.Automatic(false),
                    ),
                    filter = org.matrix.rustcomponents.sdk.TimelineFilter.All,
                    internalIdPrefix = null,
                    dateDividerMode = org.matrix.rustcomponents.sdk.DateDividerMode.DAILY,
                    trackReadReceipts = uniffi.matrix_sdk_ui.TimelineReadReceiptTracking.DISABLED,
                    reportUtds = false,
                ),
            ).also { created = true }
        }.getOrElse {
            created = false
            timelines[roomId] ?: room.timeline().also { cached -> timelines[roomId] = cached }
        }
        try {
            val item = focused.getEventTimelineItemByEventId(eventId)
            descToEvent(eventId, item)
        } finally {
            if (created) runCatching { focused.destroy() }
        }
    }.onFailure { Log.w(TAG, "decrypt failed: $eventId", it) }

    /**
     * M2：加载消息附带的媒体。
     *
     * 使用 rust-sdk 的 `MediaSource`（来自解密后的 MessageType.Image/File/...），
     * SDK 会自动下载并解密加密附件。
     */
    suspend fun loadMedia(roomId: String, eventId: String): Result<org.matrix.rustcomponents.sdk.MediaSource> = runCatching {
        val built = client ?: error("E2EE 未初始化")
        val localId = localRoomId(roomId)
        val room = built.getRoom(localId) ?: error("rust-sdk 尚未同步该房间")
        val timeline = timelines[localId] ?: room.timeline().also { timelines[localId] = it }
        val item = timeline.getEventTimelineItemByEventId(eventId)
        val content = item.content as? TimelineItemContent.MsgLike
            ?: error("不是消息事件")
        val message = content.content.kind as? MsgLikeKind.Message
            ?: error("消息已撤回或不可解密")
        when (val type = message.content.msgType) {
            is org.matrix.rustcomponents.sdk.MessageType.Image -> type.content.source
            is org.matrix.rustcomponents.sdk.MessageType.File -> type.content.source
            is org.matrix.rustcomponents.sdk.MessageType.Video -> type.content.source
            is org.matrix.rustcomponents.sdk.MessageType.Audio -> type.content.source
            else -> error("该消息不含媒体")
        }
    }

    /** 用 rust-sdk 下载 + 解密媒体，返回字节。 */
    suspend fun mediaBytesOf(source: org.matrix.rustcomponents.sdk.MediaSource): Result<ByteArray> = runCatching {
        val built = client ?: error("E2EE 未初始化")
        built.getMediaContent(source)
    }

    suspend fun sendViaCrypto(
        roomId: String,
        eventType: String,
        contentJson: String,
    ): Result<DevEventResult> = runCatching {
        val built = client ?: error("E2EE 未初始化")
        val localId = localRoomId(roomId)
        val room = built.getRoom(localId) ?: error("rust-sdk 尚未同步该房间 [" + roomId + "]（已有：" + runCatching { built.rooms().map { room -> room.id() } }.getOrDefault(emptyList()) + "）")
        val eventId = room.sendRaw(eventType, contentJson, null)
        DevEventResult(eventId = eventId, message = "已通过 rust-sdk 发送")
    }

    suspend fun sendText(roomId: String, body: String): Result<String> = runCatching {
        val content = org.json.JSONObject().put("msgtype", "m.text").put("body", body).toString()
        val result = sendViaCrypto(roomId, "m.room.message", content).getOrThrow()
        result.eventId ?: "queued"
    }

    suspend fun enableRecovery(): Result<String> = runCatching {
        val built = client ?: error("E2EE 未初始化")
        val recoveryKey = built.encryption().enableRecovery(
            true,
            "",
            object : org.matrix.rustcomponents.sdk.EnableRecoveryProgressListener {
                override fun onUpdate(progress: org.matrix.rustcomponents.sdk.EnableRecoveryProgress) {
                    statusState.update { it.copy(phase = "备份进度：" + progress.label()) }
                }
            },
        )
        statusState.update { it.copy(recoveryKey = recoveryKey, phase = "密钥备份已启用") }
        recoveryKey
    }.onFailure {
        Log.w(TAG, "enableRecovery failed", it)
        statusState.update { state -> state.copy(error = "启用备份失败：" + it.userMessage()) }
    }

    suspend fun recover(recoveryKey: String): Result<Unit> = runCatching {
        val built = client ?: error("E2EE 未初始化")
        built.encryption().recover(recoveryKey.trim())
        statusState.update { it.copy(recoveryKey = recoveryKey.trim(), phase = "已恢复密钥备份") }
    }.onFailure {
        Log.w(TAG, "recover failed", it)
        statusState.update { state -> state.copy(error = "恢复备份失败：" + it.userMessage()) }
    }

    suspend fun requestVerification(): Result<Unit> = runCatching {
        val controller = verificationController ?: error("验证控制器不可用")
        controller.requestDeviceVerification()
        verificationUi.update {
            it.copy(active = true, stage = "已发起验证请求，等待对方接受", finished = false, error = null)
        }
    }

    suspend fun acceptVerification(): Result<Unit> = runCatching {
        val controller = verificationController ?: error("验证控制器不可用")
        controller.acceptVerificationRequest()
    }

    suspend fun startSas(): Result<Unit> = runCatching {
        val controller = verificationController ?: error("验证控制器不可用")
        controller.startSasVerification()
    }

    suspend fun approveSas(): Result<Unit> = runCatching {
        val controller = verificationController ?: error("验证控制器不可用")
        controller.approveVerification()
    }

    suspend fun declineVerification(): Result<Unit> = runCatching {
        verificationController?.declineVerification()
        verificationUi.update { it.copy(active = false, stage = "已拒绝验证") }
    }

    fun verificationController(): SessionVerificationController? = verificationController

    private inner class Delegate : SessionVerificationControllerDelegate {
        override fun didReceiveVerificationRequest(details: SessionVerificationRequestDetails) {
            verificationUi.update {
                it.copy(
                    active = true,
                    stage = "收到验证请求",
                    requestFrom = details.senderProfile?.userId ?: details.deviceId,
                    canAccept = true,
                    finished = false,
                    error = null,
                )
            }
        }

        override fun didAcceptVerificationRequest() {
            verificationUi.update { it.copy(stage = "已接受请求，等待 SAS", canAccept = false) }
        }

        override fun didStartSasVerification() {
            verificationUi.update { it.copy(stage = "SAS 已开始，请比对表情") }
        }

        override fun didReceiveVerificationData(data: SessionVerificationData) {
            when (data) {
                is SessionVerificationData.Emojis -> {
                    val emojis = data.emojis.map { emoji ->
                        runCatching { emoji.symbol() + " " + emoji.description() }
                            .getOrDefault("?")
                    }
                    verificationUi.update {
                        it.copy(stage = "请与对方比对 7 个表情", emojis = emojis, canApprove = true)
                    }
                }
                is SessionVerificationData.Decimals -> {
                    verificationUi.update {
                        it.copy(
                            stage = "请与对方比对 3 组数字",
                            decimals = data.values.map { value -> value.toInt() },
                            canApprove = true,
                        )
                    }
                }
                else -> Unit
            }
        }

        override fun didFail() {
            verificationUi.update { it.copy(stage = "验证失败", active = false, error = "验证失败", canApprove = false) }
        }

        override fun didCancel() {
            verificationUi.update { it.copy(stage = "验证已取消", active = false, canApprove = false, canAccept = false) }
        }

        override fun didFinish() {
            verificationUi.update {
                it.copy(stage = "验证完成", active = false, finished = true, canApprove = false, canAccept = false)
            }
        }
    }

    /**
     * 因为 client 只配置了 homeserver_url（server name 未知），
     * rust-sdk 内部的房间 ID 是「本地形式」（不带 `:server` 后缀），
     * 与 /sync 返回的完整 ID 需要互相转换。
     */
    fun localRoomId(roomId: String): String =
        if (roomId.startsWith("!")) roomId.substringBefore(':') else roomId

    companion object {
        private const val TAG = "MatrixCryptoService"
        private const val E2EE_INIT_TIMEOUT_MS = 30_000L
        private const val SYNC_TIMEOUT_MS = 30_000uL
        private const val PREFS_CRYPTO = "cinny_crypto"
        private const val KEY_STORE_DEVICE = "device_id"
        private const val PROBE_TAG = "CryptoProbe"
    }
}

private fun serverNameOf(userId: String): String = userId.substringAfter(':', userId)

private fun Throwable.userMessage(): String = message ?: this::class.simpleName ?: "未知错误"

private fun VerificationState.label(): String = when (this) {
    VerificationState.VERIFIED -> "已验证"
    VerificationState.UNVERIFIED -> "未验证"
    VerificationState.UNKNOWN -> "未知"
    else -> "未知"
}

private fun BackupState.label(): String = when (this) {
    BackupState.ENABLED -> "已启用"
    BackupState.ENABLING -> "启用中"
    BackupState.CREATING -> "创建中"
    BackupState.RESUMING -> "恢复中"
    BackupState.DOWNLOADING -> "下载中"
    BackupState.DISABLING -> "停用中"
    BackupState.UNKNOWN -> "未知"
    else -> "未知"
}

private fun org.matrix.rustcomponents.sdk.RecoveryState.label(): String = when (this) {
    org.matrix.rustcomponents.sdk.RecoveryState.ENABLED -> "已启用"
    org.matrix.rustcomponents.sdk.RecoveryState.DISABLED -> "未启用"
    org.matrix.rustcomponents.sdk.RecoveryState.INCOMPLETE -> "不完整"
    else -> "未知"
}

private fun org.matrix.rustcomponents.sdk.EnableRecoveryProgress.label(): String = when (this) {
    is org.matrix.rustcomponents.sdk.EnableRecoveryProgress.Starting -> "准备中"
    is org.matrix.rustcomponents.sdk.EnableRecoveryProgress.CreatingBackup -> "创建备份"
    is org.matrix.rustcomponents.sdk.EnableRecoveryProgress.CreatingRecoveryKey -> "创建恢复密钥"
    is org.matrix.rustcomponents.sdk.EnableRecoveryProgress.BackingUp -> "上传房间密钥"
    is org.matrix.rustcomponents.sdk.EnableRecoveryProgress.Done -> "完成"
    is org.matrix.rustcomponents.sdk.EnableRecoveryProgress.RoomKeyUploadError -> "房间密钥上传失败"
    else -> toString()
}

private fun MessageType.matrixType(): String = when (this) {
    is MessageType.Text -> "m.text"
    is MessageType.Emote -> "m.emote"
    is MessageType.Notice -> "m.notice"
    is MessageType.Image -> "m.image"
    is MessageType.File -> "m.file"
    is MessageType.Audio -> "m.audio"
    is MessageType.Video -> "m.video"
    is MessageType.Location -> "m.location"
    is MessageType.Gallery -> "m.gallery"
    else -> "m.unknown"
}

private fun MsgLikeKind.stableLabel(): String = when (this) {
    is MsgLikeKind.Sticker -> "贴纸"
    is MsgLikeKind.Poll -> "投票"
    is MsgLikeKind.Redacted -> "已撤回"
    is MsgLikeKind.LiveLocation -> "实时位置"
    else -> "其他消息"
}
