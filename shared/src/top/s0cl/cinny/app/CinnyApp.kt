package top.s0cl.cinny.app

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import top.s0cl.cinny.call.CallIslandHost
import top.s0cl.cinny.core.design.*
import top.s0cl.cinny.core.settings.AppSettings
import top.s0cl.cinny.core.settings.InMemorySettingsStore
import top.s0cl.cinny.core.settings.SettingsStore
import top.s0cl.cinny.matrix.*
import top.s0cl.cinny.navigation.MainDestination
import top.s0cl.cinny.platform.PlatformBackHandler
import top.s0cl.cinny.platform.rememberFilePicker
import top.s0cl.cinny.platform.rememberMediaPicker
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

@Composable
fun CinnyApp(engine: MatrixEngine? = null, settingsStore: SettingsStore? = null) {
    val actualEngine = engine ?: remember { InMemoryMatrixEngine() }
    val actualSettingsStore = settingsStore ?: remember { InMemorySettingsStore() }
    val settings by actualSettingsStore.settings.collectAsState()
    val systemDark = isSystemInDarkTheme()
    val themeKind = CinnyThemeKind.entries.firstOrNull { it.name == settings.themeKind } ?: CinnyThemeKind.System
    val darkTheme = when (themeKind) {
        CinnyThemeKind.Dark, CinnyThemeKind.Butter -> true
        CinnyThemeKind.Light, CinnyThemeKind.Silver -> false
        CinnyThemeKind.System -> systemDark
    }
    val controller = remember(darkTheme) {
        ThemeController(
            colorSchemeMode = if (darkTheme) ColorSchemeMode.Dark else ColorSchemeMode.Light,
        )
    }
    MiuixTheme(controller = controller) {
        CinnyTheme(themeKind) {
            val sessionState by actualEngine.session.collectAsState()
            LaunchedEffect(actualEngine) {
                actualEngine.restoreSession()
            }
            val loggedIn = sessionState as? SessionState.LoggedIn
            if (loggedIn != null) {
                MainShell(
                    engine = actualEngine,
                    session = loggedIn.session,
                    settings = settings,
                    settingsStore = actualSettingsStore,
                )
            } else if (sessionState is SessionState.Restoring) {
                LoadingScreen("正在恢复会话…")
            } else {
                AuthScreen(actualEngine, (sessionState as? SessionState.Failed)?.message)
            }
        }
    }
}

@Composable
private fun LoadingScreen(message: String) {
    val colors = LocalCinnyColors.current
    Box(
        Modifier
            .fillMaxSize()
            .background(colors.background.container)
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Text(text = message, modifier = Modifier.padding(top = CinnySpacing.S300))
        }
    }
}

@Composable
private fun MainShell(
    engine: MatrixEngine,
    session: Session,
    settings: AppSettings,
    settingsStore: SettingsStore,
) {
    val colors = LocalCinnyColors.current
    var selectedName by rememberSaveable { mutableStateOf(MainDestination.Home.name) }
    var selectedRoomId by rememberSaveable { mutableStateOf<String?>(null) }
    var jumpToEventId by rememberSaveable { mutableStateOf<String?>(null) }
    var overlay by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = MainDestination.entries.firstOrNull { it.name == selectedName } ?: MainDestination.Home
    val syncState by engine.syncState.collectAsState()

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(colors.background.container)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding(),
    ) {
        // M4：600dp / 840dp 断点：Compact 底部导航，Medium 侧边栏，Expanded 列表-详情双栏。
        val expanded = maxWidth >= CinnyBreakpoints.MediumMax
        val compact = maxWidth < CinnyBreakpoints.CompactMax

        val content: @Composable () -> Unit = {
            DestinationContent(
                engine = engine,
                session = session,
                selected = selected,
                syncState = syncState,
                settings = settings,
                settingsStore = settingsStore,
                onOpenRoom = { selectedRoomId = it },
                onSearch = { overlay = "search" },
                onExplore = { overlay = "explore" },
                onOpenCrypto = { overlay = "crypto" },
                onOpenDevTools = { overlay = "devtools" },
                onOpenCall = { overlay = "call" },
            )
        }

        when {
            overlay != null -> {
                val close: () -> Unit = { overlay = null }
                when (overlay) {
                    "search" -> SearchScreen(
                        engine = engine,
                        onBack = close,
                        onOpenRoom = { room, event ->
                            selectedRoomId = room
                            jumpToEventId = event
                            overlay = null
                        },
                    )
                    "explore" -> ExploreScreen(
                        engine = engine,
                        onBack = close,
                        onOpenRoom = { selectedRoomId = it; overlay = null },
                    )
                    "crypto" -> EncryptionScreen(engine = engine, onBack = close)
                    "devtools" -> DevToolsScreen(engine = engine, onBack = close)
                    "call" -> CallIslandHost(url = settings.callUrl, roomId = "", onClose = close)
                }
            }

            selectedRoomId != null && !expanded -> {
                RoomScreen(
                    engine = engine,
                    roomId = selectedRoomId!!,
                    settings = settings,
                    selfUserId = session.userId,
                    jumpToEventId = jumpToEventId,
                    onBack = { selectedRoomId = null; jumpToEventId = null },
                )
            }

            compact -> {
                Column(Modifier.fillMaxSize()) {
                    TopAppBar(title = selected.title)
                    Box(Modifier.weight(1f)) { content() }
                    NavigationBar {
                        MainDestination.entries.forEach { destination ->
                            NavigationBarItem(
                                selected = destination == selected,
                                onClick = { selectedName = destination.name },
                                icon = destination.icon,
                                label = destination.title,
                            )
                        }
                    }
                }
            }

            else -> {
                Row(Modifier.fillMaxSize()) {
                    NavigationRail(
                        selected = selected,
                        onSelect = { selectedName = it.name },
                    )
                    if (expanded) {
                        Column(Modifier.width(CinnyDimensions.DetailListWidth).fillMaxHeight()) {
                            TopAppBar(title = selected.title)
                            Box(Modifier.weight(1f)) { content() }
                        }
                        VerticalDivider()
                        Column(Modifier.weight(1f).fillMaxHeight()) {
                            if (selectedRoomId != null) {
                                RoomScreen(
                                    engine = engine,
                                    roomId = selectedRoomId!!,
                                    settings = settings,
                                    selfUserId = session.userId,
                                    jumpToEventId = jumpToEventId,
                                    onBack = { selectedRoomId = null; jumpToEventId = null },
                                )
                            } else {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(
                                        text = "选择一个房间开始聊天",
                                        color = colors.background.onContainer.copy(alpha = 0.7f),
                                    )
                                }
                            }
                        }
                    } else {
                        Column(Modifier.weight(1f).fillMaxHeight()) {
                            TopAppBar(title = selected.title)
                            Box(Modifier.weight(1f)) { content() }
                        }
                    }
                }
            }
        }
    }
}

/** M4：侧边导航（Medium / Expanded 宽度）。 */
@Composable
private fun NavigationRail(
    selected: MainDestination,
    onSelect: (MainDestination) -> Unit,
) {
    val colors = LocalCinnyColors.current
    Column(
        modifier = Modifier
            .width(CinnyDimensions.NavRailWidth)
            .fillMaxHeight()
            .background(colors.background.container)
            .padding(vertical = CinnySpacing.S300, horizontal = CinnySpacing.S200),
        verticalArrangement = Arrangement.spacedBy(CinnySpacing.S100),
    ) {
        MainDestination.entries.forEach { destination ->
            val isSelected = destination == selected
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(CinnyRadii.R400))
                    .background(
                        if (isSelected) colors.background.containerActive else colors.background.container,
                    )
                    .clickable { onSelect(destination) }
                    .padding(vertical = CinnySpacing.S200),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                top.yukonga.miuix.kmp.basic.Icon(
                    imageVector = destination.icon,
                    contentDescription = destination.title,
                    modifier = Modifier.size(20.dp),
                    tint = if (isSelected) colors.primary.main else colors.background.onContainer,
                )
                Text(
                    text = destination.title,
                    color = if (isSelected) colors.primary.main else colors.background.onContainer,
                    fontSize = 11.sp,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun VerticalDivider() {
    val colors = LocalCinnyColors.current
    Box(
        Modifier
            .fillMaxHeight()
            .width(1.dp)
            .background(colors.background.containerActive),
    )
}

@Composable
private fun DestinationContent(
    engine: MatrixEngine,
    session: Session,
    selected: MainDestination,
    syncState: SyncState,
    settings: AppSettings,
    settingsStore: SettingsStore,
    onOpenRoom: (String) -> Unit,
    onSearch: () -> Unit,
    onExplore: () -> Unit,
    onOpenCrypto: () -> Unit,
    onOpenDevTools: () -> Unit,
    onOpenCall: () -> Unit,
) {
    when (selected) {
        MainDestination.Home -> RoomListScreen(engine, false, syncState, onOpenRoom, onSearch, onExplore)
        MainDestination.Direct -> RoomListScreen(engine, true, syncState, onOpenRoom, onSearch, onExplore)
        MainDestination.Spaces -> SpacesScreen(engine, onOpenRoom)
        MainDestination.Inbox -> InboxScreen(engine, onOpenRoom)
        MainDestination.Me -> SettingsScreen(
            engine = engine,
            session = session,
            settings = settings,
            onUpdate = settingsStore::update,
            onLogout = { },
            onOpenCrypto = onOpenCrypto,
            onOpenDevTools = onOpenDevTools,
            onOpenCall = onOpenCall,
        )
    }
}

@Composable
private fun AuthScreen(engine: MatrixEngine, initialError: String?) {
    val colors = LocalCinnyColors.current
    var server by rememberSaveable { mutableStateOf("matrix.org") }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf(initialError) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background.container)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(CinnySpacing.S400),
        verticalArrangement = Arrangement.spacedBy(CinnySpacing.S300),
    ) {
        Text(text = "Cinny", fontSize = 40.sp, color = colors.background.onContainer)
        Text(text = "M1 · 登录", color = colors.background.onContainer)
        Card {
            Column(
                modifier = Modifier.padding(CinnySpacing.S300),
                verticalArrangement = Arrangement.spacedBy(CinnySpacing.S300),
            ) {
                TextField(
                    value = server,
                    onValueChange = { server = it },
                    label = "Homeserver",
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                TextField(
                    value = username,
                    onValueChange = { username = it },
                    label = "用户名 / MXID",
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                TextField(
                    value = password,
                    onValueChange = { password = it },
                    label = "密码",
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                    ),
                    visualTransformation = PasswordVisualTransformation(),
                )
                Button(
                    onClick = {
                        if (busy) return@Button
                        busy = true
                        error = null
                        scope.launch {
                            engine.login(server, Credentials(username.trim(), password))
                                .onFailure { error = it.message ?: "登录失败" }
                            busy = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (busy) "登录中…" else "登录")
                }
            }
        }
        if (!error.isNullOrBlank()) {
            Text(text = userFacingError(error), color = colors.critical.main)
        }
        Text(
            text = "M3 E2EE / 密钥备份 / 推送 / 通话能力岛已接入。",
            color = colors.background.onContainer.copy(alpha = 0.7f),
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun RoomListScreen(
    engine: MatrixEngine,
    direct: Boolean,
    syncState: SyncState,
    onOpenRoom: (String) -> Unit,
    onSearch: () -> Unit,
    onExplore: () -> Unit,
) {
    val colors = LocalCinnyColors.current
    val rooms by engine.rooms().collectAsState(initial = emptyList())
    val filtered = remember(rooms, direct) {
        rooms.filter { it.isDirect == direct }.sortedBy { it.name.lowercase() }
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = CinnySpacing.S400, vertical = CinnySpacing.S200),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (direct) "私信" else "房间",
                fontSize = 14.sp,
                color = colors.background.onContainer,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "搜索",
                modifier = Modifier.clickable(onClick = onSearch).padding(horizontal = CinnySpacing.S200),
                color = colors.primary.main,
                fontSize = 12.sp,
            )
            Text(
                text = "探索",
                modifier = Modifier.clickable(onClick = onExplore).padding(horizontal = CinnySpacing.S200),
                color = colors.primary.main,
                fontSize = 12.sp,
            )
            Text(
                text = when (syncState) {
                    SyncState.Syncing -> "同步中"
                    SyncState.Connecting -> "连接中"
                    SyncState.Offline -> "离线"
                    SyncState.Error -> "同步错误"
                    SyncState.Idle -> "空闲"
                },
                fontSize = 12.sp,
                color = colors.background.onContainer.copy(alpha = 0.7f),
            )
        }
        if (filtered.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无房间", color = colors.background.onContainer.copy(alpha = 0.7f))
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(filtered, key = { it.roomId }) { room ->
                    BasicComponent(
                        title = room.name,
                        summary = buildString {
                            if (room.unreadTotal > 0) append("${room.unreadTotal} 条未读")
                            if (room.unreadHighlight > 0) append(" · ${room.unreadHighlight} 条提及")
                            if (room.topic != null) {
                                if (isNotEmpty()) append(" · ")
                                append(room.topic)
                            }
                        }.ifBlank { room.roomId },
                        onClick = { onOpenRoom(room.roomId) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun RoomScreen(
    engine: MatrixEngine,
    roomId: String,
    settings: AppSettings,
    selfUserId: String? = null,
    jumpToEventId: String? = null,
    onBack: () -> Unit,
) {
    val colors = LocalCinnyColors.current
    val autoLoadMedia = settings.autoLoadMedia
    val messageLayout = settings.messageLayout
    val sendOnEnter = settings.sendOnEnter
    PlatformBackHandler(onBack = onBack)
    val page by engine.timeline(roomId).collectAsState(initial = TimelinePage(roomId))
    val rooms by engine.rooms().collectAsState(initial = emptyList())
    val roomName = rooms.firstOrNull { it.roomId == roomId }?.name ?: roomId
    val listState = rememberLazyListState()
    var draft by rememberSaveable { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var actionItem by remember { mutableStateOf<TimelineItem?>(null) }
    var replyTo by remember { mutableStateOf<TimelineItem?>(null) }
    var threadRoot by remember { mutableStateOf<TimelineItem?>(null) }
    var editing by remember { mutableStateOf<TimelineItem?>(null) }
    var threadViewRoot by remember { mutableStateOf<String?>(null) }
    var forwardItem by remember { mutableStateOf<TimelineItem?>(null) }
    var historyItem by remember { mutableStateOf<TimelineItem?>(null) }
    var historyTexts by remember { mutableStateOf<List<String>>(emptyList()) }
    var permissions by remember { mutableStateOf<RoomPermissions?>(null) }
    var showEmoji by remember { mutableStateOf(false) }
    val roomsForForward by engine.rooms().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val pickImage = rememberMediaPicker { picked ->
        scope.launch { engine.sendImage(roomId, picked.bytes, picked.mimeType, picked.fileName) }
    }
    val pickFile = rememberFilePicker { picked ->
        scope.launch { engine.sendImage(roomId, picked.bytes, picked.mimeType, picked.fileName) }
    }

    DisposableEffect(roomId) {
        engine.setActiveRoom(roomId)
        onDispose { engine.setActiveRoom(null) }
    }

    LaunchedEffect(roomId) {
        permissions = engine.roomPermissions(roomId).getOrNull()
    }

    LaunchedEffect(jumpToEventId, page.items.size) {
        val target = jumpToEventId ?: return@LaunchedEffect
        if (page.items.none { it.eventId == target }) {
            engine.loadEventContext(roomId, target)
        }
        kotlinx.coroutines.delay(300)
        val index = page.items.indexOfFirst { it.eventId == target }
        if (index >= 0) {
            val reversedIndex = (page.items.size - 1 - index).coerceAtLeast(0)
            runCatching { listState.animateScrollToItem(reversedIndex) }
        }
    }

    LaunchedEffect(page.items.lastOrNull()?.eventId) {
        page.items.lastOrNull()?.eventId?.takeIf { it.isNotBlank() }?.let {
            engine.sendReadReceipt(roomId, it, false)
        }
    }

    threadViewRoot?.let { root ->
        ThreadScreen(
            engine = engine,
            roomId = roomId,
            rootEventId = root,
            onBack = { threadViewRoot = null },
        )
        return
    }

    Column(
        modifier = Modifier.fillMaxSize().background(colors.surface.container)
            .windowInsetsPadding(WindowInsets.safeDrawing).imePadding(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().background(colors.surface.container)
                .padding(horizontal = CinnySpacing.S300, vertical = CinnySpacing.S200),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (LocalLayoutDirection.current == LayoutDirection.Rtl) "返回 ›" else "‹ 返回",
                modifier = Modifier.clickable(onClick = onBack).padding(CinnySpacing.S200),
                color = colors.primary.main,
                fontSize = 14.sp,
            )
            Text(
                text = roomName,
                modifier = Modifier.weight(1f).padding(start = CinnySpacing.S200),
                color = colors.surface.onContainer,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        HorizontalDivider()
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            reverseLayout = true,
        ) {
            items(page.items.asReversed(), key = { it.eventId.ifBlank { it.timestampMillis.toString() } }) { item ->
                val isOwn = selfUserId != null && item.senderId == selfUserId
                val bubble = messageLayout == "Bubble"
                val compact = messageLayout == "Compact"
                Column(
                    modifier = Modifier.fillMaxWidth()
                        .combinedClickable(onClick = { }, onLongClick = { actionItem = item })
                        .padding(
                            start = CinnySpacing.S400,
                            end = CinnySpacing.S400,
                            top = if (compact) CinnySpacing.S100 else CinnySpacing.S200,
                            bottom = if (compact) CinnySpacing.S100 else CinnySpacing.S200,
                        ),
                    horizontalAlignment = if (bubble && isOwn) Alignment.End else Alignment.Start,
                ) {
                    if (!compact) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = item.senderId ?: "unknown",
                                color = colors.primary.main,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            if (item.isPinned) Text("  📌", fontSize = 11.sp)
                            if (item.isEdited) {
                                Text(
                                    "  (已编辑)",
                                    fontSize = 11.sp,
                                    color = colors.surface.onContainer.copy(alpha = 0.6f),
                                )
                            }
                        }
                    }
                    if (item.replyToEventId != null) {
                        Text("回复 ${item.replyToEventId}", fontSize = 11.sp, color = colors.primary.main)
                    }
                    val contentModifier = if (bubble) {
                        Modifier
                            .fillMaxWidth(0.82f)
                            .clip(RoundedCornerShape(CinnyRadii.R400))
                            .background(
                                if (isOwn) colors.background.containerActive else colors.background.container,
                            )
                            .padding(CinnySpacing.S300)
                    } else {
                        Modifier
                    }
                    Column(modifier = contentModifier) {
                        if (compact) {
                            Row {
                                Text(
                                    text = (item.senderId ?: "unknown") + ": ",
                                    color = colors.primary.main,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (item.isPinned) Text("📌 ", fontSize = 11.sp)
                                if (item.isEncrypted) {
                                    EncryptedMessageBody(item, colors.surface.onContainer)
                                } else {
                                    MessageBody(item, colors.surface.onContainer)
                                }
                            }
                        } else if (item.isEncrypted) {
                            EncryptedMessageBody(item, colors.surface.onContainer)
                        } else {
                            MessageBody(item, colors.surface.onContainer)
                        }
                        if (isMediaMessage(item)) {
                            MediaContent(engine, roomId, item, autoLoad = autoLoadMedia)
                        } else if (!item.isEncrypted) {
                            singleUrlIn(item.body)?.let { url -> UrlPreviewCard(engine, url) }
                        }
                        if (item.reactions.isNotEmpty()) {
                            Text(
                                item.reactions.joinToString("   ") { "${it.key} ${it.count}" },
                                fontSize = 12.sp,
                                color = colors.primary.main,
                                modifier = Modifier.clickable {
                                    scope.launch { engine.toggleReaction(roomId, item.eventId, "👍") }
                                },
                            )
                        }
                    }
                }
            }
        }
        HorizontalDivider()
        if (replyTo != null) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = CinnySpacing.S300, vertical = CinnySpacing.S100),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("回复 ${replyTo?.senderId ?: "消息"}", fontSize = 12.sp, color = colors.primary.main)
                Spacer(Modifier.weight(1f))
                Text("取消", fontSize = 12.sp, modifier = Modifier.clickable { replyTo = null })
            }
        }
        if (threadRoot != null) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = CinnySpacing.S300, vertical = CinnySpacing.S100),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Thread: ${threadRoot?.senderId ?: "消息"}", fontSize = 12.sp, color = colors.primary.main)
                Spacer(Modifier.weight(1f))
                Text("取消", fontSize = 12.sp, modifier = Modifier.clickable { threadRoot = null })
            }
        }
        if (editing != null) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = CinnySpacing.S300, vertical = CinnySpacing.S100),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("编辑中", fontSize = 12.sp, color = colors.critical.main)
                Spacer(Modifier.weight(1f))
                Text("取消", fontSize = 12.sp, modifier = Modifier.clickable { editing = null; draft = "" })
            }
        }
        if (showEmoji) {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = CinnySpacing.S300),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                listOf(
                    "😀", "😁", "😂", "🤣", "😊", "😍", "🤔", "😎", "😭", "😡",
                    "👍", "👎", "🙏", "🎉", "❤️", "🔥", "✅", "❌", "👀", "🤝",
                ).forEach { emoji ->
                    Text(
                        text = emoji,
                        fontSize = 22.sp,
                        modifier = Modifier
                            .clickable { draft += emoji }
                            .padding(CinnySpacing.S200),
                    )
                }
            }
        }
        val sendDraft: () -> Unit = {
            val text = draft.trim()
            if (text.isNotEmpty() && !sending) {
                sending = true
                scope.launch {
                    val editTarget = editing
                    val result = if (editTarget != null) {
                        engine.edit(roomId, editTarget.eventId, text)
                    } else {
                        engine.send(
                            roomId,
                            MessageDraft(
                                body = text,
                                replyToEventId = if (threadRoot == null) replyTo?.eventId else null,
                                threadRootEventId = threadRoot?.eventId,
                            ),
                        )
                    }
                    result.onSuccess {
                        draft = ""
                        replyTo = null
                        threadRoot = null
                        editing = null
                        listState.animateScrollToItem(0)
                    }
                    sending = false
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(CinnySpacing.S300),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(CinnySpacing.S200),
        ) {
            Text(
                text = "😊",
                fontSize = 18.sp,
                modifier = Modifier
                    .sizeIn(minWidth = CinnyDimensions.MinTouchTarget, minHeight = CinnyDimensions.MinTouchTarget)
                    .wrapContentSize(Alignment.Center)
                    .clickable { showEmoji = !showEmoji }
                    .semantics { contentDescription = "表情" },
            )
            Text(
                text = "+",
                modifier = Modifier
                    .sizeIn(minWidth = CinnyDimensions.MinTouchTarget, minHeight = CinnyDimensions.MinTouchTarget)
                    .wrapContentSize(Alignment.Center)
                    .clickable(onClick = pickImage)
                    .semantics { contentDescription = "发送图片" },
                color = colors.primary.main,
                fontSize = 22.sp,
            )
            Text(
                text = "📎",
                modifier = Modifier
                    .sizeIn(minWidth = CinnyDimensions.MinTouchTarget, minHeight = CinnyDimensions.MinTouchTarget)
                    .wrapContentSize(Alignment.Center)
                    .clickable(onClick = pickFile)
                    .semantics { contentDescription = "发送文件" },
                fontSize = 18.sp,
            )
            TextField(
                value = draft,
                onValueChange = { draft = it },
                label = if (editing != null) "编辑消息…" else "发送消息…",
                modifier = Modifier.weight(1f).onPreviewKeyEvent { event ->
                    if (!sendOnEnter) return@onPreviewKeyEvent false
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Enter && !event.isShiftPressed) {
                        sendDraft()
                        true
                    } else {
                        false
                    }
                },
                singleLine = false,
            )
            Button(onClick = sendDraft) {
                Text(if (sending) "…" else if (editing != null) "保存" else "发送")
            }
        }
    }

    val action = actionItem
    if (action != null) {
        val canRedact = (selfUserId != null && action.senderId == selfUserId) ||
            permissions?.canRedactOthers == true
        ModalBottomSheet(
            onDismissRequest = { actionItem = null },
            containerColor = colors.background.container,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(CinnySpacing.S400),
                verticalArrangement = Arrangement.spacedBy(CinnySpacing.S200),
            ) {
                Text("消息操作", fontSize = 16.sp)
                Button(onClick = { replyTo = action; actionItem = null }, modifier = Modifier.fillMaxWidth()) { Text("回复") }
                Button(onClick = { threadRoot = action; actionItem = null }, modifier = Modifier.fillMaxWidth()) { Text("在 Thread 中回复") }
                Button(onClick = { threadViewRoot = action.eventId; actionItem = null }, modifier = Modifier.fillMaxWidth()) { Text("打开 Thread") }
                Button(onClick = { forwardItem = action; actionItem = null }, modifier = Modifier.fillMaxWidth()) { Text("转发") }
                Button(
                    onClick = {
                        val target = action
                        actionItem = null
                        scope.launch {
                            historyTexts = engine.editHistory(roomId, target.eventId).getOrDefault(emptyList())
                            historyItem = target
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("编辑历史") }
                if (selfUserId != null && action.senderId == selfUserId) {
                    Button(
                        onClick = { editing = action; draft = action.body.orEmpty(); actionItem = null },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("编辑") }
                }
                Button(
                    onClick = { scope.launch { engine.toggleReaction(roomId, action.eventId, "👍") }; actionItem = null },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("👍 表情回应") }
                Button(
                    onClick = { scope.launch { engine.setPinned(roomId, action.eventId, !action.isPinned) }; actionItem = null },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (action.isPinned) "取消置顶" else "置顶") }
                if (canRedact) {
                    Button(
                        onClick = { scope.launch { engine.redact(roomId, action.eventId) }; actionItem = null },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("删除") }
                }
            }
        }
    }

    forwardItem?.let { item ->
        ForwardDialog(
            rooms = roomsForForward.filter { it.roomId != roomId },
            onDismiss = { forwardItem = null },
            onPick = { target ->
                forwardItem = null
                scope.launch { engine.forward(roomId, item.eventId, target.roomId) }
            },
        )
    }

    historyItem?.let { _ ->
        EditHistoryDialog(history = historyTexts, onDismiss = { historyItem = null })
    }
}

private fun userFacingError(message: String?): String {
    if (message.isNullOrBlank()) return "登录失败"
    return when {
        message.contains("ConnectionReset", ignoreCase = true) ||
            message.contains("tcp connect error", ignoreCase = true) ||
            message.contains("connection refused", ignoreCase = true) ->
            "无法连接服务器，请检查服务器地址、网络或服务器 TLS 配置。"
        message.contains("Unauthorized", ignoreCase = true) ||
            message.contains("401") ||
            message.contains("invalid password", ignoreCase = true) ->
            "用户名或密码错误。"
        message.contains("well-known", ignoreCase = true) ->
            "服务器发现失败，请直接填写 Matrix 服务器地址。"
        else -> message
    }
}
