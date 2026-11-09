package live.mehiz.mpvkt.network.remote

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import java.util.Properties

/** 远程命令执行结果。 */
data class CommandResult(
  /** 远程进程退出码，0 表示成功。 */
  val exitCode: Int,
  /** 合并后的标准输出与错误输出。 */
  val output: String,
) {
  val success: Boolean get() = exitCode == 0
}

/**
 * 通过 SSH exec 通道在远程主机执行命令。
 *
 * - SFTP 连接复用自身端口；
 * - SMB 协议本身无远程执行能力，经同主机的 SSH 通道执行（[RemoteProfile.commandPort]，默认 22）。
 */
object SshCommandExecutor {
  /**
   * 执行 [command]，阻塞直到远端进程结束并返回退出码与输出。
   * 在调用方线程（IO 线程）使用，不要在主线程调用。
   */
  fun execute(profile: RemoteProfile, command: String): CommandResult {
    val port = if (profile.protocol == ProtocolType.SFTP) {
      profile.port
    } else {
      profile.commandPort?.takeIf { it > 0 } ?: DEFAULT_SSH_PORT
    }
    val session = JSch().getSession(
      profile.username.ifBlank { DEFAULT_USER },
      profile.host,
      port,
    )
    session.setPassword(profile.password)
    session.setConfig(Properties().apply {
      // 本地测试环境不预置 known_hosts，跳过主机密钥校验。
      put("StrictHostKeyChecking", "no")
    })
    session.timeout = CONNECT_TIMEOUT_MS
    session.connect()
    try {
      val exec = session.openChannel("exec") as ChannelExec
      exec.setCommand(command)
      // 并发排空 stdout/stderr，避免管道缓冲区写满导致命令阻塞。
      val stdout = drainAsync(exec.inputStream)
      val stderr = drainAsync(exec.errStream)
      exec.connect(CONNECT_TIMEOUT_MS)
      // java.util.concurrent.Future 用 get() 阻塞取结果（join 是 CompletableFuture 的方法）。
      val out = stdout.get()
      val err = stderr.get()
      // 等待通道关闭后再读取退出状态。
      while (!exec.isClosed) Thread.sleep(POLL_INTERVAL_MS)
      val exitCode = exec.exitStatus
      exec.disconnect()
      val merged = buildString {
        if (out.isNotBlank()) append(out.trim())
        if (err.isNotBlank()) {
          if (isNotEmpty()) append('\n')
          append(err.trim())
        }
      }
      return CommandResult(exitCode, merged)
    } finally {
      session.disconnect()
    }
  }

  /** 在后台线程读取流内容，返回可通过 get() 阻塞取结果的 Future。 */
  private fun drainAsync(stream: java.io.InputStream): java.util.concurrent.Future<String> {
    val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
    return executor.submit<String> {
      stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }.also { executor.shutdown() }
  }

  private const val CONNECT_TIMEOUT_MS = 15_000
  private const val POLL_INTERVAL_MS = 50L
  private const val DEFAULT_SSH_PORT = 22
  private const val DEFAULT_USER = "root"
}
