package top.s0cl.cinny.matrix

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import top.s0cl.cinny.matrix.net.RelayHttpResult

data class HttpResult(val status: Int, val body: String) {
    val isSuccess: Boolean get() = status in 200..299
}

class ByteResult(val status: Int, val bytes: ByteArray) {
    val isSuccess: Boolean get() = status in 200..299
}

/**
 * M1 workaround: use the Chrome/WebView network stack for Matrix HTTP calls.
 *
 * The page is loaded with the homeserver origin as base URL, so relative
 * `/_matrix/...` fetches are same-origin and do not need CORS. The WAF in
 * front of s0cl.top currently accepts Chrome's TLS ClientHello only; rustls
 * and Java TLS are reset during the handshake.
 */
class WebViewTransport(private val activity: Activity) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val counter = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<HttpResult>>()
    private val pendingBytes = ConcurrentHashMap<Int, CompletableDeferred<ByteResult>>()
    private val pendingRelay = ConcurrentHashMap<Int, CompletableDeferred<RelayHttpResult>>()
    private val pendingChunks = ConcurrentHashMap<Int, java.io.ByteArrayOutputStream>()

    /** 当前 WebView 使用的 base URL；用于在 M3 中确认中继上游可用。 */
    val baseUrl: String? get() = currentBaseUrl

    @Volatile
    private var webView: WebView? = null

    @Volatile
    private var currentBaseUrl: String? = null

    @Volatile
    private var ready: CompletableDeferred<Unit>? = null

    suspend fun useBaseUrl(baseUrl: String) {
        val normalized = baseUrl.trimEnd('/') + "/"
        if (currentBaseUrl == normalized && webView != null) return
        val deferred = withContext(Dispatchers.Main) {
            webView?.let { runCatching { it.destroy() } }
            val nextReady = CompletableDeferred<Unit>()
            ready = nextReady
            val view = createWebView()
            webView = view
            currentBaseUrl = normalized
            view.loadDataWithBaseURL(normalized, BRIDGE_HTML, "text/html", "UTF-8", null)
            nextReady
        }
        try {
            withTimeout(15_000) { deferred.await() }
        } catch (error: TimeoutCancellationException) {
            throw IllegalStateException("WebView transport did not become ready: $normalized", error)
        }
    }

    suspend fun request(
        method: String,
        path: String,
        accessToken: String? = null,
        body: String? = null,
        timeoutMs: Long = 45_000,
    ): HttpResult {
        val view = webView ?: error("WebViewTransport is not initialized")
        val id = counter.incrementAndGet()
        val deferred = CompletableDeferred<HttpResult>()
        pending[id] = deferred
        withContext(Dispatchers.Main) {
            val script = buildString {
                append("nativeFetch(")
                append(id)
                append(", ")
                append(JSONObject.quote(method))
                append(", ")
                append(JSONObject.quote(path))
                append(", ")
                append(JSONObject.quote(accessToken ?: ""))
                append(", ")
                append(body?.let(JSONObject::quote) ?: "null")
                append(")")
            }
            view.evaluateJavascript(script, null)
        }
        return withTimeoutOrNull(timeoutMs) { deferred.await() } ?: run {
            pending.remove(id)
            throw IllegalStateException("WebView request timed out: $method $path")
        }
    }

    suspend fun requestBytes(
        path: String,
        accessToken: String? = null,
        timeoutMs: Long = 30_000,
    ): ByteResult {
        val view = webView ?: error("WebViewTransport is not initialized")
        val id = counter.incrementAndGet()
        val deferred = CompletableDeferred<ByteResult>()
        pendingBytes[id] = deferred
        withContext(Dispatchers.Main) {
            val script = buildString {
                append("nativeFetchBytesChunked(")
                append(id)
                append(", ")
                append(JSONObject.quote(path))
                append(", ")
                append(JSONObject.quote(accessToken ?: ""))
                append(")")
            }
            view.evaluateJavascript(script, null)
        }
        return withTimeoutOrNull(timeoutMs) { deferred.await() } ?: run {
            pendingBytes.remove(id)
            throw IllegalStateException("WebView byte request timed out: $path")
        }
    }

    suspend fun uploadBytes(
        path: String,
        accessToken: String,
        bytes: ByteArray,
        contentType: String,
        timeoutMs: Long = 60_000,
    ): HttpResult {
        val view = webView ?: error("WebViewTransport is not initialized")
        val id = counter.incrementAndGet()
        val deferred = CompletableDeferred<HttpResult>()
        pending[id] = deferred
        if (bytes.size > CHUNK_BYTES) {
            // 大文件：分块经 JS bridge 传输，避免 Binder 事务（~1MB）限制。
            val chunks = (bytes.size + CHUNK_BYTES - 1) / CHUNK_BYTES
            for (index in 0 until chunks) {
                val start = index * CHUNK_BYTES
                val end = minOf(start + CHUNK_BYTES, bytes.size)
                val part = Base64.encodeToString(bytes.copyOfRange(start, end), Base64.NO_WRAP)
                withContext(Dispatchers.Main) {
                    view.evaluateJavascript(
                        "nativeUploadChunk(" + id + ", " + index + ", " + JSONObject.quote(part) + ")",
                        null,
                    )
                }
            }
            withContext(Dispatchers.Main) {
                view.evaluateJavascript(
                    "nativeUploadAssemble(" + id + ", " + chunks + ", " + JSONObject.quote(path) +
                        ", " + JSONObject.quote(accessToken) + ", " + JSONObject.quote(contentType) + ")",
                    null,
                )
            }
        } else {
            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            withContext(Dispatchers.Main) {
                val script = buildString {
                    append("nativeUpload(")
                    append(id)
                    append(", ")
                    append(JSONObject.quote(path))
                    append(", ")
                    append(JSONObject.quote(accessToken))
                    append(", ")
                    append(JSONObject.quote(base64))
                    append(", ")
                    append(JSONObject.quote(contentType))
                    append(")")
                }
                view.evaluateJavascript(script, null)
            }
        }
        return withTimeoutOrNull(timeoutMs) { deferred.await() } ?: run {
            pending.remove(id)
            throw IllegalStateException("WebView upload timed out: $path")
        }
    }

    /**
     * M3：为 matrix-rust-sdk 提供中继出口。
     *
     * rust-sdk 只能走 rustls，会被前置 WAF 重置；这里把它的每个 HTTP 请求
     * 转成 WebView 内的一次 fetch（Chromium TLS 指纹），从而复用 M1 的可用通道。
     */
    suspend fun relay(
        method: String,
        path: String,
        headers: Map<String, String>,
        body: ByteArray,
        timeoutMs: Long = 120_000,
    ): RelayHttpResult {
        val view = webView ?: error("WebViewTransport is not initialized")
        val id = counter.incrementAndGet()
        val deferred = CompletableDeferred<RelayHttpResult>()
        pendingRelay[id] = deferred
        val headersJson = JSONObject(
            headers.filterKeys { it.lowercase() !in RELAY_SKIPPED_HEADERS },
        ).toString()
        val bodyBase64 = if (body.isEmpty()) {
            ""
        } else {
            Base64.encodeToString(body, Base64.NO_WRAP)
        }
        withContext(Dispatchers.Main) {
            val script = buildString {
                append("nativeRelay(")
                append(id)
                append(", ")
                append(JSONObject.quote(method))
                append(", ")
                append(JSONObject.quote(path))
                append(", ")
                append(JSONObject.quote(headersJson))
                append(", ")
                append(JSONObject.quote(bodyBase64))
                append(")")
            }
            view.evaluateJavascript(script, null)
        }
        return withTimeoutOrNull(timeoutMs) { deferred.await() } ?: run {
            pendingRelay.remove(id)
            throw IllegalStateException("WebView relay timed out: $method $path")
        }
    }

    fun close() {
        mainHandler.post {
            webView?.let { runCatching { it.destroy() } }
            webView = null
            currentBaseUrl = null
            ready = null
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView = WebView(activity).apply {
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            userAgentString = BROWSER_USER_AGENT
        }
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean = true
        }
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        addJavascriptInterface(Bridge(), "CinnyNative")
    }

    inner class Bridge {
        @JavascriptInterface
        fun onReady() {
            ready?.complete(Unit)
        }

        @JavascriptInterface
        fun onResponse(id: Int, status: Int, body: String) {
            pending.remove(id)?.complete(HttpResult(status, body))
        }

        @JavascriptInterface
        fun onBytes(id: Int, status: Int, base64: String) {
            val bytes = runCatching { Base64.decode(base64, Base64.DEFAULT) }.getOrDefault(ByteArray(0))
            pendingBytes.remove(id)?.complete(ByteResult(status, bytes))
        }

        @JavascriptInterface
        fun onBytesChunk(id: Int, index: Int, total: Int, status: Int, base64: String) {
            val chunk = runCatching { Base64.decode(base64, Base64.DEFAULT) }.getOrDefault(ByteArray(0))
            if (status < 0) {
                pendingChunks.remove(id)
                pendingBytes.remove(id)?.complete(ByteResult(status, ByteArray(0)))
                return
            }
            val buffer = pendingChunks.getOrPut(id) { java.io.ByteArrayOutputStream() }
            runCatching { buffer.write(chunk) }
            if (index < 0 || index >= total - 1) {
                pendingChunks.remove(id)
                pendingBytes.remove(id)?.complete(ByteResult(status, buffer.toByteArray()))
            }
        }

        @JavascriptInterface
        fun onRelay(id: Int, status: Int, headersJson: String, base64: String) {
            val bytes = runCatching { Base64.decode(base64, Base64.DEFAULT) }.getOrDefault(ByteArray(0))
            val headers = runCatching {
                val json = JSONObject(headersJson)
                buildMap {
                    val keys = json.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        put(key, json.optString(key))
                    }
                }
            }.getOrDefault(emptyMap())
            pendingRelay.remove(id)?.complete(RelayHttpResult(status, headers, bytes))
        }
    }

    companion object {
        const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/152.0.0.0 Safari/537.36 Edg/152.0.0.0"

        /**
         * 这些头部不能透传给 fetch：
         * 由 Chromium 重新生成（host/content-length/user-agent/...），
         * 或者会破坏同源 fetch（origin/referer）。
         */
        /** 单块字节数：base64 后约 256KB，远低于 Binder 1MB 限制。 */
        private const val CHUNK_BYTES = 192 * 1024

        private val RELAY_SKIPPED_HEADERS = setOf(
            "host",
            "content-length",
            "transfer-encoding",
            "connection",
            "accept-encoding",
            "cookie",
            "user-agent",
            "origin",
            "referer",
        )

        private val BRIDGE_HTML = """
            <!doctype html>
            <html><head><meta charset="utf-8"></head>
            <body>
            <script>
            (function () {
              function nativeFetch(id, method, path, token, body) {
                const headers = { 'Accept': 'application/json' };
                if (token) headers['Authorization'] = 'Bearer ' + token;
                if (body !== null && body !== undefined) headers['Content-Type'] = 'application/json';
                fetch(path, {
                  method: method,
                  headers: headers,
                  body: (body === null || body === undefined) ? undefined : body,
                  credentials: 'omit',
                  cache: 'no-store'
                }).then(async function (response) {
                  const text = await response.text();
                  window.CinnyNative.onResponse(id, response.status, text);
                }).catch(function (error) {
                  window.CinnyNative.onResponse(id, -1, String(error));
                });
              }
              function nativeFetchBytes(id, path, token) {
                fetch(path, {
                  headers: token ? { 'Authorization': 'Bearer ' + token } : {},
                  credentials: 'omit',
                  cache: 'no-store'
                }).then(async function (response) {
                  const buffer = await response.arrayBuffer();
                  const bytes = new Uint8Array(buffer);
                  let binary = '';
                  const chunk = 0x8000;
                  for (let i = 0; i < bytes.length; i += chunk) {
                    binary += String.fromCharCode.apply(null, bytes.subarray(i, i + chunk));
                  }
                  window.CinnyNative.onBytes(id, response.status, btoa(binary));
                }).catch(function (error) {
                  window.CinnyNative.onBytes(id, -1, '');
                });
              }
              window.__uploadChunks = {};
              function nativeUploadChunk(id, index, base64) {
                if (!window.__uploadChunks[id]) window.__uploadChunks[id] = [];
                window.__uploadChunks[id][index] = base64;
              }
              function nativeUploadAssemble(id, count, path, token, contentType) {
                const parts = window.__uploadChunks[id] || [];
                delete window.__uploadChunks[id];
                const binary = atob(parts.slice(0, count).join(''));
                const bytes = new Uint8Array(binary.length);
                for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
                fetch(path, {
                  method: 'POST',
                  headers: {
                    'Authorization': 'Bearer ' + token,
                    'Content-Type': contentType
                  },
                  body: bytes,
                  credentials: 'omit',
                  cache: 'no-store'
                }).then(async function (response) {
                  const text = await response.text();
                  window.CinnyNative.onResponse(id, response.status, text);
                }).catch(function (error) {
                  window.CinnyNative.onResponse(id, -1, String(error));
                });
              }
              function nativeFetchBytesChunked(id, path, token) {
                fetch(path, {
                  headers: token ? { 'Authorization': 'Bearer ' + token } : {},
                  credentials: 'omit',
                  cache: 'no-store'
                }).then(async function (response) {
                  const bytes = new Uint8Array(await response.arrayBuffer());
                  const chunk = 0x30000;
                  const total = Math.max(1, Math.ceil(bytes.length / chunk));
                  for (let i = 0; i < total; i++) {
                    const slice = bytes.subarray(i * chunk, Math.min(bytes.length, (i + 1) * chunk));
                    let binary = '';
                    const step = 0x8000;
                    for (let j = 0; j < slice.length; j += step) {
                      binary += String.fromCharCode.apply(null, slice.subarray(j, j + step));
                    }
                    window.CinnyNative.onBytesChunk(id, i, total, response.status, btoa(binary));
                  }
                }).catch(function (error) {
                  window.CinnyNative.onBytesChunk(id, -1, 0, -1, '');
                });
              }
              function nativeUpload(id, path, token, base64, contentType) {
                const binary = atob(base64);
                const bytes = new Uint8Array(binary.length);
                for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
                fetch(path, {
                  method: 'POST',
                  headers: {
                    'Authorization': 'Bearer ' + token,
                    'Content-Type': contentType
                  },
                  body: bytes,
                  credentials: 'omit',
                  cache: 'no-store'
                }).then(async function (response) {
                  const text = await response.text();
                  window.CinnyNative.onResponse(id, response.status, text);
                }).catch(function (error) {
                  window.CinnyNative.onResponse(id, -1, String(error));
                });
              }
              function base64FromBytes(bytes) {
                let binary = '';
                const chunk = 0x8000;
                for (let i = 0; i < bytes.length; i += chunk) {
                  binary += String.fromCharCode.apply(null, bytes.subarray(i, i + chunk));
                }
                return btoa(binary);
              }
              function nativeRelay(id, method, path, headersJson, bodyBase64) {
                const headers = JSON.parse(headersJson || '{}');
                const init = {
                  method: method,
                  headers: headers,
                  credentials: 'omit',
                  cache: 'no-store'
                };
                if (bodyBase64) {
                  const binary = atob(bodyBase64);
                  const bytes = new Uint8Array(binary.length);
                  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
                  init.body = bytes;
                }
                fetch(path, init).then(async function (response) {
                  const buffer = await response.arrayBuffer();
                  const headersOut = {};
                  response.headers.forEach(function (value, key) { headersOut[key] = value; });
                  window.CinnyNative.onRelay(
                    id,
                    response.status,
                    JSON.stringify(headersOut),
                    base64FromBytes(new Uint8Array(buffer))
                  );
                }).catch(function (error) {
                  window.CinnyNative.onRelay(id, -1, JSON.stringify({ error: String(error) }), '');
                });
              }
              window.nativeFetch = nativeFetch;
              window.nativeFetchBytes = nativeFetchBytes;
              window.nativeUpload = nativeUpload;
              window.nativeRelay = nativeRelay;
              window.nativeUploadChunk = nativeUploadChunk;
              window.nativeUploadAssemble = nativeUploadAssemble;
              window.nativeFetchBytesChunked = nativeFetchBytesChunked;
              window.CinnyNative.onReady();
            })();
            </script>
            </body></html>
        """.trimIndent()
    }
}
