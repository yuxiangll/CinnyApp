package top.s0cl.cinny.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text as M3Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import top.s0cl.cinny.core.design.*
import top.s0cl.cinny.core.settings.AppSettings
import top.s0cl.cinny.matrix.*
import top.s0cl.cinny.platform.PlatformBackHandler
import top.s0cl.cinny.platform.MediaAttachment
import top.s0cl.cinny.ui.renderMatrixHtml
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField

@Composable
fun InboxScreen(engine: MatrixEngine, onOpenRoom: (String) -> Unit) {
    val colors = LocalCinnyColors.current
    val scope = rememberCoroutineScope()
    val invites by engine.invites().collectAsState(initial = emptyList())
    val rooms by engine.rooms().collectAsState(initial = emptyList())
    val unread = rooms.filter { it.unreadTotal > 0 }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(CinnySpacing.S400),
        verticalArrangement = Arrangement.spacedBy(CinnySpacing.S300),
    ) {
        SmallTitle("邀请")
        Card {
            if (invites.isEmpty()) {
                BasicComponent(title = "暂无邀请", summary = "新的房间邀请会出现在这里")
            } else {
                invites.forEachIndexed { index, invite ->
                    if (index > 0) HorizontalDivider()
                    Column(Modifier.padding(CinnySpacing.S200)) {
                        BasicComponent(
                            title = invite.name,
                            summary = invite.inviter?.let { "来自 $it" } ?: invite.roomId,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(CinnySpacing.S200)) {
                            Button(onClick = {
                                scope.launch { engine.acceptInvite(invite.roomId) }
                            }) { Text("接受") }
                            Button(onClick = {
                                scope.launch { engine.declineInvite(invite.roomId) }
                            }) { Text("拒绝") }
                        }
                    }
                }
            }
        }
        SmallTitle("未读房间")
        Card {
            if (unread.isEmpty()) {
                BasicComponent(title = "暂无未读", summary = "所有房间都已读")
            } else {
                unread.forEachIndexed { index, room ->
                    if (index > 0) HorizontalDivider()
                    BasicComponent(
                        title = room.name,
                        summary = "${room.unreadTotal} 条未读 · ${room.unreadHighlight} 条提及",
                        onClick = { onOpenRoom(room.roomId) },
                    )
                }
            }
        }
        M3Text(
            text = "M2 收件箱：邀请与未读房间；完整通知中心在 M3 接入推送后完善。",
            color = colors.background.onContainer.copy(alpha = 0.7f),
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun SearchChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalCinnyColors.current
    Text(
        text = label,
        color = if (selected) colors.primary.main else colors.background.onContainer.copy(alpha = 0.7f),
        fontSize = 12.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(CinnyRadii.R400))
            .background(if (selected) colors.background.containerActive else colors.background.container)
            .clickable(onClick = onClick)
            .padding(horizontal = CinnySpacing.S300, vertical = CinnySpacing.S100),
    )
}

@Composable
fun SearchScreen(
    engine: MatrixEngine,
    onBack: () -> Unit,
    onOpenRoom: (String, String?) -> Unit,
) {
    val colors = LocalCinnyColors.current
    PlatformBackHandler(onBack = onBack)
    val scope = rememberCoroutineScope()
    var term by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val rooms = remember { mutableStateOf<List<RoomSummary>>(emptyList()) }
    var roomFilter by remember { mutableStateOf<String?>(null) }
    var senderFilter by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) {
        engine.rooms().collect { rooms.value = it }
    }
    Column(
        modifier = Modifier.fillMaxSize().background(colors.background.container)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(CinnySpacing.S400),
        verticalArrangement = Arrangement.spacedBy(CinnySpacing.S300),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "‹ 返回",
                modifier = Modifier.clickable(onClick = onBack).padding(CinnySpacing.S200),
                color = colors.primary.main,
                fontSize = 14.sp,
            )
            Text("搜索", modifier = Modifier.padding(start = CinnySpacing.S200), fontSize = 18.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(CinnySpacing.S200)) {
            TextField(
                value = term,
                onValueChange = { term = it },
                label = "搜索消息…",
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            Button(onClick = {
                if (term.isBlank() || busy) return@Button
                busy = true
                error = null
                scope.launch {
                    engine.search(term.trim(), roomFilter)
                        .onSuccess { results = it }
                        .onFailure { error = it.message }
                    busy = false
                }
            }) { Text(if (busy) "…" else "搜索") }
        }
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(CinnySpacing.S200),
        ) {
            SearchChip("全部房间", roomFilter == null) { roomFilter = null }
            rooms.value.take(8).forEach { room ->
                SearchChip(room.name, roomFilter == room.roomId) { roomFilter = room.roomId }
            }
        }
        TextField(
            value = senderFilter,
            onValueChange = { senderFilter = it },
            label = "按发送者过滤（包含匹配）",
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        error?.let { Text(it, color = colors.critical.main) }
        if (results.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("输入关键词搜索全服消息", color = colors.background.onContainer.copy(alpha = 0.7f))
            }
        } else {
            val filtered = results.filter { result ->
                (roomFilter == null || result.roomId == roomFilter) &&
                    (senderFilter.isBlank() || (result.senderId ?: "").contains(senderFilter.trim(), ignoreCase = true))
            }
            if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("没有匹配结果", color = colors.background.onContainer.copy(alpha = 0.7f))
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(filtered, key = { "${it.roomId}:${it.eventId}" }) { result ->
                        BasicComponent(
                            title = result.roomName,
                            summary = "${result.senderId ?: "unknown"}: ${result.body}",
                            onClick = { onOpenRoom(result.roomId, result.eventId.takeIf { id -> id.isNotBlank() }) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
fun ExploreScreen(
    engine: MatrixEngine,
    onBack: () -> Unit,
    onOpenRoom: (String) -> Unit,
) {
    val colors = LocalCinnyColors.current
    PlatformBackHandler(onBack = onBack)
    val scope = rememberCoroutineScope()
    var rooms by remember { mutableStateOf<List<PublicRoom>>(emptyList()) }
    var busy by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        engine.publicRooms("matrix.org")
            .onSuccess { rooms = it }
            .onFailure { error = it.message }
        busy = false
    }
    Column(
        modifier = Modifier.fillMaxSize().background(colors.background.container)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(CinnySpacing.S400),
        verticalArrangement = Arrangement.spacedBy(CinnySpacing.S300),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "‹ 返回",
                modifier = Modifier.clickable(onClick = onBack).padding(CinnySpacing.S200),
                color = colors.primary.main,
                fontSize = 14.sp,
            )
            Text("探索", modifier = Modifier.padding(start = CinnySpacing.S200), fontSize = 18.sp)
        }
        error?.let { Text(it, color = colors.critical.main) }
        if (busy) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("加载公共房间…", color = colors.background.onContainer.copy(alpha = 0.7f))
            }
        } else if (rooms.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无公共房间", color = colors.background.onContainer.copy(alpha = 0.7f))
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(rooms, key = { it.roomIdOrAlias }) { room ->
                    Column(Modifier.padding(vertical = CinnySpacing.S200)) {
                        BasicComponent(
                            title = room.name,
                            summary = buildString {
                                append(room.topic ?: room.roomIdOrAlias)
                                if (room.joinedMembers > 0) append(" · ${room.joinedMembers} 人")
                            },
                        )
                        Button(onClick = {
                            scope.launch {
                                engine.joinRoom(room.roomIdOrAlias)
                                    .onSuccess { onOpenRoom(it) }
                                    .onFailure { error = it.message }
                            }
                        }) { Text("加入房间") }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(
    engine: MatrixEngine,
    session: Session,
    settings: AppSettings,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    onLogout: () -> Unit,
    onOpenCrypto: () -> Unit = {},
    onOpenDevTools: () -> Unit = {},
    onOpenCall: () -> Unit = {},
) {
    val colors = LocalCinnyColors.current
    val scope = rememberCoroutineScope()
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(CinnySpacing.S400),
        verticalArrangement = Arrangement.spacedBy(CinnySpacing.S300),
    ) {
        SmallTitle("外观")
        Card {
            CinnyThemeKind.entries.forEachIndexed { index, kind ->
                if (index > 0) HorizontalDivider()
                BasicComponent(
                    title = themeLabel(kind),
                    summary = if (kind.name == settings.themeKind) "当前" else "",
                    onClick = { onUpdate { it.copy(themeKind = kind.name) } },
                )
            }
        }
        SmallTitle("消息")
        Card {
            BasicComponent(
                title = "消息布局",
                summary = settings.messageLayout,
                onClick = {
                    val next = when (settings.messageLayout) {
                        "Modern" -> "Compact"
                        "Compact" -> "Bubble"
                        else -> "Modern"
                    }
                    onUpdate { it.copy(messageLayout = next) }
                },
            )
            HorizontalDivider()
            BasicComponent(
                title = "自动加载媒体",
                summary = if (settings.autoLoadMedia) "已开启" else "已关闭",
                onClick = { onUpdate { it.copy(autoLoadMedia = !it.autoLoadMedia) } },
            )
            HorizontalDivider()
            BasicComponent(
                title = "Enter 发送",
                summary = if (settings.sendOnEnter) "Enter 发送" else "Enter 换行",
                onClick = { onUpdate { it.copy(sendOnEnter = !it.sendOnEnter) } },
            )
            HorizontalDivider()
            BasicComponent(
                title = "通知",
                summary = if (settings.notificationsEnabled) "已开启" else "已关闭",
                onClick = { onUpdate { it.copy(notificationsEnabled = !it.notificationsEnabled) } },
            )
        }
        SmallTitle("加密与安全")
        Card {
            BasicComponent(
                title = "E2EE 与验证",
                summary = "设备验证、密钥备份、SAS",
                onClick = onOpenCrypto,
            )
            HorizontalDivider()
            BasicComponent(
                title = "开发者工具",
                summary = "发送事件 / 状态事件 / 账号数据",
                onClick = onOpenDevTools,
            )
        }
        SmallTitle("通话")
        Card {
            BasicComponent(
                title = "通话能力岛（Element Call）",
                summary = settings.callUrl.ifBlank { "本地 stub（未配置 Element Call 地址）" },
                onClick = onOpenCall,
            )
        }
        SmallTitle("推送")
        PushSection(engine, settings, onUpdate)
        SmallTitle("账号")
        Card {
            BasicComponent(title = "用户", summary = session.userId)
            HorizontalDivider()
            BasicComponent(title = "Homeserver", summary = session.homeserverUrl)
            HorizontalDivider()
            BasicComponent(title = "设备", summary = session.deviceId)
        }
        Button(
            onClick = {
                scope.launch {
                    engine.logout()
                    onLogout()
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("退出登录") }
        M3Text(
            text = "M3 已接入 rust-sdk E2EE（本地中继传输）、密钥备份、SAS 验证与开发者工具。",
            color = colors.background.onContainer.copy(alpha = 0.7f),
            fontSize = 12.sp,
        )
    }
}

private fun themeLabel(kind: CinnyThemeKind): String = when (kind) {
    CinnyThemeKind.Light -> "浅色"
    CinnyThemeKind.Silver -> "银色"
    CinnyThemeKind.Dark -> "深色"
    CinnyThemeKind.Butter -> "黄油"
    CinnyThemeKind.System -> "跟随系统"
}

@Composable
fun MessageBody(item: TimelineItem, color: Color) {
    val colors = LocalCinnyColors.current
    var revealed by remember(item.eventId) { mutableStateOf(false) }
    val html = item.formattedBody
    val hasSpoiler = html?.contains("data-mx-spoiler", ignoreCase = true) == true
    val annotated: AnnotatedString = remember(html, item.body, revealed) {
        if (html.isNullOrBlank()) {
            AnnotatedString(item.body ?: "[暂不支持的消息类型]")
        } else {
            renderMatrixHtml(
                html = html,
                spoilerRevealed = revealed,
                textColor = color,
                linkColor = colors.primary.main,
                codeBackground = colors.background.containerActive,
                spoilerBackground = colors.background.containerActive,
            )
        }
    }
    M3Text(
        text = annotated,
        color = color,
        fontSize = 14.sp,
        modifier = if (hasSpoiler) Modifier.clickable { revealed = !revealed } else Modifier,
    )
}

private val MEDIA_TYPES = setOf("m.image", "m.video", "m.audio", "m.file", "m.sticker")

/** M2：该条目是否应渲染媒体附件。 */
fun isMediaMessage(item: TimelineItem): Boolean {
    val type = item.decryptedMsgType ?: item.msgType
    return item.mediaUrl != null || (type != null && type in MEDIA_TYPES)
}

@Composable
fun MediaContent(engine: MatrixEngine, roomId: String, item: TimelineItem, autoLoad: Boolean) {
    MediaAttachment(engine = engine, roomId = roomId, item = item, autoLoad = autoLoad)
}
