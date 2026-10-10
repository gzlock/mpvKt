package live.mehiz.mpvkt.network.smb

import live.mehiz.mpvkt.ui.player.videoExtensions
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap

data class SmbPlaybackItem(val name: String, val remotePath: String, val streamUrl: String)

class SmbRuntime(
  private val profileStore: SmbProfileStore,
) : Closeable {
  private val shareEnumerator = SmbShareEnumerator()
  private val connections = ConcurrentHashMap<ConnectionKey, SmbConnection>()
  @Volatile private var proxy: SmbHttpProxy? = null

  fun profiles(): List<SmbProfile> = profileStore.getProfiles()

  fun connect(profile: SmbProfile): SmbConnection =
    connections.getOrPut(ConnectionKey(profile.id, profile.share)) { SmbConnection(profile) }

  fun authenticate(profile: SmbProfile) = SmbConnection.authenticate(profile)

  /** Kept for callers outside the editor; authentication does not require a selected share. */
  fun test(profile: SmbProfile): Result<Unit> = runCatching { authenticate(profile) }

  fun browsableShares(profile: SmbProfile): List<String> = shareEnumerator.enumerate(profile)
    .filterNot { it.endsWith("$", ignoreCase = true) }
    .filter { share ->
      runCatching {
        SmbConnection(profile.copy(share = share)).use { it.list(profile.startPath) }
      }.isSuccess
    }

  fun list(profile: SmbProfile, path: String): List<SmbEntry> = connect(profile).list(path)

  fun playlist(
    profile: SmbProfile,
    path: String,
    sortOrder: SmbSortOrder = SmbSortOrder.NAME_ASC,
  ): List<SmbPlaybackItem> {
    val directory = path.substringBeforeLast('/', "")
    val comparator = when (sortOrder) {
      SmbSortOrder.NAME_ASC -> compareBy<SmbEntry> { it.name.lowercase() }.thenBy { it.name }
      SmbSortOrder.NAME_DESC -> compareByDescending<SmbEntry> { it.name.lowercase() }.thenByDescending { it.name }
      SmbSortOrder.MODIFIED_ASC -> compareBy<SmbEntry> { it.modifiedTime }.thenBy { it.name.lowercase() }
      SmbSortOrder.MODIFIED_DESC -> compareByDescending<SmbEntry> { it.modifiedTime }.thenBy { it.name.lowercase() }
    }
    return list(profile, directory)
      .asSequence()
      .filter { !it.directory && videoExtensions.contains(it.name.substringAfterLast('.', "").lowercase()) }
      .sortedWith(comparator)
      .map { item ->
        val remoteFile = connect(profile).open(item.path)
        SmbPlaybackItem(item.name, item.path, playbackProxy().publish(remoteFile))
      }
      .toList()
  }

  fun stream(profile: SmbProfile, path: String): String {
    return playbackProxy().publish(connect(profile).open(path))
  }

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
  }

  @Synchronized
  private fun playbackProxy(): SmbHttpProxy = proxy ?: SmbHttpProxy().also { proxy = it }

  private data class ConnectionKey(val profileId: String, val share: String)

}
