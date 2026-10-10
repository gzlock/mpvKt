package live.mehiz.mpvkt.network.smb

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.min

class SmbHttpProxy : Closeable {
  private val server = ServerSocket(0, 32, java.net.InetAddress.getByName("127.0.0.1"))
  private val executor = Executors.newCachedThreadPool()
  private val files = ConcurrentHashMap<String, SmbRemoteFile>()
  private val acceptThread = Thread {
    while (!server.isClosed) {
      runCatching { server.accept() }.onSuccess { socket -> executor.execute { handle(socket) } }
    }
  }.apply { name = "smb-http-proxy"; start() }

  fun publish(file: SmbRemoteFile): String {
    val token = UUID.randomUUID().toString()
    files[token] = file
    return "http://127.0.0.1:${server.localPort}/$token"
  }

  private fun handle(socket: Socket) {
    socket.use { client ->
      client.soTimeout = SOCKET_TIMEOUT_MILLIS
      val output = BufferedOutputStream(client.getOutputStream())
      var responseStarted = false
      try {
        val request = BufferedInputStream(client.getInputStream()).readRequest()
        if (request == null) {
          writeError(output, 400, "Bad Request")
          return
        }
        if (request.method != "GET" && request.method != "HEAD") {
          writeError(output, 405, "Method Not Allowed", "Allow: GET, HEAD\r\n")
          return
        }
        val token = runCatching {
          URLDecoder.decode(request.path.substringBefore('?').substringAfterLast('/'), Charsets.UTF_8.name())
        }.getOrNull()
        val file = token?.let(files::get)
        if (file == null) {
          writeError(output, 404, "Not Found")
          return
        }
        val size = file.size
        if (size < 0) {
          writeError(output, 502, "Bad Gateway")
          return
        }
        val requestedRange = request.headers["range"]
        val range = requestedRange?.let { parseRange(it, size) }
        if (requestedRange != null && range == null) {
          writeRangeError(output, size)
          return
        }
        val contentLength = range?.let { it.second - it.first + 1 } ?: size
        val contentRange = range?.let { (start, end) -> "bytes $start-$end/$size" }.orEmpty()
        writeHeaders(output, if (range == null) 200 else 206, contentLength, contentRange)
        responseStarted = true
        if (request.method == "HEAD") return

        val buffer = ByteArray(128 * 1024)
        var offset = range?.first ?: 0L
        var remaining = contentLength
        while (remaining > 0) {
          val read = file.read(offset, buffer, min(buffer.size.toLong(), remaining).toInt())
          if (read <= 0) break
          output.write(buffer, 0, read)
          output.flush()
          offset += read
          remaining -= read
        }
      } catch (_: Exception) {
        if (!responseStarted) runCatching { writeError(output, 502, "Bad Gateway") }
      }
    }
  }

  private fun writeHeaders(output: BufferedOutputStream, status: Int, length: Long, contentRange: String) {
    val statusText = if (status == 206) "Partial Content" else "OK"
    val headers = (
      "HTTP/1.1 $status $statusText\r\nContent-Length: $length\r\nAccept-Ranges: bytes\r\n" +
        "Content-Type: application/octet-stream\r\n" +
        (if (contentRange.isNotEmpty()) "Content-Range: $contentRange\r\n" else "") +
        "Connection: close\r\n\r\n"
      ).toByteArray()
    output.write(headers)
    output.flush()
  }

  private fun writeRangeError(output: BufferedOutputStream, size: Long) {
    output.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$size\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
    output.flush()
  }

  private fun writeError(output: BufferedOutputStream, status: Int, reason: String, extraHeaders: String = "") {
    output.write("HTTP/1.1 $status $reason\r\n${extraHeaders}Content-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
    output.flush()
  }

  private fun parseRange(value: String, size: Long): Pair<Long, Long>? {
    val match = Regex("bytes=(\\d*)-(\\d*)", RegexOption.IGNORE_CASE).matchEntire(value.trim()) ?: return null
    val start = match.groupValues[1].toLongOrNull()
    val end = match.groupValues[2].toLongOrNull()
    return when {
      size > 0 && start != null && start < size && (end == null || end >= start) -> start to (end ?: size - 1).coerceAtMost(size - 1)
      size > 0 && start == null && end != null && end > 0 -> (size - end).coerceAtLeast(0) to (size - 1)
      else -> null
    }
  }

  override fun close() {
    files.values.forEach { runCatching { it.close() } }
    files.clear()
    runCatching { server.close() }
    executor.shutdownNow()
    acceptThread.interrupt()
  }
}

private data class HttpRequest(val method: String, val path: String, val headers: Map<String, String>)

private fun BufferedInputStream.readRequest(): HttpRequest? {
  val bytes = StringBuilder()
  while (true) {
    val value = read()
    if (value < 0) return null
    bytes.append(value.toChar())
    if (bytes.endsWith("\r\n\r\n")) break
    if (bytes.length > 32 * 1024) return null
  }
  val lines = bytes.toString().trim().split("\r\n")
  val first = lines.firstOrNull()?.split(' ') ?: return null
  return HttpRequest(first.getOrNull(0) ?: return null, first.getOrNull(1) ?: return null, lines.drop(1).mapNotNull {
    it.substringBefore(':', "").takeIf(String::isNotEmpty)?.let { key -> key.lowercase() to it.substringAfter(':').trim() }
  }.toMap())
}

private fun StringBuilder.endsWith(value: String): Boolean = length >= value.length && substring(length - value.length) == value

private const val SOCKET_TIMEOUT_MILLIS = 60_000
