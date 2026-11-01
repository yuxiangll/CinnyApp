package top.s0cl.cinny.platform

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun rememberMediaPicker(onPicked: (PickedMedia) -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val picked = readPickedMedia(context, uri, fallbackName = "image") ?: return@rememberLauncherForActivityResult
        onPicked(picked)
    }
    return remember(launcher) { { launcher.launch("image/*") } }
}

@Composable
actual fun rememberFilePicker(onPicked: (PickedMedia) -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val picked = readPickedMedia(context, uri, fallbackName = "file") ?: return@rememberLauncherForActivityResult
        onPicked(picked)
    }
    return remember(launcher) { { launcher.launch("*/*") } }
}

/** 读取选择的文件：优先用 DISPLAY_NAME 作为文件名，并在 MIME 缺失时按扩展名推断。 */
private fun readPickedMedia(context: Context, uri: Uri, fallbackName: String): PickedMedia? {
    val bytes = runCatching {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    }.getOrNull() ?: return null

    var displayName: String? = null
    runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) {
                displayName = cursor.getString(nameIndex)
            }
        }
    }
    val name = displayName?.takeIf { it.isNotBlank() }
        ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        ?: fallbackName

    val resolvedType = context.contentResolver.getType(uri)
    val mime = resolvedType?.takeIf { it.isNotBlank() && it != "application/octet-stream" }
        ?: mimeFromName(name)
        ?: "application/octet-stream"

    return PickedMedia(bytes, mime, name)
}

private fun mimeFromName(name: String): String? = when (name.substringAfterLast('.', "").lowercase()) {
    "mp3" -> "audio/mpeg"
    "m4a" -> "audio/mp4"
    "ogg", "opus" -> "audio/ogg"
    "wav" -> "audio/wav"
    "flac" -> "audio/flac"
    "mp4", "m4v" -> "video/mp4"
    "webm" -> "video/webm"
    "mkv" -> "video/x-matroska"
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "pdf" -> "application/pdf"
    "zip" -> "application/zip"
    "txt" -> "text/plain"
    else -> null
}
