package top.s0cl.cinny.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text as M3Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import top.s0cl.cinny.core.design.CinnySpacing
import top.s0cl.cinny.core.design.LocalCinnyColors
import top.s0cl.cinny.matrix.MatrixEngine
import top.s0cl.cinny.matrix.MessageDraft
import top.s0cl.cinny.matrix.RoomSummary
import top.s0cl.cinny.matrix.TimelineItem
import top.s0cl.cinny.matrix.UrlPreview
import top.s0cl.cinny.core.design.CinnyRadii
import top.s0cl.cinny.platform.decodeImageBitmap
import top.s0cl.cinny.platform.PlatformBackHandler
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField

/** M2：独立的 Thread 视图（`m.thread` 关系）。 */
@Composable
fun ThreadScreen(
    engine: MatrixEngine,
    roomId: String,
    rootEventId: String,
    onBack: () -> Unit,
) {
    val colors = LocalCinnyColors.current
    val scope = rememberCoroutineScope()
    PlatformBackHandler(onBack = onBack)
    var items by remember { mutableStateOf<List<TimelineItem>>(emptyList()) }
    var reload by remember { mutableStateOf(0) }
    var draft by rememberSaveable { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }

    LaunchedEffect(roomId, rootEventId, reload) {
        engine.thread(roomId, rootEventId).collect { items = it }
    }

    Column(
        modifier = Modifier.fillMaxSize()
            .background(colors.background.container)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        M3BackRow("Thread", onBack)
        if (items.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("暂无回复", color = colors.background.onContainer.copy(alpha = 0.7f))
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(items, key = { it.eventId }) { item ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = CinnySpacing.S400, vertical = CinnySpacing.S200)) {
                        Text(
                            text = item.senderId ?: "unknown",
                            color = colors.primary.main,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        M3Text(
                            text = item.decryptedBody ?: item.body ?: (item.decryptionError ?: "[加密消息]"),
                            color = colors.background.onContainer,
                            fontSize = 14.sp,
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
        HorizontalDivider()
        Row(
            modifier = Modifier.fillMaxWidth().padding(CinnySpacing.S300),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(CinnySpacing.S200),
        ) {
            TextField(
                value = draft,
                onValueChange = { draft = it },
                label = "回复 Thread…",
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = {
                    val text = draft.trim()
                    if (text.isEmpty() || sending) return@Button
                    sending = true
                    scope.launch {
                        engine.send(
                            roomId,
                            MessageDraft(body = text, threadRootEventId = rootEventId),
                        ).onSuccess {
                            draft = ""
                            reload += 1
                        }
                        sending = false
                    }
                },
            ) { Text(if (sending) "…" else "发送") }
        }
    }
}

/** M2：转发目标房间选择。 */
@Composable
fun ForwardDialog(
    rooms: List<RoomSummary>,
    onDismiss: () -> Unit,
    onPick: (RoomSummary) -> Unit,
) {
    val colors = LocalCinnyColors.current
    AlertDialog(
        containerColor = colors.background.container,
        titleContentColor = colors.background.onContainer,
        textContentColor = colors.background.onContainer,
        onDismissRequest = onDismiss,
        title = { M3Text("转发到…") },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                rooms.forEach { room ->
                    Text(
                        text = room.name,
                        color = colors.background.onContainer,
                        fontSize = 14.sp,
                        modifier = Modifier.fillMaxWidth()
                            .clickable { onPick(room) }
                            .padding(vertical = CinnySpacing.S200),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    HorizontalDivider()
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { M3Text("取消") }
        },
    )
}

/** M2：编辑历史。 */
@Composable
fun EditHistoryDialog(history: List<String>, onDismiss: () -> Unit) {
    val colors = LocalCinnyColors.current
    AlertDialog(
        containerColor = colors.background.container,
        titleContentColor = colors.background.onContainer,
        textContentColor = colors.background.onContainer,
        onDismissRequest = onDismiss,
        title = { M3Text("编辑历史（${history.size} 次）") },
        text = {
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                if (history.isEmpty()) {
                    M3Text("没有历史版本", color = colors.background.onContainer.copy(alpha = 0.7f))
                }
                history.forEachIndexed { index, text ->
                    M3Text(
                        text = "#${history.size - index}  $text",
                        color = colors.background.onContainer,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = CinnySpacing.S100),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { M3Text("关闭") }
        },
    )
}

private val URL_REGEX = Regex("https?://[^ ]+")

/** 消息里唯一的一个链接（多条链接或没有则返回 null）。 */
fun singleUrlIn(body: String?): String? {
    if (body.isNullOrBlank()) return null
    val matches = URL_REGEX.findAll(body).map { it.value }.toList()
    return matches.singleOrNull()
}

/** M2：链接预览卡片（标题 / 描述 / 缩略图）。 */
@Composable
fun UrlPreviewCard(engine: MatrixEngine, url: String) {
    val colors = LocalCinnyColors.current
    var preview by remember(url) { mutableStateOf<UrlPreview?>(null) }
    var image by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    var expanded by remember(url) { mutableStateOf(false) }

    LaunchedEffect(url) {
        engine.urlPreview(url).onSuccess { result ->
            preview = result
            val imageUrl = result.imageUrl
            if (imageUrl != null) {
                engine.mediaBytes(imageUrl).onSuccess { bytes ->
                    image = decodeImageBitmap(bytes)
                }
            }
        }
    }

    val p = preview ?: return
    if (p.title == null && p.description == null && p.imageUrl == null) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = CinnySpacing.S200)
            .clip(RoundedCornerShape(CinnyRadii.R400))
            .background(colors.background.container)
            .clickable { expanded = !expanded }
            .padding(CinnySpacing.S300),
    ) {
        image?.let {
            Image(
                bitmap = it,
                contentDescription = p.title,
                modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp),
                contentScale = ContentScale.Fit,
            )
            Spacer(Modifier.height(CinnySpacing.S200))
        }
        M3Text(
            text = p.title ?: url,
            color = colors.background.onContainer,
            fontSize = 14.sp,
            maxLines = if (expanded) Int.MAX_VALUE else 2,
            overflow = TextOverflow.Ellipsis,
        )
        p.description?.let {
            M3Text(
                text = it,
                color = colors.background.onContainer.copy(alpha = 0.75f),
                fontSize = 12.sp,
                maxLines = if (expanded) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        M3Text(
            text = p.siteName ?: url.substringAfter("://").substringBefore('/'),
            color = colors.primary.main,
            fontSize = 11.sp,
        )
    }
}
