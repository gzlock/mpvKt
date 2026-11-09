package live.mehiz.mpvkt.network.remote

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.ProtocolException
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Locale
import java.util.TimeZone

/**
 * 基于 [HttpURLConnection] 的 WebDAV 远程连接。
 *
 * - 目录列举通过 PROPFIND（Depth: 1）+ XML multistatus 解析；
 * - 文件读取通过带 Range 的 GET 实现按偏移分段；
 * - 支持 Basic 认证与匿名访问。
 *
 * 注意：[HttpURLConnection.setRequestMethod] 默认不接受 PROPFIND，
 * 这里通过反射改写内部 method 字段绕过校验。
 */
class WebDavClient(private val profile: RemoteProfile) : RemoteClient {
  // 服务根地址，mock 环境使用 http。
  private val baseUrl = "http://${profile.host}:${profile.port}"

  override fun list(path: String): List<RemoteEntry> {
    val encoded = encodePath(path)
    val conn = newConnection(encoded, "PROPFIND")
    conn.setRequestProperty("Depth", "1")
    conn.setRequestProperty("Content-Type", "application/xml; charset=utf-8")
    // 空的 PROPFIND 请求体，服务端返回全部属性。
    conn.doOutput = true
    conn.outputStream.use { it.write(EMPTY_PROPFIND_BODY) }
    conn.connect()
    if (conn.responseCode !in 200..299) {
      throw IOException("PROPFIND failed (${conn.responseCode}) for $path")
    }
    return parseMultistatus(conn.inputStream, path.trim('/'))
  }

  override fun open(path: String): RemoteFileHandle {
    val encoded = encodePath(path)
    // 先用 HEAD 拿到总大小。
    val head = newConnection(encoded, "HEAD")
    val size = if (head.responseCode in 200..299) {
      head.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
    } else {
      -1L
    }
    if (size < 0) throw IOException("Unable to resolve WebDAV file size: $path")
    return WebDavFileHandle(this, encoded, size)
  }

  /** 打开一个从 [offset] 开始的 GET 流（带 Range）。 */
  internal fun openRange(encodedPath: String, offset: Long): InputStream {
    val conn = newConnection(encodedPath, "GET")
    conn.setRequestProperty("Range", "bytes=$offset-")
    if (conn.responseCode != HttpURLConnection.HTTP_PARTIAL &&
      conn.responseCode != HttpURLConnection.HTTP_OK
    ) {
      throw IOException("GET failed (${conn.responseCode}) for $encodedPath")
    }
    return conn.inputStream
  }

  override fun close() {
    // 无连接池，每次请求独立，无需额外清理。
  }

  private fun newConnection(encodedPath: String, method: String): HttpURLConnection {
    val url = URL("$baseUrl/$encodedPath")
    val conn = url.openConnection() as HttpURLConnection
    conn.connectTimeout = CONNECT_TIMEOUT_MS
    conn.readTimeout = READ_TIMEOUT_MS
    conn.instanceFollowRedirects = true
    forceRequestMethod(conn, method)
    if (!profile.anonymous && profile.username.isNotBlank()) {
      val credentials = "${profile.username}:${profile.password}"
      val token = Base64.getEncoder().encodeToString(credentials.toByteArray(Charsets.UTF_8))
      conn.setRequestProperty("Authorization", "Basic $token")
    }
    return conn
  }

  /** 解析 multistatus 响应，跳过代表目录自身的第一个 response。 */
  private fun parseMultistatus(stream: InputStream, selfPath: String): List<RemoteEntry> {
    val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }
      .newPullParser()
    parser.setInput(stream, "UTF-8")
    val entries = mutableListOf<RemoteEntry>()
    var event = parser.eventType
    while (event != XmlPullParser.END_DOCUMENT) {
      if (event == XmlPullParser.START_TAG && parser.name == "response") {
        parseResponse(parser)?.let { entry ->
          val entryPath = entry.path.trim('/')
          if (entryPath != selfPath && entry.name.isNotBlank()) entries += entry
        }
      }
      event = parser.next()
    }
    return entries
  }

  /** 解析单个 response 节点。 */
  private fun parseResponse(parser: XmlPullParser): RemoteEntry? {
    var href = ""
    var length = 0L
    var isCollection = false
    var modified = 0L
    var depth = 1
    while (depth > 0) {
      val event = parser.next()
      when (event) {
        XmlPullParser.START_TAG -> {
          depth++
          when (parser.name) {
            "href" -> href = parser.nextText()
            "getcontentlength" -> length = parser.nextText().toLongOrNull() ?: 0L
            "collection" -> isCollection = true
            "getlastmodified" -> modified = parseHttpDate(parser.nextText())
          }
        }
        XmlPullParser.END_TAG -> {
          depth--
          if (depth == 0 && parser.name == "response") {
            // 到达 response 结束
          }
        }
      }
    }
    // 从 href 推导名称与相对路径，最可靠。
    val decoded = decodeHref(href)
    val relative = decoded.trim('/')
    val name = relative.substringAfterLast('/')
    return RemoteEntry(
      name = name,
      path = relative,
      directory = isCollection,
      size = if (isCollection) 0L else length,
      modifiedTime = modified,
    )
  }

  private fun parseHttpDate(value: String): Long = runCatching {
    HTTP_DATE_FORMAT.get().parse(value)?.time ?: 0L
  }.getOrDefault(0L)

  private companion object {
    const val CONNECT_TIMEOUT_MS = 15_000
    const val READ_TIMEOUT_MS = 30_000
    val EMPTY_PROPFIND_BODY = ByteArray(0)

    val HTTP_DATE_FORMAT = ThreadLocal.withInitial {
      SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("GMT")
      }
    }

    /** 反射改写 HttpURLConnection 的 method 字段以支持 PROPFIND。 */
    fun forceRequestMethod(conn: HttpURLConnection, method: String) {
      try {
        conn.requestMethod = method
      } catch (e: ProtocolException) {
        var clazz: Class<in HttpURLConnection>? = conn.javaClass
        while (clazz != null) {
          val field = runCatching { clazz.getDeclaredField("method") }.getOrNull()
          if (field != null) {
            field.isAccessible = true
            field.set(conn, method)
            return
          }
          clazz = clazz.superclass
        }
        throw ProtocolException("Unsupported request method: $method")
      }
    }

    /** 对路径逐段 URL 编码，保留分隔用的 "/"。 */
    fun encodePath(path: String): String = path.trim('/')
      .split('/')
      .joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8") }

    fun decodeHref(href: String): String {
      // 兼容完整 URL（http://host:port/path）：去掉协议与主机部分，只保留路径。
      val pathOnly = if (href.contains("://")) {
        href.substringAfter("://").substringAfter('/', "")
      } else {
        href
      }
      return pathOnly.trim('/')
        .split('/')
        .joinToString("/") { java.net.URLDecoder.decode(it, "UTF-8") }
    }
  }
}

/** WebDAV 文件句柄：用带 Range 的 GET 按偏移读取，顺序读取复用流。 */
class WebDavFileHandle(
  private val client: WebDavClient,
  private val encodedPath: String,
  override val size: Long,
) : RemoteFileHandle {
  private var stream: InputStream? = null
  private var nextOffset = 0L

  @Synchronized
  override fun read(offset: Long, target: ByteArray, length: Int): Int {
    if (offset >= size) return -1
    if (offset != nextOffset || stream == null) openAt(offset)
    val input = stream ?: openAt(offset)
    val wanted = minOf(length.toLong(), size - offset).toInt()
    var total = 0
    while (total < wanted) {
      val read = input.read(target, total, wanted - total)
      if (read < 0) break
      total += read
    }
    nextOffset += total
    return total
  }

  private fun openAt(offset: Long): InputStream {
    runCatching { stream?.close() }
    val input = client.openRange(encodedPath, offset)
    stream = input
    nextOffset = offset
    return input
  }

  @Synchronized
  override fun close() {
    runCatching { stream?.close() }
    stream = null
  }
}
