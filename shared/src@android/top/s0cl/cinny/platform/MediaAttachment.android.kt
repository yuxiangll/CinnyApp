package top.s0cl.cinny.platform

import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.ViewGroup
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text as M3Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.s0cl.cinny.core.design.LocalCinnyColors
import top.s0cl.cinny.matrix.MatrixEngine
import top.s0cl.cinny.matrix.TimelineItem
import java.io.File
import java.util.concurrent.TimeUnit

private const val TAG = "MediaAttachment"

@Composable
actual fun MediaAttachment(
    engine: MatrixEngine,
    roomId: String,
    item: TimelineItem,
    autoLoad: Boolean,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val colors = LocalCinnyColors.current
    val scope = rememberCoroutineScope()
    var bytes by remember(item.eventId) { mutableStateOf<ByteArray?>(null) }
    var filePath by remember(item.eventId) { mutableStateOf<String?>(null) }
    var error by remember(item.eventId) { mutableStateOf<String?>(null) }
    var busy by remember(item.eventId) { mutableStateOf(false) }
    var savedTo by remember(item.eventId) { mutableStateOf<String?>(null) }
    val msgType = item.decryptedMsgType ?: item.msgType ?: "m.file"

    LaunchedEffect(item.eventId, autoLoad) {
        if (!autoLoad) return@LaunchedEffect
        if (bytes != null || filePath != null) return@LaunchedEffect
        busy = true
        engine.loadMedia(roomId, item)
            .onSuccess { payload ->
                if (msgType == "m.video" || msgType == "m.audio") {
                    filePath = withContext(Dispatchers.IO) {
                        writeCacheFile(context, payload.fileName ?: ("media-" + item.eventId.hashCode()), payload.bytes)
                    }
                } else {
                    bytes = payload.bytes
                }
            }
            .onFailure { error = it.message ?: "媒体加载失败" }
        busy = false
    }

    Column(modifier.fillMaxWidth()) {
        when (msgType) {
            "m.image", "m.sticker" -> {
                val cacheKey = item.mediaUrl ?: item.eventId
                val data = bytes
                val bitmap = remember(data, cacheKey) {
                    if (data == null) {
                        ImageCache.get(cacheKey)
                    } else {
                        ImageCache.get(cacheKey) ?: runCatching {
                            BitmapFactory.decodeByteArray(data, 0, data.size)?.asImageBitmap()
                        }.getOrNull()?.also { ImageCache.put(cacheKey, it) }
                    }
                }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = item.body,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp)
                            .clickable {
                                scope.launch {
                                    engine.loadMedia(roomId, item).onSuccess { payload ->
                                        savedTo = saveToDownloads(
                                            context,
                                            payload.fileName ?: "image-" + item.eventId.hashCode() + ".jpg",
                                            payload.mimeType ?: "image/jpeg",
                                            payload.bytes,
                                        ).getOrNull()
                                    }
                                }
                            },
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    M3Text(
                        text = when {
                            busy -> "[图片加载中…]"
                            error != null -> "[图片加载失败] " + error
                            else -> "[图片] " + (item.body ?: "")
                        },
                        color = colors.primary.main,
                        fontSize = 12.sp,
                    )
                }
            }
            "m.video" -> {
                val path = filePath
                if (path != null) {
                    VideoPlayer(path = path, modifier = Modifier.fillMaxWidth().height(220.dp))
                } else {
                    MediaPlaceholder(
                        label = if (busy) "[视频下载中…]" else "[视频] " + (item.body ?: ""),
                        error = error,
                        onClick = {
                            scope.launch {
                                busy = true
                                engine.loadMedia(roomId, item)
                                    .onSuccess { payload ->
                                        filePath = withContext(Dispatchers.IO) {
                                            writeCacheFile(
                                                context,
                                                payload.fileName ?: ("video-" + item.eventId.hashCode() + ".mp4"),
                                                payload.bytes,
                                            )
                                        }
                                    }
                                    .onFailure { error = it.message }
                                busy = false
                            }
                        },
                    )
                }
            }
            "m.audio" -> {
                val path = filePath
                if (path != null) {
                    AudioPlayer(path = path, title = item.body ?: "音频")
                } else {
                    MediaPlaceholder(
                        label = if (busy) "[音频下载中…]" else "[音频] " + (item.body ?: ""),
                        error = error,
                        onClick = {
                            scope.launch {
                                busy = true
                                engine.loadMedia(roomId, item)
                                    .onSuccess { payload ->
                                        filePath = withContext(Dispatchers.IO) {
                                            writeCacheFile(
                                                context,
                                                payload.fileName ?: ("audio-" + item.eventId.hashCode() + ".m4a"),
                                                payload.bytes,
                                            )
                                        }
                                    }
                                    .onFailure { error = it.message }
                                busy = false
                            }
                        },
                    )
                }
            }
            else -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    M3Text(
                        text = if (savedTo != null) "[文件] ${item.body ?: ""} → 已保存" else "[文件] " + (item.body ?: ""),
                        color = colors.primary.main,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f),
                    )
                    M3Text(
                        text = "下载",
                        color = colors.primary.main,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                            .wrapContentSize(Alignment.Center)
                            .clickable {
                            scope.launch {
                                engine.loadMedia(roomId, item)
                                    .onSuccess { payload ->
                                        savedTo = saveToDownloads(
                                            context,
                                            payload.fileName ?: (item.body ?: "file"),
                                            payload.mimeType ?: "application/octet-stream",
                                            payload.bytes,
                                        ).getOrNull()
                                    }
                                    .onFailure { error = it.message }
                            }
                        }.padding(start = 8.dp),
                    )
                }
                error?.let { M3Text(text = it, color = colors.critical.main, fontSize = 11.sp) }
            }
        }
    }
}

@Composable
private fun MediaPlaceholder(label: String, error: String?, onClick: () -> Unit) {
    val colors = LocalCinnyColors.current
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp)) {
        M3Text(text = label, color = colors.primary.main, fontSize = 12.sp)
        error?.let { M3Text(text = it, color = colors.critical.main, fontSize = 11.sp) }
    }
}

@Composable
private fun VideoPlayer(path: String, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            VideoView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                setVideoPath(path)
                val controller = MediaController(ctx)
                controller.setAnchorView(this)
                setMediaController(controller)
                setOnPreparedListener { player ->
                    player.isLooping = false
                    start()
                }
                setOnErrorListener { _, what, extra ->
                    Log.w(TAG, "video error what=$what extra=$extra")
                    true
                }
            }
        },
        update = { view ->
            if (view.tag != path) {
                view.tag = path
                view.setVideoPath(path)
                view.start()
            }
        },
    )
}

@Composable
private fun AudioPlayer(path: String, title: String) {
    val colors = LocalCinnyColors.current
    var playing by remember { mutableStateOf(false) }
    var position by remember { mutableStateOf(0) }
    var duration by remember { mutableStateOf(0) }
    val player = remember(path) {
        MediaPlayer().apply {
            setDataSource(path)
            prepare()
            setOnCompletionListener { playing = false; position = 0 }
        }
    }
    LaunchedEffect(player) {
        duration = runCatching { player.duration }.getOrDefault(0)
        while (true) {
            if (player.isPlaying) position = player.currentPosition
            kotlinx.coroutines.delay(500)
        }
    }
    DisposableEffect(player) {
        onDispose { runCatching { player.release() } }
    }
    val toggle: () -> Unit = {
        runCatching {
            if (player.isPlaying) {
                player.pause()
                playing = false
            } else {
                player.start()
                playing = true
            }
        }.onFailure { Log.w(TAG, "audio toggle failed", it) }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        top.yukonga.miuix.kmp.basic.Button(
            onClick = toggle,
            modifier = Modifier.padding(end = 8.dp),
        ) { top.yukonga.miuix.kmp.basic.Text(text = if (playing) "暂停" else "播放", fontSize = 12.sp) }
        Column(Modifier.weight(1f)) {
            M3Text(text = title, fontSize = 12.sp, color = colors.background.onContainer)
            M3Text(
                text = formatDuration(position) + " / " + formatDuration(duration),
                fontSize = 11.sp,
                color = colors.background.onContainer.copy(alpha = 0.7f),
            )
        }
    }
}

private fun formatDuration(millis: Int): String {
    val safe = millis.coerceAtLeast(0)
    return String.format(
        "%d:%02d",
        TimeUnit.MILLISECONDS.toMinutes(safe.toLong()),
        TimeUnit.MILLISECONDS.toSeconds(safe.toLong()) % 60,
    )
}

/** 写入应用缓存目录，供播放器使用。 */
internal fun writeCacheFile(context: Context, fileName: String, bytes: ByteArray): String {
    val dir = File(context.cacheDir, "media").apply { mkdirs() }
    val safeName = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
    val file = File(dir, safeName)
    file.writeBytes(bytes)
    return file.absolutePath
}

/** 保存到系统“下载”目录（MediaStore，Android 10+ 无需存储权限）。 */
internal fun saveToDownloads(
    context: Context,
    fileName: String,
    mimeType: String,
    bytes: ByteArray,
): Result<String> = runCatching {
    // 保留字母/数字（含中文）与 . _ - 空格，其余替换为下划线。
    val safeName = fileName.map { ch ->
        if (ch.isLetterOrDigit() || ch == '.' || ch == '_' || ch == '-' || ch == ' ') ch else '_'
    }.joinToString("").trim().ifBlank { "file" }.take(120)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, safeName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("无法创建下载条目")
        resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("无法写入下载文件")
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        "下载/$safeName"
    } else {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "",
        ).apply { mkdirs() }
        val file = File(dir, safeName)
        file.writeBytes(bytes)
        file.absolutePath
    }
}
