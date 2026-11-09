package live.mehiz.mpvkt.network.remote

import android.util.Log
import live.mehiz.mpvkt.ui.player.resolveAutoSubtitles
import live.mehiz.mpvkt.ui.player.videoExtensions
import live.mehiz.mpvkt.ui.utils.NaturalSort
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap

data class RemotePlaybackItem(val name: String, val remotePath: String, val streamUrl: String)

/**
 * 一个远程目录的播放清单：[items] 为视频项，[subtitles] 为按自动加载规则发布的字幕项。
 * 字幕代理 URL 与视频共用同一个本地 HTTP 代理，播放期间持续有效。
 */
data class RemotePlaylist(
  val items: List<RemotePlaybackItem>,
  val subtitles: List<RemotePlaybackItem>,
)

class RemoteRuntime(
  private val profileStore: RemoteProfileStore,
) : Closeable {
  private val shareEnumerator = SmbShareEnumerator()
  private val connections = ConcurrentHashMap<ConnectionKey, RemoteClient>()
  @Volatile private var proxy: RemoteHttpProxy? = null
  @Volatile private var thumbnailProxy: RemoteHttpProxy? = null

  fun profiles(): List<RemoteProfile> = profileStore.getProfiles()

  /** 按协议获取（或建立）可复用的远程连接。 */
  fun connect(profile: RemoteProfile): RemoteClient =
    connections.getOrPut(ConnectionKey(profile.id, profile.share)) { createClient(profile) }

  /** 根据协议类型创建对应客户端。 */
  private fun createClient(profile: RemoteProfile): RemoteClient = when (profile.protocol) {
    ProtocolType.FTP -> FtpClient(profile)
    ProtocolType.SFTP -> SftpClient(profile)
    ProtocolType.SMB -> SmbConnection(profile)
    ProtocolType.WEBDAV -> WebDavClient(profile)
  }

  /**
   * 测试连接与认证。SMB 使用无需共享名的轻量认证；其他协议通过建立并立即关闭客户端验证。
   */
  fun authenticate(profile: RemoteProfile, onStage: (SmbAuthStage) -> Unit = {}) {
    try {
      if (profile.protocol == ProtocolType.SMB) {
        SmbConnection.authenticate(profile, onStage)
      } else {
        onStage(SmbAuthStage.CONNECTING)
        onStage(SmbAuthStage.AUTHENTICATING)
        createClient(profile).use { }
      }
    } catch (t: Throwable) {
      Log.w(TAG, "authenticate failed on ${profile.host}:${profile.port}", t)
      throw t
    }
  }

  /** 供编辑器之外调用的连接测试。 */
  fun test(profile: RemoteProfile): Result<Unit> = runCatching { authenticate(profile) }

  /** 枚举可浏览的共享，仅 SMB 协议有意义；其他协议返回空列表。 */
  fun browsableShares(profile: RemoteProfile): List<String> {
    if (profile.protocol != ProtocolType.SMB) return emptyList()
    val enumerated = shareEnumerator.enumerate(profile)
      .map { it.trim() }
      .filterNot { it.isBlank() || it.endsWith("$", ignoreCase = true) }
      .distinctBy(String::lowercase)
    val verified = enumerated.filter { share ->
      runCatching {
        // 仅验证共享根目录：startPath 不一定在每个共享上存在，不应因此隐藏可用共享。
        SmbConnection(profile.copy(share = share)).use { it.list("") }
      }
        .onFailure { Log.w(TAG, "Share '$share' failed verification on ${profile.host}:${profile.port}", it) }
        .isSuccess
    }
    // 验证全部失败（瞬时方言/权限抖动）时回退到原始枚举，避免显示空列表。
    return verified.ifEmpty { enumerated }
  }

  fun list(profile: RemoteProfile, path: String): List<RemoteEntry> = connect(profile).list(path)

  fun playlist(
    profile: RemoteProfile,
    path: String,
    sortOrder: RemoteSortOrder = RemoteSortOrder.NAME_ASC,
    // 调用方依据“自动加载外部字幕”开关传入；关闭时不打开任何字幕远程句柄。
    autoLoadSubtitles: Boolean = true,
  ): RemotePlaylist {
    val directory = path.substringBeforeLast('/', "")
    val comparator = when (sortOrder) {
      RemoteSortOrder.NAME_ASC -> Comparator<RemoteEntry> { left, right -> NaturalSort.compare(left.name, right.name) }
      RemoteSortOrder.NAME_DESC -> Comparator<RemoteEntry> { left, right -> NaturalSort.compare(right.name, left.name) }
      RemoteSortOrder.MODIFIED_ASC -> compareBy<RemoteEntry> { it.modifiedTime }.thenBy(NaturalSort.comparator) { it.name }
      RemoteSortOrder.MODIFIED_DESC -> compareByDescending<RemoteEntry> { it.modifiedTime }.thenByDescending(NaturalSort.comparator) { it.name }
    }
    val entries = list(profile, directory)
    val videoItems = entries
      .asSequence()
      .filter { !it.directory && videoExtensions.contains(it.name.substringAfterLast('.', "").lowercase()) }
      .sortedWith(comparator)
      .map { item ->
        val remoteFile = connect(profile).open(item.path)
        RemotePlaybackItem(item.name, item.path, playbackProxy().publish(remoteFile))
      }
      .toList()

    val fileNames = entries.filter { !it.directory }.map { it.name }
    val currentVideoName = path.substringAfterLast('/')
    val subtitleItems = if (!autoLoadSubtitles) {
      emptyList()
    } else {
      // 与本地浏览器共用同一套判定规则（单视频全量、多视频同名前缀）。
      resolveAutoSubtitles(fileNames, currentVideoName).map { subtitleName ->
        val subtitleEntry = entries.first { !it.directory && it.name == subtitleName }
        val remoteFile = connect(profile).open(subtitleEntry.path)
        RemotePlaybackItem(subtitleName, subtitleEntry.path, playbackProxy().publish(remoteFile))
      }
    }
    return RemotePlaylist(videoItems, subtitleItems)
  }

  fun stream(profile: RemoteProfile, path: String): String {
    return playbackProxy().publish(connect(profile).open(path))
  }

  /** 打开远程文件并通过长期存活的缩略图代理发布。 */
  fun publishThumbnail(profile: RemoteProfile, path: String): String {
    val remoteFile = connect(profile).open(path)
    return thumbnailProxy().publish(remoteFile)
  }

  /** 释放先前发布的缩略图 URL 对应的远程句柄。 */
  fun unpublishThumbnail(url: String) {
    val token = url.substringAfterLast('/')
    thumbnailProxy?.unpublish(token)
  }

  /**
   * 执行一条服务器命令片段。仅 SFTP/SMB 支持，经 SSH exec 通道执行，
   * 返回包含退出码与输出的结果，供 UI 层 Toast 反馈。
   */
  fun executeCommand(profile: RemoteProfile, snippet: CommandSnippet): CommandResult =
    SshCommandExecutor.execute(profile, snippet.command)

  fun forgetProfile(profileId: String) {
    connections.entries
      .filter { it.key.profileId == profileId }
      .forEach { (key, connection) ->
        if (connections.remove(key, connection)) runCatching { connection.close() }
      }
  }

  fun closePlayback() {
    synchronized(this) {
      proxy?.close()
      proxy = null
      connections.values.forEach { runCatching { it.close() } }
      connections.clear()
    }
  }

  override fun close() {
    closePlayback()
    synchronized(this) {
      thumbnailProxy?.let { runCatching { it.close() } }
      thumbnailProxy = null
    }
  }

  @Synchronized
  private fun playbackProxy(): RemoteHttpProxy = proxy ?: RemoteHttpProxy().also { proxy = it }

  @Synchronized
  private fun thumbnailProxy(): RemoteHttpProxy =
    thumbnailProxy ?: RemoteHttpProxy().also { thumbnailProxy = it }

  private data class ConnectionKey(val profileId: String, val share: String)

  private companion object {
    const val TAG = "mpvkt-remote"
  }
}
