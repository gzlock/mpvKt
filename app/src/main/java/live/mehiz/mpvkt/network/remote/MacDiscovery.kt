package live.mehiz.mpvkt.network.remote

import android.system.Os
import android.system.OsConstants
import android.system.StructTimeval
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 基于内核邻居表 / NetBIOS 的 MAC 地址自动发现。
 *
 * 现代 Android（10+）SELinux 对普通 App 直接读取邻居表收紧：读“/proc/net/arp”与 netlink
 * 消息 nlmsg_getneigh 都可能被拒绝。因此这里按可靠性与权限要求，依次尝试三条路径：
 *
 * 1. **NetBIOS 节点状态查询（首选）**：向目标 UDP 137 发送 NODE STATUS 请求（RFC 1001/1002），
 *    响应的节点统计里包含 6 字节 unit ID，即网卡 MAC。这是普通 UDP 数据报，App 无需任何特殊
 *    SELinux 权限；只有支持 NetBIOS 的设备（Windows/SMB 宿主）会响应，正是 SMB 唤醒场景。
 * 2. **Netlink RTM_GETNEIGH（兜底）**：不 bind、sendto 空地址，靠内核自动绑定，部分设备仍放行。
 * 3. **“/proc/net/arp”（旧设备兜底）**：老设备的经典做法。
 *
 * 局限：目标不支持 NetBIOS、且 netlink/proc 又被封时读不到，返回 null 由用户手动填写。
 */
object MacDiscovery {

  /** 系统 ARP 表路径（旧设备兜底用）。 */
  private val ARP_FILE = File("/proc/net/arp")

  /** ARP 表中“完整有效”条目的标志位（ATF_COM）。 */
  private const val FLAG_COMPLETE = "0x2"

  /** 用于强制 ARP 解析的常见端口（连接被拒绝也不影响 ARP 已完成）。 */
  private val COMMON_PORTS = intArrayOf(7, 22, 80, 139, 443, 445)

  /** 单次 TCP 连接尝试超时（毫秒）。 */
  private const val CONNECT_TIMEOUT_MS = 800

  /** ARP 读取轮询次数与间隔，覆盖内核写入的短暂延迟。 */
  private const val ARP_POLL_TIMES = 5
  private const val ARP_POLL_INTERVAL_MS = 300L

  // ---- NetBIOS 常量（RFC 1001/1002）----

  /** NetBIOS 名称服务端口。 */
  private const val NBNS_PORT = 137

  /** 问题/答案类型：节点状态；问题类别：Internet。 */
  private const val QTYPE_NBSTAT = 0x0021
  private const val QCLASS_IN = 0x0001

  /** 请求头里的递归期望标志位（RD）。 */
  private const val FLAGS_RD = 0x0010

  /** 节点名称条目固定长度：15 名字 + 1 后缀 + 2 标志。 */
  private const val NODE_NAME_ENTRY_LEN = 18

  // ---- Netlink 协议常量（Linux 标准值，未在 OsConstants 暴露，故自行定义）----

  /** 邻居查询请求消息类型。 */
  private const val RTM_GETNEIGH = 30

  /** 邻居查询结果消息类型。 */
  private const val RTM_NEWNEIGH = 28

  /** 一批 dump 结束的消息类型。 */
  private const val NLMSG_DONE = 3

  /** 消息标志：请求；ROOT|MATCH 组合即 dump（列出全部）。 */
  private const val NLM_F_REQUEST = 0x1
  private const val NLM_F_ROOT = 0x100
  private const val NLM_F_MATCH = 0x200
  private const val NLM_F_DUMP = NLM_F_ROOT or NLM_F_MATCH

  /** 邻居属性：目标地址；链路层地址（MAC）。 */
  private const val NDA_DST = 1
  private const val NDA_LLADDR = 2

  /** nlmsghdr 长度（16 字节）。 */
  private const val NLMSG_HDRLEN = 16

  /** ndmsg 长度（12 字节：family/pad1/pad2/ifindex/state/flags/type）。 */
  private const val NDM_HDRLEN = 12

  /** netlink 单次接收缓冲区大小。 */
  private const val RECV_BUFFER_SIZE = 8192

  /** 接收超时（毫秒）与最大读取轮次，避免无数据时永久阻塞。 */
  private const val RECV_TIMEOUT_MS = 1500L
  private const val MAX_RECV_ROUNDS = 20

  /**
   * 发现指定主机的 MAC 地址。
   *
   * @param host 主机 IP 或可解析的主机名
   * @param port 该连接已知的端口（如有），会用于 netlink 兜底路径的强制 ARP
   * @return 形如“F0:2F:74:2E:6B:E6”的 MAC；无法发现时返回 null
   */
  suspend fun discover(host: String, port: Int? = null): String? = withContext(Dispatchers.IO) {
    runCatching {
      val address = InetAddress.getByName(host.trim())
      // 自动发现仅支持 IPv4 局域网场景（SMB/FTP 等），IPv6 直接走 proc 兜底。
      if (address !is Inet4Address) return@runCatching discoverFromArpFile(address)

      // 1) NetBIOS 节点状态（无需特殊权限，Windows/SMB 首选）。
      discoverViaNetbios(address)
      // 2) 强制 ARP 后尝试 netlink。
        ?: run {
          forceArp(address, port)
          discoverViaNetlink(address.address)
        }
      // 3) /proc/net/arp 旧设备兜底。
        ?: address.hostAddress?.let { discoverFromArpFile(it) }
    }.getOrNull()
  }

  // ================= NetBIOS 节点状态查询 =================

  /**
   * 向目标发送 NetBIOS NODE STATUS 请求并解析 MAC。
   *
   * 任意异常（端口不通/超时/响应格式不符）都向上抛出，由调用方静默进入下一兜底路径。
   */
  private fun discoverViaNetbios(address: InetAddress): String? {
    DatagramSocket().use { socket ->
      socket.soTimeout = RECV_TIMEOUT_MS.toInt()
      val request = buildNodeStatusRequest(txId = 1)
      socket.send(DatagramPacket(request, request.size, address, NBNS_PORT))

      val buffer = ByteArray(1024)
      val packet = DatagramPacket(buffer, buffer.size)
      socket.receive(packet)
      return parseNodeStatusResponse(buffer.copyOf(packet.length))
    }
  }

  /**
   * 构造 NetBIOS 节点状态请求（共 50 字节）：
   * 12 字节 NBNS 头 + 通配名（1 长度 + 32 编码 + 1 结束）+ QTYPE + QCLASS。
   */
  private fun buildNodeStatusRequest(txId: Int): ByteArray {
    // 通配名（RFC 1001/1002）：16 字节为 “*”(0x2A) + 15 个 0x00，按 nibble 编码
    //（每字节→两个 A..P 字符）。0x2A → “CK”，0x00 → “AA”，故为 “CK” + 15 对“AA”，共 32 字符。
    val encodedName = ByteArray(32)
    encodedName[0] = 'C'.code.toByte()
    encodedName[1] = 'K'.code.toByte()
    var i = 2
    repeat(15) {
      encodedName[i++] = 'A'.code.toByte()
      encodedName[i++] = 'A'.code.toByte()
    }

    val request = ByteArray(50)
    var p = 0
    p = putU16(request, p, txId)
    p = putU16(request, p, FLAGS_RD)   // 标志：opcode=0 查询、RD=1
    p = putU16(request, p, 1)          // QDCOUNT
    p = putU16(request, p, 0)          // ANCOUNT
    p = putU16(request, p, 0)          // NSCOUNT
    p = putU16(request, p, 0)          // ARCOUNT
    request[p++] = 0x20               // 名称标签长度 32
    encodedName.copyInto(request, p); p += 32
    request[p++] = 0x00               // 根标签结束
    p = putU16(request, p, QTYPE_NBSTAT)
    p = putU16(request, p, QCLASS_IN)
    return request
  }

  /**
   * 解析 NODE STATUS 响应，跳过问题与答案头，从 RDATA 中取出 6 字节 unit ID（MAC）。
   */
  private fun parseNodeStatusResponse(data: ByteArray): String? {
    var p = 0
    p += 2  // 事务 ID
    val flags = u16(data, p); p += 2
    // 低 4 位为 RCODE，非 0 即出错。
    if (flags and 0x000F != 0) return null
    val qdCount = u16(data, p); p += 2
    val anCount = u16(data, p); p += 2
    p += 4  // NSCOUNT + ARCOUNT
    if (anCount < 1) return null

    // 仅当存在问题区时才跳过（Windows 响应常把 QDCOUNT 置 0，名称直接作为答案字段）。
    if (qdCount >= 1) {
      p = skipEncodedName(data, p)
      p += 4  // 问题 QTYPE + QCLASS
    }

    // 答案：名称（可能是压缩指针）+ TYPE + CLASS + TTL + RDATA 长度。
    p = skipEncodedName(data, p)
    p += 2  // TYPE
    p += 2  // CLASS
    p += 4  // TTL
    p += 2  // RDATA 长度

    // RDATA：名称数量（1 字节）+ 若干 18 字节名称条目。
    val numNames = data[p].toInt() and 0xFF; p += 1
    p += numNames * NODE_NAME_ENTRY_LEN

    // 名称条目之后即 STATISTICS 块，其开头 6 字节 UNIT_ID 就是网卡 MAC（RFC 1002 4.2.18）。
    if (p + 6 > data.size) return null
    return formatMac(data.copyOfRange(p, p + 6))
  }

  /**
   * 跳过一个（可能含压缩指针的）编码名。
   * - 普通标签：逐段跳过，直到 0x00 根标签；
   * - 压缩指针：最高两位置 1（0xC0），名称仅占 2 字节，直接跳过即可。
   */
  private fun skipEncodedName(data: ByteArray, start: Int): Int {
    var p = start
    while (p < data.size) {
      val b = data[p].toInt() and 0xFF
      if (b == 0) return p + 1
      if ((b and 0xC0) == 0xC0) return p + 2
      p += 1 + b
    }
    return p
  }

  /** 并发向目标常见端口发起 TCP 连接，强制内核进行 ARP 解析；异常全部忽略。 */
  private suspend fun forceArp(address: InetAddress, port: Int?) {
    val ports = buildList {
      if (port != null && port in 1..65535) add(port)
      addAll(COMMON_PORTS.toList())
    }.distinct()
    coroutineScope {
      ports.forEach { candidate ->
        launch {
          runCatching {
            Socket().use { socket ->
              socket.connect(InetSocketAddress(address, candidate), CONNECT_TIMEOUT_MS)
            }
          }
        }
      }
    }
  }

  // ================= Netlink 兜底 =================

  /**
   * 通过未绑定的 NETLINK_ROUTE 套接字查询邻居表，返回目标 IP 的 MAC。
   *
   * 不调用 bind：直接以空地址 sendto，内核会隐式绑定并把请求交给路由子系统，
   * 随后读取 RTM_NEWNEIGH 流直到 NLMSG_DONE。
   */
  private fun discoverViaNetlink(targetBytes: ByteArray): String? {
    var fd: java.io.FileDescriptor? = null
    return try {
      fd = Os.socket(
        OsConstants.AF_NETLINK,
        OsConstants.SOCK_RAW,
        OsConstants.NETLINK_ROUTE,
      )
      Os.setsockoptTimeval(
        fd,
        OsConstants.SOL_SOCKET,
        OsConstants.SO_RCVTIMEO,
        StructTimeval.fromMillis(RECV_TIMEOUT_MS),
      )

      val request = buildGetNeighRequest(seq = 1)
      // 空 InetAddress → 内核 sendto 的 sockaddr 为 NULL，未连接 netlink 套接字默认寻址内核。
      Os.sendto(fd, request, 0, request.size, 0, null, 0)

      val buffer = ByteArray(RECV_BUFFER_SIZE)
      var result: String? = null
      var done = false
      var rounds = 0
      while (!done && rounds < MAX_RECV_ROUNDS) {
        // 接收超时（EAGAIN）视为数据流结束，保留已解析到的结果。
        val n = try {
          Os.read(fd, buffer, 0, buffer.size)
        } catch (e: android.system.ErrnoException) {
          if (e.errno == OsConstants.EAGAIN) break else throw e
        }
        if (n <= 0) break

        var pos = 0
        while (pos + NLMSG_HDRLEN <= n) {
          val msgLen = readU32LE(buffer, pos)
          if (msgLen < NLMSG_HDRLEN) {
            done = true
            break
          }
          val msgType = readU16LE(buffer, pos + 4)
          if (msgType == NLMSG_DONE) {
            done = true
            break
          }
          if (msgType == RTM_NEWNEIGH) {
            parseNeighMessage(buffer, pos, msgLen, n, targetBytes)?.let { result = it }
          }
          pos += align4(msgLen)
        }
        rounds++
      }
      result
    } finally {
      if (fd != null) runCatching { Os.close(fd) }
    }
  }

  /** 构造 RTM_GETNEIGH dump 请求：nlmsghdr + ndmsg（仅 family=AF_INET，其余置零）。 */
  private fun buildGetNeighRequest(seq: Int): ByteArray {
    val msg = ByteArray(NLMSG_HDRLEN + NDM_HDRLEN)
    writeU32LE(msg, 0, msg.size)                       // nlmsg_len
    writeU16LE(msg, 4, RTM_GETNEIGH)                  // nlmsg_type
    writeU16LE(msg, 6, NLM_F_REQUEST or NLM_F_DUMP)   // nlmsg_flags
    writeU32LE(msg, 8, seq)                           // nlmsg_seq
    writeU32LE(msg, 12, 0)                            // nlmsg_pid
    msg[NLMSG_HDRLEN] = OsConstants.AF_INET.toByte()  // ndm_family = AF_INET
    return msg
  }

  /** 解析单条 RTM_NEWNEIGH 消息，匹配目标 IPv4 并提取其 MAC。 */
  private fun parseNeighMessage(
    buffer: ByteArray,
    headerPos: Int,
    msgLen: Int,
    readLength: Int,
    targetBytes: ByteArray,
  ): String? {
    var attr = headerPos + NLMSG_HDRLEN + NDM_HDRLEN
    // 声明的消息边界不得越过本次实际读取到的字节数，防止截断消息导致越界。
    val end = minOf(headerPos + msgLen, readLength)

    var dst: ByteArray? = null
    var lladdr: ByteArray? = null
    while (attr + 4 <= end) {
      val attrLen = readU16LE(buffer, attr)
      val attrType = readU16LE(buffer, attr + 2)
      if (attrLen < 4) break
      val payloadStart = attr + 4
      val payloadEnd = attr + attrLen
      if (payloadEnd > end) break

      when (attrType) {
        NDA_DST -> dst = buffer.copyOfRange(payloadStart, payloadEnd)
        NDA_LLADDR -> lladdr = buffer.copyOfRange(payloadStart, payloadEnd)
      }
      attr = align4(payloadEnd)
    }

    if (dst != null && dst.contentEquals(targetBytes) && lladdr != null && lladdr.size == 6) {
      return formatMac(lladdr)
    }
    return null
  }

  // ================= /proc/net/arp 兜底 =================

  /** 轮询读取 /proc/net/arp，返回目标 IP 的完整条目 MAC。 */
  private fun discoverFromArpFile(address: InetAddress): String? {
    val targetIp = address.hostAddress ?: return null
    return discoverFromArpFile(targetIp)
  }

  private fun discoverFromArpFile(targetIp: String): String? {
    if (!ARP_FILE.exists()) return null
    repeat(ARP_POLL_TIMES) { attempt ->
      readArpEntry(targetIp)?.let { return it }
      if (attempt < ARP_POLL_TIMES - 1) Thread.sleep(ARP_POLL_INTERVAL_MS)
    }
    return null
  }

  /** 解析 /proc/net/arp 单次快照，返回目标 IP 的完整条目的 MAC；不存在或未完成时返回 null。 */
  private fun readArpEntry(targetIp: String): String? {
    return ARP_FILE.bufferedReader().use { reader ->
      reader.readLine()
      reader.lineSequence().mapNotNull { line ->
        val parts = line.trim().split(Regex("\\s+"))
        // 列：IP(0)、HW type(1)、Flags(2)、HW address(3)、Mask(4)、Device(5)。
        if (parts.size < 4) return@mapNotNull null
        if (parts[0] != targetIp) return@mapNotNull null
        if (parts[2] != FLAG_COMPLETE) return@mapNotNull null
        parts[3].takeIf { mac -> runCatching { WakeOnLan.parseMac(mac) }.isSuccess }
      }.firstOrNull()
    }
  }

  // ================= 通用辅助 =================

  /** 将 6 字节 MAC 格式化为大写、冒号分隔的字符串。 */
  private fun formatMac(bytes: ByteArray): String =
    bytes.joinToString(":") { "%02X".format(it) }

  /** 将长度向上对齐到 4 字节边界（netlink 对齐规则）。 */
  private fun align4(value: Int): Int = (value + 3) and 3.inv()

  /** 向 [buf] 的 [offset] 写入大端 u16（NetBIOS 报文按网络字节序）。 */
  private fun putU16(buf: ByteArray, offset: Int, value: Int): Int {
    buf[offset] = ((value ushr 8) and 0xFF).toByte()
    buf[offset + 1] = (value and 0xFF).toByte()
    return offset + 2
  }

  /** 读取大端 u16（NetBIOS 报文按网络字节序）。 */
  private fun u16(buf: ByteArray, offset: Int): Int =
    ((buf[offset].toInt() and 0xFF) shl 8) or (buf[offset + 1].toInt() and 0xFF)

  // ---- 小端读写（netlink 用）----

  private fun writeU16LE(buf: ByteArray, offset: Int, value: Int) {
    buf[offset] = (value and 0xFF).toByte()
    buf[offset + 1] = ((value ushr 8) and 0xFF).toByte()
  }

  private fun writeU32LE(buf: ByteArray, offset: Int, value: Int) {
    buf[offset] = (value and 0xFF).toByte()
    buf[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    buf[offset + 2] = ((value ushr 16) and 0xFF).toByte()
    buf[offset + 3] = ((value ushr 24) and 0xFF).toByte()
  }

  private fun readU16LE(buf: ByteArray, offset: Int): Int =
    (buf[offset].toInt() and 0xFF) or ((buf[offset + 1].toInt() and 0xFF) shl 8)

  private fun readU32LE(buf: ByteArray, offset: Int): Int =
    (buf[offset].toInt() and 0xFF) or
      ((buf[offset + 1].toInt() and 0xFF) shl 8) or
      ((buf[offset + 2].toInt() and 0xFF) shl 16) or
      ((buf[offset + 3].toInt() and 0xFF) shl 24)
}
