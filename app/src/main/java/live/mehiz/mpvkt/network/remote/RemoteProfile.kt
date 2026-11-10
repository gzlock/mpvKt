package live.mehiz.mpvkt.network.remote

import kotlinx.serialization.Serializable
import java.util.UUID

/** 远程存储支持的协议类型。 */
@Serializable
enum class ProtocolType(
  /** 协议的默认端口，新建连接时据此预填。 */
  val defaultPort: Int,
) {
  /** FTP 文件共享，默认端口 21。 */
  FTP(21),

  /** 基于 SSH 的 SFTP 文件共享，默认端口 22。 */
  SFTP(22),

  /** SMB/CIFS 文件共享，默认端口 445。 */
  SMB(445),

  /** WebDAV 文件共享，默认端口 80。 */
  WEBDAV(80),
}

/** 一条可在服务器上执行的命令片段，随对应连接配置一起保存。 */
@Serializable
data class CommandSnippet(
  val id: String = UUID.randomUUID().toString(),
  /** 命令名称，用于长按菜单与列表展示。 */
  val name: String,
  /** 实际下发到服务器执行的命令，可包含多行。 */
  val command: String,
  /**
   * 最近一次成功执行的时间戳（毫秒）。用于长按菜单按“最近执行”排序；
   * 为 null 表示从未执行过。默认 null 兼容旧数据反序列化。
   */
  val lastExecutedAt: Long? = null,
)

@Serializable
data class RemoteProfile(
  val id: String = UUID.randomUUID().toString(),
  val name: String,
  val host: String,
  val port: Int = 445,
  /** 连接使用的协议，决定端口默认值与可用的客户端/命令能力。 */
  val protocol: ProtocolType = ProtocolType.SMB,
  // SMB 专用：共享名；其他协议留空。
  val share: String = "",
  val startPath: String = "",
  val username: String = "",
  // SMB 专用：域/工作组。
  val domain: String = "",
  val password: String = "",
  val anonymous: Boolean = false,
  /** 服务器命令片段，仅 SFTP 与 SMB 协议使用。 */
  val commandSnippets: List<CommandSnippet> = emptyList(),
  /**
   * SMB 协议本身没有远程执行能力，其命令经同主机的 SSH 通道执行。
   * 为 null 或非正数时回退到 SSH 默认端口 22。SFTP 协议直接使用自身 [port]。
   */
  val commandPort: Int? = null,
  /**
   * 网络唤醒（Wake-on-LAN）目标主机的网卡 MAC 地址，支持“:”或“-”分隔。
   * 为空表示未配置唤醒，长按菜单触发时会提示先在编辑页填写。
   */
  val macAddress: String = "",
  /**
   * 魔术包的 UDP 广播地址；为空时使用全网广播 255.255.255.255。
   * 高级用户可改为网段定向广播（如 192.168.1.255）。
   */
  val wolBroadcastAddress: String = "",
  /** 魔术包发送的目标 UDP 端口，默认 9（部分设备使用 7）。 */
  val wolPort: Int = 9,
)

/** 该协议是否支持服务器命令片段（仅 SFTP 与 SMB）。 */
fun ProtocolType.supportsCommands(): Boolean =
  this == ProtocolType.SFTP || this == ProtocolType.SMB

/** 该连接是否支持服务器命令片段（仅 SFTP 与 SMB）。 */
fun RemoteProfile.supportsCommands(): Boolean = protocol.supportsCommands()

fun RemoteProfile.displayAddress(): String = if (protocol == ProtocolType.SMB && share.isNotBlank()) {
  "//$host/$share"
} else {
  "$protocol $host:$port"
}

fun RemoteProfile.remotePath(path: String): String {
  return listOf(startPath.trim('/'), path.trim('/'))
    .filter { it.isNotEmpty() }
    .joinToString("/")
}
