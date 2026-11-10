package live.mehiz.mpvkt.network.remote

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * 网络唤醒（Wake-on-LAN）魔术包发送器。
 *
 * 魔术包结构固定为 102 字节：前 6 字节为同步流 0xFF，随后把目标 MAC（6 字节）
 * 重复 16 次。通过 UDP 广播发送，默认端口 9，仅在同一局域网内可靠。
 *
 * 仅使用 JDK 自带的 [DatagramSocket]，无需引入额外依赖。
 */
object WakeOnLan {

  /** 魔术包固定长度：6 字节同步流 + 16 组 MAC（每组 6 字节）。 */
  private const val MAGIC_PACKET_LENGTH = 6 + 16 * 6

  /** 全网广播地址，当连接未单独配置广播地址时使用。 */
  private const val DEFAULT_BROADCAST = "255.255.255.255"

  /**
   * 解析 MAC 地址字符串，兼容“AA:BB:CC:DD:EE:FF”与“AA-BB-..”两种分隔方式，
   * 也容忍无分隔的 12 位十六进制。返回 6 字节数组；格式非法时抛出 [IllegalArgumentException]。
   */
  fun parseMac(mac: String): ByteArray {
    val hex = mac.trim().replace(":", "").replace("-", "")
    require(hex.length == 12 && hex.all { it.isHexDigit() }) {
      "Invalid MAC address: $mac"
    }
    return ByteArray(6) { i ->
      hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
  }

  /**
   * 发送一次网络唤醒魔术包。
   *
   * @param mac 目标主机网卡 MAC
   * @param broadcastAddress 广播地址，为空时使用全网广播 255.255.255.255
   * @param port 目标 UDP 端口，非法（非 1..65535）时回退默认端口 9
   */
  fun send(mac: String, broadcastAddress: String, port: Int) {
    val macBytes = parseMac(mac)
    val packetBytes = ByteArray(MAGIC_PACKET_LENGTH)
    // 前 6 字节填入 0xFF 同步流。
    for (i in 0 until 6) packetBytes[i] = 0xFF.toByte()
    // 随后把 MAC 重复 16 次。
    for (repeat in 0 until 16) {
      System.arraycopy(macBytes, 0, packetBytes, 6 + repeat * 6, 6)
    }
    val targetHost = broadcastAddress.trim().ifBlank { DEFAULT_BROADCAST }
    val targetPort = port.takeIf { it in 1..65535 } ?: 9
    val address = InetAddress.getByName(targetHost)
    DatagramSocket().use { socket ->
      // 发送广播数据必须开启 SO_BROADCAST。
      socket.broadcast = true
      socket.send(DatagramPacket(packetBytes, packetBytes.size, address, targetPort))
    }
  }
}

/** 判断字符是否为十六进制数字（不依赖 JDK 17 的 isDigit 重载，语义更明确）。 */
private fun Char.isHexDigit(): Boolean =
  this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
