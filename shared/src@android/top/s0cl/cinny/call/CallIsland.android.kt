package top.s0cl.cinny.call

import android.Manifest
import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log
import android.util.Rational
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text as M3Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import top.s0cl.cinny.platform.PlatformBackHandler
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Text

private const val TAG = "CallIsland"

/**
 * 注入到 widget 页面的宿主桥：
 *  - 页面 -> 宿主：window.postMessage 的 toWidget 消息转发给 CinnyCall；
 *  - 宿主 -> 页面：window.__cinnyFromWidget(json) 派发 fromWidget 应答。
 */
private const val HOST_JS = """
(function () {
  if (window.__cinnyHostReady) return;
  window.__cinnyHostReady = true;
  window.addEventListener('message', function (event) {
    try {
      var data = event.data;
      if (typeof data === 'string') data = JSON.parse(data);
      if (!data || data.api !== 'toWidget') return;
      if (window.CinnyCall) window.CinnyCall.onWidgetMessage(JSON.stringify(data));
      if (data.action === 'supported_api_versions') {
        window.__cinnyFromWidget(JSON.stringify({
          api: 'fromWidget',
          action: 'supported_api_versions',
          requestId: data.requestId,
          response: { supported_versions: ['0.1', '0.2'] }
        }));
      }
    } catch (e) {}
  });
  window.__cinnyFromWidget = function (payload) {
    window.postMessage(payload, '*');
    if (window.CinnyCall) window.CinnyCall.onWidgetMessage('fromWidget ' + payload.substring(0, 120));
  };
})();
"""

@Composable
actual fun CallIslandHost(url: String, roomId: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val logState = remember { mutableStateListOf<String>() }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var permissionsRequested by remember { mutableStateOf(false) }
    val effectiveUrl = remember(url) {
        url.ifBlank { "file:///android_asset/call_stub.html" }
    }

    PlatformBackHandler(onBack = onClose)

    // 音频焦点：通话占 Priority 通道，退出时释放。
    DisposableEffect(Unit) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val focusRequest = audioManager?.let { manager ->
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setOnAudioFocusChangeListener { }
                .build().also { manager.requestAudioFocus(it) }
        }
        onDispose {
            if (audioManager != null && focusRequest != null) {
                runCatching { audioManager.abandonAudioFocusRequest(focusRequest) }
            }
        }
    }

    LaunchedEffect(activity) {
        if (activity != null && !permissionsRequested) {
            permissionsRequested = true
            val missing = listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
                .filter {
                    ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
                }
            if (missing.isNotEmpty()) {
                ActivityCompat.requestPermissions(activity, missing.toTypedArray(), 0x3c1)
            }
        }
    }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (roomId.isBlank()) "通话能力岛" else "通话 · $roomId",
                color = Color.White,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = {
                    val target = activity ?: return@Button
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        runCatching {
                            target.enterPictureInPictureMode(
                                PictureInPictureParams.Builder()
                                    .setAspectRatio(Rational(16, 9))
                                    .build(),
                            )
                        }.onFailure { Log.w(TAG, "PiP failed", it) }
                    }
                },
                modifier = Modifier.padding(end = 8.dp),
            ) { Text("画中画") }
            Button(onClick = onClose) { Text("结束") }
        }
        HorizontalDivider()
        AndroidView(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.allowFileAccess = true
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: android.webkit.WebResourceRequest,
                        ): Boolean = false

                        override fun onPageFinished(view: WebView, pageUrl: String) {
                            view.evaluateJavascript(HOST_JS, null)
                        }
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onPermissionRequest(request: PermissionRequest) {
                            val granted = request.resources.all { resource ->
                                val permission = if (resource == PermissionRequest.RESOURCE_AUDIO_CAPTURE) {
                                    Manifest.permission.RECORD_AUDIO
                                } else {
                                    Manifest.permission.CAMERA
                                }
                                ContextCompat.checkSelfPermission(ctx, permission) ==
                                    PackageManager.PERMISSION_GRANTED
                            }
                            if (granted) request.grant(request.resources) else request.deny()
                        }
                    }
                    addJavascriptInterface(Bridge { message -> logState.add(message) }, "CinnyCall")
                    loadUrl(effectiveUrl)
                    webView = this
                }
            },
            update = { view -> webView = view },
        )
        HorizontalDivider()
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(max = 160.dp)
                .verticalScroll(rememberScrollState())
                .padding(8.dp),
        ) {
            M3Text(
                text = "widget 事件（toWidget / fromWidget）",
                color = Color.Gray,
                fontSize = 11.sp,
            )
            logState.takeLast(6).forEach { line ->
                M3Text(text = line, color = Color.White, fontSize = 11.sp)
            }
        }
    }
}

    /** Widget 宿主侧桥：接收页面的 toWidget 消息，并把 fromWidget 应答写回页面。 */
    private class Bridge(private val onMessage: (String) -> Unit) {
        @JavascriptInterface
        fun onWidgetMessage(json: String) {
            val summary = runCatching {
                val obj = org.json.JSONObject(json)
                val api = obj.optString("api")
                val action = obj.optString("action")
                val requestId = obj.optString("requestId")
                "toWidget $api/$action" + if (requestId.isNotBlank()) " ($requestId)" else ""
            }.getOrElse { json.take(120) }
            onMessage(summary)
            Log.i(TAG, "widget message: " + json.take(300))
        }
    }

    private fun Context.findActivity(): Activity? {
        var current: Context? = this
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return null
    }
