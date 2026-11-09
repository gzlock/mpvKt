package live.mehiz.mpvkt.network.remote

import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile
import java.io.IOException
import java.io.InputStream

/**
 * 基于 Apache Commons Net 的 FTP 远程连接。
 *
 * - 使用被动模式（[FTPClient.enterLocalPassiveMode]），避免设备在 NAT/防火墙下无法建立数据连接；
 * - 统一使用二进制传输，保证视频字节不被换行转换破坏；
 * - 文件句柄支持按偏移读取（REST + RETR），供本地 HTTP 代理分段拉取。
 */
class FtpClient(private val profile: RemoteProfile) : RemoteClient {
  private val ftp = FTPClient()

  init {
    // 连接前只能设置“不依赖底层 socket”的属性。
    // 注意：soTimeout 的 setter 会直接调用内部 socket.setSoTimeout，未连接时 socket 为 null，
    // 会抛 NullPointerException（源码已确认无空检查），因此这里不能在连接前设置 soTimeout。
    ftp.connectTimeout = CONNECT_TIMEOUT_MS
    // defaultTimeout 会在 connect 建立连接时经 applySocketAttributes 自动应用到控制 socket，
    // 作为控制通道的读取超时，等价于连接后再设置 soTimeout。
    ftp.defaultTimeout = READ_TIMEOUT_MS
    // setControlEncoding 接收编码名字符串而非 Charset。
    ftp.controlEncoding = "UTF-8"
    // 被动数据连接使用独立的 dataTimeout 字段（默认 -1 表示不超时），连接前可安全设置。
    ftp.setDataTimeout(java.time.Duration.ofMillis(READ_TIMEOUT_MS.toLong()))
    ftp.bufferSize = BUFFER_SIZE

    ftp.connect(profile.host, profile.port)
    val loggedIn = if (profile.anonymous || profile.username.isBlank()) {
      ftp.login("anonymous", "anonymous@example.com")
    } else {
      ftp.login(profile.username, profile.password)
    }
    if (!loggedIn) throw IOException("FTP login failed for ${profile.host}:${profile.port}")
    ftp.setFileType(FTPClient.BINARY_FILE_TYPE)
    ftp.enterLocalPassiveMode()
  }

  override fun list(path: String): List<RemoteEntry> {
    val normalized = path.trim('/')
    val files: Array<FTPFile> = ftp.listFiles(normalized)
      ?: throw IOException("FTP list failed: $normalized")
    return files.mapNotNull { item ->
      val name = item.name ?: return@mapNotNull null
      if (name == "." || name == "..") return@mapNotNull null
      RemoteEntry(
        name = name,
        path = listOf(normalized, name).filter { it.isNotEmpty() }.joinToString("/"),
        directory = item.isDirectory,
        size = if (item.isFile) item.size else 0L,
        modifiedTime = item.timestamp?.timeInMillis ?: 0L,
      )
    }
  }

  override fun open(path: String): RemoteFileHandle {
    val normalized = path.trim('/')
    // 先通过 mlistFile/mdtm + size 拿到文件总大小，再交给可重启句柄。
    val size = ftp.getSize(normalized)?.toLongOrNull()
      ?: throw IOException("Unable to resolve FTP file size: $normalized")
    return FtpFileHandle(ftp, normalized, size)
  }

  override fun close() {
    runCatching { ftp.logout() }
    runCatching { ftp.disconnect() }
  }

  private companion object {
    const val CONNECT_TIMEOUT_MS = 15_000
    const val READ_TIMEOUT_MS = 30_000
    const val BUFFER_SIZE = 64 * 1024
  }
}

/**
 * FTP 文件句柄：通过 REST（重启偏移）+ RETR 实现按偏移读取。
 *
 * HTTP 代理通常按顺序连续读取，因此缓存当前数据通道，顺序读取时复用；
 * 一旦出现非顺序偏移则中止当前通道并以新的 REST 偏移重新打开。
 */
class FtpFileHandle(
  private val ftp: FTPClient,
  private val remotePath: String,
  override val size: Long,
) : RemoteFileHandle {
  private var dataStream: InputStream? = null
  // 当前数据通道下一次读取对应的绝对偏移。
  private var nextOffset = 0L

  @Synchronized
  override fun read(offset: Long, target: ByteArray, length: Int): Int {
    if (offset >= size) return -1
    if (offset != nextOffset) openStreamAt(offset)
    val stream = dataStream ?: openStreamAt(offset)
    val wanted = minOf(length.toLong(), size - offset).toInt()
    var total = 0
    while (total < wanted) {
      val read = stream.read(target, total, wanted - total)
      if (read < 0) break
      total += read
    }
    nextOffset += total
    // 已读到文件末尾，关闭当前通道并完成挂起命令。
    if (nextOffset >= size) closeStream()
    return total
  }

  private fun openStreamAt(offset: Long): InputStream {
    closeStream()
    ftp.setRestartOffset(offset)
    val stream = ftp.retrieveFileStream(remotePath)
      ?: throw IOException("FTP RETR failed: $remotePath at offset $offset")
    dataStream = stream
    nextOffset = offset
    return stream
  }

  private fun closeStream() {
    dataStream?.let { stream ->
      runCatching { stream.close() }
      // 部分读取后需要 abort，完整读取则 completePendingCommand 收尾。
      if (nextOffset >= size) runCatching { ftp.completePendingCommand() } else runCatching { ftp.abort() }
    }
    dataStream = null
  }

  @Synchronized
  override fun close() {
    closeStream()
  }
}
