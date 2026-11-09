package live.mehiz.mpvkt.network.remote

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import java.io.InputStream
import java.util.Properties
import java.util.Vector

/**
 * 基于 JSch（mwiede 维护分支）的 SFTP 远程连接。
 *
 * 关闭严格主机密钥校验以适配本地测试环境；文件句柄支持按偏移读取，
 * 顺序读取复用同一通道，非顺序偏移则重新打开并跳过指定字节。
 */
class SftpClient(private val profile: RemoteProfile) : RemoteClient {
  private val session: Session
  private val channel: ChannelSftp

  init {
    val jsch = JSch()
    session = jsch.getSession(
      profile.username.ifBlank { "anonymous" },
      profile.host,
      profile.port,
    )
    session.setPassword(profile.password)
    session.timeout = CONNECT_TIMEOUT_MS
    val config = Properties().apply {
      // 本地测试环境不预置 known_hosts，跳过主机密钥校验。
      put("StrictHostKeyChecking", "no")
    }
    session.setConfig(config)
    session.connect()
    channel = session.openChannel("sftp") as ChannelSftp
    channel.connect(CONNECT_TIMEOUT_MS)
  }

  override fun list(path: String): List<RemoteEntry> {
    // 始终使用以 / 开头的绝对路径：JSch 对相对路径会先经 REALPATH 解析 cwd，
    // 部分服务端的 REALPATH 实现可能不规范，绝对路径可直接跳过该解析，最稳妥。
    val target = toAbsolutePath(path)
    @Suppress("UNCHECKED_CAST")
    val entries = channel.ls(target) as Vector<ChannelSftp.LsEntry>
    return entries.mapNotNull { entry ->
      val name = entry.filename
      if (name == "." || name == "..") return@mapNotNull null
      val attrs = entry.attrs
      RemoteEntry(
        name = name,
        path = listOf(path.trim('/'), name).filter { it.isNotEmpty() }.joinToString("/"),
        directory = attrs.isDir,
        size = if (attrs.isDir) 0L else attrs.size,
        modifiedTime = attrs.mTime.toLong() * 1000L,
      )
    }
  }

  override fun open(path: String): RemoteFileHandle {
    val target = toAbsolutePath(path)
    val attrs = channel.stat(target)
    return SftpFileHandle(channel, target, attrs.size)
  }

  /** 规范化为以 / 开头的 SFTP 绝对路径：空路径→根，其余去掉首尾斜杠后再补前导 /。 */
  private fun toAbsolutePath(path: String): String {
    val trimmed = path.trim('/')
    return if (trimmed.isEmpty()) "/" else "/$trimmed"
  }

  override fun close() {
    runCatching { channel.disconnect() }
    runCatching { session.disconnect() }
  }

  private companion object {
    const val CONNECT_TIMEOUT_MS = 15_000
  }
}

/**
 * SFTP 文件句柄：JSch 的带偏移 get 支持从指定位置开始读取。
 * 顺序读取复用当前 InputStream，非顺序偏移则关闭重开。
 */
class SftpFileHandle(
  private val channel: ChannelSftp,
  private val remotePath: String,
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
    if (nextOffset >= size) closeStream()
    return total
  }

  private fun openAt(offset: Long): InputStream {
    closeStream()
    // 三参重载 get(String, SftpProgressMonitor, long)：直接从 offset 位置开始获取输入流。
    val input = channel.get(remotePath, null, offset)
    stream = input
    nextOffset = offset
    return input
  }

  private fun closeStream() {
    runCatching { stream?.close() }
    stream = null
  }

  @Synchronized
  override fun close() = closeStream()
}
