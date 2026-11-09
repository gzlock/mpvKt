package live.mehiz.mpvkt.network.remote

import java.io.Closeable

/**
 * 远程文件的统一只读句柄，屏蔽 SMB/FTP/SFTP/WebDAV 的协议差异，
 * 供本地 HTTP 代理按偏移分段读取并支持 Range 请求。
 */
interface RemoteFileHandle : Closeable {
  /** 文件总大小（字节）。 */
  val size: Long

  /**
   * 从绝对偏移 [offset] 处读取最多 [length] 字节，写入 [target] 的起始位置（下标 0）。
   * 返回实际读取的字节数；到达文件末尾且无数据可读时返回 -1。
   */
  fun read(offset: Long, target: ByteArray, length: Int): Int
}

/**
 * 各协议远程连接的统一抽象：负责目录列举与文件打开。
 * 实现类需保证线程安全，或由调用方串行使用。
 */
interface RemoteClient : Closeable {
  /** 列举 [path] 目录下的条目（不含 "."、".."）。 */
  fun list(path: String): List<RemoteEntry>

  /** 打开 [path] 指向的文件，返回可按偏移读取的句柄，调用方负责关闭。 */
  fun open(path: String): RemoteFileHandle
}
