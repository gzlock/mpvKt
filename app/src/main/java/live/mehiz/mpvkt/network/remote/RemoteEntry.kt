package live.mehiz.mpvkt.network.remote

/**
 * 远程目录条目，屏蔽 SMB/FTP/SFTP/WebDAV 的协议差异。
 */
data class RemoteEntry(
  val name: String,
  val path: String,
  val directory: Boolean,
  val size: Long = 0L,
  val modifiedTime: Long = 0L,
)
