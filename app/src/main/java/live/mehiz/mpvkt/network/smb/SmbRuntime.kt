package live.mehiz.mpvkt.network.smb

import android.util.Log
import live.mehiz.mpvkt.ui.player.videoExtensions
import live.mehiz.mpvkt.ui.utils.NaturalSort
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap

data class SmbPlaybackItem(val name: String, val remotePath: String, val streamUrl: String)

class SmbRuntime(
  private val profileStore: SmbProfileStore,
) : Closeable {
  private val shareEnumerator = SmbShareEnumerator()
  private val connections = ConcurrentHashMap<ConnectionKey, SmbConnection>()
  @Volatile private var proxy: SmbHttpProxy? = null
  @Volatile private var thumbnailProxy: SmbHttpProxy? = null

  fun profiles(): List<SmbProfile> = profileStore.getProfiles()

  fun connect(profile: SmbProfile): SmbConnection =
    connections.getOrPut(ConnectionKey(profile.id, profile.share)) { SmbConnection(profile) }

  fun authenticate(profile: SmbProfile, onStage: (SmbAuthStage) -> Unit = {}) =
    try {
      SmbConnection.authenticate(profile, onStage)
    } catch (t: Throwable) {
      Log.w(TAG, "SMB authenticate failed on ${profile.host}:${profile.port}", t)
      throw t
    }

  /** Kept for callers outside the editor; authentication does not require a selected share. */
  fun test(profile: SmbProfile): Result<Unit> = runCatching { authenticate(profile) }

  fun browsableShares(profile: SmbProfile): List<String> {
    val enumerated = shareEnumerator.enumerate(profile)
      .map { it.trim() }
      .filterNot { it.isBlank() || it.endsWith("$", ignoreCase = true) }
      .distinctBy(String::lowercase)
    val verified = enumerated.filter { share ->
      runCatching {
        // Verify the share root only: the configured startPath may not exist on every
        // share and must not be a reason to hide an otherwise available share.
        SmbConnection(profile.copy(share = share)).use { it.list("") }
      }
        .onFailure { Log.w(TAG, "Share '$share' failed verification on ${profile.host}:${profile.port}", it) }
        .isSuccess
    }
    // If verification rejected everything (transient dialect/permission hiccup), fall back
    // to the raw enumerated list instead of showing an empty share list.
    return verified.ifEmpty { enumerated }
  }

  fun list(profile: SmbProfile, path: String): List<SmbEntry> = connect(profile).list(path)

  fun playlist(
    profile: SmbProfile,
    path: String,
    sortOrder: SmbSortOrder = SmbSortOrder.NAME_ASC,
  ): List<SmbPlaybackItem> {
    val directory = path.substringBeforeLast('/', "")
    val comparator = when (sortOrder) {
      SmbSortOrder.NAME_ASC -> Comparator<SmbEntry> { left, right -> NaturalSort.compare(left.name, right.name) }
      SmbSortOrder.NAME_DESC -> Comparator<SmbEntry> { left, right -> NaturalSort.compare(right.name, left.name) }
      SmbSortOrder.MODIFIED_ASC -> compareBy<SmbEntry> { it.modifiedTime }.thenBy(NaturalSort.comparator) { it.name }
      SmbSortOrder.MODIFIED_DESC -> compareByDescending<SmbEntry> { it.modifiedTime }.thenByDescending(NaturalSort.comparator) { it.name }
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

  /** Opens a remote file and publishes it through the long-lived thumbnail proxy. */
  fun publishThumbnail(profile: SmbProfile, path: String): String {
    val remoteFile = connect(profile).open(path)
    return thumbnailProxy().publish(remoteFile)
  }

  /** Releases the remote handle backing a previously published thumbnail URL. */
  fun unpublishThumbnail(url: String) {
    val token = url.substringAfterLast('/')
    thumbnailProxy?.unpublish(token)
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
    synchronized(this) {
      thumbnailProxy?.let { runCatching { it.close() } }
      thumbnailProxy = null
    }
  }

  @Synchronized
  private fun playbackProxy(): SmbHttpProxy = proxy ?: SmbHttpProxy().also { proxy = it }

  @Synchronized
  private fun thumbnailProxy(): SmbHttpProxy =
    thumbnailProxy ?: SmbHttpProxy().also { thumbnailProxy = it }

  private data class ConnectionKey(val profileId: String, val share: String)

  private companion object {
    const val TAG = "mpvkt-smb"
  }
}
