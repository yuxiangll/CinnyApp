package top.s0cl.cinny.matrix.net

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/** 中继请求：来自 matrix-rust-sdk（reqwest/hyper）的客户端请求。 */
data class RelayHttpRequest(
    val method: String,
    val target: String,
    val headers: Map<String, String>,
    val body: ByteArray,
) {
    /** origin-form 的 path + query。 */
    val path: String
        get() {
            if (!target.startsWith("http", ignoreCase = true)) return target
            val schemeEnd = target.indexOf("://")
            if (schemeEnd < 0) return target
            val pathStart = target.indexOf('/', schemeEnd + 3)
            return if (pathStart < 0) "/" else target.substring(pathStart)
        }
}

/** 中继响应：由上游（Chrome/WebView 网络栈）返回。 */
data class RelayHttpResult(
    val status: Int,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray = ByteArray(0),
) {
    val contentType: String?
        get() = headers.entries.firstOrNull { it.key.equals("content-type", ignoreCase = true) }?.value

    val bodyText: String
        get() = runCatching { body.toString(Charsets.UTF_8) }.getOrDefault("")
}

/**
 * M3：本机 HTTP 中继。
 *
 * matrix-rust-sdk 使用 rustls，TLS ClientHello 会被 s0cl.top 前置 WAF 重置。
 * 这里让 rust-sdk 访问 http://127.0.0.1:<port>（明文、无需 TLS），
 * 中继再把请求交给 WebView 的 Chromium 网络栈发出，从而复用可用的 TLS 指纹。
 *
 * 只实现 rust-sdk 需要的 HTTP/1.1 子集：请求行 + 头部 + Content-Length/chunked 请求体，
 * 响应固定使用 Connection: close。
 */
class LocalRelayServer(
    private val upstream: suspend (RelayHttpRequest) -> RelayHttpResult,
) {
    @Volatile
    private var serverSocket: ServerSocket? = null

    private var acceptJob: Job? = null

    val port: Int
        get() = serverSocket?.localPort ?: 0

    fun start(scope: CoroutineScope): Int {
        stop()
        val socket = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
        serverSocket = socket
        acceptJob = scope.launch(Dispatchers.IO) {
            Log.i(TAG, "relay listening on 127.0.0.1:" + socket.localPort)
            while (isActive && !socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (error: Throwable) {
                    if (!socket.isClosed) Log.w(TAG, "accept failed", error)
                    break
                }
                launch(Dispatchers.IO) { serve(client) }
            }
        }
        return socket.localPort
    }

    fun stop() {
        acceptJob?.cancel()
        acceptJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private suspend fun serve(socket: Socket) {
        socket.use { client ->
            try {
                client.tcpNoDelay = true
                client.soTimeout = RELAY_SO_TIMEOUT_MS
                val input = client.getInputStream().buffered()
                val output = client.getOutputStream()
                val request = readRequest(input, output) ?: return
                val result = try {
                    upstream(request)
                } catch (error: Throwable) {
                    Log.w(TAG, "upstream failed: " + request.method + " " + request.path, error)
                    RelayHttpResult(
                        status = 502,
                        headers = mapOf("content-type" to "text/plain; charset=utf-8"),
                        body = (error.message ?: error::class.simpleName.orEmpty()).toByteArray(),
                    )
                }
                writeResponse(output, result)
            } catch (error: Throwable) {
                Log.w(TAG, "relay connection failed", error)
            }
        }
    }

    private fun readRequest(input: InputStream, output: OutputStream): RelayHttpRequest? {
        val requestLine = readLine(input) ?: return null
        val parts = requestLine.trim().split(' ')
        if (parts.size < 3) return null
        val method = parts[0].uppercase()
        val target = parts[1]
        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val separator = line.indexOf(':')
            if (separator <= 0) continue
            headers[line.substring(0, separator).trim().lowercase()] =
                line.substring(separator + 1).trim()
        }
        if (headers["expect"]?.contains("100-continue", ignoreCase = true) == true) {
            output.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray())
            output.flush()
        }
        val body = when {
            headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true ->
                readChunked(input)
            headers["content-length"] != null ->
                readExactly(input, headers["content-length"]!!.toIntOrNull() ?: 0)
            else -> ByteArray(0)
        }
        return RelayHttpRequest(method, target, headers, body)
    }

    private fun readChunked(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val sizeLine = readLine(input) ?: break
            val size = sizeLine.substringBefore(';').trim().toIntOrNull(16) ?: break
            if (size == 0) {
                while (true) {
                    val trailer = readLine(input) ?: break
                    if (trailer.isEmpty()) break
                }
                break
            }
            out.write(readExactly(input, size))
            readLine(input)
        }
        return out.toByteArray()
    }

    private fun readExactly(input: InputStream, length: Int): ByteArray {
        if (length <= 0) return ByteArray(0)
        val buffer = ByteArray(length)
        var read = 0
        while (read < length) {
            val count = input.read(buffer, read, length - read)
            if (count < 0) break
            read += count
        }
        return if (read == length) buffer else buffer.copyOf(read)
    }

    private fun readLine(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        while (true) {
            val byte = input.read()
            if (byte == -1) return if (out.size() == 0) null else out.toString("UTF-8")
            if (byte == '\n'.code) {
                val raw = out.toString("UTF-8")
                return raw.removeSuffix("\r")
            }
            out.write(byte)
        }
    }

    private fun writeResponse(output: OutputStream, result: RelayHttpResult) {
        val head = StringBuilder()
        head.append("HTTP/1.1 ").append(result.status).append(' ')
        head.append(reason(result.status)).append("\r\n")
        var hasContentType = false
        result.headers.forEach { (name, value) ->
            val lower = name.lowercase()
            if (lower in HOP_BY_HOP_HEADERS) return@forEach
            if (lower == "content-type") hasContentType = true
            head.append(name).append(": ").append(value).append("\r\n")
        }
        if (!hasContentType) head.append("Content-Type: application/json\r\n")
        head.append("Content-Length: ").append(result.body.size).append("\r\n")
        head.append("Connection: close\r\n\r\n")
        output.write(head.toString().toByteArray())
        if (result.body.isNotEmpty()) output.write(result.body)
        output.flush()
    }

    private fun reason(status: Int): String = when (status) {
        200 -> "OK"
        201 -> "Created"
        204 -> "No Content"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        429 -> "Too Many Requests"
        500 -> "Internal Server Error"
        502 -> "Bad Gateway"
        else -> "OK"
    }

    companion object {
        private const val TAG = "LocalRelayServer"
        private const val RELAY_SO_TIMEOUT_MS = 120_000

        private val HOP_BY_HOP_HEADERS = setOf(
            "connection",
            "keep-alive",
            "transfer-encoding",
            "content-length",
            "content-encoding",
            "date",
            "server",
        )
    }
}
